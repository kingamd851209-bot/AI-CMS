import { http } from './http';

export interface SiteSettingRow {
  pageUrl?: string;
  redirect301?: string;
  redirect302?: string;
  fallbackLang?: string;
  crossMappingJson?: string;
}

export interface ColumnConfig {
  columnId: number;
  columnName: string;
  list?: SiteSettingRow | null;
  detail?: SiteSettingRow | null;
}

export const siteSettingsApi = {
  children: (siteId: number, parentColumnId: number) =>
    http.get<ColumnConfig[]>('/api/admin/v2/cms/site-settings/children', { params: { siteId, parentColumnId } }),
  config: (siteId: number, columnId: number, kind: string) =>
    http.get<SiteSettingRow>('/api/admin/v2/cms/site-settings/config', { params: { siteId, columnId, kind } }),
  save: (payload: Record<string, unknown>) =>
    http.post<boolean>('/api/admin/v2/cms/site-settings/save', payload),
  importExcel: (file: File, siteId: number) => {
    const fd = new FormData();
    fd.append('file', file);
    fd.append('siteId', String(siteId));
    return http.post<number>('/api/admin/v2/cms/site-settings/import', fd);
  },
  exportTemplateUrl: (siteId: number, parent: number) =>
    `/api/admin/v2/cms/site-settings/export-template?siteId=${siteId}&parentColumnId=${parent}`,
  exportUrl: (siteId: number, parent: number) =>
    `/api/admin/v2/cms/site-settings/export?siteId=${siteId}&parentColumnId=${parent}`
};