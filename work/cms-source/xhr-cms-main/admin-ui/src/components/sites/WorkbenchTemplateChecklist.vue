<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { http } from '@/api/http';
import { getSiteRoutes, getSiteServiceStatus, type RouteListView } from '@/api/templateRecognition';
import { siteModelApi, retainedNavigationLink, workbenchApplyLabel, workbenchApplyResumeLabel, type ParentEntryDecision, type ParentEntryDecisions, type SiteModelCatalog, type SiteModelPlan, type SiteNavigation,
  type SiteNavigationNode } from '@/api/siteModel';
import type { MenuNode } from '@/api/uiPackage';
import ParentEntryDecisionEditor from './ParentEntryDecisionEditor.vue';
import EntryDecisionSourcePreview from './EntryDecisionSourcePreview.vue';
import EntryDecisionEvidence from './EntryDecisionEvidence.vue';
import type { ReleaseAction, ReleaseNavigationEvidence, ReleasePage, ReleaseScope } from './WorkbenchReleaseReview.vue';

const props = defineProps<{
  position?:ChecklistPosition; siteId:number; packageVersion?:string; plan:SiteModelPlan|null; navigation:SiteNavigation|null;
  columns:MenuNode[]; rootMenuId?:number; pages:string[]; disabled?:boolean; packagePlanId?:string|null;
  navigationEvidence?:ReleaseNavigationEvidence;
}>();
export type ChecklistPosition = { siteId:number; version?:string; planId:string; rowKey:string; search:string; scope:'all'|RowState; stage:string; impact:string; page:number };
type ChecklistStructure = { plan: SiteModelPlan; navigation: SiteNavigation };
type ActionKind = 'structure' | 'entry' | 'recognize' | 'field' | 'list' | 'landing' | 'publish' | 'navigation-links' | 'form';
type ChecklistTarget = { sourcePath: string | null; menuId: number | null; nodeId: string | null; action: ActionKind; label: string; reason: string; fieldPlanId?: string; initialize?: boolean };
type BusinessKind = 'list' | 'field' | 'landing';
type BusinessTarget = { menuId: number; sourcePath: string; nodeKey?: string; kind: BusinessKind };
type BusinessEvidence = BusinessTarget & {
  state: 'VERIFIED' | 'UNVERIFIED';
  recordCount?: number | null;
  listSampleOwnership?: 'CURRENT_MENU' | 'OTHER_MENU' | 'UNKNOWN' | null;
  sampleOwnerMenuId?: number | null;
  fieldPlanId?: string | null;
  fieldPhase?: 'INITIALIZED' | 'CONFIRMED' | 'SOURCE_CONFLICT' | 'NONE' | null;
  landingCandidateCount?: number | null;
  reason?: string | null;
};
const emit = defineEmits<{
  open: [sourcePath: string, menuId: number, step: number, context: ChecklistStructure, target: ChecklistTarget];
  page: [path: string, target: ChecklistTarget];
  structure: [context: ChecklistStructure, target: ChecklistTarget];
  prerequisite: [context: ChecklistStructure];
  packageReview: [];
  manageContent: [menuId:number];
  remember: [position:ChecklistPosition];
  clearNavigationEvidence: [];
}>();
const running = ref<boolean|null>(null);
const routes = ref<RouteListView|null>(null);
const reading = ref(false);
const error = ref('');
const sharedFragments=ref<SiteModelCatalog['sharedFragments']|null>(null);
const includedPaths = ref<Set<string>>(new Set());
const handoff = ref<HandoffStructure|null>(null);
const evidenceTime = ref(0);
const structure = ref<ChecklistStructure | null>(null);
const pendingStructure = ref<ChecklistStructure | null>(null);
const prerequisiteAction = computed(() => {
  const pending = pendingStructure.value;
  if (!props.packageVersion) return '上传页面包';
  if (!pending) return '打开整包分析与审核';
  if (pending.plan.status !== 'CONFIRMED') return '审核整站内容模型';
  if (!pending.navigation.nodes.length) return '核对结构与栏目创建条件';
  if (pending.navigation.status === 'CONFIRMED') return workbenchApplyLabel;
  if (pending.navigation.status === 'APPLYING') return workbenchApplyResumeLabel;
  return '审核导航与栏目对应';
});
const readingStructure = ref(false);
const structureError = ref('');
const businessState = ref<'unread'|'loading'|'ready'|'failed'>('unread');
const businessEvidence = ref(new Map<string, BusinessEvidence>());
const failedBusinessTargets = ref(new Set<string>());
const businessError = ref('');
const entryDecisions=ref(new Map<string,ParentEntryDecision>());
const entryError=ref('');
let entryRevision=-1;
const checklistHeading=ref<HTMLElement>();
const entryEditor=ref<InstanceType<typeof ParentEntryDecisionEditor>|null>(null);
const entryDialog=ref<{context:ChecklistStructure;node:SiteNavigationNode;title:string;reason:string}>();
const entryVisible=ref(false);
let entryTrigger:HTMLElement|null=null;
type HandoffStructure = {
  siteId: number;
  packageVersion: string;
  plans: { id: string; status: string }[];
  applications: { menuId: number; slot: string; packageVersion: string; planId: string | null; state: string;
    templatePath: string; route: string | null; sourcePath: string | null; hasFrontChannel: boolean }[];
};
let version=0;
let structureVersion=0;
let structureRequest: AbortController | undefined;
let evidenceVersion=0;
let businessRequest: AbortController | undefined;
let handoffRequest: { siteId: number; packageVersion: string; request: Promise<HandoffStructure> } | undefined;
function reset() {
  version++;
  evidenceVersion++;
  structureRequest?.abort();
  structureRequest=undefined;
  businessRequest?.abort();
  businessRequest=undefined;
  running.value=null;
  routes.value=null;
  reading.value=false;
  error.value='';
  sharedFragments.value=null;
  includedPaths.value=new Set();
  handoff.value=null;
  handoffRequest=undefined;
  evidenceTime.value=0;
  businessState.value='unread';
  businessEvidence.value=new Map();
  failedBusinessTargets.value=new Set();
  businessError.value='';
  entryDecisions.value=new Map();
  entryRevision=-1;
  entryError.value='';
}
function readHandoff(siteId: number, packageVersion: string) {
  if (handoffRequest?.siteId === siteId && handoffRequest.packageVersion === packageVersion) return handoffRequest.request;
  const request = http.get<HandoffStructure>(`/api/admin/v2/sites/${siteId}/workbench-context/handoff`).then(result => {
    if (result.siteId !== siteId || result.packageVersion !== packageVersion) throw new Error('交接摘要的站点或页面包已变化。');
    return result;
  }).finally(() => { if (handoffRequest?.request === request) handoffRequest=undefined; });
  handoffRequest={siteId,packageVersion,request};
  return request;
}
onBeforeUnmount(()=>{reset();structureVersion++;});

