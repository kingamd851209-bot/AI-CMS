package com.xinhongru.cms.template;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** 本票受控模板凭据；仅保存映射与原指针，不保存CMS业务值。 */
record PublicBindingApplication(
        String key,
        Long siteId,
        Long menuId,
        String planId,
        String packageVersion,
        int pageType,
        String fieldConfigDigest,
        List<TemplateFieldPlanService.Binding> bindings,
        String templatePath,
        String templateDigest,
        String previousTemplate,
        String routeUrl,
        Long initializedRecordId,
        Long routeMenuId,
        List<String> routeAliases,
        String queryId,
        List<PublicSharedContentCompiler.Reference> sharedContent,
        List<DetailFormActions.Binding> detailActions,
        List<DetailCollections.Binding> detailCollections,
        HomeBannerBinding.Receipt homeBanner,
        DetailNeighbors.Binding detailNeighbors,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        NavigationLandingApplicationService.Receipt navigationLanding,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        String previousActiveTemplate) {
    PublicBindingApplication {
        routeAliases = routeAliases == null ? List.of() : List.copyOf(routeAliases);
        sharedContent = sharedContent == null ? List.of() : List.copyOf(sharedContent);
        detailActions = detailActions == null ? List.of() : List.copyOf(detailActions);
        detailCollections = detailCollections == null ? List.of() : List.copyOf(detailCollections);
    }

    PublicBindingApplication(
            String key, Long siteId, Long menuId, String planId, String packageVersion, int pageType,
            String fieldConfigDigest, List<TemplateFieldPlanService.Binding> bindings,
            String templatePath, String templateDigest, String previousTemplate, String routeUrl,
            Long initializedRecordId, Long routeMenuId, List<String> routeAliases, String queryId,
            List<PublicSharedContentCompiler.Reference> sharedContent,
            List<DetailFormActions.Binding> detailActions,
            List<DetailCollections.Binding> detailCollections, HomeBannerBinding.Receipt homeBanner,
            DetailNeighbors.Binding detailNeighbors,
            NavigationLandingApplicationService.Receipt navigationLanding) {
        this(key, siteId, menuId, planId, packageVersion, pageType, fieldConfigDigest, bindings,
                templatePath, templateDigest, previousTemplate, routeUrl, initializedRecordId,
                routeMenuId, routeAliases, queryId, sharedContent, detailActions, detailCollections,
                homeBanner, detailNeighbors, navigationLanding, null);
    }

    PublicBindingApplication(
            String key, Long siteId, Long menuId, String planId, String packageVersion, int pageType,
            String fieldConfigDigest, List<TemplateFieldPlanService.Binding> bindings,
            String templatePath, String templateDigest, String previousTemplate, String routeUrl,
            Long initializedRecordId, Long routeMenuId, List<String> routeAliases, String queryId,
            List<PublicSharedContentCompiler.Reference> sharedContent,
            List<DetailFormActions.Binding> detailActions, List<DetailCollections.Binding> detailCollections,
            HomeBannerBinding.Receipt homeBanner, DetailNeighbors.Binding detailNeighbors) {
        this(key, siteId, menuId, planId, packageVersion, pageType, fieldConfigDigest, bindings,
                templatePath, templateDigest, previousTemplate, routeUrl, initializedRecordId, routeMenuId,
                routeAliases, queryId, sharedContent, detailActions, detailCollections, homeBanner, detailNeighbors, null);
    }

    PublicBindingApplication(
            String key, Long siteId, Long menuId, String planId, String packageVersion, int pageType,
            String fieldConfigDigest, List<TemplateFieldPlanService.Binding> bindings,
            String templatePath, String templateDigest, String previousTemplate, String routeUrl,
            Long initializedRecordId, Long routeMenuId, List<String> routeAliases, String queryId,
            List<PublicSharedContentCompiler.Reference> sharedContent,
            List<DetailFormActions.Binding> detailActions, List<DetailCollections.Binding> detailCollections,
            HomeBannerBinding.Receipt homeBanner) {
        this(key, siteId, menuId, planId, packageVersion, pageType, fieldConfigDigest, bindings,
                templatePath, templateDigest, previousTemplate, routeUrl, initializedRecordId,
                routeMenuId, routeAliases, queryId, sharedContent, detailActions, detailCollections,
                homeBanner, null);
    }

    PublicBindingApplication(
            String key, Long siteId, Long menuId, String planId, String packageVersion, int pageType,
            String fieldConfigDigest, List<TemplateFieldPlanService.Binding> bindings,
            String templatePath, String templateDigest, String previousTemplate, String routeUrl,
            Long initializedRecordId, Long routeMenuId, List<String> routeAliases, String queryId,
            List<PublicSharedContentCompiler.Reference> sharedContent,
            List<DetailFormActions.Binding> detailActions, List<DetailCollections.Binding> detailCollections) {
        this(key, siteId, menuId, planId, packageVersion, pageType, fieldConfigDigest, bindings,
                templatePath, templateDigest, previousTemplate, routeUrl, initializedRecordId,
                routeMenuId, routeAliases, queryId, sharedContent, detailActions, detailCollections, null);
    }

    PublicBindingApplication(
            String key, Long siteId, Long menuId, String planId, String packageVersion, int pageType,
            String fieldConfigDigest, List<TemplateFieldPlanService.Binding> bindings,
            String templatePath, String templateDigest, String previousTemplate, String routeUrl,
            Long initializedRecordId, Long routeMenuId, List<String> routeAliases, String queryId,
            List<PublicSharedContentCompiler.Reference> sharedContent,
            List<DetailFormActions.Binding> detailActions) {
        this(key, siteId, menuId, planId, packageVersion, pageType, fieldConfigDigest, bindings,
                templatePath, templateDigest, previousTemplate, routeUrl, initializedRecordId,
                routeMenuId, routeAliases, queryId, sharedContent, detailActions, List.of());
    }

    PublicBindingApplication(
            String key,
            Long siteId,
            Long menuId,
            String planId,
            String packageVersion,
            int pageType,
            String fieldConfigDigest,
            List<TemplateFieldPlanService.Binding> bindings,
            String templatePath,
            String templateDigest,
            String previousTemplate,
            String routeUrl,
            Long initializedRecordId,
            Long routeMenuId,
            List<String> routeAliases,
            String queryId,
            List<PublicSharedContentCompiler.Reference> sharedContent) {
        this(
                key,
                siteId,
                menuId,
                planId,
                packageVersion,
                pageType,
                fieldConfigDigest,
                bindings,
                templatePath,
                templateDigest,
                previousTemplate,
                routeUrl,
                initializedRecordId,
                routeMenuId,
                routeAliases,
                queryId,
                sharedContent,
                List.of());
    }

    PublicBindingApplication(
            String key, Long siteId, Long menuId, String planId, String packageVersion,
            int pageType, String fieldConfigDigest, List<TemplateFieldPlanService.Binding> bindings,
            String templatePath, String templateDigest, String previousTemplate, String routeUrl,
            Long initializedRecordId, Long routeMenuId, List<String> routeAliases, String queryId) {
        this(key, siteId, menuId, planId, packageVersion, pageType, fieldConfigDigest, bindings,
                templatePath, templateDigest, previousTemplate, routeUrl, initializedRecordId,
                routeMenuId, routeAliases, queryId, List.of());
    }

    /** 根路由旧凭据仍不含列表查询身份。 */
    PublicBindingApplication(
            String key,
            Long siteId,
            Long menuId,
            String planId,
            String packageVersion,
            int pageType,
            String fieldConfigDigest,
            List<TemplateFieldPlanService.Binding> bindings,
            String templatePath,
            String templateDigest,
            String previousTemplate,
            String routeUrl,
            Long initializedRecordId,
            Long routeMenuId,
            List<String> routeAliases) {
        this(
                key,
                siteId,
                menuId,
                planId,
                packageVersion,
                pageType,
                fieldConfigDigest,
                bindings,
                templatePath,
                templateDigest,
                previousTemplate,
                routeUrl,
                initializedRecordId,
                routeMenuId,
                routeAliases,
                null);
    }

    /** 保留普通单页/详情凭据及旧调用方；旧JSON的nullable路由身份不改变owner语义。 */
    PublicBindingApplication(
            String key,
            Long siteId,
            Long menuId,
            String planId,
            String packageVersion,
            int pageType,
            String fieldConfigDigest,
            List<TemplateFieldPlanService.Binding> bindings,
            String templatePath,
            String templateDigest,
            String previousTemplate,
            String routeUrl,
            Long initializedRecordId) {
        this(
                key,
                siteId,
                menuId,
                planId,
                packageVersion,
                pageType,
                fieldConfigDigest,
                bindings,
                templatePath,
                templateDigest,
                previousTemplate,
                routeUrl,
                initializedRecordId,
                null,
                List.of());
    }

    private static final tools.jackson.databind.ObjectMapper JSON =
            new tools.jackson.databind.ObjectMapper();
    private static final String UUID =
            "[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}";

    static boolean managed(String path) {
        return path != null && path.startsWith("cms-applied/");
    }

    static String keyFrom(String path) {
        if (path == null || !path.matches("cms-applied/" + UUID + "/[a-f0-9]{64}\\.html"))
            throw new IllegalArgumentException("受控公开模板路径无效");
        return path.split("/")[1];
    }

    static PublicBindingApplication atPath(Path root, String code, String templatePath) {
        if (!managed(templatePath)) return null;
        var application = read(root, code, keyFrom(templatePath));
        if (application == null || !Objects.equals(application.templatePath(), templatePath))
            throw new IllegalArgumentException("当前受控模板缺少匹配的公开凭据");
        return application;
    }

    PublicBindingApplication previous(Path root, String code) {
        var previous = atPath(root, code, previousTemplate);
        if (previous != null && !sameOwner(previous))
            throw new IllegalArgumentException("前一应用与当前栏目不一致");
        return previous;
    }

    PublicBindingApplication initial(Path root, String code) {
        var receipt = this;
        var visited = new HashSet<String>();
        while (managed(receipt.previousTemplate())) {
            if (!visited.add(receipt.templatePath()) || visited.size() > 100)
                throw new IllegalArgumentException("公开应用历史无法安全核对");
            receipt = receipt.previous(root, code);
        }
        return receipt;
    }

    private boolean sameOwner(PublicBindingApplication other) {
        return Objects.equals(siteId, other.siteId)
                && Objects.equals(menuId, other.menuId)
                && Objects.equals(packageVersion, other.packageVersion)
                && pageType == other.pageType;
    }

    static String digest(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            java.security.MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    static PublicBindingApplication read(Path root, String code, String key) {
        var file = file(root, code, key);
        try {
            if (!Files.exists(file)) return null;
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(file) > 1024 * 1024)
                throw new IllegalArgumentException("公开应用凭据无效或超限");
            var result = JSON.readValue(Files.readString(file), PublicBindingApplication.class);
            if (result == null
                    || !key.equals(result.key())
                    || !key.equals(keyFrom(result.templatePath()))
                    || result.bindings() == null
                    || result.bindings().isEmpty() && result.queryId() == null && result.homeBanner() == null
                            && result.navigationLanding() == null
                    || result.navigationLanding() != null
                            && (result.pageType() != 1 || result.queryId() != null || result.homeBanner() != null
                                    || result.routeMenuId() != null || result.initializedRecordId() != null
                                    || !result.bindings().isEmpty() || !result.detailActions().isEmpty()
                                    || !result.detailCollections().isEmpty() || result.detailNeighbors() != null
                                    || result.navigationLanding().preview() == null
                                    || result.navigationLanding().preview().menuId() == null
                                    || !Objects.equals(result.planId(), result.navigationLanding().planId())
                                    || !Objects.equals(result.menuId(),
                                            result.navigationLanding().preview().menuId().longValue()))
                    || result.homeBanner() != null
                            && (result.routeMenuId() == null || result.pageType() != 1
                                    || result.queryId() != null || !result.bindings().isEmpty()
                                    || result.initializedRecordId() != null)
                    || result.queryId() != null
                            && (result.pageType() != 1
                                    || result.routeMenuId() != null
                                    || !result.queryId().matches(UUID))
                    || result.bindings().size() > 200
                    || !result.detailActions().isEmpty()
                            && (result.pageType() != 1 || result.queryId() != null
                                    || result.routeMenuId() != null || result.detailActions().size() > 12)
                    || !result.detailCollections().isEmpty()
                            && (result.queryId() != null || result.detailCollections().size() > 12
                                    || result.pageType()==0 && result.detailCollections().stream().anyMatch(b -> !Set.of("INLINE_REFERENCE", "PAGE_LIST").contains(b.mode()))
                                    || result.pageType()==1 && (result.routeMenuId()!=null || result.detailCollections().stream().anyMatch(b -> Set.of("INLINE_REFERENCE", "PAGE_LIST").contains(b.mode()))))
                    || result.previousActiveTemplate() != null
                            && (result.pageType() != 0 || result.routeMenuId() == null
                                    || !managed(result.previousActiveTemplate())
                                    || Objects.equals(result.templatePath(), result.previousActiveTemplate()))
                    || result.detailNeighbors() != null
                            && (result.pageType() != 1 || result.queryId() != null || result.routeMenuId() != null))
                throw new IllegalArgumentException("公开应用凭据与路径不一致");
            return result;
        } catch (java.io.IOException error) {
            throw new IllegalStateException("公开应用凭据不可读", error);
        }
    }

    void write(Path root, String code) {
        var target = file(root, code, key);
        try {
            TemplateFileWriter.write(
                    target.getParent(),
                    target.getFileName().toString(),
                    JSON.writeValueAsString(this));
        } catch (java.io.IOException error) {
            throw new IllegalStateException("公开应用凭据不可保存", error);
        }
    }

    static Path file(Path root, String code, String key) {
        if (code == null
                || !code.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,127}")
                || key == null
                || !key.matches(UUID)) throw new IllegalArgumentException("公开应用标识无效");
        try {
            root = root.toAbsolutePath().normalize();
            if (Files.isSymbolicLink(root)) throw new IllegalArgumentException("应用根目录不能是链接");
            Files.createDirectories(root);
            var real = root.toRealPath();
            var project = real.resolve(code);
            var dir = project.resolve("public-applications");
            if (Files.isSymbolicLink(project) || Files.isSymbolicLink(dir))
                throw new IllegalArgumentException("应用目录不能是链接");
            Files.createDirectories(dir);
            if (!dir.toRealPath().startsWith(real)) throw new IllegalArgumentException("应用目录越界");
            var file = dir.resolve(key + ".json");
            if (Files.isSymbolicLink(file)) throw new IllegalArgumentException("应用凭据不能是链接");
            return file;
        } catch (java.io.IOException error) {
            throw new IllegalStateException("公开应用目录不可用", error);
        }
    }
}
