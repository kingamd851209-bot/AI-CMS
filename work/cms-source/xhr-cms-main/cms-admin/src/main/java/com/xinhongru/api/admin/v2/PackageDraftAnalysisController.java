package com.xinhongru.api.admin.v2;

import com.xinhongru.api.admin.AdminApiResponse;
import com.xinhongru.cms.template.PackageDraftAnalysisService;
import com.xinhongru.cms.template.PackageDraftAnalysisService.Scope;
import com.xinhongru.cms.template.PackageFieldPlanService;
import com.xinhongru.cms.template.TemplateFieldPlanService;
import com.xinhongru.cms.template.TemplateDomainAgentService;
import com.xinhongru.permission.AdminPermissionService;
import com.xinhongru.site.context.SiteContextService;
import com.xinhongru.site.mapper.SiteProjectMapper;
import com.xinhongru.utils.TaleUtils;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Objects;

@RestController
@ConditionalOnProperty(name = "cms.workbench.enabled", havingValue = "true")
@RequestMapping("/api/admin/v2/sites/{siteId}/template-seed/package-analysis")
public class PackageDraftAnalysisController {
    private final SiteContextService sites;
    private final SiteProjectMapper projects;
    private final AdminPermissionService permissions;
    private final PackageDraftAnalysisService analysis;
    @org.springframework.beans.factory.annotation.Autowired private PackageFieldPlanService packageFields;

    public PackageDraftAnalysisController(
            SiteContextService sites,
            SiteProjectMapper projects,
            AdminPermissionService permissions,
            PackageDraftAnalysisService analysis) {
        this.sites = sites;
        this.projects = projects;
        this.permissions = permissions;
        this.analysis = analysis;
    }

    public record Run(
            String packageVersion,
            Long revision,
            String structurePlanId,
            Long structurePlanRevision,
            Boolean retrySkipped,
            Boolean attachModelDraft) {}

    public record Review(
            String packageVersion,
            Long revision,
            String sourcePath,
            String decision,
            Long reviewMenuId,
            String note,
            Long workspaceRevision,
            String workspaceDigest) {}

    public record Import(
            String packageVersion,
            Long revision,
            String structurePlanId,
            Long structurePlanRevision,
            List<PackageDraftAnalysisService.ImportPage> pages) {}

    private record Access(Scope scope, Integer rootMenuId, Runnable guard) {}

    public record Fields(
            String packageVersion,
            String sourcePath,
            Long fieldRevision,
            List<PackageDraftAnalysisService.FieldEdit> edits) {}

    public record Adopt(
            String packageVersion,
            String sourcePath,
            Long fieldRevision,
            List<Integer> resultIndexes) {}

    @GetMapping
    public AdminApiResponse<PackageDraftAnalysisService.State> get(
            HttpServletRequest request,
            @PathVariable long siteId,
            @RequestParam String packageVersion) {
        var access = access(request, siteId, packageVersion);
        var result = analysis.get(access.scope());
        access.guard().run();
        return AdminApiResponse.success(result);
    }

    @GetMapping("/page")
    public AdminApiResponse<PackageDraftAnalysisService.Detail> page(
            HttpServletRequest request,
            @PathVariable long siteId,
            @RequestParam String packageVersion,
            @RequestParam String sourcePath) {
        var access = access(request, siteId, packageVersion);
        var result = analysis.page(access.scope(), sourcePath);
        access.guard().run();
        return AdminApiResponse.success(result);
    }

    @PostMapping("/run")
    public AdminApiResponse<PackageDraftAnalysisService.State> run(
            HttpServletRequest request, @PathVariable long siteId, @RequestBody Run body) {
        var access = access(request, siteId, body.packageVersion());
        if (Boolean.TRUE.equals(body.attachModelDraft()))
            return AdminApiResponse.success(
                    analysis.attachModelDraft(
                            access.scope(),
                            revision(body.revision()),
                            body.structurePlanId(),
                            body.structurePlanRevision(),
                            access.guard()));
        return AdminApiResponse.success(
                analysis.start(
                        access.scope(),
                        revision(body.revision()),
                        body.structurePlanId(),
                        body.structurePlanRevision(),
                        Boolean.TRUE.equals(body.retrySkipped()),
                        access.guard()));
    }

