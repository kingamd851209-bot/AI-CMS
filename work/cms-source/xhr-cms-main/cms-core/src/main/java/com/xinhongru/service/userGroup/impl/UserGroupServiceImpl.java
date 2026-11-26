package com.xinhongru.service.userGroup.impl;

import com.alibaba.fastjson.JSON;
import com.xinhongru.constant.WebConst;
import com.xinhongru.entity.OneshotEntity;
import com.xinhongru.entity.UserEntity;
import com.xinhongru.entity.UserGroupEntity;
import com.xinhongru.enums.ErrorCodeEnum;
import com.xinhongru.mapper.OneShotMapper;
import com.xinhongru.mapper.UserGroupMapper;
import com.xinhongru.requestDto.userGroup.UserGroupSwitchDto;
import com.xinhongru.responseVo.ResultVO;
import com.xinhongru.responseVo.user.SettingVo;
import com.xinhongru.service.userGroup.UserGroupService;
import com.xinhongru.utils.AuthorityUtils;
import com.xinhongru.utils.TaleUtils;

import jakarta.servlet.http.HttpServletRequest;

import lombok.extern.slf4j.Slf4j;

import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * @Description @ClassName UserGroupServiceImpl @Author 永远的神 @Version V1.0.0 @Since 1.0 @Date
 * 2022/5/21 10:04
 */
@Slf4j
@Service
public class UserGroupServiceImpl implements UserGroupService {
    private UserGroupMapper userGroupMapper;

    private OneShotMapper oneShotMapper;

    @Autowired
    public void setOneShotMapper(OneShotMapper oneShotMapper) {
        this.oneShotMapper = oneShotMapper;
    }

    @Autowired
    public void setUserGroupMapper(UserGroupMapper userGroupMapper) {
        this.userGroupMapper = userGroupMapper;
    }

    @Override
    public Boolean addUserGroup(UserGroupEntity userGroupEntity) {
        return userGroupMapper.addUserGroup(userGroupEntity);
    }

    @Override
    public Boolean deleteUserGroup(Integer groupId) {
        return userGroupMapper.deleteUserGroup(groupId);
    }

    @Override
    public List<UserGroupEntity> getUserGroupList(Integer parentId, HttpServletRequest request) {
        // 用于返回的集合存储对象
        List<UserGroupEntity> userGroupEntityList = new ArrayList<>();

        List<UserGroupEntity> userGroupAllList = userGroupMapper.getUserGroupList(null);

        if (TaleUtils.isFullAdmin(request)) {
            parentId = 0;
        }

        String split = treeBranch();
        recursive(userGroupEntityList, userGroupAllList, parentId, split);
        return userGroupEntityList;
    }

    @Override
    public List<UserGroupEntity> getUserGroupListBySite(
            Long siteId, Integer parentId, HttpServletRequest request) {
        return buildUserGroupTree(userGroupMapper.getUserGroupList(siteId), parentId, request);
    }

    /** 按 parentId 起点，将扁平组列表递归展开为树形列表（超级管理员始终从顶级开始） */
    private List<UserGroupEntity> buildUserGroupTree(
            List<UserGroupEntity> userGroupAllList, Integer parentId, HttpServletRequest request) {
        List<UserGroupEntity> userGroupEntityList = new ArrayList<>();
        if (TaleUtils.isFullAdmin(request)) {
            parentId = 0;
        }
        recursive(userGroupEntityList, userGroupAllList, parentId, treeBranch());
        return userGroupEntityList;
    }

    @Override
    public Integer getTopParentId(HttpServletRequest request) {
        if (TaleUtils.isFullAdmin(request)) {
            return 0;
        } else {
            return TaleUtils.getUserGroupId(request);
        }
    }

    /**
     * 递归管理员组数据
     *
     * @param userGroupEntityList
     * @param userGroupAllList
     * @param parentId
     * @param split
     */
    private void recursive(
            List<UserGroupEntity> userGroupEntityList,
            List<UserGroupEntity> userGroupAllList,
            Integer parentId,
            String split) {
        List<UserGroupEntity> userGroupEntities =
                userGroupAllList.stream()
                        .filter(userGroupEntity -> userGroupEntity.getParentId().equals(parentId))
                        .collect(Collectors.toList());
        for (UserGroupEntity userGroupEntity : userGroupEntities) {
            userGroupEntity.setTitle(
                    split
                            + "<span class=\"user-group-title-span\">"
                            + userGroupEntity.getTitle()
                            + "</span>");
            userGroupEntityList.add(userGroupEntity);
            recursive(
                    userGroupEntityList,
                    userGroupAllList,
                    userGroupEntity.getId(),
                    treeIndent() + split);
        }
    }

