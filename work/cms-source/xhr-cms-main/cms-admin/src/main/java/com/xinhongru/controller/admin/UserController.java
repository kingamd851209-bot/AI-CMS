package com.xinhongru.controller.admin;

import com.xinhongru.annotation.SystemLog;
import com.xinhongru.api.admin.AdminAccountAccess;
import com.xinhongru.api.admin.AdminAccountAccess.Scope;
import com.xinhongru.constant.WebConst;
import com.xinhongru.entity.UserEntity;
import com.xinhongru.enums.ErrorCodeEnum;
import com.xinhongru.requestDto.IdDto;
import com.xinhongru.requestDto.user.UserDto;
import com.xinhongru.responseVo.ResultVO;
import com.xinhongru.security.password.Sm3PasswordHashService;
import com.xinhongru.service.log.LogService;
import com.xinhongru.service.user.UserService;
import com.xinhongru.service.userSiteGroup.UserSiteGroupService;
import com.xinhongru.security.AdminSecurityExceptionHandler.AdminSecurityException;
import com.xinhongru.utils.RsaUtils;
import com.xinhongru.utils.TaleUtils;

import io.swagger.annotations.Api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.ui.Model;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Objects;

/** Created at 2021/4/20. */
@Api("用户管理类")
@Controller
@RequestMapping(value = "/admin/user")
public class UserController {
    @Autowired private AdminAccountAccess accountAccess;
    @Autowired private UserSiteGroupService userSiteGroupService;
    private UserService userService;
    private LogService logService;
    private Sm3PasswordHashService passwordHashService;

    @Autowired
    public void setUserService(UserService userService) {
        this.userService = userService;
    }

    @Autowired
    public void setLogService(LogService logService) {
        this.logService = logService;
    }

    @Autowired
    public void setPasswordHashService(Sm3PasswordHashService passwordHashService) {
        this.passwordHashService = passwordHashService;
    }

    /**
     * 用户管理 - 查看用户列表
     *
     * @param model
     * @param httpServletRequest
     * @return
     */
    @SystemLog(value = "用户管理 - 查看用户列表", classId = 6)
    @GetMapping("/index")
    public String user(Model model, HttpServletRequest httpServletRequest) {
        return "redirect:/admin/v2/system/users";
    }

    /**
     * 用户管理 - 添加用户
     *
     * @param model
     * @return
     */
    @SystemLog(value = "用户管理 - 添加用户", classId = 6)
    @GetMapping("/add")
    public String userAdd(Model model, HttpServletRequest request) {
        return "redirect:/admin/v2/system/users";
    }

    /**
     * 用户管理 - 删除用户
     *
     * @param idDto
     * @param request
     * @param session
     * @return
     */
    @SystemLog(value = "用户管理 - 删除用户", classId = 6)
    @PostMapping("/delete")
    @ResponseBody
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ResultVO userDelete(
            @RequestBody @Validated IdDto idDto, HttpServletRequest request, HttpSession session) {
        if (this.user(request) == null) {
            return ResultVO.fail(ErrorCodeEnum.USER_NOT_LOGIN);
        }
        if (Integer.valueOf(1).equals(idDto.getId()) || WebConst.SUPER_ADMIN_ID.equals(idDto.getId())) {
            return ResultVO.fail(ErrorCodeEnum.SUPER_ADMIN_CANNOT_DELETE);
        }
        try {
            accountAccess.requireScope(request).requireAccountWrite(userService.lockUserById(idDto.getId()));
        } catch (AdminSecurityException denied) {
            return ResultVO.fail(ErrorCodeEnum.UNAUTHORIZED_OPERATION);
        }
        if (!userService.deleteUser(idDto.getId())) {
            return ResultVO.fail(ErrorCodeEnum.DELETE_FAIL);
        }
        userSiteGroupService.deleteByUserId(idDto.getId());
        return ResultVO.success(ErrorCodeEnum.DELETE_SUCCESS);
    }

    /**
     * 用户管理 - 修改用户
     *
     * @param uid
     * @param model
     * @return
     */
    @SystemLog(value = "用户管理 - 修改用户", classId = 6)
    @GetMapping("/edit/{uid}")
    public String userEdit(@PathVariable Integer uid, Model model, HttpServletRequest request) {
        return "redirect:/admin/v2/system/users";
    }