async function readStructure() {
  const token = ++structureVersion;
  structureRequest?.abort();
  structureRequest=undefined;
  const { siteId, packageVersion, rootMenuId, plan: selected } = props;
  structure.value = null;
  pendingStructure.value = null;
  structureError.value = '';
  readingStructure.value = !!(siteId && packageVersion);
  if (!siteId || !packageVersion) return;
  const request = new AbortController();
  structureRequest=request;
  const current = () => token === structureVersion && props.siteId === siteId && props.packageVersion === packageVersion && props.rootMenuId === rootMenuId;
  const sameScope = (plan: SiteModelPlan | null): plan is SiteModelPlan =>
    !!plan && plan.siteId === siteId && plan.packageVersion === packageVersion;
  const tried = new Set<string>();
  const resolve = async (candidate: SiteModelPlan | null) => {
    if (!current() || !sameScope(candidate) || tried.has(candidate.id)) return null;
    tried.add(candidate.id);
    const navigation = await siteModelApi.navigation(siteId, candidate, request.signal);
    if (!current()) return null;
    if (navigation.status !== 'APPLIED') {
      if (!pendingStructure.value) pendingStructure.value = {plan:candidate,navigation};
      return null;
    }
    const ownerId = navigation.originPlanId;
    const owner = ownerId ? await siteModelApi.get(siteId, ownerId) : candidate;
    if (!current()) return null;
    if (!sameScope(owner) || owner.status !== 'CONFIRMED') throw new Error('已应用导航的确认计划目前无法核实。');
    if (!ownerId) return { plan: owner, navigation };
    const applied = await siteModelApi.navigation(siteId, owner, request.signal);
    if (!current()) return null;
    if (applied.status !== 'APPLIED') throw new Error('原导航回执已变化，请重新读取。');
    // 已独立保存的派生导航包含实际变更，沿用来源回执时才返回原结构。
    if (candidate.status==='CONFIRMED' && navigation.planRevision===candidate.revision &&
      navigation.revision>applied.revision) return {plan:candidate,navigation};
    if (applied.originPlanId && (navigation.revision!==applied.revision || navigation.originPlanRevision!==owner.revision))
      throw new Error('原导航回执已变化，请重新读取。');
    return { plan: owner, navigation: applied };
  };
  let failure: unknown;
  try {
    if (props.packagePlanId) {
      const plan = await siteModelApi.get(siteId, props.packagePlanId);
      if (!current()) return;
      if (!sameScope(plan)) throw new Error('整包草稿的结构方案已变化，请重新读取。');
      const active = await resolve(plan);
      if (current()) structure.value = active;
      return;
    }
    const preferred = sameScope(selected) && selected.status==='CONFIRMED' ? selected : null;
    if (preferred) {
      try {
        const confirmed = await resolve(await siteModelApi.get(siteId, preferred.id));
        if (!current()) return;
        if (confirmed) { structure.value = confirmed; return; }
      }
      catch (cause) { failure = cause; }
      if (!current()) return;
    }
    if(rootMenuId) {
      try {
        const handoff = await readHandoff(siteId, packageVersion);
        if (!current()) return;
        const confirmedIds = new Set(handoff.plans.filter(plan => plan.status === 'CONFIRMED').map(plan => plan.id));
        const homePlan = handoff.applications.find(entry => entry.menuId === rootMenuId && entry.slot === 'LIST' &&
          entry.state === 'TEMPLATE_VERIFIED' && entry.packageVersion === packageVersion && entry.planId && confirmedIds.has(entry.planId));
        if (homePlan?.planId && !tried.has(homePlan.planId)) {
          const verified = await resolve(await siteModelApi.get(siteId, homePlan.planId));
          if (!current()) return;
          if (verified) { structure.value = verified; return; }
        }
      } catch(cause) {
        failure=cause;
      }
    }
    if (!current()) return;
    try {
      const latest = await siteModelApi.latest(siteId);
      if (!current()) return;
      const confirmed = await resolve(latest);
      if (!current()) return;
      if (confirmed) { structure.value = confirmed; return; }
      if (!latest && !preferred && !failure) return;
    } catch (cause) {
      failure = cause;
    }
    if (failure && !pendingStructure.value) throw failure;
  } catch (cause) {
    if (current()) structureError.value = cause instanceof Error ? cause.message : '读取已应用导航失败。';
  } finally {
    if(structureRequest===request)structureRequest=undefined;
    if (current()) readingStructure.value = false;
  }
}


async function readRuntime() {
  const {siteId,packageVersion}=props;
  if(!siteId || !packageVersion)return;
  const token=++version;
  reading.value=true;
  error.value='';
  const [status,registry,summary,catalog]=await Promise.allSettled([
    getSiteServiceStatus(siteId),getSiteRoutes(siteId),readHandoff(siteId,packageVersion),siteModelApi.catalog(siteId,packageVersion),
  ]);
  if(token!==version || props.siteId!==siteId || props.packageVersion!==packageVersion)return;
  if(status.status==='fulfilled')running.value=status.value.status==='RUNNING';
  if(registry.status==='fulfilled')routes.value=registry.value;
  if(summary.status==='fulfilled')handoff.value=summary.value;
  if(catalog.status==='fulfilled' && catalog.value.packageVersion===packageVersion){
    sharedFragments.value=catalog.value.sharedFragments;
    includedPaths.value=new Set(catalog.value.pages.flatMap(page=>page.includedPaths));
  }
  const failures = [status.status==='rejected'?'站点服务':'',registry.status==='rejected'?'运行路由':'',
    summary.status==='rejected'?'已发布回执':'',catalog.status==='rejected'||catalog.value.packageVersion!==packageVersion?'原包片段':''].filter(Boolean);
  if(failures.length)error.value=`未能更新${failures.join('、')}，可重新读取。`;
  if(failures.length<4)evidenceTime.value=Date.now();
  reading.value=false;
}
async function refreshEvidence(readNavigation = true) {
  const token=++evidenceVersion;
  businessRequest?.abort();
  businessRequest=undefined;
  businessState.value='unread';
  businessEvidence.value=new Map();
  failedBusinessTargets.value=new Set();
  businessError.value='';
  entryDecisions.value=new Map();
  entryRevision=-1;
  entryError.value='';
  handoffRequest=undefined;
  await Promise.all([readNavigation ? readStructure() : Promise.resolve(), readRuntime()]);
  const context=structure.value;
  if(token!==evidenceVersion || !context)return;
  const {plan,navigation}=context;
  const targets=[...new Map(baseRows.value.flatMap(row=>{
    const target=businessTarget(row);
    return target ? [[businessKey(target),target] as const] : [];
  })).values()];
  const needsEntries=baseRows.value.some(row=>row.entryConflict || row.next?.action==='landing');
  if(!targets.length && !needsEntries)return;
  const current=()=> token===evidenceVersion && structure.value===context &&
    props.siteId===plan.siteId && props.packageVersion===plan.packageVersion;
  const request=new AbortController();
  businessRequest=request;
  businessState.value='loading';
  try {
    const endpoint=`/api/admin/v2/sites/${plan.siteId}/template-seed/site-model/plans/${encodeURIComponent(plan.id)}/checklist-evidence`;
    const batchSize=100;
    const batches=Array.from({length:Math.ceil(targets.length/batchSize)},(_,index)=>targets.slice(index*batchSize,(index+1)*batchSize));
    const decisionRead=needsEntries ? siteModelApi.entryDecisions(plan.siteId,plan,navigation.revision,request.signal).then(result=>{
      if(current())installEntryDecisions(result);
    }).catch(cause=>{
      if(current())entryError.value=cause instanceof Error ? cause.message : '读取入口职责失败。';
    }) : Promise.resolve();
    const [results]=await Promise.all([Promise.allSettled(batches.map(batch=>http.post<{items:BusinessEvidence[]}>(endpoint,
      {packageVersion:plan.packageVersion,planRevision:plan.revision,navigationRevision:navigation.revision,targets:batch},
      {timeout:60000,signal:request.signal},
    ))),decisionRead]);
    if(!current())return;
    const verified=new Map<string,BusinessEvidence>();
    const failed=new Set<string>();
    results.forEach((result,index)=>{
      const keys=new Set(batches[index]!.map(businessKey));
      if(result.status==='fulfilled') {
        for(const item of result.value.items) {
          const key=businessKey(item);
          if(keys.has(key))verified.set(key,item);
        }
      } else {
        for(const key of keys)failed.add(key);
        if(!businessError.value)businessError.value=result.reason instanceof Error ? result.reason.message : '暂时无法读取业务前置证据。';
      }
    });
    businessEvidence.value=verified;
    failedBusinessTargets.value=failed;
    businessState.value=failed.size && failed.size===targets.length ? 'failed' : 'ready';
  } catch(cause) {
    if(!current())return;
    businessError.value=cause instanceof Error ? cause.message : '暂时无法读取业务前置证据。';
    businessState.value='failed';
  } finally {
    if(businessRequest===request)businessRequest=undefined;
  }
}
watch([() => props.siteId, () => props.packageVersion, () => props.rootMenuId, () => props.packagePlanId,
  () => props.plan?.id, () => props.plan?.revision, () => props.navigation?.status,
  () => props.navigation?.revision], (current, previous) => {
  if (previous?.[0] !== current[0] || previous?.[1] !== current[1]) reset();
  void refreshEvidence();
}, { immediate: true });
watch(()=>props.columns,()=>{if(!readingStructure.value && structure.value)void refreshEvidence(false);});
watch([()=>props.siteId,()=>props.packageVersion],()=>{
  entryVisible.value=false;
  entryTrigger=null;
});


