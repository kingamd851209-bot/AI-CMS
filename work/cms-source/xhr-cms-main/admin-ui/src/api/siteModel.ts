import { http } from '@/api/http';
import type { DomainAgentTask } from '@/api/domainAgent';
import type { FieldPlan, FieldPlanRequest } from '@/api/templateFieldPlan';
import type { RootApplicationOptions, RootApplicationResult } from '@/api/templateRootApplication';

export interface SiteModelField { key: string; name: string; type: string; fieldName: string | null }
export interface SiteContentModel { key: string; name: string; kind: string; menuId: number | null; fields: SiteModelField[] }
export interface SiteModelRecord { recordId: string; bindings: Record<string, string> }
export interface SiteModelMaintenance { mode: 'OWNED' | 'REFERENCE' | 'PAGE_LIST'; ownerModelKey: string; fieldKey: string | null; reason: string }
export interface SiteModelRegion { sourcePath: string; regionId: string; modelKey: string; mode: string; records: SiteModelRecord[]; maintenance?: SiteModelMaintenance | null }
export interface SiteModelRelation { sourceModelKey: string; fieldKey: string; targetModelKey: string | null; reason: string }
export interface SiteModelProposal { summary: string; models: SiteContentModel[]; regions: SiteModelRegion[]; relations: SiteModelRelation[]; issues: string[] }
export interface SiteModelPlan {
  id: string; siteId: number; packageVersion: string; catalogDigest: string; revision: number;
  status: 'DRAFT' | 'CONFIRMED'; proposal: SiteModelProposal; observedPages: number; totalPages: number; updatedAt: string;
  pages: { sourcePath: string; purpose: string; status: string }[];
}
export interface SiteModelReview { revision: number; packageVersion: string; proposal: SiteModelProposal; confirm: boolean }
export interface SiteNavigationObservation {
  origin: string; path: string; selector: string; href: string;
  targetPath: string | null; linkKind: 'LOCAL_PAGE' | 'ANCHOR' | 'PLACEHOLDER' | 'EXTERNAL' | 'UNRESOLVED';
}
export interface SiteNavigationEvidence {
  key: string; parentKey: string | null; name: string; order: number;
  sourcePath: string | null; evidencePath: string; evidenceSelector: string; sourceHref: string | null;
  observations?: SiteNavigationObservation[];
  independentLanding?: boolean;
}
export interface SiteNavigationNode extends SiteNavigationEvidence {
  kind: 'DIRECTORY' | 'SINGLE' | 'LIST'; modelKey: string | null;
  reuseMenuId: number | null; menuId: number | null; proposedUrl: string | null;
  contentStatus: 'HAS_CONTENT' | 'PENDING_MATCH' | 'MISSING_SOURCE' | 'NOT_REQUIRED';
  cmsName?: string | null; drift?: boolean; confirmReuse?: boolean;
}
/** 新建导航栏目需要从已经应用的模型复制已审字段。复用已有栏目的节点走另一条处理路径。 */
export function pendingNavigationModel(plan?: SiteModelPlan | null, navigation?: SiteNavigation | null) {
  if (plan?.status !== 'CONFIRMED' || navigation?.status !== 'CONFIRMED') return;
  const models = new Map(plan.proposal.models.map(model => [model.key, model]));
  for (const node of navigation.nodes) {
    if (node.menuId || node.reuseMenuId || !node.modelKey) continue;
    const model = models.get(node.modelKey);
    if (model && !model.menuId) return {node, model};
  }
}

export interface SiteNavigation {
  revision: number; planRevision: number;
  originPlanId?: string | null; originPlanRevision?: number | null;
  status: 'DRAFT' | 'CONFIRMED' | 'APPLYING' | 'APPLIED'; nodes: SiteNavigationNode[];
  supplementalEvidence?: SiteNavigationEvidence[];
}
/** 已应用且原页归属唯一的单页可以直接按整包审核建立栏目字段。 */
export function usesPageFieldReview(nav:SiteNavigation|null|undefined, node:SiteNavigationNode, rootMenuId?:number) {
  if (nav?.status!=='APPLIED' || !rootMenuId || !node.menuId || node.menuId===rootMenuId
    || node.kind!=='SINGLE' || node.modelKey || node.drift || !node.sourcePath) return false;
  return nav.nodes.some(row=>row.key===node.key && row.menuId===node.menuId && row.sourcePath===node.sourcePath)
    && !nav.nodes.some(row=>row.parentKey===node.key || row.key!==node.key
      && (row.sourcePath===node.sourcePath || row.menuId===node.menuId));
}

