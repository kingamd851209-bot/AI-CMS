export type ResourceMediaType = 'image' | 'video' | 'file';
export type ResourceRequestType = 'all' | 'image' | 'video' | 'file';

export interface ResourceDirectoryItem {
  name: string;
  path: string;
  modifiedAt?: string;
}

export interface ResourceLibraryItem {
  url: string;
  adminPreviewUrl?: string;
  adminDownloadUrl?: string;
  name: string;
  mediaType: ResourceMediaType;
  extension?: string;
  size?: number;
  modifiedAt?: string;
}

export interface ResourceLibraryResponse {
  currentPath: string;
  parentPath: string | null;
  directories: ResourceDirectoryItem[];
  items: ResourceLibraryItem[];
  total?: number;
  page?: number;
  pageSize?: number;
}

export interface ResourceUploadResponse {
  url?: string;
  path?: string;
  oldName?: string;
  mediaType?: string;
}

export interface ResourceReferenceItem {
  sourceType: string;
  sourceName: string;
  fieldName?: string;
  valuePreview?: string;
}

export interface ResourceReferenceResponse {
  path: string;
  referenced: boolean;
  references: ResourceReferenceItem[];
}

export function normalizeResourceUrl(value: string) {
  const trimmed = value.trim();
  if (!trimmed) {
    return '';
  }
  if (trimmed.startsWith('ui:') || trimmed.startsWith('assets:')) return '';
  if (/^(https?:)?\/\//i.test(trimmed) || trimmed.startsWith('data:') || trimmed.startsWith('blob:')) {
    return trimmed;
  }
  return trimmed.startsWith('/') ? trimmed : `/${trimmed.replace(/^\.?\//, '')}`;
}

export function resourceAccept(type: ResourceRequestType) {
  if (type === 'image') {
    return '.jpg,.jpeg,.png,.webp,.svg,image/*';
  }
  if (type === 'video') {
    return '.mp4,.webm,video/*';
  }
  if (type === 'all') {
    return '.jpg,.jpeg,.png,.webp,.svg,.mp4,.webm,.doc,.docx,.xls,.xlsx,.ppt,.pptx,.pdf,.rar,.zip,.apk,.txt,image/*,video/*';
  }
  return '.doc,.docx,.xls,.xlsx,.ppt,.pptx,.pdf,.rar,.zip,.apk,.txt';
}

export function assetReference(value: string) {
  if (!value.startsWith('assets:/upload/') && !value.startsWith('assets:/global/')) return '';
  if (value.split('/').includes('..') || value.includes('%') || value.includes('\\')) return '';
  return value;
}

export function resourceExtension(value: string) {
  const path = value.split(/[?#]/)[0]?.trim().toLowerCase() ?? '';
  const fileName = path.split('/').filter(Boolean).at(-1) ?? path;
  if (!fileName.includes('.')) {
    return '';
  }
  return fileName.slice(fileName.lastIndexOf('.') + 1);
}

export function normalizeAllowedExtensions(extensions: readonly string[] | undefined) {
  return Array.from(new Set((extensions ?? [])
    .map((extension) => extension.replace(/^\./, '').trim().toLowerCase())
    .filter(Boolean)));
}

export function isAllowedResourceExtension(value: string, extensions: readonly string[] | undefined) {
  const allowedExtensions = normalizeAllowedExtensions(extensions);
  if (!allowedExtensions.length) {
    return true;
  }
  const extension = resourceExtension(value);
  return Boolean(extension) && allowedExtensions.includes(extension);
}