type RowState = 'action' | 'business' | 'waiting' | 'connected' | 'data' | 'alias' | 'sourceLink' | 'verifiedNavigation' | 'approvedException';
type Row = {
  key: string;
  name: string;
  role: string;
  source: string | null;
  menuId: number | null;
  nodeId?: string;
  template?: string;
  path?: string;
  state: RowState;
  reason: string;
  next?: ChecklistTarget;
  owner?: { id: number; name: string };
  detailSources?: string[];
  entryConflict?: boolean;
  entryDecision?: ParentEntryDecision;
};
const stateLabels: Record<RowState, string> = {
  action: '可执行', business: '待业务资料', waiting: '等待证据', connected: '已接入', data: '目录与数据', alias: '共享来源', sourceLink:'沿用原链接',
  verifiedNavigation: '导航已核对', approvedException: '已审例外',
};
const stages: Record<ActionKind, { title: string; step: number }> = {
  structure: { title: '栏目结构', step: 5 },
  entry: { title: '栏目结构', step: 5 },
  recognize: { title: '页面识别', step: 1 },
  form: { title: '页面识别', step: 1 },
  field: { title: '字段与初值', step: 2 },
  list: { title: '列表绑定', step: 3 },
  landing: { title: '模板与发布', step: 4 },
  publish: { title: '模板与发布', step: 4 },
  'navigation-links': { title: '模板与发布', step: 4 },
};
const pagePaths = computed(() => new Set(props.pages));
const normalizePath = (path: string | null | undefined) => path?.trim() ? '/' + path.trim().replace(/^\/+/, '') : '';
const activity = computed(() => {
  const result = new Map<string, HandoffStructure['applications'][number]>();
  for (const entry of handoff.value?.applications || []) result.set(entry.menuId + ':' + entry.slot, entry);
  return result;
});
function publication(row: Row, column: MenuNode | undefined, detail = false) {
  const root = row.menuId === props.rootMenuId;
  const receipt = activity.value.get(row.menuId + ':' + (detail ? 'DETAIL' : 'LIST'));
  const type = detail ? 'detail' : 'list';
  const status = receipt?.state;
  const routePath = normalizePath(row.path || (root && row.template ? '/' : ''));
  const registered = routes.value?.routes.some(route => route.columnId === row.menuId && route.type === type
    && route.templatePath === row.template && normalizePath(route.originalUrl) === routePath);
  const conflict = routes.value?.conflicts.some(conflict => conflict.columns.some(entry => entry.columnId === row.menuId));
  const front = column?.hasFrontChannel ?? receipt?.hasFrontChannel;
  const verified = receipt?.state === 'TEMPLATE_VERIFIED' && receipt.packageVersion === props.packageVersion
    && receipt.templatePath === row.template && (normalizePath(receipt.route) || (root ? '/' : '')) === routePath;
  if (row.template && routePath) {
    if (conflict) return '运行路由存在冲突';
    if (front !== true) return '前台入口尚未启用';
    if (status && !verified && status !== 'UNMANAGED_TEMPLATE') return '模板应用回执与当前配置不一致';
    if (running.value === true && registered && verified) {
      row.state = 'connected';
      row.reason = '当前模板回执已通过校验，正式路由已登记。';
      return '';
    }
    if (running.value === true && registered && handoff.value) {
      row.state = 'connected';
      row.reason = '正式路由已登记且指向当前模板。模板没有可核对的工作台应用回执。';
      return '';
    }
    if (!routes.value || !handoff.value || running.value !== true) {
      row.reason = '已有模板与路由配置，等待读取运行路由和模板应用证据。';
      return '';
    }
    return '正式路由未登记或注册模板与当前配置不同';
  }
  if (row.template && !routePath) return '已有模板，正式路由尚未配置';
  return '';
}
function task(row: Row, kind: ActionKind, label: string, reason: string, fieldPlanId?: string) {
  row.state = 'action';
  row.reason = reason;
  row.next = { sourcePath: row.source, menuId: row.menuId, nodeId: row.nodeId ?? null, action: kind, label, reason };
  if (fieldPlanId) row.next.fieldPlanId = fieldPlanId;
}
const baseRows = computed<Row[]>(() => {
  if (!structure.value) return [];
  const {navigation,plan} = structure.value;
  const columns = new Map(props.columns.map(column => [Number(column.id),column]));
  const models = new Map(plan.proposal.models.map(model => [model.key, model]));
  const nodes = navigation.nodes;
  const mapped = new Set<number>();
  const used = new Set<string>();
  const result: Row[] = [];
  for (const node of nodes) {
    const id = node.menuId;
    const column = id ? columns.get(id) : undefined;
    const root = id !== null && id === props.rootMenuId;
    const model = node.modelKey ? models.get(node.modelKey) : undefined;
    const children = nodes.filter(child => child.parentKey === node.key);
    const directory = node.kind === 'DIRECTORY' && !root;
    const overview = node.kind === 'LIST' && !!node.sourcePath && children.length >= 2
      && children.every(child => child.kind === 'LIST' && child.sourcePath === node.sourcePath);
    const row: Row = {
      key: node.key, name: node.name, role: root ? '首页' : directory ? '目录' : overview ? '栏目概览' : node.kind === 'LIST' ? '列表' : '单页',
      source: node.sourcePath, menuId: id, nodeId: node.key,
      template: column?.listPageTemplate, path: column?.listPageUrl, state: 'waiting', reason: '尚缺独立页面或维护归属证据。',
    };
    const receipt = activity.value.get(id + ':LIST');
    if (root && receipt && (!column || column.listPageTemplate === receipt.templatePath)) {
      row.template = receipt.templatePath;
      row.path = receipt.route || '/';
    }
    if (id) mapped.add(id);
    if (node.sourcePath) used.add(node.sourcePath);
    result.push(row);

    const issue = id ? publication(row, column) : '';
    if (node.drift) task(row,'structure','核对栏目结构','当前栏目与已应用导航回执不一致。');
    else if (row.state !== 'connected') {
      const singleRegions = plan.proposal.regions.filter(region => region.mode === 'SINGLE' && region.sourcePath === node.sourcePath);
      const ownerIds = new Set(singleRegions.map(region => models.get(region.modelKey)?.menuId).filter(Boolean));
      const owner = !node.independentLanding && node.kind === 'SINGLE' && ownerIds.size === 1
        ? children.find(child => !child.drift && child.kind === 'SINGLE' && child.sourcePath === node.sourcePath &&
          child.menuId && ownerIds.has(child.menuId))
        : undefined;
      const hasSource = !!node.sourcePath && pagePaths.value.has(node.sourcePath);
      const linkedRegion = plan.proposal.regions.some(region => region.sourcePath === node.sourcePath && region.modelKey === node.modelKey
        && region.mode === node.kind && (node.kind === 'LIST' || region.records.length === 1));
      if (!id) task(row,'structure','核对栏目结构','已读取入口，等待核对实际对应栏目。');
      else if (owner?.menuId) {
        row.entryConflict=!root;
        row.state = 'alias';
        row.owner = { id: owner.menuId, name: owner.name };
        row.reason = '与「' + owner.name + '」共用原页，已审核模型归该栏目。';
      } else if (directory) {
        row.state = 'data';
        row.reason = '该栏目已确认为目录，维护子栏目。';
      } else if (!column && !root) row.reason = '尚未读取到对应的 CMS 栏目。';
      else if (row.template && !issue) { /* 保留已配置页面的运行证据状态。 */ }
      else if (hasSource && overview && id !== model?.menuId) task(row,'landing','审核栏目概览',issue || '核对概览卡片和各子栏目的公开地址。');
      else if (hasSource && issue) task(row,'publish','核对模板与路由',issue + '。');
      else if (hasSource && root) task(row,'publish','审核首页模板', '已有首页来源，核对模板配置和首页注册路由。');
      else if (hasSource && !linkedRegion) task(row,'recognize','审核页面证据','已定位原页，先核对主体区域、交互用途和可维护内容。');
      else if (hasSource && node.kind === 'LIST') task(row,'list','核对列表卡片','已确认列表来源，核对当前栏目的记录归属、卡片和分页。');
      else if (hasSource && model?.menuId !== id) {
        row.entryConflict=node.kind==='SINGLE' && !!model?.menuId && children.length>0;
        task(row,'structure','核对内容归属','原页已有内容模型，需核对此入口与独立维护栏目的关系。');
      }
      else if (hasSource) task(row,'field','审核字段与初值','已确认单页来源和维护栏目，核对字段及初始内容。');
    }

    if (node.kind !== 'LIST') continue;
    const detailPaths = new Set(plan.proposal.regions.filter(region => region.mode === 'DETAIL'
      && region.modelKey === node.modelKey && pagePaths.value.has(region.sourcePath)).map(region => region.sourcePath));
    row.detailSources = [...detailPaths];
    const detailReceipt = activity.value.get(id + ':DETAIL');
    const detailTemplate = column?.detailPageTemplate || (detailReceipt?.state === 'TEMPLATE_VERIFIED' ? detailReceipt.templatePath : undefined);
    const detailRoute = column?.detailPageUrl;
    const ownsDetails = !!id && model?.menuId === id;
    if (!detailTemplate && !detailRoute && !(ownsDetails && detailPaths.size)) continue;
    const detailSource = detailReceipt?.sourcePath && pagePaths.value.has(detailReceipt.sourcePath)
      ? detailReceipt.sourcePath : ownsDetails && detailPaths.size === 1 ? [...detailPaths][0]! : null;
    const detail: Row = {
      key: node.key + ':detail', name: node.name, role: '详情', source: detailSource, menuId: id, nodeId: node.key,
      template: detailTemplate, path: detailRoute, state: 'waiting', reason: '已有详情配置或区域线索，独立详情来源尚未核实。',
    };
    if (detailSource) used.add(detailSource);
    const detailIssue = id ? publication(detail,column,true) : '';
    if (detail.state !== 'connected' && detailSource && !node.drift) {
      if (detailIssue) task(detail,'publish','核对详情路由',detailIssue + '。');
      else if (!detail.template) task(detail,'field','审核详情字段与初值','已确认独立详情来源及所属栏目。');
    }
    result.push(detail);
  }

  for (const node of navigation.supplementalEvidence || []) {
    if (node.sourcePath) used.add(node.sourcePath);
    const row: Row = { key:'extra:' + node.key, name:node.name, role:'补充入口', source:node.sourcePath, menuId:null, nodeId:node.key,
      state:'waiting', reason:'来源尚未判定为独立导航入口。' };
    if (retainedNavigationLink(navigation,node)) {
      row.state='sourceLink';
      row.reason='保留原页的外部地址或锚点，发布后核对链接。';
    } else if (navigation.status === 'APPLIED')
      task(row,'structure','核对新增导航','在栏目树中查看原页、上级栏目和本次追加的影响范围。');
    else if (node.sourcePath && pagePaths.value.has(node.sourcePath) && !includedPaths.value.has(node.sourcePath))
      task(row,'recognize','审核入口用途','从补充入口的原页核对是否需要独立路由。');
    result.push(row);
  }
  for (const column of props.columns) {
    const id = Number(column.id);
    if (!id || mapped.has(id)) continue;
    const configured = activity.value.get(id + ':LIST');
    const regions = plan.proposal.regions.filter(region => models.get(region.modelKey)?.menuId === id);
    const regionPaths = [...new Set(regions.map(region=>region.sourcePath))];
    const source = configured?.sourcePath || (regionPaths.length === 1 ? regionPaths[0]! : null);
    const row: Row = { key:'cms:' + id, name:column.className || column.name || String(id), role:'既有栏目', source, menuId:id,
      template:column.listPageTemplate, path:column.listPageUrl, state:'waiting', reason:'当前导航未指定此栏目的独立发布来源。' };
    const issue = publication(row,column);
    if (row.state !== 'connected' && issue && source && pagePaths.value.has(source)) task(row,'publish','核对模板与路由',issue + '。');
    else if (row.state === 'waiting' && !row.template && !row.path
      && (column.hasFrontChannel === false || (regions.length && regions.every(region => used.has(region.sourcePath))))) {
      row.state = 'data';
      row.reason = '保留栏目及其维护数据，目前无独立公开路由。';
    }
    result.push(row);
  }
  for (const path of props.pages) {
    if (used.has(path)) continue;
    const row: Row = { key:'source:' + path, name:path, role:'包内页面', source:path, menuId:null,
      state:'waiting', reason:'暂无线索证明是独立页面。' };
    if (includedPaths.value.has(path)) {
      row.state = 'data';
      row.reason = '已查到被其他页面引用的公共片段。';
    }
    result.push(row);
  }
  return result;
});

