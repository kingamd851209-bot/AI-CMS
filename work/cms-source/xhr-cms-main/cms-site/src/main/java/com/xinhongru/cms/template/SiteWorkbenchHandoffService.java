package com.xinhongru.cms.template;

import com.xinhongru.entity.OneshotEntity;
import com.xinhongru.mapper.OneShotMapper;
import com.xinhongru.site.entity.SiteProjectEntity;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** 交接摘要基于真实栏目指针和明确计划版本，不把最近草案当作活动发布。 */
@Service
public class SiteWorkbenchHandoffService {
    static final Pattern FORM_TEMPLATE =
            Pattern.compile(
                    "cms-form-(?:native|prefill)/([1-9][0-9]{0,9})/([a-f0-9]{64})/"
                        + "[A-Za-z][A-Za-z0-9_]{0,62}--form-([1-9][0-9]{0,9})-([a-f0-9]{64})[.]html");
    private final SiteWorkbenchContextService workspace;
    private final SiteModelPlanService plans;
    private final OneShotMapper menus;
    private final TemplateStorageService templates;
    private final NativeFormImportService nativeForms;
    private final Path applications;
    private final ObjectMapper json = new ObjectMapper();

    public SiteWorkbenchHandoffService(
            SiteWorkbenchContextService workspace,
            SiteModelPlanService plans,
            OneShotMapper menus,
            TemplateStorageService templates,
            NativeFormImportService nativeForms,
            @Value(
                            "${cms.ui-package.root:${user.dir}/runtime/ui-packages}/.workbench-content-units")
                    String root) {
        this.workspace = workspace;
        this.plans = plans;
        this.menus = menus;
        this.templates = templates;
        this.nativeForms = nativeForms;
        this.applications = Path.of(root).toAbsolutePath().normalize();
    }

    public record PlanReference(String id, long revision, String status) {}

    public record Application(
            Integer menuId,
            String menuName,
            String slot,
            String templatePath,
            String route,
            String planId,
            String packageVersion,
            String sourcePath,
            String state,
            boolean hasFrontChannel,
            Integer singlePageStatus) {}

    public record NextAction(int step, String sourcePath, Integer menuId) {}

    public record Handoff(
            Long siteId,
            String packageVersion,
            List<PlanReference> plans,
            List<Application> applications,
            SiteWorkbenchContextService.Document agents,
            SiteWorkbenchContextService.Document progress,
            String markdown,
            NextAction nextAction) {}

    public Handoff read(SiteProjectEntity site, boolean export) {
        var agents = workspace.agents(site.getId(), site.getProjectCode());
        var progress = workspace.progress(site.getId(), site.getProjectCode());
        boolean hasPackage =
                site.getUiPackageVersion() != null && !site.getUiPackageVersion().isBlank();
        var references =
                (hasPackage
                                ? plans.index(
                                        site.getId(),
                                        site.getProjectCode(),
                                        site.getUiPackageVersion())
                                : List.<SiteModelPlanService.Plan>of())
                        .stream()
                                .map(
                                        plan ->
                                                new PlanReference(
                                                        plan.id(), plan.revision(), plan.status()))
                                .toList();
        var active = activeApplications(site);
        var invalid =
                active.stream().filter(item -> !"TEMPLATE_VERIFIED".equals(item.state())).toList();
        NextAction next =
                !hasPackage
                        ? new NextAction(0, null, null)
                        : invalid.size() == 1 && invalid.getFirst().sourcePath() != null
                                ? new NextAction(
                                        4,
                                        invalid.getFirst().sourcePath(),
                                        invalid.getFirst().menuId())
                                : null;
        String markdown = markdown(site, references, active, agents, next);
        if (export)
            progress =
                    workspace.exportProgress(
                            site.getId(), site.getProjectCode(), markdown, progress.digest());
        return new Handoff(
                site.getId(),
                Objects.toString(site.getUiPackageVersion(), ""),
                references,
                active,
                agents,
                progress,
                markdown,
                next);
    }

