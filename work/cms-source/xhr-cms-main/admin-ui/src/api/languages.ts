import { http } from './http';

export interface LanguageItem {
  id: number;
  languageCode: string;
  languageName: string;
  nativeName?: string | null;
  locale?: string | null;
  isDefault: number;
  enabled: number;
  sort: number;
  createTime?: string;
  updateTime?: string;
}

export interface LanguageCreatePayload {
  languageCode: string;
  languageName: string;
  nativeName?: string | null;
  locale?: string | null;
  sort?: number;
  enabled?: boolean;
  defaultLanguage?: boolean;
}

export interface LanguageUpdatePayload {
  languageName: string;
  nativeName?: string | null;
  locale?: string | null;
  sort?: number;
  enabled?: boolean;
}

const BASE_PATH = '/api/admin/i18n/languages';

export function listLanguages(): Promise<LanguageItem[]> {
  return http.get(BASE_PATH);
}

export function createLanguage(payload: LanguageCreatePayload): Promise<LanguageItem> {
  return http.post(BASE_PATH, payload);
}

export function updateLanguage(id: number, payload: LanguageUpdatePayload): Promise<LanguageItem> {
  return http.put(`${BASE_PATH}/${id}`, payload);
}

export function deleteLanguage(id: number): Promise<void> {
  return http.delete(`${BASE_PATH}/${id}`);
}

export function setDefaultLanguage(id: number): Promise<void> {
  return http.post(`${BASE_PATH}/${id}/default`, {});
}

export function enableLanguage(id: number): Promise<void> {
  return http.post(`${BASE_PATH}/${id}/enable`, {});
}

export function disableLanguage(id: number): Promise<void> {
  return http.post(`${BASE_PATH}/${id}/disable`, {});
}
