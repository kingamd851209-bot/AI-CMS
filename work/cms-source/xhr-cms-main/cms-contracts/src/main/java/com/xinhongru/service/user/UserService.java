package com.xinhongru.service.user;

import com.xinhongru.entity.UserEntity;
import com.xinhongru.requestDto.LoginDto;
import com.xinhongru.requestDto.profile.UpdatePwdDto;
import com.xinhongru.requestDto.profile.UserDto;
import com.xinhongru.responseVo.ResultVO;
import com.xinhongru.responseVo.user.SettingVo;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import org.springframework.stereotype.Service;

import java.util.List;

/** Created at 2021/4/20. */
@Service
public interface UserService {
    /**
     * @return @Author: Donghua.Chen @Description: 删除 @Date: 2018/4/20
     */
    boolean deleteUser(Integer uid);

    /**
     * 查询当前登录用户的所有子集管理员
     *
     * @param httpServletRequest
     * @return
     */
    List<UserEntity> getUserList(HttpServletRequest httpServletRequest);

    /**
     * 管理员管理列表：返回全部管理员（普通管理员隐藏超级管理员），并填充各自所属管理员组名称。
     *
     * @param httpServletRequest
     * @return
     */
    List<UserEntity> listAllAdminUsers(HttpServletRequest httpServletRequest);

    /**
     * @param user @Author: Donghua.Chen @Description: 更改用户信息 @Date: 2018/4/20
     */
    int updateUserInfo(UserEntity user);

    /**
     * @param uId 主键 @Author: Donghua.Chen @Description: 根据主键编号获取用户信息 @Date: 2018/4/20
     */
    UserEntity getUserInfoById(Integer uId, Boolean isTree);

    /** 事务中读取并锁定待管理账号。 */
    UserEntity lockUserById(Integer id);

    /**
     * 用户登录
     *
     * @param username 用户名
     * @param password 密码
     * @param ip ip
     * @return
     */
    UserEntity login(String username, String password, String ip);

    /**
     * 添加用户
     *
     * @param temp
     */
    boolean addUser(UserEntity temp);

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
     * 获取所有栏目的可选权限集合
     *
     * @return
     */
    List<SettingVo> getSettingVos();

    /**
     * 通过管理员组ID查询管理员集合
     *
     * @param groupId
     * @return
     */
    List<UserEntity> getUserListByGroupId(Integer groupId);

    /**
     * 个人设置 - 保存个人信息
     *
     * @param userDto
     * @param request
     * @param session
     * @return
     */
    ResultVO saveProfile(UserDto userDto, HttpServletRequest request, HttpSession session);

    /**
     * 个人设置 - 更新个人密码
     *
     * @param updatePwdDto
     * @param request
     * @param session
     * @return
     */
    ResultVO updatePassword(
            UpdatePwdDto updatePwdDto, HttpServletRequest request, HttpSession session);

    /**
     * 管理员登录
     *
     * @param loginDto
     * @param response
     * @param request
     * @return
     */
    ResultVO toLogin(LoginDto loginDto, HttpServletResponse response, HttpServletRequest request);
}