export function retainedNavigationLink(nav:SiteNavigation, entry:SiteNavigationEvidence) {
  const observed=entry.observations || [];
  const first=observed[0];
  if (!first || !['EXTERNAL','ANCHOR'].includes(first.linkKind) || !first.href.trim()) return false;
  return observed.every(item=>item.linkKind===first.linkKind && item.href.trim()===first.href.trim())
    && !nav.nodes.some(node=>node.parentKey===entry.key)
    && !nav.supplementalEvidence?.some(node=>node.parentKey===entry.key);
}
export interface NavigationAppendScope {
  packageVersion:string; planRevision:number; navigationRevision:number; nodeKey:string;
  kind?:SiteNavigationNode['kind']; modelKey?:string; reuseMenuId?:number; confirmReuse?:boolean;
}
export interface NavigationAppendPreview {
  originPlanId:string; originPlanRevision:number; navigationRevision:number; previewDigest:string;
  node:SiteNavigationNode; nodes:SiteNavigationNode[]; parent:SiteNavigationNode|null;
}
export interface NavigationKindCorrectionScope extends NavigationContinuationScope {
  nodeKey:string;
}
export interface NavigationKindCorrectionPreview {
  nodeKey:string; menuId:number; label:string; sourcePath:string;
  fromKind:'SINGLE'; toKind:'LIST'; eligible:boolean; message:string; previewToken:string|null;
  listEvidence:{listSelector:string;cardCount:number;detailPath:string;detailHeading:string;matchingCards:number}|null;
}
export interface NavigationContinuationScope {
  packageVersion:string; planRevision:number; navigationRevision:number;
}
export interface NavigationContinuationPreview {
  sourcePlanRevision:number; planRevision:number; navigationRevision:number; nodeCount:number; previewDigest:string;
  modelLinks?: {nodeKey:string; menuId:number; oldModelKey:string|null; newModelKey:string}[];
  modelLinkIssues?: {nodeKey:string; menuId:number; candidateModelKey:string; message:string}[];
}
export interface WorkbenchApplyIssue {code:string;message:string;nodeKey?:string|null;modelKey?:string|null}
export interface WorkbenchApplyScope {packageVersion:string;planRevision:number;navigationRevision:number;packageRevision:number;reuseOwners?:Record<string,string>}
export interface WorkbenchApplyPreview {
  planRevision:number;navigationRevision:number;packageRevision:number;nodeCount:number;ready:boolean;token?:string|null;
  pendingRequestId?:string|null;pendingToken?:string|null;
  models:{modelKey:string;name:string;menuId:number|null;ownerNodeKey:string|null;candidates:{nodeKey:string;name:string;sourcePath:string|null}[]}[];
  issues:WorkbenchApplyIssue[];
}
export interface WorkbenchApplyRequest extends WorkbenchApplyScope {token:string;requestId:string;confirmed:true}
export interface WorkbenchApplyResult {
  status:'APPLIED'|'BLOCKED'|'PARTIAL'|'CLOSED';planRevision:number;navigationRevision:number;packageRevision:number;
  nodes:SiteNavigationNode[];issues:WorkbenchApplyIssue[];requestId:string;
}
export const workbenchApplyLabel='核对并应用栏目与字段';
export const workbenchApplyResumeLabel='核对或继续上次栏目应用';
export interface NavigationModelFieldEvidence {sourcePath:string;resultIndex:number;unitId:string;sourceIds:string[];sourceKinds?:string[]}
export interface NavigationModelDraftField {key:string;name:string;type:string;evidence:NavigationModelFieldEvidence[]}
export interface NavigationModelDraft {
  title:string;sourcePath:string;mode:'CREATE'|'EXTEND';existingModel?:SiteContentModel|null;
  model:SiteContentModel|null;fields:NavigationModelDraftField[];
  landingModel?:SiteContentModel|null;landingFields?:NavigationModelDraftField[];
  warnings:string[];blocked:string|null;fieldRevision:number;
}
const modelDraftRoute=(siteId:number,plan:SiteModelPlan)=>`${route(siteId)}/plans/${encodeURIComponent(plan.id)}/navigation/model-draft`;
export type ParentEntryRole = 'DIRECTORY' | 'REDIRECT' | 'INDEPENDENT_OVERVIEW';
export interface ParentEntryDecision {
  nodeKey: string;
  role: ParentEntryRole;
  targetNodeKey?: string | null;
  valid: boolean;
  reason?: string | null;
}
export interface ParentEntryDecisions { revision: number; items: ParentEntryDecision[] }
type ParentEntrySelection = Pick<ParentEntryDecision, 'nodeKey' | 'role' | 'targetNodeKey'>;
export type NavigationLandingCardState = 'READY' | 'FRONT_DISABLED' | 'ROUTE_MISSING' | 'TEMPLATE_MISSING' | 'ROUTE_CONFLICT' | 'INDEPENDENT_SOURCE_REQUIRED' | 'QUERY_REQUIRED' | 'ANCHOR_MISSING' | 'DISPLAY_ONLY';
export interface NavigationLandingSelection { sourcePath: string; nodeKey: string; containerSelector?: string }
export interface NavigationLandingCandidate { selector: string; labels: string[] }
export interface NavigationLandingCard {
  selector: string; nodeKey: string; menuId: number; label: string; rawHref: string;
  imageSource: string; summary: string; state: NavigationLandingCardState; publicUrl: string | null;
}
export interface NavigationLandingPreview extends NavigationLandingSelection {
  containerSelector: string;
  sourceDigest: string; navigationRevision: number; planRevision: number;
  menuId: number; label: string; cards: NavigationLandingCard[]; digest: string;
}
export interface NavigationLandingApplication {
  applied: boolean; templatePath: string | null; publicUrl: string | null;
  requiresRouteReload: boolean; message: string;
  displayOnlyNodeKeys?: string[];
}
export interface SiteModelStructureResult {
  plan: SiteModelPlan;
  modelKey: string;
  menuId: number;
  fields: Record<string, string>;
  reused: boolean;
  initializationPending: boolean;
  publicationPending: boolean;
}
export interface SiteModelSupplementPreview {
  menuId: number; beforeDigest: string; afterDigest: string; additions: SiteModelField[];
}
export interface SiteModelOwnedInstance { parentMenuId: number; menuId: number | null }
export interface SiteModelReferenceScope {
  menuId: number; fieldName: string | null; currentMenuIds: number[]; targetMenuIds: number[]; query: string; previewDigest: string;
}
export interface SiteModelRecordSeed {
  sourcePath: string;
  regionId: string;
  modelKey: string;
  menuId: number;
  ownerRecordId?: number | null;
  ready: boolean;
  previewDigest: string;
  issues: string[];
  records: {
    sourceRecordId: string;
    seedKey: string;
    contentId: number | null;
    status: 'PENDING' | 'EXISTING' | 'CREATED' | 'BLOCKED' | 'REMOVED';
    issues: string[];
    fields: { fieldName: string; label: string; rawValue: string | number | null; value: string | number | null }[];
  }[];
}
export interface SiteEvidenceValue { id: string; selector: string; attribute: string; rawValue: string; typeHint: string }
export interface SiteEvidenceRegion {
  id: string; kind: string; selector: string; warnings: string[];
  records: { id: string; order: number; selector: string; values: SiteEvidenceValue[]; warnings: string[] }[];
}
export interface SiteFragmentLink { selector: string; rawValue: string; targetPath: string | null }
export interface SitePageSummary { sourcePath: string; warnings: string[]; includedPaths: string[]; fragmentLinks: SiteFragmentLink[] }
export interface SitePageEvidence extends SitePageSummary { digest: string; regions: SiteEvidenceRegion[] }
export interface SiteModelCatalog {
  packageVersion: string; pages: SitePageSummary[]; warnings: string[];
  sharedFragments: { kind: string; digest: string; occurrences: { sourcePath: string; selector: string }[] }[];
}
export interface HomeBannerSelection { sourcePath: string; regionId: string }
export type HomeBannerState = 'READY' | 'APPLIED' | 'OTHER_ACTIVE' | 'REVIEW_REQUIRED' | 'SOURCE_CHANGED'
  | 'NOT_INITIALIZED' | 'ROOT_NOT_PUBLIC' | 'TARGET_NOT_PUBLIC' | 'BLOCKED';
