<template>
  <el-card shadow="never" class="release-review">
    <template #header><strong id="workbench-release-review" ref="progressHeading" tabindex="-1">整站发布签收</strong></template>
    <p v-if="!packageVersion">上传页面包并应用整站结构后，可核对必发页面和交互。</p>
    <template v-else>
      <p>站点 #{{ siteId }} · 页面包 {{ packageVersion }}<span v-if="reviewScope"> · 结构修订 {{ reviewScope.planRevision }} · 导航修订 {{ reviewScope.navigationRevision }}</span></p>
      <div class="admin-table-toolbar">
        <el-select :model-value="deployment" aria-label="部署方式" :disabled="busy" @update:model-value="changeDeployment">
          <el-option label="CMS 服务部署" value="CMS" />
          <el-option label="独立静态托管" value="STATIC" />
        </el-select>
        <el-button type="primary" :loading="reading" :disabled="disabled || saving" @click="read()">
          {{ report ? '重新核对整站' : '核对整站' }}
        </el-button>
        <el-button v-if="report?.nextBatch" :disabled="disabled || busy" @click="read(true)">继续核验</el-button>
        <el-button :disabled="disabled || saving" @click="emit('publish')">修正后重新生成</el-button>
      </div>
      <p v-if="deployment === 'STATIC'">从本机交付目录启动独立站点后，运行 verify-static.py --record，再点「核对整站」。表单服务连通后，还需从独立站点提交并核对后台记录。</p>
      <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
      <template v-if="report">
        <el-alert :type="report.ready ? 'success' : 'warning'" :closable="false" show-icon
          :title="report.ready ? '已满足当前结构与部署方式的签收条件' : '整站签收待处理'" />
        <p role="status">本次{{ deployment === 'CMS' ? ' CMS 服务' : '独立静态' }}回执读取于 {{ readAt }}。交付检查项 {{ pageRows.filter(page => page.state === 'READY').length }} / {{ pageRows.length }} 项已就绪，{{ pageRows.filter(page => page.state === 'EXCEPTION').length }} 项已审例外。当前有效的访客交互通过回执 {{ verifiedInteractions }} / {{ interactionRows.length }} 项。</p>
        <p v-if="report.deploymentEvidence">
          独立 HTTP 核验通过<span v-if="report.deploymentEvidence.verifiedAt">于 {{ report.deploymentEvidence.verifiedAt }}</span>。
          清单 SHA-256 <code>{{ report.deploymentEvidence.manifestDigest }}</code>
        </p>
        <p v-if="!visitorBase">访客地址待核对。<router-link v-if="deployment === 'CMS'" to="/admin/v2/site-service">查看站点服务地址</router-link><span v-else>请核对独立静态部署回执。</span></p>
        <p v-if="!report.evidenceCurrent && report.evidence.length">页面产物或扫描结果已变化，请复测已有交互并重新保存回执。</p>
        <p v-if="report.links.state === 'SCANNED'">静态链接已扫描 {{ report.links.checkedLinks }} 条，发现 {{ linkCounts.blocking }} 项发布阻断。扫描提示 {{ linkCounts.external + linkCounts.dynamic }} 项，其中站外或非网页链接 {{ linkCounts.external }} 项，浏览器交互链接 {{ linkCounts.dynamic }} 项<span v-if="report.links.truncated">。扫描达到上限，需完成核对后签收</span>。</p>
        <p v-else>静态链接扫描未完成，请核对发布产物后重新核对整站。</p>
        <el-table :data="pageRows" size="small" max-height="440" empty-text="暂无交付检查项">
          <el-table-column label="栏目与来源" min-width="155">
            <template #default="{ row }">
              {{ row.name }}<span v-if="row.menuId"> #{{ row.menuId }}</span>
              <div v-if="row.sourcePath">{{ row.sourcePath }}</div>
            </template>
          </el-table-column>
          <el-table-column label="正式页面" min-width="160">
            <template #default="{ row }">
              {{ row.route || '—' }}<span v-if="row.slot === 'DETAIL'"> · 详情<span v-if="row.recordId"> #{{ row.recordId }}</span></span>
              <div v-if="row.artifact">产物 · {{ row.artifact }}</div>
              <template v-if="row.visitorUrl"><a :href="row.visitorUrl" target="_blank" rel="noopener noreferrer">打开访客页</a>
                <el-button link @click="copyUrl(row.visitorUrl)">复制地址</el-button></template>
            </template>
          </el-table-column>
          <el-table-column label="核对结果" min-width="200">
            <template #default="{ row }">
              <el-tag size="small" :type="row.state === 'READY' ? 'success' : row.state === 'EXCEPTION' ? 'info' : 'warning'">
                {{ row.state === 'READY' ? '已核对' : row.state === 'EXCEPTION' ? '已审例外' : '待处理' }}
              </el-tag>
              <div>{{ row.reason }}</div>
              <div v-if="row.next?.hint">{{ row.next.hint }}</div>
              <el-button v-if="row.next?.run" link type="primary" :disabled="disabled || busy"
                @click="row.next.run($event)">{{ row.next.label }}</el-button>
            </template>
          </el-table-column>
        </el-table>

        <div class="admin-table-toolbar">
          <strong>交互验收</strong>
          <el-button size="small" :disabled="disabled || busy" @click="edit()">登记交互</el-button>
        </div>
        <el-table :data="interactionRows" size="small" max-height="400" empty-text="当前产物未识别到必需交互。可登记搜索、表单或定制功能。">
          <el-table-column label="路线与功能" min-width="160">
            <template #default="{ row }">{{ row.route }} · {{ kinds[row.kind as Kind] }}<span v-if="row.categoryId"> #{{ row.categoryId }}</span></template>
          </el-table-column>
          <el-table-column label="验收结果" min-width="185">
            <template #default="{ row }">
              {{ row.cmsRequired && deployment === 'STATIC' ? '待核对独立部署的动态服务' : outcomes[(row.receipt?.outcome ?? 'PENDING') as Outcome] }}
              <span v-if="row.receipt && !report.evidenceCurrent"> · 待复测</span>
              <div v-if="row.receipt">{{ row.receipt.owner }} · {{ row.receipt.result }}</div>
            </template>
          </el-table-column>
          <el-table-column width="120">
            <template #default="{ row }"><el-button link type="primary" :disabled="disabled || busy" @click="edit(row)">查看或登记</el-button></template>
          </el-table-column>
        </el-table>
      </template>
    </template>

    <el-dialog v-if="draft" v-model="dialog" title="交互验收回执" width="min(580px, 95vw)" :close-on-click-modal="false" :before-close="closeDialog">
      <el-form label-position="top" :disabled="saving" @submit.prevent>
        <el-form-item label="正式站内路由"><el-input v-model="draft.route" placeholder="/about.html" :disabled="editingExisting" /></el-form-item>
        <el-form-item label="功能">
          <el-select v-model="draft.kind" :disabled="editingExisting">
            <el-option v-for="(label, key) in kinds" :key="key" :value="key" :label="label" />
          </el-select>
        </el-form-item>
        <template v-if="draft.kind === 'FORM'">
          <p>在隔离站点实际提交，经表单中心查到记录后填写 ID。请勿填写提交者信息或查询令牌。</p>
          <el-form-item label="表单分类 ID"><el-input-number v-model="draft.categoryId" :min="1" :disabled="editingExisting" /></el-form-item>
          <el-form-item label="表单记录 ID"><el-input-number v-model="draft.recordId" :min="1" /></el-form-item>
          <el-button link type="primary" @click="emit('openFormCenter')">打开表单中心</el-button>
        </template>
        <el-form-item label="责任人"><el-input v-model="draft.owner" maxlength="120" /></el-form-item>
        <el-form-item label="复现步骤"><el-input v-model="draft.steps" type="textarea" maxlength="1000" :rows="3" /></el-form-item>
        <el-form-item label="验证结果或批准的例外"><el-input v-model="draft.result" type="textarea" maxlength="1000" :rows="3" /></el-form-item>
        <el-form-item label="结论">
          <el-select v-model="draft.outcome">
            <el-option v-for="(label, key) in outcomes" :key="key" :value="key" :label="label" :disabled="key === 'EXCEPTION' && required" />
          </el-select>
        </el-form-item>
      </el-form>
      <el-alert v-if="saveError" :title="saveError" type="error" :closable="false" show-icon />
      <template #footer>
        <el-button :disabled="saving" @click="dialog = false">取消</el-button>
        <el-button type="primary" :disabled="disabled || !draft.owner.trim() || !draft.steps.trim() || !draft.result.trim()"
          :loading="saving" @click="save">保存回执</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script lang="ts">
