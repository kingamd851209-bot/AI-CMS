package com.xinhongru.service.log.impl;

import com.xinhongru.common.page.PageResult;
import com.xinhongru.constant.ErrorConstant;
import com.xinhongru.constant.WebConst;
import com.xinhongru.dto.LogDto;
import com.xinhongru.entity.LogEntity;
import com.xinhongru.entity.UserEntity;
import com.xinhongru.exception.BusinessException;
import com.xinhongru.mapper.LogMapper;
import com.xinhongru.persistence.Pages;
import com.xinhongru.requestDto.IdDto;
import com.xinhongru.responseVo.logVo.LogHtmlVo;
import com.xinhongru.service.log.LogService;
import com.xinhongru.service.user.AdminUserQueryService;
import com.xinhongru.utils.PageUtils;
import com.xinhongru.utils.TaleUtils;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** 请求日志 Created at 2021/8/9. */
@Service
public class LogServiceImpl implements LogService {
    private LogMapper logDao;

    private AdminUserQueryService userQueries;

    @Autowired
    public void setUserQueries(AdminUserQueryService userQueries) {
        this.userQueries = userQueries;
    }

    @Autowired
    public void setLogDao(LogMapper logDao) {
        this.logDao = logDao;
    }

    @Override
    public void addLog(LogEntity logEntity) {
        logDao.addLog(logEntity);
    }

    @Override
    public void deleteLogById(Integer id) {
        if (null == id) {
            throw BusinessException.withErrorCode(ErrorConstant.Common.PARAM_IS_EMPTY);
        }
        deleteLogById(id);
    }

    @Override
    public PageResult<LogEntity> getLogs(int pageNum, int pageSize, HttpServletRequest request) {
        UserEntity loginUser = TaleUtils.getLoginUser(request);
        return Pages.query(pageNum, pageSize, page -> logDao.getLogs(page, loginUser.getId()));
    }

    @Override
    public LogHtmlVo getLogList(LogDto logDto, HttpServletRequest httpServletRequest) {
        LogHtmlVo logHtmlVo = new LogHtmlVo();

        // 查询当前登录用户的所有子集管理员
        List<UserEntity> userList = userQueries.visibleUsers(httpServletRequest);
        UserEntity loginUser = TaleUtils.getLoginUser(httpServletRequest);
        userList.add(loginUser);

        List<Integer> userIds =
                userList.stream()
                        .map(userEntity -> userEntity.getId())
                        .collect(Collectors.toList());

        logDto.setUserIds(userIds);

        PageResult<LogEntity> pageInfo =
                Pages.query(
                        logDto.getPage(),
                        logDto.getLimit(),
                        page -> logDao.getLogList(page, logDto));

        // 根据条件获取数据总记录数
        Long count = pageInfo.getTotal();
        String urlSearch = "";
        String queryString = httpServletRequest.getQueryString();
        if (StringUtils.isNotBlank(queryString)) {
            Pattern pattern = Pattern.compile(WebConst.REGEX_URL_PAGE);
            Matcher matcher = pattern.matcher(queryString);
            while (matcher.find()) {
                queryString = queryString.replace(matcher.group(0), "");
            }
            urlSearch = queryString;
        }
        String stringBuilderPageHtml =
                PageUtils.getStringBuilderPageHtml(
                        logDto.getPage(), logDto.getLimit(), urlSearch, count.intValue());
        logHtmlVo
                .setLogDto(logDto)
                .setLogEntities(pageInfo.getList())
                .setTotal(pageInfo.getTotal())
                .setPageHtml(stringBuilderPageHtml)
                .setUserEntities(userList);
        return logHtmlVo;
    }

    /**
     * 根据日志ID获取日志明细
     *
     * @param idDto
     * @return
     */
    @Override
    public LogEntity getLogDetails(IdDto idDto) {
        return logDao.getLogDetails(idDto.getId());
    }
}
