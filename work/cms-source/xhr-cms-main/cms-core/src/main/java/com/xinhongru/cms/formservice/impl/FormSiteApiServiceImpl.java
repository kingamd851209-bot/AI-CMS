package com.xinhongru.cms.formservice.impl;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.xinhongru.cms.formservice.FormApiException;
import com.xinhongru.cms.formservice.FormFieldDefinition;
import com.xinhongru.cms.formservice.FormSiteApiService;
import com.xinhongru.cms.formservice.FormTokenGenerator;
import com.xinhongru.common.reflect.EntityFields;
import com.xinhongru.entity.FormContentEntity;
import com.xinhongru.entity.FormOneshotEntity;
import com.xinhongru.entity.FormServiceKeyEntity;
import com.xinhongru.mapper.FormContentMapper;
import com.xinhongru.mapper.FormOneshotMapper;
import com.xinhongru.mapper.FormServiceKeyMapper;
import com.xinhongru.requestDto.imageCodeDto.ImageCodeDto;
import com.xinhongru.responseVo.ResultVO;
import com.xinhongru.responseVo.imageCode.ImageCodeVO;
import com.xinhongru.service.imageCode.ImageCodeService;
import com.xinhongru.service.imageCode.ImageCodeService.Purpose;
import com.xinhongru.site.entity.SiteProjectEntity;
import com.xinhongru.site.mapper.SiteProjectMapper;
import com.xinhongru.utils.RedisUtils;

import lombok.extern.slf4j.Slf4j;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** 表单服务接口业务实现（运行在站点服务内，直接读写本站点数据）。 */
@Slf4j
@Service
public class FormSiteApiServiceImpl implements FormSiteApiService {
    /** 提交限流：窗口秒数与窗口内最大次数 */
    private static final int SUBMIT_WINDOW_SECONDS = 60;

    private static final int SUBMIT_MAX_PER_WINDOW = 10;
    private static final String RATE_LIMIT_KEY_PREFIX = "form:submit:rl:";

    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final Pattern TEL = Pattern.compile("^1[3-9]\\d{9}$");
    private static final Pattern EMAIL =
            Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");
    private static final Pattern ID_CARD = Pattern.compile("^(\\d{15}|\\d{17}[\\dXx])$");
    private static final Pattern POSITIVE_INT = Pattern.compile("^[1-9]\\d*$");
    private static final Pattern WEB_URL =
            Pattern.compile("^https?://\\S+$", Pattern.CASE_INSENSITIVE);

    /** 文本类字段类型（用于取标题与展示） */
    private static final Set<String> TEXT_FIELD_TYPES =
            Set.of("SingleLine", "MultiLine", "Text", "Textarea", "Editor", "RichText");

    /** 可写入 t_from_contents 的字符串列（排除 @TableField(exist=false) 的非表字段） */
    private static final Set<String> WRITABLE_COLUMNS = buildWritableColumns();

    /** 列长度上限（按建表定义），写入前裁剪，避免截断异常 */
    private static final Map<String, Integer> COLUMN_MAX_LENGTH = buildColumnMaxLength();

    private final FormOneshotMapper formOneshotMapper;
    private final FormContentMapper formContentMapper;
    private final FormServiceKeyMapper formServiceKeyMapper;
    private final SiteProjectMapper aiSiteProjectMapper;
    private final ImageCodeService imageCodeService;

    public FormSiteApiServiceImpl(
            FormOneshotMapper formOneshotMapper,
            FormContentMapper formContentMapper,
            FormServiceKeyMapper formServiceKeyMapper,
            SiteProjectMapper aiSiteProjectMapper,
            ImageCodeService imageCodeService) {
        this.formOneshotMapper = formOneshotMapper;
        this.formContentMapper = formContentMapper;
        this.formServiceKeyMapper = formServiceKeyMapper;
        this.aiSiteProjectMapper = aiSiteProjectMapper;
        this.imageCodeService = imageCodeService;
    }

    @Override
    public Map<String, Object> publicDefinition(Long siteId, Integer categoryId) {
        var category = requirePublicCategory(siteId, categoryId);
        var definition =
                baseDefinition(
                        category, fieldsOf(category).stream().filter(f -> !f.readOnly()).toList());
        ((List<Map<String, Object>>) definition.get("fields"))
                .forEach(
                        field -> {
                            field.remove("defaultValue");
                            int limit =
                                    COLUMN_MAX_LENGTH.getOrDefault(
                                            String.valueOf(field.get("fieldName")), 500);
                            Object configured = field.get("maxLength");
                            field.put("storageMaxLength", limit);
                            field.put("configuredMaxLength", configured);
                            field.put(
                                    "maxLength",
                                    configured instanceof Number n && n.intValue() > 0
                                            ? Math.min(limit, n.intValue())
                                            : limit);
                        });
        return definition;
    }

