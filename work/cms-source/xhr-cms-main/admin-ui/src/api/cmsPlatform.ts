import { http } from './http';
import { useSiteContextStore } from '@/stores/siteContext';

/** 分类/元数据节点（对齐后端 ProductOneshotEntity/NewsOneshotEntity） */
export interface CategoryNode {
  id: number;
  className: string;
  classEnName?: string;
  parentId: number;
  sort: number;
  pageType: number;
  status?: number;
  modelField?: string;
  modelList?: string;
  modelSearch?: string;
  menuField?: string;
  setting?: string;
  isDeleted?: boolean;
  [k: string]: unknown;
}

/** 内容分页返回 */
export interface ContentListResult<T = Record<string, unknown>> {
  list: T[];
  total: number;
  page: number;
  size: number;
}

function createCategoryApi(base: 'product-category' | 'news-category' | 'solution-category' | 'case-category') {
  const root = `/api/admin/v2/cms-platform/${base}`;
  return {
    tree: (parentId = 0) => http.get<CategoryNode[]>(`${root}/tree`, { params: { parentId } }),
    all: () => http.get<CategoryNode[]>(`${root}/all`),
    detail: (id: number) => http.get<CategoryNode>(`${root}/${id}`),
    create: (data: Partial<CategoryNode>) => http.post<CategoryNode>(root, data),
    update: (id: number, data: Partial<CategoryNode>) => http.put<CategoryNode>(`${root}/${id}`, data),
    remove: (id: number) => http.delete<number>(`${root}/${id}`),
    sort: (id: number, sort: number) => http.put<boolean>(`${root}/${id}/sort`, undefined, { params: { sort } })
  };
}

/**
 * 按站点隔离的类别工厂：调用时从当前站点上下文读取 siteId 附加到 tree/all/create。
 * 供表单模块使用（t_from_oneshot.site_project_id = 站点ID）。
 */
function createSiteCategoryApi(base: 'form-category') {
  const root = `/api/admin/v2/cms-platform/${base}`;
  const siteId = () => {
    const store = useSiteContextStore();
    const id = store.currentSite?.id;
    return id ? Number(id) : 0;
  };
  return {
    tree: (parentId = 0) => http.get<CategoryNode[]>(`${root}/tree`, { params: { parentId, siteId: siteId() } }),
    all: () => http.get<CategoryNode[]>(`${root}/all`, { params: { siteId: siteId() } }),
    detail: (id: number) => http.get<CategoryNode>(`${root}/${id}`),
    create: (data: Partial<CategoryNode>) => http.post<CategoryNode>(root, data, { params: { siteId: siteId() } }),
    update: (id: number, data: Partial<CategoryNode>) => http.put<CategoryNode>(`${root}/${id}`, data),
    remove: (id: number) => http.delete<number>(`${root}/${id}`),
    sort: (id: number, sort: number) => http.put<boolean>(`${root}/${id}/sort`, undefined, { params: { sort } })
  };
}

function createCenterApi(base: 'product-center' | 'news-center' | 'form-center' | 'solution-center' | 'case-center') {
  const root = `/api/admin/v2/cms-platform/${base}`;
  return {
    contents: (classId: number, params: Record<string, unknown>) =>
      http.get<ContentListResult>(`${root}/contents`, { params: { classId, ...params } }),
    detail: (id: number) => http.get<Record<string, unknown>>(`${root}/${id}`),
    create: (data: Record<string, unknown>) => http.post<Record<string, unknown>>(root, data),
    update: (id: number, data: Record<string, unknown>) => http.put<boolean>(`${root}/${id}`, data),
    remove: (id: number) => http.delete<boolean>(`${root}/${id}`),
    batchOption: (ids: number[], option: string, classId?: number, status?: number, note?: string) =>
      http.post<number>(`${root}/batch-option`, undefined, { params: { classId, ids: ids.join(','), option, status, note } })
  };
}

