package com.xinhongru.entity;

import com.xinhongru.common.entity.IntegerEntity;

import io.swagger.annotations.ApiModelProperty;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.List;

/** Created at 2021/4/20. */
@Slf4j
@AllArgsConstructor
@NoArgsConstructor
@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
public class UserEntity extends IntegerEntity<UserEntity> {
    /** 用户名 */
    private String username;

    /** 密码 */
    private String password;

    /** email */
    private String email;

    /** 主页地址 */
    private String homeUrl;

    /** 用户显示的名称 */
    private String screenName;

    /** 用户注册时的GMT unix时间戳 */
    private Integer created;

    /** 是否锁定：0 正常，1 锁定 */
    private Integer activated;

    /** 是否登录 */
    private Integer logged;

    /** 用户组 */
    private String groupName;

    /** 用户组ID */
    private Integer groupId;

    /** 栏目权限 */
    private String groupSetting;

    /** 按钮权限 */
    private String otherSetting;

    /** 最后登录时间 */
    private LocalDateTime lastLoginTime;

    /** 最后登录IP地址 */
    private String lastLoginIp;

    /** 累计登录次数 */
    private Integer loginTimes;

    //    @ApiModelProperty(value = "栏目权限",hidden = true)
    //    private List<SettingVo> settingVos;

    @ApiModelProperty(value = "管理员组集合", hidden = true)
    private List<UserGroupEntity> userGroupDropdownList;
}
