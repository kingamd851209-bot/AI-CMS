import { http } from './http';
export interface IntegrationEntry { enabled: boolean; scriptUrl: string; parameters: Record<string, string> }
export interface IntegrationSettings { chat: IntegrationEntry; stats: IntegrationEntry }
export interface IntegrationView { settings: IntegrationSettings; mode: string; diagnostics: string[] }
export const siteExternalIntegrationsApi = {
  get: (siteId: number) => http.get<IntegrationView>(`/api/admin/v2/sites/${siteId}/external-integrations`),
  save: (siteId: number, settings: IntegrationSettings) => http.put<IntegrationView>(`/api/admin/v2/sites/${siteId}/external-integrations`, settings)
};
