package com.xinhongru.cms.template;

import com.xinhongru.cms.content.record.SinglePageFieldValues;
import com.xinhongru.cms.template.SiteModelNavigationService.Scope;
import com.xinhongru.content.model.ContentExtensionFields;
import com.xinhongru.content.model.ContentFieldConfigDigest;
import com.xinhongru.content.model.ContentFieldDefinition;
import com.xinhongru.content.model.ContentModelCodec;
import com.xinhongru.entity.OneshotEntity;
import com.xinhongru.mapper.OneShotMapper;
import com.xinhongru.service.oneshot.OneShotService;
import com.xinhongru.site.mapper.SiteProjectMapper;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import java.util.*;
import java.util.function.Consumer;

/** 公共来源仅填入原生草稿单页的空字段，业务值仍由原生单页维护。 */
@Service
public class PublicSharedContentSeedService {
    public record Value(
            String sourceId,
            int start,
            int end,
            String modelKey,
            String fieldKey,
            Long menuId,
            String fieldName,
            String rawValue,
            String status,
            String reason) {}

    public record Preview(String digest, List<Value> values) {}

    private record Prepared(Preview preview, Map<Integer, Map<String, Object>> writes) {}

    private final PublicSharedContentCompiler compiler;
    private final SiteModelEvidenceService evidence;
    private final OneShotMapper menus;
    private final SiteProjectMapper projects;
    private final SinglePageFieldValues fields;
    private final OneShotService nativeMenus;
    private final ObjectMapper json = new ObjectMapper();

    public PublicSharedContentSeedService(
            PublicSharedContentCompiler compiler,
            SiteModelEvidenceService evidence,
            OneShotMapper menus,
            SiteProjectMapper projects,
            SinglePageFieldValues fields,
            OneShotService nativeMenus) {
        this.compiler = compiler;
        this.evidence = evidence;
        this.menus = menus;
        this.projects = projects;
        this.fields = fields;
        this.nativeMenus = nativeMenus;
    }

    public Preview preview(
            Scope scope,
            String planId,
            PublicSharedContentCompiler.Request request,
            Consumer<Integer> authorize) {
        return prepare(scope, planId, request, authorize, false).preview();
    }

    @Transactional
    public Preview confirm(
            Scope scope,
            String planId,
            PublicSharedContentCompiler.Request request,
            HttpServletRequest http,
            Consumer<Integer> authorize) {
        if (!Boolean.TRUE.equals(request.confirmed()) || request.digest() == null)
            throw new IllegalArgumentException("请先预览并确认公共信息初值");
        var project = projects.selectByIdForUpdate(scope.siteId());
        if (project == null
                || !Objects.equals(project.getRootMenuId(), scope.rootMenuId())
                || !Objects.equals(project.getProjectCode(), scope.code())
                || !Objects.equals(project.getUiPackageVersion(), scope.packageVersion())
                || Integer.valueOf(1).equals(project.getDeleted()))
            throw new IllegalArgumentException("站点或原包已变化");
        var prepared = prepare(scope, planId, request, authorize, true);
        if (prepared.writes().isEmpty()) return prepared.preview();
        if (!prepared.preview().digest().equals(request.digest()))
            throw new IllegalArgumentException("公共字段或当前值已变化，请重新预览；未写入初值");
        for (var entry : prepared.writes().entrySet()) {
            authorize.accept(entry.getKey());
            var current = menus.lockFieldConfiguration(scope.siteId(), entry.getKey().longValue());
            var update =
                    new OneshotEntity()
                            .setId(current.getId())
                            .setExtensionFields(current.getExtensionFields());
            fields.apply(
                    update,
                    current,
                    ContentModelCodec.read(current.getMenuField()),
                    entry.getValue(),
                    scope.siteId());
            var result = nativeMenus.update(update, http);
            if (result == null || !Boolean.TRUE.equals(result.getSuccess()))
                throw new IllegalStateException("原生公共信息保存失败，事务已回滚");
        }
        var saved = prepare(scope, planId, request, authorize, true).preview();
        for (var value : saved.values()) {
            var attempted = prepared.writes().get(Math.toIntExact(value.menuId()));
            if (attempted != null
                    && attempted.containsKey(value.fieldName())
                    && !"PRESERVED".equals(value.status()))
                throw new IllegalStateException("公共信息初值未实际写入，事务已回滚，请重试");
        }
        return saved;
    }

