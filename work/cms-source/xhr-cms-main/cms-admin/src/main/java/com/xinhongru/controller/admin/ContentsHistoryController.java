package com.xinhongru.controller.admin;

import com.alibaba.fastjson.JSON;
import com.xinhongru.dto.LogTableDto;
import com.xinhongru.service.member.impl.MemberCredentials;
import com.xinhongru.entity.ContentHistoryEntity;
import com.xinhongru.exception.BusinessException;
import com.xinhongru.service.ContentHistoryService;

import io.swagger.annotations.Api;

import lombok.extern.slf4j.Slf4j;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.List;

/** 数据版本历史 */
@Api("数据版本历史")
@Slf4j
@Controller
@Transactional(rollbackFor = BusinessException.class)
@RequestMapping("/admin/content/history")
public class ContentsHistoryController {
    @Autowired private ContentHistoryService contentHistoryService;

    /**
     * 获取数据历史版本
     *
     * @param classId
     * @param infoId
     * @param model
     * @return
     */
    @GetMapping("/list/{classId}/{infoId}")
    public String index(
            @PathVariable("classId") Integer classId,
            @PathVariable("infoId") Integer infoId,
            Model model) {
        if (MemberCredentials.isDirectory(classId)) return "redirect:/admin/v2/cms/content";
        List<ContentHistoryEntity> list = contentHistoryService.list(infoId, classId);
        if (CollectionUtils.isNotEmpty(list)) {
            for (ContentHistoryEntity entity : list) {
                if (StringUtils.isBlank(entity.getComparison())) {
                    continue;
                }
                entity.setFields(JSON.parseArray(entity.getComparison(), LogTableDto.class));
            }
        }

        model.addAttribute("view", list);
        return "redirect:/admin/v2/cms/content";
    }
}
