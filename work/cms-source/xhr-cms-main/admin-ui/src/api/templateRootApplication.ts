import { http } from '@/api/http';
export interface RootApplicationOptions { availableAliases: string[]; activeAliases: string[]; applied: boolean; activePlanId: string | null; message: string }
export interface RootApplicationResult { applied: boolean; templatePath: string; publicUrls: string[]; requiresRouteReload: boolean; message: string }
export interface RootSwitchOptions {
  activePlanId: string | null;
  activeTemplatePath: string | null;
  sourceTemplatePath: string | null;
  publicUrls: string[];
  canSwitch: boolean;
  canRollback: boolean;
  message: string;
}
const base = (siteId: number, planId: string) => `/api/admin/v2/sites/${siteId}/template-seed/field-plans/${encodeURIComponent(planId)}/text-binding/public-home`;
export const inspectPublicHome = (siteId: number, planId: string): Promise<RootApplicationOptions> => http.get(base(siteId, planId));
export const applyPublicHome = (siteId: number, planId: string, aliases: string[]): Promise<RootApplicationResult> => http.post(base(siteId, planId), { confirmed: true, aliases });
export const withdrawPublicHome = (siteId: number, planId: string, packageVersion: string): Promise<RootApplicationResult> => http.post(`${base(siteId, planId)}/withdraw`, { confirmed: true, packageVersion });
export const inspectPublicHomeSwitch = (siteId: number, planId: string): Promise<RootSwitchOptions> => http.get(`${base(siteId, planId)}/switch`);
export const switchPublicHome = (siteId: number, planId: string, state: RootSwitchOptions, rollback: boolean): Promise<RootApplicationResult> =>
  http.post(`${base(siteId, planId)}/switch${rollback ? '/rollback' : ''}`, {
    confirmed: true,
    expectedActiveTemplatePath: state.activeTemplatePath,
    ...(!rollback && { expectedSourceTemplatePath: state.sourceTemplatePath }),
  });
