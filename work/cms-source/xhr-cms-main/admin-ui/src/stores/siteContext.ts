import { defineStore } from 'pinia';
import { http, isAuthenticationError } from '@/api/http';
import { legacyModuleMenuItems } from '@/views/migration/cmsLegacyModules';
import type { MenuEntity } from '@/api/systemMenus';

export interface SiteSummary {
  id: number | string;
  name: string;
  code: string;
  domain?: string;
  language?: string;
  rootMenuId?: number | null;
  status?: string;
}

interface SiteManagementApiSite {
  siteId: number | string;
  projectName: string;
  projectCode: string;
  siteDomain?: string;
  defaultLanguage?: string;
  rootMenuId?: number | null;
  status?: string;
}

interface SiteContextApiResponse {
  siteId: number | string;
  rootMenuId?: number | null;
  siteName: string;
  siteCode: string;
  globalMode?: boolean;
  currentUser?: CurrentAdmin | null;
  capabilities?: PermissionCapabilities | null;
}

export interface PermissionCapabilities {
  canManageSystem: boolean;
  canAccessPermissionCenter: boolean;
  canManageSystemSettings: boolean;
  /** 平台管理（超管或绑定平台组，可跨站点）；false 表示站点管理 */
  platformAdmin?: boolean;
  /** 菜单配置授权的系统菜单路径；null/undefined 表示全放行（超管/平台组） */
  systemMenuPaths?: string[] | null;
}

export interface ContentMenuNode {
  id: number;
  parentId?: number | null;
  className: string;
  pageType?: number | null;
  manageable?: boolean;
  manageReason?: string | null;
  contentRoute?: string | null;
  contentManageable?: boolean;
  navigationOnly?: boolean;
  siteScoped?: boolean;
  invalidReason?: string | null;
  children?: ContentMenuNode[];
}

interface ContentNavigationApiResponse {
  siteId?: number | string | null;
  rootMenuId?: number | null;
  currentSite?: SiteContextApiResponse;
  menus?: ContentMenuNode[];
  tree?: ContentMenuNode[];
}

export interface SystemMenuItem {
  key: string;
  label: string;
  path: string;
  category?: string;
  icon?: string;
  badge?: string;
  disabled?: boolean;
  tooltip?: string;
  children?: SystemMenuItem[];
}

export interface CurrentAdmin {
  id: number;
  username: string;
  screenName?: string;
  groupName?: string;
  groupId?: number;
  groupSetting?: string;
  otherSetting?: string;
  permissionCodes?: string[];
  permissions?: Array<{ code?: string | null }>;
}

interface SiteContextState {
  authenticated: boolean;
  currentAdmin: CurrentAdmin | null;
  currentSite: SiteSummary | null;
  sites: SiteSummary[];
  contentMenus: ContentMenuNode[];
  switching: boolean;
  loading: boolean;
  message: string;
  routeNotice: boolean;
  systemMenus: SystemMenuItem[];
  capabilities: PermissionCapabilities | null;
}

interface LoadSitesOptions {
  autoSelectIfMissing?: boolean;
}

let switchRequestSeq = 0;
let switchQueue: Promise<unknown> = Promise.resolve();
let switchSession = 0;
let queuedSwitches = 0;
let navigationRequestSeq = 0;
const LAST_SITE_STORAGE_PREFIX = 'hongru:admin:last-site';

function toSystemMenuItem(item: {
  key: string;
  label: string;
  path: string;
  category: string;
  icon: string;
}): SystemMenuItem {
  return {
    key: item.key,
    label: item.label,
    path: item.path,
    category: item.category,
    icon: item.icon
  };
}

/**
 * 将 MenuEntity 转换为 SystemMenuItem
 */
function convertMenuEntityToSystemMenuItem(menu: MenuEntity, allMenus: MenuEntity[]): SystemMenuItem {
  const menuId = Number(menu.id);
  const children = allMenus
    .filter(m => Number(m.parentId) === menuId && m.enabled && m.visible)
    .sort((a, b) => a.sortOrder - b.sortOrder)
    .map(m => convertMenuEntityToSystemMenuItem(m, allMenus));

  const item: SystemMenuItem = {
    key: menu.menuKey,
    label: menu.title,
    path: menu.path,
    category: menu.category,
    icon: menu.icon,
    disabled: !menu.enabled
  };

  if (children.length > 0) {
    item.children = children;
  }

  return item;
}

