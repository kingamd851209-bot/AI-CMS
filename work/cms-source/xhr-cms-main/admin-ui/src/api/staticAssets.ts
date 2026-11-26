import { http, resolveAdminApiBaseUrl } from './http';

/** 静态资源文件项 */
export interface AssetEntry {
  name: string;
  dir: boolean;
  size: number;
  ext: string;
  path: string;
}

/** 资源作用域 */
export type AssetScope = 'GLOBAL' | 'SITE_ASSETS' | 'SITE_TEMPLATE' | 'SITE_UIPACKAGE';

/** 左侧导航站点项 */
export interface SiteNavItem {
  siteId: number;
  name: string;
  code: string;
}

/** 资源同步结果 */
export interface SyncCopyResult {
  copiedCount: number;
  failed: Array<{ path: string; reason: string }>;
}

const root = '/api/admin/v2/static-assets';

function scopeParams(scope: AssetScope, siteId?: number) {
  const params: Record<string, string | number> = { scope };
  if (siteId != null) {
    params.siteId = siteId;
  }
  return params;
}

export const staticAssetApi = {
  metadata: (path: string, scope: AssetScope, siteId?: number) =>
    http.get<{ exists: boolean; name: string; size: number }>(`${root}/metadata`, {
      params: { path, ...scopeParams(scope, siteId) }
    }),
  nav: () => http.get<SiteNavItem[]>(`${root}/nav`),
  list: (path = '', scope: AssetScope = 'GLOBAL', siteId?: number) =>
    http.get<AssetEntry[]>(`${root}/list`, { params: { path, ...scopeParams(scope, siteId) } }),
  mkdir: (path: string, name: string, scope: AssetScope = 'GLOBAL', siteId?: number) =>
    http.post<void>(`${root}/mkdir`, undefined, { params: { path, name, ...scopeParams(scope, siteId) } }),
  upload: (file: File, path = '', scope: AssetScope = 'GLOBAL', siteId?: number) => {
    const fd = new FormData();
    fd.append('file', file);
    fd.append('path', path || '');
    fd.append('scope', scope);
    if (siteId != null) {
      fd.append('siteId', String(siteId));
    }
    return http.post<string>(`${root}/upload`, fd);
  },
  remove: (path: string, scope: AssetScope = 'GLOBAL', siteId?: number) =>
    http.delete<void>(`${root}/delete`, { params: { path, ...scopeParams(scope, siteId) } }),
  downloadUrl: (path: string, scope: AssetScope = 'GLOBAL', siteId?: number) => {
    const params = new URLSearchParams({ path, scope });
    if (siteId != null) {
      params.set('siteId', String(siteId));
    }
    return `${resolveAdminApiBaseUrl().replace(/\/$/, '')}${root}/download?${params.toString()}`;
  },
  syncCopy: (payload: {
    sourceScope: AssetScope;
    sourceSiteId?: number | null;
    sourcePaths: string[];
    targetScope: AssetScope;
    targetSiteId?: number | null;
    targetPath: string;
  }) => http.post<SyncCopyResult>(`${root}/sync/copy`, payload)
};

/** 静态资源支持的类型与展示图标 */
export const ASSET_TYPE_LABELS: Record<string, string> = {
  images: '图片',
  videos: '视频',
  audios: '音频',
  pdf: 'PDF',
  ppt: 'PPT',
  excel: 'Excel',
  word: 'Word',
  files: '其他'
};

export function assetCategoryOf(fileName: string): string {
  const ext = fileName.toLowerCase().split('.').pop() || '';
  if (['jpg', 'jpeg', 'png', 'gif', 'webp', 'svg', 'bmp', 'ico'].includes(ext)) return 'images';
  if (['mp4', 'webm', 'mov', 'avi'].includes(ext)) return 'videos';
  if (['mp3', 'wav', 'ogg', 'aac'].includes(ext)) return 'audios';
  if (ext === 'pdf') return 'pdf';
  if (['ppt', 'pptx'].includes(ext)) return 'ppt';
  if (['xls', 'xlsx', 'csv'].includes(ext)) return 'excel';
  if (['doc', 'docx'].includes(ext)) return 'word';
  return 'files';
}
