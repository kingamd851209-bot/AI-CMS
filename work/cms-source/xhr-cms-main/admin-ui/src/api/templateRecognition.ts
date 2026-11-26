import { http } from '@/api/http';

export interface TemplateFileInfo {
  relativePath: string;
  fileName: string;
}

export interface SiteInfo {
  id: number;
  projectName: string;
  projectCode: string;
}

export async function getCommonTemplateFiles(siteId: number): Promise<TemplateFileInfo[]> {
  return http.get<TemplateFileInfo[]>(`/api/admin/v2/sites/${siteId}/template-files/common`);
}

export async function getTemplateFiles(siteId: number, templateType?: string): Promise<TemplateFileInfo[]> {
  const params: Record<string, string> = {};
  if (templateType) params.templateType = templateType;
  return http.get<TemplateFileInfo[]>(`/api/admin/v2/sites/${siteId}/template-files`, { params });
}

export async function getSiteInfo(siteId: number): Promise<SiteInfo> {
  return http.get<SiteInfo>(`/api/admin/v2/sites/${siteId}/info`);
}

export async function saveTemplateContent(siteId: number, templatePath: string, content: string): Promise<string> {
  return http.post<string>(`/api/admin/v2/sites/${siteId}/template-content`, {
    templatePath,
    content
  });
}

export async function getTemplateContent(siteId: number, templatePath: string): Promise<string | null> {
  return http.get<string | null>(`/api/admin/v2/sites/${siteId}/template-content`, {
    params: { templatePath }
  });
}

// 内容模板保存目录：按栏目树结构生成的相对子目录（如 about/news/xwzx）
export async function getColumnTemplateDir(siteId: number, columnId: number): Promise<string> {
  const dir = await http.get<string>(`/api/admin/v2/sites/${siteId}/column-template-dir`, {
    params: { columnId }
  });
  return dir ?? '';
}

// ==================== 模板预览 API ====================

export interface TemplatePreviewResult {
  renderedHtml: string;
  staticPreviewUrl: string | null;
}

// 模板渲染可能较慢（首次构建引擎/查询数据）；全局默认 15s 超时会把请求中止，Network 里显示为 canceled。
const TEMPLATE_PREVIEW_TIMEOUT_MS = 600000;

/**
 * 预览公共元素模板
 */
export async function previewCommonTemplate(
  siteId: number,
  templatePath: string
): Promise<TemplatePreviewResult> {
  return http.get<TemplatePreviewResult>(
    `/api/admin/v2/sites/${siteId}/template-preview/common`,
    { params: { templatePath }, timeout: TEMPLATE_PREVIEW_TIMEOUT_MS }
  );
}

/**
 * 预览列表页模板
 */
export async function previewListTemplate(
  siteId: number,
  templatePath: string,
  columnId: number
): Promise<TemplatePreviewResult> {
  return http.get<TemplatePreviewResult>(
    `/api/admin/v2/sites/${siteId}/template-preview/list`,
    { params: { templatePath, columnId }, timeout: TEMPLATE_PREVIEW_TIMEOUT_MS }
  );
}

/**
 * 预览详情页模板
 */
export async function previewDetailTemplate(
  siteId: number,
  templatePath: string,
  columnId: number,
  contentId?: number
): Promise<TemplatePreviewResult> {
  const params: Record<string, string | number> = { templatePath, columnId };
  if (contentId) params.contentId = contentId;
  return http.get<TemplatePreviewResult>(
    `/api/admin/v2/sites/${siteId}/template-preview/detail`,
    { params, timeout: TEMPLATE_PREVIEW_TIMEOUT_MS }
  );
}

/**
 * 自动预览模板（根据路径自动判断模板类型）
 */
export async function previewTemplate(
  siteId: number,
  templatePath: string,
  columnId?: number,
  contentId?: number
): Promise<TemplatePreviewResult> {
  const params: Record<string, string | number> = { templatePath };
  if (columnId) params.columnId = columnId;
  if (contentId) params.contentId = contentId;
  return http.get<TemplatePreviewResult>(
    `/api/admin/v2/sites/${siteId}/template-preview/auto`,
    { params, timeout: TEMPLATE_PREVIEW_TIMEOUT_MS }
  );
}