    /**
     * 获取请求绑定的登录对象
     *
     * @param request
     * @return
     */
    public UserEntity user(HttpServletRequest request) {
        return TaleUtils.getLoginUser(request);
    }

    public Integer getUid(HttpServletRequest request) {
        return this.user(request).getId();
    }

    /**
     * 用户管理 - 保存用户信息
     *
     * @param userDto
     * @param request
     * @param session
     * @return
     */
    @SystemLog(value = "用户管理 - 保存用户信息", classId = 6)
    @ResponseBody
    @PostMapping(value = "/edit")
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ResultVO saveProfile(
            @RequestBody @Validated UserDto userDto,
            HttpServletRequest request,
            HttpSession session) {
        if (this.user(request) == null) {
            return ResultVO.fail(ErrorCodeEnum.USER_NOT_LOGIN);
        }
        try {
            Scope scope = accountAccess.requireScope(request);
            boolean create = Integer.valueOf(0).equals(userDto.getId());
            UserEntity target = create ? null : userService.lockUserById(userDto.getId());
            if (create) {
                AdminAccountAccess.requireCreatableUsername(userDto.getUsername());
            } else {
                scope.requireAccountWrite(target);
            }
            scope.requirePrimaryGroup(userDto.getGroupId(), target);
        } catch (AdminSecurityException denied) {
            return ResultVO.fail(ErrorCodeEnum.UNAUTHORIZED_OPERATION);
        }

        if (StringUtils.isNotBlank(userDto.getPassword())) {
            userDto.setPassword(RsaUtils.decryptByPrivateKey(userDto.getPassword()));
            if (StringUtils.isEmpty(userDto.getPassword())) {
                return ResultVO.fail(ErrorCodeEnum.PASSWORD_FORMAT_ERROR);
            }
        }
        if (StringUtils.isNotBlank(userDto.getRepass())) {
            userDto.setRepass(RsaUtils.decryptByPrivateKey(userDto.getRepass()));
            if (StringUtils.isEmpty(userDto.getRepass())) {
                return ResultVO.fail(ErrorCodeEnum.PASSWORD_FORMAT_ERROR);
            }
        }

        if (!Objects.equals(userDto.getPassword(), userDto.getRepass())) {
            return ResultVO.fail(ErrorCodeEnum.TWICE_PASSWORD_NOT_THE_SAME);
            //        } else if (StringUtils.isBlank(userDto.getPassword())) {
            //            return ResultVO.fail(ErrorCodeEnum.PASSWORD_LENGTH_MUST_BETWEEN_8_END_24);
        }
        if (StringUtils.isNotBlank(userDto.getScreenName())
                && StringUtils.isNotBlank(userDto.getEmail())) {
            UserEntity temp = new UserEntity();
            if (userDto.getId() == 0) {
                /**
                 * @Date: 2021/10/30 20:26 @Step 第1步: 添加用户
                 */
                int hasUsername = userService.hasUsername(userDto.getUsername());
                if (hasUsername > 0) {
                    return ResultVO.fail(ErrorCodeEnum.USER_ALREADY_EXISTS);
                }
                int hasEmail = userService.hasEmail(userDto.getEmail());
                if (hasEmail > 0) {
                    return ResultVO.fail(ErrorCodeEnum.EMAIL_ALREADY_EXISTS);
                }
                Long currentTime = System.currentTimeMillis() / 1000;
                temp.setPassword(passwordHashService.hash(userDto.getPassword()));
                temp.setEmail(userDto.getEmail());
                temp.setScreenName(userDto.getScreenName());
                temp.setUsername(userDto.getUsername());
                temp.setCreated(currentTime.intValue());
                temp.setGroupId(userDto.getGroupId());
                userService.addUser(temp);
            } else {
                /**
                 * @Date: 2021/10/30 20:26 @Step 第2步: 修改用户
                 */
                temp.setId(userDto.getId());
                temp.setScreenName(userDto.getScreenName());
                temp.setEmail(userDto.getEmail());
                temp.setGroupId(userDto.getGroupId());
                if (StringUtils.isNotBlank(userDto.getPassword())) {
                    String pwd = passwordHashService.hash(userDto.getPassword());
                    temp.setPassword(pwd);
                }
                userService.updateUserInfo(temp);
            }
        }
        return ResultVO.success(ErrorCodeEnum.SAVE_SUCCESS);
    }
}
