import { createRouter, createWebHistory, type RouteLocationNormalized, type RouteRecordRaw } from 'vue-router';
import { getActivePinia } from 'pinia';
import axios from 'axios';
import { AdminHttpError, httpClient, resetUnauthorizedRedirectState, setUnauthorizedSessionResetHandler } from '@/api/http';
import { useSiteContextStore } from '@/stores/siteContext';
import AiModelConfigView from '@/views/ai-config/AiModelConfigView.vue';
import CmsContentView from '@/views/cms/CmsContentView.vue';
import CmsContentOverview from '@/views/cms/CmsContentOverview.vue';
import CmsI18nContentEditView from '@/views/cms/CmsI18nContentEditView.vue';
import CmsSinglePageView from '@/views/cms/CmsSinglePageView.vue';
import CmsMenusView from '@/views/cms/CmsMenusView.vue';
import ContentManagementView from '@/views/cms/ContentManagementView.vue';
import TemplateManagementView from '@/views/cms/TemplateManagementView.vue';
import WorkspaceHomeView from '@/views/dashboard/WorkspaceHomeView.vue';
import HeadlessApiDocView from '@/views/sites/HeadlessApiDocView.vue';
import LoginView from '@/views/auth/LoginView.vue';
import SystemLogsView from '@/views/system/SystemLogsView.vue';
import SystemSettingsView from '@/views/system/SystemSettingsView.vue';
import SystemUsersView from '@/views/system/SystemUsersView.vue';

