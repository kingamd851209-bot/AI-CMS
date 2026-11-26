package com.xinhongru.service.cmsPlatform.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.xinhongru.cms.formservice.FormFieldDefinition;
import com.xinhongru.cms.formservice.FormTokenGenerator;
import com.xinhongru.entity.FormOneshotEntity;
import com.xinhongru.entity.FormServiceKeyEntity;
import com.xinhongru.mapper.FormOneshotMapper;
import com.xinhongru.mapper.FormServiceKeyMapper;
import com.xinhongru.service.cmsPlatform.FormServiceConfigService;
import com.xinhongru.site.entity.SiteProjectEntity;
import com.xinhongru.site.mapper.SiteProjectMapper;

import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** 表单服务接口管理侧配置实现。 */
@Slf4j
@Service
public class FormServiceConfigServiceImpl implements FormServiceConfigService {
    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String KEY_PLACEHOLDER = "ak_xxx";

    private final FormServiceKeyMapper formServiceKeyMapper;
    private final FormOneshotMapper formOneshotMapper;
    private final SiteProjectMapper aiSiteProjectMapper;

    public FormServiceConfigServiceImpl(
            FormServiceKeyMapper formServiceKeyMapper,
            FormOneshotMapper formOneshotMapper,
            SiteProjectMapper aiSiteProjectMapper) {
        this.formServiceKeyMapper = formServiceKeyMapper;
        this.formOneshotMapper = formOneshotMapper;
        this.aiSiteProjectMapper = aiSiteProjectMapper;
    }

    @Override
    public KeyView getKey(Long siteId) {
        return toKeyView(siteId, findKey(siteId));
    }

    @Override
    public KeyView rotateKey(Long siteId) {
        FormServiceKeyEntity existing = findKey(siteId);
        LocalDateTime now = LocalDateTime.now();
        String apiKey = FormTokenGenerator.generate(32);
        if (existing == null) {
            FormServiceKeyEntity entity =
                    new FormServiceKeyEntity()
                            .setSiteId(siteId)
                            .setApiKey(apiKey)
                            .setEnabled(1)
                            .setRotateTime(now)
                            .setCreateTime(now);
            formServiceKeyMapper.insert(entity);
            return toKeyView(siteId, entity);
        }
        existing.setApiKey(apiKey).setRotateTime(now).setUpdateTime(now);
        formServiceKeyMapper.updateById(existing);
        return toKeyView(siteId, existing);
    }

    @Override
    public KeyView updateKey(Long siteId, Boolean enabled, String remark) {
        FormServiceKeyEntity existing = findKey(siteId);
        if (existing == null) {
            throw new IllegalStateException("该站点尚未生成认证key");
        }
        if (enabled != null) {
            existing.setEnabled(enabled ? 1 : 0);
        }
        if (remark != null) {
            existing.setRemark(remark);
        }
        existing.setUpdateTime(LocalDateTime.now());
        formServiceKeyMapper.updateById(existing);
        return toKeyView(siteId, existing);
    }

    @Override
    public DocView doc(Long siteId) {
        SiteProjectEntity site = aiSiteProjectMapper.selectById(siteId);
        String baseUrl = baseUrl(site);
        FormServiceKeyEntity key = findKey(siteId);
        boolean hasKey = key != null;
        boolean keyEnabled = hasKey && Integer.valueOf(1).equals(key.getEnabled());

        List<FormOneshotEntity> categories =
                formOneshotMapper.selectList(
                        new QueryWrapper<FormOneshotEntity>()
                                .eq("site_project_id", siteId)
                                .eq("is_deleted", 0)
                                .orderByAsc("sort")
                                .orderByAsc("id"));
        List<CategoryView> categoryViews = new ArrayList<>();
        for (FormOneshotEntity category : categories) {
            categoryViews.add(
                    new CategoryView(
                            category.getId(),
                            category.getClassName(),
                            category.getPageType() == null ? 0 : category.getPageType(),
                            Integer.valueOf(1).equals(category.getFormCaptcha()),
                            fieldCount(category)));
        }

        String sampleCategoryId =
                categories.isEmpty() ? "{categoryId}" : String.valueOf(categories.get(0).getId());
        return new DocView(
                baseUrl, hasKey, keyEnabled, categoryViews, endpoints(baseUrl, sampleCategoryId));
    }

