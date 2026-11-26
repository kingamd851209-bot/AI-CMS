package com.xinhongru.service.content;

import com.xinhongru.common.page.PageResult;
import com.xinhongru.dto.cond.ContentCond;
import com.xinhongru.entity.ContentEntity;
import com.xinhongru.entity.OneshotEntity;
import com.xinhongru.requestDto.IdDto;
import com.xinhongru.requestDto.SortDto;
import com.xinhongru.requestDto.model.OptionDto;
import com.xinhongru.requestDto.model.PageDto;
import com.xinhongru.responseVo.ResultVO;
import com.xinhongru.responseVo.model.ModelManageSeoVo;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 文章服务层 Created at 2021/8/9. */
public interface ContentService {
    /**
     * 添加文章
     *
     * @param contentEntity
     * @return
     */
    ResultVO addArticle(ContentEntity contentEntity, HttpServletRequest httpServletRequest);

    /**
     * 会员名录数据导入
     *
     * @param contentEntity
     * @return
     */
    int addArticleImport(ContentEntity contentEntity);

    /**
     * 根据编号删除文章
     *
     * @param cid
     * @return
     */
    void deleteArticleById(Integer cid);

    /**
     * 批量删除数据
     *
     * @param cids
     * @param classid
     */
    void deleteArticleByIds(List<Integer> cids, Integer classid);

    /**
     * 更新文章
     *
     * @param content
     * @param httpServletRequest
     * @return
     */
    ResultVO updateContentByCid(ContentEntity content, HttpServletRequest httpServletRequest);

    /**
     * 根据编号获取文章
     *
     * @param cid
     * @return
     */
    ContentEntity getArticleById(Integer cid);

    /**
     * 根据条件获取文章列表
     *
     * @param oneshotEntity
     * @param contentCond
     * @param pageNum
     * @param pageSize
     * @return
     */
    PageResult<ContentEntity> getArticlesByCond(
            OneshotEntity oneshotEntity, ContentCond contentCond, int pageNum, int pageSize);

    /**
     * 根据条件获取文章总记录数
     *
     * @param contentCond
     * @return
     */
    int getArticlesCountByCond(@Param("contentCond") ContentCond contentCond);

    /**
     * 获取最近的文章（只包含id和title）
     *
     * @param pageNum
     * @param pageSize
     * @return
     */
    PageResult<ContentEntity> getRecentlyArticle(int pageNum, int pageSize);

    /**
     * 搜索文章
     *
     * @param param
     * @param pageNun
     * @param pageSize
     * @return
     */
    PageResult<ContentEntity> searchArticle(String param, int pageNun, int pageSize);

    /**
     * 更新推荐状态
     *
     * @param ids
     * @param optionType 1首页置顶,2首页热门,3频道置顶,4频道推荐 5取消首页置顶,6取消首页热门,7取消频道置顶,8取消频道推荐
     * @return
     */
    int updateElite(List<Integer> ids, Integer classid, String optionType);

    /**
     * 内容管理 - 修改排序号
     *
     * @param classid
     * @param sortDto
     * @param httpServletRequest
     * @return
     */
    ResultVO updateSort(Integer classid, SortDto sortDto, HttpServletRequest httpServletRequest);

    /**
     * 更新静态页发布状态
     *
     * @param contentEntity
     */
    void updatePublished(ContentEntity contentEntity);

    /**
     * 根据条件查询数据
     *
     * @param oneshotEntity
     * @param pageDto
     * @return
     */
    PageResult<ContentEntity> getContentByCond(OneshotEntity oneshotEntity, PageDto pageDto);

    /**
     * 获取列表类型数据的SEO信息
     *
     * @param classid
     * @param id
     * @return
     */
    ContentEntity getSeoById(Integer classid, Integer id);

    /**
     * 更新SEO信息
     *
     * @param modelManageSeoVo
     * @param httpServletRequest
     * @return
     */
    ResultVO updateSEO(ModelManageSeoVo modelManageSeoVo, HttpServletRequest httpServletRequest);

    /**
     * 删除单一数据
     *
     * @param classid
     * @param id
     * @param httpServletRequest
     * @return
     */
    ResultVO deleteInfoById(Integer classid, IdDto id, HttpServletRequest httpServletRequest);

    /**
     * 更新列表类型的一些操作
     *
     * @param optionDto
     * @param classid
     * @param httpServletRequest
     * @return
     */
    ResultVO updateOption(
            OptionDto optionDto, Integer classid, HttpServletRequest httpServletRequest);

    /**
     * 通过classid获取所有文章内容
     *
     * @param classId
     * @return
     */
    List<ContentEntity> getArticleByClassId(Integer classId);

    /**
     * 通过classid获取所有文章内容的id字段和title字段
     *
     * @param classIdList@return
     */
    List<ContentEntity> getArticleByClassIdForSelect(List<Integer> classIdList);

    /**
     * 导出数据
     *
     * @param classid
     * @param contentCond
     * @param httpServletRequest
     * @param httpServletResponse
     * @return
     */
    void outputExcel(
            Integer classid,
            ContentCond contentCond,
            HttpServletRequest httpServletRequest,
            HttpServletResponse httpServletResponse);

    /**
     * 通过classid和id集合获取所有文章内容的id字段和title字段
     *
     * @param classId
     * @param idList
     * @return 返回按照idList顺序的集合对象 如果数据已经被删除或者被关闭不会被返回
     */
    List<ContentEntity> getArticleByClassIdForSelect(Integer classId, List<Integer> idList);
}
