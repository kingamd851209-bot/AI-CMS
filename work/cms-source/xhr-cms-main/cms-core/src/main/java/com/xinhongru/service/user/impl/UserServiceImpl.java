package com.xinhongru.service.user.impl;

import com.alibaba.fastjson.JSON;
import com.xinhongru.constant.ErrorConstant;
import com.xinhongru.constant.LogActions;
import com.xinhongru.constant.WebConst;
import com.xinhongru.entity.LogEntity;
import com.xinhongru.entity.OneshotEntity;
import com.xinhongru.entity.UserEntity;
import com.xinhongru.entity.UserGroupEntity;
import com.xinhongru.enums.ErrorCodeEnum;
import com.xinhongru.exception.BusinessException;
import com.xinhongru.mapper.OneShotMapper;
import com.xinhongru.mapper.UserMapper;
import com.xinhongru.requestDto.LoginDto;
import com.xinhongru.requestDto.profile.UpdatePwdDto;
import com.xinhongru.requestDto.profile.UserDto;
import com.xinhongru.responseVo.ResultVO;
import com.xinhongru.responseVo.user.SettingVo;
import com.xinhongru.security.SessionIdentityService;
import com.xinhongru.security.identity.SessionIdentity;
import com.xinhongru.security.password.Sm3PasswordHashService;
import com.xinhongru.service.imageCode.ImageCodeService;
import com.xinhongru.service.log.LogService;
import com.xinhongru.service.user.AdminUserQueryService;
import com.xinhongru.service.user.UserService;
import com.xinhongru.service.userGroup.UserGroupService;
import com.xinhongru.utils.*;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import lombok.extern.slf4j.Slf4j;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/** Created at 2021/4/20. */
@Slf4j
@Service
public class UserServiceImpl implements UserService {
    @Autowired private SessionIdentityService sessions;

    @Autowired private AdminUserQueryService userQueries;
    private UserMapper userDao;
    private OneShotMapper oneShotMapper;
    private UserGroupService userGroupService;
    private LogService logService;
    private Sm3PasswordHashService passwordHashService;

    private ImageCodeService imageCodeService;

    @Autowired
    public void setImageCodeService(ImageCodeService imageCodeService) {
        this.imageCodeService = imageCodeService;
    }

    @Autowired
    public void setLogService(LogService logService) {
        this.logService = logService;
    }

    @Autowired
    public void setUserGroupService(UserGroupService userGroupService) {
        this.userGroupService = userGroupService;
    }

    @Autowired
    public void setOneShotMapper(OneShotMapper oneShotMapper) {
        this.oneShotMapper = oneShotMapper;
    }

    @Autowired
    public void setUserDao(UserMapper userDao) {
        this.userDao = userDao;
    }

    @Autowired
    public void setPasswordHashService(Sm3PasswordHashService passwordHashService) {
        this.passwordHashService = passwordHashService;
    }

    @Override
    public boolean deleteUser(Integer uid) {
        if (uid.equals(WebConst.SUPER_ADMIN_ID)) {
            return false;
        }
        return userDao.deleteUser(uid);
    }

    @Override
    public List<UserEntity> getUserList(HttpServletRequest request) {
        return userQueries.visibleUsers(request);
    }

    @Override
    public List<UserEntity> listAllAdminUsers(HttpServletRequest httpServletRequest) {
        List<UserEntity> userList = userDao.getUserList();
        if (!TaleUtils.isFullAdmin(httpServletRequest)) {
            userList =
                    userList.stream()
                            .filter(item -> !item.getId().equals(WebConst.SUPER_ADMIN_ID))
                            .collect(Collectors.toList());
        }
        // 一次性加载全部组名映射，避免对每个用户单独查询数据库
        Map<Integer, String> groupNameById =
                userGroupService.getChildUserGroupList(0, httpServletRequest).stream()
                        .collect(
                                Collectors.toMap(
                                        UserGroupEntity::getId,
                                        UserGroupEntity::getTitle,
                                        (a, b) -> a));
        for (UserEntity userEntity : userList) {
            userEntity.setGroupName(
                    userEntity.getGroupId() == null
                            ? null
                            : groupNameById.get(userEntity.getGroupId()));
        }
        return userList;
    }

