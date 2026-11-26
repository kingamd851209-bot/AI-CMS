package com.xinhongru.api.admin.v2;

import com.xinhongru.ai.generation.AiGenerationTasks;
import com.xinhongru.ai.generation.AiUsage;
import com.xinhongru.api.admin.AdminApiResponse;
import com.xinhongru.cms.template.TemplateDomainChatService;
import com.xinhongru.cms.template.TemplateSeedImportService;
import com.xinhongru.cms.template.TemplateSeedService;
import com.xinhongru.site.context.SiteContextService;
import com.xinhongru.site.entity.SiteProjectEntity;
import com.xinhongru.site.mapper.SiteProjectMapper;
import com.xinhongru.utils.TaleUtils;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.io.IOException;

@RestController
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "cms.workbench.enabled",
        havingValue = "true",
        matchIfMissing = false)
@RequestMapping("/api/admin/v2/sites/{siteId}/template-seed")
public class TemplateSeedController {
    private static final Set<String> BINDING_PREVIEW_KINDS =
            Set.of("TEXT", "HTML_REGION", "IMAGE", "BACKGROUND_IMAGE", "LINK");
    private static final ObjectMapper CHAT_JSON = new ObjectMapper();
    private final TemplateSeedService service;

    @org.springframework.beans.factory.annotation.Autowired
    private com.xinhongru.ai.generation.AiGenerationTasks analysisTasks;

    @org.springframework.beans.factory.annotation.Value("${cms.workbench.experience.enabled:false}")
    private boolean experienceEnabled;

    @org.springframework.beans.factory.annotation.Autowired
    private com.xinhongru.cms.template.TextBindingDraftService textDrafts;

    @org.springframework.beans.factory.annotation.Autowired
    private com.xinhongru.cms.template.TemplateFieldPlanService fieldPlans;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.xinhongru.cms.template.PackageFieldPlanService packageFields;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.xinhongru.cms.template.SiteModelPlanService siteModelPlans;

    @org.springframework.beans.factory.annotation.Autowired
    private com.xinhongru.cms.template.TemplateContentUnitsService contentUnits;

    @org.springframework.beans.factory.annotation.Autowired
    private com.xinhongru.cms.template.PageBodyCoverageService bodyCoverage;

    @org.springframework.beans.factory.annotation.Autowired
    private TemplateDomainChatService domainChat;

    @org.springframework.beans.factory.annotation.Autowired
    private com.xinhongru.cms.uipackage.UiPackageInspectionService packageInspection;

    @org.springframework.beans.factory.annotation.Autowired
    private com.xinhongru.cms.template.TemplateFragmentInspectionService fragmentInspection;

    @org.springframework.beans.factory.annotation.Autowired
    private com.xinhongru.cms.template.TextBindingPublicApplicationService publicBindings;

    private final com.xinhongru.cms.template.TemplateSeedAnalysisService analysis;
    private final TemplateSeedImportService imports;
    private final com.xinhongru.permission.AdminPermissionService permissions;
    private final SiteContextService siteContext;
    private final SiteProjectMapper projects;

    public TemplateSeedController(
            TemplateSeedService service,
            SiteContextService siteContext,
            SiteProjectMapper projects,
            TemplateSeedImportService imports,
            com.xinhongru.permission.AdminPermissionService permissions,
            com.xinhongru.cms.template.TemplateSeedAnalysisService analysis) {
        this.service = service;
        this.analysis = analysis;
        this.imports = imports;
        this.permissions = permissions;
        this.siteContext = siteContext;
        this.projects = projects;
    }

    private SiteProjectEntity site(HttpServletRequest request, Long siteId) {
        siteContext.requireSuperAdmin(request);
        siteContext.assertCanAccessSite(request, siteId);
        SiteProjectEntity site = projects.selectById(siteId);
        if (site == null) throw new IllegalArgumentException("站点不存在");
        return site;
    }

    @GetMapping("/pages")
    public AdminApiResponse<List<TemplateSeedService.Page>> pages(
            HttpServletRequest request, @PathVariable Long siteId) {
        var site = site(request, siteId);
        return AdminApiResponse.success(
                service.pages(site.getProjectCode(), site.getUiPackageVersion()));
    }

    @GetMapping("/preview")
    public AdminApiResponse<TemplateSeedService.Preview> preview(
            HttpServletRequest request,
            @PathVariable Long siteId,
            @RequestParam String sourcePath) {
        var site = site(request, siteId);
        return AdminApiResponse.success(
                service.preview(site.getProjectCode(), site.getUiPackageVersion(), sourcePath));
    }

    @GetMapping("/package-inspection")
    public AdminApiResponse<com.xinhongru.cms.uipackage.UiPackageInspectionService.Inspection>
            inspectPackage(HttpServletRequest request, @PathVariable Long siteId) {
        var site = site(request, siteId);
        return AdminApiResponse.success(
                packageInspection.inspect(site.getProjectCode(), site.getUiPackageVersion()));
    }

    @GetMapping("/async-fragments")
    public AdminApiResponse<com.xinhongru.cms.template.TemplateFragmentInspectionService.Inspection>
            inspectFragments(
                    HttpServletRequest request,
                    @PathVariable Long siteId,
                    @RequestParam String sourcePath) {
        var site = site(request, siteId);
        return AdminApiResponse.success(
                fragmentInspection.inspect(
                        site.getProjectCode(), site.getUiPackageVersion(), sourcePath));
    }

