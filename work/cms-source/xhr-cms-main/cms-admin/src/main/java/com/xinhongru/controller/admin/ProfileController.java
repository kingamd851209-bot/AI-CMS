package com.xinhongru.controller.admin;

import com.xinhongru.annotation.SystemLog;
import com.xinhongru.requestDto.profile.UpdatePwdDto;
import com.xinhongru.requestDto.profile.UserDto;
import com.xinhongru.responseVo.ResultVO;
import com.xinhongru.service.user.UserService;

import io.swagger.annotations.Api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * @Description @ClassName ProfileController @Author 永远的神 @Version V1.0.0 @Since 1.0 @Date 2022/3/29
 * 20:18
 */
@Api("个人设置")
@Controller
@RequestMapping(value = "/admin/profile")
public class ProfileController {
    private UserService userService;

    @Autowired
    public void setUserService(UserService userService) {
        this.userService = userService;
    }

    /**
     * 个人设置 - 进入个人设置页
     *
     * @param model
     * @param httpServletRequest
     * @return
     */
    @SystemLog(value = "个人设置 - 进入个人设置页", classId = 5)
    @GetMapping(value = "/user")
    public String profileUser(Model model, HttpServletRequest httpServletRequest) {
        return "redirect:/admin/v2/system/users";
    }

    /**
     * 个人设置 - 进入密码修改页
     *
     * @param model
     * @param httpServletRequest
     * @return
     */
    @SystemLog(value = "个人设置 - 进入密码修改页", classId = 8)
    @GetMapping(value = "/password")
    public String profilePwd(Model model, HttpServletRequest httpServletRequest) {
        return "redirect:/admin/v2/system/users";
    }

    /**
     * 个人设置 - 保存个人信息
     *
     * @param userDto
     * @param request
     * @param session
     * @return
     */
    @SystemLog(value = "个人设置 - 保存个人信息", classId = 5)
    @PostMapping(value = "/user")
    @ResponseBody
    public ResultVO saveProfile(
            @RequestBody @Validated UserDto userDto,
            HttpServletRequest request,
            HttpSession session) {
        return userService.saveProfile(userDto, request, session);
    }

    /**
     * 个人设置 - 更新个人密码
     *
     * @param updatePwdDto
     * @param request
     * @param session
     * @return
     */
    @SystemLog(value = "个人设置 - 保存个人密码", classId = 8)
    @PostMapping(value = "/password")
    @ResponseBody
    public ResultVO updatePassword(
            @RequestBody @Validated UpdatePwdDto updatePwdDto,
            HttpServletRequest request,
            HttpSession session) {
        return userService.updatePassword(updatePwdDto, request, session);
    }
}