/**
 * 预览模板内容（接收模板字符串）
 * 用于编辑器实时预览，无需保存模板文件
 */
export async function previewTemplateContent(
  siteId: number,
  templateContent: string,
  columnId?: number,
  contentId?: number
): Promise<TemplatePreviewResult> {
  return http.post<TemplatePreviewResult>(
    `/api/admin/v2/sites/${siteId}/template-preview/content`,
    {
      templateContent,
      columnId,
      contentId
    },
    { timeout: TEMPLATE_PREVIEW_TIMEOUT_MS }
  );
}

/**
 * 获取模板文件的绝对路径
 * 用于 AI 辅助修复功能
 */
export async function getTemplateAbsolutePath(
  siteId: number,
  templatePath: string
): Promise<{ absolutePath: string | null; templatePath: string }> {
  return http.get<{ absolutePath: string | null; templatePath: string }>(
    `/api/admin/v2/sites/${siteId}/template-preview/template-absolute-path`,
    { params: { templatePath } }
  );
}

// ==================== 站点服务 API ====================

export interface SiteServiceView {
  siteId: number;
  projectName: string;
  projectCode: string;
  siteDomain: string | null;
  defaultLanguage: string | null;
  status: string;
  hasUiPackage: boolean;
  hasTemplates: boolean;
  uiServiceStatus: 'running' | 'available' | 'offline';
  uiServiceUrl: string | null;
  siteServicePort: number | null;
  siteServiceStatus: 'RUNNING' | 'STOPPED';
  siteServiceUrl: string | null;
  siteServicePid: number | null;
}

export interface SiteServiceStatus {
  status: 'RUNNING' | 'STOPPED';
  port: number | null;
  accessUrl: string | null;
  errorMessage: string | null;
}

/** 站点服务类型：统一进程 / 独立进程 */
export type SiteServiceMode = 'unified' | 'independent';

export interface GlobalParamsView {
  javaPath: string | null;
  jarPath: string | null;
  workDir: string | null;
  jvmArgs: string;
  programArgs: string;
}

export interface SiteServiceModeView {
  mode: SiteServiceMode;
  globalParams: GlobalParamsView;
  /** 未配置时实际使用的默认值（javaPath/jarPath/workDir） */
  defaults: Record<string, string>;
  /** 切换服务模式后重启成功的站点数；非切换场景为 null */
  restarted: number | null;
  /** 切换服务模式后重启失败的站点数；非切换场景为 null */
  failed: number | null;
}

export interface SiteLaunchParamsView {
  /** 站点级覆盖，仅包含已设置的非空字段 */
  override: Record<string, string>;
  globalParams: GlobalParamsView;
  defaults: Record<string, string>;
}

export interface TemplateFileView {
  relativePath: string;
  fileName: string;
  type: string;
}

export interface TemplateRenderResult {
  renderedHtml: string | null;
  errorMessage: string | null;
  errorCode: string | null;
  columnName: string | null;
  templatePath: string | null;
}

/**
 * 获取站点列表
 */
export async function getSiteServiceList(): Promise<SiteServiceView[]> {
  return http.get<SiteServiceView[]>('/api/admin/v2/site-service');
}

/**
 * 访问站点 - 根据根栏目导航渲染对应模板
 */
export async function visitSite(siteId: number): Promise<TemplateRenderResult> {
  return http.get<TemplateRenderResult>(`/api/admin/v2/site-service/${siteId}/visit`);
}

/**
 * 获取站点模板列表
 */
export async function getSiteTemplates(
  siteId: number,
  type?: string
): Promise<TemplateFileView[]> {
  const params: Record<string, string> = {};
  if (type) params.type = type;
  return http.get<TemplateFileView[]>(`/api/admin/v2/site-service/${siteId}/templates`, { params });
}

