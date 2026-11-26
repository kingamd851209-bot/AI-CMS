package com.xinhongru;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.xinhongru.entity.*;
import com.xinhongru.mapper.UserSiteGroupMapper;
import com.xinhongru.permission.*;
import com.xinhongru.service.user.UserService;
import com.xinhongru.service.userGroup.UserGroupService;
import com.xinhongru.service.userSiteGroup.UserSiteGroupService;
import com.xinhongru.service.userSiteGroup.UserSiteGroupService.SiteGroupRef;
import com.xinhongru.service.userSiteGroup.impl.UserSiteGroupServiceImpl;
import com.xinhongru.utils.TaleUtils;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import java.util.List;

class MultiGroupPermissionTest {
    @Test void unionsColumnsOperationsMenusAndKeepsPrimaryWithoutCrossSiteLeak() {
        var users = mock(UserService.class);
        var groups = mock(UserGroupService.class);
        var refs = mock(UserSiteGroupService.class);
        var user = new UserEntity().setId(99).setUsername("test").setGroupId(12);
        when(users.getUserInfoById(99, false)).thenReturn(user);
        when(refs.listByUserId(99)).thenReturn(List.of(ref(1, 11), ref(1, 12), ref(2, 20), ref(1, 21)));
        when(groups.getUserGroupById(11)).thenReturn(group(11, 1, "10,30", "1;", "[100]"));
        when(groups.getUserGroupById(12)).thenReturn(group(12, 1, "10,40", "2;5", "[200]"));
        when(groups.getUserGroupById(20)).thenReturn(group(20, 2, "90", "9", "[900]"));
        when(groups.getUserGroupById(21)).thenReturn(group(21, 2, "91", "9", "[901]"));
        try (var tale = mockStatic(TaleUtils.class)) {
            tale.when(() -> TaleUtils.getLoginUser(any())).thenReturn(user);
            var service = new AdminPermissionService(users, refs, groups);
            var snapshot = service.snapshot(new MockHttpServletRequest(), 1L);
            assertThat(snapshot.groupId()).isEqualTo(12);
            assertThat(snapshot.menuIdSet()).containsExactly(10, 30, 40);
            assertThat(snapshot.canOperate(10, 1)).isTrue();
            assertThat(snapshot.canOperate(10, 2)).isTrue();
            assertThat(snapshot.canOperate(30, 5)).isFalse();
            assertThat(snapshot.canOperate(40, 5)).isTrue();
            assertThat(snapshot.systemMenuIds()).containsExactly(100, 200);
            assertThat(snapshot.groupSetting()).isEqualTo("10,30,40");
            assertThat(snapshot.otherSetting()).isEqualTo("1,2;;5");
            var second = service.snapshot(new MockHttpServletRequest(), 2L);
            assertThat(second.menuIdSet()).containsExactly(90);
            assertThat(service.snapshot(new MockHttpServletRequest(), 3L).menuIdSet()).isEmpty();
        }
    }
    @Test void acceptsSameSiteDifferentGroupsRejectsDuplicatesInvalidAndPlatformMix() {
        var mapper = mock(UserSiteGroupMapper.class);
        var groups = mock(UserGroupService.class);
        when(groups.getUserGroupById(11)).thenReturn(group(11, 1, "", "", null));
        when(groups.getUserGroupById(12)).thenReturn(group(12, 1, "", "", null));
        when(groups.getUserGroupById(20)).thenReturn(group(20, 0, "", "", null));
        var service = new UserSiteGroupServiceImpl(mapper, groups);
        service.saveUserSiteGroups(99, List.of(new SiteGroupRef(1, 11), new SiteGroupRef(1, 12)));
        verify(mapper).insertBatch(argThat(rows -> rows.size() == 2));
        assertThatThrownBy(() -> service.saveUserSiteGroups(99, List.of(new SiteGroupRef(1, 11), new SiteGroupRef(1, 11)))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.saveUserSiteGroups(99, List.of(new SiteGroupRef(2, 11)))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.saveUserSiteGroups(99, List.of(new SiteGroupRef(0, 20), new SiteGroupRef(1, 11)))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.saveUserSiteGroups(99, List.of(new SiteGroupRef(1, 999)))).isInstanceOf(IllegalArgumentException.class);
        service.saveUserSiteGroups(99, List.of(new SiteGroupRef(0, 20)));
    }
    @Test void invalidRelationsNeverMakeSitesVisibleOrGrantPlatformAccess() {
        var users = mock(UserService.class);
        var groups = mock(UserGroupService.class);
        var refs = mock(UserSiteGroupService.class);
        var user = new UserEntity().setId(99).setUsername("test").setGroupId(11);
        when(users.getUserInfoById(99, false)).thenReturn(user);
        when(groups.getUserGroupById(11)).thenReturn(group(11, 1, "10", "1", null).setDeleted(true));
        when(groups.getUserGroupById(12)).thenReturn(group(12, 2, "20", "4", null));
        when(groups.getUserGroupById(13)).thenReturn(group(13, 0, "", "", null).setDeleted(true));
        try (var tale = mockStatic(TaleUtils.class)) {
            tale.when(() -> TaleUtils.getLoginUser(any())).thenReturn(user);
            for (var relation : List.of(ref(1, 11), ref(1, 12), ref(1, 999), ref(0, 12), ref(0, 13))) {
                when(refs.listByUserId(99)).thenReturn(List.of(relation));
                clearInvocations(groups);
                var snapshot = new AdminPermissionService(users, refs, groups)
                        .snapshot(new MockHttpServletRequest(), 1L);
                assertThat(snapshot.accessibleSiteIds()).isEmpty();
                assertThat(snapshot.canAccessAllSites()).isFalse();
                assertThat(snapshot.menuIdSet()).isEmpty();
                verify(groups, atMostOnce()).getUserGroupById(relation.getGroupId());
            }
        }
    }

