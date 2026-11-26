package com.xinhongru.web.service.impl;

import com.xinhongru.constant.WebConst;
import com.xinhongru.entity.ContentEntity;
import com.xinhongru.entity.OneshotEntity;
import com.xinhongru.mapper.ContentMapper;
import com.xinhongru.mapper.OneShotMapper;
import com.xinhongru.mapper.OptionMapper;
import com.xinhongru.service.oneshot.OneShotService;
import com.xinhongru.utils.RedisUtils;
import com.xinhongru.utils.StringDeal;
import com.xinhongru.web.resonseVo.FooterEntity;
import com.xinhongru.web.resonseVo.HeaderEntity;
import com.xinhongru.web.resonseVo.IdEntity;
import com.xinhongru.web.resonseVo.SeoEntity;
import com.xinhongru.web.service.SeoService;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;

import lombok.extern.slf4j.Slf4j;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.Locale;

/**
 * @Description @ClassName SeoServiceImpl @Author 永远的神 @Version V1.0.0 @Since 1.0 @Date 2022/4/16
 * 20:24
 */
@Slf4j
@Service
public class SeoServiceImpl implements SeoService {
    @Resource private OptionMapper optionMapper;
    @Resource private OneShotMapper oneShotMapper;
    @Resource private ContentMapper contentMapper;
    @Resource private HttpServletRequest httpServletRequest;
    @Resource private OneShotService oneShotService;

    private String getSiteTitle() {
        return optionMapper.getOptionByName("site_title").getValue();
    }

    private String getSiteKeywords() {
        return optionMapper.getOptionByName("site_keywords").getValue();
    }

    private String getSiteDescription() {
        return optionMapper.getOptionByName("site_description").getValue();
    }

    @Override
    public SeoEntity getIndexSeo(int indexClassId) {
        SeoEntity seoEntity = new SeoEntity();
        OneshotEntity modelById = oneShotMapper.getModelById(indexClassId);
        if (modelById == null) {
            return null;
        }
        if (StringUtils.isNotBlank(modelById.getSeoTitle())) {
            seoEntity.setTitle(modelById.getSeoTitle());
            seoEntity.setKeywords(modelById.getSeoKeywords());
            seoEntity.setDescription(modelById.getSeoDescription());
        } else {
            seoEntity.setTitle(optionMapper.getOptionByName("site_title").getValue());
            seoEntity.setKeywords(optionMapper.getOptionByName("site_keywords").getValue());
            seoEntity.setDescription(optionMapper.getOptionByName("site_description").getValue());
        }
        return seoEntity;
    }

    @Override
    public SeoEntity getSeoEntityByClassId(int classId) {
        SeoEntity seoEntity = new SeoEntity();
        OneshotEntity modelById = oneShotMapper.getModelById(classId);
        if (modelById == null) {
            return null;
        }
        if (StringUtils.isNotBlank(modelById.getSeoTitle())) {
            seoEntity.setTitle(modelById.getSeoTitle());
            seoEntity.setKeywords(modelById.getSeoKeywords());
            seoEntity.setDescription(modelById.getSeoDescription());
        } else {
            if (WebConst.TEMPLATE_1_IDENTIFIES.equals(modelById.getIdentifies())
                    || WebConst.TEMPLATE_2_IDENTIFIES.equals(modelById.getIdentifies())) {
                // 如果是业务板块 子站栏目
                seoEntity.setTitle(modelById.getClassName());
            } else {
                String domain = StringDeal.getDomain(httpServletRequest).toLowerCase(Locale.CHINA);
                String siteName = RedisUtils.getString(domain);
                if (StringUtils.isNotBlank(siteName)) {
                    // 子站栏目
                    seoEntity.setTitle(modelById.getClassName() + WebConst.TITLE_SPLIT + siteName);
                } else {
                    // 普通站点栏目
                    seoEntity.setTitle(
                            modelById.getClassName() + WebConst.TITLE_SPLIT + getSiteTitle());
                }
            }
            seoEntity.setSiteName(getSiteTitle());
            seoEntity.setKeywords(getSiteKeywords());
            seoEntity.setDescription(getSiteDescription());
        }
        return seoEntity;
    }

    @Override
    public SeoEntity getSeoEntityByClassIdAndId(int classId, int id) {
        SeoEntity seoEntity = new SeoEntity();
        ContentEntity contentEntity = contentMapper.getArticleById(id);
        if (contentEntity == null) {
            return null;
        }
        if (!contentEntity.getClassid().equals(classId)) {
            return null;
        }
        if (StringUtils.isNotBlank(contentEntity.getSeoTitle())) {
            seoEntity.setTitle(contentEntity.getSeoTitle());
            seoEntity.setKeywords(contentEntity.getSeoKeywords());
            seoEntity.setDescription(contentEntity.getSeoDescription());
        } else {
            if (StringUtils.isNotBlank(contentEntity.getTitle())) {
                seoEntity.setTitle(
                        contentEntity.getTitle() + WebConst.TITLE_SPLIT + getSiteTitle());
            } else {
                seoEntity.setTitle(
                        contentEntity.getNote1() + WebConst.TITLE_SPLIT + getSiteTitle());
            }
        }
        return seoEntity;
    }

    @Override
    public HeaderEntity getHeader(IdEntity idEntity, int id) {
        HeaderEntity headerEntity = new HeaderEntity();
        OneshotEntity modelById = oneShotService.getModelById(id);
        headerEntity.setLogo1(modelById.getPhoto1());
        headerEntity.setLogo2(modelById.getPhoto2());
        headerEntity.setIdEntity(idEntity);
        return headerEntity;
    }

    @Override
    public FooterEntity getFooter(int id) {
        FooterEntity footerEntity = new FooterEntity();
        footerEntity.setCommon(oneShotService.getModelById(id));
        return footerEntity;
    }
}
