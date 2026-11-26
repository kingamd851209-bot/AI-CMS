package com.xinhongru.api.admin.v2;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xinhongru.cms.template.SiteModelPlanService;
import com.xinhongru.site.context.SiteAccessDeniedException;
import com.xinhongru.site.context.SiteContextService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class SiteModelControllerAccessDeniedTest {
    @Test
    void latestPlanReturnsForbiddenWhenPlatformAccessIsDenied() throws Exception {
        SiteContextService sites = mock(SiteContextService.class);
        SiteModelPlanService plans = mock(SiteModelPlanService.class);
        doThrow(new SiteAccessDeniedException("需要平台管理员权限")).when(sites).requireSuperAdmin(any());
        var controller = new SiteModelController(sites, null, null, null, null, plans, null);

        MockMvcBuilders.standaloneSetup(controller).build()
                .perform(get("/api/admin/v2/sites/59/template-seed/site-model/plans/latest"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.data").doesNotExist());

        verifyNoInteractions(plans);
    }
}
