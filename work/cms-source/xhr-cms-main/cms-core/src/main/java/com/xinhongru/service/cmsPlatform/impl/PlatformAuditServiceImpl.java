package com.xinhongru.service.cmsPlatform.impl;

import com.xinhongru.entity.CheckRecordEntity;
import com.xinhongru.entity.FormOneshotEntity;
import com.xinhongru.entity.PlatformOneshotEntity;
import com.xinhongru.entity.UserEntity;
import com.xinhongru.mapper.FormContentMapper;
import com.xinhongru.mapper.FormOneshotMapper;
import com.xinhongru.mapper.PlatformContentMapper;
import com.xinhongru.mapper.PlatformOneshotMapper;
import com.xinhongru.service.CheckRecordService;
import com.xinhongru.service.cmsPlatform.PlatformAuditService;
import com.xinhongru.service.cmsPlatform.PlatformContentService;
import com.xinhongru.service.cmsPlatform.PlatformDataType;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class PlatformAuditServiceImpl implements PlatformAuditService {
    @Resource private CheckRecordService checkRecordService;
    @Resource private FormCenterServiceImpl formCenterService;
    @Resource private PlatformContentService platformContentService;
    @Resource private PlatformContentMapper platformContentMapper;
    @Resource private FormContentMapper formContentMapper;
    @Resource private PlatformOneshotMapper platformOneshotMapper;
    @Resource private FormOneshotMapper formOneshotMapper;

    @Override
    public int audit(
            String configType,
            Integer categoryId,
            List<Integer> ids,
            Integer status,
            String note,
            UserEntity user) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        int effectiveStatus = status == null ? 2 : status;
        Map<Integer, String> titles = new HashMap<>();
        String menuName;
        if ("form".equals(configType)) {
            formCenterService.batchOption(categoryId, ids, "approve", effectiveStatus, null);
            formContentMapper.selectBatchIds(ids).forEach(c -> titles.put(c.getId(), c.getTitle()));
            FormOneshotEntity menu = formOneshotMapper.selectById(categoryId);
            menuName = menu == null ? null : menu.getClassName();
        } else {
            // 产品/新闻/解决方案/案例：内容与分类已统一到 t_platform_contents / t_platform_oneshot
            PlatformDataType dataType = dataTypeOf(configType);
            platformContentService.batchOption(
                    dataType, categoryId, ids, "approve", effectiveStatus, null);
            platformContentMapper
                    .selectBatchIds(ids)
                    .forEach(c -> titles.put(c.getId(), c.getTitle()));
            PlatformOneshotEntity menu = platformOneshotMapper.selectById(categoryId);
            menuName = menu == null ? null : menu.getClassName();
        }
        insertRecords(configType, categoryId, ids, effectiveStatus, note, user, titles, menuName);
        return ids.size();
    }

    /** 与改造前一致：未知类型按产品处理 */
    private PlatformDataType dataTypeOf(String configType) {
        for (PlatformDataType type : PlatformDataType.values()) {
            if (type.permissionType().equals(configType)) {
                return type;
            }
        }
        return PlatformDataType.PRODUCT;
    }

    private void insertRecords(
            String configType,
            Integer categoryId,
            List<Integer> ids,
            Integer checkStatus,
            String note,
            UserEntity user,
            Map<Integer, String> titles,
            String menuName) {
        for (Integer id : ids) {
            CheckRecordEntity record = new CheckRecordEntity();
            record.setContentId(id);
            record.setContentTitle(titles.getOrDefault(id, null));
            record.setSubmitMenuId(categoryId);
            record.setSubmitMenuName(menuName);
            record.setCheckStatus(checkStatus);
            record.setCheckDescription(note);
            record.setType(configType);
            record.setSubmitTime(LocalDateTime.now());
            record.setCheckTime(LocalDateTime.now());
            if (user != null) {
                record.setSubmitUserId(user.getId());
                record.setSubmitUserName(user.getScreenName());
                record.setCheckUserId(user.getId());
                record.setCheckUserName(user.getScreenName());
            }
            checkRecordService.save(record);
        }
    }
}
