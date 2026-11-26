package com.xinhongru.mapper;

import com.xinhongru.entity.UserGroupEntity;
import com.xinhongru.requestDto.userGroup.UserGroupSwitchDto;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * @Description @Author 永远的神 @Version V1.0.0 @Since 1.0 @Date 2022/5/21 9:17
 */
@Mapper
public interface UserGroupMapper {
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
    Boolean deleteUserGroup(@Param("groupId") Integer groupId);

    /**
     * 获取管理员组集合
     *
     * @param siteId 站点id，null 表示不过滤（全量）
     * @return
     */
    List<UserGroupEntity> getUserGroupList(@Param("siteId") Long siteId);

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
    UserGroupEntity getUserGroupById(@Param("groupId") Integer groupId);

    /**
     * 修改管理员组可用状态
     *
     * @param userGroupById
     * @return
     */
    Boolean updateSwitch(UserGroupSwitchDto userGroupById);
}