    @Override
    public Map<String, Object> publicCaptcha(Long siteId, Integer categoryId) {
        requirePublicCategory(siteId, categoryId);
        return createCaptcha();
    }

    @Override
    public Map<String, Object> publicSubmit(Long siteId, SubmitRequest request, String clientIp) {
        if (request == null) throw new FormApiException(422, "请提供提交内容");
        var category = requirePublicCategory(siteId, request.categoryId());
        var allowed =
                fieldsOf(category).stream()
                        .filter(f -> !f.readOnly())
                        .map(FormFieldDefinition::fieldName)
                        .collect(java.util.stream.Collectors.toSet());
        var submitted = request.fields() == null ? Map.<String, Object>of() : request.fields();
        for (var entry : submitted.entrySet()) {
            if (!allowed.contains(entry.getKey()) || !(entry.getValue() instanceof String))
                throw new FormApiException(422, "字段未开放或值不是文本: " + entry.getKey());
        }
        for (var field : fieldsOf(category)) {
            Object value = submitted.get(field.fieldName());
            if (value instanceof String text
                    && text.codePointCount(0, text.length())
                            > COLUMN_MAX_LENGTH.getOrDefault(field.fieldName(), 500))
                throw new FormApiException(422, "字段超过存储长度: " + field.label());
            if (value instanceof String text
                    && field.maxLength() > 0
                    && text.length() > field.maxLength())
                throw new FormApiException(422, "字段超过配置长度: " + field.label());
        }
        var result = submitCategory(siteId, category, request, clientIp, true);
        return Map.of("id", result.get("id"), "message", "留言提交成功");
    }

    private FormOneshotEntity requirePublicCategory(Long siteId, Integer categoryId) {
        var category = requireCategory(siteId, categoryId);
        var key =
                formServiceKeyMapper.selectOne(
                        new QueryWrapper<FormServiceKeyEntity>()
                                .eq("site_id", siteId)
                                .last("LIMIT 1"));
        if (key == null
                || !Integer.valueOf(1).equals(key.getEnabled())
                || !Boolean.TRUE.equals(category.getHasFrontChannel())
                || !isCaptchaRequired(category)
                || fieldsOf(category).isEmpty())
            throw new FormApiException(403, "该分类未开放公开留言，请启用表单服务、前台展示和验证码");
        for (var field : fieldsOf(category)) {
            if (!field.readOnly()
                    && (!Set.of("SingleLine", "MultiLine", "Text", "Textarea")
                                    .contains(field.fieldType())
                            || !field.fieldName().matches("title|singleLine[0-9]+|note[0-9]+")
                            || !WRITABLE_COLUMNS.contains(field.fieldName())))
                throw new FormApiException(403, "公开留言目前只支持普通文本字段，请核对分类配置");
        }
        return category;
    }

    @Override
    public Map<String, Object> definition(Long siteId, Integer categoryId, String apiKey) {
        requireValidKey(siteId, apiKey);
        FormOneshotEntity category = requireCategory(siteId, categoryId);
        return definitionOf(category);
    }

    @Override
    public Map<String, Object> display(
            Long siteId, Integer categoryId, Integer contentId, String apiKey) {
        requireValidKey(siteId, apiKey);
        FormOneshotEntity category = requireCategory(siteId, categoryId);
        List<FormFieldDefinition> fields = fieldsOf(category);

        FormContentEntity content = null;
        if (contentId != null) {
            content = formContentMapper.selectById(contentId);
            if (content == null || !Objects.equals(content.getClassid(), category.getId())) {
                throw new FormApiException(404, "提交记录不存在");
            }
        }

        Map<String, Object> data = baseDefinition(category, fields);
        Map<String, Object> values = new LinkedHashMap<>();
        for (FormFieldDefinition field : fields) {
            Object value =
                    content == null ? field.defaultValue() : readColumn(content, field.fieldName());
            values.put(field.fieldName(), value == null ? "" : value);
        }
        data.put("id", content == null ? null : content.getId());
        data.put("values", values);
        return data;
    }

