package com.xinhongru;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.xinhongru.api.admin.v2.AdminUsersV2ApiController;
import com.xinhongru.api.admin.AdminAccountAccess;
import com.xinhongru.security.AdminSecurityExceptionHandler.AdminSecurityException;
import com.xinhongru.entity.*;
import com.xinhongru.security.AdminAuthenticationService;
import com.xinhongru.security.password.Sm3PasswordHashService;
import com.xinhongru.service.log.LogService;
import com.xinhongru.service.user.UserService;
import com.xinhongru.service.userGroup.UserGroupService;
import com.xinhongru.service.userSiteGroup.UserSiteGroupService;
import com.xinhongru.service.userSiteGroup.UserSiteGroupService.SiteGroupRef;
import com.xinhongru.site.context.SiteContextService;
import com.xinhongru.site.entity.SiteProjectEntity;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class AdminUsersMultiGroupRegressionTest {
    @Test void savesOnlyManagedEditsAndPreservesOtherSiteRelationsAndPrimary() {
        var users = mock(UserService.class);
        var groups = mock(UserGroupService.class);
        var refs = mock(UserSiteGroupService.class);
        var auth = mock(AdminAuthenticationService.class);
        var sites = mock(SiteContextService.class);
        var request = new MockHttpServletRequest();
        var target = new UserEntity().setId(99).setUsername("test").setGroupId(21)
                .setGroupName("other").setScreenName("test").setEmail("test@example.com");
        when(auth.requireLoginSessionUser(request)).thenReturn(new UserEntity().setId(88).setGroupId(9));
        when(users.lockUserById(99)).thenReturn(target);
        when(users.getUserInfoById(99, false)).thenReturn(target);
        when(users.updateUserInfo(any())).thenReturn(1);
        when(sites.listAvailableSites(request)).thenReturn(List.of(new SiteProjectEntity().setId(1L).setProjectName("managed")));
        when(groups.getUserGroupById(9)).thenReturn(new UserGroupEntity().setId(9).setSiteId(1L).setGroupSetting("6"));
        when(groups.getUserGroupById(10)).thenReturn(new UserGroupEntity().setId(10).setSiteId(1L).setParentId(9));
        when(groups.getUserGroupById(11)).thenReturn(new UserGroupEntity().setId(11).setSiteId(1L).setParentId(9));
        when(groups.getUserGroupById(12)).thenReturn(new UserGroupEntity().setId(12).setSiteId(1L).setParentId(9));
        when(groups.getUserGroupById(21)).thenReturn(new UserGroupEntity().setId(21).setSiteId(2L));
        when(groups.getUserGroupById(22)).thenReturn(new UserGroupEntity().setId(22).setSiteId(2L));
        when(groups.getUserGroupById(30)).thenReturn(new UserGroupEntity().setId(30).setSiteId(0L));
        when(groups.getChildUserGroupList(0, request)).thenReturn(List.of());
        when(refs.listByUserId(99)).thenReturn(List.of(
                new UserSiteGroupEntity().setSiteId(1L).setGroupId(10),
                new UserSiteGroupEntity().setSiteId(2L).setGroupId(21),
                new UserSiteGroupEntity().setSiteId(2L).setGroupId(22)));
        var controller = new AdminUsersV2ApiController(users, groups, refs,
                mock(Sm3PasswordHashService.class), auth, mock(LogService.class), sites,
                new AdminAccountAccess(auth, groups, refs));
        controller.save(request, body(List.of(new SiteGroupRef(1, 11), new SiteGroupRef(1, 12))));
        verify(refs).saveUserSiteGroups(99, List.of(new SiteGroupRef(1, 11), new SiteGroupRef(1, 12),
                new SiteGroupRef(2, 21), new SiteGroupRef(2, 22)));
        verify(users).updateUserInfo(argThat(user -> user.getGroupId() == 21));
        clearInvocations(users, refs);
        assertThatThrownBy(() -> controller.save(request, body(List.of(new SiteGroupRef(2, 23)))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("无权管理");
        assertThatThrownBy(() -> controller.save(request, body(List.of(new SiteGroupRef(0, 30)))))
                .isInstanceOf(AdminSecurityException.class).hasMessageContaining("无权管理");
        var profileChange = new AdminUsersV2ApiController.UserRequest(99, "test", null, null,
                "test@example.com", "new name", null, List.of(new SiteGroupRef(1, 11)));
        assertThatThrownBy(() -> controller.save(request, profileChange))
                .isInstanceOf(AdminSecurityException.class);
        assertThatThrownBy(() -> controller.switchStatus(request, 99, new AdminUsersV2ApiController.StatusRequest(false)))
                .isInstanceOf(AdminSecurityException.class);
        assertThatThrownBy(() -> controller.delete(request, 99)).isInstanceOf(AdminSecurityException.class);
        verify(users, never()).updateUserInfo(any());
        verify(refs, never()).saveUserSiteGroups(anyInt(), anyList());
    }

    private AdminUsersV2ApiController.UserRequest body(List<SiteGroupRef> refs) {
        return new AdminUsersV2ApiController.UserRequest(99, "test", null, null,
                "test@example.com", "test", null, refs);
    }
}
