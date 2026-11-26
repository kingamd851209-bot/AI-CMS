import { http } from '@/api/http';

export interface SiteV2Item {
  siteId: number;
  parentSiteId?: number;
  projectName: string;
  projectCode: string;
  siteDomain?: string;
  siteMode: string;
  siteDescription?: string;
  defaultLanguage?: string;
  uiPackageStatus?: string;
  createTime?: string;
  rootMenuId?: number;
  siteServiceIp?: string;
  siteServicePort?: number;
}

export interface CreateSiteRequest {
  siteName: string;
  siteCode: string;
  siteDomain?: string;
  siteMode?: string;
  siteDescription?: string;
  defaultLanguage?: string;
  siteServiceIp?: string;
  parentSiteId?: number | null;
  siteServicePort?: number;
}

export interface UpdateSiteRequest {
  siteName?: string;
  siteCode?: string;
  siteDomain?: string;
  siteMode?: string;
  siteDescription?: string;
  defaultLanguage?: string;
  siteServiceIp?: string;
  parentSiteId?: number | null;
  siteServicePort?: number;
}

export async function listSites(): Promise<SiteV2Item[]> {
  return http.get<SiteV2Item[]>('/api/admin/v2/sites');
}

export async function createSite(request: CreateSiteRequest): Promise<SiteV2Item> {
  return http.post<SiteV2Item>('/api/admin/v2/sites', request);
}

export async function updateSite(siteId: number, request: UpdateSiteRequest): Promise<SiteV2Item> {
  return http.put<SiteV2Item>(`/api/admin/v2/sites/${siteId}`, request);
}

export async function deleteSite(siteId: number): Promise<void> {
  return http.delete(`/api/admin/v2/sites/${siteId}`);
}

export async function getSite(siteId: number): Promise<SiteV2Item> {
  return http.get<SiteV2Item>(`/api/admin/v2/sites/${siteId}`);
}

export interface LocalIpList {
  ips: string[];
  defaultIp: string | null;
}

export async function listLocalIps(): Promise<LocalIpList> {
  return http.get<LocalIpList>('/api/admin/v2/site-service/local-ips');
}

export interface PortAvailability {
  available: boolean;
  suggestedPort: number | null;
  message: string | null;
}

/** 保存站点前检查服务端口是否已被其它站点占用（依据 site_service_port 字段） */
export async function checkSiteServicePort(port: number, excludeSiteId?: number): Promise<PortAvailability> {
  return http.get<PortAvailability>('/api/admin/v2/site-service/port-availability', {
    params: { port, excludeSiteId }
  });
}

export interface MenuTreeNode {
  id: number;
  parentId: number;
  className: string;
  children: MenuTreeNode[];
}

export interface MenuTranslation {
  menuId: number;
  translatedName: string;
}

export interface GenerateI18nSiteRequest {
  targetLanguage: string;
  engine?: 'SPRING_AI';
  siteName: string;
  siteCode: string;
  siteDomain?: string;
  /** 站点服务端口 */
  siteServicePort?: number;
  siteServiceIp?: string;
  selectedMenuIds: number[];
  /** 翻译预览结果，生成时直接复用；未覆盖的栏目后台才会调用 AI */
  menuTranslations?: MenuTranslation[];
}

export interface I18nSiteGenerationResult {
  siteId: number;
  siteName: string;
  siteCode: string;
  translatedMenuCount: number;
  /** 生成后创建的多语言站点任务ID（用于跳转任务明细） */
  taskId?: number | null;
}

export async function getSiteMenuTree(siteId: number): Promise<MenuTreeNode[]> {
  return http.get<MenuTreeNode[]>(`/api/admin/v2/sites/${siteId}/menu-tree`);
}

export async function generateI18nSite(siteId: number, request: GenerateI18nSiteRequest): Promise<I18nSiteGenerationResult> {
  return http.post<I18nSiteGenerationResult>(`/api/admin/v2/sites/${siteId}/generate-i18n-site`, request, {
    timeout: 310000
  });
}

export interface TranslateMenusPreviewRequest {
  targetLanguage: string;
  menuIds: number[];
  engine?: 'SPRING_AI';
}

export interface TranslateMenusTaskResponse {
  taskId: string | null;
  prompt: string | null;
}

export async function createTranslateMenusTask(siteId: number, request: TranslateMenusPreviewRequest): Promise<TranslateMenusTaskResponse> {
  return http.post<TranslateMenusTaskResponse>(`/api/admin/v2/sites/${siteId}/translate-menus-preview/task`, request, { timeout: 15000 });
}


export interface TranslatedMenuPreview {
  menuId: number;
  originalName: string;
  translatedName: string;
}

export interface TranslateMenusPreviewResult {
  items: TranslatedMenuPreview[];
  prompt: string | null;
  rawResponse: string | null;
}

export async function translateMenusPreview(siteId: number, request: TranslateMenusPreviewRequest): Promise<TranslateMenusPreviewResult> {
  // 异步任务接口负责 AI 翻译；该兼容接口保留给旧调用方。
  return http.post<TranslateMenusPreviewResult>(`/api/admin/v2/sites/${siteId}/translate-menus-preview`, request, {
    timeout: 310000
  });
}

/** Headless 站点接入认证配置（每站点一对 appKey/appSecret） */
export interface SiteHeadlessAuthView {
  siteId: number;
  hasAuth: boolean;
  appKey: string | null;
  appSecret: string | null;
  enabled: boolean;
  remark: string | null;
  rotateTime: string | null;
}

const headlessAuthRoot = (siteId: number) => `/api/admin/v2/sites/${siteId}/headless-auth`;

export async function getHeadlessAuth(siteId: number): Promise<SiteHeadlessAuthView> {
  return http.get<SiteHeadlessAuthView>(headlessAuthRoot(siteId));
}

export async function rotateHeadlessAuth(siteId: number): Promise<SiteHeadlessAuthView> {
  return http.post<SiteHeadlessAuthView>(`${headlessAuthRoot(siteId)}/rotate`);
}

export async function updateHeadlessAuth(
  siteId: number,
  request: { enabled?: boolean; remark?: string }
): Promise<SiteHeadlessAuthView> {
  return http.put<SiteHeadlessAuthView>(headlessAuthRoot(siteId), request);
}