function businessTarget(row: Row): BusinessTarget | undefined {
  const kind=row.next?.action==='recognize' && row.role==='单页' ? 'field' : row.next?.action;
  if(row.menuId && row.source && (kind==='list' || kind==='field' || kind==='landing'))
    return {menuId:row.menuId,sourcePath:row.source,nodeKey:row.nodeId,kind};
}
function businessKey(target: BusinessTarget) {
  return JSON.stringify([target.kind,target.menuId,target.sourcePath,target.nodeKey||'']);
}
const rows = computed<Row[]>(() => baseRows.value.map(base => {
  const evidence = props.navigationEvidence;
  const published = evidence?.siteId === props.siteId && releaseContext(evidence.scope) &&
    evidence.pages.find(page => page.menuId === base.menuId && page.sourcePath === base.source && base.role !== '详情');
  if (published && ['READY', 'EXCEPTION'].includes(published.state)) return {
    ...base, state: published.state === 'READY' ? 'verifiedNavigation' : 'approvedException',
    reason: published.reason, next: undefined,
    entryDecision: base.nodeId ? entryDecisions.value.get(base.nodeId) : undefined,
  };
  const target=businessTarget(base);
  if(!target && !base.entryConflict)return base;
  const row={...base};
  const key=target ? businessKey(target) : '';
  const item=businessEvidence.value.get(key);
  const entry=row.nodeId ? entryDecisions.value.get(row.nodeId) : undefined;
  const entrance=base.entryConflict || (target?.kind==='landing' && (item?.state==='VERIFIED' && item.landingCandidateCount===0 || !!entry));
  if(entrance) {
    row.entryDecision=entry;
    if(entry?.valid===true) {
      if(entry.role==='DIRECTORY') {
        task(row,'structure','核对子入口','已决定作为目录。继续核对下级栏目和公共导航。');
        row.state='data';
      } else if(entry.role==='REDIRECT') {
        const destination=structure.value?.navigation.nodes.find(node=>node.key===entry.targetNodeKey);
        if(destination?.menuId && !destination.drift && destination.kind!=='DIRECTORY')
          task(row,'navigation-links','核对导航链接',`已决定导向「${destination.cmsName || destination.name}」（#${destination.menuId}）。待目标具备正式地址后，核对公共导航中的入口链接。`);
        else {
          row.reason='已保存导向决定，当前导航尚未读取到目标栏目。';
          row.state='waiting';
          row.next=undefined;
        }
      } else if(item?.state==='VERIFIED' && (item.landingCandidateCount ?? 0)>0) {
        task(row,'landing','审核栏目概览','已决定建设独立概览。继续核对检测到的分类卡片和各子栏目的正式地址。');
      } else {
        row.reason='已决定建设独立概览，等待可核对的分类卡片、栏目对应及正式地址。';
        row.state='waiting';
        row.next=undefined;
      }
    } else {
      const reason=entry?.reason || (base.entryConflict ? '父入口与实际维护栏目共用来源。请确认目录、导向目标或所需的独立概览证据。'
        : '原页没有可用的栏目概览容器。请确认目录、导向目标或补充独立概览的分类卡片。');
      task(row,'entry',entry ? '重新决定入口职责' : '决定入口职责',reason);
    }
    return row;
  }
  if(!target)return row;
  if(item?.state!=='VERIFIED') {
    const status=businessState.value;
    row.reason += status==='failed' || failedBusinessTargets.value.has(key) ? ' 业务前置证据读取失败。' : status==='loading' ? ' 正在核对业务前置证据。'
      : item?.reason ? ' 业务前置证据未能核实：'+item.reason : ' 业务前置证据尚未读取。';
    return row;
  }
  if(target.kind==='list' && item.recordCount===0 && item.listSampleOwnership==='OTHER_MENU' &&
    item.sampleOwnerMenuId && item.sampleOwnerMenuId!==row.menuId) {
    row.state='business';
    row.reason=`本栏目没有记录，共用页面的已确认样本归属栏目 #${item.sampleOwnerMenuId}。请补充本栏目的真实记录、分类与来源资料。`;
    row.next=undefined;
  } else if(target.kind==='field' && item.fieldPhase==='SOURCE_CONFLICT' && item.fieldPlanId) {
    task(row,'field',item.reason || '处理原文冲突','同一段原文有多个字段绑定。核对原文，选择保留的字段或修正范围。',item.fieldPlanId);
    row.next!.initialize=true;
  } else if(target.kind==='field' && item.fieldPhase==='CONFIRMED' && item.fieldPlanId) {
    task(row,'field','检查初始内容','字段计划已审核。下一步检查初值和已有内容，再确认初始化。',item.fieldPlanId);
    row.next!.initialize=true;
  } else if(target.kind==='field' && item.fieldPhase==='INITIALIZED' && item.fieldPlanId) {
    task(row,'field','审核私有草稿','当前字段方案已有有效的私有初始化回执。核对现有内容和待绑定项，确认后再处理公开模板。',item.fieldPlanId);
  }
  return row;
}));