export interface HomeBannerOptions extends RootApplicationOptions {
  ready: boolean; state: HomeBannerState; publishedRecords: number | null; maxVisibleRecords: number;
}
const route = (siteId: number) => `/api/admin/v2/sites/${siteId}/template-seed/site-model`;
const navigationRoute = (siteId: number, plan: SiteModelPlan) => `${route(siteId)}/plans/${encodeURIComponent(plan.id)}/navigation`;
const recordSeedRoute = (siteId: number, plan: SiteModelPlan) => `${route(siteId)}/plans/${encodeURIComponent(plan.id)}/record-seed`;
const referenceScopeRoute = (siteId: number, plan: SiteModelPlan) => `${route(siteId)}/plans/${encodeURIComponent(plan.id)}/structure/reference-scope`;
const homeBannerRoute = (siteId: number, plan: SiteModelPlan) => `${route(siteId)}/plans/${encodeURIComponent(plan.id)}/public-home`;
const referenceScopeParams = (plan: SiteModelPlan, modelKey: string, fieldKey: string, menuId: number) => ({
  packageVersion: plan.packageVersion, revision: plan.revision, modelKey, fieldKey, menuId,
});
const recordSeedScope = (plan: SiteModelPlan, region: SiteModelRegion) => ({
  packageVersion: plan.packageVersion, revision: plan.revision, sourcePath: region.sourcePath, regionId: region.regionId,
});
export const siteModelApi = {
  navigationLandingCandidates: (siteId: number, plan: SiteModelPlan, selection: Omit<NavigationLandingSelection, 'containerSelector'>) =>
    http.get<NavigationLandingCandidate[]>(`${route(siteId)}/plans/${encodeURIComponent(plan.id)}/navigation-landing/candidates`, {
      params: { packageVersion: plan.packageVersion, ...selection },
    }),
  inspectNavigationLanding: (siteId: number, plan: SiteModelPlan, selection: NavigationLandingSelection) =>
    http.get<NavigationLandingApplication>(`${route(siteId)}/plans/${encodeURIComponent(plan.id)}/public-navigation-landing`, {
      params: { packageVersion: plan.packageVersion, ...selection },
    }),
  applyNavigationLanding: (siteId: number, plan: SiteModelPlan, preview: NavigationLandingPreview,
    expectedTemplate: string | null, routeUrl: string, displayOnlyNodeKeys: string[]) =>
    http.post<NavigationLandingApplication>(`${route(siteId)}/plans/${encodeURIComponent(plan.id)}/public-navigation-landing`, {
      packageVersion: plan.packageVersion, sourcePath: preview.sourcePath, nodeKey: preview.nodeKey,
      containerSelector: preview.containerSelector, previewDigest: preview.digest, expectedTemplate, routeUrl, confirmed: true, displayOnlyNodeKeys,
    }),
  withdrawNavigationLanding: (siteId: number, plan: SiteModelPlan, selection: NavigationLandingSelection) =>
    http.post<NavigationLandingApplication>(`${route(siteId)}/plans/${encodeURIComponent(plan.id)}/public-navigation-landing/withdraw`, {
      packageVersion: plan.packageVersion, sourcePath: selection.sourcePath, nodeKey: selection.nodeKey, confirmed: true,
    }),
  navigationLanding: (siteId: number, plan: SiteModelPlan, selection: NavigationLandingSelection) =>
    http.get<NavigationLandingPreview>(`${route(siteId)}/plans/${encodeURIComponent(plan.id)}/navigation-landing`, {
      params: { packageVersion: plan.packageVersion, ...selection },
    }),
  latest: (siteId: number) => http.get<SiteModelPlan | null>(`${route(siteId)}/plans/latest`),
  get: (siteId: number, id: string) => http.get<SiteModelPlan>(`${route(siteId)}/plans/${encodeURIComponent(id)}`),
  homeBanner: (siteId: number, plan: SiteModelPlan, selection: HomeBannerSelection) =>
    http.get<HomeBannerOptions>(homeBannerRoute(siteId, plan), {
      params: { packageVersion: plan.packageVersion, revision: plan.revision, ...selection },
    }),
  applyHomeBanner: (siteId: number, plan: SiteModelPlan, selection: HomeBannerSelection, aliases: string[]) =>
    http.post<RootApplicationResult>(homeBannerRoute(siteId, plan), {
      packageVersion: plan.packageVersion, revision: plan.revision, ...selection, aliases, confirmed: true,
    }),
  withdrawHomeBanner: (siteId: number, plan: SiteModelPlan, selection: HomeBannerSelection) =>
    http.post<RootApplicationResult>(`${homeBannerRoute(siteId, plan)}/withdraw`, {
      packageVersion: plan.packageVersion, revision: plan.revision, ...selection, confirmed: true,
    }),
  catalog: (siteId: number, packageVersion: string) =>
    http.get<SiteModelCatalog>(`${route(siteId)}/catalog`, { params: { packageVersion } }),
  review: (siteId: number, id: string, review: SiteModelReview) =>
    http.post<SiteModelPlan>(`${route(siteId)}/plans/${encodeURIComponent(id)}/review`, review),
  navigation: (siteId: number, plan: SiteModelPlan, signal?: AbortSignal) =>
    http.get<SiteNavigation>(navigationRoute(siteId, plan), { params: { packageVersion: plan.packageVersion }, timeout: 60000, signal }),
  previewNavigationContinuation: (siteId:number,plan:SiteModelPlan,scope:NavigationContinuationScope) =>
    http.post<NavigationContinuationPreview>(`${navigationRoute(siteId,plan)}/continuation/preview`,scope,{timeout:60000}),
  applyNavigationContinuation: (siteId:number,plan:SiteModelPlan,scope:NavigationContinuationScope & {previewDigest:string;confirmed:true}) =>
    http.post<SiteNavigation>(`${navigationRoute(siteId,plan)}/continuation/apply`,scope,{timeout:120000}),
  previewNavigationAppend: (siteId:number,plan:SiteModelPlan,scope:NavigationAppendScope,signal:AbortSignal) =>
    http.post<NavigationAppendPreview>(`${navigationRoute(siteId,plan)}/append/preview`,{confirmed:false,confirmReuse:false,...scope},{signal,timeout:60000}),
  applyNavigationAppend: (siteId:number,plan:SiteModelPlan,scope:NavigationAppendScope & {previewDigest:string;confirmed:true}) =>
    http.post<{planId:string;navigation:SiteNavigation}>(`${navigationRoute(siteId,plan)}/append/apply`,{confirmReuse:false,...scope},{timeout:120000}),
  previewNavigationKindCorrection: (siteId:number,plan:SiteModelPlan,scope:NavigationKindCorrectionScope) =>
    http.post<NavigationKindCorrectionPreview>(`${navigationRoute(siteId,plan)}/kind-correction/preview`,{...scope,confirm:false},{timeout:60000}),
  applyNavigationKindCorrection: (siteId:number,plan:SiteModelPlan,scope:NavigationKindCorrectionScope & {previewToken:string;confirm:true}) =>
    http.post<{planId:string;navigation:SiteNavigation}>(`${navigationRoute(siteId,plan)}/kind-correction/apply`,scope,{timeout:120000}),
  previewPlaceholderModel: (siteId:number,plan:SiteModelPlan,navigationRevision:number,nodeKey:string) =>
    http.post<{sourcePath:string;nodeName:string;menuId:number;fields:Array<{name:string;type:string;selector:string;value:string}>;digest:string}>(
      `${navigationRoute(siteId,plan)}/placeholder-model/preview`,{packageVersion:plan.packageVersion,planRevision:plan.revision,navigationRevision,nodeKey},{timeout:60000}),
  confirmPlaceholderModel: (siteId:number,plan:SiteModelPlan,navigationRevision:number,nodeKey:string,digest:string) =>
    http.post<SiteModelPlan>(`${navigationRoute(siteId,plan)}/placeholder-model/confirm`,
      {packageVersion:plan.packageVersion,planRevision:plan.revision,navigationRevision,nodeKey,digest},{timeout:60000}),
  previewWorkbenchApply: (siteId:number,id:string,scope:WorkbenchApplyScope,signal:AbortSignal) =>
    http.post<WorkbenchApplyPreview>(`${route(siteId)}/plans/${encodeURIComponent(id)}/apply-workbench/preview`,scope,{timeout:65000,signal}),
  applyWorkbench: (siteId:number,id:string,scope:WorkbenchApplyRequest,signal:AbortSignal) =>
    http.post<WorkbenchApplyResult>(`${route(siteId)}/plans/${encodeURIComponent(id)}/apply-workbench/apply`,scope,{timeout:120000,signal}),
  closeWorkbenchApply: (siteId:number,id:string,scope:WorkbenchApplyRequest,signal:AbortSignal) =>
    http.post<WorkbenchApplyResult>(`${route(siteId)}/plans/${encodeURIComponent(id)}/apply-workbench/close`,scope,{timeout:65000,signal}),
  previewNavigationModel: (siteId:number,plan:SiteModelPlan,navigationRevision:number,nodeKey:string,signal:AbortSignal) =>
    http.post<NavigationModelDraft>(`${modelDraftRoute(siteId,plan)}/preview`,{packageVersion:plan.packageVersion,planRevision:plan.revision,navigationRevision,nodeKey},{timeout:60000,signal}),
  saveNavigationModel: (siteId:number,plan:SiteModelPlan,navigationRevision:number,nodeKey:string,fieldRevision:number,model:SiteContentModel,signal:AbortSignal) =>
    http.post<SiteModelPlan>(`${modelDraftRoute(siteId,plan)}/save`,{packageVersion:plan.packageVersion,planRevision:plan.revision,navigationRevision,nodeKey,fieldRevision,model},{timeout:60000,signal}),
  entryDecisions: (siteId: number, plan: SiteModelPlan, navigationRevision: number, signal?: AbortSignal) =>
    http.get<ParentEntryDecisions>(`${route(siteId)}/plans/${encodeURIComponent(plan.id)}/parent-entry-decisions`, {
      params: { packageVersion: plan.packageVersion, planRevision: plan.revision, navigationRevision }, timeout: 60000, signal,
    }),
  saveEntryDecision: (siteId: number, plan: SiteModelPlan, navigationRevision: number, expectedRevision: number, selection: ParentEntrySelection) =>
    http.post<ParentEntryDecisions>(`${route(siteId)}/plans/${encodeURIComponent(plan.id)}/parent-entry-decisions`, {
      packageVersion: plan.packageVersion, planRevision: plan.revision, navigationRevision, expectedRevision, ...selection,
    }, { timeout: 60000 }),
  reviewNavigation: (siteId: number, plan: SiteModelPlan, revision: number, nodes: SiteNavigationNode[]) =>
    http.post<SiteNavigation>(`${navigationRoute(siteId, plan)}/review`, {
      packageVersion: plan.packageVersion, revision, nodes, confirmed: true,
    }),
  applyNavigation: (siteId: number, plan: SiteModelPlan, revision: number) =>
    http.post<SiteNavigation>(`${navigationRoute(siteId, plan)}/apply`, {
      packageVersion: plan.packageVersion, revision, confirmed: true,
    }),
  applyStructure: (siteId: number, plan: SiteModelPlan, modelKey: string, parentMenuId?: number) =>
    http.post<SiteModelStructureResult>(`${route(siteId)}/plans/${encodeURIComponent(plan.id)}/apply-structure`, {
      packageVersion: plan.packageVersion, revision: plan.revision, modelKey, parentMenuId,
    }),
  ownedInstances: (siteId: number, plan: SiteModelPlan, modelKey: string) =>
    http.get<SiteModelOwnedInstance[]>(`${route(siteId)}/plans/${encodeURIComponent(plan.id)}/structure/owned-instances`, {
      params: { packageVersion: plan.packageVersion, revision: plan.revision, modelKey },
    }),
  referenceScope: (siteId: number, plan: SiteModelPlan, modelKey: string, fieldKey: string, menuId: number) =>
    http.get<SiteModelReferenceScope>(referenceScopeRoute(siteId, plan), { params: referenceScopeParams(plan, modelKey, fieldKey, menuId) }),
  applyReferenceScope: (siteId: number, plan: SiteModelPlan, modelKey: string, fieldKey: string, preview: SiteModelReferenceScope) =>
    http.post<SiteModelReferenceScope>(referenceScopeRoute(siteId, plan), {
      ...referenceScopeParams(plan, modelKey, fieldKey, preview.menuId), confirmed: true, previewDigest: preview.previewDigest,
    }),
  previewFieldSupplement: (siteId:number, plan:SiteModelPlan, modelKey:string) =>
    http.post<SiteModelSupplementPreview>(`${route(siteId)}/plans/${encodeURIComponent(plan.id)}/field-supplement/preview`, {
      packageVersion:plan.packageVersion, revision:plan.revision, modelKey, confirmed:false,
    }),
  applyFieldSupplement: (siteId:number, plan:SiteModelPlan, modelKey:string, preview:SiteModelSupplementPreview) =>
    http.post<SiteModelStructureResult>(`${route(siteId)}/plans/${encodeURIComponent(plan.id)}/field-supplement/apply`, {
      packageVersion:plan.packageVersion, revision:plan.revision, modelKey,
      beforeDigest:preview.beforeDigest, afterDigest:preview.afterDigest, confirmed:true,
    }),
  sharedFields: (siteId:number, plan:SiteModelPlan, modelKey:string, menuId:number, preview?:SiteModelSupplementPreview) =>
    http.post<SiteModelSupplementPreview>(`${route(siteId)}/plans/${encodeURIComponent(plan.id)}/field-supplement/shared`, {
      packageVersion:plan.packageVersion, selection:{ revision:plan.revision, modelKey, menuId,
        beforeDigest:preview?.beforeDigest, afterDigest:preview?.afterDigest, confirmed:!!preview },
    }),
  recordSeed: (siteId: number, plan: SiteModelPlan, region: SiteModelRegion, ownerRecordId?: number, menuId?: number) =>
    http.get<SiteModelRecordSeed>(recordSeedRoute(siteId, plan), { params: { ...recordSeedScope(plan, region), ownerRecordId, menuId } }),
  importRecordSeed: (siteId: number, plan: SiteModelPlan, region: SiteModelRegion, previewDigest: string, ownerRecordId?: number, menuId?: number) =>
    http.post<SiteModelRecordSeed>(recordSeedRoute(siteId, plan), {
      ...recordSeedScope(plan, region), ownerRecordId, menuId, previewDigest, confirmed: true,
    }),
  fieldPlan: (siteId: number, plan: SiteModelPlan, modelKey: string, request: FieldPlanRequest) =>
    http.post<FieldPlan>(`${route(siteId)}/plans/${encodeURIComponent(plan.id)}/field-plans`, {
      packageVersion: plan.packageVersion, revision: plan.revision, modelKey, request,
    }),
  evidence: (siteId: number, packageVersion: string, sourcePath: string) =>
    http.get<SitePageEvidence>(`${route(siteId)}/evidence`, { params: { packageVersion, sourcePath } }),
  start: (siteId: number, packageVersion: string, requestId: string, basePlanId?: string, basePlanRevision?: number) =>
    http.post<DomainAgentTask>(`${route(siteId)}/tasks`, { packageVersion, requestId, basePlanId, basePlanRevision }),
  task: (siteId: number, taskId: string) => http.get<DomainAgentTask>(`${route(siteId)}/tasks/${encodeURIComponent(taskId)}`),
  cancel: (siteId: number, taskId: string) => http.delete<DomainAgentTask>(`${route(siteId)}/tasks/${encodeURIComponent(taskId)}`),
  recoverDraft: (siteId: number, taskId: string, packageVersion: string) =>
    http.post<SiteModelPlan>(`${route(siteId)}/tasks/${encodeURIComponent(taskId)}/recover-draft`, { packageVersion }),
};