/**
 * 渲染指定模板
 */
export async function renderSiteTemplate(
  siteId: number,
  templatePath?: string,
  columnId?: number
): Promise<TemplateRenderResult> {
  const params: Record<string, string | number> = {};
  if (templatePath) params.templatePath = templatePath;
  if (columnId) params.columnId = columnId;
  return http.get<TemplateRenderResult>(`/api/admin/v2/site-service/${siteId}/render`, { params });
}

/**
 * 渲染栏目列表页
 */
export async function renderSiteListTemplate(
  siteId: number,
  columnId: number,
  templatePath?: string
): Promise<TemplateRenderResult> {
  const params: Record<string, string> = {};
  if (templatePath) params.templatePath = templatePath;
  return http.get<TemplateRenderResult>(
    `/api/admin/v2/site-service/${siteId}/render-list/${columnId}`,
    { params }
  );
}

/**
 * 渲染内容详情页
 */
export async function renderSiteDetailTemplate(
  siteId: number,
  columnId: number,
  contentId: number,
  templatePath?: string
): Promise<TemplateRenderResult> {
  const params: Record<string, string> = {};
  if (templatePath) params.templatePath = templatePath;
  return http.get<TemplateRenderResult>(
    `/api/admin/v2/site-service/${siteId}/render-detail/${columnId}/${contentId}`,
    { params }
  );
}

export interface UiServiceStatus {
  status: 'running' | 'available' | 'offline' | 'error';
  message: string | null;
}

export interface UiServiceAccessUrl {
  baseUrl: string | null;
  indexUrl: string | null;
  errorMessage: string | null;
}

/**
 * 启动站点的 UI 包服务
 */
export async function startUiService(siteId: number): Promise<UiServiceStatus> {
  return http.post<UiServiceStatus>(`/api/admin/v2/site-service/${siteId}/ui-service/start`);
}

/**
 * 停止站点的 UI 包服务
 */
export async function stopUiService(siteId: number): Promise<UiServiceStatus> {
  return http.post<UiServiceStatus>(`/api/admin/v2/site-service/${siteId}/ui-service/stop`);
}

/**
 * 获取站点的 UI 服务访问地址
 */
export async function getUiServiceAccessUrl(siteId: number): Promise<UiServiceAccessUrl> {
  return http.get<UiServiceAccessUrl>(`/api/admin/v2/site-service/${siteId}/ui-service/access-url`);
}

/**
 * 获取 UI 服务当前生效端口（服务未运行时返回持久化配置或默认端口）
 */
export async function getUiServicePort(): Promise<number> {
  return http.get<number>('/api/admin/v2/site-service/ui-service/port');
}

// ==================== 站点独立服务 API ====================

/**
 * 启动站点独立服务
 */
export async function startSiteService(siteId: number): Promise<SiteServiceStatus> {
  return http.post<SiteServiceStatus>(`/api/admin/v2/site-service/${siteId}/site-service/start`);
}

/**
 * 停止站点独立服务
 */
export async function stopSiteService(siteId: number): Promise<SiteServiceStatus> {
  return http.post<SiteServiceStatus>(`/api/admin/v2/site-service/${siteId}/site-service/stop`);
}

/**
 * 获取站点独立服务状态
 */
export async function getSiteServiceStatus(siteId: number): Promise<SiteServiceStatus> {
  return http.get<SiteServiceStatus>(`/api/admin/v2/site-service/${siteId}/site-service/status`);
}

/**
 * 单站点服务设置
 */
export interface SiteServiceSettings {
  siteDomain: string | null;
  siteServiceIp: string | null;
  siteServicePort: number | null;
  previewAccessEnabled: boolean;
  password: string | null;
}

export async function fetchSiteServiceSettings(siteId: number): Promise<SiteServiceSettings> {
  return http.get<SiteServiceSettings>(`/api/admin/v2/site-service/${siteId}/site-service/settings`);
}

