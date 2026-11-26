package com.xinhongru.web.service.impl;

import com.xinhongru.constant.WebConst;
import com.xinhongru.entity.OneshotEntity;
import com.xinhongru.web.mapper.MenuMapper;
import com.xinhongru.web.resonseVo.ChildCompanyDTO;
import com.xinhongru.web.service.MenuService;

import lombok.extern.slf4j.Slf4j;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * @Description @ClassName MenuServiceImpl @Author 永远的神 @Version V1.0.0 @Since 1.0 @Date 2022/4/16
 * 22:08
 */
@Slf4j
@Service
public class MenuServiceImpl implements MenuService {
    private MenuMapper menuMapper;

    @Autowired
    public void setMenuMapper(MenuMapper menuMapper) {
        this.menuMapper = menuMapper;
    }

    @Cacheable(value = "modelById", key = "'modelById'+#p0")
    @Override
    public OneshotEntity getModelById(int id) {
        OneshotEntity modelById = menuMapper.getModelById(id);
        if (modelById != null) {
            modelById.setListPageUrl(getListUrl(modelById));
        }
        return modelById;
    }

    @Override
    public OneshotEntity getModelByIdIgnoreChannel(int id) {
        OneshotEntity column = menuMapper.getModelByIdIgnoreChannel(id);
        if (column != null) {
            column.setListPageUrl(getListUrl(column));
        }
        return column;
    }

    @Override
    public OneshotEntity getOneshotEntityByBizcode(String bizcode, Long siteProjectId) {
        if (StringUtils.isBlank(bizcode) || siteProjectId == null) {
            return null;
        }
        OneshotEntity entity = menuMapper.getOneshotEntityByBizcode(bizcode, siteProjectId);
        if (entity != null) {
            entity.setListPageUrl(getListUrl(entity));
        }
        return entity;
    }

    @Override
    public List<OneshotEntity> getOneshotListByBizcodes(List<String> bizcodes, Long siteProjectId) {
        if (CollectionUtils.isEmpty(bizcodes) || siteProjectId == null) {
            return new ArrayList<>();
        }
        List<OneshotEntity> list = menuMapper.getOneshotListByBizcodes(bizcodes, siteProjectId);
        return list != null ? list : new ArrayList<>();
    }

    @Override
    public List<OneshotEntity> getOneshotEntitiesByBizcodes(
            List<String> bizcodes, Long siteProjectId) {
        if (CollectionUtils.isEmpty(bizcodes) || siteProjectId == null) {
            return new ArrayList<>();
        }
        List<OneshotEntity> columns =
                menuMapper.getOneshotEntitiesByBizcodes(bizcodes, siteProjectId);
        return resolveListUrls(columns);
    }

    @Override
    public List<OneshotEntity> getOneshotChildrenByParentIds(
            List<Integer> parentIds, Long siteProjectId) {
        if (CollectionUtils.isEmpty(parentIds) || siteProjectId == null) {
            return new ArrayList<>();
        }
        List<OneshotEntity> columns =
                menuMapper.getOneshotChildrenByParentIds(parentIds, siteProjectId);
        return resolveListUrls(columns);
    }

    @Override
    public List<OneshotEntity> getOneshotChildrenIgnoreChannel(int parentId) {
        List<OneshotEntity> columns =
                menuMapper.getOneshotChildrenByParentIdIgnoreChannel(parentId);
        return resolveListUrls(columns);
    }

    private List<OneshotEntity> resolveListUrls(List<OneshotEntity> columns) {
        columns.forEach(column -> column.setListPageUrl(getListUrl(column)));
        return columns;
    }

    private String getListUrl(OneshotEntity oneshotEntity) {
        if (oneshotEntity == null) {
            return "javascript:void(0);";
        } else if (StringUtils.isBlank(oneshotEntity.getListPageUrl())) {
            // 如果为空则读取第一个子栏目的地址
            OneshotEntity firstModelByParentId = getFirstModelByParentId(oneshotEntity.getId());
            if (firstModelByParentId == null) {
                return "javascript:void(0);";
            } else {
                oneshotEntity.setListPageUrl(firstModelByParentId.getListPageUrl());
            }
        }
        return oneshotEntity
                .getListPageUrl()
                .replace("{pageIndex}", "1")
                .replace("{classId}", String.valueOf(oneshotEntity.getId()))
                .replace("{parentId}", String.valueOf(oneshotEntity.getParentId()))
                .replace("{id}", String.valueOf(oneshotEntity.getId()));
    }