/** 产品配置 */
export const productCategoryApi = createCategoryApi('product-category');
/** 新闻配置 */
export const newsCategoryApi = createCategoryApi('news-category');
/** 解决方案配置 */
export const solutionCategoryApi = createCategoryApi('solution-category');
/** 案例配置 */
export const caseCategoryApi = createCategoryApi('case-category');
/** 表单配置（按站点隔离） */
export const formCategoryApi = createSiteCategoryApi('form-category');
/** 产品中心 */
export const productCenterApi = createCenterApi('product-center');
/** 新闻中心 */
export const newsCenterApi = createCenterApi('news-center');
/** 解决方案 */
export const solutionCenterApi = createCenterApi('solution-center');
/** 案例中心 */
export const caseCenterApi = createCenterApi('case-center');
/** 表单内容 */
export const formCenterApi = createCenterApi('form-center');

export interface ContentPageOption {
  value: number;
  label: string;
}

/** 推送配置（独立表 cms_platform_push_config） */
export interface PlatformPushConfig {
  id?: number;
  configType: 'product' | 'news' | 'form' | 'solution' | 'case';
  categoryId: number;
  siteId: number;
  siteColumnId: number;
  targetStatus: number;
  fieldMapping?: string;
  createTime?: string;
}

export interface SiteCatalogOption {
  id: number;
  name: string;
  code: string;
  rootMenuId: number;
}

export interface SiteColumnNode {
  id: number;
  className: string;
  parentId: number;
  modelField?: string;
}

function createPushConfigApi(base: 'product' | 'news' | 'form' | 'solution' | 'case') {
  const root = '/api/admin/v2/cms-platform/push-config';
  return {
    list: (categoryId: number) => http.get<PlatformPushConfig[]>(`${root}/list`, { params: { configType: base, categoryId } }),
    create: (data: Partial<PlatformPushConfig>) => http.post<PlatformPushConfig>(root, data),
    update: (id: number, data: Partial<PlatformPushConfig>) => http.put<PlatformPushConfig>(`${root}/${id}`, data),
    remove: (id: number) => http.delete<boolean>(`${root}/${id}`)
  };
}

export const productPushConfigApi = createPushConfigApi('product');
export const newsPushConfigApi = createPushConfigApi('news');
export const formPushConfigApi = createPushConfigApi('form');
export const solutionPushConfigApi = createPushConfigApi('solution');
export const casePushConfigApi = createPushConfigApi('case');

/** 定时发布配置 */
export interface SchedulePublishConfig {
  id?: number;
  siteId?: number;
  cronExpression?: string;
  cronDescription?: string;
  columnIds?: string;
  columnNames?: string;
  remark?: string;
  enabled?: number;
  createTime?: string;
  updateTime?: string;
}

/** 定时发布执行历史 */
export interface ScheduleRunHistory {
  id: number;
  scheduleId: number;
  runTime?: string;
  status?: string;
  message?: string;
}

/** 定时发布管理（站点隔离） */
export const schedulePublishApi = {
  list: (siteId: number, keyword?: string) =>
    http.get<SchedulePublishConfig[]>('/api/admin/v2/cms/schedule-publish/list', { params: { siteId, keyword } }),
  detail: (id: number) => http.get<SchedulePublishConfig>(`/api/admin/v2/cms/schedule-publish/${id}`),
  create: (siteId: number, data: Partial<SchedulePublishConfig>) =>
    http.post<SchedulePublishConfig>('/api/admin/v2/cms/schedule-publish', data, { params: { siteId } }),
  update: (id: number, data: Partial<SchedulePublishConfig>) =>
    http.put<boolean>(`/api/admin/v2/cms/schedule-publish/${id}`, data),
  remove: (id: number) => http.delete<boolean>(`/api/admin/v2/cms/schedule-publish/${id}`),
  toggleEnabled: (id: number, enabled: number) =>
    http.put<boolean>(`/api/admin/v2/cms/schedule-publish/${id}/enabled`, undefined, { params: { enabled } }),
  history: (id: number) => http.get<ScheduleRunHistory[]>(`/api/admin/v2/cms/schedule-publish/${id}/history`),
  validateCron: (cron: string) => http.get<boolean>('/api/admin/v2/cms/schedule-publish/validate', { params: { cron } })
};