export async function saveSiteServiceSettings(siteId: number, settings: SiteServiceSettings): Promise<SiteServiceStatus> {
  return http.put<SiteServiceStatus>(`/api/admin/v2/site-service/${siteId}/site-service/settings`, settings);
}

export async function setSiteServicePort(siteId: number, port: number): Promise<SiteServiceStatus> {
  return http.put<SiteServiceStatus>(
    `/api/admin/v2/site-service/${siteId}/site-service/port`,
    undefined,
    { params: { port } }
  );
}

// ==================== 站点服务类型与启动参数 API ====================

/**
 * 获取站点服务类型与全局启动参数
 */
export async function getSiteServiceMode(): Promise<SiteServiceModeView> {
  return http.get<SiteServiceModeView>('/api/admin/v2/site-service/mode');
}

/**
 * 设置站点服务类型（会先停止当前所有运行中的站点服务）
 */
export async function setSiteServiceMode(mode: SiteServiceMode): Promise<SiteServiceModeView> {
  return http.put<SiteServiceModeView>(
    '/api/admin/v2/site-service/mode',
    undefined,
    { params: { mode } }
  );
}

/**
 * 保存全局启动参数
 */
export async function saveGlobalLaunchParams(
  params: Partial<GlobalParamsView>
): Promise<SiteServiceModeView> {
  return http.put<SiteServiceModeView>('/api/admin/v2/site-service/launch-params', params);
}

/**
 * 获取站点启动参数（站点覆盖 + 全局 + 默认值）
 */
export async function getSiteLaunchParams(siteId: number): Promise<SiteLaunchParamsView> {
  return http.get<SiteLaunchParamsView>(
    `/api/admin/v2/site-service/${siteId}/site-service/launch-params`
  );
}

/**
 * 保存站点启动参数覆盖（留空表示沿用全局默认）
 */
export async function saveSiteLaunchParams(
  siteId: number,
  params: Partial<GlobalParamsView>
): Promise<SiteLaunchParamsView> {
  return http.put<SiteLaunchParamsView>(
    `/api/admin/v2/site-service/${siteId}/site-service/launch-params`,
    params
  );
}

// ==================== 栏目模板状态检查 API ====================

export interface ColumnTemplateStatus {
  hasTemplate: boolean;
  templatePath: string | null;
  templateDir: string | null;
  templateFiles: Array<{
    fileName: string;
    relativePath: string;
  }>;
}

/**
 * 检查栏目模板状态
 */
export async function checkColumnTemplateStatus(
  siteId: number,
  columnId: number,
  templateType?: 'list' | 'detail'
): Promise<ColumnTemplateStatus> {
  const params: Record<string, string> = {};
  if (templateType) params.templateType = templateType;
  return http.get<ColumnTemplateStatus>(
    `/api/admin/v2/site-service/${siteId}/columns/${columnId}/template-status`,
    { params }
  );
}

// ==================== 站点路由管理 API ====================

export interface RouteInfoView {
  pattern: string | null;
  originalUrl: string | null;
  type: 'list' | 'detail' | null;
  isDynamic: boolean;
  columnId: number;
  parentId: number;
  columnName: string;
  depth: number;
  sort: number;
  templatePath: string | null;
  description: string | null;
  /** 动态占位符按当前栏目信息解析出的可访问样例地址 */
  sampleUrl: string | null;
}

export interface ConflictColumnView {
  columnId: number;
  columnName: string;
}

export interface RouteConflictView {
  pattern: string;
  columns: ConflictColumnView[];
}

export interface RouteListView {
  routes: RouteInfoView[];
  conflicts: RouteConflictView[];
}

/**
 * 重新加载站点路由
 */
export async function reloadSiteRoutes(siteId: number): Promise<boolean> {
  return http.post<boolean>(`/api/admin/v2/site-service/${siteId}/site-service/reload-routes`);
}

/**
 * 获取站点路由列表
 */
export async function getSiteRoutes(siteId: number): Promise<RouteListView> {
  return http.get<RouteListView>(`/api/admin/v2/site-service/${siteId}/site-service/routes`);
}
