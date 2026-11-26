package com.xinhongru.web.resonseVo;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;

/**
 * @Description @ClassName IdEntity @Author 永远的神 @Version V1.0.0 @Since 1.0 @Date 2022/4/17 13:32
 */
@Slf4j
@Data
@NoArgsConstructor
@Accessors(chain = true)
public class IdEntity {
    private Integer classId = 0;
    private Integer parentId = 0;
    private Integer infoId = 0;
    private Integer relationId = 0;
    private Integer pageIndex = 1;
    private Integer pageSize = 10;

    /** 产品内容 id（{productId} 占位符捕获；用于产品对比等按产品出内容的列表页）。 */
    private Integer productId = 0;

    /** 分页地址规则 默认为list_{parentId}_{classId}_{pageIndex}.html */
    private String pagePath;

    private String bizCode;

    private Long siteProjectId;

    @com.fasterxml.jackson.annotation.JsonIgnore
    @com.alibaba.fastjson.annotation.JSONField(serialize = false, deserialize = false)
    @lombok.Setter(lombok.AccessLevel.NONE)
    private Long serverSiteProjectId;

    @com.fasterxml.jackson.annotation.JsonIgnore
    @com.alibaba.fastjson.annotation.JSONField(serialize = false, deserialize = false)
    @lombok.Setter(lombok.AccessLevel.NONE)
    private java.util.Map<String, String> queryValues = java.util.Map.of();

    @com.fasterxml.jackson.annotation.JsonIgnore
    @com.alibaba.fastjson.annotation.JSONField(serialize = false, deserialize = false)
    @lombok.Getter(lombok.AccessLevel.NONE)
    @lombok.Setter(lombok.AccessLevel.NONE)
    private boolean queryValuesBound;

    /** 仅服务器列表筛选通路填充；模板只按固定键读取，不把这些键当权限。 */
    public void bindQueryValues(java.util.Map<String, String> values) {
        if (serverSiteProjectId == null
                || !serverSiteProjectId.equals(siteProjectId)
                || values == null
                || values.size() > 64) throw new IllegalArgumentException("列表筛选上下文无效或超限");
        int bytes = 0;
        var copy = new java.util.LinkedHashMap<String, String>();
        for (var entry : values.entrySet()) {
            String key = entry.getKey(), value = entry.getValue();
            if (key == null
                    || key.isBlank()
                    || key.length() > 128
                    || value == null
                    || value.length() > 1000
                    || java.util.Set.of(
                                    "siteId",
                                    "siteProjectId",
                                    "serverSiteProjectId",
                                    "classId",
                                    "parentId",
                                    "infoId",
                                    "id",
                                    "productId",
                                    "pagePath",
                                    "bizCode")
                            .contains(key)) throw new IllegalArgumentException("列表筛选参数无效");
            bytes +=
                    key.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                            + value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if (bytes > 16 * 1024) throw new IllegalArgumentException("列表筛选参数总量超过16KB");
            copy.put(key, value);
        }
        if (queryValuesBound && !queryValues.equals(copy))
            throw new IllegalArgumentException("列表筛选上下文不能更换");
        queryValues = java.util.Collections.unmodifiableMap(copy);
        queryValuesBound = true;
    }

    /** 仅由站点服务器绑定，不作为请求参数setter。 */
    public void bindServerSite(Long siteId) {
        if (siteId == null || siteId <= 0) throw new IllegalArgumentException("当前服务器站点无效");
        if (serverSiteProjectId != null && !serverSiteProjectId.equals(siteId))
            throw new IllegalArgumentException("当前服务器站点不能更换");
        this.serverSiteProjectId = siteId;
        this.siteProjectId = siteId;
    }

    /** 保留旧公共构造器；站点参数本身不会产生可信服务器身份。 */
    public IdEntity(
            Integer classId,
            Integer parentId,
            Integer infoId,
            Integer relationId,
            Integer pageIndex,
            Integer pageSize,
            Integer productId,
            String pagePath,
            String bizCode,
            Long siteProjectId,
            ConditionVo conditionVo) {
        this.classId = classId;
        this.parentId = parentId;
        this.infoId = infoId;
        this.relationId = relationId;
        this.pageIndex = pageIndex;
        this.pageSize = pageSize;
        this.productId = productId;
        this.pagePath = pagePath;
        this.bizCode = bizCode;
        this.siteProjectId = siteProjectId;
        this.conditionVo = conditionVo;
    }

    /** 搜索条件 */
    private ConditionVo conditionVo;
}
