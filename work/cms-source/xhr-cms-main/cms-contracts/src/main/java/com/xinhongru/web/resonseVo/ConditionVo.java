package com.xinhongru.web.resonseVo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

/**
 * @Description @Copyright (c) 1998-2022 北京新鸿儒世纪网络技术有限公司 All Rights Reserved. @Url
 * https://www.xinhongru.com @ClassName ConditionVo @Author guoyangyang <519156724@qq.com> @Version
 * V1.0.0 @Since 1.0 @Date 2022/12/11 12:07
 */
@AllArgsConstructor
@NoArgsConstructor
@Data
@Accessors(chain = true)
public class ConditionVo {
    /** 按标题搜索 */
    private String key;

    private Integer classIds;

    /** 查询年份 */
    private Integer year;

    /** 查询月份 */
    private Integer month;

    /** 单独设置的年份字段 */
    private String yearString;

    /** 项目分类 */
    private String type;

    /** 区域 */
    private String area;

    private Integer pageIndex;

    /** 按标题搜索 */
    private String keywords;
}
