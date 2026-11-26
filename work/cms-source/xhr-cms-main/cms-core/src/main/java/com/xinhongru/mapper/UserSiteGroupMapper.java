package com.xinhongru.mapper;

import com.xinhongru.entity.UserSiteGroupEntity;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 用户-站点-管理员组关联 Mapper。 */
@Mapper
public interface UserSiteGroupMapper {
    List<UserSiteGroupEntity> selectByUserId(@Param("userId") Integer userId);

    UserSiteGroupEntity selectByUserIdAndSiteId(
            @Param("userId") Integer userId, @Param("siteId") Long siteId);

    int deleteByUserId(@Param("userId") Integer userId);

    int insertBatch(@Param("list") List<UserSiteGroupEntity> list);
}