/** 推送设置所需的站点/站点栏目目录 */
export const siteCatalogApi = {
  sites: () => http.get<SiteCatalogOption[]>('/api/admin/v2/cms-platform/site-catalog/sites'),
  columns: (siteId: number) =>
    http.get<SiteColumnNode[]>(`/api/admin/v2/cms-platform/site-catalog/columns`, { params: { siteId } })
};

/** 内容中台推送记录 */
export interface PlatformPushRecord {
  id: number;
  sourceContentId: number;
  contentType: string;
  siteId?: number;
  columnId?: number;
  targetContentId?: number;
  fieldMapping?: string;
  fieldContent?: string;
  pushConfigId?: number;
  pushStatus: string;
  /** 推送类型 INSERT新增 UPDATE更新 */
  pushType?: string;
  targetStatus?: number;
  pushTime?: string;
  pushUserId?: number;
  pushUser?: string;
  errorMessage?: string;
}

/** 一键推送执行 + 推送记录 */
export const platformPushApi = {
  execute: (payload: { configType: string; pushConfigId: number; contentIds: number[] }) =>
    http.post<number>('/api/admin/v2/cms-platform/push/execute', payload),
  records: (configType: string, sourceContentId: number) =>
    http.get<PlatformPushRecord[]>('/api/admin/v2/cms-platform/push/records', { params: { configType, sourceContentId } })
};

/** 审批（写入共享审核表 t_check_record） */
export const platformAuditApi = {
  audit: (payload: { configType: string; categoryId: number; ids: number[]; status: number; note?: string }) =>
    http.post<number>('/api/admin/v2/cms-platform/audit', payload)
};

// ==================== 表单服务接口（站点服务暴露） ====================

/** 站点级表单服务认证 key（每站点一把） */
export interface FormServiceKeyView {
  siteId: number;
  hasKey: boolean;
  apiKey: string | null;
  enabled: boolean;
  remark: string | null;
  rotateTime: string | null;
}

export interface FormServiceEndpointParam {
  name: string;
  required: boolean;
  desc: string;
}

export interface FormServiceEndpoint {
  name: string;
  method: string;
  path: string;
  params: FormServiceEndpointParam[];
  sampleRequest: string;
  sampleResponse: string;
}

export interface FormServiceCategory {
  id: number;
  name: string;
  pageType: number;
  captchaRequired: boolean;
  fieldCount: number;
}

export interface FormServiceDocView {
  baseUrl: string;
  hasKey: boolean;
  keyEnabled: boolean;
  categories: FormServiceCategory[];
  endpoints: FormServiceEndpoint[];
}

const FORM_SERVICE_ROOT = '/api/admin/v2/cms-platform/form-service';

/**
 * 表单服务接口配置：站点 key 的读取/轮换/启停 + 动态接口文档。
 * 站点服务上暴露的表单接口以该 key 鉴权，独立于后台登录。
 */
export const formServiceApi = {
  getKey: (siteId: number) =>
    http.get<FormServiceKeyView>(`${FORM_SERVICE_ROOT}/key`, { params: { siteId } }),
  rotateKey: (siteId: number) =>
    http.post<FormServiceKeyView>(`${FORM_SERVICE_ROOT}/key`, undefined, { params: { siteId } }),
  updateKey: (data: { siteId: number; enabled?: boolean; remark?: string }) =>
    http.put<FormServiceKeyView>(`${FORM_SERVICE_ROOT}/key`, data),
  getDoc: (siteId: number) =>
    http.get<FormServiceDocView>(`${FORM_SERVICE_ROOT}/doc`, { params: { siteId } })
};
