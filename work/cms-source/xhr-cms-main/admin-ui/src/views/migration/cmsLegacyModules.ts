export type LegacyModuleStatus = 'vue-ready' | 'api-needed' | 'planned';
export type LegacyModuleSection = 'cms' | 'system';

export interface CmsLegacyModule {
  key: string;
  title: string;
  group: string;
  description: string;
  legacyRoutes: string[];
  targetRoute: string;
  status: LegacyModuleStatus;
  nextApiWork: string[];
  menuIcon: string;
}

export interface LegacyModuleMenuItem {
  key: string;
  moduleKey: string;
  label: string;
  path: string;
  category: string;
  icon: string;
  section: LegacyModuleSection;
}

export const cmsLegacyModules: CmsLegacyModule[] = [
  {
    key: 'menus',
    title: '栏目管理',
    group: '内容管理',
    description: 'Vue3 已完整承接栏目树、栏目编辑、模板路径、显示状态和 SEO 配置；旧路径已进入删除清单候选，删除前仅用于历史核对。',
    legacyRoutes: [
      '/admin/menu/index',
      '/admin/menu/other',
      '/admin/menu/open',
      '/admin/menu/add/{parentId}',
      '/admin/menu/edit/{id}',
      '/admin/menu/seo/{id}',
      '/admin/menu/open/add/{parentId}',
      '/admin/menu/open/edit/{id}',
      '/admin/menu_manage/{id}'
    ],
    targetRoute: '/admin/v2/cms/menus',
    status: 'vue-ready',
    nextApiWork: ['补齐拖拽排序和批量操作体验', '继续核对历史旧路径与培训文档，避免误用旧入口'],
    menuIcon: 'Collection'
  },
  {
    key: 'content',
    title: '内容管理',
    group: '内容管理',
    description: 'Vue3 已按当前站点栏目树承接内容列表、动态字段编辑、排序、发布、推荐关系、SEO、内容历史、审核详情和预览诊断；旧内容入口已进入删除清单候选，仅用于删除前核对。',
    legacyRoutes: [
      '/admin/content/history/list/{classId}/{infoId}',
      '/admin/model_manage/{classid}',
      '/admin/model_manage/{classid}/edit/{id}',
      '/admin/model_manage/{classid}/add',
      '/admin/model_manage/{classid}/seo/{id}',
      '/admin/check/index',
      '/admin/check/index/{classId}',
      '/admin/check/detail',
      '/admin/check/check'
    ],
    targetRoute: '/admin/v2/cms/content',
    status: 'vue-ready',
    nextApiWork: ['旧内容入口进入删除清单候选，继续核对培训材料和删除前运维确认', '继续用真实样本站点扩充内容历史、审核和预览诊断回归样本'],
    menuIcon: 'Grid'
  },
  {
    key: 'users',
    title: '用户权限',
    group: '系统管理',
    description: 'Vue3 已完整承接管理员账号、管理员组、站点权限、菜单权限、全局权限、未知历史权限保留、个人资料和修改密码。',
    legacyRoutes: [
      '/admin/user/index',
      '/admin/user/add',
      '/admin/user/edit/{uid}',
      '/admin/user_group/index',
      '/admin/user_group/add/{parentId}',
      '/admin/user_group/edit/{id}',
      '/admin/profile/user',
      '/admin/profile/password'
    ],
    targetRoute: '/admin/v2/system/users',
    status: 'vue-ready',
    nextApiWork: ['旧用户、用户组和个人资料入口进入删除清单候选，完成培训材料与权限运维手册路径清理', '继续用真实历史管理员组样本扩充未知权限保留回归'],
    menuIcon: 'UserFilled'
  },
  {
    key: 'logs',
    title: '日志',
    group: '系统管理',
    description: '已迁移为 Vue3 原生日志查询页面，承接系统日志列表、筛选和详情查看。',
    legacyRoutes: ['/admin/log/index', '/admin/log/details/{id}', '/admin/log-operation/index/{classId}/{infoId}'],
    targetRoute: '/admin/v2/system/logs',
    status: 'vue-ready',
    nextApiWork: ['补齐操作审计详情与关联资源跳转信息', '增加日志导出能力'],
    menuIcon: 'Document'
  },
  {
    key: 'settings',
    title: '系统设置',
    group: '系统管理',
    description: 'Vue3 已承接完整旧 options 目录、历史兼容确认、配置变更审计、上传存储和安全策略阻断，以及品牌登录配置写回。',
    legacyRoutes: ['/admin/system/setting'],
    targetRoute: '/admin/v2/system/settings',
    status: 'vue-ready',
    nextApiWork: ['旧入口进入可删除候选清单，删除前按完整门禁执行回归、培训文档清理和运维确认'],
    menuIcon: 'Setting'
  }
];

export const legacyToCoverageStatusMap = Object.freeze({
  'vue-ready': 'VUE3_COMPLETE',
  'api-needed': 'VUE3_PARTIAL',
  planned: 'LEGACY_FALLBACK'
} as const);

export const legacyToCoverageModuleTitleMap: Readonly<Partial<Record<CmsLegacyModule['key'], string>>> = Object.freeze({
  users: '管理员与权限',
  logs: '系统日志',
  settings: '系统配置'
} as const);

export const legacyModuleKeys = cmsLegacyModules.map((module) => module.key);

export const legacyModuleByKey = Object.freeze(Object.fromEntries(
  cmsLegacyModules.map((module) => [module.key, module])
) as Record<string, CmsLegacyModule>);

function legacyModuleSection(targetRoute: string): LegacyModuleSection {
  return targetRoute.startsWith('/admin/v2/system/') ? 'system' : 'cms';
}

export function legacyModuleCoverageTitle(module: CmsLegacyModule): string {
  return legacyToCoverageModuleTitleMap[module.key] ?? module.title;
}

export function legacyModuleCoverageStatus(status: LegacyModuleStatus) {
  return legacyToCoverageStatusMap[status];
}

export function legacyModuleMenuItems(group?: string): LegacyModuleMenuItem[] {
  return cmsLegacyModules
    .filter((module) => !group || module.group === group || legacyModuleSection(module.targetRoute) === group)
    .map((module) => {
      const section = legacyModuleSection(module.targetRoute);

      return {
        key: `${section}-${module.key}`,
        moduleKey: module.key,
        label: module.title,
        path: module.targetRoute,
        category: module.group,
        icon: module.menuIcon,
        section
      };
    });
}