export interface ReleaseScope {
  id: string; packageVersion: string; planRevision: number; navigationRevision: number;
  originPlanId?: string | null; originPlanRevision?: number | null;
}
export interface ReleasePage {
  menuId?: number; name: string; sourcePath?: string; slot: string; recordId?: number; route?: string;
  artifact?: string; state: string; reason: string;
}
export interface ReleaseAction { label?: string; hint?: string; run?: (event: MouseEvent) => void }
export interface ReleaseNavigationEvidence { scope: ReleaseScope; siteId: number; pages: ReleasePage[]; contentPages?: ReleasePage[] }

type Deployment = 'CMS' | 'STATIC';

function releaseReadKey(adminId: string | undefined, siteId: number, scope?: ReleaseScope | null) {
  if (!adminId || !siteId || !scope?.id || !scope.packageVersion) return;
  return 'site-build:release-read:' + JSON.stringify([adminId, siteId, scope.packageVersion, scope.id,
    scope.planRevision, scope.navigationRevision]);
}

function rememberedModes(adminId: string | undefined, siteId: number, scope?: ReleaseScope | null): Deployment[] {
  const key = releaseReadKey(adminId, siteId, scope);
  if (!key) return [];
  try {
    const saved: unknown = JSON.parse(localStorage.getItem(key) || 'null');
    if (Array.isArray(saved)) return (['CMS', 'STATIC'] as const).filter(mode => saved.includes(mode));
  } catch { /* 浏览器存储不可用时保留原来的手动读取。 */ }
  return [];
}