    @GetMapping("/isolated-preview")
    public AdminApiResponse<com.xinhongru.cms.uipackage.UiPackageInspectionService.Preview>
            isolatedPreview(
                    HttpServletRequest request,
                    @PathVariable Long siteId,
                    @RequestParam String sourcePath,
                    @RequestParam(required = false) String packageVersion) {
        var project = site(request, siteId);
        String code = project.getProjectCode(), version = project.getUiPackageVersion();
        if (Integer.valueOf(1).equals(project.getDeleted())
                || packageVersion != null && !packageVersion.isBlank() && !Objects.equals(version, packageVersion))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "站点或页面包已变化，请重新读取");
        var result = packageInspection.preview(code, version, sourcePath);
        var current = site(request, siteId);
        if (Integer.valueOf(1).equals(current.getDeleted())
                || !Objects.equals(code, current.getProjectCode())
                || !Objects.equals(version, current.getUiPackageVersion()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "站点或页面包已变化，请重新读取");
        return AdminApiResponse.success(result);
    }

    @GetMapping("/preview-videos")
    public AdminApiResponse<com.xinhongru.cms.uipackage.UiPackageInspectionService.Videos>
            previewVideos(
                    HttpServletRequest request, @PathVariable Long siteId,
                    @RequestParam String sourcePath, @RequestParam String packageVersion)
                    throws IOException {
        String code = previewVideoScope(request, siteId, packageVersion);
        var result = packageInspection.videos(code, packageVersion, sourcePath);
        recheckPreviewVideoScope(request, siteId, packageVersion, code);
        return AdminApiResponse.success(result);
    }

    @GetMapping("/preview-video")
    public void previewVideo(
            HttpServletRequest request, HttpServletResponse response, @PathVariable Long siteId,
            @RequestParam String sourcePath, @RequestParam String packageVersion,
            @RequestParam String path)
            throws IOException {
        String code = previewVideoScope(request, siteId, packageVersion);
        var delivery = packageInspection.video(code, packageVersion, sourcePath, path, request.getHeader("Range"));
        recheckPreviewVideoScope(request, siteId, packageVersion, code);
        delivery.headers().forEach(response::setHeader);
        response.setHeader("Cache-Control", "private, no-store");
        response.setStatus(delivery.status());
        if (!"HEAD".equals(request.getMethod())) delivery.write(response.getOutputStream());
    }

    private String previewVideoScope(HttpServletRequest request, Long siteId, String version) {
        var project = site(request, siteId);
        if (version.isBlank() || Integer.valueOf(1).equals(project.getDeleted())
                || !Objects.equals(version, project.getUiPackageVersion()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "站点或页面包已变化，请重新读取");
        return project.getProjectCode();
    }

    private void recheckPreviewVideoScope(
            HttpServletRequest request, Long siteId, String version, String code) {
        if (!Objects.equals(code, previewVideoScope(request, siteId, version)))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "站点或页面包已变化，请重新读取");
    }

    @GetMapping("/content-units")
    public AdminApiResponse<com.xinhongru.cms.template.TemplateContentUnitsService.Workspace>
            contentUnits(
                    HttpServletRequest request,
                    @PathVariable Long siteId,
                    @RequestParam String sourcePath) {
        var site = site(request, siteId);
        return AdminApiResponse.success(
                contentUnits.load(site.getProjectCode(), site.getUiPackageVersion(), sourcePath));
    }

    @GetMapping("/coverage")
    public AdminApiResponse<com.xinhongru.cms.template.PageBodyCoverageService.Coverage> contentCoverage(
            HttpServletRequest request, @PathVariable Long siteId, @RequestParam String sourcePath,
            @RequestParam String packageVersion, @RequestParam String digest, @RequestParam long revision) {
        var project = site(request, siteId);
        String code = project.getProjectCode();
        String version = project.getUiPackageVersion();
        if (Integer.valueOf(1).equals(project.getDeleted()) || !Objects.equals(version, packageVersion))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "站点或页面包已变化，请重新读取");
        var result = bodyCoverage.read(code, version, sourcePath, digest, revision);
        var current = site(request, siteId);
        if (Integer.valueOf(1).equals(current.getDeleted()) || !Objects.equals(code, current.getProjectCode())
                || !Objects.equals(version, current.getUiPackageVersion()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "站点或页面包已变化，请重新读取");
        return AdminApiResponse.success(result);
    }