    @Override
    public Map<String, Object> submit(Long siteId, SubmitRequest request, String clientIp) {
        requireValidKey(siteId, request.apiKey());
        FormOneshotEntity category = requireCategory(siteId, request.categoryId());
        return submitCategory(siteId, category, request, clientIp, false);
    }

    private Map<String, Object> submitCategory(
            Long siteId,
            FormOneshotEntity category,
            SubmitRequest request,
            String clientIp,
            boolean publicRequest) {
        requireNotRateLimited(siteId, clientIp, publicRequest);
        if (isCaptchaRequired(category)) {
            verifyCaptcha(request.captchaKey(), request.captchaCode());
        }

        List<FormFieldDefinition> fields = fieldsOf(category);
        Map<String, Object> submitted = request.fields() == null ? Map.of() : request.fields();
        List<Map<String, Object>> errors = validate(fields, submitted);
        if (!errors.isEmpty()) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("errors", errors);
            throw new FormApiException(422, "字段校验未通过", detail);
        }

        FormContentEntity entity = new FormContentEntity();
        entity.setClassid(category.getId());
        entity.setStatus(1);
        entity.setCreateTime(LocalDateTime.now());
        String title = "";
        for (FormFieldDefinition field : fields) {
            if (field.readOnly()) {
                continue;
            }
            String value = capValue(field.fieldName(), asString(submitted.get(field.fieldName())));
            if (value.isEmpty()) {
                continue;
            }
            writeColumn(entity, field.fieldName(), value);
            if (title.isEmpty() && isTextLike(field)) {
                title = value;
            }
        }
        if (!publicRequest || entity.getTitle() == null || entity.getTitle().isEmpty()) {
            entity.setTitle(title.isEmpty() ? "表单提交" : capValue("title", title));
        }

