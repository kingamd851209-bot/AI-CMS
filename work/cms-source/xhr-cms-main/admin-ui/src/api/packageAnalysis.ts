import { http } from '@/api/http';
import type { DomainAgentResult } from './domainAgent';
import type { FieldPlan, FieldPlanRequest } from './templateFieldPlan';

export type PackageReview = 'PENDING' | 'CONFIRMED' | 'NEEDS_REVIEW';
export interface PackageAnalysisPage {
  sourcePath:string; state:'PENDING'|'RUNNING'|'READY'|'FAILED'|'SKIPPED'; menuId:number|null;
  candidateName:string|null; candidateNames:string[]; reason:string|null; review:PackageReview;
  recommendedMenuId?:number|null; structureCandidateNames?:string[];
  reviewMenuId?:number|null; reviewNote?:string|null; observedSources:number; totalSources:number; unitCount:number;
}
export interface PackageAnalysis {
  packageVersion:string; revision:number; state:'IDLE'|'RUNNING'|'PAUSED'|'COMPLETED'|'ATTENTION';
  structurePlanId:string|null; structurePlanRevision:number|null; structureStatus:string|null; structureMessage:string|null;
  pages:PackageAnalysisPage[]; updatedAt:string|null;
}
export interface PackageFieldEdit {resultIndex:number;unitId:string;name:string;fieldType:string;ignored:boolean;doubts:string}
export interface PackageAnalysisDetail {page:PackageAnalysisPage;results:DomainAgentResult[];fieldRevision:number;originalResults?:DomainAgentResult[]}
const base=(siteId:number)=>`/api/admin/v2/sites/${siteId}/template-seed/package-analysis`;
export const packageAnalysisApi={
  read:(siteId:number, packageVersion:string, signal:AbortSignal)=>http.get<PackageAnalysis>(base(siteId),{params:{packageVersion},signal,timeout:30000}),
  run:(siteId:number, payload:{packageVersion:string;revision:number;structurePlanId?:string;structurePlanRevision?:number;attachModelDraft?:boolean}, signal:AbortSignal)=>http.post<PackageAnalysis>(`${base(siteId)}/run`,payload,{signal,timeout:45000}),
  page:(siteId:number, packageVersion:string, sourcePath:string, signal:AbortSignal)=>http.get<PackageAnalysisDetail>(`${base(siteId)}/page`,{params:{packageVersion,sourcePath},signal,timeout:30000}),
  review:(siteId:number, payload:{packageVersion:string;revision:number;sourcePath:string;decision:Exclude<PackageReview,'PENDING'>;reviewMenuId:number|null;note:string;workspaceRevision?:number;workspaceDigest?:string}, signal:AbortSignal)=>http.post<PackageAnalysis>(`${base(siteId)}/review`,payload,{signal,timeout:30000}),
  fields:(siteId:number,payload:{packageVersion:string;sourcePath:string;fieldRevision:number;edits:PackageFieldEdit[]},signal:AbortSignal)=>http.post<PackageAnalysisDetail>(`${base(siteId)}/fields`,payload,{signal,timeout:35000}),
  adopt:(siteId:number,payload:{packageVersion:string;sourcePath:string;fieldRevision:number;resultIndexes?:number[]},signal:AbortSignal)=>http.post<DomainAgentResult>(`${base(siteId)}/adopt`,payload,{signal,timeout:35000}),
  fieldPlan:(siteId:number,payload:{packageVersion:string;packageRevision:number;fieldRevision:number;structurePlanId:string;structurePlanRevision:number;navigationRevision:number;nodeKey:string;request:FieldPlanRequest},signal:AbortSignal)=>
    http.post<FieldPlan>(`${base(siteId)}/field-plan`,payload,{signal,timeout:60000}),
};