/**
 * 从 API 加载动态菜单并构建树形结构
 */
async function loadDynamicSystemMenus(): Promise<SystemMenuItem[]> {
  try {
    const menus = await http.get<MenuEntity[]>('/api/admin/v2/menus');
    if (!Array.isArray(menus) || menus.length === 0) {
      return [];
    }

    const enabledMenus = menus.filter(m => m.enabled && m.visible);
    // 使用 Number() 确保 parentId 比较时类型一致
    const rootMenus = enabledMenus
      .filter(m => Number(m.parentId) === 0)
      .sort((a, b) => a.sortOrder - b.sortOrder);

    return rootMenus.map(menu => convertMenuEntityToSystemMenuItem(menu, enabledMenus));
  } catch (e) {
    console.error('加载动态菜单失败', e);
    return [];
  }
}

export function flattenContentMenus(contentMenus: ContentMenuNode[] = []): ContentMenuNode[] {
  return contentMenus.flatMap((menu) => [menu, ...flattenContentMenus(menu.children ?? [])]);
}

function isSinglePageMenu(menu: ContentMenuNode): boolean {
  return menu.pageType === 0 || contentMenuReason(menu) === 'single-page-non-content';
}

function isContentMenuRoutable(menu: ContentMenuNode): boolean {
  return Boolean(resolveContentMenuPath(menu, null));
}

function isListContentMenuRoutable(menu: ContentMenuNode): boolean {
  return !isSinglePageMenu(menu) && isContentMenuRoutable(menu);
}

function contentMenuReason(menu: ContentMenuNode): string | null {
  return menu.manageReason ?? menu.invalidReason ?? null;
}

export function findFirstManageableContentMenu(contentMenus: ContentMenuNode[] = []): ContentMenuNode | undefined {
  return flattenContentMenus(contentMenus).find((menu) => isListContentMenuRoutable(menu));
}

function resolveContentMenuPath(menu: ContentMenuNode, fallbackPath: string | null = '/admin/v2/cms/content'): string | null {
  if (menu.manageable === false) {
    return isSinglePageMenu(menu) && menu.id ? `/admin/v2/cms/single/${menu.id}` : null;
  }

  if (isSinglePageMenu(menu) && menu.id) {
    return `/admin/v2/cms/single/${menu.id}`;
  }

  if (typeof menu.contentRoute !== 'undefined') {
    return menu.contentRoute ?? (menu.manageable ? fallbackPath : null);
  }

  if (menu.manageable) {
    return `/admin/v2/cms/content/${menu.id}`;
  }

  if (menu.contentManageable) {
    return `/admin/v2/cms/content/${menu.id}`;
  }

  return null;
}

export function isManageableContentMenu(contentMenus: ContentMenuNode[] = [], menuId: number | string): boolean {
  return flattenContentMenus(contentMenus).some((menu) => String(menu.id) === String(menuId) && isListContentMenuRoutable(menu));
}

/** 归一化菜单路径用于比较：去掉查询串与首尾斜杠 */
function normalizeMenuPath(path: string): string {
  return String(path || '')
    .split('?')[0]
    .replace(/\/+$/, '')
    .replace(/^\/+/, '');
}

function canManageSystemAdmin(admin: CurrentAdmin | null): boolean {
  if (!admin) {
    return true;
  }
  if (admin.id === 13 || admin.username === 'supera') {
    return true;
  }
  return String(admin.groupSetting || '')
    .split(',')
    .map((item) => item.trim())
    .includes('6');
}

function adminPermissionCodes(admin: CurrentAdmin | null): Set<string> {
  const codes = new Set(
    String(admin?.groupSetting || '')
      .split(',')
      .map((item) => item.trim())
      .filter(Boolean)
  );
  (admin?.permissionCodes ?? []).forEach((code) => {
    if (code) {
      codes.add(code);
    }
  });
  (admin?.permissions ?? []).forEach((permission) => {
    if (permission.code) {
      codes.add(permission.code);
    }
  });
  return codes;
}