export const routes: RouteRecordRaw[] = [
  ...(import.meta.env.VITE_DEVELOPER_WORKBENCH === 'true' ? [{
    path: '/admin/v2/site-build',
    name: 'site-build-workbench',
    component: () => import('@/views/sites/SiteBuildWorkbenchView.vue'),
    meta: { title: 'AI建站工作台', requiresSystemAdmin: true, requiresDeveloperAdmin: true }
  }] : []),
  {
    path: '/admin/login',
    name: 'admin-login',
    component: LoginView,
    meta: { title: '登录', public: true, layout: 'auth' }
  },
  {
    path: '/admin/v2',
    name: 'workspace-home',
    component: WorkspaceHomeView,
    meta: { title: '站点运营工作台' }
  },
    {
    path: '/admin/v2/sites-v2',
    alias: '/admin/v2/sites',
    name: 'site-management-v2',
    component: () => import('@/views/sites/SiteManagementV2View.vue'),
    meta: { title: '站点管理', requiresSystemAdmin: true }
  },
  {
    path: '/admin/v2/sites-v2/i18n-tasks',
    name: 'i18n-site-task',
    component: () => import('@/views/sites/I18nSiteTaskView.vue'),
    meta: { title: '多语言站点任务', requiresSystemAdmin: true }
  },
  {
    path: '/admin/v2/site-service',
    name: 'site-service',
    component: () => import('@/views/sites/SiteServiceView.vue'),
    meta: { title: '站点服务', requiresSystemAdmin: true }
  },
  {
    path: '/admin/v2/site-logs',
    name: 'site-logs',
    component: () => import('@/views/sites/SiteLogsView.vue'),
    meta: { title: '站点日志' }
  },
  {
    path: '/admin/v2/headless-api-doc',
    name: 'headless-api-doc',
    component: HeadlessApiDocView,
    meta: { title: 'Headless 接口文档' }
  },
  {
    path: '/admin/v2/ai/config',
    name: 'ai-model-config',
    component: AiModelConfigView,
    meta: { title: 'AI 模型配置', requiresSystemAdmin: true }
  },
    {
    path: '/admin/v2/cms/menus',
    name: 'cms-menus',
    component: CmsMenusView,
    meta: { title: '栏目管理' }
  },
  {
    path: '/admin/v2/cms/content-management',
    name: 'content-management',
    component: ContentManagementView,
    meta: { title: '内容管理' }
  },
  {
    path: '/admin/v2/cms/site-settings',
    name: 'cms-site-settings',
    component: () => import('@/views/cms/SiteSettingsView.vue'),
    meta: { title: '站点设置' }
  },
  {
    path: '/admin/v2/cms/form-config',
    name: 'cms-form-config',
    component: () => import('@/views/cms-platform/FormConfigView.vue'),
    meta: { title: '表单设置' }
  },
  {
    path: '/admin/v2/cms/form-center',
    name: 'cms-form-center',
    component: () => import('@/views/cms-platform/FormCenterView.vue'),
    meta: { title: '表单内容' }
  },
  {
    path: '/admin/v2/cms/schedule-publish',
    name: 'cms-schedule-publish',
    component: () => import('@/views/cms/SchedulePublishView.vue'),
    meta: { title: '定时发布' }
  },
  {
    path: '/admin/v2/cms-platform/product-config',
    name: 'cms-platform-product-config',
    component: () => import('@/views/cms-platform/ProductConfigView.vue'),
    meta: { title: '产品配置' }
  },
  {
    path: '/admin/v2/cms-platform/news-config',
    name: 'cms-platform-news-config',
    component: () => import('@/views/cms-platform/NewsConfigView.vue'),
    meta: { title: '新闻配置' }
  },
  {
    path: '/admin/v2/cms-platform/product-center',
    name: 'cms-platform-product-center',
    component: () => import('@/views/cms-platform/ProductCenterView.vue'),
    meta: { title: '产品中心' }
  },
  {
    path: '/admin/v2/cms-platform/news-center',
    name: 'cms-platform-news-center',
    component: () => import('@/views/cms-platform/NewsCenterView.vue'),
    meta: { title: '新闻中心' }
  },
  {
    path: '/admin/v2/cms-platform/solution-config',
    name: 'cms-platform-solution-config',
    component: () => import('@/views/cms-platform/SolutionConfigView.vue'),
    meta: { title: '解决方案配置' }
  },
  {
    path: '/admin/v2/cms-platform/solution-center',
    name: 'cms-platform-solution-center',
    component: () => import('@/views/cms-platform/SolutionCenterView.vue'),
    meta: { title: '解决方案' }
  },
  {
    path: '/admin/v2/cms-platform/case-config',
    name: 'cms-platform-case-config',
    component: () => import('@/views/cms-platform/CaseConfigView.vue'),
    meta: { title: '案例配置' }
  },
  {
    path: '/admin/v2/cms-platform/case-center',
    name: 'cms-platform-case-center',
    component: () => import('@/views/cms-platform/CaseCenterView.vue'),
    meta: { title: '案例中心' }
  },
  {
    path: '/admin/v2/cms-platform/static-assets',
    name: 'cms-platform-static-assets',
    component: () => import('@/views/cms-platform/StaticAssetView.vue'),
    meta: { title: '静态资源' }
  },
  {
    path: '/admin/v2/cms/template-management',
    name: 'template-management',
    component: TemplateManagementView,
    meta: { title: '模板管理' }
  },
  {
    path: '/admin/v2/cms/static-publish',
    name: 'static-publish',
    component: () => import('@/views/cms/StaticPublishView.vue'),
    meta: { title: '静态页发布' }
  },
  {
    path: '/admin/v2/cms/content',
    name: 'cms-content',
    component: CmsContentOverview,
    meta: { title: '内容总览' }
  },
  {
    path: '/admin/v2/cms/content/:menuId',
    name: 'cms-content-menu',
    component: CmsContentView,
    meta: { title: '内容管理' }
  },
  {
    path: '/admin/v2/cms/single/:menuId',
    name: 'cms-single-page',
    component: CmsSinglePageView,
    meta: { title: '单页维护' }
  },
  {
    path: '/admin/v2/cms/i18n-content',
    name: 'cms-i18n-content-edit',
    component: CmsI18nContentEditView,
    meta: { title: '目标语言内容编辑' }
  },
  {
    path: '/admin/v2/cms/ui-package-preview',
    name: 'ui-package-preview',
    component: () => import('@/views/cms/UiPackagePreviewView.vue'),
    meta: { title: 'UI Package 预览' }
  },
  {
    path: '/admin/v2/seo/robots',
    name: 'seo-robots',
    component: () => import('@/views/seo/SeoRobotsView.vue'),
    meta: { title: 'Robots管理' }
  },
  {
    path: '/admin/v2/seo/sitemap',
    name: 'seo-sitemap',
    component: () => import('@/views/seo/SeoSitemapView.vue'),
    meta: { title: 'SiteMap管理' }
  },
  {
    path: '/admin/v2/seo/hreflang',
    name: 'seo-hreflang',
    component: () => import('@/views/seo/SeoHreflangView.vue'),
    meta: { title: 'Hreflang管理' }
  },
  {
    path: '/admin/v2/seo/geo',
    name: 'seo-geo',
    component: () => import('@/views/seo/SeoGeoView.vue'),
    meta: { title: 'GEO管理' }
  },
  {
    path: '/admin/v2/seo/meta',
    name: 'seo-meta',
    component: () => import('@/views/seo/SeoMetaView.vue'),
    meta: { title: 'SEO管理' }
  },
  {
    path: '/admin/v2/sensitive-word',
    name: 'sensitive-word',
    component: () => import('@/views/sensitive/SensitiveWordView.vue'),
    meta: { title: '敏感词管理' }
  },
  {
    path: '/admin/v2/system/users',
    name: 'system-users',
    component: SystemUsersView,
    meta: { title: '用户权限', requiresPermissionCenterRead: true, profilePanelsAllowed: true }
  },
  {
    path: '/admin/v2/system/admin-groups',
    name: 'system-admin-groups',
    component: () => import('@/views/system/AdminGroupsView.vue'),
    meta: { title: '管理员组管理', requiresSystemAdmin: true }
  },
  {
    path: '/admin/v2/system/menus',
    name: 'system-menus',
    component: () => import('@/views/system/SystemMenusView.vue'),
    meta: { title: '菜单管理', requiresSystemAdmin: true }
  },
  {
    path: '/admin/v2/system/languages',
    name: 'system-languages',
    component: () => import('@/views/system/LanguageConfigView.vue'),
    meta: { title: '语言配置', requiresSystemAdmin: true }
  },
  {
    path: '/admin/v2/system/admin-users',
    name: 'system-admin-users',
    component: () => import('@/views/system/AdminUsersView.vue'),
    meta: { title: '管理员管理', requiresSystemAdmin: true }
  },
  {
    path: '/admin/v2/system/logs',
    name: 'system-logs',
    component: SystemLogsView,
    meta: { title: '系统日志', requiresSystemAdmin: true }
  },
  {
    path: '/admin/v2/system/operation-logs',
    name: 'system-operation-logs',
    component: () => import('@/views/system/OperationLogsView.vue'),
    meta: { title: '操作日志', requiresSystemAdmin: true }
  },
  {
    path: '/admin/v2/system/settings',
    name: 'system-settings',
    component: SystemSettingsView,
    meta: { title: '系统设置', requiresSystemSettingsManage: true }
  },
  {
    path: '/admin/v2/:pathMatch(.*)*',
    redirect: '/admin/v2'
  }
];