    @Transactional
    @Override
    public int updateUserInfo(UserEntity user) {
        if (null == user.getId()) {
            throw BusinessException.withErrorCode("用户编号不可能为空");
        }
        return userDao.updateUserInfo(user);
    }

    @Override
    public UserEntity lockUserById(Integer id) {
        return userDao.lockUserById(id);
    }

    @Override
    public UserEntity getUserInfoById(Integer uId, Boolean isTree) {
        UserEntity userEntity = userDao.getUserInfoById(uId);
        if (userEntity != null) {
            UserGroupEntity userGroupById =
                    userGroupService.getUserGroupById(userEntity.getGroupId());
            if (userGroupById != null) {
                userEntity.setGroupSetting(userGroupById.getGroupSetting());
                userEntity.setOtherSetting(userGroupById.getOtherSetting());
            }
        }
        // 权限已经提升到管理员组级别了 管理员已经不存在权限了
        //        //设置权限
        //        if (isTree) {
        //            userEntity.setSettingVos(getSettingVoList(userEntity));
        //        }
        return userEntity;
    }

    private List<SettingVo> getSettingVoList(UserEntity userEntity) {
        // 获取所有栏目
        List<OneshotEntity> allMenu = oneShotMapper.getAllMenu();
        if (userEntity.getId() == null || !userEntity.getId().equals(13)) {
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
        List<SettingVo> settingVos = new ArrayList<>();
        String split = treeIndent() + treeBranch();
        int parentId = 0;
        recursiveForSetting(settingVos, allMenu, parentId, split, userEntity);

        return settingVos;
    }

    private void recursiveForSetting(
            List<SettingVo> settingVos,
            List<OneshotEntity> allMenu,
            int parentId,
            String split,
            UserEntity userEntity) {
        List<OneshotEntity> oneshotsByParentId =
                allMenu.stream()
                        .filter(oneshot -> oneshot.getParentId() == parentId)
                        .collect(Collectors.toList());
        SettingVo settingVo;
        String classNameHtml;
        String menuSetting;
        List<String> settingList;

        String[] settingTitleArr = {
            "添加", "修改", "删除", "锁定/审核", "查看", "排序", "复制", "首页置顶", "首页热门", "首页推荐", "频道置顶", "频道热门",
            "频道推荐", "栏目置顶", "栏目热门", "栏目推荐", "导航推荐", "右侧推荐", "左侧推荐", "导出数据", "导入数据", "审核状态"
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
                            + (AuthorityUtils.hasGroupSetting(
                                            userEntity.getGroupSetting(), oneshotEntity.getId())
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
                                            userEntity);
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
                    settingVos, allMenu, oneshotEntity.getId(), treeIndent() + split, userEntity);
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
            UserEntity userEntity) {
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
                        + (AuthorityUtils.hasOtherSetting(
                                        userEntity.getGroupSetting(),
                                        userEntity.getOtherSetting(),
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
    public UserEntity login(String username, String password, String ip) {
        if (StringUtils.isBlank(username) || StringUtils.isBlank(password)) {
            throw BusinessException.withErrorCode(ErrorConstant.Auth.USERNAME_PASSWORD_IS_EMPTY);
        }
        password = RsaUtils.decryptByPrivateKey(password);
        if (StringUtils.isEmpty(password)) {
            throw BusinessException.withErrorCode(ErrorConstant.Auth.PASSWORD_FORMAT_ERROR);
        }

        UserEntity user = userDao.getUserInfoByUserName(username);
        /** 校验用户是否存 */
        if (null == user) {
            throw BusinessException.withErrorCode(ErrorConstant.Auth.USERNAME_PASSWORD_ERROR);
        }
        if (Integer.valueOf(1).equals(user.getActivated())) {
            throw BusinessException.withErrorCode("账号已被禁用，请联系超级管理员");
        }
        /** 校验密码是否匹配 */
        if (!passwordHashService.matches(password, user.getPassword())) {
            throw BusinessException.withErrorCode(ErrorConstant.Auth.USERNAME_PASSWORD_ERROR);
        }
        if (user.getId().equals(WebConst.SUPER_ADMIN_ID)
                && !user.getUsername().equals(WebConst.SUPER_ADMIN_NAME)) {
            throw BusinessException.withErrorCode(ErrorConstant.Auth.SUPER_ADMIN_NAME_NOT_MATCH);
        }
        user.setLastLoginTime(LocalDateTime.now());
        user.setLoginTimes(user.getLoginTimes() + 1);
        user.setLastLoginIp(ip);
        userDao.updateUserInfo(user);
        return user;
    }

    @Override
    public boolean addUser(UserEntity userEntity) {
        return userDao.addUser(userEntity);
    }

    @Override
    public int hasUsername(String username) {
        return userDao.hasUsername(username);
    }

    @Override
    public int hasEmail(String email) {
        return userDao.hasEmail(email);
    }

    @Override
    public List<SettingVo> getSettingVos() {
        return getSettingVoList(new UserEntity());
    }

    @Override
    public List<UserEntity> getUserListByGroupId(Integer groupId) {
        return userDao.getUserListByGroupId(groupId);
    }

    @Override
    public ResultVO saveProfile(UserDto userDto, HttpServletRequest request, HttpSession session) {
        UserEntity users = TaleUtils.getLoginUser(request);
        if (StringUtils.isNotBlank(userDto.getScreenName())
                && StringUtils.isNotBlank(userDto.getEmail())) {
            UserEntity temp = new UserEntity();
            temp.setId(users.getId());
            temp.setScreenName(userDto.getScreenName());
            temp.setEmail(userDto.getEmail());
            updateUserInfo(temp);
            // 更新当前请求的身份数据；下一请求重新加载账号和权限
            UserEntity original = users;
            original.setScreenName(userDto.getScreenName());
            original.setEmail(userDto.getEmail());
            request.setAttribute(SessionIdentity.ADMIN_USER, original);
            return ResultVO.success(ErrorCodeEnum.SAVE_SUCCESS);
        }
        return ResultVO.fail(ErrorCodeEnum.SAVE_FAIL);
    }

    @Override
    public ResultVO updatePassword(
            UpdatePwdDto updatePwdDto, HttpServletRequest request, HttpSession session) {
        UserEntity users = TaleUtils.getLoginUser(request);
        updatePwdDto.setOldPassword(RsaUtils.decryptByPrivateKey(updatePwdDto.getOldPassword()));
        if (StringUtils.isEmpty(updatePwdDto.getOldPassword())) {
            return ResultVO.fail(ErrorCodeEnum.OLD_PASSWORD_FORMAT_ERROR);
        }
        updatePwdDto.setPassword(RsaUtils.decryptByPrivateKey(updatePwdDto.getPassword()));
        if (StringUtils.isEmpty(updatePwdDto.getPassword())) {
            return ResultVO.fail(ErrorCodeEnum.NEW_PASSWORD_FORMAT_ERROR);
        }
        updatePwdDto.setRepass(RsaUtils.decryptByPrivateKey(updatePwdDto.getRepass()));
        if (StringUtils.isEmpty(updatePwdDto.getRepass())) {
            return ResultVO.fail(ErrorCodeEnum.NEW_PASSWORD_FORMAT_ERROR);
        }

        if (!passwordHashService.matches(updatePwdDto.getOldPassword(), users.getPassword())) {
            return ResultVO.fail(ErrorCodeEnum.OLD_PASSWORD_ERROR);
        }
        if (!updatePwdDto.getPassword().equals(updatePwdDto.getRepass())) {
            return ResultVO.fail(ErrorCodeEnum.TWICE_PASSWORD_NOT_THE_SAME);
        }
        if (updatePwdDto.getPassword().equals(updatePwdDto.getOldPassword())) {
            return ResultVO.fail(ErrorCodeEnum.OLD_PASSWORD_NEW_PASSWORD_CANNOT_SAME);
        }

        try {
            UserEntity temp = new UserEntity();
            temp.setId(users.getId());
            String pwd = passwordHashService.hash(updatePwdDto.getPassword());
            temp.setPassword(pwd);
            updateUserInfo(temp);
            // 更新当前请求的身份数据；下一请求重新加载账号和权限
            UserEntity original = users;
            original.setPassword(pwd);
            request.setAttribute(SessionIdentity.ADMIN_USER, original);
            return ResultVO.success(ErrorCodeEnum.PASSWORD_UPDATE_SUCCESS);
        } catch (Exception e) {
            String msg = "密码修改失败";
            if (e instanceof BusinessException) {
                msg = e.getMessage();
            }
            log.error(msg, e);
            return ResultVO.fail(ErrorCodeEnum.PASSWORD_UPDATE_FAIL);
        }
    }

    @Override
    public ResultVO toLogin(
            LoginDto loginDto, HttpServletResponse response, HttpServletRequest request) {
        //        if(LocalDate.of(2022, 8, 7).compareTo(LocalDate.now())<0){
        //            return APIResponse.fail("试用期已过，请联系我们");
        //        }
        // 验证图形验证码
        //        ResultVO<Boolean> imgResultVO =
        // imageCodeService.checkImageCode(loginDto.getImageCodeKey(),
        // loginDto.getImageCodeValue());
        //        if (!imgResultVO.getSuccess()) {
        //            return imgResultVO;
        //        }

        // 获取ip
        String ip = RequestOriginUtils.clientIp(request);
        Integer error_count = MapCache.single().hget("login_error_count", ip);
        try {
            long beginTime = System.currentTimeMillis();
            UserEntity userInfo = login(loginDto.getUsername(), loginDto.getPassword(), ip);
            sessions.loginAdmin(request, userInfo);
            long endTime = System.currentTimeMillis();
            LogEntity logEntity = new LogEntity();
            logEntity.setAction(LogActions.LOGIN.getAction());
            logEntity.setRequestData(
                    "{\"username\":\"" + loginDto.getUsername() + "\",\"password\":\"********\"}");
            logEntity.setUserId(userInfo.getId());
            logEntity.setIp(RequestOriginUtils.clientIp(request));
            logEntity.setCreateTime(LocalDateTime.now());
            logEntity.setUrl(request.getRequestURI());
            logEntity.setHttpMethod(request.getMethod());
            logEntity.setClassMethod(this.getClass().getName());
            logEntity.setSpendTime((endTime - beginTime));
            logEntity.setResponseData(JSON.toJSONString(userInfo));
            logEntity.setUserName(userInfo.getUsername());
            logService.addLog(logEntity);
        } catch (Exception e) {
            log.error(e.getMessage());
            error_count = null == error_count ? 1 : error_count + 1;
            if (error_count > 3) {
                return ResultVO.fail("您输入密码已经错误超过3次，请10分钟后尝试");
            }
            MapCache.single().hset("login_error_count", ip, error_count, 10 * 60); // 加入ip的过滤
            String msg = "登录失败，再输入错误" + (3 - error_count) + "次，将会锁定10分钟";
            if (error_count >= 3) {
                msg = "您输入密码已经错误3次，请10分钟后尝试";
            }
            if (e instanceof BusinessException) {
                log.error(((BusinessException) e).getErrorCode());
            } else {
                log.error(msg, e);
            }
            return ResultVO.fail(msg);
        }

        return ResultVO.success();
    }
}