export function canAccessPermissionCenter(admin: CurrentAdmin | null): boolean {
  if (!admin) {
    return true;
  }
  if (admin.id === 13 || admin.username === 'supera') {
    return true;
  }
  const permissionCodes = adminPermissionCodes(admin);
  return permissionCodes.has('system.access.read') || permissionCodes.has('system.access.manage');
}

export function canManageSystemSettings(admin: CurrentAdmin | null): boolean {
  if (!admin) {
    return true;
  }
  if (admin.id === 13 || admin.username === 'supera') {
    return true;
  }
  const permissionCodes = adminPermissionCodes(admin);
  return permissionCodes.has('6') || permissionCodes.has('system.setting.manage');
}

/** 超级管理员（id=13 或 username=supera） */
function isSuperAdminUser(admin: CurrentAdmin | null): boolean {
  return admin != null && (admin.id === 13 || admin.username === 'supera');
}

/**
 * 解析当前用户在「当前站点生效管理员组」下的栏目/按钮权限。
 * group_setting 逗号分隔栏目 id；other_setting 分号分段与 group_setting 逐位对齐、段内逗号分隔 optionId。
 */
function permOf(admin: CurrentAdmin | null) {
  const superAdmin = isSuperAdminUser(admin);
  // 未知/未识别身份：保守放行，避免误锁
  if (admin == null) {
    return { hasMenu: () => true, canOperate: () => true };
  }
  const groupSetting = String(admin?.groupSetting || '');
  const otherSetting = String(admin?.otherSetting || '');
  const menuKeys = groupSetting.split(',').map((item) => item.trim()).filter(Boolean);
  const menuKeySet = new Set(menuKeys);
  return {
    hasMenu(menuId: number | string): boolean {
      if (superAdmin) {
        return true;
      }
      return menuKeySet.has(String(menuId));
    },
    canOperate(menuId: number | string, optionId: number): boolean {
      if (superAdmin) {
        return true;
      }
      const index = menuKeys.indexOf(String(menuId));
      if (index === -1) {
        return false;
      }
      const segment = otherSetting.split(';', menuKeys.length)[index] ?? '';
      return segment.split(',').map((item) => item.trim()).includes(String(optionId));
    }
  };
}

function lastSiteStorageKey(admin: CurrentAdmin | null): string | null {
  if (admin?.id != null) {
    return `${LAST_SITE_STORAGE_PREFIX}:${admin.id}`;
  }
  if (admin?.username) {
    return `${LAST_SITE_STORAGE_PREFIX}:${admin.username}`;
  }
  return null;
}

function readRememberedSiteId(admin: CurrentAdmin | null): string | null {
  const key = lastSiteStorageKey(admin);
  if (!key || typeof localStorage === 'undefined') {
    return null;
  }
  try {
    return localStorage.getItem(key);
  } catch {
    return null;
  }
}

function rememberSiteId(admin: CurrentAdmin | null, siteId: SiteSummary['id'] | null | undefined) {
  const key = lastSiteStorageKey(admin);
  if (!key || siteId == null || typeof localStorage === 'undefined') {
    return;
  }
  try {
    localStorage.setItem(key, String(siteId));
  } catch {
    // Ignore storage failures so restricted browser modes do not break navigation.
  }
}

