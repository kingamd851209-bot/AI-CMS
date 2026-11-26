package com.xinhongru.cms.content.record;

import com.alibaba.fastjson.JSON;
import com.xinhongru.common.reflect.EntityFields;
import com.xinhongru.content.model.ContentExtensionFields;
import com.xinhongru.content.model.ContentFieldDefinition;
import com.xinhongru.content.model.ContentModelCodec;
import com.xinhongru.content.model.ContentStatus;
import com.xinhongru.content.model.NativeImageFields;
import com.xinhongru.dto.LogTableDto;
import com.xinhongru.entity.ContentEntity;
import com.xinhongru.entity.ContentHistoryEntity;
import com.xinhongru.entity.OneshotEntity;
import com.xinhongru.entity.UserEntity;
import com.xinhongru.mapper.ContentHistoryMapper;
import com.xinhongru.mapper.ContentMapper;
import com.xinhongru.mapper.OneShotMapper;
import com.xinhongru.mapper.UserMapper;
import com.xinhongru.security.upload.ResourceHtml;
import com.xinhongru.security.upload.ResourceReferenceResolver;
import com.xinhongru.service.member.impl.MemberCredentials;

import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Canonical content writer. A record is persisted once in t_contents with its audit history. */
@Service
public class ContentRecordService {
    private static final Pattern GROUP_EDGE_SPACE =
            Pattern.compile("^[\\p{IsWhite_Space}]+|[\\p{IsWhite_Space}]+$");

    @Autowired private ResourceHtml resourceHtml;

    @Autowired private ResourceReferenceResolver resourceResolver;

    @Autowired private ContentExtensionResources extensionResources;
    @Autowired private ContentReferenceValues referenceValues;
    @Autowired private MemberCredentials memberCredentials;

    private final OneShotMapper oneShotMapper;
    private final ContentMapper contentMapper;
    private final ContentHistoryMapper contentHistoryMapper;
    private final UserMapper userMapper;

    public ContentRecordService(
            OneShotMapper oneShotMapper,
            ContentMapper contentMapper,
            ContentHistoryMapper contentHistoryMapper,
            UserMapper userMapper) {
        this.oneShotMapper = oneShotMapper;
        this.contentMapper = contentMapper;
        this.contentHistoryMapper = contentHistoryMapper;
        this.userMapper = userMapper;
    }

    @Transactional(rollbackFor = Exception.class)
    public ContentMutationResult saveDraft(SaveDraftCommand command) {
        return saveCommand(command, null, null);
    }

    /** 可信初值导入设置原生排序，即使栏目未将排序列暴露为编辑字段。 */
    @Transactional(rollbackFor = Exception.class)
    public ContentMutationResult saveSeedDraft(SaveDraftCommand command, int sort) {
        if (command == null || command.recordId() != null || sort < 1)
            throw new IllegalArgumentException("初值导入只能创建草稿并设置正整数排序");
        return saveCommand(command, null, sort);
    }

    public void requireOwnerRecord(
            Long siteId, Integer ownerMenuId, Long ownerRecordId, boolean lock) {
        if (ownerMenuId == null
                || ownerMenuId <= 0
                || ownerRecordId == null
                || ownerRecordId <= 0
                || ownerRecordId > Integer.MAX_VALUE)
            throw new IllegalArgumentException("请选择所属的有效主体内容");
        int recordId = ownerRecordId.intValue();
        var owner =
                lock
                        ? contentMapper.lockActiveArticleInSiteMenu(siteId, ownerMenuId, recordId)
                        : contentMapper.selectActiveArticleInSiteMenu(
                                siteId, ownerMenuId, recordId);
        if (owner == null
                || !Objects.equals(owner.getId(), recordId)
                || !Objects.equals(owner.getClassid(), ownerMenuId)
                || owner.getSiteProjectId() != null
                        && !Objects.equals(owner.getSiteProjectId(), siteId))
            throw new IllegalArgumentException("主体内容不存在或不属于当前站点和主体栏目");
    }