export function hasRememberedReleaseReads(adminId: string, siteId: number, scope?: ReleaseScope | null) {
  return rememberedModes(adminId, siteId, scope).length > 0;
}

function rememberReleaseRead(adminId: string | undefined, siteId: number, scope: ReleaseScope, mode: Deployment) {
  const key = releaseReadKey(adminId, siteId, scope);
  if (!key) return;
  try { localStorage.setItem(key, JSON.stringify([...new Set([...rememberedModes(adminId, siteId, scope), mode])])); }
  catch { /* 此次真实回执仍可使用。 */ }
}

function forgetReleaseReads(adminId: string | undefined, siteId: number, scope?: ReleaseScope | null) {
  const key = releaseReadKey(adminId, siteId, scope);
  if (!key) return;
  try { localStorage.removeItem(key); } catch { /* 下次打开仍须重新请求服务端。 */ }
}
</script>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { http } from '@/api/http';
import { getSiteServiceList } from '@/api/templateRecognition';
import type { SiteBuildRevisionSnapshot } from '@/utils/siteBuildProgress';
import { ElMessage } from 'element-plus';

const kinds = { FORM: '表单', SEARCH: '搜索', FILTER: '筛选', PAGINATION: '分页', ASYNC: '异步片段', CUSTOM: '定制交接' };
const outcomes = { PENDING: '待验证', FAILED: '验证失败', PASSED: '已验证', EXCEPTION: '批准的例外' };
type Kind = keyof typeof kinds;
type Outcome = keyof typeof outcomes;
interface Target { route: string; kind: Kind; categoryId?: number | null }
interface Interaction extends Target { cmsRequired: boolean }
interface Evidence extends Target { owner: string; steps: string; result: string; outcome: Outcome; recordId?: number | null }
interface InteractionRow extends Interaction { receipt?: Evidence }
interface Report {
  fingerprint: string; digest: string; evidenceCurrent: boolean; ready: boolean;
  nextBatch?: number | null; continuationToken?: string | null; batch: number;
  deploymentEvidence?: { manifestDigest: string; verifiedAt?: string | null } | null;
  pages: ReleasePage[]; interactions: Interaction[]; evidence: Evidence[];
  links: { state: string; checkedLinks: number; truncated: boolean; items: {state: string}[] };
}
const props = defineProps<{
  adminId?: string; siteId: number; packageVersion?: string; scope?: ReleaseScope | null; disabled?: boolean;
  resolveAction: (page: ReleasePage, scope: ReleaseScope) => ReleaseAction | undefined;
  confirmLeave: (action: string) => Promise<boolean>;
}>();
const emit = defineEmits<{ publish: []; openFormCenter: []; navigationEvidence: [ReleaseNavigationEvidence | undefined] }>();
type ReleaseStageEvidence = SiteBuildRevisionSnapshot<Report>;
const deployment = ref<Deployment>('CMS');
const stageEvidence = ref<Partial<Record<Deployment,ReleaseStageEvidence>>>({});
const modeReadTimes = new Map<Deployment, string>();
const progressHeading = ref<HTMLElement>();