    @Override
    public Boolean updateUserGroup(UserGroupEntity userGroupEntity) {
        return userGroupMapper.updateUserGroup(userGroupEntity);
    }

    @Override
    public UserGroupEntity getUserGroupById(Integer groupId) {
        return userGroupMapper.getUserGroupById(groupId);
    }

    @Override
    public List<SettingVo> getSettingVos(int groupId, HttpServletRequest request) {
        return getSettingVoList(groupId, TaleUtils.getLoginUser(request));
    }

    /**
     * 获取管理员组权限集合
     *
     * @param groupId
     * @param userEntity
     * @return
     */
    private List<SettingVo> getSettingVoList(int groupId, UserEntity userEntity) {
        // 获取所有栏目
        List<OneshotEntity> allMenu = oneShotMapper.getAllMenu();
        if (userEntity.getId() == null || !userEntity.getId().equals(WebConst.SUPER_ADMIN_ID)) {
            List<Integer> masterIds = new ArrayList<>();
            masterIds.add(2);
            masterIds.add(3);
            masterIds.add(4);
            masterIds.add(9);
            // 排除特殊权限
            allMenu =
                    allMenu.stream()
                            .filter(item -> !masterIds.contains(item.getId()))
                            .collect(Collectors.toList());
        }
        // 仅显示后台显示的栏目
        allMenu =
                allMenu.stream()
                        .filter(item -> item.getHasManageChannel())
                        .collect(Collectors.toList());

        // 当前登录用户拥有的栏目数据
        UserGroupEntity loginUserGroupById =
                userGroupMapper.getUserGroupById(userEntity.getGroupId());

        // 修改的用户组的权限数据
        UserGroupEntity settingUserGroupById = userGroupMapper.getUserGroupById(groupId);

        // 如果不是超级管理员组 只显示当前管理员组拥有权限的栏目集合
        if (!userEntity.getId().equals(WebConst.SUPER_ADMIN_ID)) {
            // 获取当前登录用户所属管理员组信息
            if (loginUserGroupById == null) {
                log.warn("当前登录用户的管理员组信息不存在，{}", JSON.toJSONString(userEntity));
                return null;
            }
            String[] menuIds = loginUserGroupById.getGroupSetting().split(",");
            if (ArrayUtils.isEmpty(menuIds)) {
                log.warn("当前登录用户的管理员组没有任何栏目权限,{}", JSON.toJSONString(loginUserGroupById));
                return null;
            }
            int[] intMenuIds = Arrays.stream(menuIds).mapToInt(Integer::parseInt).toArray();

            allMenu =
                    allMenu.stream()
                            .filter(
                                    oneshotEntity ->
                                            ArrayUtils.contains(intMenuIds, oneshotEntity.getId()))
                            .collect(Collectors.toList());
        }
        List<SettingVo> settingVos = new ArrayList<>();
        String split = treeIndent() + treeBranch();
        int parentId = 0;
        recursiveForSetting(
                settingVos, allMenu, parentId, split, loginUserGroupById, settingUserGroupById);

        return settingVos;
    }