export function createSystemMenus(contentMenus: ContentMenuNode[] = [], currentAdmin: CurrentAdmin | null = null, capabilities: PermissionCapabilities | null = null): SystemMenuItem[] {
  const systemManageable = capabilities?.canManageSystem ?? canManageSystemAdmin(currentAdmin);
  const systemSettingsManageable = capabilities?.canManageSystemSettings ?? canManageSystemSettings(currentAdmin);
  const legacyMenuItems = legacyModuleMenuItems();
  const contentOverview = legacyMenuItems
    .find((item) => item.moduleKey === 'content');
  const contentOverviewItem: SystemMenuItem = contentOverview
    ? {
        ...toSystemMenuItem(contentOverview),
        label: '内容总览'
      }
    : {
        key: 'cms-content-overview',
        label: '内容总览',
        path: '/admin/v2/cms/content',
        category: '站点内容',
        icon: 'Grid'
      };
  const contentMenuItems = contentMenus.map(contentMenuToSystemMenuItem).filter((item): item is SystemMenuItem => Boolean(item));
  const firstManageableContentMenu = findFirstManageableContentMenu(contentMenus);
  const cmsMenuItems = legacyMenuItems
    .filter((item) => item.section === 'cms')
    .filter((item) => item.moduleKey === 'menus')
    .map(toSystemMenuItem);
  const systemMenuItems = legacyMenuItems
    .filter((item) => item.section === 'system')
    .filter((item) => {
      if (item.moduleKey === 'users') {
        return false;
      }
      if (item.moduleKey === 'settings') {
        return systemSettingsManageable;
      }
      if (item.moduleKey === 'logs') {
        return systemManageable;
      }
      return true;
    })
    .map(toSystemMenuItem);
  const siteIntelligenceItems: SystemMenuItem[] = [
    ...(import.meta.env.VITE_DEVELOPER_WORKBENCH === 'true' && systemManageable && (capabilities?.platformAdmin ?? isSuperAdminUser(currentAdmin))
      ? [{
          key: 'site-build-workbench',
          label: 'AI建站工作台',
          path: '/admin/v2/site-build',
          category: '站点智能管理',
          icon: 'MagicStick'
        }]
      : []),
    ...(systemManageable
      ? [
          {
            key: 'site-management-v2',
            label: '站点管理',
            path: '/admin/v2/sites-v2',
            category: '站点智能管理',
            icon: 'OfficeBuilding'
          },
          {
            key: 'i18n-site-task',
            label: '多语言站点任务',
            path: '/admin/v2/sites-v2/i18n-tasks',
            category: '站点智能管理',
            icon: 'List'
          },
          {
            key: 'headless-api-doc',
            label: 'Headless 接口文档',
            path: '/admin/v2/headless-api-doc',
            category: '站点智能管理',
            icon: 'Document'
          },
          {
            key: 'ai-config',
            label: 'AI 模型配置',
            path: '/admin/v2/ai/config',
            category: '站点智能管理',
            icon: 'Connection'
          }
        ]
      : [])
  ];

  return [
    {
      key: 'workspace',
      label: '工作台',
      path: '/admin/v2',
      category: '工作台',
      icon: 'HomeFilled',
      children: [
        {
          key: 'workspace-home',
          label: '工作台',
          path: '/admin/v2',
          category: '工作台',
          icon: 'House'
        }
      ]
    },
    {
      key: 'site-intelligence',
      label: '站点智能管理',
      path: '/admin/v2/sites',
      category: '站点智能管理',
      icon: 'Promotion',
      children: siteIntelligenceItems
    },
    {
      key: 'site-service',
      label: '站点服务',
      path: '/admin/v2/site-service',
      category: '站点服务',
      icon: 'Monitor',
      children: [
        {
          key: 'site-service-main',
          label: '站点服务',
          path: '/admin/v2/site-service',
          category: '站点服务',
          icon: 'Monitor'
        },
        {
          key: 'site-logs',
          label: '站点日志',
          path: '/admin/v2/site-logs',
          category: '站点服务',
          icon: 'Document'
        }
      ]
    },
    {
      key: 'cms',
      label: '站点内容',
      path: firstManageableContentMenu ? `/admin/v2/cms/content/${firstManageableContentMenu.id}` : contentOverviewItem.path,
      category: '站点内容',
      icon: 'Grid',
      children: [
        contentOverviewItem,
        ...cmsMenuItems,
        {
          key: 'content-management',
          label: '内容管理',
          path: '/admin/v2/cms/content-management',
          category: '站点内容',
          icon: 'Document'
        },
        {
          key: 'template-management',
          label: '模板管理',
          path: '/admin/v2/cms/template-management',
          category: '站点内容',
          icon: 'Files'
        },
        {
          key: 'static-publish',
          label: '静态页发布',
          path: '/admin/v2/cms/static-publish',
          category: '站点内容',
          icon: 'Promotion'
        },
        {
          key: 'cms-site-settings',
          label: '站点设置',
          path: '/admin/v2/cms/site-settings',
          category: '站点内容',
          icon: 'Setting'
        },
        {
          key: 'form-config',
          label: '表单设置',
          path: '/admin/v2/cms/form-config',
          category: '站点内容',
          icon: 'Tickets'
        },
        {
          key: 'form-center',
          label: '表单内容',
          path: '/admin/v2/cms/form-center',
          category: '站点内容',
          icon: 'DocumentCopy'
        },
        {
          key: 'schedule-publish',
          label: '定时发布',
          path: '/admin/v2/cms/schedule-publish',
          category: '站点内容',
          icon: 'Timer'
        },
        ...contentMenuItems
      ]
    },
    {
      key: 'cms-platform',
      label: '内容中台',
      path: '/admin/v2/cms-platform/product-config',
      category: '内容中台',
      icon: 'FolderOpened',
      children: [
        {
          key: 'cms-platform-product-config',
          label: '产品配置',
          path: '/admin/v2/cms-platform/product-config',
          category: '内容中台',
          icon: 'Goods'
        },
        {
          key: 'cms-platform-news-config',
          label: '新闻配置',
          path: '/admin/v2/cms-platform/news-config',
          category: '内容中台',
          icon: 'News'
        },
        {
          key: 'cms-platform-product-center',
          label: '产品中心',
          path: '/admin/v2/cms-platform/product-center',
          category: '内容中台',
          icon: 'GoodsFilled'
        },
        {
          key: 'cms-platform-news-center',
          label: '新闻中心',
          path: '/admin/v2/cms-platform/news-center',
          category: '内容中台',
          icon: 'Newspaper'
        },
        {
          key: 'cms-platform-solution-config',
          label: '解决方案配置',
          path: '/admin/v2/cms-platform/solution-config',
          category: '内容中台',
          icon: 'Management'
        },
        {
          key: 'cms-platform-solution-center',
          label: '解决方案',
          path: '/admin/v2/cms-platform/solution-center',
          category: '内容中台',
          icon: 'Tickets'
        },
        {
          key: 'cms-platform-case-config',
          label: '案例配置',
          path: '/admin/v2/cms-platform/case-config',
          category: '内容中台',
          icon: 'Collection'
        },
        {
          key: 'cms-platform-case-center',
          label: '案例中心',
          path: '/admin/v2/cms-platform/case-center',
          category: '内容中台',
          icon: 'Files'
        },
        {
          key: 'cms-platform-static-assets',
          label: '静态资源',
          path: '/admin/v2/cms-platform/static-assets',
          category: '内容中台',
          icon: 'Folder'
        }
      ]
    },
    {
      key: 'seo-marketing',
      label: '营销管理',
      path: '/admin/v2/seo/robots',
      category: '营销管理',
      icon: 'TrendCharts',
      children: [
        {
          key: 'seo-robots',
          label: 'Robots管理',
          path: '/admin/v2/seo/robots',
          category: '营销管理',
          icon: 'Document'
        },
        {
          key: 'seo-sitemap',
          label: 'SiteMap管理',
          path: '/admin/v2/seo/sitemap',
          category: '营销管理',
          icon: 'MapLocation'
        },
        {
          key: 'seo-hreflang',
          label: 'Hreflang管理',
          path: '/admin/v2/seo/hreflang',
          category: '营销管理',
          icon: 'Link'
        },
        {
          key: 'seo-geo',
          label: 'GEO管理',
          path: '/admin/v2/seo/geo',
          category: '营销管理',
          icon: 'Location'
        },
        {
          key: 'seo-meta',
          label: 'SEO管理',
          path: '/admin/v2/seo/meta',
          category: '营销管理',
          icon: 'Search'
        }
      ]
    },
    {
      key: 'security-compliance',
      label: '安全合规',
      path: '/admin/v2/sensitive-word',
      category: '安全合规',
      icon: 'Shield',
      children: [
        {
          key: 'sensitive-word',
          label: '敏感词',
          path: '/admin/v2/sensitive-word',
          category: '安全合规',
          icon: 'Warning'
        }
      ]
    },
    {
      key: 'system',
      label: '系统',
      path: systemManageable
        ? (systemMenuItems[0]?.path ?? '/admin/v2/system/users')
        : systemSettingsManageable
          ? '/admin/v2/system/settings'
          : '/admin/v2/system/users',
      category: '系统管理',
      icon: 'Setting',
      children: [
        ...(systemManageable
          ? [
              {
                key: 'system-admin-groups',
                label: '管理员组管理',
                path: '/admin/v2/system/admin-groups',
                category: '系统管理',
                icon: 'UserFilled'
              },
              {
                key: 'system-admin-users',
                label: '管理员管理',
                path: '/admin/v2/system/admin-users',
                category: '系统管理',
                icon: 'Avatar'
              }
            ]
          : []),
        ...systemMenuItems
      ]
    }
  ];
}

