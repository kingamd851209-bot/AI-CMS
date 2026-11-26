import { http } from '@/api/http';

export interface PairingContainer { id: string; selector: string; cardTag: string; cardClass: string; count: number; example: string }
export interface PairingCandidates { sourcePath: string; digest: string; containers: PairingContainer[]; warnings: string[] }
export interface PairingEvidence { sourceId: string; kind: string; selector: string; preview: string; originalLength: number }
export interface PairingSource { sourcePath: string; digest: string; evidence: PairingEvidence[]; evidenceComplete: boolean }
export interface PairingFact {
  name:string;
  list:{value:string;selector:string}|null;
  detail:{value:string;selector:string}|null;
  result:'MATCH'|'DIFFERENT'|'PRESENT'|'UNAVAILABLE';
}
export interface PairingRecordCandidate {recordId:number;menuId:number;title:string;status:number;date:string|null;bodyExcerptMatched:boolean}
export interface PairingItem { id: string; selector: string; hrefs: string[]; status: 'MATCHED' | 'PENDING' | 'SHARED_DETAIL'; reason: string; list: PairingSource; detail: PairingSource | null; comparisons?:PairingFact[]; detailBodyHash?:string|null; recordCandidates?:PairingRecordCandidate[] }
export type PairingOwnership = 'PLACEHOLDER' | 'EXISTING_RECORD' | 'OTHER_DETAIL' | 'LIST_ONLY' | 'SHARED_DETAIL';
export interface PairingDecision { cardId: string; ownership: PairingOwnership; recordId?: number; detailPath?: string; detailDigest?:string|null }
export interface PairingReview { revision: number; evidenceDigest: string; decisions: PairingDecision[] }
export interface PairingInspection {
  siteId: number; menuId: number; packageVersion: string; fieldConfigDigest: string;
  listSourcePath: string; listDigest: string; container: PairingContainer; totalCards: number; inspectedCards: number;
  items: PairingItem[]; warnings: string[];
  review?: PairingReview;
}
const base = (siteId: number) => `/api/admin/v2/sites/${siteId}/template-seed/list-pairing`;
export const listPairingContainers = (siteId: number, menuId: number, sourcePath: string): Promise<PairingCandidates> => http.get(`${base(siteId)}/containers`, { params: { menuId, sourcePath } });
export const inspectListPairing = (siteId: number, menuId: number, candidates: PairingCandidates, containerId: string, detailPlanId?: string): Promise<PairingInspection> => http.post(`${base(siteId)}/inspect`, { menuId, listSourcePath: candidates.sourcePath, listDigest: candidates.digest, containerId, detailPlanId });
export const reviewedPairingDetails = (siteId: number, pairing: PairingRequest): Promise<import('./templateFieldPlan').FieldPlanSummary[]> =>
  http.post(`${base(siteId)}/reviewed-details`, pairing);
export const savePairingReview = (siteId: number, pairing: PairingRequest, review: PairingReview): Promise<PairingReview> => http.post(`${base(siteId)}/review`, { pairing, ...review });

export interface PairedUnit { id: string; name: string; fieldType: string; valuePreview: string; valueLength: number; doubts: string | null }
export interface PairedPage { sourcePath: string; digest: string; revision: number; units: PairedUnit[] }
export interface PairedOptions { cardId: string; list: PairedPage; detail: PairedPage; fieldConfigDigest: string; fields: import('./templateFieldPlan').FieldPlanField[]; warnings: string[] }
export interface PairedMappingWorkspace {
  sourcePath: string; digest: string; revision: number;
  sources: Pick<import('./templateFieldPlan').FieldPlan['sources'][number], 'id' | 'kind' | 'selector' | 'rawValue'>[];
  units: {id: string; fieldType: string; sources: import('./templateFieldPlan').FieldPlanReference[]}[];
}
export interface PairingRequest { menuId: number; listSourcePath: string; listDigest: string; containerId: string; detailPlanId?:string }
export interface SharedDetailTemplateScope { pairing: PairingRequest; cardIds: string[] }
export const createSharedDetailTemplatePlan = (siteId: number, body: SharedDetailTemplateScope & {
  sourcePath: string; digest: string; unitsRevision: number;
}): Promise<import('./templateFieldPlan').FieldPlan> => http.post(`${base(siteId)}/detail-field-plan`, body);
export interface PairedPlanRequest { pairing: PairingRequest; cardId: string; detailDigest: string; listRevision: number; detailRevision: number; fieldConfigDigest: string; listChoices: Record<string,string>; detailChoices: Record<string,string> }
export interface PairedBatchEntry { planId: string; status: 'INITIALIZED' | 'RECOVERY_REQUIRED'; recordId: number | null; message: string; recovery: import('./templateTextBinding').BindingRecovery | null }
export interface PairedBatchResult { entries: PairedBatchEntry[]; initialized: number; pending: number }
export const getPairedOptions = (siteId: number, pairing: PairingRequest, cardId: string): Promise<PairedOptions> => http.post(`${base(siteId)}/options`, {pairing, cardId});
export const readPairedListWorkspace = (siteId: number, sourcePath: string): Promise<PairedMappingWorkspace> =>
  http.get(`/api/admin/v2/sites/${siteId}/template-seed/content-units`, {params: {sourcePath}});
export const createPairedPlan = (siteId: number, body: PairedPlanRequest): Promise<import('./templateFieldPlan').FieldPlan> => http.post(`${base(siteId)}/plans`, body);
export const initializePairedBatch = (siteId: number, menuId: number, planIds: string[]): Promise<PairedBatchResult> => http.post(`${base(siteId)}/initialize-batch`, {menuId, planIds, confirmed: true}, {timeout: 120000});
export const previewPairedList = (siteId: number, planId: string): Promise<import('./templateTextBinding').TextBindingDraft> => http.get(`${base(siteId)}/plans/${encodeURIComponent(planId)}/list-preview`);
