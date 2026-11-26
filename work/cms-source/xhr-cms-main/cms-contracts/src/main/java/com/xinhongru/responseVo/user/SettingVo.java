package com.xinhongru.responseVo.user;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;
import java.util.List;

/**
 * @Description @ClassName UserVo @Author 永远的神 @Version V1.0.0 @Since 1.0 @Date 2022/3/27 11:36
 */
@Slf4j
@AllArgsConstructor
@NoArgsConstructor
@Data
@Accessors(chain = true)
public class SettingVo implements Serializable {
    /** 栏目ID */
    private Integer id;

    /** 栏目名称 */
    private String className;

    /** 父级栏目ID 顶级为0 */
    private int parentId;

    /** 排序号 默认为0 */
    private int sort;

    /** 权限ID集合 */
    private String setting;

    /** 是否后台展示 */
    private Boolean hasManageChannel;

    /** 页面类型 0单篇 1列表 */
    private Integer pageType;

    /** 是否删除 */
    private Boolean isDeleted;

    /** 子权限集合 */
    private List<SettingOptionVo> settingOptionVos;
}