    /** 根据parentId获取栏目集合 */
    @Cacheable(value = "oneshotListByParentId", key = "'oneshotListByParentId'+#p0")
    @Override
    public List<OneshotEntity> getOneshotListByParentId(int parentId) {
        List<OneshotEntity> listByParentId = menuMapper.getOneshotListByParentId(parentId);
        return resolveListUrls(listByParentId);
    }

    @Override
    public List<OneshotEntity> getOneshotListByParentId(int parentId, int limit) {
        List<OneshotEntity> oneshotListByParentId = getOneshotListByParentId(parentId);
        return oneshotListByParentId.stream().limit(limit).collect(Collectors.toList());
    }

    //    @Cacheable(value = "oneshotListByParentId", key = "'oneshotListByParentId'+#p0+'_'+#p1")
    @Override
    public List<OneshotEntity> getOneshotListByParentId(int parentId, String outClassId) {
        List<OneshotEntity> listByParentId = getOneshotListByParentId(parentId);
        if (StringUtils.isNotBlank(outClassId)) {
            Set<String> classIdList = new HashSet<>(Arrays.asList(outClassId.split(",")));
            return listByParentId.stream()
                    .filter(item -> !classIdList.contains(String.valueOf(item.getId())))
                    .collect(Collectors.toList());
        }
        return listByParentId;
    }

    @Override
    public OneshotEntity getFirstModelByParentId(Integer parentId) {
        List<OneshotEntity> oneshotListByParentId = getOneshotListByParentId(parentId);
        if (CollectionUtils.isNotEmpty(oneshotListByParentId)) {
            return oneshotListByParentId.stream().findFirst().get();
        } else {
            return null;
        }
    }

    @Override
    public List<OneshotEntity> getOneshotListByIds(String ids) {
        if (StringUtils.isBlank(ids)) {
            return new ArrayList<>();
        }
        ids = ids.replace("，", ",").replace(" ", "");
        List<OneshotEntity> listByParentId =
                menuMapper.getOneshotListByIds(Arrays.asList(ids.split(",")));
        return resolveListUrls(listByParentId);
    }

    @Override
    public List<OneshotEntity> getOneshotListByIdsRaw(List<Integer> ids) {
        if (CollectionUtils.isEmpty(ids)) {
            return new ArrayList<>();
        }
        List<OneshotEntity> list = menuMapper.getOneshotListByIdsRaw(ids);
        return list != null ? list : new ArrayList<>();
    }

    @Override
    public void updateHits(Integer classId) {
        menuMapper.updateHits(classId);
    }

    /** 产品内容页获取随机推荐产品 */
    @Override
    public List<OneshotEntity> getRandomProduct() {
        List<OneshotEntity> randomProduct = menuMapper.getRandomProduct();
        return resolveListUrls(randomProduct);
    }

    @Override
    public OneshotEntity getOneshotEntityByParentIdAndIdentifies(int parentId, String identifies) {
        for (OneshotEntity column : menuMapper.getOneshotListByParentId(parentId)) {
            if (identifies.equals(column.getIdentifies())) {
                column.setListPageUrl(getListUrl(column));
                return column;
            }
        }
        return null;
    }

    @Override
    public List<Integer> getClassIdListByParentIdAndIdentifies(int parentId, String identifies) {
        List<OneshotEntity> oneshotEntityList =
                getOneshotListByParentIdAndIdentifies(parentId, identifies);
        if (CollectionUtils.isNotEmpty(oneshotEntityList)) {
            return oneshotEntityList.stream()
                    .map(oneshotEntity -> oneshotEntity.getId())
                    .collect(Collectors.toList());
        }
        return new ArrayList<>();
    }

