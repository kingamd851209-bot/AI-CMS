package com.xinhongru.controller.admin;

import com.alibaba.fastjson.JSON;
import com.xinhongru.annotation.SystemLog;
import com.xinhongru.exception.BusinessException;
import com.xinhongru.operations.OperationRequestIdentity;
import com.xinhongru.operations.OperationTask;
import com.xinhongru.operations.OperationTaskExecutionAdapter;
import com.xinhongru.responseVo.ResultVO;
import com.xinhongru.responseVo.builderHtml.BuilderHtmlVo;
import com.xinhongru.service.oneshot.OneShotService;
import com.xinhongru.site.context.SiteContextService;
import com.xinhongru.utils.RedisUtils;

import io.swagger.annotations.Api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import lombok.extern.slf4j.Slf4j;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

@Api("静态页发布")
@Slf4j
@Controller
@Transactional(rollbackFor = BusinessException.class)
@RequestMapping("/admin/builder")
@Validated
public class BuilderHtmlController {
    private OneShotService oneShotService;
    private OperationTaskExecutionAdapter operationTaskAdapter;
    private SiteContextService siteContextService;
    private final String operationOwnerToken = "static-builder-" + UUID.randomUUID();

    @Autowired
    public void setOneShotService(OneShotService oneShotService) {
        this.oneShotService = oneShotService;
    }

    @Autowired(required = false)
    public void setOperationTaskAdapter(OperationTaskExecutionAdapter operationTaskAdapter) {
        this.operationTaskAdapter = operationTaskAdapter;
    }

    @Autowired
    public void setSiteContextService(SiteContextService siteContextService) {
        this.siteContextService = siteContextService;
    }

    @RequestMapping("/index")
    @SystemLog(value = "静态页发布 - 进入静态页发布页面", classId = 15)
    public String index(
            Model model,
            HttpServletResponse httpServletResponse,
            HttpServletRequest httpServletRequest) {
        model.addAttribute(
                "view", JSON.toJSONString(oneShotService.getBuilderNodes(httpServletRequest)));
        return "redirect:/admin/v2/sites";
    }

    @ResponseBody
    @PostMapping("/index")
    @SystemLog(value = "静态页发布 - 创建发布任务", classId = 15)
    public ResultVO builderHtml(
            @RequestBody @Validated BuilderHtmlVo builderHtmlVo,
            HttpServletResponse httpServletResponse,
            HttpServletRequest httpServletRequest) {
        return oneShotService.builderHtmlTask(httpServletRequest, builderHtmlVo);
    }

    @GetMapping("/publish")
    @SystemLog(value = "静态页发布 - 发布静态页面", classId = 15)
    public void builderHtml(
            HttpServletResponse response,
            HttpServletRequest request,
            String taskId,
            String languageCode,
            String lang) {
        String language = StringUtils.defaultIfBlank(languageCode, lang);
        if (operationTaskAdapter == null) {
            if (StringUtils.isBlank(language)) {
                oneShotService.builderHtml(request, response, taskId);
            } else {
                oneShotService.builderHtml(request, response, taskId, language);
            }
            return;
        }
        List<Integer> menuIds = RedisUtils.getEntity(taskId, List.class);
        if (menuIds == null || menuIds.isEmpty()) {
            throw new IllegalStateException("静态发布任务缺少可重建的栏目清单");
        }
        OperationTaskExecutionAdapter.ExecutionResult<StaticPublishResult> execution =
                operationTaskAdapter.execute(
                        new OperationTask.TaskSubmission(
                                requireSiteId(request),
                                OperationTask.Type.STATIC_PUBLISH,
                                "legacy-static-"
                                        + taskId
                                        + '-'
                                        + StringUtils.defaultString(language, "default"),
                                "/admin/builder/publish",
                                "传统静态页面发布",
                                OperationRequestIdentity.currentOperatorId(),
                                OperationTaskExecutionAdapter.command(
                                        new StaticPublishCommand(
                                                taskId,
                                                StringUtils.defaultString(language),
                                                menuIds))),
                        operationOwnerToken,
                        Duration.ofMinutes(30),
                        context -> {
                            oneShotService.builderHtml(
                                    request, response, taskId, language, context);
                            return new StaticPublishResult(taskId, language);
                        },
                        result -> "静态页面发布完成",
                        OperationTaskExecutionAdapter.json(StaticPublishResult.class));
        if (execution.result() == null) {
            throw new IllegalStateException("静态发布任务正在由其他实例处理，请到运营任务查看结果");
        }
    }

    private record StaticPublishResult(String taskId, String languageCode) {}

    private record StaticPublishCommand(String taskId, String language, List<Integer> menuIds) {}

    private long requireSiteId(HttpServletRequest request) {
        Long siteId = siteContextService.current(request).getSiteId();
        if (siteId == null || siteId <= 0) {
            throw new IllegalStateException("无法从静态发布请求推导站点");
        }
        return siteId;
    }
}
