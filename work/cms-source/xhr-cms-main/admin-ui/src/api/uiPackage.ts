import { http } from '@/api/http';

export interface UiPackageStatus {
  status: 'NOT_UPLOADED' | 'UPLOADED' | 'RUNNING' | 'STOPPED';
  packageName?: string;
  version?: string;
  accessUrl?: string;
}

export interface UiPackageFileInfo {
  relativePath: string;
  displayPath: string;
  fileName: string;
}

export async function getUiPackageStatus(siteId: number): Promise<UiPackageStatus> {
  return http.get<UiPackageStatus>(`/api/admin/v2/sites/${siteId}/ui-package`);
}

export async function uploadUiPackage(siteId: number, file: File): Promise<UiPackageStatus> {
  const formData = new FormData();
  formData.append('file', file);
  return http.post<UiPackageStatus>(`/api/admin/v2/sites/${siteId}/ui-package`, formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
    // 覆盖全局 15s 超时：后端 multipart 上限 800MB，大 ZIP 上传耗时可达数分钟，放宽到 30 分钟
    timeout: 1800000
  });
}

export async function deleteUiPackage(siteId: number): Promise<void> {
  return http.delete(`/api/admin/v2/sites/${siteId}/ui-package`);
}

export async function startUiPackage(siteId: number): Promise<UiPackageStatus> {
  return http.post<UiPackageStatus>(`/api/admin/v2/sites/${siteId}/ui-package/start`);
}

export async function stopUiPackage(siteId: number): Promise<UiPackageStatus> {
  return http.post<UiPackageStatus>(`/api/admin/v2/sites/${siteId}/ui-package/stop`);
}

export async function setUiPackagePort(siteId: number, port: number): Promise<UiPackageStatus> {
  return http.put<UiPackageStatus>(`/api/admin/v2/sites/${siteId}/ui-package/port`, { port });
}

export interface UiPackageUploadRecord {
  id: number;
  version: string;
  packageName: string | null;
  fileSize: number | null;
  uploadType: 'UPLOAD' | 'UPGRADE' | 'REUPLOAD';
  uploadUserId: number | null;
  uploadUser: string | null;
  uploadTime: string;
}

/**
 * 升级 UIPackage：版本号不变，同名文件覆盖，未包含的旧文件保留
 */
export async function upgradeUiPackage(siteId: number, file: File): Promise<UiPackageStatus> {
  const formData = new FormData();
  formData.append('file', file);
  return http.post<UiPackageStatus>(`/api/admin/v2/sites/${siteId}/ui-package/upgrade`, formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
    timeout: 1800000
  });
}

/**
 * 重新上传 UIPackage：生成新版本，旧包整体被替换
 */
export async function reuploadUiPackage(siteId: number, file: File): Promise<UiPackageStatus> {
  const formData = new FormData();
  formData.append('file', file);
  return http.post<UiPackageStatus>(`/api/admin/v2/sites/${siteId}/ui-package/reupload`, formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
    timeout: 1800000
  });
}

/**
 * 获取 UIPackage 上传记录
 */
export async function listUiPackageUploadRecords(siteId: number): Promise<UiPackageUploadRecord[]> {
  return http.get<UiPackageUploadRecord[]>(`/api/admin/v2/sites/${siteId}/ui-package/upload-records`);
}

export async function listUiPackageFiles(siteId: number): Promise<UiPackageFileInfo[]> {
  return http.get<UiPackageFileInfo[]>(`/api/admin/v2/sites/${siteId}/ui-package/files`);
}

export async function getUiPackageAccessUrl(siteId: number): Promise<string> {
  return http.get<string>(`/api/admin/v2/sites/${siteId}/ui-package/access-url`);
}

export async function getUiPackageFileContent(siteId: number, filePath: string): Promise<string> {
  return http.get<string>(`/api/admin/v2/sites/${siteId}/ui-package/file-content`, {
    params: { path: filePath }
  });
}

// Menu Recognition APIs

export interface RecognizedMenu {
  name: string;
  enName?: string;
  path?: string;
  pageType?: number;
  sort?: number;
  parentName?: string;
  listPageUrl?: string;
  listPageTemplate?: string;
  detailPageUrl?: string;
  detailPageTemplate?: string;
  parentIdOverride?: number;
}

export interface RecognitionResult {
  status: 'SUCCESS' | 'FAILED';
  errorCode?: string;
  errorMessage?: string;
  menus: RecognizedMenu[];
  rawResponse?: string;
  prompt?: string;
}

export interface MenuNode {
  id?: number;
  parentId?: number;
  className?: string;
  name?: string;  // 后端 ColumnNode 返回的字段
  classEnName?: string;
  enName?: string;  // 后端 ColumnNode 返回的字段
  sort?: number;
  pageType?: number;
  depth?: number;
  hasManageChannel?: boolean;
  hasFrontChannel?: boolean;
  url?: string;
  listPageUrl?: string;
  listPageTemplate?: string;
  detailPageUrl?: string;
  detailPageTemplate?: string;
  children?: MenuNode[];
  isNew?: boolean;
}

export interface ExistingMenusView {
  tree: MenuNode[];
  flat: MenuNode[];
}

export interface SaveResult {
  newCount: number;
  matchedCount: number;
  totalCount: number;
}


export async function getExistingColumns(siteId: number): Promise<ExistingMenusView> {
  return http.get<ExistingMenusView>(`/api/admin/v2/sites/${siteId}/existing-columns`);
}


// 栏目字段配置 API

export interface FieldOption {
  datePrecision?: 'DAY' | 'SECOND' | null;
  isOpen: string;
  fieldName: string;
  fromName: string;
  fieldType: string;
  title?: string;
  defaultValue?: string;
  inputType?: string;
  inputMultiple?: boolean;
  bak?: string;
}

export interface FieldWorkspaceResponse {
  menu: {
    id: number;
    className: string;
    classEnName?: string;
    pageType: number;
    parentId?: number;
  };
  type: number;
  fieldOptions: FieldOption[];
  fieldListDisplay: FieldOption[];
  fieldSearch: FieldOption[];
}

/**
 * 获取栏目字段配置
 * @param menuId 栏目ID
 * @param type 0=内容字段(列表栏目), 1=单篇字段(单篇栏目)
 */
export async function getMenuFieldConfig(menuId: number, type: number = 0): Promise<FieldWorkspaceResponse> {
  return http.get<FieldWorkspaceResponse>(`/api/admin/v2/cms/fields/menus/${menuId}`, {
    params: { type }
  });
}

export interface BatchFieldOptions {
  menuId: number;
  type: number;
  fieldOptions: FieldOption[];
}

/** 批量获取多个栏目的字段配置（type 由后端按栏目 pageType 推导），避免逐栏目请求。 */
export async function getMenuFieldConfigs(menuIds: number[]): Promise<BatchFieldOptions[]> {
  if (!menuIds.length) return [];
  return http.get<BatchFieldOptions[]>('/api/admin/v2/cms/fields/menus/batch', {
    params: { menuIds: menuIds.join(',') }
  });
}
