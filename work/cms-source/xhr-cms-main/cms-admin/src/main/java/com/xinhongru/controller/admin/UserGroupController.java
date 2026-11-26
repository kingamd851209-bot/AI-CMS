package com.xinhongru.controller.admin;

import com.xinhongru.annotation.SystemLog;
import com.xinhongru.api.admin.AdminAccountAccess;
import com.xinhongru.api.admin.AdminAccountAccess.Scope;
import com.xinhongru.constant.WebConst;
import com.xinhongru.security.AdminSecurityExceptionHandler.AdminSecurityException;
import com.xinhongru.site.context.SiteContextService;
import com.xinhongru.entity.UserGroupEntity;
import com.xinhongru.enums.ErrorCodeEnum;
import com.xinhongru.requestDto.IdDto;
import com.xinhongru.requestDto.userGroup.UserGroupSwitchDto;
import com.xinhongru.responseVo.ResultVO;
import com.xinhongru.service.user.UserService;
import com.xinhongru.service.userGroup.UserGroupService;
import com.xinhongru.utils.TaleUtils;

import io.swagger.annotations.Api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import lombok.extern.slf4j.Slf4j;

import org.apache.commons.collections4.CollectionUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * @Description @ClassName UserGroupController @Author 永远的神 @Version V1.0.0 @Since 1.0 @Date
 * 2022/5/21 9:01
 */
@Slf4j
@Api("管理员组管理类")
@Controller
@RequestMapping(value = "/admin/user_group")
public class UserGroupController {
    private UserGroupService userGroupService;
    private UserService userService;
    @Autowired private AdminAccountAccess accountAccess;
    @Autowired private SiteContextService siteContextService;

    @Autowired
    public void setUserService(UserService userService) {
        this.userService = userService;
    }

    @Autowired
    public void setUserGroupService(UserGroupService userGroupService) {
        this.userGroupService = userGroupService;
    }

    /**
     * 管理员组管理 - 进入管理员组管理页
     *
     * @param request
     * @param response
     * @param model
     * @return
     */
    @GetMapping("/index")
    @SystemLog(value = "管理员组管理 - 进入管理员组管理页", classId = 12)
    public String index(HttpServletRequest request, HttpServletResponse response, Model model) {
        return "redirect:/admin/v2/system/users";
    }

    /**
     * 管理员组管理 - 进入添加管理员组页
     *
     * @param model
     * @return
     */
    @SystemLog(value = "管理员组管理 - 进入添加管理员组页", classId = 12)
    @GetMapping("/add/{parentId}")
    public String userGroupAdd(
            @PathVariable Integer parentId,
            Model model,
            HttpServletRequest request,
            HttpServletResponse response) {
        return "redirect:/admin/v2/system/users";
    }

    /**
     * 管理员组管理 - 进入添加管理员组页
     *
     * @param model
     * @return
     */
    @SystemLog(value = "管理员组管理 - 进入修改管理员组页", classId = 12)
    @GetMapping("/edit/{id}")
    public String userGroupEdit(
            @PathVariable Integer id,
            Model model,
            HttpServletRequest request,
            HttpServletResponse httpServletResponse) {
        return "redirect:/admin/v2/system/users";
    }

    /**
     * 管理员组管理 - 进入添加管理员组页
     *
     * @param model
     * @return
     */
    @SystemLog(value = "管理员组管理 - 保存管理员组信息", classId = 12)
    @ResponseBody
    @PostMapping("/edit")
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public ResultVO saveUserGroup(
            @RequestBody @Validated UserGroupEntity userGroupEntity,
            Model model,
            HttpServletRequest request,
            HttpServletResponse response) {
        if (TaleUtils.getLoginUser(request) == null) {
            return ResultVO.fail(ErrorCodeEnum.USER_NOT_LOGIN);
        }
        try {
            Scope access = accountAccess.requireScope(request);
            if (userGroupEntity.getId() == null) userGroupEntity.setId(0);
            boolean create = userGroupEntity.getId() == 0;
            UserGroupEntity before = create ? null : userGroupService.getUserGroupById(userGroupEntity.getId());
            if (!create) access.requireGroupAccess(before);
            Long site = create
                    ? (WebConst.PLATFORM_SITE_ID.equals(userGroupEntity.getSiteId())
                            ? WebConst.PLATFORM_SITE_ID : siteContextService.current(request).getSiteId())
                    : before.getSiteId();
            access.prepareGroupSave(userGroupEntity, before, site);
        } catch (AdminSecurityException denied) {
            return ResultVO.fail(ErrorCodeEnum.UNAUTHORIZED_OPERATION);
        }

        return userGroupService.addOrUpdateUserGroup(userGroupEntity);
    }

    @SystemLog(value = "管理员组管理 - 删除管理员组", classId = 12)
    @ResponseBody
    @PostMapping("/delete")
    public ResultVO userGroupDelete(
            @RequestBody @Validated IdDto idDto,
            HttpServletRequest request,
            HttpServletResponse response) {
        if (TaleUtils.getLoginUser(request) == null) {
            return ResultVO.fail(ErrorCodeEnum.USER_NOT_LOGIN);
        }
        if (!mayChangeGroupStatus(request, idDto.getId())) {
            return ResultVO.fail(ErrorCodeEnum.UNAUTHORIZED_OPERATION);
        } else if (idDto.getId().equals(1) || idDto.getId().equals(2)) {
            return ResultVO.fail(ErrorCodeEnum.TOP_LEVEL_ADMINISTRATOR_GROUP_CANNOT_BE_DELETED);
        } else if (CollectionUtils.isNotEmpty(
                userGroupService.getUserGroupListByParentId(idDto.getId()))) {
            return ResultVO.fail(ErrorCodeEnum.HAS_CHILD_USER_GROUP_DATA);
        } else if (CollectionUtils.isNotEmpty(userService.getUserListByGroupId(idDto.getId()))) {
            return ResultVO.fail(ErrorCodeEnum.HAS_USER_IN_GROUP);
        }

        Boolean deleteUserGroup = userGroupService.deleteUserGroup(idDto.getId());
        if (deleteUserGroup) {
            return ResultVO.success(ErrorCodeEnum.DELETE_SUCCESS);
        } else {
            return ResultVO.fail(ErrorCodeEnum.DELETE_FAIL);
        }
    }

    /**
     * 管理员组 - 修改管理员组可用状态
     *
     * @param userGroupSwitchDto
     * @param request
     * @param response
     * @param model
     * @return
     */
    @ResponseBody
    @PostMapping("/updateSwitch")
    @SystemLog(value = "管理员组 - 修改管理员组可用状态", classId = 12)
    public ResultVO updateSwitch(
            @RequestBody @Validated UserGroupSwitchDto userGroupSwitchDto,
            HttpServletRequest request,
            HttpServletResponse response,
            Model model) {
        if (!mayChangeGroupStatus(request, userGroupSwitchDto.getId())) {
            return ResultVO.fail(ErrorCodeEnum.UNAUTHORIZED_OPERATION);
        }
        return userGroupService.updateSwitch(userGroupSwitchDto, request);
    }

    private boolean mayChangeGroupStatus(HttpServletRequest request, Integer id) {
        try {
            Scope access = accountAccess.requireScope(request);
            UserGroupEntity target = userGroupService.getUserGroupById(id);
            return access.canManageGroup(target) && !WebConst.PLATFORM_SITE_ID.equals(target.getSiteId());
        } catch (AdminSecurityException denied) {
            return false;
        }
    }
}