// 同一层保持已审核导航的顺序。来源页可提前取证，聚合模板的绑定排在被引用栏目之后。
function bindingPriority(row:Row) {
  if (row.next?.action === 'structure' || row.next?.action==='entry') return 0;
  if (row.menuId === props.rootMenuId) return 5;
  if (row.nodeId && structure.value?.navigation.nodes.some(node=>node.parentKey===row.nodeId)) return 4;
  if (row.source && includedPaths.value.has(row.source)) return 1;
  if (row.next?.action === 'landing' || row.role === '栏目概览') return 3;
  return 2;
}
const tasks = computed(() => {
  const grouped = new Map<string,{row:Row; sources:Row[]}>();
  for (const row of rows.value) {
    const next = row.next;
    if (!next || row.state!=='action') continue;
    const target = next.action === 'recognize' ? [next.sourcePath, next.menuId]
      : next.action === 'structure' || next.action==='entry' ? [next.nodeId || next.menuId] : [next.menuId, next.sourcePath, row.role === '详情'];
    const key = JSON.stringify([next.action, ...target]);
    const group = grouped.get(key);
    if (group) group.sources.push(row);
    else grouped.set(key,{row,sources:[row]});
  }
  for (const row of rows.value) {
    if (row.state !== 'alias' || !row.owner) continue;
    for (const group of grouped.values()) {
      if (group.row.menuId === row.owner.id && group.row.source === row.source) group.sources.push(row);
    }
  }
  const menuDepth=new Map(props.columns.map(menu=>[Number(menu.id),menu.depth || 0]));
  return [...grouped.values()].sort((a,b)=>bindingPriority(a.row)-bindingPriority(b.row) || (menuDepth.get(b.row.menuId || 0) || 0)-(menuDepth.get(a.row.menuId || 0) || 0));
});
type ProgressGroup = 'content' | 'preview';
const contentActions=new Set<ActionKind>(['entry','recognize','form','field','list']);
const previewActions=new Set<ActionKind>(['landing','publish','navigation-links']);
function progressRows(group: ProgressGroup) {
  const result=new Map<string,Row>();
  const proposal=structure.value?.plan.proposal;
  if(!proposal)return [];
  const models=new Map(proposal.models.map(model=>[model.key,model.menuId]));
  const ownedMenus=new Set(proposal.regions.filter(region=>pagePaths.value.has(region.sourcePath) &&
    region.maintenance?.mode!=='REFERENCE').map(region=>models.get(region.modelKey)));
  for(const row of rows.value) {
    if(isSignedNavigation(row) || row.state==='alias' || row.state==='sourceLink' || row.role==='目录')continue;
    const hasAction=row.next && (group==='content' ? contentActions : previewActions).has(row.next.action);
    const independent=!!(row.source && pagePaths.value.has(row.source) && !includedPaths.value.has(row.source) &&
      row.role!=='包内页面' && row.role!=='补充入口');
    const owned=row.menuId!==null && ownedMenus.has(row.menuId);
    if(group==='content' ? !(hasAction || owned || independent && row.state!=='data')
      : !(row.menuId && row.state!=='data' && (hasAction || row.template || row.path || independent)))continue;
    const key=JSON.stringify([row.menuId ?? row.source ?? row.key,row.role==='详情']);
    if(!result.has(key))result.set(key,row);
  }
  return [...result.values()];
}
function progressCount(group: ProgressGroup) {
  const candidates=progressRows(group);
  const evidence=props.navigationEvidence;
  const proof=evidence?.siteId===props.siteId && releaseContext(evidence.scope) ? evidence.contentPages ?? [] : [];
  let pending=0, unverified=0;
  for(const row of candidates){
    const slot=row.role==='详情' ? 'DETAIL' : 'LIST';
    const matches=proof.filter(page=>row.menuId===page.menuId && !!row.source && row.source===page.sourcePath && page.slot===slot);
    const published=matches.length>0 && matches.every(page=>page.state==='READY' && !!page.artifact &&
      (slot!=='DETAIL' || !!page.recordId));
    if(group==='preview' && (published || row.state==='connected'))continue;
    if(group==='content' && published){
      const isList=row.role==='列表' || row.role==='栏目概览' ||
        structure.value?.navigation.nodes.some(node=>node.menuId===row.menuId && node.kind==='LIST');
      if(!isList || slot==='DETAIL' || proof.some(page=>page.menuId===row.menuId && page.slot==='DETAIL' &&
        page.state==='READY' && page.recordId))continue;
    }
    const actions=group==='content' ? contentActions : previewActions;
    const target=group==='content' ? businessTarget(row) : undefined;
    if(row.state==='action' && row.next && actions.has(row.next.action) &&
      (!target || businessEvidence.value.get(businessKey(target))?.state==='VERIFIED'))pending++;
    else unverified++;
  }
  return {total:candidates.length,pending,unverified,verified:candidates.length-pending-unverified};
}
const stageEvidence=computed(()=>{
  const context=structure.value;
  if(!context || !props.packageVersion || context.plan.siteId!==props.siteId ||
    context.plan.packageVersion!==props.packageVersion) return undefined;
  const {plan,navigation}=context;
  const scope={siteId:props.siteId,packageVersion:props.packageVersion,planId:plan.id,
    planRevision:plan.revision,navigationRevision:navigation.revision};
  if(reading.value || readingStructure.value || businessState.value==='loading') return {...scope,state:'loading' as const};
  if(structureError.value || !handoff.value) return {...scope,state:'error' as const};
  return {...scope,state:'ready' as const,value:{
    content:sharedFragments.value!==null ? progressCount('content') : null,
    preview:running.value===true && routes.value && sharedFragments.value!==null ? progressCount('preview') : null
  }};
});
const search = ref(''), scopeFilter = ref<'all'|RowState>('action'), stageFilter = ref('all'), page = ref(1);
const impactFilter=ref('全部影响');
function impact(row:Row){
  if(isSignedNavigation(row))return '可稍后核对';
  if(row.next?.action==='structure' || row.state==='waiting')return '阻断下一步';
  if(row.next || row.entryDecision)return '需要决定';
  return '可稍后核对';
}
function isSignedNavigation(row:Row) {
  return row.state==='verifiedNavigation' || row.state==='approvedException';
}
const counts = computed(() => new Map((Object.keys(stateLabels) as RowState[]).map(state=>[
  state, state === 'action' ? tasks.value.length : rows.value.filter(row=>row.state===state).length,
])));
const shownEntries = computed(() => scopeFilter.value === 'action' ? tasks.value :
  rows.value.filter(row=>scopeFilter.value==='all'||row.state===scopeFilter.value).map(row=>({row,sources:[row]})));