        String token = FormTokenGenerator.generate(32);
        entity.setQueryToken(token);
        formContentMapper.insert(entity);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", entity.getId());
        data.put("queryToken", token);
        return data;
    }

    @Override
    public Map<String, Object> queryList(Long siteId, QueryListRequest request) {
        requireValidKey(siteId, request.apiKey());
        FormOneshotEntity category = requireCategory(siteId, request.categoryId());

        int page = Math.max(1, request.page());
        int size = Math.min(Math.max(1, request.size()), 100);
        Long total = formContentMapper.selectCount(queryConditions(category.getId(), request));
        QueryWrapper<FormContentEntity> listWrapper =
                queryConditions(category.getId(), request)
                        .orderByDesc("id")
                        .last("LIMIT " + size + " OFFSET " + ((long) (page - 1) * size));
        List<FormContentEntity> rows = formContentMapper.selectList(listWrapper);

        List<FormFieldDefinition> fields = fieldsOf(category);
        List<Map<String, Object>> list = new ArrayList<>();
        for (FormContentEntity row : rows) {
            list.add(toRecordView(row, fields));
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("page", page);
        data.put("size", size);
        data.put("total", total == null ? 0L : total);
        data.put("list", list);
        return data;
    }

    @Override
    public Map<String, Object> queryOne(
            Long siteId,
            Integer categoryId,
            Integer contentId,
            String queryToken,
            String mobile,
            String email,
            String apiKey) {
        requireValidKey(siteId, apiKey);
        if (contentId == null) {
            throw new FormApiException(404, "提交记录不存在");
        }
        FormContentEntity content = formContentMapper.selectById(contentId);
        if (content == null) {
            throw new FormApiException(404, "提交记录不存在");
        }
        // 由记录的 class_id 反查分类，顺带校验该记录属于当前站点
        FormOneshotEntity category = requireCategory(siteId, content.getClassid());
        if (categoryId != null && !Objects.equals(categoryId, content.getClassid())) {
            throw new FormApiException(404, "提交记录不存在");
        }

        List<FormFieldDefinition> fields = fieldsOf(category);
        boolean matched = false;
        if (notBlank(queryToken) && queryToken.equals(content.getQueryToken())) {
            matched = true;
        }
        if (!matched && notBlank(mobile)) {
            matched = matchAnyValidatedField(content, fields, "tel", mobile);
        }
        if (!matched && notBlank(email)) {
            matched = matchAnyValidatedField(content, fields, "email", email);
        }
        if (!matched) {
            throw new FormApiException(404, "提交记录不存在或凭证不匹配");
        }
        return toRecordView(content, fields);
    }

    @Override
    public Map<String, Object> authCheck(Long siteId, String apiKey) {
        requireValidKey(siteId, apiKey);
        SiteProjectEntity site = aiSiteProjectMapper.selectById(siteId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("valid", true);
        data.put("siteId", siteId);
        data.put("projectCode", site == null ? null : site.getProjectCode());
        data.put("siteName", site == null ? null : site.getProjectName());
        return data;
    }

    @Override
    public Map<String, Object> captcha(Long siteId, Integer categoryId, String apiKey) {
        requireValidKey(siteId, apiKey);
        FormOneshotEntity category = requireCategory(siteId, categoryId);
        if (!isCaptchaRequired(category)) {
            throw new FormApiException(404, "该表单未开启验证码");
        }
        return createCaptcha();
    }

    private Map<String, Object> createCaptcha() {
        ResultVO<ImageCodeVO> result = imageCodeService.getImageCode(Purpose.PUBLIC_FORM, new ImageCodeDto());
        if (result == null
                || !Boolean.TRUE.equals(result.getSuccess())
                || result.getData() == null) {
            throw new FormApiException(500, "验证码生成失败");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("captchaKey", result.getData().getCodeKey());
        data.put("imageBase64", result.getData().getBase64Image());
        return data;
    }

    // ---------------- 鉴权与分类 ----------------

    private void requireValidKey(Long siteId, String apiKey) {
        if (siteId == null || isBlank(apiKey)) {
            throw new FormApiException(401, "认证失败");
        }
        FormServiceKeyEntity key =
                formServiceKeyMapper.selectOne(
                        new QueryWrapper<FormServiceKeyEntity>()
                                .eq("site_id", siteId)
                                .last("LIMIT 1"));
        if (key == null
                || !Integer.valueOf(1).equals(key.getEnabled())
                || !apiKey.equals(key.getApiKey())) {
            throw new FormApiException(401, "认证失败");
        }
    }

    private FormOneshotEntity requireCategory(Long siteId, Integer categoryId) {
        if (categoryId == null) {
            throw new FormApiException(404, "表单分类不存在");
        }
        FormOneshotEntity category = formOneshotMapper.selectById(categoryId);
        if (category == null
                || Boolean.TRUE.equals(category.getIsDeleted())
                || !Objects.equals(category.getSiteProjectId(), siteId)) {
            throw new FormApiException(404, "表单分类不存在或不属于当前站点");
        }
        return category;
    }

    private boolean isCaptchaRequired(FormOneshotEntity category) {
        return Integer.valueOf(1).equals(category.getFormCaptcha());
    }

    private void verifyCaptcha(String captchaKey, String captchaCode) {
        if (isBlank(captchaKey) || isBlank(captchaCode)) {
            throw new FormApiException(422, "验证码错误或已过期");
        }
        ResultVO<Boolean> result;
        try {
            result = imageCodeService.checkImageCode(Purpose.PUBLIC_FORM, captchaKey, captchaCode);
        } catch (Exception e) {
            throw new FormApiException(422, "验证码错误或已过期");
        }
        if (result == null || !Boolean.TRUE.equals(result.getSuccess())) {
            throw new FormApiException(422, "验证码错误或已过期");
        }
    }

    private void requireNotRateLimited(Long siteId, String clientIp, boolean failClosed) {
        String redisKey =
                RATE_LIMIT_KEY_PREFIX + siteId + ":" + (isBlank(clientIp) ? "unknown" : clientIp);
        try {
            RedisTemplate<String, Object> template = RedisUtils.getRedisTemplate();
            Long count = template.opsForValue().increment(redisKey);
            if (failClosed && count == null) throw new FormApiException(503, "留言服务暂不可用，请稍后再试");
            if (count != null && count == 1L) {
                template.expire(redisKey, Duration.ofSeconds(SUBMIT_WINDOW_SECONDS));
            }
            if (count != null && count > SUBMIT_MAX_PER_WINDOW) {
                throw new FormApiException(429, "提交过于频繁，请稍后再试");
            }
        } catch (FormApiException e) {
            throw e;
        } catch (Exception e) {
            if (failClosed) throw new FormApiException(503, "留言服务暂不可用，请稍后再试");
            // 兼容既有认证接口；公开留言不在限流不可用时放行
            log.warn("表单提交限流检查失败（放行）: siteId={}, err={}", siteId, e.getMessage());
        }
    }

    // ---------------- 定义与视图 ----------------

    private List<FormFieldDefinition> fieldsOf(FormOneshotEntity category) {
        boolean singlePage = category.getPageType() == null || category.getPageType() == 0;
        return FormFieldDefinition.parse(
                singlePage ? category.getMenuField() : category.getModelField());
    }

    private Map<String, Object> definitionOf(FormOneshotEntity category) {
        return baseDefinition(category, fieldsOf(category));
    }

    private Map<String, Object> baseDefinition(
            FormOneshotEntity category, List<FormFieldDefinition> fields) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("categoryId", category.getId());
        data.put("categoryName", category.getClassName());
        data.put("pageType", category.getPageType() == null ? 0 : category.getPageType());
        data.put("captchaRequired", isCaptchaRequired(category));
        data.put("fields", toFieldViews(fields));
        return data;
    }

    private List<Map<String, Object>> toFieldViews(List<FormFieldDefinition> fields) {
        List<Map<String, Object>> views = new ArrayList<>();
        for (FormFieldDefinition field : fields) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("fieldName", field.fieldName());
            view.put("label", field.label());
            view.put("fieldType", field.fieldType());
            view.put("required", field.required());
            view.put("readOnly", field.readOnly());
            view.put("maxLength", field.maxLength());
            view.put("inputType", field.inputType());
            view.put("multiple", field.multiple());
            view.put("defaultValue", field.defaultValue());
            view.put("validations", field.validations());
            List<Map<String, Object>> options = new ArrayList<>();
            for (FormFieldDefinition.Option option : field.options()) {
                Map<String, Object> optionView = new LinkedHashMap<>();
                optionView.put("value", option.value());
                optionView.put("label", option.label());
                options.add(optionView);
            }
            view.put("options", options);
            views.add(view);
        }
        return views;
    }

    private Map<String, Object> toRecordView(
            FormContentEntity row, List<FormFieldDefinition> fields) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", row.getId());
        view.put("title", row.getTitle());
        view.put(
                "createTime",
                row.getCreateTime() == null ? null : row.getCreateTime().format(DATE_TIME));
        Map<String, Object> values = new LinkedHashMap<>();
        for (FormFieldDefinition field : fields) {
            Object value = readColumn(row, field.fieldName());
            values.put(field.fieldName(), value == null ? "" : value);
        }
        view.put("fields", values);
        return view;
    }

    private boolean isTextLike(FormFieldDefinition field) {
        String type = field.fieldType();
        if (type != null && !type.isBlank()) {
            return TEXT_FIELD_TYPES.contains(type);
        }
        String name = field.fieldName().toLowerCase();
        return name.startsWith("singleline")
                || name.startsWith("note")
                || name.startsWith("content");
    }

    // ---------------- 校验 ----------------

    private List<Map<String, Object>> validate(
            List<FormFieldDefinition> fields, Map<String, Object> submitted) {
        List<Map<String, Object>> errors = new ArrayList<>();
        for (FormFieldDefinition field : fields) {
            if (field.readOnly()) {
                continue;
            }
            String value = asString(submitted.get(field.fieldName())).trim();
            if (value.isEmpty()) {
                if (field.required()) {
                    addError(errors, field, field.label() + " 为必填项");
                }
                continue;
            }
            if (field.maxLength() > 0 && value.length() > field.maxLength()) {
                addError(errors, field, field.label() + " 长度不能超过 " + field.maxLength());
            }
            if (field.validations().contains("tel") && !TEL.matcher(value).matches()) {
                addError(errors, field, field.label() + " 格式不正确");
            }
            if (field.validations().contains("email") && !EMAIL.matcher(value).matches()) {
                addError(errors, field, field.label() + " 格式不正确");
            }
            if (field.validations().contains("idCard") && !ID_CARD.matcher(value).matches()) {
                addError(errors, field, field.label() + " 格式不正确");
            }
            if (field.validations().contains("number") && !POSITIVE_INT.matcher(value).matches()) {
                addError(errors, field, field.label() + " 必须为正整数");
            }
            if (field.validations().contains("webUrl") && !WEB_URL.matcher(value).matches()) {
                addError(errors, field, field.label() + " 格式不正确");
            }
            if ("enum".equals(field.inputType()) && !enumValueAllowed(field, value)) {
                addError(errors, field, field.label() + " 取值不在允许范围内");
            }
        }
        return errors;
    }

    private boolean enumValueAllowed(FormFieldDefinition field, String value) {
        if (field.options().isEmpty()) {
            return true;
        }
        Set<String> allowed = new LinkedHashSet<>();
        for (FormFieldDefinition.Option option : field.options()) {
            allowed.add(option.value());
        }
        if (field.multiple()) {
            for (String part : value.split(",")) {
                String item = part.trim();
                if (!item.isEmpty() && !allowed.contains(item)) {
                    return false;
                }
            }
            return true;
        }
        return allowed.contains(value);
    }

    private void addError(
            List<Map<String, Object>> errors, FormFieldDefinition field, String message) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("field", field.fieldName());
        error.put("label", field.label());
        error.put("message", message);
        errors.add(error);
    }

    // ---------------- 查询条件 ----------------

    private QueryWrapper<FormContentEntity> queryConditions(
            Integer categoryId, QueryListRequest request) {
        QueryWrapper<FormContentEntity> wrapper = new QueryWrapper<>();
        wrapper.eq("class_id", categoryId).eq("deleted", 0);
        if (notBlank(request.keyword())) {
            wrapper.like("title", request.keyword());
        }
        if (request.status() != null) {
            wrapper.eq("status", request.status());
        }
        if (notBlank(request.beginTime())) {
            wrapper.ge("create_time", request.beginTime());
        }
        if (notBlank(request.endTime())) {
            wrapper.le("create_time", request.endTime());
        }
        return wrapper;
    }

    private boolean matchAnyValidatedField(
            FormContentEntity content,
            List<FormFieldDefinition> fields,
            String validation,
            String expected) {
        for (FormFieldDefinition field : fields) {
            if (!field.validations().contains(validation)) {
                continue;
            }
            Object value = readColumn(content, field.fieldName());
            if (value != null && expected.equals(value)) {
                return true;
            }
        }
        return false;
    }

    // ---------------- 反射读写与长度保护 ----------------

    private static Set<String> buildWritableColumns() {
        Set<String> columns = new LinkedHashSet<>();
        for (Field field : EntityFields.all(FormContentEntity.class)) {
            if (!field.getType().equals(String.class) || Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            TableField annotation = field.getAnnotation(TableField.class);
            if (annotation != null && !annotation.exist()) {
                continue;
            }
            columns.add(field.getName());
        }
        return columns;
    }

    private static Map<String, Integer> buildColumnMaxLength() {
        Map<String, Integer> lengths = new HashMap<>();
        lengths.put("title", 500);
        lengths.put("tags", 200);
        lengths.put("seoTitle", 255);
        for (int i = 1; i <= 20; i++) {
            lengths.put("singleLine" + i, 500);
        }
        for (int i = 1; i <= 15; i++) {
            lengths.put("photo" + i, 255);
            lengths.put("note" + i, 20000);
        }
        for (int i = 1; i <= 6; i++) {
            lengths.put("content" + i, 20000);
        }
        for (int i = 1; i <= 4; i++) {
            lengths.put("media" + i, 255);
            lengths.put("file" + i, 255);
        }
        lengths.put("seoKeywords", 20000);
        lengths.put("seoDescription", 20000);
        return lengths;
    }

    private String capValue(String fieldName, String value) {
        if (value == null) {
            return "";
        }
        int max = COLUMN_MAX_LENGTH.getOrDefault(fieldName, 500);
        return value.codePointCount(0, value.length()) <= max
                ? value : value.substring(0, value.offsetByCodePoints(0, max));
    }

    private String readColumn(FormContentEntity content, String fieldName) {
        if (!WRITABLE_COLUMNS.contains(fieldName) || content == null) {
            return null;
        }
        try {
            Field field = EntityFields.find(FormContentEntity.class, fieldName);
            field.setAccessible(true);
            Object value = field.get(content);
            return value instanceof String text ? text : null;
        } catch (Exception e) {
            return null;
        }
    }

    private void writeColumn(FormContentEntity content, String fieldName, String value) {
        if (!WRITABLE_COLUMNS.contains(fieldName)) {
            return;
        }
        try {
            Field field = EntityFields.find(FormContentEntity.class, fieldName);
            field.setAccessible(true);
            field.set(content, value);
        } catch (Exception e) {
            log.debug("忽略不可写字段: {}", fieldName);
        }
    }

    private static String asString(Object value) {
        if (value == null) {
            return "";
        }
        return value instanceof String text ? text : String.valueOf(value);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean notBlank(String value) {
        return !isBlank(value);
    }
}
