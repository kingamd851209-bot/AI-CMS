import { http } from './http';

export interface AssetsUploadResponse {
  reference: string;
  url: string | null;
  originalName: string;
  fileSize: number;
  contentType: string;
  modifiedAt?: number;
}

export interface AssetDirectory {
  name: string;
  path: string;
}

export interface AssetsListItem {
  name: string;
  reference: string;
  url: string | null;
  fileSize: number;
  contentType: string;
  modifiedAt?: number;
}

export interface AssetsListResponse {
  currentPath: string;
  parentPath: string | null;
  directories: AssetDirectory[];
  items: AssetsListItem[];
  total: number;
  page: number;
  pageSize: number;
}

const BASE_PATH = '/api/admin/v2/cms/assets-upload';

export function uploadAsset(file: File, type: string, scope = 'site'): Promise<AssetsUploadResponse> {
  const formData = new FormData();
  formData.append('file', file);
  formData.append('scope', scope);
  return http.post<AssetsUploadResponse>(
    `/api/admin/v2/cms/assets-upload?type=${encodeURIComponent(type)}`,
    formData,
    { headers: { 'Content-Type': 'multipart/form-data' } }
  );
}

export function listAssets(type: string, path = '', page = 1, pageSize = 24, extensions: string[] = [], scope = 'site'): Promise<AssetsListResponse> {
  return http.get<AssetsListResponse>(BASE_PATH, {
    params: { type, path, page, pageSize, scope, extensions: extensions.join(',') }
  });
}