const router = createRouter({
  history: createWebHistory(),
  routes
});

setUnauthorizedSessionResetHandler(() => {
  if (!getActivePinia()) {
    return;
  }

  useSiteContextStore().clearSessionState();
});

/** 被权限守卫拦截时设为 true，使跳转到 /admin/v2 后仍能展示一次提示，
 *  而不是被下一次放行导航立即清掉。 */
let permissionNoticePending = false;

export async function adminRouteGuard(to: RouteLocationNormalized) {
  const login = to.name === 'site-build-workbench'
    ? {path:'/admin/login', query:{returnTo:to.fullPath}}
    : '/admin/login';
  const unavailable = {path:'/admin/login', query:{returnTo:to.fullPath, sessionUnavailable:'1'}};
  if (to.meta.public) {
    return true;
  }

  if (!getActivePinia()) {
    return true;
  }

  const siteContext = useSiteContextStore();

  try {
    const response = await httpClient.get('/api/admin/v2/auth/session');
    if (response.data?.code !== 0 || !response.data.data || response.data.data.pendingAuthorization) {
      siteContext.clearSessionState();
      return login;
    }
  } catch (error) {
    if (to.name==='site-build-workbench' && axios.isAxiosError(error) && (!error.response || error.response.status>=500)) return unavailable;
    siteContext.clearSessionState();
    return login;
  }

  if (!siteContext.authenticated) {
    try {
      await siteContext.ensureAuthenticated();
    } catch (error) {
      if (to.name==='site-build-workbench' && error instanceof AdminHttpError && !error.authenticationError && !error.authorizationError) return unavailable;
      // 会话失效、站点上下文加载失败等一律按未登录处理，回登录页而不是留在系统首页
      siteContext.clearMessage();
      return login;
    }
  }

  resetUnauthorizedRedirectState();

  // 会话缺失时后端返回 200 但不带 currentUser，这里按未登录处理，回登录页
  if (!siteContext.currentAdmin) {
    return login;
  }

  if (!hasRoutePermission(to, siteContext)) {
    permissionNoticePending = true;
    return '/admin/v2';
  }

  if (to.name === 'site-build-workbench' && typeof to.query.siteId === 'string' &&
      String(siteContext.currentSite?.id) !== to.query.siteId) {
    const id = Number(to.query.siteId);
    if (!Number.isSafeInteger(id) || id <= 0) return '/admin/v2';
    try { await siteContext.switchSite(id); }
    catch (error) {
      if (error instanceof AdminHttpError && !error.authorizationError && !error.authenticationError && error.status !== 404) return unavailable;
      siteContext.setMessage('无法进入指定站点，请核对站点权限。', true);
      permissionNoticePending = true;
      return '/admin/v2';
    }
    if (!hasRoutePermission(to, siteContext)) { permissionNoticePending = true; return '/admin/v2'; }
  }
  if (permissionNoticePending) {
    permissionNoticePending = false;
  } else {
    siteContext.clearMessage();
  }

  return true;
}