const stagesInScope = computed(() => [...new Set(Object.values(stages).map(stage=>stage.title))].map(name=>({
  name, count:shownEntries.value.filter(entry=>entry.row.next && stages[entry.row.next.action].title===name).length,
})));
const filtered = computed(() => {
  const term = search.value.trim().toLowerCase();
  return shownEntries.value.filter(({row,sources}) => (
    (impactFilter.value==='全部影响' || impact(row)===impactFilter.value) && (stageFilter.value === 'all' || (row.next && stages[row.next.action].title === stageFilter.value))
  ) && sources.some(source=>[source.name,source.source,source.role,source.reason,source.menuId?.toString()].some(value=>value?.toLowerCase().includes(term))));
});
const shown = computed(()=>filtered.value.slice((page.value-1)*15,page.value*15));
const entryKey = (entry: {row: Row}) => entry.row.key;
const stateLabel = (state: RowState) => stateLabels[state];
watch([search,scopeFilter,stageFilter,impactFilter,()=>props.siteId],()=>{page.value=1;});
watch(scopeFilter,()=>{stageFilter.value='all';});
let restored=false;
watch([structure,reading,readingStructure,businessState],async()=>{
  const pos=props.position;
  if(restored || !pos || reading.value || readingStructure.value || businessState.value==='loading' || !structure.value ||
     pos.siteId!==props.siteId || pos.version!==props.packageVersion || pos.planId!==structure.value.plan.id)return;
  restored=true;
  search.value=pos.search; scopeFilter.value=pos.scope; impactFilter.value=pos.impact;
  await nextTick();stageFilter.value=pos.stage;await nextTick();
  const index=filtered.value.findIndex(entry=>entry.row.key===pos.rowKey);
  page.value=index<0?Math.min(pos.page,Math.max(1,Math.ceil(filtered.value.length/15))):Math.floor(index/15)+1;
  await nextTick();
  const target=document.querySelector<HTMLElement>('[data-checklist-key="'+CSS.escape(pos.rowKey)+'"]');
  (target||checklistHeading.value)?.focus();
});
function nextRow(row: Row, event?: MouseEvent) {
  const context = structure.value;
  const next = row.next;
  if (props.disabled || !context || !next) return;
  emit('remember',{siteId:props.siteId,version:props.packageVersion,planId:context.plan.id,rowKey:row.key,search:search.value,scope:scopeFilter.value,stage:stageFilter.value,impact:impactFilter.value,page:page.value});
  if(next.action==='entry') { openEntry(row,event); return; }
  const {sourcePath, menuId} = next;
  const step = stages[next.action].step;
  if (next.action === 'structure') emit('structure',context,next);
  else if (menuId && sourcePath) emit('open',sourcePath,menuId,step,context,next);
  else if (sourcePath) emit('page',sourcePath,next);
}
function nextTask(group?: ProgressGroup) {
  const actions=group==='content' ? contentActions : group==='preview' ? previewActions : null;
  return tasks.value.find(({row})=>row.next && (!actions || actions.has(row.next.action)))?.row;
}
function nextTaskLabel(group: ProgressGroup) {
  const row=nextTask(group);
  return row?.next ? `${row.name} · ${row.next.label}` : undefined;
}
function openNext(group?: ProgressGroup, event?: MouseEvent) {
  if(props.disabled) {
    ElMessage.info('工作台正在处理其他操作，请稍后再试。');
    return false;
  }
  if(reading.value || readingStructure.value || businessState.value==='loading'){
    ElMessage.info('站点待办正在读取证据。');
    return false;
  }
  const row=nextTask(group);
  if(structure.value && row) {
    nextRow(row,event);
    return true;
  }
  if(structure.value && group){
    const candidates=progressRows(group);
    const waiting=candidates.find(row=>row.state==='business') ?? candidates.find(row=>row.state==='waiting' || row.state==='data');
    if(waiting) {
      scopeFilter.value=waiting.state;
      search.value=''; stageFilter.value='all'; impactFilter.value='全部影响';
      void nextTick().then(()=>{
        const index=filtered.value.findIndex(entry=>entry.row.key===waiting.key);
        if(index>=0){page.value=Math.floor(index/15)+1; focusChecklist();}
      });
      ElMessage.info(`${waiting.name} · ${waiting.reason}`);
      return true;
    }
  }
  ElMessage.info(structureError.value || (!structure.value ? '请先读取或确认整站栏目结构。' : '当前阶段没有可打开的待办。'));
  return false;
}
function releaseContext(scope: ReleaseScope) {
  const current=structure.value;
  if (!current || current.plan.packageVersion!==scope.packageVersion ||
    current.navigation.revision!==scope.navigationRevision) return;
  if (current.plan.id===scope.id && current.plan.revision===scope.planRevision) return current;
  if (current.plan.id===scope.originPlanId && current.plan.revision===scope.originPlanRevision) return current;
}
function focusChecklist() {
  checklistHeading.value?.focus({preventScroll:true});
  checklistHeading.value?.scrollIntoView({block:'start'});
}
function releaseAction(page: ReleasePage, scope: ReleaseScope): ReleaseAction {
  const showChecklist={label:'查看站点待办',run:focusChecklist};
  if (readingStructure.value || reading.value || businessState.value==='loading')
    return {...showChecklist,hint:'站点待办正在读取证据。'};
  if (!releaseContext(scope))
    return {...showChecklist,hint:'请在站点待办更新证据，并重新核对整站。'};
  // 缺少已发布记录的详情项使用列表原页上报，只用它核对内容归属。
  const missingDetailRecord=page.slot==='DETAIL' && !page.recordId && !page.route && !page.artifact;
  const wantsDetail=page.slot==='DETAIL' && !missingDetailRecord;
  const row=rows.value.find(row=>row.menuId===page.menuId && row.source===(page.sourcePath ?? null) &&
    (row.role==='详情')===wantsDetail);
  if (!row) return showChecklist;
  const target=businessTarget(row);
  if ((target && (businessState.value==='failed' || failedBusinessTargets.value.has(businessKey(target)))) ||
    (row.next?.action==='entry' && entryError.value))
    return {...showChecklist,hint:'此项证据读取失败，请在站点待办重试。'};
  if (row.state==='business' || row.state==='data' || row.state==='alias')
    return {...showChecklist,hint:row.reason};
  if (missingDetailRecord) {
    if (page.reason==='请先核对栏目是否需要独立详情') {
      if (row.next) return {label:row.next.label,hint:page.reason,run:event=>nextRow(row,event)};
      if (page.sourcePath && page.menuId) {
        const path=page.sourcePath, menuId=page.menuId;
        return {label:'核对列表原页',hint:page.reason,run:()=>emit('page',path,{
          sourcePath:path,menuId,nodeId:row.nodeId ?? null,action:'recognize',label:'核对列表原页',reason:page.reason,
        })};
      }
      return {...showChecklist,hint:page.reason};
    }
    const menuId=row.menuId;
    return menuId && props.columns.some(column=>Number(column.id)===menuId && column.pageType===1)
      ? {label:'核对栏目已发布内容',run:()=>emit('manageContent',menuId)}
      : {...showChecklist,hint:'请核对详情是否属于本栏目及对应的已发布记录。'};
  }
  if (row.next) return {label:row.next.label,hint:row.next.reason,run:event=>nextRow(row,event)};
  return {...showChecklist,hint:row.reason};
}
function isEntryRow(row:Row) {
  return !!row.entryDecision || row.next?.action==='entry';
}
function openEntry(row: Row, event?: MouseEvent) {
  const context=structure.value;
  const node=context?.navigation.nodes.find(candidate=>candidate.key===row.nodeId && candidate.menuId===row.menuId);
  if(!context || !node || !isEntryRow(row) || props.disabled || entryVisible.value)return;
  entryTrigger=event && event.currentTarget instanceof HTMLElement ? event.currentTarget : null;
  entryDialog.value={context,node,title:row.entryDecision ? '修改入口职责' : '决定入口职责',reason:row.reason};
  entryVisible.value=true;
}
function installEntryDecisions(result:ParentEntryDecisions) {
  if(result.revision<entryRevision)return;
  entryRevision=result.revision;
  entryDecisions.value=new Map(result.items.map(item=>[item.nodeKey,item]));
}
function acceptEntry(result:ParentEntryDecisions) {
  const origin=entryDialog.value?.context;
  const current=structure.value;
  if(origin && current && origin.plan.id===current.plan.id && origin.plan.revision===current.plan.revision
    && origin.navigation.revision===current.navigation.revision && props.siteId===origin.plan.siteId
    && props.packageVersion===origin.plan.packageVersion) {
    emit('clearNavigationEvidence');
    installEntryDecisions(result);
    const dialog=entryDialog.value;
    if(dialog)dialog.reason=rows.value.find(row=>row.nodeId===dialog.node.key && row.menuId===dialog.node.menuId)?.reason ?? dialog.reason;
    page.value=Math.min(page.value,Math.max(1,Math.ceil(filtered.value.length/15)));
  }
}
function entryIsSaving(){return entryEditor.value?.isSaving() ?? false;}
function hasUnsavedChanges(){return entryEditor.value?.hasUnsavedChanges() || entryIsSaving();}
async function closeEntry(done:()=>void) {
  if(entryIsSaving())return;
  if(hasUnsavedChanges()){
    try { await ElMessageBox.confirm('入口职责尚未保存，返回会放弃这次修改。','返回清单',{
      type:'warning',confirmButtonText:'放弃修改',cancelButtonText:'继续编辑',
    }); }
    catch { return; }
  }
  done();
}
function returnToChecklist(){void closeEntry(()=>{entryVisible.value=false;});}
async function restoreChecklistFocus() {
  const trigger=entryTrigger;
  entryTrigger=null;
  entryDialog.value=undefined;
  await nextTick();
  if(trigger?.isConnected && trigger.getClientRects().length && !trigger.hasAttribute('disabled'))trigger.focus();
  else checklistHeading.value?.focus();
}
defineExpose({stageEvidence,openNext,nextTaskLabel,refreshProgress:refreshEvidence,hasUnsavedChanges,isSaving:entryIsSaving,appliedStructure:structure,releaseContext,releaseAction});
</script>