    private void recursiveForSetting(
            List<SettingVo> settingVos,
            List<OneshotEntity> allMenu,
            int parentId,
            String split,
            UserGroupEntity loginUserGroupById,
            UserGroupEntity settingUserGroupById) {
        List<OneshotEntity> oneshotsByParentId =
                allMenu.stream()
                        .filter(oneshot -> oneshot.getParentId() == parentId)
                        .collect(Collectors.toList());
        SettingVo settingVo;
        String classNameHtml;
        String menuSetting;
        List<String> settingList;

        String[] settingTitleArr = {
            "添加", "修改", "删除", "发布/草稿", "查看", "排序", "复制", "首页置顶", "首页热门", "首页推荐", "频道置顶", "频道热门",
            "频道推荐", "栏目置顶", "栏目热门", "栏目推荐", "导航推荐", "右侧推荐", "左侧推荐", "导出数据", "导入数据", "审核状态", "审核"
        };
        for (OneshotEntity oneshotEntity : oneshotsByParentId) {
            settingVo = new SettingVo();
            settingVo.setId(oneshotEntity.getId());
            // 栏目名称
            classNameHtml =
                    split
                            + "<input type=\"checkbox\" name=\"group-setting\" value=\""
                            + oneshotEntity.getId()
                            + "\" id=\"setting_"
                            + oneshotEntity.getId()
                            + "\" "
                            + "pid=\""
                            + oneshotEntity.getParentId()
                            + "\" style=\"margin-left:3px;cursor: pointer;\""
                            + (settingUserGroupById != null
                                            && AuthorityUtils.hasGroupSetting(
                                                    settingUserGroupById.getGroupSetting(),
                                                    oneshotEntity.getId())
                                    ? " checked=\"checked\""
                                    : "")
                            + "> "
                            + "<label style=\"font-weight:unset;cursor: pointer;\" for=\"setting_"
                            + oneshotEntity.getId()
                            + "\">"
                            + oneshotEntity.getClassName()
                            + "</label>\n";
            // 栏目拥有的操作权限
            if (oneshotEntity.getPageType() == 1) {
                menuSetting = oneshotEntity.getSetting();
                if (StringUtils.isNotBlank(menuSetting)) {
                    classNameHtml +=
                            "<span id=\"menu-id-"
                                    + oneshotEntity.getId()
                                    + "\" style=\"margin-left: 15px;\">";
                    settingList = Arrays.asList(menuSetting.split(","));
                    for (int i = 0; i < settingTitleArr.length; i++) {
                        if (settingList.contains(String.valueOf(i + 1))) {
                            classNameHtml =
                                    builderSettingOptions(
                                            classNameHtml,
                                            oneshotEntity,
                                            i + 1,
                                            settingTitleArr[i],
                                            loginUserGroupById,
                                            settingUserGroupById);
                        }
                    }
                    classNameHtml += "</span>";
                }
            }
            settingVo.setClassName(classNameHtml);
            settingVo.setParentId(oneshotEntity.getParentId());

            // 需要根据栏目权限来显示可选的按钮

            settingVo.setSetting("");
            settingVo.setHasManageChannel(false);
            settingVo.setPageType(0);

            settingVo.setSettingOptionVos(new ArrayList());

            settingVos.add(settingVo);

            recursiveForSetting(
                    settingVos,
                    allMenu,
                    oneshotEntity.getId(),
                    treeIndent() + split,
                    loginUserGroupById,
                    settingUserGroupById);
        }
    }

    private static String treeIndent() {
        return "<span style=\"display:inline-block;width:18px;height:18px;\"></span>";
    }

    private static String treeBranch() {
        return "<span style=\"display:inline-block;width:18px;height:18px;\">-</span>";
    }

    private String builderSettingOptions(
            String classNameHtml,
            OneshotEntity oneshotEntity,
            int optionId,
            String optionTitle,
            UserGroupEntity loginUserGroupById,
            UserGroupEntity settingUserGroupById) {
        classNameHtml +=
                "<input type=\"checkbox\" mid=\""
                        + oneshotEntity.getId()
                        + "\" name=\"other_setting_"
                        + oneshotEntity.getId()
                        + "\" value=\""
                        + optionId
                        + "\" "
                        + "id=\"setting_"
                        + oneshotEntity.getId()
                        + "_"
                        + optionId
                        + "\" style=\"margin-left:3px;\""
                        + (settingUserGroupById != null
                                        && AuthorityUtils.hasOtherSetting(
                                                settingUserGroupById.getGroupSetting(),
                                                settingUserGroupById.getOtherSetting(),
                                                oneshotEntity.getId(),
                                                optionId)
                                ? " checked=\"checked\""
                                : "")
                        + "> ";
        classNameHtml +=
                "<label style=\"font-weight:unset;cursor: pointer;\" for=\"setting_"
                        + oneshotEntity.getId()
                        + "_"
                        + optionId
                        + "\">"
                        + optionTitle
                        + "</label>\n";
        return classNameHtml;
    }

    @Override
    public List<UserGroupEntity> getDropdownList(Integer parentId, HttpServletRequest request) {
        List<UserGroupEntity> userGroupEntityList = new ArrayList<>();
        List<UserGroupEntity> userGroupAllList = userGroupMapper.getUserGroupList(null);
        // 只能添加级别比自己低一级的组
        String split = "┃━ ";
        recursiveDropdownList(userGroupEntityList, userGroupAllList, parentId, split);
        return userGroupEntityList;
    }

