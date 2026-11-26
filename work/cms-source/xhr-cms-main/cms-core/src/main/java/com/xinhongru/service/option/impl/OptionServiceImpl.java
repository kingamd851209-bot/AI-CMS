package com.xinhongru.service.option.impl;

import com.xinhongru.constant.ErrorConstant;
import com.xinhongru.entity.OptionsEntity;
import com.xinhongru.exception.BusinessException;
import com.xinhongru.mapper.OptionMapper;
import com.xinhongru.service.option.OptionService;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/** 网站配置服务层 Created at 2021/4/28. */
@Service
public class OptionServiceImpl implements OptionService {
    @Autowired private OptionMapper optionDao;

    @Override
    @CacheEvict(
            value = {"optionsCache", "optionCache"},
            allEntries = true,
            beforeInvocation = true)
    public void deleteOptionByName(String name) {
        if (StringUtils.isBlank(name)) {
            throw BusinessException.withErrorCode(ErrorConstant.Common.PARAM_IS_EMPTY);
        }
        optionDao.deleteOptionByName(name);
    }

    @Override
    @Transactional
    @CacheEvict(
            value = {"optionsCache", "optionCache"},
            allEntries = true,
            beforeInvocation = true)
    public void updateOptionByName(String name, String value) {
        if (StringUtils.isBlank(name)) {
            throw BusinessException.withErrorCode(ErrorConstant.Common.PARAM_IS_EMPTY);
        }
        OptionsEntity option = new OptionsEntity();
        option.setId(0);
        option.setName(name);
        option.setValue(value);
        if (optionDao.getOptionByName(name) == null) {
            option.setDescription("");
            optionDao.addOption(option);
        } else {
            optionDao.updateOptionByName(option);
        }
    }

    @Override
    @Transactional
    @CacheEvict(
            value = {"optionsCache", "optionCache"},
            allEntries = true,
            beforeInvocation = true)
    public void saveOptions(Map<String, String> options) {
        if (null != options && !options.isEmpty()) {
            options.forEach(this::updateOptionByName);
        }
    }

    @Override
    @Cacheable(value = "optionCache", key = "'optionByName_' + #p0")
    public OptionsEntity getOptionByName(String name) {
        if (StringUtils.isBlank(name)) {
            throw BusinessException.withErrorCode(ErrorConstant.Common.PARAM_IS_EMPTY);
        }
        return optionDao.getOptionByName(name);
    }

    @Override
    @Cacheable(value = "optionsCache", key = "'options_'")
    public List<OptionsEntity> getOptions() {
        return optionDao.getOptions();
    }
}