    private Prepared prepare(
            Scope scope,
            String planId,
            PublicSharedContentCompiler.Request request,
            Consumer<Integer> authorize,
            boolean lock) {
        var mapping = compiler.previewSeedMapping(scope, planId, request);
        var page =
                evidence.sharedValues(scope.code(), scope.packageVersion(), request.sourcePath());
        if (!Objects.equals(page.digest(), request.sourceDigest()))
            throw new IllegalArgumentException("公共来源在读取期间变化，请重新预览");
        var sources = new HashMap<String, SiteModelEvidenceService.SharedValue>();
        page.values().forEach(value -> sources.put(value.id(), value));
        var owners = new TreeMap<Integer, OneshotEntity>();
        for (var id :
                mapping.bindings().stream()
                        .map(PublicSharedContentCompiler.Binding::menuId)
                        .distinct()
                        .sorted()
                        .toList()) {
            int key = Math.toIntExact(id);
            authorize.accept(key);
            var owner =
                    lock
                            ? menus.lockFieldConfiguration(scope.siteId(), id)
                            : menus.selectPublicBindingMenu(scope.siteId(), id);
            if (owner == null
                    || !Objects.equals(owner.getId(), key)
                    || !Objects.equals(owner.getSiteProjectId(), scope.siteId())
                    || !Objects.equals(owner.getSiteRootId(), scope.rootMenuId())
                    || Objects.equals(owner.getId(), scope.rootMenuId())
                    || Boolean.TRUE.equals(owner.getIsDeleted())
                    || !Integer.valueOf(0).equals(owner.getPageType())
                    || !Boolean.TRUE.equals(owner.getHasManageChannel()))
                throw new IllegalArgumentException("公共信息目标不是本站可维护单页");
            owners.put(key, owner);
        }
        var rows = new ArrayList<Value>();
        var writes = new TreeMap<Integer, Map<String, Object>>();
        var digestValues = new ArrayList<Object>();
        var selectedValues = new HashMap<String, String>();
        for (var binding : mapping.bindings()) {
            var source = sources.get(binding.sourceId());
            if (source == null) throw new IllegalArgumentException("公共来源已变化");
            var owner = owners.get(Math.toIntExact(binding.menuId()));
            var definition =
                    ContentModelCodec.read(owner.getMenuField()).stream()
                            .filter(
                                    field ->
                                            binding.fieldName().equals(field.getFieldName())
                                                    && binding.fieldType()
                                                            .equals(field.getFieldType())
                                                    && ContentFieldDefinition.enabled(
                                                            field.getIsOpen()))
                            .findFirst()
                            .orElseThrow();
            String initial =
                    "Image".equals(binding.fieldType())
                            ? source.targetPath() == null ? "" : "ui:/" + source.targetPath()
                            : source.rawValue().substring(binding.start(), binding.end()).strip();
            String fieldIdentity = binding.menuId() + "/" + binding.fieldName();
            var previous = selectedValues.putIfAbsent(fieldIdentity, initial);
            if (previous != null && !previous.equals(initial))
                throw new IllegalArgumentException("同一公共字段选择了不同初值，请先核对");
            String current = read(owner, binding.fieldName());
            String status = "READY", reason = "仅填充空字段";
            if (current != null && !current.isBlank()) {
                status = "PRESERVED";
                reason = "字段已有内容，保持原值";
            } else if (owner.getStatus() != null && owner.getStatus() != 0) {
                status = "PENDING";
                reason = "仅草稿单页可以初始化，请先核对发布状态";
            } else if (ContentFieldDefinition.enabled(definition.getIsReadOnly())) {
                status = "PENDING";
                reason = "字段为只读";
            } else if (initial.isBlank()) {
                status = "PENDING";
                reason = "原值为空或图片无法解析为当前包资源";
            } else if ("SingleLine".equals(binding.fieldType())
                    && !ContentExtensionFields.isName(binding.fieldName())
                    && initial.length()
                            > Math.min(
                                    250,
                                    definition.getMaxLength() == null
                                                    || definition.getMaxLength() <= 0
                                            ? 250
                                            : definition.getMaxLength())) {
                status = "PENDING";
                reason = "原文超过字段容量，请重新选择范围";
            }
            rows.add(
                    new Value(
                            binding.sourceId(),
                            binding.start(),
                            binding.end(),
                            binding.modelKey(),
                            binding.fieldKey(),
                            binding.menuId(),
                            binding.fieldName(),
                            initial,
                            status,
                            reason));
            digestValues.add(
                    List.of(
                            fieldIdentity,
                            ContentFieldConfigDigest.of(owner),
                            Objects.toString(current, ""),
                            Objects.toString(owner.getStatus(), "")));
            if ("READY".equals(status))
                writes.computeIfAbsent(owner.getId(), key -> new LinkedHashMap<>())
                        .put(binding.fieldName(), initial);
        }
        // 在预览时也执行正式字段/资源/必填校验，但仅作用于内存副本。
        for (var entry : writes.entrySet()) {
            var owner = owners.get(entry.getKey());
            var copy = new OneshotEntity();
            BeanUtils.copyProperties(owner, copy);
            fields.apply(
                    copy,
                    owner,
                    ContentModelCodec.read(owner.getMenuField()),
                    entry.getValue(),
                    scope.siteId());
        }
        String digest =
                PublicBindingApplication.digest(
                        json.writeValueAsString(List.of(mapping.digest(), rows, digestValues)));
        return new Prepared(new Preview(digest, List.copyOf(rows)), writes);
    }

    private static String read(OneshotEntity owner, String name) {
        Object value;
        if (ContentExtensionFields.isName(name)) value = owner.getExtensionValues().get(name);
        else
            try {
                var field = com.xinhongru.common.reflect.EntityFields.find(owner.getClass(), name);
                field.setAccessible(true);
                value = field.get(owner);
            } catch (ReflectiveOperationException error) {
                throw new IllegalArgumentException("公共字段无法读取", error);
            }
        if (value == null || value instanceof String) return (String) value;
        throw new IllegalArgumentException("公共字段必须为文字或图片引用");
    }
}
