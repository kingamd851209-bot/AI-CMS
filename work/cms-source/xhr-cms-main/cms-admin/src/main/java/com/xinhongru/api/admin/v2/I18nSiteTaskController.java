package com.xinhongru.api.admin.v2;

import com.xinhongru.api.admin.AdminApiResponse;
import com.xinhongru.entity.UserEntity;
import com.xinhongru.i18n.task.entity.I18nSiteTaskEntity;
import com.xinhongru.i18n.task.service.I18nSiteTaskService;
import com.xinhongru.i18n.task.view.I18nSiteTaskDetailView;
import com.xinhongru.site.context.SiteContextService;
import com.xinhongru.utils.TaleUtils;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** 多语言站点任务：主任务列表/明细、按栏目维度同步 */
@RestController
@RequestMapping("/api/admin/v2/i18n-site-tasks")
public class I18nSiteTaskController {
    private final I18nSiteTaskService taskService;
    private final SiteContextService siteContextService;

    public I18nSiteTaskController(
            I18nSiteTaskService taskService, SiteContextService siteContextService) {
        this.taskService = taskService;
        this.siteContextService = siteContextService;
    }

    /** 主任务列表 */
    @GetMapping
    public AdminApiResponse<List<I18nSiteTaskEntity>> list(
            @RequestParam(required = false) String keyword) {
        return AdminApiResponse.success(taskService.listTasks(keyword));
    }

    /** 主任务 + 明细（含栏目编码、上级栏目与模版路径） */
    @GetMapping("/{taskId}")
    public AdminApiResponse<TaskDetailResponse> detail(@PathVariable Long taskId) {
        I18nSiteTaskEntity task = taskService.getTask(taskId);
        if (task == null) {
            throw new IllegalArgumentException("任务不存在");
        }
        return AdminApiResponse.success(
                new TaskDetailResponse(task, taskService.listDetailViews(taskId)));
    }

    /** 按逐条明细各自选定的维度同步栏目数据（会覆盖目标站点对应数据） */
    @PostMapping("/{taskId}/sync")
    public AdminApiResponse<Void> sync(
            HttpServletRequest request, @PathVariable Long taskId, @RequestBody SyncRequest body) {
        siteContextService.requireSuperAdmin(request);
        UserEntity user = TaleUtils.getLoginUser(request);
        Long operatorId = user == null ? null : user.getId().longValue();
        String operatorName = user == null ? null : user.getUsername();
        taskService.syncDetails(
                taskId,
                body == null ? null : body.detailDimensions(),
                body == null
                        ? null
                        : new I18nSiteTaskService.SyncOptions(
                                body.contentMode(),
                                body.contentTranslation(),
                                body.modelTranslation(),
                                body.existingAction()),
                operatorId,
                operatorName);
        return AdminApiResponse.success();
    }

    /** 检查（必要时自动启动）AI 翻译服务，供同步前提示：ready=false 时 message 为不可用原因 */
    @GetMapping("/ai-translation-ready")
    public AdminApiResponse<TranslationReadyView> translationReady(HttpServletRequest request) {
        siteContextService.requireSuperAdmin(request);
        String unavailable = taskService.ensureTranslationAvailable();
        return AdminApiResponse.success(new TranslationReadyView(unavailable == null, unavailable));
    }

    /** 同步静态资源：把原站点 UIPackage 目录复制到目标站点（会覆盖目标站点静态资源），后台异步执行 */
    @PostMapping("/{taskId}/sync-assets")
    public AdminApiResponse<I18nSiteTaskEntity> syncAssets(
            HttpServletRequest request, @PathVariable Long taskId) {
        siteContextService.requireSuperAdmin(request);
        taskService.syncAssets(taskId);
        return AdminApiResponse.success(taskService.getTask(taskId));
    }

    /** 停止该任务正在进行的同步（协作式取消：停在当前栏目/当前文件之后） */
    @PostMapping("/{taskId}/sync-stop")
    public AdminApiResponse<StopSyncResponse> stopSync(
            HttpServletRequest request, @PathVariable Long taskId) {
        siteContextService.requireSuperAdmin(request);
        boolean stopped = taskService.stopSync(taskId);
        return AdminApiResponse.success(new StopSyncResponse(stopped, taskService.getTask(taskId)));
    }

    /** 停止结果：stopped 表示确实有进行中的同步并已发出停止 */
    public record StopSyncResponse(boolean stopped, I18nSiteTaskEntity task) {}

    /** AI 翻译服务就绪检查结果：ready=false 时 message 为不可用原因 */
    public record TranslationReadyView(boolean ready, String message) {}

    /**
     * 同步请求
     *
     * @param detailDimensions 明细ID -> 该明细要同步的维度(model/template/content)；维度为空表示该明细全部维度
     * @param contentMode 内容同步方式：increment(增量同步) / overwrite(覆盖同步)
     * @param contentTranslation 栏目内容翻译方式：none(原文同步) / ai(AI翻译后同步)
     * @param modelTranslation 数据模型字段业务名称翻译方式：none(原文同步) / ai(AI翻译后同步)
     * @param existingAction 增量命中已存在内容时的处理：skip(跳过) / update(更新)
     */
    public record SyncRequest(
            Map<Long, Set<String>> detailDimensions,
            String contentMode,
            String contentTranslation,
            String modelTranslation,
            String existingAction) {}

    public record TaskDetailResponse(
            I18nSiteTaskEntity task, List<I18nSiteTaskDetailView> details) {}
}