    @PostMapping("/domain-agent-chat")
    public AdminApiResponse<TemplateDomainChatService.Reply> domainChat(
            HttpServletRequest request,
            @PathVariable Long siteId,
            @RequestBody TemplateDomainChatService.Request body) {
        var project = site(request, siteId);
        var user = TaleUtils.getLoginUser(request);
        if (user == null || user.getId() == null)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前账号无权使用内部 Agent 对话");
        String code = project.getProjectCode();
        String version = project.getUiPackageVersion();
        return AdminApiResponse.success(domainChat.reply(code, version, body, () -> {
            if (!permissions.snapshotForActor(user.getId(), siteId).isAll())
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前账号无权使用内部 Agent 对话");
            var current = site(request, siteId);
            if (current.getRootMenuId() == null || Integer.valueOf(1).equals(current.getDeleted())
                    || code == null || version == null || version.isBlank()
                    || !Objects.equals(code, current.getProjectCode())
                    || !Objects.equals(version, current.getUiPackageVersion()))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "站点或页面包已变化，请刷新后继续对话");
        }, () -> domainChatTask(analysisTasks, body, user.getId(), siteId, code, version)));
    }

    static Map<String, Object> domainChatTask(
            AiGenerationTasks tasks, TemplateDomainChatService.Request request,
            Integer owner, Long siteId, String code, String version) {
        var ref = request.taskRef();
        if (!Set.of("site", "page").contains(Objects.toString(ref.kind(), ""))
                || ref.id() == null || !ref.id().matches("[a-fA-F0-9-]{36}")) {
            throw new IllegalArgumentException("Agent 对话任务引用无效");
        }
        var task = tasks.require(ref.id(), owner);
        String inputKey = task.inputKey();
        boolean siteModel = inputKey.startsWith(SiteModelController.TASK_SCOPE);
        if (!Objects.equals(task.siteId, siteId)
                || task.usage != AiUsage.WORKBENCH_DOMAIN_AGENT
                || siteModel != "site".equals(ref.kind())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "当前范围内的 Agent 任务不存在");
        }
        var scope = CHAT_JSON.readTree(siteModel
                ? inputKey.substring(SiteModelController.TASK_SCOPE.length()) : inputKey);
        if (!scope.isArray() || !Objects.equals(scope.path(0).asText(), code)
                || !Objects.equals(scope.path(1).asText(), version)
                || !siteModel && (!scope.path(2).isTextual()
                        || !Objects.equals(scope.path(2).asText(), request.sourcePath()))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Agent 任务属于其他页面包或页面");
        }
        var snapshot = task.snapshot();
        var context = new LinkedHashMap<String, Object>();
        context.put("kind", ref.kind());
        context.put("state", snapshot.state());
        if (!siteModel) context.put("sourcePath", scope.path(2).asText());
        String progress = snapshot.progress();
        if (progress == null || progress.isBlank() || progress.length() > 32_000) return context;
        JsonNode data;
        try {
            data = CHAT_JSON.readTree(progress);
        } catch (tools.jackson.core.JacksonException invalid) {
            return context;
        }
        context.putAll(domainChatStep(data));
        for (String key : List.of("observedPages", "observedValues", "totalPages", "observedSources", "totalSources")) {
            copyProgressCount(context, data, key);
        }
        if (data.path("checkpointAvailable").isBoolean())
            context.put("checkpointAvailable", data.path("checkpointAvailable").asBoolean());
        var recent = new ArrayList<Map<String, Object>>();
        var steps = data.path("steps");
        if (steps.isArray()) {
            for (int i = Math.max(0, steps.size() - 5); i < steps.size(); i++) {
                var step = domainChatStep(steps.get(i));
                if (!step.isEmpty()) recent.add(step);
            }
        }
        if (!recent.isEmpty()) context.put("steps", recent);
        return context;
    }

    private static Map<String, Object> domainChatStep(JsonNode data) {
        var step = new LinkedHashMap<String, Object>();
        copyProgressCount(step, data, "round");
        for (String key : List.of("tool", "status", "message")) {
            if (data.path(key).isTextual()) {
                String value = data.path(key).asText();
                step.put(key, value.substring(0, Math.min(value.length(), 400)));
            }
        }
        return step;
    }

    private static void copyProgressCount(Map<String, Object> target, JsonNode data, String key) {
        if (data.path(key).isIntegralNumber() && data.path(key).canConvertToInt()
                && data.path(key).asInt() >= 0)
            target.put(key, data.path(key).asInt());
    }

    public record BindingPreview(
            String html,
            List<String> warnings,
            String digest,
            long revision,
            String packageVersion) {}

    @GetMapping("/binding-preview")
    public AdminApiResponse<BindingPreview> bindingPreview(
            HttpServletRequest request,
            @PathVariable Long siteId,
            @RequestParam String sourcePath,
            @RequestParam String digest,
            @RequestParam long revision) {
        var site = site(request, siteId);
        String code = site.getProjectCode();
        String version = site.getUiPackageVersion();
        var workspace = contentUnits.load(code, version, sourcePath);
        if (!workspace.digest().equals(digest) || workspace.revision() != revision)
            throw new IllegalArgumentException("内容单元已变化，请重新读取后预览");

        var selectors = new LinkedHashMap<String, String>();
        for (var source : workspace.sources()) {
            if (BINDING_PREVIEW_KINDS.contains(source.kind()))
                selectors.put(source.id(), source.selector());
        }
        var preview = packageInspection.preview(code, version, sourcePath, selectors, digest);

        var currentWorkspace = contentUnits.load(code, version, sourcePath);
        var currentSite = site(request, siteId);
        if (!Objects.equals(code, currentSite.getProjectCode())
                || !Objects.equals(version, currentSite.getUiPackageVersion())
                || !currentWorkspace.digest().equals(preview.digest())
                || currentWorkspace.revision() != workspace.revision())
            throw new IllegalArgumentException("站点模板或内容单元已变化，请重新读取后预览");
        return AdminApiResponse.success(
                new BindingPreview(
                        preview.html(), preview.warnings(), preview.digest(), revision, version));
    }

    @PostMapping("/content-units")
    public AdminApiResponse<com.xinhongru.cms.template.TemplateContentUnitsService.Workspace>
            saveContentUnits(
                    HttpServletRequest request,
                    @PathVariable Long siteId,
                    @RequestBody
                            com.xinhongru.cms.template.TemplateContentUnitsService.SaveRequest
                                    body) {
        var site = site(request, siteId);
        var user = TaleUtils.getLoginUser(request);
        if (user == null || user.getId() == null) throw new IllegalArgumentException("登录用户无效");
        String version = site.getUiPackageVersion();
        if (body != null
                && body.expectedPackageVersion() != null
                && !Objects.equals(version, body.expectedPackageVersion()))
            throw new IllegalArgumentException("页面包版本已变化，请重新读取后再保存");
        return AdminApiResponse.success(
                experienceEnabled
                        ? contentUnits.saveWithActor(
                                site.getProjectCode(), version, body, user.getId().longValue())
                        : contentUnits.save(site.getProjectCode(), version, body));
    }

    @GetMapping("/field-plans")
    public AdminApiResponse<com.xinhongru.cms.template.TemplateFieldPlanService.Recent>
            recentFieldPlans(
                    HttpServletRequest request,
                    @PathVariable Long siteId,
                    @RequestParam String sourcePath,
                    @RequestParam Long menuId) {
        var project = site(request, siteId);
        requireMenu(request, menuId, false);
        if (java.util.Objects.equals(project.getRootMenuId(), Math.toIntExact(menuId)))
            throw new IllegalArgumentException("站点根栏目不可作为绑定目标");
        return AdminApiResponse.success(
                fieldPlans.recent(
                        siteId,
                        project.getProjectCode(),
                        project.getUiPackageVersion(),
                        sourcePath,
                        menuId));
    }

    @PostMapping("/field-plans")
    public AdminApiResponse<com.xinhongru.cms.template.TemplateFieldPlanService.Plan>
            previewFieldPlan(
                    HttpServletRequest request,
                    @PathVariable Long siteId,
                    @RequestBody com.xinhongru.cms.template.TemplateFieldPlanService.Request body) {
        var site = site(request, siteId);
        if (body == null) throw new IllegalArgumentException("请提供字段计划来源");
        if (siteModelPlans != null)
            throw new IllegalArgumentException("新字段绑定请从已确认的整站模型生成；已有计划仍可读取和维护");
        requireMenu(request, body.menuId(), false);
        if (java.util.Objects.equals(site.getRootMenuId(), Math.toIntExact(body.menuId())))
            throw new IllegalArgumentException("站点根栏目不可作为绑定目标");
        return AdminApiResponse.success(
                fieldPlans.preview(
                        siteId, site.getProjectCode(), site.getUiPackageVersion(), body));
    }

    @GetMapping("/field-plans/{planId}")
    public AdminApiResponse<com.xinhongru.cms.template.TemplateFieldPlanService.Plan> readFieldPlan(
            HttpServletRequest request, @PathVariable Long siteId, @PathVariable String planId) {
        var site = site(request, siteId);
        var plan =
                fieldPlans.readForWithdrawal(siteId, site.getProjectCode(), site.getUiPackageVersion(), planId);
        requireMenu(request, plan.menuId(), false);
        return AdminApiResponse.success(plan);
    }

    @PostMapping("/field-plans/{planId}/preflight")
    public AdminApiResponse<com.xinhongru.cms.template.TemplateFieldPlanService.Preflight>
            preflightFieldPlan(
                    HttpServletRequest request,
                    @PathVariable Long siteId,
                    @PathVariable String planId) {
        var site = site(request, siteId);
        var plan =
                fieldPlans.read(siteId, site.getProjectCode(), site.getUiPackageVersion(), planId);
        requireMenu(request, plan.menuId(), false);
        return AdminApiResponse.success(withDerivedPlan(request, siteId, plan,
                () -> fieldPlans.preflight(siteId, site.getProjectCode(), site.getUiPackageVersion(), planId)));
    }

    public record ConfirmFieldPlanRequest(boolean acknowledgeReuse, boolean acknowledgeCreate) {}

    @PostMapping("/field-plans/{planId}/confirm")
    public AdminApiResponse<com.xinhongru.cms.template.TemplateFieldPlanService.Plan>
            confirmFieldPlan(
                    HttpServletRequest request,
                    @PathVariable Long siteId,
                    @PathVariable String planId,
                    @RequestBody ConfirmFieldPlanRequest body) {
        var site = site(request, siteId);
        var plan =
                fieldPlans.read(siteId, site.getProjectCode(), site.getUiPackageVersion(), planId);
        requireMenu(request, plan.menuId(), false);
        if (body == null) throw new IllegalArgumentException("请明确确认字段复用与新增影响");
        return AdminApiResponse.success(
                withDerivedPlan(
                        request,
                        siteId,
                        plan,
                        () ->
                                fieldPlans.confirm(
                                        siteId,
                                        plan.code(),
                                        plan.packageVersion(),
                                        new com.xinhongru.cms.template.TemplateFieldPlanService
                                                .Confirmation(
                                                planId,
                                                body.acknowledgeReuse(),
                                                body.acknowledgeCreate()))));
    }

    private <T> T withDerivedPlan(
            HttpServletRequest request,
            Long siteId,
            com.xinhongru.cms.template.TemplateFieldPlanService.Plan plan,
            Supplier<T> write) {
        return siteModelPlans == null
                ? write.get()
                : siteModelPlans.withDerivedPlan(
                        siteId,
                        plan.code(),
                        plan.packageVersion(),
                        plan.id(),
                        () -> {
                            var current = site(request, siteId);
                            if (!Objects.equals(plan.code(), current.getProjectCode())
                                    || !Objects.equals(
                                            plan.packageVersion(), current.getUiPackageVersion()))
                                throw new org.springframework.web.server.ResponseStatusException(
                                        org.springframework.http.HttpStatus.CONFLICT,
                                        "站点或页面包已变化，请重新读取字段计划");
                            if (packageFields != null) packageFields.assertCurrent(plan);
                            return write.get();
                        });
    }

    private com.xinhongru.cms.template.TemplateFieldPlanService.Plan textPlan(
            HttpServletRequest request, Long siteId, String planId, boolean writing) {
        return textPlan(request, siteId, planId, writing, false);
    }

    private com.xinhongru.cms.template.TemplateFieldPlanService.Plan textPlan(
            HttpServletRequest request, Long siteId, String planId, boolean writing, boolean archived) {
        var project = site(request, siteId);
        var plan =
                archived
                        ? fieldPlans.readForWithdrawal(
                                siteId, project.getProjectCode(), project.getUiPackageVersion(), planId)
                        : fieldPlans.read(
                                siteId, project.getProjectCode(), project.getUiPackageVersion(), planId);
        requireMenu(request, plan.menuId(), writing);
        if (java.util.Objects.equals(project.getRootMenuId(), Math.toIntExact(plan.menuId())))
            throw new IllegalArgumentException("站点根栏目不可作为绑定目标");
        return plan;
    }

    @GetMapping("/field-plans/{planId}/text-binding")
    public AdminApiResponse<com.xinhongru.cms.template.TextBindingDraftService.Result>
            inspectTextBinding(
                    HttpServletRequest request,
                    @PathVariable Long siteId,
                    @PathVariable String planId) {
        var plan = textPlan(request, siteId, planId, false);
        return AdminApiResponse.success(
                textDrafts.inspect(siteId, plan.code(), plan.packageVersion(), planId));
    }

    public record InitializeTextBindingRequest(boolean confirmed) {}

    @PostMapping("/field-plans/{planId}/text-binding/initialize")
    public AdminApiResponse<com.xinhongru.cms.template.TextBindingDraftService.Result>
            initializeTextBinding(
                    HttpServletRequest request,
                    @PathVariable Long siteId,
                    @PathVariable String planId,
                    @RequestBody InitializeTextBindingRequest body) {
        var plan = textPlan(request, siteId, planId, true);
        var user = TaleUtils.getLoginUser(request);
        if (user == null || user.getId() == null) throw new IllegalArgumentException("登录用户无效");
        return AdminApiResponse.success(
                withDerivedPlan(
                        request,
                        siteId,
                        plan,
                        () ->
                                textDrafts.initialize(
                                        siteId,
                                        plan.code(),
                                        plan.packageVersion(),
                                        planId,
                                        body != null && body.confirmed(),
                                        user.getId().longValue(),
                                        request)));
    }

    @GetMapping("/field-plans/{planId}/text-binding/existing-detail")
    public AdminApiResponse<
                    com.xinhongru.cms.template.TextBindingDraftService.ExistingDetailPreview>
            previewExistingDetail(
                    HttpServletRequest request,
                    @PathVariable Long siteId,
                    @PathVariable String planId,
                    @RequestParam Long recordId,
                    @RequestParam(defaultValue = "false") boolean preserveCurrent) {
        var plan = textPlan(request, siteId, planId, false);
        return AdminApiResponse.success(
                withDerivedPlan(
                        request,
                        siteId,
                        plan,
                        () ->
                                textDrafts.previewExistingDetail(
                                        siteId,
                                        plan.code(),
                                        plan.packageVersion(),
                                        planId,
                                        recordId,
                                        preserveCurrent)));
    }

    public record InitializeExistingDetailRequest(
            Long recordId, String previewDigest, boolean confirmed, boolean preserveCurrent) {
        public InitializeExistingDetailRequest(
                Long recordId, String previewDigest, boolean confirmed) {
            this(recordId, previewDigest, confirmed, false);
        }
    }

    @PostMapping("/field-plans/{planId}/text-binding/existing-detail")
    public AdminApiResponse<com.xinhongru.cms.template.TextBindingDraftService.Result>
            initializeExistingDetail(
                    HttpServletRequest request,
                    @PathVariable Long siteId,
                    @PathVariable String planId,
                    @RequestBody InitializeExistingDetailRequest body) {
        var plan = textPlan(request, siteId, planId, false);
        requireMenuUpdate(request, plan.menuId(), "无权更新栏目内容草稿");
        var user = TaleUtils.getLoginUser(request);
        if (user == null || user.getId() == null) throw new IllegalArgumentException("登录用户无效");
        if (body == null) throw new IllegalArgumentException("请选择并确认现有详情草稿");
        return AdminApiResponse.success(
                withDerivedPlan(
                        request,
                        siteId,
                        plan,
                        () ->
                                textDrafts.initializeExistingDetail(
                                        siteId,
                                        plan.code(),
                                        plan.packageVersion(),
                                        planId,
                                        body.recordId(),
                                        body.previewDigest(),
                                        body.confirmed(),
                                        user.getId().longValue(),
                                        body.preserveCurrent())));
    }

    public record PublicBindingRequest(
            Boolean confirmed,
            String packageVersion,
            java.util.List<com.xinhongru.cms.template.FormActionBinding.Selection> actions,
            java.util.List<String> collectionRegionIds,
            String neighborCandidateId,
            String routeUrl,
            String changeDigest,
            Boolean preview) {
        public PublicBindingRequest(boolean confirmed, String packageVersion,
                java.util.List<com.xinhongru.cms.template.FormActionBinding.Selection> actions,
                java.util.List<String> collectionRegionIds) {
            this(confirmed, packageVersion, actions, collectionRegionIds, null, null, null, false);
        }
    }

    @GetMapping("/field-plans/{planId}/text-binding/public-application/neighbors")
    public AdminApiResponse<com.xinhongru.cms.template.TextBindingPublicApplicationService.NeighborEvidence>
            publicBindingNeighbors(HttpServletRequest request, @PathVariable Long siteId,
                                   @PathVariable String planId) {
        var plan = textPlan(request, siteId, planId, false);
        return AdminApiResponse.success(
                publicBindings.neighborEvidence(siteId, plan.code(), plan.packageVersion(), planId));
    }

    @GetMapping("/field-plans/{planId}/text-binding/public-application/collections")
    public AdminApiResponse<
                    com.xinhongru.cms.template.TextBindingPublicApplicationService
                            .CollectionEvidence>
            publicBindingCollections(
                    HttpServletRequest request,
                    @PathVariable Long siteId,
                    @PathVariable String planId) {
        var plan = textPlan(request, siteId, planId, false);
        var evidence =
                publicBindings.collectionEvidence(
                        siteId, plan.code(), plan.packageVersion(), planId);
        var permission = permissions.snapshot(request);
        var visible =
                evidence.collections().stream()
                        .filter(
                                collection ->
                                        collection.targetMenuIds().stream()
                                                .allMatch(permission::hasMenuAccess))
                        .toList();
        return AdminApiResponse.success(
                new com.xinhongru.cms.template.TextBindingPublicApplicationService
                        .CollectionEvidence(
                        visible,
                        evidence.selectedCollectionRegionIds().stream()
                                .filter(
                                        id ->
                                                visible.stream()
                                                        .anyMatch(c -> c.regionId().equals(id)))
                                .toList()));
    }

    @GetMapping("/field-plans/{planId}/text-binding/public-application/actions")
    public AdminApiResponse<
                    com.xinhongru.cms.template.TextBindingPublicApplicationService.ActionEvidence>
            publicBindingActions(
                    HttpServletRequest request,
                    @PathVariable Long siteId,
                    @PathVariable String planId) {
        var plan = textPlan(request, siteId, planId, false);
        return AdminApiResponse.success(
                publicBindings.actionEvidence(siteId, plan.code(), plan.packageVersion(), planId));
    }

    @GetMapping("/field-plans/{planId}/text-binding/public-application")
    public AdminApiResponse<com.xinhongru.cms.template.TextBindingPublicApplicationService.Result>
            inspectPublicBinding(
                    HttpServletRequest request,
                    @PathVariable Long siteId,
                    @PathVariable String planId) {
        var plan = textPlan(request, siteId, planId, false, true);
        return AdminApiResponse.success(
                publicBindings.inspect(siteId, plan.code(), plan.packageVersion(), planId));
    }

    @PostMapping("/field-plans/{planId}/text-binding/public-application")
    public AdminApiResponse<com.xinhongru.cms.template.TextBindingPublicApplicationService.Result>
            applyPublicBinding(
                    HttpServletRequest request,
                    @PathVariable Long siteId,
                    @PathVariable String planId,
                    @RequestBody PublicBindingRequest body) {
        boolean preview = body != null && Boolean.TRUE.equals(body.preview());
        var plan = textPlan(request, siteId, planId, false, preview);
        requireMenuUpdate(request, plan.menuId(), "无权更新栏目公开模板");
        if (preview) {
            requireCollectionMenus(request, siteId, plan, body.collectionRegionIds());
            return AdminApiResponse.success(publicBindings.apply(
                    siteId, plan.code(), plan.packageVersion(), planId, false,
                    null, body.collectionRegionIds(), body.neighborCandidateId(), body.routeUrl(), null, true));
        }
        if (body != null && body.actions() != null) {
            for (var action : body.actions()) {
                if (action == null) throw new IllegalArgumentException("详情动作缺少目标");
                requireMenu(request, action.targetMenuId(), false);
            }
        }
        return AdminApiResponse.success(
                withDerivedPlan(
                        request,
                        siteId,
                        plan,
                        () -> {
                            if (body != null)
                                requireCollectionMenus(request, siteId, plan, body.collectionRegionIds());
                            return publicBindings.apply(
                                    siteId,
                                    plan.code(),
                                    plan.packageVersion(),
                                    planId,
                                    body != null && Boolean.TRUE.equals(body.confirmed()),
                                    body == null ? null : body.actions(),
                                    body == null ? null : body.collectionRegionIds(),
                                    body == null ? null : body.neighborCandidateId(),
                                    body == null ? null : body.routeUrl(),
                                    body == null ? null : body.changeDigest(),
                                    body != null && Boolean.TRUE.equals(body.preview()));
                        }));
    }

    private void requireCollectionMenus(HttpServletRequest request, Long siteId,
            com.xinhongru.cms.template.TemplateFieldPlanService.Plan plan, java.util.List<String> regionIds) {
        if (regionIds == null || regionIds.isEmpty()) return;
        var evidence = publicBindings.collectionEvidence(siteId, plan.code(), plan.packageVersion(), plan.id());
        for (var id : regionIds) {
            var choice = evidence.collections().stream().filter(c -> c.regionId().equals(id)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("集合未获当前页面审核"));
            for (Integer menuId : choice.targetMenuIds()) requireMenu(request, menuId.longValue(), false);
        }
    }

    @PostMapping("/field-plans/{planId}/text-binding/public-application/withdraw")
    public AdminApiResponse<com.xinhongru.cms.template.TextBindingPublicApplicationService.Result>
            withdrawPublicBinding(
                    HttpServletRequest request,
                    @PathVariable Long siteId,
                    @PathVariable String planId,
                    @RequestBody PublicBindingRequest body) {
        var project = site(request, siteId);
        // 撤回允许操作者明确指定原包版本；不重绑到当前包，也不放宽站点/栏目权限。
        String version =
                body != null && body.packageVersion() != null
                        ? body.packageVersion()
                        : project.getUiPackageVersion();
        var plan = fieldPlans.readForWithdrawal(siteId, project.getProjectCode(), version, planId);
        requireMenu(request, plan.menuId(), false);
        requireMenuUpdate(request, plan.menuId(), "无权更新栏目公开模板");
        if (java.util.Objects.equals(project.getRootMenuId(), Math.toIntExact(plan.menuId())))
            throw new IllegalArgumentException("站点根栏目不可作为绑定目标");
        return AdminApiResponse.success(
                publicBindings.withdraw(
                        siteId, plan.code(), version, planId, body != null && Boolean.TRUE.equals(body.confirmed())));
    }

    @GetMapping("/field-plans/{planId}/text-binding/recovery")
    public AdminApiResponse<com.xinhongru.cms.template.TextBindingDraftService.Recovery>
            recoveryState(
                    HttpServletRequest request,
                    @PathVariable Long siteId,
                    @PathVariable String planId) {
        var plan = textPlan(request, siteId, planId, false, true);
        return AdminApiResponse.success(
                textDrafts.recovery(siteId, plan.code(), plan.packageVersion(), planId));
    }

    @PostMapping("/field-plans/{planId}/text-binding/recover")
    public AdminApiResponse<com.xinhongru.cms.template.TextBindingDraftService.Result>
            recoverBinding(
                    HttpServletRequest request,
                    @PathVariable Long siteId,
                    @PathVariable String planId,
                    @RequestBody
                            com.xinhongru.cms.template.TextBindingDraftService.RecoverRequest
                                    body) {
        var plan = textPlan(request, siteId, planId, true);
        var user = TaleUtils.getLoginUser(request);
        if (user == null || user.getId() == null) throw new IllegalArgumentException("登录用户无效");
        return AdminApiResponse.success(
                withDerivedPlan(
                        request,
                        siteId,
                        plan,
                        () ->
                                textDrafts.recover(
                                        siteId,
                                        plan.code(),
                                        plan.packageVersion(),
                                        planId,
                                        body,
                                        user.getId().longValue(),
                                        request)));
    }

    @GetMapping("/field-plans/{planId}/text-binding/draft-preview")
    public AdminApiResponse<com.xinhongru.cms.template.TextBindingDraftService.Draft>
            previewTextBindingDraft(
                    HttpServletRequest request,
                    @PathVariable Long siteId,
                    @PathVariable String planId) {
        var plan = textPlan(request, siteId, planId, false);
        return AdminApiResponse.success(
                textDrafts.preview(siteId, plan.code(), plan.packageVersion(), planId));
    }

    @GetMapping("/fields")
    public AdminApiResponse<List<TemplateSeedImportService.Field>> fields(
            HttpServletRequest request, @PathVariable Long siteId, @RequestParam Long menuId) {
        var site = site(request, siteId);
        requireMenu(request, menuId, false);
        if (java.util.Objects.equals(site.getRootMenuId(), Math.toIntExact(menuId)))
            throw new IllegalArgumentException("不能导入站点根栏目");
        return AdminApiResponse.success(imports.fields(siteId, menuId));
    }

    @PostMapping("/import")
    public AdminApiResponse<TemplateSeedImportService.ImportResult> importDraft(
            HttpServletRequest request,
            @PathVariable Long siteId,
            @RequestBody TemplateSeedImportService.ImportRequest body) {
        var site = site(request, siteId);
        if (body == null) throw new IllegalArgumentException("请提供导入映射");
        requireMenu(request, body.menuId(), true);
        if (java.util.Objects.equals(site.getRootMenuId(), Math.toIntExact(body.menuId())))
            throw new IllegalArgumentException("不能导入站点根栏目");
        var user = TaleUtils.getLoginUser(request);
        if (user == null || user.getId() == null) throw new IllegalArgumentException("登录用户无效");
        return AdminApiResponse.success(
                imports.importDraft(
                        siteId,
                        site.getProjectCode(),
                        site.getUiPackageVersion(),
                        user.getId().longValue(),
                        body));
    }

    private void requireMenuUpdate(HttpServletRequest request, Long menuId, String denialMessage) {
        var permission = permissions.snapshot(request);
        int id = Math.toIntExact(menuId);
        if (!permission.hasMenuAccess(id)
                || !permission.canOperate(id, com.xinhongru.constant.WebConst.UPDATE_AUTHORITY_ID))
            throw com.xinhongru.security.AdminSecurityExceptionHandler.AdminSecurityException
                    .forbidden("CMS_OPERATION_DENIED", denialMessage);
    }

    private void requireMenu(HttpServletRequest request, Long menuId, boolean writing) {
        if (menuId == null || menuId <= 0) throw new IllegalArgumentException("请选择栏目");
        var permission = permissions.snapshot(request);
        int id = Math.toIntExact(menuId);
        if (!permission.hasMenuAccess(id) || (writing && !permission.canOperate(id, 1))) {
            throw com.xinhongru.security.AdminSecurityExceptionHandler.AdminSecurityException
                    .forbidden("CMS_OPERATION_DENIED", "无权管理栏目或新增内容");
        }
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(
            org.springframework.web.server.ResponseStatusException.class)
    public org.springframework.http.ResponseEntity<AdminApiResponse<Void>> statusFailure(
            org.springframework.web.server.ResponseStatusException failure) {
        String message = failure.getReason() == null ? "模板绑定请求未完成" : failure.getReason();
        return org.springframework.http.ResponseEntity.status(failure.getStatusCode())
                .body(new AdminApiResponse<>(failure.getStatusCode().value(), message, null));
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(IllegalArgumentException.class)
    public org.springframework.http.ResponseEntity<AdminApiResponse<Void>> invalidInput(
            IllegalArgumentException error) {
        return org.springframework.http.ResponseEntity.badRequest()
                .body(new AdminApiResponse<>(400, error.getMessage(), null));
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(
            com.xinhongru.site.context.SiteAccessDeniedException.class)
    public org.springframework.http.ResponseEntity<AdminApiResponse<Void>> accessDenied() {
        return org.springframework.http.ResponseEntity.status(403)
                .body(new AdminApiResponse<>(403, "只有开发管理员可以使用工作台", null));
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(IllegalStateException.class)
    public org.springframework.http.ResponseEntity<AdminApiResponse<Void>> unavailable(
            IllegalStateException error) {
        return org.springframework.http.ResponseEntity.status(503)
                .body(new AdminApiResponse<>(503, error.getMessage(), null));
    }

    public record AnalysisRequest(String sourcePath) {}

    public record AnalysisTaskRequest(String sourcePath, String requestId) {}

    @PostMapping("/analysis-tasks")
    public AdminApiResponse<com.xinhongru.ai.generation.AiGenerationTasks.Snapshot> startAnalysis(
            HttpServletRequest request,
            @PathVariable Long siteId,
            @RequestBody AnalysisTaskRequest body) {
        var project = site(request, siteId);
        var user = analysisUser(request);
        if (body == null || body.sourcePath() == null || body.sourcePath().isBlank())
            throw new IllegalArgumentException("请选择页面");
        var task =
                analysisTasks.create(
                        user.getId(),
                        siteId,
                        com.xinhongru.ai.generation.AiUsage.WORKBENCH_ANALYSIS,
                        body.requestId() == null
                                ? java.util.UUID.randomUUID().toString()
                                : body.requestId(),
                        project.getProjectCode()
                                + "\n"
                                + project.getUiPackageVersion()
                                + "\n"
                                + body.sourcePath());
        synchronized (task) {
            if (task.state().equals("pending")) {
                analysisTasks.start(
                        task,
                        reactor.core.publisher.Mono.fromCallable(
                                        () -> {
                                            try {
                                                return analysis.analyzeBatches(
                                                        siteId,
                                                        user.getId().longValue(),
                                                        project.getProjectCode(),
                                                        project.getUiPackageVersion(),
                                                        body.sourcePath(),
                                                        task);
                                            } catch (RuntimeException error) {
                                                if (task.terminal()) return null;
                                                throw error;
                                            }
                                        })
                                .map(
                                        value ->
                                                com.xinhongru.ai.generation.AiTaskEvent.delta(
                                                        new tools.jackson.databind.ObjectMapper()
                                                                .writeValueAsString(value)))
                                .flux());
            }
        }
        return AdminApiResponse.success(task.snapshot());
    }

    @GetMapping("/analysis-tasks/{taskId}")
    public AdminApiResponse<com.xinhongru.ai.generation.AiGenerationTasks.Snapshot> analysisTask(
            HttpServletRequest request, @PathVariable Long siteId, @PathVariable String taskId) {
        return AdminApiResponse.success(ownedAnalysis(request, siteId, taskId).snapshot());
    }

    @org.springframework.web.bind.annotation.DeleteMapping("/analysis-tasks/{taskId}")
    public AdminApiResponse<com.xinhongru.ai.generation.AiGenerationTasks.Snapshot> cancelAnalysis(
            HttpServletRequest request, @PathVariable Long siteId, @PathVariable String taskId) {
        var task = ownedAnalysis(request, siteId, taskId);
        analysisTasks.cancel(task);
        return AdminApiResponse.success(task.snapshot());
    }

    private com.xinhongru.entity.UserEntity analysisUser(HttpServletRequest request) {
        var user = TaleUtils.getLoginUser(request);
        if (user == null || user.getId() == null) throw new IllegalArgumentException("登录用户无效");
        return user;
    }

    private com.xinhongru.ai.generation.AiGenerationTasks.Task ownedAnalysis(
            HttpServletRequest request, Long siteId, String taskId) {
        site(request, siteId);
        var task = analysisTasks.require(taskId, analysisUser(request).getId());
        if (!java.util.Objects.equals(siteId, task.siteId)
                || task.usage != com.xinhongru.ai.generation.AiUsage.WORKBENCH_ANALYSIS)
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND, "页面分析任务不存在");
        return task;
    }

    @PostMapping("/analyze")
    public AdminApiResponse<com.xinhongru.cms.template.TemplateSeedAnalysisService.Analysis>
            analyze(
                    HttpServletRequest request,
                    @PathVariable Long siteId,
                    @RequestBody AnalysisRequest body) {
        var site = site(request, siteId);
        if (body == null || body.sourcePath() == null || body.sourcePath().isBlank()) {
            throw new IllegalArgumentException("请选择要分析的页面");
        }
        var user = TaleUtils.getLoginUser(request);
        if (user == null || user.getId() == null) throw new IllegalArgumentException("登录用户无效");
        return AdminApiResponse.success(
                analysis.analyze(
                        siteId,
                        user.getId().longValue(),
                        site.getProjectCode(),
                        site.getUiPackageVersion(),
                        body.sourcePath()));
    }
}