    @Test void contentAuditUsesSecondaryGroupAndKeepsLegacyPrimaryRule() {
        assertAuditBranch(12, true, 1, 1);
        assertAuditBranch(12, false, 2, 0);
        assertAuditBranch(2, false, 1, 1);
    }

    private void assertAuditBranch(int primaryGroup, boolean secondaryAudit, int status, int checkStatus) {
        var users = mock(UserService.class);
        var groups = mock(UserGroupService.class);
        var refs = mock(UserSiteGroupService.class);
        var user = new UserEntity().setId(99).setUsername("test").setGroupId(primaryGroup)
                .setGroupSetting("10").setOtherSetting("1");
        when(users.getUserInfoById(99, false)).thenReturn(user);
        when(refs.listByUserId(99)).thenReturn(List.of(ref(1, primaryGroup), ref(1, 20)));
        when(groups.getUserGroupById(primaryGroup)).thenReturn(group(primaryGroup, 1, "10", "1", null));
        when(groups.getUserGroupById(20)).thenReturn(group(20, 1, "10", secondaryAudit ? String.valueOf(com.xinhongru.constant.WebConst.CHECK_AUTHORITY_ID) : "", null));
        var permission = new AdminPermissionService(users, refs, groups);
        new com.xinhongru.utils.AuthorityUtils().setPermissionService(permission);
        var dao = mock(com.xinhongru.mapper.ContentMapper.class);
        var models = mock(com.xinhongru.mapper.OneShotMapper.class);
        var model = new OneshotEntity().setId(10).setSetting(String.valueOf(com.xinhongru.constant.WebConst.CHECK_AUTHORITY_ID)).setModelField("[]").setSiteProjectId(1L);
        when(models.getModelById(10)).thenReturn(model);
        when(models.lockFieldConfiguration(1L, 10L)).thenReturn(model);
        var history = mock(com.xinhongru.service.ContentHistoryService.class);
        var checks = mock(com.xinhongru.service.CheckRecordService.class);
        var service = new com.xinhongru.service.content.impl.ContentServiceImpl();
        service.setPermissionService(permission);
        service.setContentDao(dao);
        service.setOneShotMapper(models);
        service.setLogOperationService(mock(com.xinhongru.service.logOperation.LogOperationService.class));
        org.springframework.test.util.ReflectionTestUtils.setField(service, "contentHistoryService", history);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "checkRecordService", checks);
        var request = new MockHttpServletRequest();
        request.getSession().setAttribute(com.xinhongru.constant.WebConst.CURRENT_SESSION_SITE, 1L);
        try (var tale = mockStatic(TaleUtils.class)) {
            tale.when(() -> TaleUtils.getLoginUser(any())).thenReturn(user);
            tale.when(() -> TaleUtils.getUserGroupId(any())).thenReturn(primaryGroup);
            var content = new ContentEntity().setClassid(10).setTitle("test");
            service.addArticle(content, request);
            assertThat(content.getStatus()).isEqualTo(status);
            verify(history).save(argThat(row -> row.getCheckStatus() == checkStatus));
            if (checkStatus == 0) verify(checks).save(any(CheckRecordEntity.class));
            else verifyNoInteractions(checks);
        }
    }

    private UserSiteGroupEntity ref(long site, int id) { return new UserSiteGroupEntity().setSiteId(site).setGroupId(id); }
    private UserGroupEntity group(int id, long site, String columns, String operations, String menus) {
        return new UserGroupEntity().setId(id).setSiteId(site).setGroupSetting(columns).setOtherSetting(operations).setMenuSetting(menus);
    }
}
