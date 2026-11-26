import { http } from '@/api/http';
import type { FieldPlanBinding } from '@/api/templateFieldPlan';
import type { QueryFormActionEvidence } from '@/api/templateQueryBinding';
export interface PreparedEditorContent { originalHtml: string; valueHtml: string; changes: string[]; resourceMappings: {attribute: string; original: string; resolved: string}[] }
export interface TextBindingResult {
  templatePath: string; initialized: boolean; recordId: number | null; bound: FieldPlanBinding[];
  domChanges?: {unitId: string; before: string; after: string; reason: string}[];
  pending: {unitId: string; reason: string}[]; warnings: string[];
  preparedContents?: Record<string, PreparedEditorContent>; compiledHtml?: string; mediaMappings?: Record<string, string>;
}
export interface TextBindingDraft {
  binding: TextBindingResult; preview: {html: string; warnings: string[]};
}
const base = (siteId: number, planId: string) => `/api/admin/v2/sites/${siteId}/template-seed/field-plans/${encodeURIComponent(planId)}/text-binding`;
export const inspectTextBinding = (siteId: number, planId: string): Promise<TextBindingResult> => http.get(base(siteId, planId));
export const initializeTextBinding = (siteId: number, planId: string): Promise<TextBindingResult> => http.post(`${base(siteId, planId)}/initialize`, {confirmed: true});
export const previewTextBindingDraft = (siteId: number, planId: string): Promise<TextBindingDraft> => http.get(`${base(siteId, planId)}/draft-preview`);

export interface ExistingDetailPreview {
  recordId: number;
  title: string | null;
  previewDigest: string;
  preserveCurrent?: boolean;
  changes: { field: string; name: string; currentPreview: string; initialPreview: string; action: 'FILL' | 'KEEP' | 'REPLACE' }[];
  applicable: boolean;
  initialized: boolean;
}
export const inspectExistingDetail = (siteId: number, planId: string, recordId: number, preserveCurrent: boolean) =>
  http.get<ExistingDetailPreview>(`${base(siteId, planId)}/existing-detail`, { params: { recordId, preserveCurrent: preserveCurrent || undefined } });
export const initializeExistingDetail = (siteId: number, planId: string, { recordId, previewDigest, preserveCurrent }: ExistingDetailPreview) =>
  http.post<TextBindingResult>(`${base(siteId, planId)}/existing-detail`, { recordId, previewDigest, preserveCurrent: preserveCurrent || undefined, confirmed: true });

export type RecoveryAction = 'KEEP_SINGLE_CURRENT' | 'RESUME_EXISTING_DETAIL' | 'RETRY_MISSING_DETAIL';
export interface BindingRecovery { activePlanId?: string | null; phase: string; recordId: number | null; message: string; actions: RecoveryAction[]; stateDigest: string; currentValueDigest: string | null; differences: {field: string; name: string; initialPreview: string; currentPreview: string; equal: boolean}[] }
export const getBindingRecovery = (siteId: number, planId: string): Promise<BindingRecovery> => http.get(`${base(siteId, planId)}/recovery`);
export const recoverTextBinding = (siteId: number, planId: string, state: BindingRecovery, action: RecoveryAction): Promise<TextBindingResult> => http.post(`${base(siteId, planId)}/recover`, { action, confirmed: true, stateDigest: state.stateDigest, currentValueDigest: state.currentValueDigest });

export interface PublicBindingResult {
  applied: boolean;
  templatePath: string | null;
  publicUrl: string | null;
  requiresRouteReload: boolean;
  message: string;
  activePlanId?: string | null;
  currentRoute?: string | null;
  previousRoute?: string | null;
  changeDigest?: string | null;
  requiresCurrentPlan?: boolean;
  preview?: TextBindingDraft['preview'] | null;
}
export interface DetailFormAction { sourceId: string; fieldName: 'title'; inputId: string; targetMenuId: number; formCategoryId: number }
export interface DetailFormActionEvidence { actions: QueryFormActionEvidence[]; targetMenus: { id: number; name: string }[]; selectedActions: DetailFormAction[] }
export interface DetailCollection {
  regionId: string; mode: 'OWNED' | 'REFERENCE' | 'INLINE_REFERENCE' | 'PAGE_LIST'; reason: string;
  targetMenuId: number | null; targetMenuName: string | null; recordCount: number;
  targetMenuIds?: number[];
  regionSelector?: string | null; rowSelectors?: string[];
  fields: { fieldName: string; label: string; type: string }[]; unavailableReason: string | null;
}
export interface DetailCollectionEvidence { collections: DetailCollection[]; selectedCollectionRegionIds: string[] }
export interface DetailNeighborCandidate {
  id: string; kind: 'NEIGHBORS' | 'RETURN_ONLY'; selector: string;
  previousHref: string | null; previousTitle: string | null; nextHref: string | null; nextTitle: string | null;
  returnSelector: string | null; returnHref: string | null;
}
export interface DetailNeighborEvidence {
  candidates: DetailNeighborCandidate[]; selectedCandidateId: string | null; returnListUrl: string | null;
}
export const inspectPublicBinding = (siteId: number, planId: string): Promise<PublicBindingResult> => http.get(`${base(siteId, planId)}/public-application`);
export const inspectDetailFormActions = (siteId: number, planId: string): Promise<DetailFormActionEvidence> => http.get(`${base(siteId, planId)}/public-application/actions`);
export const inspectDetailCollections = (siteId: number, planId: string): Promise<DetailCollectionEvidence> => http.get(`${base(siteId, planId)}/public-application/collections`);
export const inspectDetailNeighbors = (siteId: number, planId: string): Promise<DetailNeighborEvidence> => http.get(`${base(siteId, planId)}/public-application/neighbors`);
export const applyPublicBinding = (siteId: number, planId: string, actions?: DetailFormAction[], collectionRegionIds?: string[], neighborCandidateId?: string, change?: {routeUrl?: string; changeDigest: string}): Promise<PublicBindingResult> =>
  http.post(`${base(siteId, planId)}/public-application`, { confirmed: true, preview: false, actions, collectionRegionIds, neighborCandidateId, ...change });
export const previewPublicBinding = (siteId: number, planId: string, change: {routeUrl: string} | {neighborCandidateId: string} | {collectionRegionIds: string[]}): Promise<PublicBindingResult> =>
  http.post(`${base(siteId, planId)}/public-application`, { confirmed: false, preview: true, ...change });
export const withdrawPublicBinding = (siteId: number, planId: string, packageVersion: string): Promise<PublicBindingResult> => http.post(`${base(siteId, planId)}/public-application/withdraw`, { confirmed: true, preview: false, packageVersion });