    @PostMapping("/review")
    public AdminApiResponse<PackageDraftAnalysisService.State> review(
            HttpServletRequest request, @PathVariable long siteId, @RequestBody Review body) {
        var access = access(request, siteId, body.packageVersion());
        return AdminApiResponse.success(
                analysis.review(
                        access.scope(),
                        revision(body.revision()),
                        body.sourcePath(),
                        body.decision(),
                        body.reviewMenuId(),
                        body.note(),
                        body.workspaceRevision(),
                        body.workspaceDigest(),
                        access.guard()));
    }

    @PostMapping("/import")
    public AdminApiResponse<PackageDraftAnalysisService.State> importPages(
            HttpServletRequest request, @PathVariable long siteId, @RequestBody Import body) {
        var access = access(request, siteId, body.packageVersion());
        return AdminApiResponse.success(
                analysis.importPages(
                        access.scope(),
                        revision(body.revision()),
                        body.structurePlanId(),
                        body.structurePlanRevision(),
                        body.pages(),
                        access.guard()));
    }

    @PostMapping("/field-plan")
    public AdminApiResponse<TemplateFieldPlanService.Plan> createPackageFieldPlan(
            HttpServletRequest request, @PathVariable long siteId, @RequestBody PackageFieldPlanService.Request body) {
        var access = access(request, siteId, body.packageVersion());
        return AdminApiResponse.success(packageFields.preview(
                access.scope(), access.rootMenuId(), body, access.guard()));
    }

    @PostMapping("/fields")
    public AdminApiResponse<PackageDraftAnalysisService.Detail> fields(
            HttpServletRequest request, @PathVariable long siteId, @RequestBody Fields body) {
        var access = access(request, siteId, body.packageVersion());
        return AdminApiResponse.success(
                analysis.editFields(
                        access.scope(),
                        body.sourcePath(),
                        revision(body.fieldRevision()),
                        body.edits(),
                        access.guard()));
    }

    @PostMapping("/adopt")
    public AdminApiResponse<TemplateDomainAgentService.Result> adopt(
            HttpServletRequest request, @PathVariable long siteId, @RequestBody Adopt body) {
        var access = access(request, siteId, body.packageVersion());
        return AdminApiResponse.success(
                analysis.adoption(
                        access.scope(),
                        body.sourcePath(),
                        revision(body.fieldRevision()),
                        body.resultIndexes(),
                        access.guard()));
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<AdminApiResponse<Void>> scopedConflict(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode())
                .body(
                        new AdminApiResponse<>(
                                error.getStatusCode().value(), error.getReason(), null));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<AdminApiResponse<Void>> invalid(IllegalArgumentException error) {
        return ResponseEntity.badRequest()
                .body(new AdminApiResponse<>(400, error.getMessage(), null));
    }

    private static long revision(Long value) {
        if (value == null || value < 0) throw new IllegalArgumentException("整包草稿修订无效");
        return value;
    }

    private Access access(HttpServletRequest request, long siteId, String version) {
        sites.requireSuperAdmin(request);
        sites.assertCanAccessSite(request, siteId);
        var actor = TaleUtils.getLoginUser(request);
        if (actor == null || actor.getId() == null)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "请重新登录");
        var project = projects.selectById(siteId);
        if (project == null) throw new IllegalArgumentException("站点不存在");
        var code = project.getProjectCode();
        int owner = actor.getId();
        Integer rootMenuId = project.getRootMenuId();
        Runnable guard =
                () -> {
                    if (!permissions.snapshotForActor(owner, siteId).isAll())
                        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权查看或分析此站点页面包");
                    var current = projects.selectById(siteId);
                    if (current == null
                            || Integer.valueOf(1).equals(current.getDeleted())
                    || rootMenuId == null || !Objects.equals(rootMenuId, current.getRootMenuId())
                            || code == null
                            || version == null
                            || version.isBlank()
                            || !Objects.equals(code, current.getProjectCode())
                            || !Objects.equals(version, current.getUiPackageVersion()))
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "站点或页面包版本已变化，请重新读取");
                };
        guard.run();
        return new Access(new Scope(siteId, code, version), rootMenuId, guard);
    }
}