function contentMenuToSystemMenuItem(menu: ContentMenuNode): SystemMenuItem | null {
  const path = resolveContentMenuPath(menu, null) ?? '';
  const reason = contentMenuReason(menu);
  const children = (menu.children ?? []).map(contentMenuToSystemMenuItem).filter((item): item is SystemMenuItem => Boolean(item));
  if (!path && children.length === 0 && reason === 'missing-manage-channel') {
    return null;
  }
  return {
    key: `cms-content-menu-${menu.id}`,
    label: menu.className,
    path,
    category: '站点内容',
    icon: path ? (menu.pageType === 0 ? 'Document' : 'Grid') : 'Grid',
    badge: path ? undefined : contentMenuBadge(reason),
    disabled: !path,
    tooltip: path ? undefined : contentMenuTooltip(reason),
    children
  };
}

function contentMenuBadge(reason: string | null): string {
  if (reason === 'single-page-non-content') {
    return '单页';
  }
  if (reason === 'external-link') {
    return '外链';
  }
  return '分组';
}

function contentMenuTooltip(reason: string | null): string {
  if (reason === 'single-page-non-content') {
    return '当前栏目为单页栏目，请继续使用单页维护入口。';
  }
  if (reason === 'external-link') {
    return '当前栏目为外链栏目，内容管理侧边栏仅保留展示。';
  }
  return '当前栏目仅作为分组展示，请选择下方可管理内容栏目。';
}

