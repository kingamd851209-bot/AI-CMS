package com.xinhongru.mapper;

import com.xinhongru.entity.UserEntity;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** Created at 2021/4/20. */
@Mapper
public interface UserMapper {
    /**
     * @Author: Donghua.Chen @Description: 删除 @Date: 2018/4/20
     *
     * @return
     */
    boolean deleteUser(Integer id);

    /**
     * @Author: Donghua.Chen @Description: 查询用户列表 @Date: 2018/4/20
     *
     * @return
     */
    List<UserEntity> getUserList();

    /**
     * @Author: Donghua.Chen @Description: 更改用户信息 @Date: 2018/4/20
     *
     * @param user
     */
    int updateUserInfo(UserEntity user);

    /**
     * @Author: Donghua.Chen @Description: 根据主键编号获取用户信息 @Date: 2018/4/20
     *
     * @param id 主键
     */
    UserEntity getUserInfoById(@Param("id") Integer id);

    UserEntity lockUserById(@Param("id") Integer id);

    /**
     * 根据用户名和密码获取用户信息
     *
     * @param username
     * @param password
     * @return
     */
    UserEntity getUserInfoByCond(
            @Param("username") String username, @Param("password") String password);

    /**
     * 添加用户
     *
     * @param userEntity
     * @return
     */
    boolean addUser(UserEntity userEntity);

    /**
     * 检测用户名是否存在
     *
     * @param username
     * @return
     */
    int hasUsername(String username);

    /**
     * 检查邮箱是否存在
     *
     * @param email
     * @return
     */
    int hasEmail(String email);

    /**
     * 通过管理员组ID查询管理员集合
     *
     * @param groupId
     * @return
     */
    List<UserEntity> getUserListByGroupId(Integer groupId);

    /**
     * 根据用户名获取用户信息
     *
     * @param username
     * @return
     */
    UserEntity getUserInfoByUserName(@Param("username") String username);
}