function markProgress(mode: Deployment, state: ReleaseStageEvidence['state'], scope = reviewScope.value, value?: Report) {
  if(!scope || scope.packageVersion!==props.packageVersion)return;
  const position={
    siteId:props.siteId,packageVersion:scope.packageVersion,planId:scope.id,
    planRevision:scope.planRevision,navigationRevision:scope.navigationRevision
  };
  if(state==='ready'){
    if(value)stageEvidence.value[mode]={...position,state,value};
  } else stageEvidence.value[mode]={...position,state};
}

function focusProgress() {
  if (!report.value && !busy.value) showModeReport(deployment.value);
  progressHeading.value?.scrollIntoView({block:'start'});
  progressHeading.value?.focus({preventScroll:true});
}
const report = ref<Report>();
// 与 WorkbenchReleaseReviewService 的发布阻断状态一致。
const blockingLinkStates = new Set(['MISSING_ARTIFACT', 'PACKAGE_FALLBACK', 'MISSING_ANCHOR', 'UNVERIFIED']);
const linkCounts = computed(() => {
  const items = report.value?.links.items ?? [];
  return {
    blocking: items.filter(item => blockingLinkStates.has(item.state)).length,
    external: items.filter(item => item.state === 'EXTERNAL').length,
    dynamic: items.filter(item => item.state === 'DYNAMIC').length,
  };
});
const cmsOrigin = ref('');
const visitorBase = computed(() => deployment.value === 'CMS' ? cmsOrigin.value
  : report.value?.pages.find(page => page.slot === 'DEPLOYMENT' && page.state === 'READY')?.route);
