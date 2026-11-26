package com.xinhongru.entity;

import com.xinhongru.common.entity.IntegerEntity;
import com.xinhongru.responseVo.user.SettingVo;

import io.swagger.annotations.ApiModelProperty;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;

import java.util.Date;
import java.util.List;

/**
 * @Description @ClassName UserGroupEntity @Author 永远的神 @Version V1.0.0 @Since 1.0 @Date 2022/5/21
 * 9:10
 */
@Slf4j
@AllArgsConstructor
@NoArgsConstructor
@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
public class UserGroupEntity extends IntegerEntity<UserGroupEntity> {
    @NotBlank(message = "管理员组名称不能为空")
    private String title;

    @Min(value = 0, message = "管理员组父级id不能小于0")
    private Integer parentId = 0;

    /** 组介绍 */
    private String note;

    /** 组锁定状态 */
    private Boolean enabled;

    /** 组删除状态 */
    private Boolean deleted;

    /** 所属站点(cms_ai_site_project.id) */
    private Long siteId;

    @ApiModelProperty(value = "栏目权限", required = true)
    @NotBlank(message = "您尚未设置任何权限")
    private String groupSetting;

    private String otherSetting;

    /** 菜单权限配置（JSON 格式，存储选中的菜单 ID 列表） */
    private String menuSetting;

    /** 数据中台模块权限（逗号分隔模块id：1产品中心/2新闻中心/3解决方案/4案例中心） */
    private String platformSetting;

    /** 数据中台操作权限（分号分段，与 platformSetting 逐位对齐；段内逗号分隔操作id：1新增/2编辑/3删除/4审核/5查看） */
    private String platformOtherSetting;

    private Date createTime = new Date();

    @ApiModelProperty(value = "栏目权限", hidden = true)
    private List<SettingVo> settingVos;

    @ApiModelProperty(value = "管理员组集合", hidden = true)
    private List<UserGroupEntity> userGroupDropdownList;
}