function hasRoutePermission(to: RouteLocationNormalized, siteContext: ReturnType<typeof useSiteContextStore>) {
  // 开发者工具只开放给平台管理员，不能由普通系统菜单路径授权绕过。
  if (to.meta.requiresDeveloperAdmin && !siteContext.isPlatformAdmin) {
    siteContext.setMessage('开发者工作台仅向平台管理员开放', true);
    return false;
  }
  if (to.meta.profilePanelsAllowed && ['profile', 'password'].includes(String(to.query.panel || ''))) {
    return true;
  }

  if (to.meta.requiresPermissionCenterRead) {
    if (siteContext.canAccessPermissionCenter) {
      return true;
    }
    siteContext.setMessage('您没有权限访问该系统管理入口', true);
    return false;
  }

  if (to.meta.requiresSystemSettingsManage) {
    if (siteContext.canManageSystemSettings) {
      return true;
    }
    siteContext.setMessage('您没有权限访问该系统管理入口', true);
    return false;
  }

  if (!to.meta.requiresSystemAdmin) {
    return true;
  }

  if (siteContext.canManageSystem || siteContext.canAccessSystemMenuPath(to.path)) {
    return true;
  }

  siteContext.setMessage('您没有权限访问该系统管理入口', true);
  return false;
}

router.beforeEach(adminRouteGuard);

export default router;