export const useSiteContextStore = defineStore('siteContext', {
  state: (): SiteContextState => ({
    authenticated: false,
    currentAdmin: null,
    currentSite: null,
    sites: [],
    contentMenus: [],
    switching: false,
    loading: false,
    message: '',
    routeNotice: false,
    systemMenus: createSystemMenus(),
    capabilities: null
  }),
  getters: {
    hasSites: (state) => state.sites.length > 0,
    currentSiteLabel: (state) => state.currentSite?.name ?? '未选择站点',
    firstManageableContentMenu: (state) => findFirstManageableContentMenu(state.contentMenus),
    canManageSystem: (state) => state.capabilities?.canManageSystem ?? false,
    canManageSystemSettings: (state) => state.capabilities?.canManageSystemSettings ?? false,
    canAccessPermissionCenter: (state) => state.capabilities?.canAccessPermissionCenter ?? false,
    /** 平台管理（超管或绑定平台组，可跨站点）；无 capabilities 时退回超管判断 */
    isPlatformAdmin: (state) => state.capabilities?.platformAdmin ?? isSuperAdminUser(state.currentAdmin),
    /** 「菜单配置」是否授权了某系统菜单路径；canManageSystem 与全放行（paths 为 null）直接通过 */
    canAccessSystemMenuPath: (state) => (path: string) => {
      if (state.capabilities?.canManageSystem) {
        return true;
      }
      const paths = state.capabilities?.systemMenuPaths;
      if (paths === null || paths === undefined) {
        return false;
      }
      const normalized = normalizeMenuPath(path);
      return paths.some((item) => normalizeMenuPath(item) === normalized);
    },
    /** 当前用户的栏目访问权限；平台组与超管放行 */
    hasMenuPermission: (state) => {
      const permissions = permOf(state.currentAdmin);
      return (menuId: number | string) => state.capabilities?.platformAdmin === true || permissions.hasMenu(menuId);
    },
    /** 当前用户的栏目操作权限；平台组与超管放行 */
    canOperate: (state) => (menuId: number | string, optionId: number) =>
      state.capabilities?.platformAdmin === true || permOf(state.currentAdmin).canOperate(menuId, optionId)
  },
  actions: {
    clearSessionState() {
      switchSession++;
      switchRequestSeq++;
      queuedSwitches = 0;
      this.authenticated = false;
      this.currentAdmin = null;
      this.currentSite = null;
      this.sites = [];
      this.contentMenus = [];
      this.switching = false;
      this.loading = false;
      this.clearMessage();
      this.systemMenus = createSystemMenus();
      this.capabilities = null;
    },
    async ensureAuthenticated() {
      if (this.authenticated) {
        return true;
      }

      const currentContext = await http.get<SiteContextApiResponse>('/api/admin/site-context/current');
      this.authenticated = true;
      this.applyCurrentContext(currentContext);
      return true;
    },
    setSites(sites: SiteSummary[]) {
      this.sites = sites;
      const current = sites.find((site) => String(site.id) === String(this.currentSite?.id));
      if (current) {
        this.currentSite = current;
      } else {
        this.currentSite = null;
      }
    },
    async switchSite(siteId: SiteSummary['id']) {
      if (this.routeNotice) this.clearMessage();
      const session = switchSession;
      queuedSwitches++;
      this.switching = true;
      const job = switchQueue.then(async () => {
        if (session !== switchSession) throw new Error('登录状态已变化，请重新选择站点');
        const requestSeq = ++switchRequestSeq;
        const current = () => session === switchSession && requestSeq === switchRequestSeq;
        const readCurrent = () => http.get<SiteContextApiResponse>('/api/admin/site-context/current');
        this.resetContentNavigation();
        try {
          const selected = await http.post<SiteContextApiResponse>(`/api/admin/v2/sites/${siteId}/switch-current`);
          if (!current()) return selected;
          const context = this.currentAdmin && isSuperAdminUser(this.currentAdmin) ? selected : await readCurrent();
          if (!current()) return selected;
          if (context.globalMode || String(context.siteId) !== String(siteId)) {
            throw new Error('服务端当前站点已变化，请重新选择站点');
          }
          this.applyCurrentContext(context);
          await this.loadContentNavigation(requestSeq);
          return selected;
        } catch (error) {
          if (current()) {
            try {
              const actual = await readCurrent();
              if (current()) {
                if (actual.globalMode) this.currentSite = null;
                this.applyCurrentContext(actual);
                if (this.currentSite) await this.loadContentNavigation(requestSeq);
              }
            } catch {
              if (current()) this.currentSite = null;
            }
            if (current()) this.setMessage(error instanceof Error && error.message ? error.message : '站点切换失败');
          }
          throw error;
        }
      });
      switchQueue = job.catch(() => undefined);
      try {
        return await job;
      } finally {
        if (session === switchSession) {
          this.switching = --queuedSwitches > 0;
        }
      }
    },
    setLoading(loading: boolean) {
      this.loading = loading;
    },
    setMessage(message: string, routeNotice = false) {
      this.routeNotice = routeNotice;
      this.message = message;
    },
    clearMessage() {
      this.routeNotice = false;
      this.message = '';
    },
    resetContentNavigation() {
      this.contentMenus = [];
      this.systemMenus = createSystemMenus([], this.currentAdmin, this.capabilities);
    },
    isCurrentSiteManageableMenu(menuId: number | string) {
      return isManageableContentMenu(this.contentMenus, menuId);
    },
    async loadSites(options: LoadSitesOptions = {}) {
      this.loading = true;
      if (!this.routeNotice) {
        this.message = '';
      }
      const session = switchSession;
      let contextSequence = switchRequestSeq;
      try {
        await switchQueue;
        if (session !== switchSession) return;
        contextSequence = switchRequestSeq;
        const currentContext = await http.get<SiteContextApiResponse>('/api/admin/site-context/current');
        const sites = await http.get<SiteManagementApiSite[]>('/api/admin/v2/sites');
        if (session !== switchSession) return;
        this.authenticated = true;
        this.sites = sites.map(siteSummaryFromApi);
        if (contextSequence !== switchRequestSeq) return;
        this.applyCurrentContext(currentContext);
        if (options.autoSelectIfMissing) {
          await this.ensureCurrentSiteSelection();
        }
        if (this.currentSite) {
          await this.loadContentNavigation(undefined, true);
        } else {
          this.resetContentNavigation();
        }
      } catch (error) {
        if (session === switchSession && contextSequence === switchRequestSeq && !isAuthenticationError(error)) {
          this.authenticated = false;
          this.currentSite = null;
          this.sites = [];
          this.resetContentNavigation();
          if (!this.message) {
            this.setMessage('站点上下文加载失败，请稍后重试');
          }
        }
        throw error;
      } finally {
        this.loading = false;
      }
    },
    async refreshCurrentContext() {
      const session = switchSession;
      await switchQueue;
      if (session !== switchSession) return;
      const contextSequence = switchRequestSeq;
      const currentContext = await http.get<SiteContextApiResponse>('/api/admin/site-context/current');
      if (session !== switchSession || contextSequence !== switchRequestSeq) return;
      this.authenticated = true;
      if (!currentContext || currentContext.globalMode || currentContext.siteId == null) {
        if (currentContext && typeof currentContext.currentUser !== 'undefined') {
          this.currentAdmin = currentContext.currentUser ?? null;
        }
        this.currentSite = null;
        this.resetContentNavigation();
        return;
      }
      this.applyCurrentContext(currentContext);
      if (this.currentSite) {
        await this.loadContentNavigation(undefined, true);
      } else {
        this.resetContentNavigation();
      }
    },
    applyCurrentContext(context: SiteContextApiResponse | null) {
      if (context && typeof context.currentUser !== 'undefined') {
        this.currentAdmin = context.currentUser ?? null;
      }
      if (context && context.capabilities) {
        this.capabilities = context.capabilities;
      }
      if (context && !context.globalMode && context.siteId != null) {
        const fallback = this.sites.find((site) => String(site.id) === String(context.siteId)) ?? null;
        this.currentSite = siteSummaryFromContext(context, fallback);
        rememberSiteId(this.currentAdmin, this.currentSite.id);
        return;
      }
      const current = this.sites.find((site) => String(site.id) === String(this.currentSite?.id));
      this.currentSite = current ?? null;
    },
    async ensureCurrentSiteSelection() {
      if (this.currentSite || !this.sites.length) {
        return;
      }
      const rememberedSiteId = readRememberedSiteId(this.currentAdmin);
      const preferredSite = this.sites.find((site) => String(site.id) === String(rememberedSiteId)) ?? this.sites[0];
      if (!preferredSite) {
        return;
      }
      await this.switchSite(preferredSite.id);
    },
    async loadContentNavigation(activeSwitchSeq?: number, propagateError = false) {
      const requestSeq = ++navigationRequestSeq;
      const contextSequence = activeSwitchSeq ?? switchRequestSeq;
      try {
        // 并行加载内容导航和动态系统菜单
        const [navigation, dynamicMenus] = await Promise.all([
          http.get<ContentNavigationApiResponse>('/api/admin/v2/cms/navigation'),
          loadDynamicSystemMenus().catch(() => null)
        ]);

        if (requestSeq !== navigationRequestSeq || contextSequence !== switchRequestSeq) {
          return null;
        }

        this.contentMenus = navigation.tree ?? navigation.menus ?? [];

        // 优先使用动态菜单，如果为空则回退到静态配置
        if (dynamicMenus && dynamicMenus.length > 0) {
          this.systemMenus = dynamicMenus;
        } else if (isSuperAdminUser(this.currentAdmin) || this.currentAdmin == null) {
          // 超管（或未知/未识别身份）回退全量静态菜单
          this.systemMenus = createSystemMenus(this.contentMenus, this.currentAdmin, this.capabilities);
        } else {
          // 严格隐藏：非超管未配置 menu_setting 时，仅展示工作台基础菜单
          this.systemMenus = createSystemMenus(this.contentMenus, this.currentAdmin, this.capabilities)
            .filter((menu) => menu.key === 'workspace');
        }

        return navigation;
      } catch (error) {
        if (requestSeq !== navigationRequestSeq || contextSequence !== switchRequestSeq) {
          return null;
        }
        this.resetContentNavigation();
        if (isAuthenticationError(error)) {
          throw error;
        }
        this.setMessage('栏目导航加载失败，请稍后重试');
        if (propagateError) {
          throw error;
        }
        return null;
      }
    }
  }
});

function siteSummaryFromApi(site: SiteManagementApiSite): SiteSummary {
  return {
    id: site.siteId,
    name: site.projectName,
    code: site.projectCode,
    domain: site.siteDomain,
    language: site.defaultLanguage,
    rootMenuId: site.rootMenuId,
    status: site.status
  };
}

function siteSummaryFromContext(context: SiteContextApiResponse, fallback: SiteSummary | null): SiteSummary {
  return {
    id: context.siteId,
    name: context.siteName,
    code: context.siteCode,
    domain: fallback?.domain,
    language: fallback?.language,
    rootMenuId: context.rootMenuId,
    status: fallback?.status
  };
}
