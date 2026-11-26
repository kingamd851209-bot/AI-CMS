package com.xinhongru.cms.template;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xinhongru.cms.template.SiteModelNavigationService.ContentStatus;
import com.xinhongru.cms.template.SiteModelNavigationService.Kind;
import com.xinhongru.cms.template.SiteModelNavigationService.Navigation;
import com.xinhongru.cms.template.SiteModelNavigationService.Node;
import com.xinhongru.cms.template.SiteModelNavigationService.SavedNavigation;
import com.xinhongru.cms.template.SiteModelNavigationService.Scope;
import com.xinhongru.cms.template.SiteModelNavigationService.Status;
import com.xinhongru.cms.template.SiteModelPlanService.Plan;
import com.xinhongru.cms.template.SiteModelPlanService.Proposal;
import com.xinhongru.site.entity.SiteProjectEntity;
import com.xinhongru.site.mapper.SiteProjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.web.server.ResponseStatusException;

import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

class DerivedNavigationReadTest {
    private static final Scope SCOPE = new Scope(11L, "demo", "v-demo", 100);
    private static final String PLAN = "11111111-1111-1111-1111-111111111111";
    private static final String SOURCE = "about/team.html";
    private static final long PLAN_REVISION = 5;
    private static final long NAVIGATION_REVISION = 8;
    private static final Runnable GUARD = () -> {};

    @TempDir Path temporary;
    private SiteModelPlanStore storage;
    private Navigation navigationView;
    private WorkbenchChecklistEvidenceService checklist;
    private ParentEntryDecisionService entries;

    @BeforeEach
    void setup() {
        String root = temporary.toString();
        storage = new SiteModelPlanStore(root, new ObjectMapper());
        storage.save(new Plan(1, PLAN, SCOPE.siteId(), SCOPE.code(), SCOPE.packageVersion(),
                "catalog", PLAN_REVISION, "CONFIRMED",
                new Proposal("审核计划", List.of(), List.of(), List.of(), List.of()),
                List.of(), 1, 1, "2026-01-01T00:00:00Z", null));
        var plans = new SiteModelPlanService(null, null, null, root);
        var navigation = new SiteModelNavigationService(plans, null, null, null, null, null,
                new AbstractPlatformTransactionManager() {
                    @Override protected Object doGetTransaction() { throw new AssertionError(); }
                    @Override protected void doBegin(Object tx, org.springframework.transaction.TransactionDefinition definition) {
                        throw new AssertionError();
                    }
                    @Override protected void doCommit(DefaultTransactionStatus status) { throw new AssertionError(); }
                    @Override protected void doRollback(DefaultTransactionStatus status) { throw new AssertionError(); }
                }, root) {
            // 隔离栏目查询，仍通过生产守卫读取临时目录中持久化的已应用回执。
            @Override public Navigation get(Scope scope, String id, Runnable guard) {
                guard.run();
                return navigationView;
            }
            @Override Navigation appliedSnapshot(Scope scope, String id) { return navigationView; }
        };
        var mapper = (SiteProjectMapper) Proxy.newProxyInstance(SiteProjectMapper.class.getClassLoader(),
                new Class<?>[]{SiteProjectMapper.class}, (proxy, method, args) -> {
                    if (!"selectById".equals(method.getName())) throw new AssertionError(method);
                    assertEquals(SCOPE.siteId(), args[0]);
                    var project = new SiteProjectEntity();
                    project.setProjectCode(SCOPE.code());
                    return project;
                });
        entries = new ParentEntryDecisionService(plans, navigation, new SiteWorkbenchContextService(mapper, root));
        var fields = new TemplateFieldPlanService(null, null, temporary.resolve("units").toString());
        checklist = new WorkbenchChecklistEvidenceService(plans, navigation, fields, null, null, null);
    }

