import { http } from '@/api/http';

export interface FieldPlanReference { sourceId: string; start: number; end: number }
export interface FieldPlanBinding {
  unitId: string; name: string; fieldType: string; sources: FieldPlanReference[];
  action: 'REUSE' | 'CREATE' | 'IGNORED' | 'PENDING_TYPE' | 'CONFLICT' | 'MENU_NAME';
  menuId?: number | null;
  fieldName: string | null; capacity: number; valueLength: number; valuePreview: string;
  message: string; currentValuePresent: boolean;
  locked?: boolean;
}
export interface FieldPlanField {
  fieldName: string; name: string; fieldType: string; capacity: number; writable: boolean;
  currentValuePresent: boolean; currentValuePreview: string; inputType?: string | null;
}
export interface FieldPlan {
  schemaVersion: number; id: string; siteId: number; code: string; packageVersion: string;
  sourcePath: string; digest: string; unitsRevision: number; menuId: number; pageType: number;
  fieldConfigDigest: string; afterFieldConfigDigest: string; bindings: FieldPlanBinding[];
  sources: {id: string; kind: string; rawValue: string; selector: string; context: string; repeatGroup: string | null; scriptInput?: {reader: string; input: string; reason: string} | null}[];
  origins?: {role: string; sourcePath: string; digest: string; unitsRevision: number;
    pairingReview?: {cardId: string; sharedDetail?: boolean; evidenceDigest?: string;
      pairing?: import('./templateListPairing').PairingRequest} | null}[] | null;
  sharedDetailSeedPlanId?: string;
  fields: FieldPlanField[]; fieldUpdate: Record<string, unknown> | null;
  canConfirm: boolean; status: 'DRAFT' | 'CONFIRMED' | 'CONFIRMED_WITH_PENDING'; warnings: string[];
}
export interface FieldPlanRequest {
  sourcePath: string; digest: string; unitsRevision: number; menuId: number; choices: Record<string, string>;
}
export function previewFieldPlan(siteId: number, request: FieldPlanRequest): Promise<FieldPlan> {
  return http.post<FieldPlan>(`/api/admin/v2/sites/${siteId}/template-seed/field-plans`, request);
}
export function readFieldPlan(siteId: number, id: string): Promise<FieldPlan> {
  return http.get<FieldPlan>(`/api/admin/v2/sites/${siteId}/template-seed/field-plans/${encodeURIComponent(id)}`);
}
/** 字段新增只走人工正式API；确认计划本身不另写字段表。 */
export async function confirmFieldPlan(siteId: number, id: string): Promise<FieldPlan> {
  const checked = await http.post<{plan: FieldPlan; requiresFieldUpdate: boolean}>(`/api/admin/v2/sites/${siteId}/template-seed/field-plans/${encodeURIComponent(id)}/preflight`, {});
  let savedFields = false;
  try {
    if (checked.requiresFieldUpdate && checked.plan.fieldUpdate) {
      await http.post(`/api/admin/v2/cms/fields/menus/${checked.plan.menuId}`, checked.plan.fieldUpdate, {
        headers: {'X-CMS-Field-Config-Digest': checked.plan.fieldConfigDigest}
      });
      savedFields = true;
    }
    return await http.post<FieldPlan>(`/api/admin/v2/sites/${siteId}/template-seed/field-plans/${encodeURIComponent(id)}/confirm`, {acknowledgeReuse: true, acknowledgeCreate: true});
  } catch (cause) {
    if (savedFields) throw new Error(`字段已通过CMS保存，但计划尚未确认：${cause instanceof Error ? cause.message : '请重新读取计划'}。未初始化内容或模板。`);
    throw cause;
  }
}

export interface FieldPlanSummary {
  id: string; sourcePath: string; menuId: number; status: FieldPlan['status']; unitsRevision: number; digest: string;
}
export interface RecentFieldPlans { plans: FieldPlanSummary[]; truncated: boolean; warnings: string[] }
export function recentFieldPlans(siteId: number, sourcePath: string, menuId: number): Promise<RecentFieldPlans> {
  return http.get(`/api/admin/v2/sites/${siteId}/template-seed/field-plans`, {params: {sourcePath, menuId}});
}
