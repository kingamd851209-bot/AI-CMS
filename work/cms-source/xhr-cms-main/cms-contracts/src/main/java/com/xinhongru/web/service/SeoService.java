package com.xinhongru.web.service;

import com.xinhongru.web.resonseVo.FooterEntity;
import com.xinhongru.web.resonseVo.HeaderEntity;
import com.xinhongru.web.resonseVo.IdEntity;
import com.xinhongru.web.resonseVo.SeoEntity;

/**
 * @Description @Author 永远的神 @Version V1.0.0 @Since 1.0 @Date 2022/4/16 20:24
 */
public interface SeoService {
    /**
     * 获取首页SEO信息
     *
     * @param indexClassId
     * @return
     */
    SeoEntity getIndexSeo(int indexClassId);

    /**
     * 获取栏目SEO信息
     *
     * @param classId
     * @return
     */
    SeoEntity getSeoEntityByClassId(int classId);

    /**
     * 获取内容页SEO信息
     *
     * @param classId
     * @param id
     * @return
     */
    SeoEntity getSeoEntityByClassIdAndId(int classId, int id);

    HeaderEntity getHeader(IdEntity idEntity, int id);

    FooterEntity getFooter(int id);
}