    @Test
    void locallyAppliedDerivedNavigationProvidesEvidenceAndStoredEntryRole() {
        navigationView = derived(PLAN_REVISION, Status.APPLIED);
        save(navigationView);

        var evidence = readChecklist(PLAN_REVISION, NAVIGATION_REVISION).items().getFirst();
        assertEquals("VERIFIED", evidence.state());
        assertEquals(102L, evidence.menuId());
        assertEquals("NONE", evidence.fieldPhase());
        assertTrue(readEntries(PLAN_REVISION, NAVIGATION_REVISION).items().isEmpty());

        var written = entries.save(SCOPE, PLAN, new ParentEntryDecisionService.Save(
                SCOPE.packageVersion(), PLAN_REVISION, NAVIGATION_REVISION, 0L,
                "parent", ParentEntryDecisionService.Role.DIRECTORY, null), GUARD);
        var stored = readEntries(PLAN_REVISION, NAVIGATION_REVISION);
        assertEquals(written, stored);
        assertEquals(ParentEntryDecisionService.Role.DIRECTORY, stored.items().getFirst().role());
        assertTrue(stored.items().getFirst().valid());
    }

    @Test
    void inheritedViewWithoutOwnReceiptIsRejected() {
        navigationView = derived(PLAN_REVISION, Status.APPLIED);
        assertConflict(PLAN_REVISION, NAVIGATION_REVISION);
    }

    @Test
    void staleRevisionIsRejectedDespiteSavedAppliedReceipt() {
        navigationView = derived(PLAN_REVISION - 1, Status.APPLIED);
        save(navigationView);
        assertConflict(PLAN_REVISION, NAVIGATION_REVISION);
    }

    @Test
    void nonAppliedReceiptIsRejected() {
        navigationView = derived(PLAN_REVISION, Status.CONFIRMED);
        save(navigationView);
        assertConflict(PLAN_REVISION, NAVIGATION_REVISION);
    }

    @Test
    void outdatedClientNavigationRevisionIsRejected() {
        navigationView = derived(PLAN_REVISION, Status.APPLIED);
        save(navigationView);
        assertConflict(PLAN_REVISION, NAVIGATION_REVISION - 1);
    }

    private void assertConflict(long planRevision, long navigationRevision) {
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> readChecklist(planRevision, navigationRevision)).getStatusCode().value());
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> readEntries(planRevision, navigationRevision)).getStatusCode().value());
    }

    private WorkbenchChecklistEvidenceService.Result readChecklist(long planRevision, long navigationRevision) {
        return checklist.read(SCOPE, PLAN, new WorkbenchChecklistEvidenceService.Request(
                SCOPE.packageVersion(), planRevision, navigationRevision,
                List.of(new WorkbenchChecklistEvidenceService.Target(102L, SOURCE, "team", "field"))), GUARD);
    }

    private ParentEntryDecisionService.Result readEntries(long planRevision, long navigationRevision) {
        return entries.read(SCOPE, PLAN, SCOPE.packageVersion(), planRevision, navigationRevision, GUARD);
    }

    private void save(Navigation view) {
        storage.saveNavigation(SCOPE.siteId(), SCOPE.code(), SCOPE.packageVersion(), PLAN,
                new SavedNavigation(view, Map.of()));
    }

    private static Navigation derived(long planRevision, Status status) {
        var parent = new Node("parent", null, "关于", 1, "about/index.html", "index.html", "nav a",
                "/about/index.html", Kind.SINGLE, null, null, 101, "/about/index.html",
                ContentStatus.PENDING_MATCH, "关于", false, false);
        var team = new Node("team", "parent", "团队", 1, SOURCE, "index.html", "nav a",
                "/" + SOURCE, Kind.SINGLE, null, null, 102, "/" + SOURCE,
                ContentStatus.PENDING_MATCH, "团队", false, false);
        return new Navigation(NAVIGATION_REVISION, planRevision, status, List.of(parent, team),
                List.of(), "22222222-2222-2222-2222-222222222222", 3L);
    }
}