    /** Trusted integration entry point; workflow status never comes from the content payload. */
    @Transactional(rollbackFor = Exception.class)
    public ContentMutationResult saveIntegration(SaveDraftCommand command, ContentStatus status) {
        Objects.requireNonNull(status, "integration status");
        return saveCommand(command, status, null);
    }

    @Transactional(rollbackFor = Exception.class)
    public GroupAssignmentResult assignDraftGroup(
            Long siteId,
            Integer menuId,
            String groupField,
            String groupValue,
            List<GroupAssignmentRow> records,
            Long operatorId) {
        String value = normalizeGroup(groupValue);
        if (value.isEmpty()
                || value.length() > 512
                || value.indexOf('\r') >= 0
                || value.indexOf('\n') >= 0) throw new IllegalArgumentException("请输入有效的单行分组值");
        if (records == null || records.isEmpty() || records.size() > 100)
            throw new IllegalArgumentException("每次请选择 1–100 条草稿");
        Map<Integer, String> expectedVersions = new LinkedHashMap<>();
        for (GroupAssignmentRow row : records) {
            if (row == null
                    || row.recordId() == null
                    || row.recordId() <= 0
                    || row.rowVersion() == null
                    || !row.rowVersion().matches("[0-9]{1,10}:[0-9]{1,10}")
                    || expectedVersions.putIfAbsent(row.recordId(), row.rowVersion()) != null)
                throw new IllegalArgumentException("内容 ID 或版本无效，请重新选择草稿");
        }

        OneshotEntity menu = oneShotMapper.lockFieldConfiguration(siteId, menuId.longValue());
        if (menu == null || !Objects.equals(menu.getSiteProjectId(), siteId))
            throw new IllegalArgumentException("栏目不属于当前站点");
        ContentFieldDefinition field = requireGroupField(menu, groupField);
        if (ContentFieldDefinition.enabled(field.getIsReadOnly()))
            throw new IllegalArgumentException("分组字段已设为只读");
        List<ContentFieldDefinition> definitions = parseFieldOptions(menu.getModelField());
        String column = nativeGroupColumn(groupField);
        String property =
                column == null
                        ? groupField
                        : column.startsWith("single_line")
                                ? "singleLine" + column.substring(11)
                                : column;
        Integer maxLength = field.getMaxLength();
        int nativeLimit = "tags".equals(column) ? 200 : 500;
        if ((maxLength != null && maxLength > 0 && value.length() > maxLength)
                || (column != null && value.length() > nativeLimit))
            throw new IllegalArgumentException("分组值长度超过字段限制");

        List<ContentEntity> drafts = new ArrayList<>();
        for (Integer id : expectedVersions.keySet().stream().sorted().toList()) {
            ContentEntity draft = contentMapper.lockActiveArticleInSiteMenu(siteId, menuId, id);
            if (draft == null
                    || !Objects.equals(draft.getStatus(), ContentStatus.DRAFT.code())
                    || !expectedVersions.get(id).equals(rowVersion(draft))
                    || readFieldValue(draft, property) instanceof String current
                            && !normalizeGroup(current).isEmpty()) throw new DraftGroupConflict();
            drafts.add(draft);
        }

        for (ContentEntity draft : drafts) {
            extractMemberPassword(draft);
            ContentEntity before = new ContentEntity();
            BeanUtils.copyProperties(draft, before);
            if (column == null) {
                draft.setExtensionFields(
                        ContentExtensionFields.put(draft.getExtensionFields(), field, value));
            } else {
                setEntityField(draft, property, value);
            }
            draft.setPublicSearchText(
                    com.xinhongru.cms.search.PublicSearchText.forContent(
                            definitions, draft, before));
            if (contentMapper.assignDraftGroup(siteId, draft, column, value, recordVersion(draft))
                    != 1) throw new DraftGroupConflict();
            recordHistory(
                    draft,
                    draft.getId(),
                    compareFields(before, draft, List.of(field)),
                    "设置草稿分组",
                    1,
                    operatorId);
        }
        return new GroupAssignmentResult(drafts.size());
    }