    // ---------------- 内部 ----------------

    private FormServiceKeyEntity findKey(Long siteId) {
        return formServiceKeyMapper.selectOne(
                new QueryWrapper<FormServiceKeyEntity>().eq("site_id", siteId).last("LIMIT 1"));
    }

    private KeyView toKeyView(Long siteId, FormServiceKeyEntity entity) {
        if (entity == null) {
            return new KeyView(siteId, false, null, false, null, null);
        }
        return new KeyView(
                siteId,
                true,
                entity.getApiKey(),
                Integer.valueOf(1).equals(entity.getEnabled()),
                entity.getRemark(),
                entity.getRotateTime() == null ? null : entity.getRotateTime().format(DATE_TIME));
    }

    private int fieldCount(FormOneshotEntity category) {
        boolean singlePage = category.getPageType() == null || category.getPageType() == 0;
        String json = singlePage ? category.getMenuField() : category.getModelField();
        return FormFieldDefinition.parse(json).size();
    }

    private String baseUrl(SiteProjectEntity site) {
        if (site == null) {
            return "";
        }
        String domain = site.getSiteDomain() == null ? "" : site.getSiteDomain().trim();
        Integer port = site.getSiteServicePort();
        if (domain.isEmpty() && (port == null || port <= 0)) {
            return "";
        }
        String host = domain.isEmpty() ? "localhost" : domain;
        if (!host.startsWith("http://") && !host.startsWith("https://")) {
            host = "http://" + host;
        }
        host = host.replaceAll("/+$", "");
        if (port != null && port > 0 && port != 80 && port != 443 && !hasExplicitPort(host)) {
            host = host + ":" + port;
        }
        return host;
    }

    private boolean hasExplicitPort(String url) {
        int schemeIndex = url.indexOf("://");
        String rest = schemeIndex >= 0 ? url.substring(schemeIndex + 3) : url;
        int slashIndex = rest.indexOf('/');
        if (slashIndex >= 0) {
            rest = rest.substring(0, slashIndex);
        }
        return rest.contains(":");
    }

