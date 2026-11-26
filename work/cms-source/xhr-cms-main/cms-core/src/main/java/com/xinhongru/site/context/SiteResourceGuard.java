package com.xinhongru.site.context;

import com.xinhongru.security.AdminSecurityExceptionHandler.AdminSecurityException;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

import javax.sql.DataSource;

/** Validates project-site ownership for both native records and read-only legacy projections. */
@Service
public class SiteResourceGuard {
    private final SiteContextService siteContextService;
    private final JdbcTemplate jdbc;

    @Autowired
    public SiteResourceGuard(SiteContextService siteContextService, DataSource dataSource) {
        this(
                siteContextService,
                new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource")));
    }

    SiteResourceGuard(SiteContextService siteContextService, JdbcTemplate jdbc) {
        this.siteContextService = Objects.requireNonNull(siteContextService, "siteContextService");
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    public SiteContext assertAccessible(HttpServletRequest request, ResourceReference reference) {
        ResourceReference resolved =
                reference == null ? ResourceReference.currentSite() : reference;
        SiteContext site = siteContextService.requireCurrentSite(request);
        long siteId = Objects.requireNonNull(site.getSiteId(), "当前站点 ID 不能为空");
        try {
            assertOwnership(request, siteId, resolved);
        } catch (SiteAccessDeniedException ex) {
            throw siteMismatch();
        }
        return site;
    }

    public SiteContext assertAccessible(
            HttpServletRequest request, ResourceReference reference, String permissionCode) {
        return assertAccessible(request, reference);
    }

    public boolean canAccess(HttpServletRequest request, ResourceReference reference) {
        try {
            assertAccessible(request, reference);
            return true;
        } catch (AdminSecurityException | SiteAccessDeniedException ex) {
            return false;
        }
    }

    public boolean canAccess(
            HttpServletRequest request, ResourceReference reference, String permissionCode) {
        return canAccess(request, reference);
    }

    public <T> List<T> filterAccessible(
            HttpServletRequest request,
            List<T> candidates,
            Function<T, ResourceReference> referenceFactory) {
        if (candidates == null || candidates.isEmpty()) {
            assertAccessible(request, ResourceReference.currentSite());
            return List.of();
        }
        return candidates.stream()
                .filter(candidate -> canAccess(request, referenceFactory.apply(candidate)))
                .toList();
    }

    public <T> List<T> filterAccessible(
            HttpServletRequest request,
            List<T> candidates,
            Function<T, ResourceReference> referenceFactory,
            String permissionCode) {
        return filterAccessible(request, candidates, referenceFactory);
    }

    private void assertOwnership(
            HttpServletRequest request, long siteId, ResourceReference reference) {
        if (reference.bodySite() != null && reference.bodySite().value() != siteId) {
            throw siteMismatch();
        }
        if (reference.channel() != null) {
            siteContextService.assertMenuInCurrentSite(
                    request, Math.toIntExact(reference.channel().value()));
        }
        assertLocaleOwnership(siteId, reference.locale());
        assertModelOwnership(siteId, reference.model());
        ResourceReference.ResourceId content = reference.content();
        if (content == null) {
            return;
        }
        List<Long> menuIds =
                jdbc.queryForList(
                        "SELECT class_id FROM t_contents WHERE id=?", Long.class, content.value());
        if (menuIds.size() != 1) {
            throw AdminSecurityException.notFound("RESOURCE_NOT_FOUND", "请求的资源不存在");
        }
        if (reference.channel() == null
                || menuIds.getFirst().longValue() != reference.channel().value()) {
            throw siteMismatch();
        }
    }

    private void assertLocaleOwnership(long siteId, ResourceReference.ResourceId locale) {
        if (locale == null) return;
        Integer count =
                jdbc.queryForObject(
                        """
                        SELECT COUNT(*) FROM cms_site_locale
                        WHERE site_id=? AND language_id=? AND enabled=1 AND deleted=0
                        """,
                        Integer.class,
                        siteId,
                        locale.value());
        if (count == null || count != 1) throw siteMismatch();
    }

    private void assertModelOwnership(long siteId, ResourceReference.ResourceId model) {
        if (model == null) return;
        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        "SELECT menu_ids, can_add_menu_ids FROM t_module WHERE id=? AND deleted=0",
                        model.value());
        if (rows.size() != 1) throw siteMismatch();
        Set<Long> menuIds = new LinkedHashSet<>();
        addIds(menuIds, rows.getFirst().get("menu_ids"));
        addIds(menuIds, rows.getFirst().get("can_add_menu_ids"));
        if (menuIds.isEmpty()) throw siteMismatch();
        String placeholders = String.join(",", Collections.nCopies(menuIds.size(), "?"));
        Object[] arguments = new Object[menuIds.size() + 1];
        arguments[0] = siteId;
        int index = 1;
        for (Long menuId : menuIds) arguments[index++] = menuId;
        Integer count =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM t_oneshot WHERE site_project_id=? AND id IN ("
                                + placeholders
                                + ")",
                        Integer.class,
                        arguments);
        if (count == null || count == 0) throw siteMismatch();
    }

    private void addIds(Set<Long> target, Object raw) {
        if (raw == null) return;
        for (String token : raw.toString().split("[,\\s]+")) {
            if (token.isBlank()) continue;
            try {
                long id = Long.parseLong(token);
                if (id > 0) target.add(id);
            } catch (NumberFormatException ignored) {
                // Ignore stale references; valid menu IDs still determine legacy model ownership.
            }
        }
    }

    private AdminSecurityException siteMismatch() {
        return AdminSecurityException.forbidden("RESOURCE_SITE_MISMATCH", "请求资源不属于当前站点");
    }
}
