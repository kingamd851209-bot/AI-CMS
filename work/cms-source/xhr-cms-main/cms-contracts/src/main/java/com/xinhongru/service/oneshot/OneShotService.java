package com.xinhongru.service.oneshot;

import com.xinhongru.dto.OneshotDto;
import com.xinhongru.dto.cond.ContentCond;
import com.xinhongru.entity.BusinessEntity;
import com.xinhongru.entity.ContentEntity;
import com.xinhongru.entity.OneshotEntity;
import com.xinhongru.operations.OperationExecutionContext;
import com.xinhongru.requestDto.IdDto;
import com.xinhongru.requestDto.SortDto;
import com.xinhongru.requestDto.oneshot.MenuModuleEditDto;
import com.xinhongru.requestDto.oneshot.OneshotSwitchDto;
import com.xinhongru.requestDto.oneshot.OneshotTitleDto;
import com.xinhongru.responseVo.ResultVO;
import com.xinhongru.responseVo.builderHtml.BuilderHtmlVo;
import com.xinhongru.responseVo.builderHtml.NodeVo;
import com.xinhongru.responseVo.fieldHtmlVo.FieldHtmlVo;
import com.xinhongru.responseVo.fieldHtmlVo.ModelFieldHtmlVo;
import com.xinhongru.responseVo.oneshot.EditOneshotDto;
import com.xinhongru.responseVo.oneshot.OpenEditOneshotDto;
import com.xinhongru.responseVo.oneshot.SeoDto;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;

@Service
public interface OneShotService {
    /**
     * 后台单篇保存
     *
     * @param oneshotEntity
     * @return
     */
    int save(OneshotEntity oneshotEntity);

    /**
     * 后台单篇更新
     *
     * @param oneshotEntity
     * @param httpServletRequest
     * @return
     */
    ResultVO update(OneshotEntity oneshotEntity, HttpServletRequest httpServletRequest);

    /**
     * 后台获取单篇信息
     *
     * @param id
     * @return
     */
    OneshotEntity getModelById(int id);

    void withdrawSinglePage(Long siteId, Integer menuId);

    /**
     * 后台获取栏目选中字段文本 (单篇内容)
     *
     * @param oneshotEntity
     * @return
     */
    FieldHtmlVo getContentFieldDefinitionList(OneshotEntity oneshotEntity);

    /**
     * 后台获取列表显示选中字段文本 （列表类型 列表页面）
     *
     * @param modelList
     * @param contentEntity
     * @return
     */
    FieldHtmlVo getModelListDisplayFieldList(String modelList, ContentEntity contentEntity);

    /**
     * 后台获取列表添加修改选中字段文本（列表类型 添加修改页面）
     *
     * @param modelById
     * @param contentEntity
     * @param request
     * @return
     */
    FieldHtmlVo getContentFieldDefinitionModelList(
            OneshotEntity modelById, ContentEntity contentEntity, HttpServletRequest request);

    /**
     * 按照条件获取所有栏目 并完成递归
     *
     * @param oneshotDto
     * @return
     */
    List<OneshotEntity> getAllMenu(OneshotDto oneshotDto);

    /**
     * 栏目管理 - 修改开关类型的字段
     *
     * @param oneshotSwitchDto
     * @return
     */
    ResultVO updateSwitch(OneshotSwitchDto oneshotSwitchDto, HttpServletRequest httpServletRequest);

    /**
     * 栏目管理 - 基础配置 -修改排序号
     *
     * @param sortDto
     * @param httpServletRequest
     * @return
     */
    ResultVO updateSort(SortDto sortDto, HttpServletRequest httpServletRequest);

    /**
     * 栏目管理 - 基础配置 -修改栏目标题
     *
     * @param oneshotTitleDto
     * @param httpServletRequest
     * @return
     */
    ResultVO updateTitle(OneshotTitleDto oneshotTitleDto, HttpServletRequest httpServletRequest);

    /**
     * 更新SEO信息
     *
     * @param seoDto
     * @param httpServletRequest
     * @return
     */
    ResultVO updateSEO(SeoDto seoDto, HttpServletRequest httpServletRequest);

    /**
     * 返回下拉列表类型的栏目集合
     *
     * @param oneshotDto
     * @return
     */
    List<OneshotEntity> getAllMenuForDropdownList(OneshotDto oneshotDto);

    /**
     * 返回下拉列表类型的栏目集合，并按当前站点上下文过滤
     *
     * @param oneshotDto
     * @param httpServletRequest
     * @return
     */
    List<OneshotEntity> getAllMenuForDropdownList(
            OneshotDto oneshotDto, HttpServletRequest httpServletRequest);

    /**
     * 返回下拉列表类型的栏目集合，并按站点ID过滤
     *
     * @param oneshotDto
     * @param siteId 站点ID
     * @return
     */
    List<OneshotEntity> getAllMenuForDropdownList(OneshotDto oneshotDto, Long siteId);

    /**
     * 按站点ID返回原始栏目菜单（不处理下拉缩进前缀）；siteId 为空时返回全部
     *
     * @param siteId 站点ID
     * @return 栏目集合
     */
    List<OneshotEntity> getAllMenuBySiteProjectId(Long siteId);

    /**
     * 添加修改栏目数据保存
     *
     * @param editOneshotDto
     * @param httpServletRequest
     * @return
     */
    ResultVO updateEdit(EditOneshotDto editOneshotDto, HttpServletRequest httpServletRequest);

