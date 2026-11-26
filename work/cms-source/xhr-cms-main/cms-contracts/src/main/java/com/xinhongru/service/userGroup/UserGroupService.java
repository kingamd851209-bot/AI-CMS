package com.xinhongru.service.userGroup;

import com.xinhongru.entity.UserGroupEntity;
import com.xinhongru.requestDto.userGroup.UserGroupSwitchDto;
import com.xinhongru.responseVo.ResultVO;
import com.xinhongru.responseVo.user.SettingVo;

import jakarta.servlet.http.HttpServletRequest;

import java.util.List;

/**
 * @Description @Author 永远的神 @Version V1.0.0 @Since 1.0 @Date 2022/5/21 10:04
 */
public interface UserGroupService {
    /**
     * 添加管理员组
     *
     * @param userGroupEntity
     * @return
     */
    Boolean addUserGroup(UserGroupEntity userGroupEntity);

    /**
     * 根据管理员组ID删除管理员组
     *
     * @param groupId
     * @return
     */
    Boolean deleteUserGroup(Integer groupId);

    /**
     * 获取管理员组集合
     *
     * @return
     */
    List<UserGroupEntity> getUserGroupList(Integer parentId, HttpServletRequest request);

    /**
     * 获取指定站点的管理员组集合（树形）
     *
     * @param siteId 站点id
     * @param parentId
     * @param request
     * @return
     */
    List<UserGroupEntity> getUserGroupListBySite(
            Long siteId, Integer parentId, HttpServletRequest request);

    /**
     * 获取当前登录用户拥有管理员组的顶级ID
     *
     * @param request
     * @return
     */
    Integer getTopParentId(HttpServletRequest request);

    /**
     * 修改管理员组
     *
     * @param userGroupEntity
     * @return
     */
    Boolean updateUserGroup(UserGroupEntity userGroupEntity);

    /**
     * 根据管理员组ID获取管理员组对象
     *
     * @param groupId
     * @return
     */
    UserGroupEntity getUserGroupById(Integer groupId);

    /**
     * 获取当前登录管理员权限集合
     *
     * @return
     * @param groupId
     * @param request
     */
    List<SettingVo> getSettingVos(int groupId, HttpServletRequest request);

    /**
     * 获取管理员组集合 下拉框类型的
     *
     * @param parentId
     * @param request
     * @return
     */
    List<UserGroupEntity> getDropdownList(Integer parentId, HttpServletRequest request);

    /**
     * 获取指定站点的管理员组集合 下拉框类型的
     *
     * @param siteId 站点id
     * @param parentId
     * @param request
     * @return
     */
    List<UserGroupEntity> getDropdownListBySite(
            Long siteId, Integer parentId, HttpServletRequest request);

    /**
     * 获取管理员组集合 纯数据类型
     *
     * @param parentId
     * @param request
     * @return
     */
    List<UserGroupEntity> getChildUserGroupList(Integer parentId, HttpServletRequest request);

    /**
     * 保存或修改管理员组信息
     *
     * @param userGroupEntity
     * @return
     */
    ResultVO addOrUpdateUserGroup(UserGroupEntity userGroupEntity);

    /**
     * 根据父级ID获取管理员组
     *
     * @param parentId
     * @return
     */
    List<UserGroupEntity> getUserGroupListByParentId(Integer parentId);

    /**
     * 根据管理员组ID获取组名
     *
     * @param groupId
     * @return
     */
    String getUserGroupNameByGroupId(int groupId);

    /**
     * 修改管理员组可用状态
     *
     * @param userGroupSwitchDto
     * @param request
     * @return
     */
    ResultVO updateSwitch(UserGroupSwitchDto userGroupSwitchDto, HttpServletRequest request);
}
