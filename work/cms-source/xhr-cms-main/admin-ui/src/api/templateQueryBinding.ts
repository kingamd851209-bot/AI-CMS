import { http } from '@/api/http';
import type { PairingRequest } from '@/api/templateListPairing';
export interface QueryOption {label:string;value:string}
export interface QueryControl {id:string;selector:string;kind:string;tag:string;name:string;value:string;label:string;href:string;options:QueryOption[];status:'CANDIDATE'|'PENDING';reason:string}
export interface QueryCandidates {sourcePath:string;digest:string;menuId:number;fieldConfigDigest:string;routeDigest:string;containerSelector:string;controls:QueryControl[];truncated:boolean;warnings:string[]}
export interface QueryField {fieldName:string;title:string;fieldType:string;select?:boolean;options?:string[];inputType:string;inputMultiple:boolean}
export interface QueryFields {filters:QueryField[];sorting:QueryField[]}
export interface QueryMapping {controlId:string;role:'FILTER'|'SORT'|'SUBMIT';fieldName:string|null;operator:string|null;values:Record<string,string>}
export interface QueryMappingRequest {sourcePlanId:string;sourcePath:string;digest:string;menuId:number;containerSelector:string;fieldConfigDigest:string;routeDigest:string;mappings:QueryMapping[];confirmed:boolean;presentation?:QueryPresentationRequest|QueryTimelinePresentation|null;pageSize?:number}
export interface QuerySnapshot {schemaVersion:number;id:string;siteId:number;code:string;packageVersion:string;sourcePlanId:string;sourcePath:string;digest:string;menuId:number;bizCode:string;containerSelector:string;fieldConfigDigest:string;routeDigest:string;firstUrl:string;mappings:QueryMapping[];allowedFilterKeys:string[];allowedSortKeys:string[];presentation?:QueryPresentationPlan|null;pageSize?:number;sharedLayout?:{sourceQueryId:string;navigationPlanId:string}|null}
export interface CompiledQuery {html:string;snapshot:QuerySnapshot;warnings:string[];completeList:boolean}
export interface SharedLayoutRequest {
  menuId:number;
  sourcePath:string;
  layout:{sourceQueryId:string;navigationPlanId:string};
  expectedId?:string;
}
export interface SharedLayoutChoice { menuId:number; name:string; queryId:string; current:boolean }
export const readSharedLayoutChoices=(siteId:number,menuId:number,sourcePath:string):Promise<SharedLayoutChoice[]>=>
  http.get(`${base(siteId)}/shared-layout/sources`,{params:{menuId,sourcePath}});
export const previewSharedLayout=(siteId:number,body:SharedLayoutRequest):Promise<QuerySnapshot>=>
  http.post(`${base(siteId)}/shared-layout/preview`,body);
export const confirmSharedLayout=(siteId:number,body:SharedLayoutRequest):Promise<QuerySnapshot>=>
  http.post(`${base(siteId)}/shared-layout/confirm`,body);
const base=(siteId:number)=>`/api/admin/v2/sites/${siteId}/template-query`;
export const queryCandidates=(siteId:number,menuId:number,sourcePath:string,selector:string):Promise<QueryCandidates>=>http.get(`${base(siteId)}/candidates`,{params:{menuId,sourcePath,selector}});
export const queryFields=(siteId:number,menuId:number):Promise<QueryFields>=>http.get(`${base(siteId)}/fields`,{params:{menuId}});
export const confirmQueryBindings=(siteId:number,body:QueryMappingRequest):Promise<QuerySnapshot>=>http.post(`${base(siteId)}/plans`,body);
export const readQueryBindings=(siteId:number,id:string):Promise<QuerySnapshot>=>http.get(`${base(siteId)}/plans/${encodeURIComponent(id)}`);
export const compileQueryBindings=(siteId:number,id:string):Promise<CompiledQuery>=>http.get(`${base(siteId)}/plans/${encodeURIComponent(id)}/compiled`);