    List<Application> activeApplications(SiteProjectEntity site) {
        var active = new ArrayList<Application>();
        for (var menu : menus.selectListBySiteProjectId(site.getId())) {
            if (!Objects.equals(menu.getSiteProjectId(), site.getId())
                    || Boolean.TRUE.equals(menu.getIsDeleted())) continue;
            if (menu.getListPageTemplate() != null && !menu.getListPageTemplate().isBlank())
                active.add(
                        application(
                                site,
                                menu,
                                "LIST",
                                menu.getListPageTemplate(),
                                menu.getListPageUrl()));
            if (menu.getDetailPageTemplate() != null && !menu.getDetailPageTemplate().isBlank())
                active.add(
                        application(
                                site,
                                menu,
                                "DETAIL",
                                menu.getDetailPageTemplate(),
                                menu.getDetailPageUrl()));
        }
        return List.copyOf(active);
    }

    boolean verifiedHomeSourceAlias(SiteProjectEntity site, Application home, Application candidate) {
        if (home == null || !Objects.equals(home.menuId(), site.getRootMenuId()) || home.menuId().equals(candidate.menuId())
                || !"TEMPLATE_VERIFIED".equals(home.state()) || !"TEMPLATE_VERIFIED".equals(candidate.state())
                || !"LIST".equals(home.slot()) || !"LIST".equals(candidate.slot()) || !candidate.hasFrontChannel()
                || !Integer.valueOf(3).equals(candidate.singlePageStatus())
                || !Objects.equals(home.packageVersion(), site.getUiPackageVersion())
                || !Objects.equals(home.packageVersion(), candidate.packageVersion())
                || !Objects.equals(home.planId(), candidate.planId()) || !Objects.equals(home.sourcePath(), candidate.sourcePath())
                || !PublicBindingApplication.managed(home.templatePath()) || !PublicBindingApplication.managed(candidate.templatePath())) return false;
        var root = PublicBindingApplication.atPath(applications, site.getProjectCode(), home.templatePath());
        var source = PublicBindingApplication.atPath(applications, site.getProjectCode(), candidate.templatePath());
        return root != null && source != null && source.routeMenuId()==null
                && Objects.equals(root.routeMenuId(), home.menuId().longValue())
                && Objects.equals(root.menuId(), candidate.menuId().longValue()) && root.menuId().equals(source.menuId())
                && Objects.equals(root.templateDigest(), source.templateDigest());
    }

    boolean hasPublishedSinglePage(SiteProjectEntity site, OneshotEntity menu, Application app) {
        if (!Objects.equals(menu.getId(), site.getRootMenuId()) || !PublicBindingApplication.managed(app.templatePath()))
            return Integer.valueOf(3).equals(menu.getStatus());
        var receipt = PublicBindingApplication.atPath(applications, site.getProjectCode(), app.templatePath());
        if (receipt == null || receipt.routeMenuId() == null) return Integer.valueOf(3).equals(menu.getStatus());
        if (!site.getId().equals(receipt.siteId()) || receipt.routeMenuId().longValue() != menu.getId().longValue()
                || !Objects.equals(receipt.packageVersion(), site.getUiPackageVersion())) return false;
        var owner = menus.selectPublicBindingMenu(site.getId(), receipt.menuId());
        return owner != null && Objects.equals(owner.getSiteRootId(), site.getRootMenuId())
                && !Boolean.TRUE.equals(owner.getIsDeleted()) && Boolean.TRUE.equals(owner.getHasFrontChannel())
                && Integer.valueOf(0).equals(owner.getPageType()) && Integer.valueOf(3).equals(owner.getStatus());
    }