    static ContentFieldDefinition requireGroupField(OneshotEntity menu, String fieldName) {
        if (MemberCredentials.isPasswordField(menu.getId(), fieldName)) {
            throw new IllegalArgumentException("会员密码不能用于分组");
        }
        return ContentModelCodec.read(menu.getModelField()).stream()
                .filter(field -> Objects.equals(fieldName, field.getFieldName()))
                .filter(
                        field ->
                                "SingleLine".equals(field.getFieldType())
                                        && ContentFieldDefinition.enabled(field.getIsOpen())
                                        && (ContentExtensionFields.isName(fieldName)
                                                || nativeGroupColumn(fieldName) != null))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("分组须使用当前栏目已启用的单行文本字段"));
    }

    /** 动态 SQL 仅使用已知的原生列名，自定义字段通过参数读取 JSON。 */
    static String nativeGroupColumn(String name) {
        if ("title".equals(name) || "tags".equals(name)) return name;
        if (name != null && name.matches("single(?:Line|_line)(?:[1-9]|1[0-9]|20)"))
            return name.replace("singleLine", "single_line");
        return null;
    }

    static String normalizeGroup(String value) {
        return value == null ? "" : GROUP_EDGE_SPACE.matcher(value).replaceAll("");
    }

    static String rowVersion(ContentEntity content) {
        return content.getId() + ":" + recordVersion(content);
    }

    private static int recordVersion(ContentEntity content) {
        return Objects.requireNonNullElse(content.getVersion(), 0);
    }

    public record GroupAssignmentRow(Integer recordId, String rowVersion) {}

    public record GroupAssignmentResult(int updatedCount) {}

    public static final class DraftGroupConflict extends RuntimeException {
        DraftGroupConflict() {
            super("所选内容已变化、不再是未分组草稿或不属于当前栏目，请刷新后重新选择");
        }
    }

    /** 按当前字段模型恢复业务值，保留当前状态与归属，并在同一事务记录历史。 */
    @Transactional(rollbackFor = Exception.class)
    public boolean rollbackHistory(
            Long siteId, Integer menuId, Integer contentId, Integer historyId, Long operatorId) {
        if (siteId == null
                || siteId <= 0
                || menuId == null
                || menuId <= 0
                || contentId == null
                || contentId <= 0
                || historyId == null
                || historyId <= 0) throw new IllegalArgumentException("历史恢复参数无效");
        OneshotEntity menu = oneShotMapper.lockFieldConfiguration(siteId, menuId.longValue());
        if (menu == null || !Objects.equals(menu.getSiteProjectId(), siteId))
            throw new IllegalArgumentException("栏目不属于当前站点");
        ContentEntity current =
                contentMapper.lockActiveArticleInSiteMenu(siteId, menuId, contentId);
        if (current == null) throw new IllegalArgumentException("内容不存在或不属于当前站点栏目");
        ContentHistoryEntity history = contentHistoryMapper.getById(historyId);
        if (history == null
                || !Objects.equals(history.getContentId(), contentId)
                || !Objects.equals(history.getMenuId(), menuId))
            throw new IllegalArgumentException("历史版本不存在或不属于当前内容");
        ContentEntity target;
        try {
            target = JSON.parseObject(history.getParams(), ContentEntity.class);
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("该版本数据快照解析失败", error);
        }
        if (target == null) throw new IllegalArgumentException("该版本缺少可回滚的数据快照");
        if ((target.getId() != null && !Objects.equals(target.getId(), contentId))
                || (target.getClassid() != null && !Objects.equals(target.getClassid(), menuId))
                || (target.getSiteProjectId() != null
                        && !Objects.equals(target.getSiteProjectId(), siteId)))
            throw new IllegalArgumentException("历史快照不属于当前站点栏目内容");
        List<ContentFieldDefinition> definitions = parseFieldOptions(menu.getModelField());
        Map<String, Object> values = new LinkedHashMap<>();
        List<ContentFieldDefinition> restored = new ArrayList<>();
        Set<String> systemFields =
                Set.of(
                        "id",
                        "siteProjectId",
                        "classid",
                        "contentCode",
                        "status",
                        "hits",
                        "publicSearchText",
                        "public_search_text");
        if (MemberCredentials.isDirectory(menuId)) current.setSingleLine5(null);
        for (ContentFieldDefinition field : definitions) {
            if (!ContentFieldDefinition.enabled(field.getIsOpen())
                    || ContentFieldDefinition.enabled(field.getIsReadOnly())
                    || (MemberCredentials.isDirectory(menuId) && "singleLine5".equals(field.getFieldName()))
                    || systemFields.contains(field.getFieldName())) continue;
            values.put(field.getFieldName(), readFieldValue(target, field.getFieldName()));
            restored.add(field);
        }
        ContentEntity candidate = new ContentEntity();
        BeanUtils.copyProperties(current, candidate);
        canonicalizeValues(values, definitions, siteId);
        mapValuesToEntity(candidate, values, definitions, siteId);
        if (Boolean.TRUE.equals(menu.getIsSubMenu())
                && !Objects.equals(candidate.getXgid(), current.getXgid()))
            requireOwnerRecord(
                    siteId,
                    menu.getParentId(),
                    candidate.getXgid() == null ? null : candidate.getXgid().longValue(),
                    true);
        List<LogTableDto> diffs = compareFields(current, candidate, restored);
        if (diffs.isEmpty()) return false;
        candidate.setPublicSearchText(
                com.xinhongru.cms.search.PublicSearchText.forContent(
                        definitions, candidate, current));
        contentMapper.updateArticleById(candidate);
        recordHistory(
                candidate,
                contentId,
                diffs,
                "回滚到版本#" + historyId + "，修改" + diffs.size() + "个字段",
                1,
                operatorId);
        return true;
    }

    private ContentMutationResult saveCommand(
            SaveDraftCommand command, ContentStatus status, Integer initialSort) {
        if (command == null
                || command.siteId() == null
                || command.siteId() <= 0
                || command.menuId() == null
                || command.menuId() <= 0) {
            throw new IllegalArgumentException("save draft command is invalid");
        }
        ContentEntity saved =
                save(
                        command.siteId(),
                        command.menuId(),
                        command.recordId(),
                        command.sharedPayload(),
                        command.payload(),
                        command.operatorId(),
                        command.rowVersion(),
                        status,
                        initialSort);
        return new ContentMutationResult(
                saved.getId().longValue(),
                0L,
                0L,
                null,
                ContentStatus.fromCode(saved.getStatus()).name(),
                0);
    }

    public record SaveDraftCommand(
            Long siteId,
            Long menuId,
            Long recordId,
            Map<String, Object> sharedPayload,
            Map<String, Object> payload,
            Long operatorId,
            String rowVersion) {
        public SaveDraftCommand(
                Long siteId, Long menuId, Long recordId, Map<String, Object> sharedPayload,
                Map<String, Object> payload, Long operatorId) {
            this(siteId, menuId, recordId, sharedPayload, payload, operatorId, null);
        }
    }

    public record ContentMutationResult(
            Long recordId,
            Long variantId,
            Long activeVersionId,
            Long publishedVersionId,
            String workflowStatus,
            Integer lockVersion) {}

    private ContentEntity save(
            Long siteId,
            Long menuId,
            Long recordId,
            Map<String, Object> sharedPayload,
            Map<String, Object> payload,
            Long operatorId,
            String expectedRowVersion,
            ContentStatus status,
            Integer initialSort) {
        // 合并 payload
        Map<String, Object> allValues = new LinkedHashMap<>();
        if (sharedPayload != null) allValues.putAll(sharedPayload);
        if (payload != null) allValues.putAll(payload);

        // 获取栏目信息
        OneshotEntity menu = oneShotMapper.lockFieldConfiguration(siteId, menuId);
        if (menu == null || !Objects.equals(menu.getSiteProjectId(), siteId)) {
            throw new IllegalArgumentException("栏目不属于当前站点");
        }
        if (Boolean.TRUE.equals(menu.getIsSubMenu()) && allValues.containsKey("xgid")) {
            Object value = allValues.get("xgid");
            String parent = Objects.toString(value, "");
            if (!parent.matches("[1-9][0-9]{0,9}"))
                throw new IllegalArgumentException("请选择所属的有效主体内容");
            requireOwnerRecord(siteId, menu.getParentId(), Long.parseLong(parent), true);
        }

        // 解析字段配置
        List<ContentFieldDefinition> fieldOptions = parseFieldOptions(menu.getModelField());
        canonicalizeValues(allValues, fieldOptions, siteId);

        if (recordId == null || recordId == 0) {
            return insertContent(
                    siteId, menuId, allValues, fieldOptions, operatorId, status, initialSort);
        }
        ContentEntity existing =
                contentMapper.lockActiveArticleInSiteMenu(
                        siteId, Math.toIntExact(menuId), Math.toIntExact(recordId));
        if (existing == null) throw new IllegalArgumentException("内容不存在或不属于当前站点栏目");
        if (expectedRowVersion != null && !expectedRowVersion.equals(rowVersion(existing)))
            throw new IllegalStateException("内容已被更新。请重新打开编辑器，核对最新内容后再保存。");
        return updateContent(existing, allValues, fieldOptions, operatorId, status, siteId);
    }

    private void canonicalizeValues(
            Map<String, Object> allValues, List<ContentFieldDefinition> definitions, Long siteId) {
        if (resourceHtml != null) {
            String code = resourceResolver.siteCode(siteId);
            allValues.replaceAll(
                    (field, value) -> {
                        if (!(value instanceof String text)) return value;
                        if (field.matches("content[0-9]+"))
                            return resourceHtml.canonical(text, code);
                        if (field.matches("(photo|media|file)[0-9]+")
                                && definitions.stream()
                                        .noneMatch(
                                                definition ->
                                                        field.equals(definition.getFieldName())
                                                                && NativeImageFields.supports(
                                                                        definition)))
                            return resourceHtml.canonicalValue(text, code);
                        return value;
                    });
        }
    }

    /** 创建内容及初始历史 */
    private ContentEntity insertContent(
            Long siteId,
            Long menuId,
            Map<String, Object> values,
            List<ContentFieldDefinition> fieldOptions,
            Long operatorId,
            ContentStatus status,
            Integer initialSort) {
        ContentEntity content = new ContentEntity();
        content.setClassid(Math.toIntExact(menuId));
        content.setStatus(status == null ? ContentStatus.DRAFT.code() : status.code());
        content.setHits(0);
        content.setSort(0);
        content.setSiteProjectId(siteId);

        // 映射字段值
        mapValuesToEntity(content, values, fieldOptions, siteId);
        String memberPassword = extractMemberPassword(content);
        if (initialSort != null) content.setSort(initialSort);

        // create_time 为 NOT NULL 且无默认值；字段未提供（或为空）时回填当前时间，
        // 避免 createTime 字段被空值覆盖为 null 导致插入失败。
        if (content.getCreateTime() == null) {
            content.setCreateTime(LocalDateTime.now());
        }

        // 插入记录
        content.setPublicSearchText(
                com.xinhongru.cms.search.PublicSearchText.forContent(fieldOptions, content, null));
        contentMapper.addArticle(content);
        if (memberPassword != null && !memberPassword.isBlank()) {
            memberCredentials.store(content.getId(), memberPassword);
        }
        // 新增内容默认 content_code 取自身 id
        contentMapper.assignContentCodeToOwnId(content.getId());

        // 记录新增历史
        recordHistory(content, content.getId(), null, "新增数据", 1, operatorId);

        return content;
    }

    /** 更新内容及字段变更历史 */
    private ContentEntity updateContent(
            ContentEntity content,
            Map<String, Object> values,
            List<ContentFieldDefinition> fieldOptions,
            Long operatorId,
            ContentStatus status,
            Long siteId) {
        Integer contentId = content.getId();

        // 变更前快照，用于字段级比对
        extractMemberPassword(content);
        ContentEntity before = new ContentEntity();
        BeanUtils.copyProperties(content, before);

        // 映射字段值
        mapValuesToEntity(content, values, fieldOptions, siteId);
        String memberPassword = extractMemberPassword(content);
        if (memberPassword != null && !memberPassword.isBlank()) {
            memberCredentials.store(contentId, memberPassword);
        }

        if (status != null) content.setStatus(status.code());

        // 更新记录
        content.setPublicSearchText(
                com.xinhongru.cms.search.PublicSearchText.forContent(
                        fieldOptions, content, before));
        contentMapper.updateArticleById(content);

        // 字段级差异并记录保存历史（每次保存都记录）
        List<ContentFieldDefinition> auditFields = new ArrayList<>(fieldOptions);
        if (status != null)
            auditFields.add(new ContentFieldDefinition().setFieldName("status").setTitle("状态"));
        List<LogTableDto> diffs = compareFields(before, content, auditFields);
        recordHistory(content, contentId, diffs, "修改" + diffs.size() + "个字段", 1, operatorId);

        return content;
    }

    private String extractMemberPassword(ContentEntity content) {
        if (!MemberCredentials.isDirectory(content.getClassid())) return null;
        String raw = content.getSingleLine5();
        content.setSingleLine5(null);
        return raw;
    }

    /**
     * Trusted integration path: reuse model checks and history without widening the admin payload.
     */
    void applyIntegrationFields(
            ContentEntity content,
            Map<String, Object> values,
            List<ContentFieldDefinition> definitions) {
        mapValuesToEntity(content, values, definitions, content.getSiteProjectId());
    }

    void recordIntegrationHistory(
            ContentEntity before, ContentEntity after, List<ContentFieldDefinition> definitions) {
        List<ContentFieldDefinition> auditFields = new ArrayList<>(definitions);
        auditFields.add(new ContentFieldDefinition().setFieldName("status").setTitle("状态"));
        List<LogTableDto> diffs = before == null ? null : compareFields(before, after, auditFields);
        recordHistory(after, after.getId(), diffs, "网点同步", 1, null);
    }

    /** 记录历史版本 */
    private void recordHistory(
            ContentEntity content,
            Integer contentId,
            List<LogTableDto> diffs,
            String description,
            Integer checkStatus,
            Long operatorId) {
        ContentHistoryEntity history =
                new ContentHistoryEntity()
                        .setContentId(contentId)
                        .setMenuId(content.getClassid())
                        .setTitle(content.getTitle())
                        .setParams(JSON.toJSONString(content))
                        .setComparison(diffs == null ? null : JSON.toJSONString(diffs))
                        .setUpdateDescription(description)
                        .setCheckStatus(checkStatus);
        fillOperator(history, operatorId);
        contentHistoryMapper.save(history);
    }

    /** 填充操作人信息 */
    private void fillOperator(ContentHistoryEntity history, Long operatorId) {
        if (operatorId == null || operatorId <= 0) {
            return;
        }
        try {
            UserEntity user = userMapper.getUserInfoById(Math.toIntExact(operatorId));
            if (user != null) {
                history.setUserId(user.getId());
                history.setUserAccount(user.getUsername());
                history.setUserName(user.getScreenName());
            }
        } catch (Exception ignored) {
            // 查询用户失败不影响保存
        }
    }

    /** 按字段配置逐字段比对，返回变更字段列表（属性名 + 中文标题） */
    private List<LogTableDto> compareFields(
            ContentEntity before, ContentEntity after, List<ContentFieldDefinition> fieldOptions) {
        List<LogTableDto> diffs = new ArrayList<>();
        if (fieldOptions == null || before == null || after == null) {
            return diffs;
        }
        for (ContentFieldDefinition option : fieldOptions) {
            String key = option.getFieldName();
            if (key == null || key.isBlank()) {
                continue;
            }
            Object beforeValue = readFieldValue(before, key);
            Object afterValue = readFieldValue(after, key);
            if (!Objects.equals(normalize(beforeValue), normalize(afterValue))) {
                diffs.add(
                        new LogTableDto()
                                .setFieldKey(key)
                                .setFieldName(isBlank(option.getTitle()) ? key : option.getTitle())
                                .setUpdateBefore(beforeValue)
                                .setUpdateAfter(afterValue));
            }
        }
        return diffs;
    }

    private static Object readFieldValue(Object target, String fieldName) {
        if (ContentExtensionFields.isName(fieldName) && target instanceof ContentEntity content)
            return content.getExtensionValues().get(fieldName);
        try {
            Field field = EntityFields.find(target.getClass(), fieldName);
            if (!field.canAccess(target)) {
                field.setAccessible(true);
            }
            return field.get(target);
        } catch (NoSuchFieldException | IllegalAccessException ignored) {
            return null;
        }
    }

    private static String normalize(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? null : text.trim();
    }

    private static boolean isBlank(String text) {
        return text == null || text.isBlank();
    }

    /** 只读复验已保存业务值；在副本上复用原生映射，不保存或生成历史。 */
    public void validateCurrentValues(
            ContentEntity current, List<ContentFieldDefinition> definitions, Long siteId) {
        if (current == null || siteId == null) throw new IllegalArgumentException("当前内容或站点无效");
        var copy = new ContentEntity();
        BeanUtils.copyProperties(current, copy);
        Map<String, Object> currentValues = new LinkedHashMap<>();
        List<ContentFieldDefinition> validationFields = new ArrayList<>();
        for (var definition : definitions) {
            if (!ContentFieldDefinition.enabled(definition.getIsOpen())) continue;
            if (MemberCredentials.isPasswordField(current.getClassid(), definition.getFieldName())) {
                if (ContentFieldDefinition.enabled(definition.getIsRequired())
                        && !memberCredentials.isConfigured(current)) {
                    throw new IllegalArgumentException("会员密码尚未设置");
                }
                continue;
            }
            var validation = new ContentFieldDefinition();
            BeanUtils.copyProperties(definition, validation);
            // 只读开关限制人工修改，不能跳过已有值的类型/资源校验。
            validation.setIsReadOnly("off");
            validationFields.add(validation);
            Object value = readFieldValue(current, definition.getFieldName());
            if (ContentFieldDefinition.enabled(definition.getIsRequired())
                    && (value == null || value instanceof String text && text.isBlank()))
                throw new IllegalArgumentException("当前必填字段为空: " + definition.getFieldName());
            if ("Editor".equals(definition.getFieldType()) && value instanceof String html) {
                String original =
                        resourceHtml == null
                                ? html
                                : resourceHtml.canonical(html, resourceResolver.siteCode(siteId));
                String cleaned = ContentExtensionHtml.clean(original);
                if (!comparableMarkup(original).equals(comparableMarkup(cleaned)))
                    throw new IllegalArgumentException(
                            "当前正文需要安全清洗，不能确认保留原内容: " + definition.getFieldName());
            }
            currentValues.put(definition.getFieldName(), value);
        }
        canonicalizeValues(currentValues, validationFields, siteId);
        mapValuesToEntity(copy, currentValues, validationFields, siteId);
    }

    private static String comparableMarkup(String html) {
        var document = org.jsoup.Jsoup.parseBodyFragment(html);
        document.outputSettings().prettyPrint(false);
        for (var element : document.select("[style]"))
            element.attr(
                    "style",
                    element.attr("style")
                            .trim()
                            .toLowerCase(Locale.ROOT)
                            .replaceAll("\\s*([:;])\\s*", "$1")
                            .replaceAll(";+$", ""));
        return document.body().html();
    }

    /** 将值映射到实体 */
    private void mapValuesToEntity(
            ContentEntity content,
            Map<String, Object> values,
            List<ContentFieldDefinition> definitions,
            Long siteId) {
        if (values == null) return;
        Map<String, ContentFieldDefinition> fields = new LinkedHashMap<>();
        for (ContentFieldDefinition field : definitions) {
            if (ContentFieldDefinition.enabled(field.getIsOpen()))
                fields.put(field.getFieldName(), field);
        }
        for (var entry : values.entrySet()) {
            String name = entry.getKey();
            if (Set.of(
                            "id",
                            "siteProjectId",
                            "classid",
                            "contentCode",
                            "version",
                            "status",
                            "hits",
                            "publicSearchText",
                            "public_search_text")
                    .contains(name)) {
                throw new IllegalArgumentException("不可修改内容标识或系统字段: " + name);
            }
            ContentFieldDefinition definition = fields.get(name);
            if (definition == null && !"xgid".equals(name)) {
                throw new IllegalArgumentException("字段未在内容模型中启用: " + name);
            }
            if (definition != null && ContentFieldDefinition.enabled(definition.getIsReadOnly()))
                continue;
            Object value = entry.getValue();
            if (ContentExtensionFields.isName(name)) {
                if (com.xinhongru.content.model.ContentReferences.applies(definition))
                    value = referenceValues.prepare(siteId, definition, value);
                if (ContentExtensionFields.isResource(definition))
                    value = extensionResources.prepare(definition, value, siteId);
                if ("Editor".equals(definition.getFieldType())
                        && (value == null || value instanceof String)) {
                    value = ContentExtensionHtml.clean((String) value);
                    if (resourceHtml != null)
                        value =
                                resourceHtml.canonical(
                                        (String) value, resourceResolver.siteCode(siteId));
                }
                content.setExtensionFields(
                        ContentExtensionFields.put(
                                content.getExtensionFields(), definition, value));
                continue;
            }
            if (definition != null && NativeImageFields.supports(definition)) {
                if (content.getId() == null
                        || !Objects.equals(value, readFieldValue(content, name)))
                    setEntityField(
                            content,
                            name,
                            extensionResources.prepareTemplateMedia(definition, value, siteId));
                continue;
            }
            if (value instanceof List<?> valuesList) {
                value =
                        valuesList.isEmpty()
                                ? ""
                                : ","
                                        + valuesList.stream()
                                                .map(String::valueOf)
                                                .collect(Collectors.joining(","))
                                        + ",";
            }
            String text = value == null ? "" : String.valueOf(value);
            if (definition != null
                    && definition.getMaxLength() != null
                    && definition.getMaxLength() > 0
                    && text.length() > definition.getMaxLength()) {
                throw new IllegalArgumentException("字段长度超过模型限制: " + name);
            }
            setEntityField(content, name, text);
        }
        ContentExtensionFields.validateRequired(content.getExtensionFields(), definitions);
    }

    private void setEntityField(ContentEntity content, String name, String value) {
        try {
            Field field = EntityFields.find(ContentEntity.class, name);
            field.setAccessible(true);
            Class<?> type = field.getType();
            if (type == String.class) field.set(content, value);
            else if (type == Integer.class)
                field.set(content, value.isBlank() ? null : Integer.valueOf(value));
            else if (type == Boolean.class) {
                if (!Set.of("", "true", "false", "1", "0", "on", "off")
                        .contains(value.toLowerCase(Locale.ROOT)))
                    throw new IllegalArgumentException("布尔字段值无效: " + name);
                field.set(content, ContentFieldDefinition.enabled(value));
            } else if (type == LocalDateTime.class) {
                field.set(
                        content,
                        value.isBlank()
                                ? null
                                : value.length() == 10
                                        ? LocalDate.parse(value).atStartOfDay()
                                        : LocalDateTime.parse(value.replace(' ', 'T')));
            } else throw new IllegalArgumentException("不支持的内容字段类型: " + name);
        } catch (ReflectiveOperationException | DateTimeParseException | NumberFormatException e) {
            throw new IllegalArgumentException("内容字段值无效: " + name, e);
        }
    }

    /** 解析字段配置 */
    private List<ContentFieldDefinition> parseFieldOptions(String modelField) {
        return ContentModelCodec.read(modelField);
    }
}