    @Override
    public List<OneshotEntity> getOneshotListByParentIdAndIdentifies(
            int parentId, String... identifies) {
        Set<String> requested = new HashSet<>(Arrays.asList(identifies));
        List<OneshotEntity> oneshotListByParentId = menuMapper.getOneshotListByParentId(parentId);
        oneshotListByParentId =
                oneshotListByParentId.stream()
                        .filter(column -> requested.contains(column.getIdentifies()))
                        .collect(Collectors.toList());
        return resolveListUrls(oneshotListByParentId);
    }

    // @Cacheable(value = "getBreadcrumb", key = "'getBreadcrumb'+#classId")
    @Override
    public String getBreadcrumb(int classId, int id) {
        StringBuilder stringBuilder = new StringBuilder();
        int depth = 0;
        if (id > 0) {
            depth = 1;
        }
        // 容器开始标签
        // stringBuilder.append("<div class=\"mm site\">");
        String crumbsHtml = "";
        // 首页
        stringBuilder.append(getCrumbsHtml(WebConst.HOME_ID, crumbsHtml, depth));
        crumbsHtml = "";
        if (id > 0) {
            crumbsHtml = " - text";
        }

        // 栏目循环体
        crumbsHtml = getCrumbsHtml(classId, crumbsHtml, depth);
        stringBuilder.append(crumbsHtml);
        // 容器结束
        // stringBuilder.append("</div>");
        return stringBuilder.toString();
    }

    @Override
    public List<OneshotEntity> getOneshotListByParentIdAndShowPosition(
            int parentId, String showPosition) {
        List<OneshotEntity> oneshotListByParentId = menuMapper.getOneshotListByParentId(parentId);
        oneshotListByParentId =
                oneshotListByParentId.stream()
                        .filter(
                                oneshotEntity ->
                                        oneshotEntity.getShowPosition() != null
                                                && oneshotEntity
                                                        .getShowPosition()
                                                        .contains(showPosition))
                        .collect(Collectors.toList());
        return resolveListUrls(oneshotListByParentId);
    }

    @Override
    public List<OneshotEntity> getCompanyListByBusinessId(Integer businessId) {
        return menuMapper.getCompanyListByBusinessId(businessId);
    }

    @Override
    public List<ChildCompanyDTO> getChildCompany(Integer categoryId) {
        return menuMapper.getChildCompany(categoryId);
    }

    @Override
    public List<Integer> getClassIdListByIdentifies(String identifies) {
        return menuMapper.getClassIdListByIdentifies(identifies);
    }

    @Override
    public List<Integer> getClassIdListByIdentifies(String identifies, Long siteProjectId) {
        if (StringUtils.isBlank(identifies) || siteProjectId == null) {
            return new ArrayList<>();
        }
        List<Integer> ids = menuMapper.getClassIdListByIdentifiesAndSite(identifies, siteProjectId);
        return ids != null ? ids : new ArrayList<>();
    }

    /** 循环遍历当前位置标签 */
    private String getCrumbsHtml(int classId, String crumbsHtml, int depth) {
        OneshotEntity modelById = getModelById(classId);
        if (modelById != null && modelById.getIsShowCrumbs()) {
            if (classId == WebConst.HOME_ID) {
                crumbsHtml =
                        "<a href=\""
                                + getListUrl(modelById)
                                + "\">"
                                + modelById.getClassName()
                                + "</a>\n"
                                + crumbsHtml;
            } else {
                if (depth == 0) {
                    crumbsHtml =
                            "<em>/</em><span>"
                                    + modelById.getClassName()
                                    + "</span>\n"
                                    + crumbsHtml;
                } else {
                    crumbsHtml =
                            "<em>/</em><a href=\""
                                    + getListUrl(modelById)
                                    + "\">"
                                    + modelById.getClassName()
                                    + "</a>\n"
                                    + crumbsHtml;
                }
            }
            depth++;
            return getCrumbsHtml(modelById.getParentId(), crumbsHtml, depth);
        }

        return crumbsHtml;
    }
}