    private Application application(
            SiteProjectEntity site, OneshotEntity menu, String slot, String path, String route) {
        var form = FORM_TEMPLATE.matcher(path);
        if ("LIST".equals(slot) && form.matches())
            return nativeApplication(site, menu, path, route, form);
        String plan = null, version = null, source = null;
        String state = "UNMANAGED_TEMPLATE";
        if (PublicBindingApplication.managed(path)) {
            var receipt =
                    applications
                            .resolve(site.getProjectCode())
                            .resolve("public-applications")
                            .resolve(PublicBindingApplication.keyFrom(path) + ".json");
            try {
                safe(receipt, applications);
                if (!Files.isRegularFile(receipt, LinkOption.NOFOLLOW_LINKS)
                        || Files.size(receipt) > 1024 * 1024) state = "RECEIPT_MISSING";
                else {
                    var stored =
                            PublicBindingApplication.read(
                                    applications,
                                    site.getProjectCode(),
                                    PublicBindingApplication.keyFrom(path));
                    JsonNode value = json.valueToTree(stored);
                    plan = text(value, "planId");
                    version = text(value, "packageVersion");
                    if (value.path("navigationLanding").isObject())
                        source =
                                text(value.path("navigationLanding").path("preview"), "sourcePath");
                    else if (value.path("homeBanner").isObject())
                        source = text(value.path("homeBanner"), "sourcePath");
                    else source = referencedSource(site, value);
                    Long routeOwner =
                            value.path("routeMenuId").isIntegralNumber()
                                    ? value.path("routeMenuId").asLong()
                                    : value.path("menuId").asLong();
                    if (value.path("siteId").asLong() != site.getId()
                            || !Objects.equals(routeOwner, menu.getId().longValue())
                            || !path.equals(text(value, "templatePath"))
                            || !Objects.equals(route, text(value, "routeUrl")))
                        state = "RECEIPT_MISMATCH";
                    else if (!Objects.equals(version, site.getUiPackageVersion()))
                        state = "PACKAGE_CHANGED";
                    else {
                        var directory =
                                templates
                                        .resolveTemplateDir(site.getProjectCode())
                                        .toAbsolutePath()
                                        .normalize();
                        var template = directory.resolve(path).normalize();
                        safe(template, directory);
                        state =
                                Files.isRegularFile(template, LinkOption.NOFOLLOW_LINKS)
                                                && Files.size(template) <= 2 * 1024 * 1024
                                                && PublicBindingApplication.digest(
                                                                Files.readString(template))
                                                        .equals(text(value, "templateDigest"))
                                        ? "TEMPLATE_VERIFIED"
                                        : "TEMPLATE_CHANGED";
                    }
                }
            } catch (IllegalArgumentException | tools.jackson.core.JacksonException error) {
                state = "RECEIPT_INVALID";
            } catch (IOException error) {
                throw new IllegalStateException("站点交接凭据无法读取", error);
            }
        }
        return new Application(
                menu.getId(),
                menu.getClassName(),
                slot,
                path,
                route,
                plan,
                version,
                source,
                state,
                Boolean.TRUE.equals(menu.getHasFrontChannel()),
                Objects.equals(menu.getPageType(), 0) ? menu.getStatus() : null);
    }

    private Application nativeApplication(
            SiteProjectEntity site,
            OneshotEntity menu,
            String path,
            String route,
            java.util.regex.Matcher form) {
        String source = null;
        String state = "RECEIPT_MISMATCH";
        try {
            var version = site.getUiPackageVersion();
            var receipt =
                    nativeForms.read(
                            site.getId(),
                            site.getProjectCode(),
                            version,
                            Integer.valueOf(form.group(3)));
            if (Objects.equals(receipt.menuId(), menu.getId().longValue())
                    && form.group(1).equals(menu.getId().toString())
                    && form.group(2).equals(receipt.sourceDigest())) {
                source = receipt.sourcePath();
                FormActionBinding.publicRoute(menu, site.getId());
                var root =
                        templates
                                .resolveTemplateDir(site.getProjectCode())
                                .toAbsolutePath()
                                .normalize();
                var file = root.resolve(path).normalize();
                safe(file, root);
                state =
                        Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                                        && Files.size(file) <= 2 * 1024 * 1024
                                        && form.group(4)
                                                .equals(
                                                        PublicBindingApplication.digest(
                                                                Files.readString(file)))
                                ? "TEMPLATE_VERIFIED"
                                : "TEMPLATE_CHANGED";
            }
        } catch (IllegalArgumentException | tools.jackson.core.JacksonException invalid) {
            state = "RECEIPT_INVALID";
        } catch (IOException error) {
            throw new IllegalStateException("原生表单模板无法读取", error);
        }
        return new Application(
                menu.getId(),
                menu.getClassName(),
                "LIST",
                path,
                route,
                null,
                site.getUiPackageVersion(),
                source,
                state,
                Boolean.TRUE.equals(menu.getHasFrontChannel()),
                menu.getStatus());
    }

