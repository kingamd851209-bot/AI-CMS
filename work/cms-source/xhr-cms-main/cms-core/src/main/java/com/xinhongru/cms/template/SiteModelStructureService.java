package com.xinhongru.cms.template;

import com.xinhongru.cms.template.SiteModelPlanService.Field;
import com.xinhongru.cms.template.SiteModelPlanService.Model;
import com.xinhongru.cms.template.SiteModelPlanService.Plan;
import com.xinhongru.content.model.ContentExtensionFields;
import com.xinhongru.content.model.ContentFieldCompatibility;
import com.xinhongru.content.model.ContentFieldConfigDigest;
import com.xinhongru.content.model.ContentFieldDefinition;
import com.xinhongru.content.model.ContentModelCodec;
import com.xinhongru.content.model.ContentReferences;
import com.xinhongru.entity.OneshotEntity;
import com.xinhongru.mapper.OneShotMapper;
import com.xinhongru.requestDto.fieldsetting.SetFieldDto;
import com.xinhongru.responseVo.ResultVO;
import com.xinhongru.responseVo.oneshot.EditOneshotDto;
import com.xinhongru.service.fieldsetting.FieldSettingService;
import com.xinhongru.service.oneshot.OneShotService;
import com.xinhongru.site.mapper.SiteProjectMapper;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.BeanUtils;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class SiteModelStructureService {
    private final SiteModelPlanService plans;
    private final SiteModelNavigationService navigation;
    private final OneShotMapper menus;
    private final SiteProjectMapper projects;
    private final OneShotService menuService;
    private final FieldSettingService fields;
    private final TransactionTemplate transactions;

    public SiteModelStructureService(
            SiteModelPlanService plans,
            SiteModelNavigationService navigation,
            OneShotMapper menus,
            SiteProjectMapper projects,
            OneShotService menuService,
            FieldSettingService fields,
            PlatformTransactionManager transactionManager) {
        this.plans = plans;
        this.navigation = navigation;
        this.menus = menus;
        this.projects = projects;
        this.menuService = menuService;
        this.fields = fields;
        this.transactions = new TransactionTemplate(transactionManager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public record Scope(Long siteId, String code, String packageVersion, Integer rootMenuId) {}

    public record Apply(long revision, String modelKey, Integer parentMenuId) {}

    public record OwnedInstance(Integer parentMenuId, Integer menuId) {}

    public record ReferenceScope(
            Integer menuId,
            String fieldName,
            List<Integer> currentMenuIds,
            List<Integer> targetMenuIds,
            String query,
            String previewDigest) {}

    public ReferenceScope referenceScope(
            Scope scope,
            String planId,
            long revision,
            String modelKey,
            String fieldKey,
            Integer menuId,
            Runnable check) {
        synchronized (plans) {
            check.run();
            var plan =
                    plans.confirmed(
                            scope.siteId(), scope.code(), scope.packageVersion(), planId, revision);
            var result =
                    referenceScope(
                            scope,
                            plan,
                            modelKey,
                            fieldKey,
                            menuId,
                            id -> menus.selectPublicBindingMenu(scope.siteId(), id.longValue()));
            check.run();
            return result;
        }
    }

    public ReferenceScope applyReferenceScope(
            Scope scope,
            String planId,
            long revision,
            String modelKey,
            String fieldKey,
            Integer menuId,
            String digest,
            boolean confirmed,
            Runnable check) {
        if (!confirmed || digest == null) throw new IllegalArgumentException("请先核对并确认引用范围");
        synchronized (plans) {
            return Objects.requireNonNull(
                    transactions.execute(
                            status -> {
                                check.run();
                                lockProject(scope);
                                var plan =
                                        plans.confirmed(
                                                scope.siteId(),
                                                scope.code(),
                                                scope.packageVersion(),
                                                planId,
                                                revision);
                                var result =
                                        referenceScope(
                                                scope,
                                                plan,
                                                modelKey,
                                                fieldKey,
                                                menuId,
                                                id -> lockMenu(scope, id));
                                if (!digest.equals(result.previewDigest()))
                                    throw conflict("引用字段或审核栏目范围已变化，请重新预览");
                                if (result.fieldName() == null) throw conflict("请先应用有序内容引用字段");
                                if (result.currentMenuIds().equals(result.targetMenuIds()))
                                    return result;
                                var menu = lockMenu(scope, menuId);
                                if (menu.getDetailPageTemplate() != null
                                        && !menu.getDetailPageTemplate().isBlank())
                                    throw conflict("请先撤回已有详情应用并解除栏目详情模板配置，再更新引用范围；更新后重新生成字段绑定计划");
                                var raw =
                                        com.alibaba.fastjson.JSON.parseArray(menu.getModelField());
                                for (int i = 0; i < raw.size(); i++) {
                                    var item = raw.getJSONObject(i);
                                    if (result.fieldName().equals(item.getString("fieldName")))
                                        item.put("defaultValue", result.query());
                                }
                                if (menus.updateFieldConfig(
                                                menuId,
                                                menu.getMenuField(),
                                                raw.toJSONString(),
                                                menu.getModelList(),
                                                menu.getModelSearch())
                                        != 1) throw conflict("引用字段已变化，未保存");
                                check.run();
                                return referenceScope(
                                        scope,
                                        plan,
                                        modelKey,
                                        fieldKey,
                                        menuId,
                                        id -> lockMenu(scope, id));
                            }));
        }
    }

    private ReferenceScope referenceScope(
            Scope scope,
            Plan plan,
            String modelKey,
            String fieldKey,
            Integer menuId,
            Function<Integer, OneshotEntity> readMenu) {
        var owner = model(plan, modelKey);
        var targetKey = regionReferences(plan, owner).get(fieldKey);
        if (targetKey == null
                || menuId == null
                || !ownerMenuIds(scope, plan, owner).contains(menuId))
            throw conflict("请选择已审核详情主体及其引用字段");
        var target = model(plan, targetKey);
        var menu = readMenu.apply(menuId);
        var allowed = referenceTargetIds(scope, plan, target, readMenu);
        if (menu == null
                || !scope.siteId().equals(menu.getSiteProjectId())
                || !scope.rootMenuId().equals(menu.getSiteRootId())) throw conflict("详情主体栏目已变化");
        var planned =
                owner.fields().stream()
                        .filter(field -> field.key().equals(fieldKey))
                        .findFirst()
                        .orElseThrow();
        var current = List.<Integer>of();
        if (planned.fieldName() != null) {
            var definition = plannedField(ContentModelCodec.read(menu.getModelField()), planned);
            if (!ContentReferences.applies(definition)
                    || !Boolean.TRUE.equals(definition.getInputMultiple()))
                throw conflict("有序内容引用字段配置已变化");
            current = ContentReferences.menuIds(definition);
            if (!current.contains(Math.toIntExact(target.menuId()))
                    || !allowed.containsAll(current)) throw conflict("已配置引用的栏目已超出已审核且字段兼容的目标范围");
        }
        String query = referenceQuery(allowed);
        String fingerprint =
                stableId(
                        plan.id()
                                + ":"
                                + plan.revision()
                                + ":"
                                + menuId
                                + ":"
                                + fieldKey
                                + ":"
                                + ContentFieldConfigDigest.of(menu)
                                + ":"
                                + query);
        return new ReferenceScope(
                menuId, planned.fieldName(), current, allowed, query, fingerprint);
    }

    public List<Integer> referenceTargetIds(
            Scope scope,
            String planId,
            String modelKey,
            String fieldKey,
            String fieldName,
            Integer menuId) {
        synchronized (plans) {
            var plan = plans.get(scope.siteId(), scope.code(), scope.packageVersion(), planId);
            if (!"CONFIRMED".equals(plan.status())) return List.of();
            var owner = model(plan, modelKey);
            var targetKey = regionReferences(plan, owner).get(fieldKey);
            if (targetKey == null
                    || !ownerMenuIds(scope, plan, owner).contains(menuId)
                    || owner.fields().stream()
                            .noneMatch(
                                    field ->
                                            field.key().equals(fieldKey)
                                                    && Objects.equals(
                                                            field.fieldName(), fieldName)))
                return List.of();
            return referenceTargetIds(
                    scope,
                    plan,
                    model(plan, targetKey),
                    id -> menus.selectPublicBindingMenu(scope.siteId(), id.longValue()));
        }
    }

    private List<Integer> referenceTargetIds(
            Scope scope, Plan plan, Model target, Function<Integer, OneshotEntity> readMenu) {
        if (target.menuId() == null || !"LIST".equals(target.kind()))
            throw conflict("请先确认引用目标模型的实际列表栏目");
        var mapped =
                target.fields().stream()
                        .filter(field -> field.fieldName() != null && !"Sort".equals(field.type()))
                        .toList();
        Integer canonicalId = Math.toIntExact(target.menuId());
        var approved = ownerMenuIds(scope, plan, target);
        var canonical = approved.contains(canonicalId) ? readMenu.apply(canonicalId) : null;
        if (!referenceMenu(scope, canonical)) throw conflict("引用目标代表栏目归属或管理状态已变化");
        var baseline = ContentModelCodec.read(canonical.getModelField());
        var result =
                approved.stream()
                        .filter(
                                id -> {
                                    var menu =
                                            canonicalId.equals(id) ? canonical : readMenu.apply(id);
                                    if (!referenceMenu(scope, menu)) return false;
                                    var definitions =
                                            canonicalId.equals(id)
                                                    ? baseline
                                                    : ContentModelCodec.read(menu.getModelField());
                                    return mapped.stream()
                                            .allMatch(
                                                    field ->
                                                            compatibleReferenceField(
                                                                    plannedField(baseline, field),
                                                                    plannedField(
                                                                            definitions, field)));
                                })
                        .toList();
        if (!result.contains(canonicalId)) throw conflict("引用目标代表栏目与审核字段不兼容");
        return result;
    }

    private static boolean referenceMenu(Scope scope, OneshotEntity menu) {
        return menu != null
                && scope.siteId().equals(menu.getSiteProjectId())
                && scope.rootMenuId().equals(menu.getSiteRootId())
                && Integer.valueOf(1).equals(menu.getPageType())
                && !Boolean.TRUE.equals(menu.getIsSubMenu())
                && !Boolean.TRUE.equals(menu.getIsDeleted())
                && Boolean.TRUE.equals(menu.getHasManageChannel());
    }

    private static boolean compatibleReferenceField(
            ContentFieldDefinition baseline, ContentFieldDefinition candidate) {
        if (baseline == null || candidate == null) return false;
        if (baseline == candidate) return true;
        if (!Objects.toString(baseline.getInputType(), "")
                        .equals(Objects.toString(candidate.getInputType(), ""))
                || Boolean.TRUE.equals(baseline.getInputMultiple())
                        != Boolean.TRUE.equals(candidate.getInputMultiple())
                || ContentFieldDefinition.enabled(baseline.getIsNumber())
                        != ContentFieldDefinition.enabled(candidate.getIsNumber())) return false;
        if (ContentExtensionFields.isName(baseline.getFieldName())) {
            try {
                ContentFieldCompatibility.validateExtensionChange(baseline, candidate);
            } catch (IllegalArgumentException incompatible) {
                return false;
            }
        }
        return true;
    }

    private static ContentFieldDefinition plannedField(
            List<ContentFieldDefinition> definitions, Field planned) {
        return definitions.stream()
                .filter(
                        field ->
                                planned.fieldName().equals(field.getFieldName())
                                        && planned.type().equals(field.getFieldType())
                                        && ContentFieldDefinition.enabled(field.getIsOpen())
                                        && !ContentFieldDefinition.enabled(field.getIsReadOnly()))
                .findFirst()
                .orElse(null);
    }

    private static String referenceQuery(List<Integer> menuIds) {
        if (menuIds.size() > 100) throw new IllegalArgumentException("关联来源最多100项");
        return "{$" + menuIds.stream().map(String::valueOf).collect(Collectors.joining(",")) + "_title_id$}";
    }

    public List<OwnedInstance> ownedInstances(
            Scope scope, String planId, long revision, String modelKey, Runnable check) {
        synchronized (plans) {
            check.run();
            var plan =
                    plans.confirmed(
                            scope.siteId(), scope.code(), scope.packageVersion(), planId, revision);
            var child = model(plan, modelKey);
            var owner = ownedOwner(plan, child);
            if (owner == null || owner.menuId() == null) {
                check.run();
                return List.of();
            }
            var siteMenus = menus.selectActiveSiteModelMenus(scope.siteId());
            var canonical = canonicalOwnedMenu(scope, child, owner);
            var result =
                    ownerMenuIds(scope, plan, owner).stream()
                            .map(
                                    parentId -> {
                                        var instance =
                                                existingOwnedMenu(
                                                        scope, child, owner, parentId, canonical,
                                                        siteMenus);
                                        return new OwnedInstance(
                                                parentId,
                                                instance == null ? null : instance.getId());
                                    })
                            .toList();
            check.run();
            return result;
        }
    }

    public Integer ownedMenuId(
            Scope scope,
            String planId,
            long revision,
            String modelKey,
            Integer parentMenuId,
            Runnable check) {
        synchronized (plans) {
            check.run();
            var plan =
                    plans.confirmed(
                            scope.siteId(), scope.code(), scope.packageVersion(), planId, revision);
            var result = ownedMenuId(scope, plan, model(plan, modelKey), parentMenuId);
            check.run();
            return result;
        }
    }

    Integer ownedMenuId(Scope scope, Plan plan, Model child, Integer parentMenuId) {
        var owner = ownedOwner(plan, child);
        if (owner == null
                || owner.menuId() == null
                || parentMenuId == null
                || !ownerMenuIds(scope, plan, owner).contains(parentMenuId))
            throw conflict("所属条目的父栏目必须是已审核的详情主体栏目");
        var instance =
                existingOwnedMenu(
                        scope,
                        child,
                        owner,
                        parentMenuId,
                        canonicalOwnedMenu(scope, child, owner),
                        menus.selectActiveSiteModelMenus(scope.siteId()));
        return instance == null ? null : instance.getId();
    }

    public record Supplement(
            long revision,
            String modelKey,
            String beforeDigest,
            String afterDigest,
            boolean confirmed) {}

    public record SupplementPreview(
            Integer menuId, String beforeDigest, String afterDigest, List<Field> additions) {}

    public record SharedFields(long revision, String modelKey, Integer menuId, String beforeDigest,
                               String afterDigest, boolean confirmed) {}

    public SupplementPreview sharedFields(Scope scope, String planId, SharedFields input, Runnable check) {
        synchronized (plans) {
            check.run();
            if (input == null || input.menuId() == null) throw new IllegalArgumentException("请选择共享模型的目标栏目");
            var plan = plans.confirmed(scope.siteId(), scope.code(), scope.packageVersion(), planId, input.revision());
            var model = model(plan, input.modelKey());
            if (!"LIST".equals(model.kind()) || model.menuId() == null
                    || model.menuId().equals(input.menuId().longValue())
                    || !ownerMenuIds(scope, plan, model).contains(input.menuId()))
                throw conflict("目标栏目未审核为此模型的独立列表");
            if (!input.confirmed()) return sharedFields(scope, model,
                    menus.selectPublicBindingMenu(scope.siteId(), model.menuId()),
                    menus.selectPublicBindingMenu(scope.siteId(), input.menuId().longValue()), input);
            return transactions.execute(status -> {
                lockProject(scope);
                check.run();
                var original = lockMenu(scope, Math.toIntExact(model.menuId()));
                var target = lockMenu(scope, input.menuId());
                var result = sharedFields(scope, model, original, target, input);
                check.run();
                return result;
            });
        }
    }

    private SupplementPreview sharedFields(Scope scope, Model model, OneshotEntity original,
            OneshotEntity target, SharedFields input) {
        if (!referenceMenu(scope, original) || !referenceMenu(scope, target))
            throw conflict("共享模型的两个栏目须为本站正常独立列表");
        String before = ContentFieldConfigDigest.of(target);
        var originalFields = ContentModelCodec.read(original.getModelField());
        var targetFields = ContentModelCodec.read(target.getModelField());
        var additional = new ArrayList<Field>();
        var definitions = new ArrayList<ContentFieldDefinition>();
        for (var planned : model.fields()) {
            if (!ContentExtensionFields.isName(planned.fieldName())) continue;
            var definition = plannedField(originalFields, planned);
            if (definition == null || ContentReferences.applies(definition)) continue;
            var sameSlot = targetFields.stream().filter(field -> planned.fieldName().equals(field.getFieldName())).findFirst();
            if (sameSlot.isPresent()) {
                if (!compatibleReferenceField(definition, plannedField(targetFields, planned)))
                    throw conflict("目标栏目的同名字段槽位已有不同配置：" + planned.name());
                continue;
            }
            if (targetFields.stream().anyMatch(field -> Objects.equals(definition.getTitle(), field.getTitle())))
                throw conflict("目标栏目已使用同名业务字段，请先核对：" + planned.name());
            additional.add(planned);
            definitions.add(definition);
        }
        String after = definitions.isEmpty() ? before : ContentFieldConfigDigest.of(appendFields(target, definitions));
        if (input.confirmed() && !(definitions.isEmpty() && before.equals(input.afterDigest()))) {
            if (!before.equals(input.beforeDigest()) || !after.equals(input.afterDigest()))
                throw conflict("栏目字段已变化，请重新核对共享字段方案");
            if (!definitions.isEmpty() && !fields.appendModelFields(scope.siteId(), target.getId(), before, definitions))
                throw conflict("栏目已变化，未同步共享字段");
            if (!after.equals(ContentFieldConfigDigest.of(lockMenu(scope, target.getId()))))
                throw new IllegalStateException("共享字段写入后回读与审核不一致");
        }
        return new SupplementPreview(target.getId(), before, after, List.copyOf(additional));
    }

    public SupplementPreview previewSupplement(
            Scope scope, String planId, Supplement input, Runnable check) {
        check.run();
        var plan =
                plans.confirmed(
                        scope.siteId(),
                        scope.code(),
                        scope.packageVersion(),
                        planId,
                        input.revision());
        var model = model(plan, input.modelKey());
        int menuId = supplementMenuId(scope, model);
        var menu = menus.selectPublicBindingMenu(scope.siteId(), (long) menuId);
        var additions = additions(plan, model);
        var definitions = supplementDefinitions(scope, plan, model, additions);
        validateSupplementMenu(scope, menu, model);
        var slots =
                additions.stream()
                        .map(Field::fieldName)
                        .collect(java.util.stream.Collectors.toSet());
        boolean recovering =
                ContentModelCodec.read(supplementRaw(menu)).stream()
                        .anyMatch(field -> slots.contains(field.getFieldName()));
        var original = recovering ? beforeSupplement(menu, definitions) : menu;
        var updated = recovering ? menu : appendFields(menu, definitions);
        check.run();
        return new SupplementPreview(
                menuId,
                ContentFieldConfigDigest.of(original),
                ContentFieldConfigDigest.of(updated),
                additions);
    }

    public Applied applySupplement(Scope scope, String planId, Supplement input, Runnable check) {
        if (!input.confirmed()
                || input.beforeDigest() == null
                || input.afterDigest() == null
                || input.beforeDigest().equals(input.afterDigest()))
            throw new IllegalArgumentException("请核对待增补字段及前后配置摘要");
        synchronized (plans) {
            check.run();
            var head = plans.get(scope.siteId(), scope.code(), scope.packageVersion(), planId);
            if (head.revision() != input.revision() && head.revision() != input.revision() + 1)
                throw staleRevision();
            plans.confirmed(
                    scope.siteId(), scope.code(), scope.packageVersion(), planId, head.revision());
            var before =
                    head.revision() == input.revision()
                            ? head
                            : plans.get(
                                    scope.siteId(),
                                    scope.code(),
                                    scope.packageVersion(),
                                    planId,
                                    input.revision());
            if (!"CONFIRMED".equals(before.status())) throw staleRevision();
            var model = model(before, input.modelKey());
            var additions = additions(before, model);
            var definitions = supplementDefinitions(scope, before, model, additions);
            var mapping = new LinkedHashMap<String, String>();
            model.fields().stream()
                    .filter(field -> field.fieldName() != null)
                    .forEach(field -> mapping.put(field.key(), field.fieldName()));
            additions.forEach(field -> mapping.put(field.key(), field.fieldName()));
            var bound =
                    new Model(
                            model.key(),
                            model.name(),
                            model.kind(),
                            model.menuId(),
                            model.fields().stream()
                                    .map(
                                            field ->
                                                    new Field(
                                                            field.key(),
                                                            field.name(),
                                                            field.type(),
                                                            mapping.get(field.key())))
                                    .toList());
            boolean retry = before != head;
            if (retry && !model(head, input.modelKey()).equals(bound)) throw staleRevision();
            var next =
                    Objects.requireNonNull(
                            transactions.execute(
                                    status -> {
                                        lockProject(scope);
                                        check.run();
                                        var menu = lockMenu(scope, supplementMenuId(scope, model));
                                        validateSupplementMenu(scope, menu, model);
                                        for (var definition : definitions) {
                                            if (!ContentReferences.applies(definition)) continue;
                                            for (Integer targetId :
                                                    ContentReferences.menuIds(definition)) {
                                                var target =
                                                        Objects.equals(menu.getId(), targetId)
                                                                ? menu
                                                                : lockMenu(scope, targetId);
                                                if (!Integer.valueOf(1)
                                                        .equals(target.getPageType()))
                                                    throw conflict("引用目标须为本站列表内容栏目");
                                            }
                                        }
                                        String digest = ContentFieldConfigDigest.of(menu);
                                        if (input.afterDigest().equals(digest)) {
                                            var original = beforeSupplement(menu, definitions);
                                            boolean matches =
                                                    input.beforeDigest()
                                                            .equals(
                                                                    ContentFieldConfigDigest.of(
                                                                            original));
                                            if (!matches && "[]".equals(supplementRaw(original))) {
                                                matches =
                                                        input.beforeDigest()
                                                                        .equals(
                                                                                ContentFieldConfigDigest
                                                                                        .of(
                                                                                                withSupplementRaw(
                                                                                                        original,
                                                                                                        null)))
                                                                || input.beforeDigest()
                                                                        .equals(
                                                                                ContentFieldConfigDigest
                                                                                        .of(
                                                                                                withSupplementRaw(
                                                                                                        original,
                                                                                                        "")));
                                            }
                                            if (!matches) throw conflict("栏目原字段配置与增补预览不一致");
                                        } else {
                                            if (retry || !input.beforeDigest().equals(digest))
                                                throw conflict("栏目字段配置已变化，请重新核对增补预览");
                                            var updated = appendFields(menu, definitions);
                                            if (!input.afterDigest()
                                                    .equals(ContentFieldConfigDigest.of(updated)))
                                                throw conflict("增补方案已变化，请重新核对");
                                            if (!fields.appendModelFields(
                                                    scope.siteId(),
                                                    updated.getId(),
                                                    digest,
                                                    definitions)) throw conflict("栏目已变化，未保存增补字段");
                                            if (!input.afterDigest()
                                                    .equals(
                                                            ContentFieldConfigDigest.of(
                                                                    lockMenu(scope, menu.getId()))))
                                                throw new IllegalStateException("增补后回读的栏目字段配置不一致");
                                        }
                                        check.run();
                                        return retry ? head : plans.withStructure(head, bound);
                                    }));
            saveStructure(head, next);
            return new Applied(
                    next,
                    model.key(),
                    supplementMenuId(scope, model),
                    Map.copyOf(mapping),
                    true,
                    true,
                    true);
        }
    }

    private static int supplementMenuId(Scope scope, Model model) {
        if (model.menuId() == null
                || Objects.equals(model.menuId(), scope.rootMenuId().longValue()))
            throw conflict("请选择已对应实际内容栏目的模型");
        return Math.toIntExact(model.menuId());
    }

    private static List<Field> additions(Plan plan, Model model) {
        var references = regionReferences(plan, model);
        var result =
                model.fields().stream()
                        .filter(field -> field.fieldName() == null)
                        .filter(
                                field ->
                                        !references.containsKey(field.key())
                                                || model(plan, references.get(field.key())).menuId()
                                                        != null)
                        .map(
                                field ->
                                        new Field(
                                                field.key(),
                                                field.name(),
                                                field.type(),
                                                "ext_"
                                                        + stableId(
                                                                "site-supplement:"
                                                                        + plan.id()
                                                                        + ":"
                                                                        + model.key()
                                                                        + ":"
                                                                        + field.key())))
                        .toList();
        if (result.isEmpty()) {
            if (model.fields().stream()
                    .anyMatch(
                            field ->
                                    field.fieldName() == null
                                            && references.containsKey(field.key())))
                throw conflict("请先确认引用目标模型的实际栏目");
            throw conflict("该模型没有待增补的字段");
        }
        if (plan.proposal().relations().stream()
                .anyMatch(
                        relation ->
                                model.key().equals(relation.sourceModelKey())
                                        && !references.containsKey(relation.fieldKey())
                                        && result.stream()
                                                .anyMatch(
                                                        field ->
                                                                field.key()
                                                                        .equals(
                                                                                relation
                                                                                        .fieldKey()))))
            throw conflict("请先核对待增补字段的内容ID关联");
        return result;
    }

    private static Map<String, String> regionReferences(Plan plan, Model model) {
        var references = new LinkedHashMap<String, String>();
        for (var region : plan.proposal().regions()) {
            var maintenance = region.maintenance();
            if (maintenance != null
                    && "REFERENCE".equals(maintenance.mode())
                    && model.key().equals(maintenance.ownerModelKey()))
                references.put(maintenance.fieldKey(), region.modelKey());
        }
        // 单条推荐链接也可以形成已审核的模型关联；保留待配置的引用字段，初建时只分配普通内容字段。
        for (var relation : plan.proposal().relations()) {
            if (!model.key().equals(relation.sourceModelKey()) || relation.targetModelKey() == null) continue;
            if (plan.proposal().models().stream().anyMatch(target ->
                    target.key().equals(relation.targetModelKey()) && "LIST".equals(target.kind()))) {
                references.putIfAbsent(relation.fieldKey(), relation.targetModelKey());
            }
        }
        return references;
    }

    private List<ContentFieldDefinition> supplementDefinitions(
            Scope scope, Plan plan, Model model, List<Field> additions) {
        var references = regionReferences(plan, model);
        var datePrecisions =
                hasDateFields(additions)
                        ? plans.datePrecisions(plan, model, additions)
                        : Map.<String, String>of();
        return additions.stream()
                .map(
                        field -> {
                            var definition =
                                    supplementDefinition(field, datePrecisions.get(field.key()));
                            String targetKey = references.get(field.key());
                            if (targetKey != null) {
                                var target = model(plan, targetKey);
                                definition
                                        .setInputType("content")
                                        .setInputMultiple(true)
                                        .setDefaultValue(
                                                referenceQuery(
                                                        referenceTargetIds(
                                                                scope,
                                                                plan,
                                                                target,
                                                                id ->
                                                                        menus
                                                                                .selectPublicBindingMenu(
                                                                                        scope
                                                                                                .siteId(),
                                                                                        id
                                                                                                .longValue()))));
                            }
                            ContentExtensionFields.validateDefinition(definition);
                            return definition;
                        })
                .toList();
    }

    private static void validateSupplementMenu(Scope scope, OneshotEntity menu, Model model) {
        if (menu == null
                || !Objects.equals(menu.getId(), supplementMenuId(scope, model))
                || !Objects.equals(menu.getSiteProjectId(), scope.siteId())
                || !Objects.equals(menu.getSiteRootId(), scope.rootMenuId())
                || !Boolean.TRUE.equals(menu.getHasManageChannel())
                || !Integer.valueOf("SINGLE".equals(model.kind()) ? 0 : 1)
                        .equals(menu.getPageType())) throw conflict("目标栏目不属于本站或栏目类型、管理状态已变化");
    }

    private OneshotEntity appendFields(OneshotEntity menu, List<ContentFieldDefinition> additions) {
        var used = new HashSet<String>();
        for (String configuration :
                List.of(
                        Objects.toString(menu.getMenuField(), ""),
                                Objects.toString(menu.getModelField(), ""),
                        Objects.toString(menu.getModelList(), ""),
                                Objects.toString(menu.getModelSearch(), ""))) {
            ContentModelCodec.read(configuration).forEach(field -> used.add(field.getFieldName()));
        }
        for (var field : additions) {
            if (used.contains(field.getFieldName())
                    || menu.getExtensionValues().containsKey(field.getFieldName())
                    || menus.hasStoredExtensionField(menu.getId(), field.getFieldName()))
                throw conflict("增补字段的槽位已有配置或历史数据，请核对：" + field.getTitle());
        }
        String merged = ContentModelCodec.append(supplementRaw(menu), additions);
        return withSupplementRaw(menu, merged);
    }

    private static String supplementRaw(OneshotEntity menu) {
        return Integer.valueOf(0).equals(menu.getPageType())
                ? menu.getMenuField()
                : menu.getModelField();
    }

    private static OneshotEntity withSupplementRaw(OneshotEntity menu, String raw) {
        var updated = new OneshotEntity();
        BeanUtils.copyProperties(menu, updated);
        if (Integer.valueOf(0).equals(menu.getPageType())) updated.setMenuField(raw);
        else updated.setModelField(raw);
        return updated;
    }

    private static OneshotEntity beforeSupplement(
            OneshotEntity menu, List<ContentFieldDefinition> expected) {
        var raw = supplementRaw(menu);
        var definitions = ContentModelCodec.read(raw);
        int existingCount = definitions.size() - expected.size();
        if (existingCount < 0
                || !definitions.subList(existingCount, definitions.size()).equals(expected))
            throw conflict("栏目字段配置与已审核增补方案不一致");
        var keys =
                expected.stream()
                        .map(ContentFieldDefinition::getFieldName)
                        .collect(java.util.stream.Collectors.toSet());
        if (definitions.subList(0, existingCount).stream()
                .anyMatch(field -> keys.contains(field.getFieldName())))
            throw conflict("栏目已有重复增补槽位");
        String encoded = ContentModelCodec.write(expected);
        String suffix =
                (existingCount == 0 ? "" : ",") + encoded.substring(1, encoded.length() - 1);
        int end = raw.lastIndexOf(']');
        int start = end - suffix.length();
        if (start < 0 || !raw.regionMatches(start, suffix, 0, suffix.length()))
            throw conflict("已写入字段的原始配置已变化，请人工核对");
        return withSupplementRaw(menu, raw.substring(0, start) + raw.substring(end));
    }

    private static ContentFieldDefinition supplementDefinition(Field field, String datePrecision) {
        return new ContentFieldDefinition()
                .setFieldName(field.fieldName())
                .setFieldType(field.type())
                .setFromName(field.name())
                .setTitle(field.name())
                .setIsOpen("on")
                .setDatePrecision(datePrecision)
                .setMaxLength(extensionCapacity(field.type(), datePrecision));
    }

    private static boolean hasDateFields(List<Field> fields) {
        return fields.stream().anyMatch(field -> "DateTime".equals(field.type()));
    }

    private static int extensionCapacity(String type, String datePrecision) {
        return switch (type) {
            case "DateTime" -> "DAY".equals(datePrecision) ? 10 : 19;
            case "Image", "Video", "File" -> 2048;
            case "SingleLine" -> 500;
            case "MultiLine" -> 10000;
            case "Editor" -> 200000;
            default -> throw new IllegalArgumentException("字段类型无法建立配置");
        };
    }

    private void saveStructure(Plan before, Plan after) {
        if (before == after) return;
        try {
            plans.saveStructure(after);
        } catch (IllegalStateException | ResponseStatusException failure) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "栏目字段已保存，整站计划回写失败。请用原确认方案重试以恢复对应关系", failure);
        }
    }

    public record Applied(
            Plan plan,
            String modelKey,
            Integer menuId,
            Map<String, String> fields,
            boolean reused,
            boolean initializationPending,
            boolean publicationPending) {}

    public Applied apply(
            Scope scope, String planId, Apply input, HttpServletRequest request, Runnable check) {
        synchronized (plans) {
            check.run();
            Plan head = plans.get(scope.siteId(), scope.code(), scope.packageVersion(), planId);
            Model selected = model(head, input.modelKey());
            if (input.revision() != head.revision()) {
                // 接受同一应用产生的下一版，处理成功响应丢失后的原请求重试。
                if (input.revision() != head.revision() - 1 || selected.menuId() == null)
                    throw staleRevision();
                Plan original =
                        plans.get(
                                scope.siteId(),
                                scope.code(),
                                scope.packageVersion(),
                                planId,
                                input.revision());
                Model before = model(original, input.modelKey());
                if (!"CONFIRMED".equals(original.status())
                        || before.menuId() != null
                        || !before.equals(withoutStructure(selected))) throw staleRevision();
            }
            plans.confirmed(
                    scope.siteId(), scope.code(), scope.packageVersion(), planId, head.revision());
            var references = regionReferences(head, selected);
            if (head.proposal().relations().stream()
                    .anyMatch(
                            relation ->
                                    selected.key().equals(relation.sourceModelKey())
                                            && !references.containsKey(relation.fieldKey())))
                throw conflict("该模型的内容ID关联仍待配置，请先处理关联后再创建栏目");
            if (selected.name().codePointCount(0, selected.name().length()) > 64)
                throw new IllegalArgumentException("栏目名称不能超过64个字符");
            Map<String, String> datePrecisions =
                    selected.fields().stream().anyMatch(field -> "DateTime".equals(field.type()))
                            ? plans.datePrecisions(head, selected, selected.fields())
                            : Map.of();
            Applied applied =
                    Objects.requireNonNull(
                            transactions.execute(
                                    status ->
                                            applyInTransaction(
                                                    scope,
                                                    head,
                                                    selected,
                                                    input,
                                                    request,
                                                    check,
                                                    datePrecisions)));
            if (applied.plan() != head) {
                try {
                    plans.saveStructure(applied.plan());
                } catch (IllegalStateException | ResponseStatusException failure) {
                    throw new ResponseStatusException(
                            HttpStatus.SERVICE_UNAVAILABLE,
                            "栏目和字段已保存，整站计划回写失败。请对同一模型重试以恢复对应关系",
                            failure);
                }
            }
            return applied;
        }
    }

    private Applied applyInTransaction(
            Scope scope,
            Plan head,
            Model model,
            Apply input,
            HttpServletRequest request,
            Runnable check,
            Map<String, String> datePrecisions) {
        lockProject(scope);
        lockMenu(scope, scope.rootMenuId());
        check.run();
        String marker = modelMarker(model.key());
        List<OneshotEntity> siteMenus = menus.selectActiveSiteModelMenus(scope.siteId());
        Integer parentId = input.parentMenuId();
        var owner = ownedOwner(head, model);
        boolean owned = owner != null;
        OneshotEntity existing;
        OneshotEntity canonical = null;
        if (owned) {
            if (owner.menuId() == null) throw conflict("请先应用详情主体的正式栏目");
            if (parentId == null) parentId = Math.toIntExact(owner.menuId());
            if (!ownerMenuIds(scope, head, owner).contains(parentId))
                throw conflict("所属条目的父栏目必须是已审核的详情主体栏目");
            var parent = lockMenu(scope, parentId);
            if (!Integer.valueOf(1).equals(parent.getPageType())
                    || !Boolean.TRUE.equals(parent.getHasManageChannel())
                    || Boolean.TRUE.equals(parent.getIsSubMenu()))
                throw conflict("所属条目的主体须为本站启用内容管理的列表栏目");
            canonical = canonicalOwnedMenu(scope, model, owner);
            marker = ownedMarker(model, owner, parentId);
            if (!Long.valueOf(parentId).equals(owner.menuId()) && canonical == null)
                throw conflict("请先创建主栏目下的所属条目栏目");
            existing = existingOwnedMenu(scope, model, owner, parentId, canonical, siteMenus);
        } else {
            existing = markedMenu(siteMenus, marker);
            if (model.menuId() != null
                    && (existing == null
                            || model.menuId().longValue() != existing.getId().longValue()))
                throw conflict("该模型已对应现有栏目，请在 CMS 字段配置中维护");
        }
        if (existing != null) existing = lockMenu(scope, existing.getId());
        if (parentId == null)
            parentId = model.menuId() == null ? scope.rootMenuId() : existing.getParentId();
        if (!owned && !Objects.equals(parentId, scope.rootMenuId())) lockMenu(scope, parentId);
        Integer targetParent = parentId;
        if (existing == null
                && siteMenus.stream()
                        .anyMatch(
                                menu ->
                                        (!owned || Objects.equals(targetParent, menu.getParentId()))
                                                && model.name()
                                                        .strip()
                                                        .equalsIgnoreCase(menu.getClassName())))
            throw conflict(owned ? "该父栏目下已有同名栏目，请先核对现有栏目" : "本站已有同名栏目，请在整站计划中核对并选择实际栏目");

        boolean single = "SINGLE".equals(model.kind());
        var mapping = new LinkedHashMap<String, String>();
        List<ContentFieldDefinition> options;
        List<ContentFieldDefinition> listFields;
        List<ContentFieldDefinition> searchFields;
        if (canonical != null) {
            canonical = lockMenu(scope, canonical.getId());
            validateOwnedMenu(
                    scope,
                    canonical,
                    model,
                    Math.toIntExact(owner.menuId()),
                    modelMarker(model.key()));
            options = ContentModelCodec.read(canonical.getModelField());
            listFields = ContentModelCodec.read(canonical.getModelList());
            searchFields = ContentModelCodec.read(canonical.getModelSearch());
            model.fields().forEach(field -> mapping.put(field.key(), field.fieldName()));
        } else {
            options =
                    allocateFields(
                            model,
                            single,
                            modelMarker(model.key()),
                            mapping,
                            regionReferences(head, model).keySet(),
                            owned,
                            datePrecisions);
            listFields =
                    options.stream()
                            .filter(field -> ContentFieldDefinition.enabled(field.getIsList()))
                            .toList();
            searchFields = listFields;
        }
        boolean reused = existing != null;
        Integer menuId;
        if (reused) {
            if (!matchesStructure(
                    existing,
                    parentId,
                    marker,
                    model,
                    single,
                    owned,
                    options,
                    listFields,
                    searchFields)) throw conflict("本站同标识栏目与审核结构不一致，请在 CMS 核对栏目及字段");
            menuId = existing.getId();
        } else {
            EditOneshotDto menu =
                    new EditOneshotDto()
                            .setClassName(model.name().strip())
                            .setParentId(parentId)
                            .setSiteProjectId(scope.siteId())
                            .setSiteRootId(scope.rootMenuId())
                            .setIdentifies(marker)
                            .setPageType(single ? 0 : 1)
                            .setHasManageChannel(true)
                            .setHasFrontChannel(false)
                            .setIsLoop(false)
                            .setIsShowCrumbs(false)
                            .setIsCanDeleted(true)
                            .setIsSubMenu(owned ? true : null)
                            .setPublished(false)
                            .setUrl(
                                    single
                                            ? "/admin/menu_manage/{id}"
                                            : "/admin/model_manage/{id}");
            requireSuccess(menuService.updateEdit(menu, request));
            menuId = menu.getId();
            lockMenu(scope, menuId);
            requireSuccess(
                    fields.updateModelField(
                            new SetFieldDto()
                                    .setId(menuId)
                                    .setType(single ? 1 : 0)
                                    .setFieldOptions(options)
                                    .setFieldListDisplay(listFields)
                                    .setFieldSearch(searchFields)));
            if (!matchesStructure(
                    lockMenu(scope, menuId),
                    parentId,
                    marker,
                    model,
                    single,
                    owned,
                    options,
                    listFields,
                    searchFields)) throw new IllegalStateException("保存后读取的栏目或字段与审核结构不一致");
        }
        Model bound =
                new Model(
                        model.key(),
                        model.name(),
                        model.kind(),
                        model.menuId() == null ? menuId.longValue() : model.menuId(),
                        model.fields().stream()
                                .map(
                                        field ->
                                                new Field(
                                                        field.key(),
                                                        field.name(),
                                                        field.type(),
                                                        mapping.get(field.key())))
                                .toList());
        if (model.menuId() != null && !model.equals(bound)) throw conflict("计划记录的字段槽位与已应用结构不一致");
        Plan applied = model.menuId() == null ? plans.withStructure(head, bound) : head;
        check.run();
        mapping.values().removeIf(Objects::isNull);
        return new Applied(applied, model.key(), menuId, Map.copyOf(mapping), reused, true, true);
    }

    private static Model ownedOwner(Plan plan, Model child) {
        return plan.proposal().regions().stream()
                .filter(region -> child.key().equals(region.modelKey()))
                .map(SiteModelPlanService.RegionUse::maintenance)
                .filter(maintenance -> maintenance != null && "OWNED".equals(maintenance.mode()))
                .findFirst()
                .map(maintenance -> model(plan, maintenance.ownerModelKey()))
                .orElse(null);
    }

    private List<Integer> ownerMenuIds(Scope scope, Plan plan, Model owner) {
        return navigation.modelMenuIds(
                new SiteModelNavigationService.Scope(
                        scope.siteId(), scope.code(), scope.packageVersion(), scope.rootMenuId()),
                plan,
                owner);
    }

    private static String ownedMarker(Model child, Model owner, Integer parentId) {
        return Long.valueOf(parentId).equals(owner.menuId())
                ? modelMarker(child.key())
                : "site_model_" + stableId(child.key() + ":" + parentId);
    }

    private static OneshotEntity markedMenu(List<OneshotEntity> siteMenus, String marker) {
        var matches =
                siteMenus.stream().filter(menu -> marker.equals(menu.getIdentifies())).toList();
        if (matches.size() > 1) throw conflict("本站存在重复的结构栏目标识，请先核对栏目");
        return matches.isEmpty() ? null : matches.getFirst();
    }

    private OneshotEntity canonicalOwnedMenu(Scope scope, Model child, Model owner) {
        if (child.menuId() == null) return null;
        var menu = menus.getModelById(Math.toIntExact(child.menuId()));
        validateOwnedMenu(
                scope, menu, child, Math.toIntExact(owner.menuId()), modelMarker(child.key()));
        return menu;
    }

    private OneshotEntity existingOwnedMenu(
            Scope scope,
            Model child,
            Model owner,
            Integer parentId,
            OneshotEntity canonical,
            List<OneshotEntity> siteMenus) {
        String marker = ownedMarker(child, owner, parentId);
        var match = markedMenu(siteMenus, marker);
        if (Long.valueOf(parentId).equals(owner.menuId())
                && canonical != null
                && (match == null || !canonical.getId().equals(match.getId())))
            throw conflict("主栏目的所属条目与已审核计划不一致");
        if (match == null) return null;
        var menu = menus.getModelById(match.getId());
        if (child.menuId() == null && Long.valueOf(parentId).equals(owner.menuId())) {
            if (menu == null) throw conflict("所属条目栏目已变化，请重新读取");
            return menu;
        }
        validateOwnedMenu(scope, menu, child, parentId, marker);
        if (canonical == null
                || !sameFields(menu.getModelField(), canonical.getModelField())
                || !sameFields(menu.getModelList(), canonical.getModelList())
                || !sameFields(menu.getModelSearch(), canonical.getModelSearch()))
            throw conflict("所属条目实例的字段与主栏目定义不一致，请核对 CMS 字段");
        return menu;
    }

    private static boolean sameFields(String a, String b) {
        return ContentModelCodec.read(a).equals(ContentModelCodec.read(b));
    }

    private static void validateOwnedMenu(
            Scope scope, OneshotEntity menu, Model child, Integer parentId, String marker) {
        if (menu == null
                || !Objects.equals(menu.getSiteProjectId(), scope.siteId())
                || !Objects.equals(menu.getSiteRootId(), scope.rootMenuId())
                || !Objects.equals(menu.getParentId(), parentId)
                || !child.name().strip().equals(menu.getClassName())
                || !marker.equals(menu.getIdentifies())
                || !Integer.valueOf(1).equals(menu.getPageType())
                || !Boolean.TRUE.equals(menu.getHasManageChannel())
                || !Boolean.TRUE.equals(menu.getIsSubMenu())) throw conflict("所属条目的栏目、类型或归属已变化");
        var options = ContentModelCodec.read(menu.getModelField());
        if (child.fields().stream()
                .anyMatch(
                        field ->
                                field.fieldName() == null
                                        || options.stream()
                                                .noneMatch(
                                                        option ->
                                                                field.fieldName()
                                                                                .equals(
                                                                                        option
                                                                                                .getFieldName())
                                                                        && field.type()
                                                                                .equals(
                                                                                        option
                                                                                                .getFieldType())
                                                                        && ContentFieldDefinition
                                                                                .enabled(
                                                                                        option
                                                                                                .getIsOpen())
                                                                        && !ContentFieldDefinition
                                                                                .enabled(
                                                                                        option
                                                                                                .getIsReadOnly()))))
            throw conflict("所属条目的字段槽位与审核模型不一致");
    }

    private OneshotEntity lockMenu(Scope scope, Integer menuId) {
        OneshotEntity menu =
                menuId == null || menuId <= 0
                        ? null
                        : menus.lockFieldConfiguration(scope.siteId(), menuId.longValue());
        if (menu == null
                || !Objects.equals(menu.getSiteProjectId(), scope.siteId())
                || (!Objects.equals(menuId, scope.rootMenuId())
                        && !Objects.equals(menu.getSiteRootId(), scope.rootMenuId())))
            throw conflict("目标栏目不属于本站，或栏目已删除");
        return menu;
    }

    private void lockProject(Scope scope) {
        var project = projects.selectByIdForUpdate(scope.siteId());
        if (project == null
                || !Objects.equals(project.getRootMenuId(), scope.rootMenuId())
                || !Objects.equals(project.getProjectCode(), scope.code())
                || !Objects.equals(project.getUiPackageVersion(), scope.packageVersion()))
            throw conflict("站点或页面包已变化，请重新读取整站结构");
    }

    private List<ContentFieldDefinition> allocateFields(
            Model model,
            boolean single,
            String marker,
            Map<String, String> mapping,
            Set<String> references,
            boolean owned,
            Map<String, String> datePrecisions) {
        var catalog = fields.getFieldSettingList(single ? 0 : 1);
        var used = new HashSet<String>();
        var options = new ArrayList<ContentFieldDefinition>();
        for (Field field : model.fields()) {
            if (references.contains(field.key())) continue;
            if ("Sort".equals(field.type())) {
                mapping.put(field.key(), "sort");
                if (owned)
                    options.add(
                            new ContentFieldDefinition()
                                    .setFieldName("sort")
                                    .setFieldType("Sort")
                                    .setFromName(field.name())
                                    .setTitle(field.name())
                                    .setIsOpen("on")
                                    .setSort(model.fields().size() - options.size()));
                continue;
            }
            ContentFieldDefinition slot =
                    catalog.stream()
                            .filter(candidate -> field.type().equals(candidate.getFieldType()))
                            .filter(
                                    candidate ->
                                            compatibleNativeSlot(
                                                    field.type(), candidate.getFieldName()))
                            .filter(
                                    candidate ->
                                            !"title".equals(candidate.getFieldName())
                                                    || List.of("title", "name", "heading")
                                                            .contains(field.key()))
                            .filter(candidate -> used.add(candidate.getFieldName()))
                            .findFirst()
                            .orElse(null);
            var option =
                    new ContentFieldDefinition()
                            .setFieldType(field.type())
                            .setFromName(field.name())
                            .setTitle(field.name())
                            .setIsOpen("on")
                            .setSort(model.fields().size() - options.size());
            if (slot == null) {
                option.setFieldName("ext_" + stableId(marker + ":" + field.key()));
                String datePrecision = datePrecisions.get(field.key());
                option.setDatePrecision(datePrecision)
                        .setMaxLength(extensionCapacity(field.type(), datePrecision));
                ContentExtensionFields.validateDefinition(option);
            } else {
                option.setId(slot.getId()).setFieldName(slot.getFieldName());
            }
            if (!single && "SingleLine".equals(field.type()))
                option.setIsList("on").setIsSearch("on");
            mapping.put(field.key(), option.getFieldName());
            options.add(option);
        }
        return List.copyOf(options);
    }

    private static boolean compatibleNativeSlot(String type, String fieldName) {
        if (fieldName == null) return false;
        return switch (type) {
            case "SingleLine" -> fieldName.matches("title|singleLine[0-9]+");
            case "MultiLine" -> fieldName.matches("note[0-9]+");
            case "Image" -> fieldName.matches("photo[0-9]+");
            case "Video" -> fieldName.matches("media[0-9]+");
            case "Editor" -> fieldName.matches("content[0-9]+");
            default -> false;
        };
    }

    private static boolean matchesStructure(
            OneshotEntity menu,
            Integer parentId,
            String marker,
            Model model,
            boolean single,
            boolean owned,
            List<ContentFieldDefinition> options,
            List<ContentFieldDefinition> listFields,
            List<ContentFieldDefinition> searchFields) {
        return marker.equals(menu.getIdentifies())
                && Objects.equals(parentId, menu.getParentId())
                && model.name().strip().equals(menu.getClassName())
                && Integer.valueOf(single ? 0 : 1).equals(menu.getPageType())
                && (!owned || Boolean.TRUE.equals(menu.getIsSubMenu()))
                && !Boolean.FALSE.equals(menu.getHasManageChannel())
                && ContentModelCodec.read(single ? menu.getMenuField() : menu.getModelField())
                        .equals(options)
                && ContentModelCodec.read(menu.getModelList()).equals(listFields)
                && ContentModelCodec.read(menu.getModelSearch()).equals(searchFields);
    }

    static String modelMarker(String modelKey) {
        return "site_model_" + stableId(modelKey);
    }

    private static String stableId(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8))
                .toString()
                .replace("-", "");
    }

    private static Model model(Plan plan, String modelKey) {
        return plan.proposal().models().stream()
                .filter(candidate -> candidate.key().equals(modelKey))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("请选择审核计划中的内容类型"));
    }

    private static Model withoutStructure(Model model) {
        return new Model(
                model.key(),
                model.name(),
                model.kind(),
                null,
                model.fields().stream()
                        .map(
                                field ->
                                        new Field(
                                                field.key(),
                                                field.name(),
                                                field.type(),
                                                "Sort".equals(field.type()) ? "sort" : null))
                        .toList());
    }

    private static void requireSuccess(ResultVO<?> result) {
        if (result == null || !Boolean.TRUE.equals(result.getSuccess()))
            throw new IllegalStateException(
                    result == null || result.getMsg() == null ? "CMS 结构保存失败" : result.getMsg());
    }

    private static ResponseStatusException staleRevision() {
        return conflict("整站计划已更新，请重新加载当前已确认修订");
    }

    private static ResponseStatusException conflict(String reason) {
        return new ResponseStatusException(HttpStatus.CONFLICT, reason);
    }
}