export interface QueryDisplaySource {id:string;kind:string;rawValue:string;selector:string;context:string;repeatGroup:string|null}
export interface QueryDisplayField {fieldName:string;name:string;fieldType:string}
export interface QueryTimelineSelectors {
  navigationSelector: string; navigationItemSelector: string; groupSelector: string;
  groupItemSelector: string; groupTitleSelector: string | null; rowSelector: string;
  rowTextSelector?: string | null; rowImageSelector?: string | null; rowSecondarySelector?: string | null;
}
export interface QueryTimelineLayout extends QueryTimelineSelectors {labels: string[]; sourceRows: number}
export interface QueryTimelinePresentation {
  timeline: QueryTimelineSelectors & {groupField: string; rowField: string; imageField?: string | null; secondaryField?: string | null; ascending: boolean};
  replaceGroupConfirmed: boolean;
  pagination?: null; actions?: null;
}
export interface QueryTimelineEvidence {candidates: QueryCandidates; layouts: QueryTimelineLayout[]; fields: QueryDisplayField[]}
export const queryTimelineEvidence = (siteId: number, menuId: number, sourcePath: string, sourcePlanId: string): Promise<QueryTimelineEvidence> =>
  http.get(`${base(siteId)}/timeline/evidence`, {params: {menuId, sourcePath, sourcePlanId}});
export interface QueryFormActionEvidence {sourceId:string;label:string;targetPath:string;targetTitle:string;fields:{inputId:string;parameterName:string;label:string}[];suggestedInputId:string|null}
export interface QueryFormAction {sourceId:string;fieldName:string;inputId:string;targetMenuId:number;formCategoryId?:number}
export type QueryFormActionTargetRequest = ({sourcePlanId:string;pairing:PairingRequest;cardId:string} | {detailPlanId:string}) & {action:QueryFormAction;formCategoryId?:number;expectedTemplate?:string|null;confirmed?:boolean};
export interface QueryFormActionTargetResult {templatePath:string;previousTemplate:string|null;publicUrl:string;applied:boolean;message:string;formCategoryId?:number|null;submissionReady?:boolean}
export interface QueryCarouselCounterEvidence {currentSample:string;totalSample:string;currentSelector:string;totalSelector:string;sourceIds:string[]}
export interface QueryPresentationEvidence {sourcePath:string;digest:string;containerSelector:string;cardSelector:string;originalCards:number;sources:QueryDisplaySource[];fields:QueryDisplayField[];warnings:string[];detailLinkSourceIds:string[];detailSourcePath?:string;actions?:QueryFormActionEvidence[];targetMenus?:{id:number;name:string}[];carouselCounter?:QueryCarouselCounterEvidence|null;paginationDraft?:QueryPagination|null}
export interface QueryCounter {selector:string;start:number;end:number}
export interface QueryPagination {containerSelector:string;numberSelector:string;activeClass:string;disabledClass:string;links:Record<string,string>;counters:Record<string,QueryCounter>;ignoredControls:Record<string,string>}
export interface QueryCategorySidebarRequest {selector:string;navigationPlanId:string}
export interface QueryCategorySidebar {selector:string;currentMenuId:number;navigationRevision:number;entries:{name:string;menuId:number}[]}
export interface QueryDeferredRegion {selector:string;reason:string}
export interface ConfirmedQueryDeferredRegion extends QueryDeferredRegion {sourceDigest:string;regionDigest:string}
export interface QueryFeaturedCarousel {selector:string;imageField:string;titleField:string;summaryField?:string}
export interface QueryPresentationRequest {pairing:PairingRequest;cardId:string;bindings:{sourceId:string;fieldName:string}[];sharedStatic:Record<string,string>;replaceGroupConfirmed:boolean;pagination:QueryPagination|null;unpaged?:true;fixedHeadingPlanId?:string;actions?:QueryFormAction[];categorySidebar?:QueryCategorySidebarRequest;deferredRegion?:QueryDeferredRegion;timeline?:QueryTimelinePresentation['timeline'];carouselCounter?:true;featuredCarousel?:QueryFeaturedCarousel}
export interface QueryPresentationPlan {request:QueryPresentationRequest|QueryTimelinePresentation;containerSelector:string;cardSelector:string;originalCards:number;sources:QueryDisplaySource[];fields:QueryDisplayField[];originalCardSelectors:string[];categorySidebar?:QueryCategorySidebar|null;deferredRegion?:ConfirmedQueryDeferredRegion|null;carouselCounter?:QueryCarouselCounterEvidence|null}
export const queryPresentationEvidence=(siteId:number,sourcePlanId:string,pairing:PairingRequest,cardId:string):Promise<QueryPresentationEvidence>=>http.post(`${base(siteId)}/presentation/evidence`,{sourcePlanId,pairing,cardId});
export const previewQueryFormTarget=(siteId:number,request:QueryFormActionTargetRequest):Promise<QueryFormActionTargetResult>=>http.post(`${base(siteId)}/prefill-target/preview`,{...request,confirmed:false});
export const applyQueryFormTarget=(siteId:number,request:QueryFormActionTargetRequest):Promise<QueryFormActionTargetResult>=>http.post(`${base(siteId)}/prefill-target/apply`,request);