    private String referencedSource(SiteProjectEntity site, JsonNode application)
            throws IOException {
        String query = text(application, "queryId");
        String id = query == null ? text(application, "planId") : query;
        if (id == null || !id.matches("[a-f0-9-]{36}")) return null;
        var path =
                applications
                        .resolve(site.getProjectCode())
                        .resolve(query == null ? "field-plans" : "query-bindings")
                        .resolve(id + ".json");
        safe(path, applications);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 1024 * 1024)
            return null;
        var source = json.readTree(Files.readString(path));
        if (source.path("siteId").asLong() != site.getId()
                || !Objects.equals(site.getProjectCode(), text(source, "code"))
                || !Objects.equals(
                        text(application, "packageVersion"), text(source, "packageVersion"))
                || !Objects.equals(id, text(source, "id"))) return null;
        return text(source, "sourcePath");
    }

    private static String text(JsonNode value, String name) {
        return value.path(name).isTextual() ? value.path(name).asText() : null;
    }

    private static void safe(Path path, Path root) {
        if (!path.normalize().startsWith(root)) throw new IllegalArgumentException("交接凭据路径越界");
        for (Path cursor = path; cursor != null; cursor = cursor.getParent())
            if (Files.isSymbolicLink(cursor)) throw new IllegalArgumentException("交接凭据路径含符号链接");
    }

    private static String markdown(
            SiteProjectEntity site,
            List<PlanReference> plans,
            List<Application> active,
            SiteWorkbenchContextService.Document agents,
            NextAction next) {
        var text =
                new StringBuilder("# 建站进度\n\n站点 ")
                        .append(site.getId())
                        .append("，项目 ")
                        .append(clean(site.getProjectCode()))
                        .append("，当前页面包 ")
                        .append(clean(site.getUiPackageVersion()))
                        .append("。\n\n站点约定文件 ")
                        .append(agents.present() ? "已保存" : "尚未保存")
                        .append("，摘要 ")
                        .append(agents.digest())
                        .append("。\n\n## 已保存计划\n\n")
                        .append("以下为各计划自身的明确修订，活动发布以随后栏目指针为准。\n\n");
        for (var plan : plans)
            text.append("- ")
                    .append(clean(plan.id()))
                    .append("，修订 ")
                    .append(plan.revision())
                    .append("，")
                    .append(clean(plan.status()))
                    .append("\n");
        text.append("\n## 当前模板指针\n\n");
        for (var item : active)
            text.append("- 栏目 ")
                    .append(item.menuId())
                    .append(" ")
                    .append(clean(item.menuName()))
                    .append("，")
                    .append(item.slot())
                    .append("，状态 ")
                    .append(item.state())
                    .append("，路由 ")
                    .append(clean(item.route()))
                    .append("，来源 ")
                    .append(clean(item.sourcePath()))
                    .append("，回执计划 ")
                    .append(clean(item.planId()))
                    .append("，回执包 ")
                    .append(clean(item.packageVersion()))
                    .append("，模板 ")
                    .append(clean(item.templatePath()))
                    .append("\n");
        text.append("\n## 后续核对\n\n模板摘要验证只覆盖当前指针和文件。正式内容发布状态、静态任务产物和浏览器交互仍需分别读取对应证据。\n\n");
        text.append(
                next == null
                        ? "当前事实不能唯一确定继续位置，保留工作台当前页面，再选择待核对来源。\n"
                        : next.step() == 0
                                ? "尚未配置页面包，请进入项目与页面包步骤。\n"
                                : "当前唯一异常模板对应栏目 "
                                        + next.menuId()
                                        + "，建议进入预览与公开步骤核对 "
                                        + clean(next.sourcePath())
                                        + "。\n");
        return text.toString();
    }

    private static String clean(String value) {
        return value == null
                ? "未记录"
                : value.replace('\n', ' ')
                        .replace('\r', ' ')
                        .replace("<", "&lt;")
                        .replace(">", "&gt;")
                        .replace("`", "\\`");
    }
}