    /**
     * 删除栏目
     *
     * @param idDto
     * @param httpServletRequest
     * @return
     */
    ResultVO deleteMenu(IdDto idDto, HttpServletRequest httpServletRequest);

    /**
     * 栏目管理（开发） 通过模块添加栏目
     *
     * @param menuModuleEditDto
     * @param httpServletRequest
     * @return
     */
    ResultVO addEditByModuleId(
            MenuModuleEditDto menuModuleEditDto, HttpServletRequest httpServletRequest);

    /**
     * 获取所有内容管理栏目
     *
     * @param oneshotDto
     * @return
     */
    List<OneshotEntity> getAllContentMenu(OneshotDto oneshotDto);

    /**
     * 获取所有内容管理栏目，并按当前站点上下文过滤
     *
     * @param oneshotDto
     * @param httpServletRequest
     * @return
     */
    List<OneshotEntity> getAllContentMenu(
            OneshotDto oneshotDto, HttpServletRequest httpServletRequest);

    /**
     * 获取前后台不都为隐藏的栏目 意思就是不返回前后台都隐藏的栏目
     *
     * @param oneshotDto
     * @return
     */
    List<OneshotEntity> getAllContentMenuManageAndFrontNotAllFalse(OneshotDto oneshotDto);

    /**
     * 获取前后台不都为隐藏的栏目，并按当前站点上下文过滤
     *
     * @param oneshotDto
     * @param httpServletRequest
     * @return
     */
    List<OneshotEntity> getAllContentMenuManageAndFrontNotAllFalse(
            OneshotDto oneshotDto, HttpServletRequest httpServletRequest);

    /**
     * 获取列表页面对象
     *
     * @param modelById
     * @param contentCond
     * @param page
     * @param limit
     * @param httpServletRequest
     * @return
     */
    ModelFieldHtmlVo getContentList(
            OneshotEntity modelById,
            ContentCond contentCond,
            int page,
            int limit,
            HttpServletRequest httpServletRequest);

    /**
     * @param openEditOneshotDto
     * @param httpServletRequest
     * @return
     */
    ResultVO updateEditOpen(
            OpenEditOneshotDto openEditOneshotDto, HttpServletRequest httpServletRequest);

    /**
     * 获取静态页生成的树对象
     *
     * @param httpServletRequest
     * @return
     */
    List<NodeVo> getBuilderNodes(HttpServletRequest httpServletRequest);

    /**
     * 获取静态页发布栏目节点（支持懒加载）
     *
     * @param httpServletRequest
     * @param parentId 父栏目ID，null 则返回根节点
     * @return
     */
    List<NodeVo> getBuilderNodes(HttpServletRequest httpServletRequest, Integer parentId);

    /**
     * 构建生成任务
     *
     * @param httpServletRequest
     * @param builderHtmlVo
     * @return
     */
    ResultVO builderHtmlTask(HttpServletRequest httpServletRequest, BuilderHtmlVo builderHtmlVo);

    /**
     * 发布静态页面
     *
     * @param httpServletRequest
     * @param httpServletResponse
     * @param taskId
     */
    void builderHtml(
            HttpServletRequest httpServletRequest,
            HttpServletResponse httpServletResponse,
            String taskId);

    /**
     * 发布指定语言静态页面。不传语言时由旧方法保持原行为。
     *
     * @param httpServletRequest
     * @param httpServletResponse
     * @param taskId
     * @param languageCode
     */
    void builderHtml(
            HttpServletRequest httpServletRequest,
            HttpServletResponse httpServletResponse,
            String taskId,
            String languageCode);

    void builderHtml(
            HttpServletRequest httpServletRequest,
            HttpServletResponse httpServletResponse,
            String taskId,
            String languageCode,
            OperationExecutionContext operationContext);

    /** Render a frozen release into an explicit staging root instead of the live html root. */
    void builderHtmlToStaging(
            HttpServletRequest request,
            HttpServletResponse response,
            String releaseId,
            String languageCode,
            List<Integer> menuIds,
            Path stagingRoot);

    /** Per-page hooks for a frozen release build; existing callers remain source-compatible. */
    default void builderHtmlToStaging(
            HttpServletRequest request,
            HttpServletResponse response,
            String releaseId,
            String languageCode,
            List<Integer> menuIds,
            Path stagingRoot,
            PageBuildProgress progress) {
        builderHtmlToStaging(request, response, releaseId, languageCode, menuIds, stagingRoot);
    }

    interface PageBuildProgress {
        void beforePage(String publicPath);

        void afterPage(String publicPath);

        static PageBuildProgress none() {
            return new PageBuildProgress() {
                @Override
                public void beforePage(String publicPath) {}

                @Override
                public void afterPage(String publicPath) {}
            };
        }
    }

    /**
     * 获取可修改编辑栏目（对外开放）：非系统栏目、非二级栏目
     *
     * @param oneshotDto
     * @return
     */
    List<OneshotEntity> getEditableMenu(OneshotDto oneshotDto);

    /**
     * 获取当前站点内可修改编辑栏目（对外开放）：非系统栏目、非二级栏目
     *
     * @param oneshotDto
     * @param httpServletRequest
     * @return
     */
    List<OneshotEntity> getEditableMenu(
            OneshotDto oneshotDto, HttpServletRequest httpServletRequest);

    /**
     * 获取业务分类列表
     *
     * @return
     */
    List<BusinessEntity> getBusinessEntityList();
}