function visitorLink(page: ReleasePage) {
  const path = deployment.value === 'CMS' ? page.route : page.artifact;
  if (!visitorBase.value || !path || page.slot === 'DEPLOYMENT' || /[?#{}\\;]|^\/\/|^[a-z]+:/i.test(path)) return;
  try {
    const base = new URL(visitorBase.value);
    if (!['https:', 'http:'].includes(base.protocol) || base.username || base.password || base.search || base.hash) return;
    const url = new URL(path, base);
    if (url.origin === base.origin && !url.search && !url.hash) return url.href;
  } catch { return; }
}
async function copyUrl(value: string) {
  try { await navigator.clipboard.writeText(value); ElMessage.success('访客地址已复制'); }
  catch { ElMessage.warning('复制不可用，请复制访客链接地址'); }
}
const readAt = ref('');
function acceptEvidence(value: Report, scope: ReleaseScope, mode: Deployment) {
  modeReadTimes.set(mode, new Date().toLocaleString());
  markProgress(mode, 'ready', scope, value);
  if (mode === 'CMS') emit('navigationEvidence', {
    scope, siteId: props.siteId, pages: value.pages.filter(page => page.slot === 'NAVIGATION' && page.menuId),
    contentPages: value.pages.filter(page => page.menuId && (page.slot === 'LIST' || page.slot === 'DETAIL')),
  });
}
function showModeReport(mode: Deployment) {
  const scope = reviewScope.value;
  const proof = stageEvidence.value[mode];
  if (mode !== deployment.value || !scope || proof?.state !== 'ready' || proof.siteId !== props.siteId
      || proof.packageVersion !== props.packageVersion || proof.packageVersion !== scope.packageVersion
      || proof.planId !== scope.id || proof.planRevision !== scope.planRevision
      || proof.navigationRevision !== scope.navigationRevision) return;
  resolvedScope.value = scope;
  report.value = proof.value;
  readAt.value = modeReadTimes.get(mode) ?? '';
  if (mode === 'CMS') {
    const owner = props.siteId;
    const value = report.value;
    void getSiteServiceList().then(services => {
      if (props.siteId === owner && deployment.value === 'CMS' && report.value === value)
        cmsOrigin.value = services.find(site => site.siteId === owner && site.siteServiceStatus === 'RUNNING')?.siteServiceUrl || '';
    }).catch(() => { /* 不影响已经取得的报告。 */ });
  }
}
const reading = ref(false), saving = ref(false), dialog = ref(false);
const error = ref(''), saveError = ref('');
const editingExisting = ref(false), required = ref(false);
const draft = ref<Evidence>();
let request: AbortController | undefined;
let manualReadMode: Deployment | undefined;
const restoring = new Map<Deployment, AbortController>();
const restorationAttempts = new Set<Deployment>();
const busy = computed(() => reading.value || saving.value);
const plansEndpoint = computed(() => `/api/admin/v2/sites/${props.siteId}/template-seed/site-model/plans`);
const resolvedScope = ref<ReleaseScope>();
const reviewScope = computed(() => resolvedScope.value ?? props.scope);
const pageRows = computed(() => {
  const scope = resolvedScope.value;
  return report.value?.pages.map(page => ({
    ...page, visitorUrl: visitorLink(page),
    next: scope && page.state === 'PENDING' ? props.resolveAction(page, scope) : undefined,
  })) ?? [];
});
const endpoint = (id: string) => `${plansEndpoint.value}/${id}/release-review`;
const params = (scope: ReleaseScope, mode: Deployment) => ({
  packageVersion: scope.packageVersion,
  planRevision: scope.planRevision,
  navigationRevision: scope.navigationRevision,
  deployment: mode,
});
const readReport = (scope: ReleaseScope, mode: Deployment, signal: AbortSignal, previous?: Report) =>
  http.get<Report>(endpoint(scope.id), {
    params: { ...params(scope, mode), ...(previous ? { batch: previous.nextBatch, previousFingerprint: previous.continuationToken } : {}) },
    timeout: 60000,
    signal,
  });
async function resolveScope(signal: AbortSignal): Promise<ReleaseScope> {
  const preferred = props.scope;
  const packageVersion = props.packageVersion;
  if (!packageVersion) throw new Error('请先上传当前站点页面包。');
  const request = { params: { packageVersion }, signal };
  const base = plansEndpoint.value;
  const plan = await http.get<{ id: string; revision: number; status: string; packageVersion: string } | null>(base + '/latest', request);
  if (!plan || plan.status !== 'CONFIRMED' || plan.packageVersion !== packageVersion) {
    if (preferred?.packageVersion === packageVersion) return preferred;
    throw new Error('请先确认并应用当前页面包的整站结构。');
  }
  const nav = await http.get<Pick<ReleaseScope, 'originPlanId' | 'originPlanRevision'> &
    { revision: number; planRevision: number; status: string }>(`${base}/${plan.id}/navigation`, request);
  if (nav.status !== 'APPLIED' || nav.planRevision !== plan.revision)
    throw new Error('请先应用当前结构修订的导航。');
  return { id: plan.id, packageVersion, planRevision: plan.revision, navigationRevision: nav.revision,
    originPlanId: nav.originPlanId, originPlanRevision: nav.originPlanRevision };
}
function sameTarget(left: Target, right: Target) {
  return left.route === right.route && left.kind === right.kind && (left.categoryId ?? null) === (right.categoryId ?? null);
}
const interactionRows = computed<InteractionRow[]>(() => {
  if (!report.value) return [];
  const { interactions, evidence } = report.value;
  return [
    ...interactions.map(item => ({ ...item, receipt: evidence.find(row => sameTarget(row, item)) })),
    ...evidence.filter(row => !interactions.some(item => sameTarget(row, item)))
      .map(receipt => ({ ...receipt, cmsRequired: false, receipt })),
  ];
});
const verifiedInteractions = computed(() => report.value?.evidenceCurrent
  ? interactionRows.value.filter(row => row.receipt?.outcome === 'PASSED' && !(deployment.value === 'STATIC' && row.cmsRequired)).length : 0);
function reset() {
  cmsOrigin.value = '';
  request?.abort();
  request = undefined;
  if (manualReadMode && stageEvidence.value[manualReadMode]?.state === 'loading')
    delete stageEvidence.value[manualReadMode];
  manualReadMode = undefined;
  reading.value = false;
  saving.value = false;
  dialog.value = false;
  report.value = undefined;
  readAt.value = '';
  error.value = '';
  saveError.value = '';
}
function resetProgress() {
  for (const controller of restoring.values()) controller.abort();
  restoring.clear();
  restorationAttempts.clear();
  modeReadTimes.clear();
  if (Object.keys(stageEvidence.value).length) stageEvidence.value = {};
  reset();
  emit('navigationEvidence', undefined);
}
function invalidateProgress() {
  forgetReleaseReads(props.adminId, props.siteId, reviewScope.value);
  if (scopeKey(props.scope) !== scopeKey(reviewScope.value))
    forgetReleaseReads(props.adminId, props.siteId, props.scope);
  resetProgress();
}
function scopeKey(scope?: ReleaseScope | null) {
  return scope && JSON.stringify([scope.packageVersion,scope.id,scope.planRevision,scope.navigationRevision]);
}
watch([() => props.adminId, () => props.siteId, () => props.packageVersion], () => {
  resolvedScope.value = undefined;
  resetProgress();
}, { flush:'sync' });
watch(() => scopeKey(props.scope), (next, previous) => {
  // 首次载入栏目上下文可与用户主动读取并行；已有修订切换必须取消旧请求。
  if (!previous && next && (!resolvedScope.value || next === scopeKey(resolvedScope.value))) return;
  resolvedScope.value = undefined;
  resetProgress();
}, { flush:'sync' });
watch(deployment, mode => { reset(); showModeReport(mode); }, { flush:'sync' });
onBeforeUnmount(resetProgress);

async function restoreMode(scope: ReleaseScope, mode: Deployment) {
  const controller = new AbortController();
  const { adminId, siteId } = props;
  const key = scopeKey(scope);
  const matches = () => restoring.get(mode) === controller && props.siteId === siteId && props.adminId === adminId
    && props.packageVersion === scope.packageVersion && scopeKey(props.scope) === key;
  restorationAttempts.add(mode);
  restoring.set(mode, controller);
  markProgress(mode, 'loading', scope);
  try {
    const current = await resolveScope(controller.signal);
    if (!matches()) return;
    if (scopeKey(current) !== key) return;
    const fresh = await readReport(current, mode, controller.signal);
    if (matches()) {
      acceptEvidence(fresh, current, mode);
      if (!busy.value) showModeReport(mode);
    }
  } catch { /* 自动恢复失败时，进入签收后仍可手动读取。 */ }
  finally {
    if (matches() && stageEvidence.value[mode]?.state === 'loading') delete stageEvidence.value[mode];
    if (restoring.get(mode) === controller) restoring.delete(mode);
  }
}

function restoreRememberedReads() {
  const scope = props.scope;
  if (!scope || scope.packageVersion !== props.packageVersion || props.disabled) return;
  for (const mode of rememberedModes(props.adminId, props.siteId, scope)) {
    if (!restorationAttempts.has(mode) && !stageEvidence.value[mode] && !(request && deployment.value === mode))
      void restoreMode(scope, mode);
  }
}
watch([() => props.adminId, () => props.siteId, () => props.packageVersion, () => scopeKey(props.scope), () => props.disabled],
  restoreRememberedReads, { immediate: true, flush: 'post' });

async function read(continuePrevious = false) {
  if (!props.packageVersion || busy.value || props.disabled) return;
  const previous = continuePrevious ? report.value : undefined;
  if (continuePrevious && !previous?.nextBatch) return;
  const mode = deployment.value;
  restoring.get(mode)?.abort();
  restoring.delete(mode);
  const controller = new AbortController();
  request = controller;
  manualReadMode = mode;
  reading.value = true;
  markProgress(mode, 'loading');
  if (mode === 'CMS') emit('navigationEvidence', undefined);
  report.value = undefined;
  error.value = '';
  try {
    const scope = await resolveScope(controller.signal);
    if (request !== controller) return;
    if (props.scope && scopeKey(props.scope) !== scopeKey(scope)) throw new Error('当前结构修订与服务器最新回执不一致，请刷新工作台后重试。');
    resolvedScope.value = scope;
    markProgress(mode, 'loading', scope);
    const result = await readReport(scope, mode, controller.signal, previous);
    if (request === controller) {
      rememberReleaseRead(props.adminId, props.siteId, scope, mode);
      acceptEvidence(result, scope, mode);
      showModeReport(mode);
    }
  } catch (cause) {
    if (request === controller) {
      markProgress(mode, 'error');
      resolvedScope.value = undefined;
      error.value = cause instanceof Error ? cause.message : '整站核验读取失败';
    }
  } finally {
    if (request === controller) { reading.value = false; request = undefined; manualReadMode = undefined; }
  }
}
function edit(row?: InteractionRow) {
  required.value = !!row && !!report.value?.interactions.some(item => sameTarget(item, row));
  editingExisting.value = !!row;
  const previous = row?.receipt;
  draft.value = {
    route: row?.route ?? '', kind: row?.kind ?? 'CUSTOM', categoryId: row?.categoryId,
    recordId: previous?.recordId, owner: previous?.owner ?? '', steps: previous?.steps ?? '', result: previous?.result ?? '',
    outcome: report.value?.evidenceCurrent ? previous?.outcome ?? 'PENDING' : 'PENDING',
  };
  saveError.value = '';
  dialog.value = true;
}
function closeDialog(done: () => void) {
  if (!saving.value) done();
}
async function save() {
  const current = report.value;
  const value = draft.value;
  const scope = reviewScope.value;
  if (!current || !value || !scope || busy.value || props.disabled) return;
  const mode = deployment.value;
  const controller = new AbortController();
  request = controller;
  saving.value = true;
  saveError.value = '';
  const evidence = current.evidence.filter(row => !sameTarget(row, value))
    .map(row => current.evidenceCurrent ? row : { ...row, outcome: 'PENDING' });
  try {
    const result = await http.post<Report>(endpoint(scope.id), {
      scope: params(scope, mode), fingerprint: current.fingerprint, expectedDigest: current.digest, batch: current.batch,
      evidence: [...evidence, { ...value, categoryId: value.kind === 'FORM' ? value.categoryId : null }],
    }, { timeout: 60000, signal: controller.signal });
    if (request === controller) {
      acceptEvidence(result, scope, mode);
      showModeReport(mode);
      dialog.value = false;
    }
  } catch (cause) {
    if (request === controller) saveError.value = cause instanceof Error ? cause.message : '交互回执保存失败';
  } finally {
    if (request === controller) { saving.value = false; request = undefined; }
  }
}
async function changeDeployment(mode: Deployment) {
  if (mode === deployment.value || saving.value) return;
  if (dialog.value && !await props.confirmLeave('切换部署方式')) return;
  deployment.value = mode;
}
defineExpose({stageEvidence,selectedDeployment:deployment,focusProgress,invalidateProgress,
  isSaving: () => saving.value, hasUnsavedChanges: () => dialog.value});
</script>

<style scoped>
.release-review :deep(.el-tag) { --el-tag-text-color: var(--el-text-color-primary); }
.release-review :deep(.el-alert__title) { color: var(--el-text-color-primary); }
.release-review { margin-block: 12px; }
.release-review .admin-table-toolbar { margin-block: 12px; }
.release-review .el-select { max-width: 235px; }
</style>