<template>
  <el-card shadow="never" class="workbench-template-checklist">
    <template #header><strong ref="checklistHeading" tabindex="-1">站点待办清单</strong></template>
    <p>建议先核对栏目结构和公共区域，再联合审核栏目列表与详情。频道和首页中的推荐内容，在来源栏目明确后绑定。</p>
    <div v-if="!structure" class="checklist-toolbar" role="status">
      <span v-if="readingStructure">正在读取已应用导航与确认计划。</span>
      <span v-else-if="structureError">读取已应用导航失败。{{ structureError }}</span>
      <template v-else-if="pendingStructure">
        <div>
          <strong>下一步 · {{ prerequisiteAction }}</strong>
          <p v-if="pendingStructure.plan.status!=='CONFIRMED'">已有内容模型草稿。先核对模型和字段，再确认导航要建立哪些栏目。</p>
          <p v-else-if="pendingStructure.navigation.status==='CONFIRMED'">已确认 {{ pendingStructure.navigation.nodes.length }} 个导航入口。先查看栏目和字段的应用影响，再确认执行。</p>
          <p v-else-if="pendingStructure.navigation.status==='APPLYING'">读取上次确认的应用范围，可从已经完成的步骤继续。</p>
          <p v-else>当前有 {{ pendingStructure.navigation.nodes.length }} 个导航入口。先核对导航和实际栏目，保存到 CMS 后即可继续字段与模板待办。</p>
          <el-button type="primary" :disabled="disabled" @click="emit('prerequisite',pendingStructure)">{{ prerequisiteAction }}</el-button>
        </div>
      </template>
      <template v-else>
        <span>{{ packageVersion ? '当前页面包还没有可用的整站结构。先查看整包草稿或启动分析。' : '请先为此站点上传前端页面包。' }}</span>
        <el-button type="primary" :disabled="disabled" @click="emit('packageReview')">{{ prerequisiteAction }}</el-button>
      </template>
      <el-button v-if="structureError" size="small" :disabled="disabled" @click="refreshEvidence()">重新读取导航</el-button>
    </div>
    <div v-if="structure && structure.navigation.planRevision < structure.plan.revision" class="checklist-toolbar">
      <el-alert :title="`已应用导航对应结构修订 ${structure.navigation.planRevision}，当前结构修订为 ${structure.plan.revision}。接续后可核对当前修订的字段与签收。`" type="warning" :closable="false" />
      <el-button type="primary" :disabled="disabled" @click="emit('prerequisite',structure)">前往导航修订接续</el-button>
    </div>
    <div class="checklist-toolbar">
      <el-button :disabled="disabled || !packageVersion" :loading="reading || readingStructure || businessState==='loading'" @click="refreshEvidence()">更新站点证据</el-button>
      <span role="status">{{ running===null ? '站点服务状态未核实' : running ? '站点服务运行中' : '站点服务已停止' }}</span>
      <span v-if="error" role="status">{{ error }}<template v-if="evidenceTime">仍显示本次进入后已读取的证据。</template></span>
      <span v-if="businessState==='loading'" role="status">正在核对栏目资料和私有草稿。</span>
      <span v-if="businessError" role="status">部分或全部业务前置证据读取失败。{{ businessError }}</span>
      <span v-if="entryError" role="status">入口职责读取失败。{{ entryError }}</span>
    </div>
    <template v-if="structure">
      <div class="checklist-toolbar">
        <el-select v-model="impactFilter" aria-label="按影响筛选"><el-option v-for="label in ['全部影响','阻断下一步','需要决定','可稍后核对']" :key="label" :value="label" /></el-select>
        <el-select v-model="scopeFilter" aria-label="清单状态">
          <el-option v-for="(label, state) in stateLabels" :key="state" :value="state" :label="label+'（'+(counts.get(state) || 0)+'）'" />
          <el-option :label="'全部来源（'+rows.length+'）'" value="all" />
        </el-select>
        <el-select v-if="scopeFilter==='action'" v-model="stageFilter" aria-label="按处理环节筛选">
          <el-option value="all" label="所有处理环节" />
          <el-option v-for="stage in stagesInScope" :key="stage.name" :value="stage.name" :label="stage.name+'（'+stage.count+'）'" />
        </el-select>
        <el-input v-model="search" clearable placeholder="搜索栏目、原页或原因" aria-label="搜索站点待办" style="max-width:340px" />
        <el-pagination v-if="filtered.length > 15" v-model:current-page="page" :total="filtered.length" :page-size="15" layout="total, prev, pager, next" />
      </div>
      <el-table :data="shown" :row-key="entryKey" size="small" max-height="560"
        :empty-text="reading || businessState==='loading' ? '正在核对证据' : error || businessError ? '证据读取不完整，请更新站点证据' : search || stageFilter!=='all' || impactFilter!=='全部影响' ? '筛选条件无匹配项' : '当前范围暂无该状态条目'">
        <el-table-column type="expand">
          <template #default="{row:entry}">
            <ul class="checklist-sources">
              <li v-for="item in entry.sources" :key="item.key">
                {{ item.name }} · {{ item.role }}<template v-if="item.menuId">（#{{ item.menuId }}）</template>
                <span v-if="item.source"> · {{ item.source }}</span>
                <span> · {{ stateLabel(item.state) }}。{{ item.reason }}</span>
                <small v-if="item.detailSources?.length" class="checklist-runtime">同一模型的详情来源 {{ item.detailSources.join('、') }}</small>
                <small v-else-if="item.role==='列表'" class="checklist-runtime">未查到此模型的独立详情来源。</small>
              </li>
            </ul>
          </template>
        </el-table-column>
        <el-table-column label="栏目与用途" min-width="170">
          <template #default="{row:{row}}">
            <el-button v-if="isEntryRow(row)" link type="primary" :disabled="disabled" @click="openEntry(row,$event)">{{ row.name }}</el-button>
            <span v-else>{{ row.name }}</span> · {{ row.role }}<span v-if="row.menuId"> · #{{ row.menuId }}</span>
            <small v-if="row.source" class="checklist-runtime">{{ row.source }}</small>
          </template>
        </el-table-column>
        <el-table-column label="状态与依据" min-width="250">
          <template #default="{row:{row}}">
            <template v-if="!isSignedNavigation(row)">{{ impact(row) }} · </template>{{ stateLabel(row.state) }}。{{ row.reason }}
            <small v-if="row.path" class="checklist-runtime">{{ row.path }}</small>
          </template>
        </el-table-column>
        <el-table-column label="下一步" min-width="156">
          <template #default="{row:{row,sources}}">
            <el-button v-if="row.next" :data-checklist-key="row.key" link type="primary" :disabled="disabled || (reading && row.next.action!=='entry')" @click="nextRow(row,$event)">{{ row.next.label }}</el-button>
            <el-button v-if="row.state==='connected' && row.menuId" link type="primary" :disabled="disabled" @click="emit('manageContent',row.menuId)">维护已有内容</el-button>
            <el-button v-if="row.entryDecision && row.next?.action!=='entry'" link :disabled="disabled" @click="openEntry(row,$event)">修改入口职责</el-button>
            <small v-if="sources.length > 1" class="checklist-runtime">{{ sources.length }} 条来源</small>
          </template>
        </el-table-column>
      </el-table>
    </template>
    <details>
      <summary>公共头尾与片段证据</summary>
      <p v-if="sharedFragments && !sharedFragments.length">本次未读取到重复公共片段。</p>
      <details v-for="fragment in sharedFragments || []" :key="`${fragment.kind}:${fragment.digest}`">
        <summary>{{ fragment.kind }} · {{ fragment.occurrences.length }} 处来源</summary>
        <ul><li v-for="(occurrence,index) in fragment.occurrences" :key="index">{{ occurrence.sourcePath }} · {{ occurrence.selector }}</li></ul>
      </details>
    </details>
    <el-dialog v-model="entryVisible"
      :title="entryDialog ? entryDialog.title+' · '+(entryDialog.node.cmsName || entryDialog.node.name) : '入口职责'"
      class="workbench-entry-dialog" top="5vh" width="min(1280px, calc(100vw - 24px))" append-to-body destroy-on-close
      :before-close="closeEntry" :close-on-click-modal="false" @closed="restoreChecklistFocus">
      <div v-if="entryDialog" class="entry-dialog-layout">
        <div class="entry-dialog-decision">
          <section aria-label="入口问题" class="entry-dialog-problem">
            <h4>处理依据</h4>
            <p>{{ entryDialog.reason }}</p>
          </section>
          <EntryDecisionEvidence :plan="entryDialog.context.plan" :navigation="entryDialog.context.navigation" :node="entryDialog.node" />
          <ParentEntryDecisionEditor ref="entryEditor" :site-id="entryDialog.context.plan.siteId" :plan="entryDialog.context.plan"
            :navigation="entryDialog.context.navigation" :node="entryDialog.node" :disabled="disabled" @saved="acceptEntry">
            <template #actions><el-button :disabled="entryIsSaving()" @click="returnToChecklist">返回清单</el-button></template>
          </ParentEntryDecisionEditor>
        </div>
        <EntryDecisionSourcePreview :site-id="entryDialog.context.plan.siteId" :package-version="entryDialog.context.plan.packageVersion"
          :source-path="entryDialog.node.sourcePath" :visible="entryVisible" />
      </div>
    </el-dialog>
  </el-card>
</template>

<style scoped>
.checklist-toolbar{display:flex;align-items:center;flex-wrap:wrap;gap:8px 12px;margin-bottom:12px}
.checklist-runtime{display:block;color:var(--el-text-color-secondary)}
:global(.workbench-entry-dialog .el-dialog__body) { max-height: 78dvh; overflow-y: auto; }
.entry-dialog-layout { display: grid; grid-template-columns: minmax(290px, 1fr) minmax(0, 1.5fr); gap: 20px; }
.entry-dialog-decision { display: grid; align-content: start; gap: 12px; min-width: 0; }
.entry-dialog-problem { background: var(--el-fill-color-light); border-radius: var(--el-border-radius-base); padding: 10px 12px; }
.entry-dialog-problem p, .entry-dialog-problem h4 { margin: 0; overflow-wrap: anywhere; }
.entry-dialog-problem p { margin-top: 4px; }
@media (max-width: 800px) { .entry-dialog-layout { grid-template-columns: minmax(0, 1fr); gap: 16px; } }
</style>