    @Override
    public List<UserGroupEntity> getDropdownListBySite(
            Long siteId, Integer parentId, HttpServletRequest request) {
        List<UserGroupEntity> userGroupEntityList = new ArrayList<>();
        List<UserGroupEntity> userGroupAllList = userGroupMapper.getUserGroupList(siteId);
        String split = "┃━ ";
        recursiveDropdownList(userGroupEntityList, userGroupAllList, parentId, split);
        return userGroupEntityList;
    }

    @Override
    public List<UserGroupEntity> getChildUserGroupList(
            Integer parentId, HttpServletRequest request) {
        List<UserGroupEntity> userGroupEntityList = new ArrayList<>();
        List<UserGroupEntity> userGroupAllList = userGroupMapper.getUserGroupList(null);
        recursiveUserGroup(userGroupEntityList, userGroupAllList, parentId);
        return userGroupEntityList;
    }

    private void recursiveUserGroup(
            List<UserGroupEntity> userGroupEntityList,
            List<UserGroupEntity> userGroupAllList,
            Integer parentId) {
        List<UserGroupEntity> userGroupEntities =
                userGroupAllList.stream()
                        .filter(userGroupEntity -> userGroupEntity.getParentId().equals(parentId))
                        .collect(Collectors.toList());
        for (UserGroupEntity userGroupEntity : userGroupEntities) {
            userGroupEntityList.add(userGroupEntity);
            recursiveUserGroup(userGroupEntityList, userGroupAllList, userGroupEntity.getId());
        }
    }

    @Override
    public ResultVO addOrUpdateUserGroup(UserGroupEntity userGroupEntity) {
        Boolean flag;
        if (userGroupEntity.getId().equals(0)) {
            flag = userGroupMapper.addUserGroup(userGroupEntity);
        } else {
            flag = userGroupMapper.updateUserGroup(userGroupEntity);
        }
        if (flag) {
            return ResultVO.saveSuccess(ErrorCodeEnum.SAVE_SUCCESS);
        } else {
            return ResultVO.fail(ErrorCodeEnum.SAVE_FAIL);
        }
    }

    @Override
    public List<UserGroupEntity> getUserGroupListByParentId(Integer parentId) {
        List<UserGroupEntity> userGroupList = userGroupMapper.getUserGroupList(null);
        return userGroupList.stream()
                .filter(userGroupEntity -> userGroupEntity.getParentId().equals(parentId))
                .collect(Collectors.toList());
    }

    @Override
    public String getUserGroupNameByGroupId(int groupId) {
        UserGroupEntity userGroupById = getUserGroupById(groupId);
        if (userGroupById != null) {
            return userGroupById.getTitle();
        }
        return null;
    }

    @Override
    public ResultVO updateSwitch(
            UserGroupSwitchDto userGroupSwitchDto, HttpServletRequest request) {
        List<UserGroupEntity> dropdownList = getDropdownList(getTopParentId(request), request);
        int[] groupIds = dropdownList.stream().mapToInt(UserGroupEntity::getId).toArray();
        if (!ArrayUtils.contains(groupIds, userGroupSwitchDto.getId())) {
            return ResultVO.fail(ErrorCodeEnum.UNAUTHORIZED_OPERATION);
        }

        UserGroupEntity userGroupById =
                userGroupMapper.getUserGroupById(userGroupSwitchDto.getId());

        if (userGroupById == null) {
            return ResultVO.fail(ErrorCodeEnum.DATA_NOT_FOUND);
        }
        Boolean updateSwitch = userGroupMapper.updateSwitch(userGroupSwitchDto);
        if (updateSwitch) {
            return ResultVO.success(ErrorCodeEnum.SAVE_SUCCESS);
        } else {
            return ResultVO.fail(ErrorCodeEnum.SAVE_FAIL);
        }
    }

    private void recursiveDropdownList(
            List<UserGroupEntity> userGroupEntityList,
            List<UserGroupEntity> userGroupAllList,
            Integer parentId,
            String split) {
        List<UserGroupEntity> userGroupEntities =
                userGroupAllList.stream()
                        .filter(userGroupEntity -> userGroupEntity.getParentId().equals(parentId))
                        .collect(Collectors.toList());
        for (UserGroupEntity userGroupEntity : userGroupEntities) {
            userGroupEntity.setTitle(split + userGroupEntity.getTitle());
            userGroupEntityList.add(userGroupEntity);
            recursiveDropdownList(
                    userGroupEntityList, userGroupAllList, userGroupEntity.getId(), "┃  " + split);
        }
    }
}
