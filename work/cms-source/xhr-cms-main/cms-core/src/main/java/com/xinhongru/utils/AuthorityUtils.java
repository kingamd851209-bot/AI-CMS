package com.xinhongru.utils;

import com.alibaba.fastjson.JSON;
import com.xinhongru.entity.UserEntity;
import com.xinhongru.enums.ErrorCodeEnum;
import com.xinhongru.responseVo.ResultVO;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import lombok.extern.slf4j.Slf4j;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * @Description 判断是否有权限的工具类 @ClassName AuthorityUtils @Author 永远的神 @Version V1.0.0 @Since 1.0 @Date
 * 2022/3/27 22:27
 */
@Slf4j
@Component
public class AuthorityUtils {
    private static com.xinhongru.permission.AdminPermissionService permissionService;

    @Autowired
    public void setPermissionService(com.xinhongru.permission.AdminPermissionService service) {
        permissionService = service;
    }

    /**
     * 获取当前登录用户是否拥有当前栏目的权限
     *
     * @param request
     * @param menuId
     * @return
     */
    public static boolean hasGroupSetting(HttpServletRequest request, int menuId) {
        return permissionService.snapshot(request).hasMenuAccess(menuId);
    }

    /**
     * 拥有栏目集合的任意一个权限即可
     *
     * @param request
     * @param menuIds
     * @return
     */
    public static boolean hasGroupSettingByAnyMenuId(HttpServletRequest request, int[] menuIds) {
        var snapshot = permissionService.snapshot(request);
        return snapshot.isAll() || Arrays.stream(menuIds).anyMatch(snapshot::hasMenuAccess);
    }

    /**
     * 获取当前登录用户是否拥有当前栏目 当前操作的权限
     *
     * @param request
     * @param menuId
     * @param optionId
     * @return
     */
    public static boolean hasOtherSetting(HttpServletRequest request, int menuId, int optionId) {
        return permissionService.snapshot(request).canOperate(menuId, optionId);
    }

    /**
     * 判断用户是否具有当前栏目权限
     *
     * @param userEntity
     * @param menuId
     * @return
     */
    public static boolean hasGroupSetting(UserEntity userEntity, int menuId) {
        return hasGroupSetting(userEntity.getGroupSetting(), menuId);
    }

    /**
     * 判断用户是否具有当前按钮的权限
     *
     * @param userEntity
     * @param menuId
     * @param optionId
     * @return
     */
    public static boolean hasOtherSetting(UserEntity userEntity, int menuId, int optionId) {
        return hasOtherSetting(
                userEntity.getGroupSetting(), userEntity.getOtherSetting(), menuId, optionId);
    }

    public static boolean hasGroupSetting(String groupSetting, int menuId) {
        List<String> groupSettingList = new ArrayList<>();
        if (StringUtils.isBlank(groupSetting)) {
            return false;
        }
        groupSettingList = Arrays.asList(groupSetting.split(","));
        return groupSettingList.contains(String.valueOf(menuId));
    }

    public static boolean hasOtherSetting(
            String groupSetting, String otherSetting, int menuId, int optionId) {
        if (!hasGroupSetting(groupSetting, menuId)) {
            return false;
        }
        if (StringUtils.isBlank(otherSetting)) {
            return false;
        }
        List<String> groupSettingList = new ArrayList<>();
        groupSettingList = Arrays.asList(groupSetting.split(","));

        List<String> otherSettingList = new ArrayList<>();
        otherSettingList = Arrays.asList(otherSetting.split(";", groupSettingList.size()));

        int index = groupSettingList.indexOf(String.valueOf(menuId));
        if (index == -1) {
            return false;
        }
        if (StringUtils.isBlank(otherSettingList.get(index))) {
            return false;
        }
        return Arrays.asList(otherSettingList.get(index).split(","))
                .contains(String.valueOf(optionId));
    }

    /**
     * 校验权限 视图专用
     *
     * @param classId
     * @param httpServletRequest
     * @param httpServletResponse
     * @return
     */
    public static String checkAuthorityForView(
            Integer classId,
            HttpServletRequest httpServletRequest,
            HttpServletResponse httpServletResponse) {
        // 判断当前登录用户是否有用此栏目的权限
        // if(!AuthorityUtils.hasGroupSetting(httpServletRequest, classId)){
        //    httpServletResponse.setContentType("text/html;charset=utf-8");
        //    PrintWriter out = null;
        //    try {
        //        out = httpServletResponse.getWriter();
        //
        // out.print("<script>alert('您试图越权访问，系统已经拒绝你的请求。');window.parent.location.href='/admin/logout';</script>");
        //        out.flush();
        //        out.close();
        //    } catch (IOException e) {
        //        e.printStackTrace();
        //        return "redirect:/admin/logout";
        //    }
        // }
        return null;
    }

    public static String checkAuthorityForView(
            int[] classIds,
            HttpServletRequest httpServletRequest,
            HttpServletResponse httpServletResponse) {
        // 判断当前登录用户是否有用此栏目的权限
        if (!AuthorityUtils.hasGroupSettingByAnyMenuId(httpServletRequest, classIds)) {
            httpServletResponse.setContentType("text/html;charset=utf-8");
            PrintWriter out = null;
            try {
                out = httpServletResponse.getWriter();
                out.print(
                        "<script>alert('您试图越权访问，系统已经拒绝你的请求。');window.location.href='/admin/logout';</script>");
                out.flush();
                out.close();
            } catch (IOException e) {
                e.printStackTrace();
                return "redirect:/admin/logout";
            }
        }
        return null;
    }

    /**
     * 校验权限 ResultVO 专用
     *
     * @param classId
     * @param httpServletRequest
     * @param httpServletResponse
     * @return
     */
    public static ResultVO checkAuthorityForResultVO(
            Integer classId,
            HttpServletRequest httpServletRequest,
            HttpServletResponse httpServletResponse) {
        if (!AuthorityUtils.hasGroupSetting(httpServletRequest, classId)) {
            httpServletResponse.setContentType("application/json;charset=utf-8");
            PrintWriter out = null;
            try {
                out = httpServletResponse.getWriter();
                out.print(JSON.toJSONString(ResultVO.fail("您视图越权访问，系统已经拒绝你的请求。")));
                out.flush();
                out.close();
            } catch (IOException e) {
                e.printStackTrace();
                return ResultVO.fail(ErrorCodeEnum.SAVE_FAIL);
            }
        }
        return null;
    }
}