    private List<EndpointView> endpoints(String baseUrl, String categoryId) {
        String base = baseUrl.isEmpty() ? "{baseUrl}" : baseUrl;
        List<EndpointView> list = new ArrayList<>();

        list.add(
                new EndpointView(
                        "表单定义查询",
                        "GET",
                        "/api/form/definition",
                        List.of(
                                param("categoryId", true, "表单分类ID"),
                                param("key", false, "站点认证key，也可放 X-Form-Key 头")),
                        "curl -H \"X-Form-Key: "
                                + KEY_PLACEHOLDER
                                + "\" \""
                                + base
                                + "/api/form/definition?categoryId="
                                + categoryId
                                + "\"",
                        "{\"code\":0,\"message\":\"ok\",\"data\":{\"categoryId\":"
                                + categoryId
                                + ",\"fields\":[{\"fieldName\":\"singleLine1\",\"label\":\"姓名\",\"required\":true,\"options\":[]}]}}"));

        list.add(
                new EndpointView(
                        "表单显示",
                        "GET",
                        "/api/form/display",
                        List.of(
                                param("categoryId", true, "表单分类ID"),
                                param("id", false, "提交记录ID，传则回填该记录的值"),
                                param("key", false, "站点认证key，也可放 X-Form-Key 头")),
                        "curl -H \"X-Form-Key: "
                                + KEY_PLACEHOLDER
                                + "\" \""
                                + base
                                + "/api/form/display?categoryId="
                                + categoryId
                                + "\"",
                        "{\"code\":0,\"message\":\"ok\",\"data\":{\"categoryId\":"
                                + categoryId
                                + ",\"values\":{\"singleLine1\":\"\"}}}"));

        list.add(
                new EndpointView(
                        "表单提交",
                        "POST",
                        "/api/form/submit",
                        List.of(
                                param("categoryId", true, "表单分类ID（body）"),
                                param("fields", true, "字段值，JSON 对象 {fieldName: value}"),
                                param("captchaKey", false, "验证码标识（分类开启验证码时必填）"),
                                param("captchaCode", false, "验证码内容（分类开启验证码时必填）"),
                                param("key", false, "站点认证key，也可放 X-Form-Key 头")),
                        "curl -X POST -H \"Content-Type: application/json\" -H \"X-Form-Key: "
                                + KEY_PLACEHOLDER
                                + "\" -d '{\"categoryId\":"
                                + categoryId
                                + ",\"fields\":{\"singleLine1\":\"张三\"}}' \""
                                + base
                                + "/api/form/submit\"",
                        "{\"code\":0,\"message\":\"ok\",\"data\":{\"id\":5001,\"queryToken\":\"9f3c...\"}}"));

        list.add(
                new EndpointView(
                        "表单查询",
                        "GET",
                        "/api/form/query",
                        List.of(
                                param("categoryId", true, "表单分类ID"),
                                param("id", false, "传则按提交人回查单条（需 queryToken 或 mobile/email）"),
                                param("queryToken", false, "提交返回的回查凭证"),
                                param("mobile", false, "提交人电话（与记录校验后回查）"),
                                param("email", false, "提交人邮箱（与记录校验后回查）"),
                                param("page", false, "页码，默认1"),
                                param("size", false, "每页条数，默认10，上限100"),
                                param("keyword", false, "标题模糊匹配"),
                                param("beginTime", false, "创建时间起 yyyy-MM-dd HH:mm:ss"),
                                param("endTime", false, "创建时间止 yyyy-MM-dd HH:mm:ss"),
                                param("status", false, "状态"),
                                param("key", false, "站点认证key，也可放 X-Form-Key 头")),
                        "curl -H \"X-Form-Key: "
                                + KEY_PLACEHOLDER
                                + "\" \""
                                + base
                                + "/api/form/query?categoryId="
                                + categoryId
                                + "&page=1&size=10\"",
                        "{\"code\":0,\"message\":\"ok\",\"data\":{\"page\":1,\"size\":10,\"total\":37,\"list\":[]}}"));

        list.add(
                new EndpointView(
                        "认证校验",
                        "GET",
                        "/api/form/auth-check",
                        List.of(param("key", true, "站点认证key，也可放 X-Form-Key 头")),
                        "curl -H \"X-Form-Key: "
                                + KEY_PLACEHOLDER
                                + "\" \""
                                + base
                                + "/api/form/auth-check\"",
                        "{\"code\":0,\"message\":\"ok\",\"data\":{\"valid\":true,\"siteId\":12,\"projectCode\":\"SITE_A\"}}"));

        list.add(
                new EndpointView(
                        "图形验证码",
                        "GET",
                        "/api/form/captcha",
                        List.of(
                                param("categoryId", true, "表单分类ID（分类需开启验证码）"),
                                param("key", false, "站点认证key，也可放 X-Form-Key 头")),
                        "curl -H \"X-Form-Key: "
                                + KEY_PLACEHOLDER
                                + "\" \""
                                + base
                                + "/api/form/captcha?categoryId="
                                + categoryId
                                + "\"",
                        "{\"code\":0,\"message\":\"ok\",\"data\":{\"captchaKey\":\"uuid-xxx\",\"imageBase64\":\"data:image/png;base64,...\"}}"));

        return list;
    }

    private ParamView param(String name, boolean required, String desc) {
        return new ParamView(name, required, desc);
    }
}
