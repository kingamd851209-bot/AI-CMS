<template>
  <el-card v-if="plan" shadow="never">
    <template #header><strong>公开应用{{ plan.pageType === 0 ? '单页模板' : '详情模板' }} · 栏目 {{ plan.menuId }}</strong></template>
    <p>先在本工作台核对栏目路由与内容发布状态，再检查并应用模板。已发布页面可用当前 CMS 内容试编译、检查目标地址，再切换模板和地址。</p>
    <p>当前来源：{{ plan.sourcePath }}。检查成功后，请核对下方公开路径；模板应用后仍需打开前台验证样式、内容和链接。</p>
    <p v-if="plan.pageType === 1">本步骤接入详情页；完整列表和筛选需另行确认接线，私有单卡预览不代表列表已公开。</p>
    <el-button :disabled="blocked" @click="check">检查公开应用</el-button>
    <el-button v-if="!result?.applied && !stale && !previewFirstSingle" type="primary" :disabled="blocked || plan.status === 'DRAFT' || (pageListDirty && !collectionPreview?.changeDigest) || (plan.pageType === 1 && (!actionEvidence || !collectionEvidence || result?.applied || !actionsReady || !collectionsReady || !neighborsReady))" @click="act('apply')">确认公开应用</el-button>
    <el-button v-if="result?.applied" :disabled="blocked" @click="act('withdraw')">{{ result.previousRoute ? '恢复前一应用' : '撤回当前模板应用' }}</el-button>
    <el-button :disabled="blocked || !result" @click="reload">重新加载已有站点路由</el-button>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-alert v-if="notice" :title="notice" type="info" :closable="false" />
    <p v-if="result"><strong>{{ result.applied ? '模板已应用 · 待前台验证' : '当前模板尚未应用此计划' }}</strong><br/>{{ result.message }}</p>
    <p v-if="result?.publicUrl">公开路径：<code>{{ result.publicUrl }}</code>。从站点服务打开前台，按此路径核对当前内容。</p>
    <p v-if="result?.previousRoute && result.previousRoute !== result.currentRoute">
      旧地址 <code>{{ result.previousRoute }}</code> 的静态文件待核对。请生成当前地址后在静态核验中检查旧文件。
    </p>
    <section v-if="(result?.applied || previewFirstSingle) && plan.status !== 'DRAFT'" aria-label="预览公开模板与路由">
      <label>目标正式地址
        <el-input v-model="routeDraft" :disabled="blocked" :readonly="previewFirstSingle" maxlength="512" aria-label="目标正式地址" />
      </label>
      <el-button :disabled="blocked || !routeDraft.trim()" @click="checkRoute()">试编译并预览 CMS 内容</el-button>
      <p v-if="requiresCurrentPlan">请按当前已确认的站点结构生成并确认此页字段计划，然后返回切换。</p>
      <el-button v-if="requiresCurrentPlan" :disabled="blocked" @click="emit('review')">打开当前字段审核</el-button>
      <template v-if="routePreview?.preview">
        <p>目标地址 <code>{{ routePreview.publicUrl }}</code> · {{ routePreview.message }}</p>
        <p v-for="warning in routePreview.preview.warnings" :key="warning">{{ warning }}</p>
        <iframe title="公开切换前的 CMS 内容预览" sandbox="" referrerpolicy="no-referrer"
          :srcdoc="routePreview.preview.html" style="width:100%;height:50vh;border:0" />
        <el-button v-if="!requiresCurrentPlan" type="primary" :disabled="blocked || !routePreview.changeDigest" @click="act('route')">{{ previewFirstSingle ? '确认公开应用' : '确认切换正式地址和模板' }}</el-button>
      </template>
    </section>
    <el-button v-if="result?.applied" link type="primary" @click="$router.push('/admin/v2/site-service')">打开站点服务</el-button>
    <el-button v-if="result?.applied && result.publicUrl" type="primary" plain
      @click="$router.push({path:'/admin/v2/cms/static-publish',query:{siteId:String(siteId),menuId:String(plan.menuId)}})">生成此栏目静态页</el-button>
    <section v-if="plan.pageType === 1 && plan.status !== 'DRAFT'" aria-labelledby="detail-form-action-heading">
      <h3 id="detail-form-action-heading">详情按钮表单预填</h3>
      <p v-if="!actionEvidence">点击「检查公开应用」读取原页面按钮和可选目标栏目。</p>
      <p v-else-if="!actionEvidence.actions.length">当前页面没有可接入预填的表单按钮。</p>
      <template v-else>
        <p>访客点击已接入的按钮时，详情记录标题（title）将随链接传入选定表单输入框。访客可核对预填值并自行提交。</p>
        <p v-if="result?.applied">当前详情模板已应用。修改预填配置前，请先撤回本次模板应用。</p>
        <p v-if="!actionEvidence.targetMenus.length">当前站点没有可选目标栏目，请先核对单页栏目和公开路由。</p>
        <p v-if="!categories.length">当前站点没有可选表单分类。请在「站点内容 → 表单设置」核对分类，或在工作台导入原页面表单。</p>
        <p v-if="actionsDirty" role="status">有待应用的详情预填修改。</p>
        <div v-for="action in actionEvidence.actions" :key="action.sourceId" class="detail-form-action">
          <p><strong>{{ action.label || '表单按钮' }}</strong> · {{ action.targetPath }}</p>
          <el-checkbox :model-value="!!actionDraft[action.sourceId]" :disabled="blocked || result?.applied"
            @change="setActionEnabled(action, $event === true)">启用表单预填</el-checkbox>
          <template v-if="actionDraft[action.sourceId]">
            <div class="detail-form-fields">
              <label>目标表单栏目
                <el-select v-model="actionDraft[action.sourceId]!.targetMenuId" aria-label="目标表单栏目" :disabled="blocked || result?.applied" filterable>
                  <el-option v-for="menu in actionEvidence.targetMenus" :key="menu.id" :label="`${menu.name} · #${menu.id}`" :value="menu.id" />
                </el-select>
              </label>
              <label>预填输入框
                <el-select v-model="actionDraft[action.sourceId]!.inputId" aria-label="预填输入框" :disabled="blocked || result?.applied">
                  <el-option v-for="field in action.fields" :key="field.inputId" :label="`${field.label}（${field.inputId}）`" :value="field.inputId" />
                </el-select>
              </label>
              <label>后台表单分类
                <el-select v-model="actionDraft[action.sourceId]!.formCategoryId" aria-label="后台表单分类" :disabled="blocked || result?.applied" filterable>
                  <el-option v-for="category in categories" :key="category.id" :label="`${category.className} · #${category.id}`" :value="category.id" />
                </el-select>
              </label>
            </div>
            <el-button :disabled="blocked || !selection(action.sourceId)" @click="checkActionTarget(action.sourceId, false)">预览目标表单</el-button>
            <el-button v-if="targetPreview(action.sourceId)?.submissionReady && !targetPreview(action.sourceId)?.applied && !result?.applied"
              :disabled="blocked" type="primary" @click="checkActionTarget(action.sourceId, true)">接入目标表单预填</el-button>
            <p v-if="targetPreview(action.sourceId)" role="status">
              <code>{{ targetPreview(action.sourceId)?.publicUrl }}</code> · {{ targetPreview(action.sourceId)?.message }}
              <span v-if="targetPreview(action.sourceId)?.applied">可以应用详情模板。</span>
            </p>
            <p v-else-if="!result?.applied && !matchesSaved(action.sourceId)">选择栏目、输入框和分类后，预览并核对目标。</p>
          </template>
        </div>
      </template>
    </section>
    <p v-if="collectionError" role="status">{{ collectionError }}</p>
    <section v-if="collectionEvidence?.collections.length || collectionDraft.length" aria-labelledby="detail-collection-heading">
      <h3 id="detail-collection-heading">内容引用与重复区域</h3>
      <p>按已确认的整站结构接入。归属条目随当前详情记录维护，引用条目按内容编辑器的选择顺序展示。启用后，当前记录无条目时隐藏原区块。</p>
      <p v-if="result?.applied && plan.pageType===1">修改详情集合绑定前，请先撤回本次模板应用。</p>
      <p v-if="collectionsDirty" role="status">有待应用的集合修改。</p>
      <div v-for="item in collectionEvidence?.collections" :key="item.regionId" class="detail-form-action">
        <el-checkbox :model-value="collectionDraft.includes(item.regionId)" :disabled="blocked || stale || (result?.applied && plan.pageType===1) || (!!item.unavailableReason && !collectionDraft.includes(item.regionId))"
          @change="setCollection(item.regionId, $event === true)">{{ item.mode === 'PAGE_LIST' ? '单页重复区域' : item.mode === 'OWNED' ? '归属条目' : item.mode === 'INLINE_REFERENCE' ? '推荐链接' : '引用条目' }} · {{ item.targetMenuName || '目标栏目待确认' }}<span v-if="item.targetMenuId"> · #{{ item.targetMenuId }}</span><span v-if="(item.targetMenuIds?.length ?? 0) > 1"> · 共 {{ item.targetMenuIds?.length }} 个来源栏目</span></el-checkbox>
        <p>{{ item.reason }}</p>
        <p v-if="item.mode==='PAGE_LIST'">{{ collectionEvidence?.selectedCollectionRegionIds.includes(item.regionId) ? '已应用，按专用栏目中已发布记录的排序展示。列表为空时隐藏条目区域。' : '当前沿用原页面，仍待应用此集合。' }}</p>
        <details v-if="item.regionSelector">
          <summary>原页位置与 {{ item.recordCount }} 条来源</summary>
          <p><code>{{ item.regionSelector }}</code></p>
          <ol v-if="item.rowSelectors?.length">
            <li v-for="position in item.rowSelectors" :key="position"><code>{{ position }}</code></li>
          </ol>
        </details>
        <p v-if="item.fields.length">绑定字段 {{ item.fields.map(field => field.label || field.fieldName).join('、') }} · 原页面 {{ item.recordCount }} 条</p>
        <el-button v-if="item.mode==='PAGE_LIST' && item.targetMenuId && !item.unavailableReason" link type="primary"
          @click="$router.push({path:'/admin/v2/cms/content-management',query:{siteId:String(siteId),menuId:String(item.targetMenuId)}})">打开条目维护</el-button>
        <p v-if="item.unavailableReason" role="status">{{ item.unavailableReason }}</p>
      </div>
      <el-button v-if="pageListDirty" :disabled="blocked || stale || !collectionsReady" @click="checkCollections">试编译并预览重复条目</el-button>
      <iframe v-if="collectionPreview?.preview" title="单页重复条目预览" sandbox="" referrerpolicy="no-referrer"
        :srcdoc="collectionPreview.preview.html" style="width:100%;height:50vh;border:0" />
      <el-button v-if="result?.applied && plan.pageType===0 && collectionsDirty" type="primary" :disabled="blocked || stale || !collectionsReady || (pageListDirty && !collectionPreview?.changeDigest)" @click="act('apply')">保存单页集合绑定</el-button>
      <div v-for="id in unknownCollections" :key="id">
        <el-checkbox :model-value="true" :disabled="blocked || result?.applied" @change="setCollection(id, false)">旧绑定 {{ id }} · 当前方案已无对应证据</el-checkbox>
      </div>
    </section>
    <section v-if="plan.pageType === 1 && plan.status !== 'DRAFT'" aria-labelledby="detail-neighbor-heading">
      <h3 id="detail-neighbor-heading">详情上下篇与返回列表</h3>
      <el-alert v-if="neighborError" :title="neighborError" type="warning" :closable="false" />
      <p v-else-if="!neighborEvidence">点击「检查公开应用」读取原页导航证据。</p>
      <p v-else-if="!neighborEvidence.candidates.length && !neighborDraft">当前没有可审核的上下篇或返回列表。独立返回入口需先应用本栏的正式列表。</p>
      <template v-else>
        <p>核对原页证据后，选择要接入的详情导航。</p>
        <p v-if="result?.applied && !activeNeighborsEditable">请打开当前应用的有效详情计划后修改详情导航。</p>
        <label>审核原页区域
          <el-select v-model="neighborDraft" aria-label="审核详情导航区域" :disabled="blocked || stale || (result?.applied && !activeNeighborsEditable)">
            <el-option label="不接入详情导航" value="" />
            <el-option v-if="!neighborsReady" label="原选区无法定位" :value="neighborDraft" disabled />
            <el-option v-for="(candidate, index) in neighborEvidence?.candidates" :key="candidate.id"
              :label="candidate.kind === 'RETURN_ONLY' ? '返回本栏目列表' : `区域 ${index + 1} · ${candidate.nextTitle || candidate.previousTitle || '上下篇'}`" :value="candidate.id" />
          </el-select>
        </label>
        <p v-if="neighborsDirty" role="status">有待应用的详情导航修改。</p>
        <p v-if="!neighborsReady" role="status">原选区已无法定位。请选择有效候选，或明确移除该绑定。</p>
        <div v-if="neighborCandidate" class="detail-form-action detail-neighbor-evidence">
          <template v-if="neighborCandidate.kind === 'NEIGHBORS'">
            <p>上下篇按当前栏目的有效已发布记录排序。相邻项不存在时隐藏该项；两项都缺失时隐藏区域。</p>
            <p>原页定位 <code>{{ neighborCandidate.selector }}</code></p>
            <p>原上一篇 {{ neighborCandidate.previousTitle || '无' }} · <code>{{ neighborCandidate.previousHref || '无链接' }}</code></p>
            <p>原下一篇 {{ neighborCandidate.nextTitle || '无' }} · <code>{{ neighborCandidate.nextHref || '无链接' }}</code></p>
          </template>
          <template v-if="neighborCandidate.returnHref">
            <p>原返回链接 <code>{{ neighborCandidate.returnHref }}</code> · 定位 <code>{{ neighborCandidate.returnSelector }}</code></p>
            <p v-if="neighborEvidence?.returnListUrl">将返回本栏正式列表 <code>{{ neighborEvidence.returnListUrl }}</code>。</p>
            <p v-else role="status">本栏正式列表尚未就绪。接入时隐藏返回入口，直至正式列表可用。</p>
          </template>
        </div>
        <template v-if="activeNeighborsEditable && neighborsDirty">
          <el-button :disabled="blocked || !neighborsReady" @click="checkRoute(true)">核对详情导航修改</el-button>
          <template v-if="neighborPreview">
            <p role="status">当前正式路径 <code>{{ neighborPreview.publicUrl }}</code> · {{ neighborPreview.message }}</p>
            <el-button type="primary" :disabled="blocked || !neighborPreview.changeDigest" @click="act('neighbors')">确认修改详情导航</el-button>
          </template>
        </template>
      </template>
    </section>
    <section v-if="plan.pageType === 0" class="home-application">
      <h3>首页接入</h3>
      <p>先完成上方单页公开应用，再将它用作首页内容。根栏目只承担路由，根字段与来源单页内容不改写；原页面包保留。</p>
      <el-button :disabled="blocked" @click="checkHome">读取真实首页入口</el-button>
      <el-alert v-if="homeSwitchError" :title="homeSwitchError" type="error" :closable="false" />
      <template v-if="homeSwitch">
        <p role="status">{{ homeSwitch.message }}</p>
        <p v-if="homeSwitch.activePlanId">当前首页计划 <code>{{ homeSwitch.activePlanId }}</code></p>
        <p v-if="homeSwitch.activeTemplatePath">当前根模板 <code>{{ homeSwitch.activeTemplatePath }}</code></p>
        <p v-if="homeSwitch.sourceTemplatePath">本计划来源模板 <code>{{ homeSwitch.sourceTemplatePath }}</code></p>
        <p v-if="homeSwitch.publicUrls.length">受影响入口 {{ homeSwitch.publicUrls.join('、') }}</p>
        <el-button v-if="homeSwitch.canSwitch" type="primary" :disabled="blocked" @click="changeHomeVersion(false)">切换到本计划</el-button>
        <el-button v-if="homeSwitch.canRollback" :disabled="blocked" @click="changeHomeVersion(true)">恢复上一版首页</el-button>
      </template>
      <template v-if="homeOptions && (!homeOptions.activePlanId || homeOptions.applied && !homeSwitch?.canRollback)">
        <p>{{ homeOptions.message }}</p>
        <p>将一起接入根路径 /、当前根路由及以下真实入口：{{ homeOptions.availableAliases.join('、') || '没有额外 index 文件' }}。</p>
        <el-button v-if="!homeOptions.activePlanId" type="primary" :disabled="blocked || !result?.applied" @click="homeAction(false)">确认接入首页</el-button>
        <el-button v-if="homeOptions.applied && homeSwitch && !homeSwitch.canRollback" :disabled="blocked" @click="homeAction(true)">撤回本计划首页应用</el-button>
      </template>
      <template v-if="homeResult">
        <p>{{ homeResult.message }}</p>
        <p>请在当前站点实际前台地址逐一读取：</p><ul><li v-for="url in homeResult.publicUrls" :key="url"><code>{{ url }}</code></li></ul>
      </template>
      <el-button v-if="homeResult?.requiresRouteReload || homeSwitch?.activePlanId" :disabled="blocked" @click="reload">重载首页路由</el-button>
      <el-button v-if="rootMenuId && (homeResult?.applied ?? homeOptions?.applied)" type="primary" plain
        :disabled="blocked" @click="$router.push({path:'/admin/v2/cms/static-publish',query:{siteId:String(siteId),menuId:String(rootMenuId)}})">生成正式首页静态页</el-button>
    </section>
  </el-card>
</template>
<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { ElMessageBox } from 'element-plus';
import type { FieldPlan } from '@/api/templateFieldPlan';
import { applyPublicBinding, previewPublicBinding, inspectDetailCollections, inspectDetailFormActions, inspectDetailNeighbors, inspectPublicBinding, withdrawPublicBinding, type DetailCollectionEvidence, type DetailFormAction, type DetailFormActionEvidence, type DetailNeighborEvidence, type PublicBindingResult } from '@/api/templateTextBinding';
import { formCategoryApi, type CategoryNode } from '@/api/cmsPlatform';
import { applyQueryFormTarget, previewQueryFormTarget, type QueryFormActionEvidence, type QueryFormActionTargetResult } from '@/api/templateQueryBinding';
import { reloadSiteRoutes } from '@/api/templateRecognition';
import { inspectPublicHome, applyPublicHome, withdrawPublicHome, inspectPublicHomeSwitch, switchPublicHome, type RootApplicationOptions, type RootApplicationResult, type RootSwitchOptions } from '@/api/templateRootApplication';
const props = defineProps<{ siteId: number; rootMenuId?: number | null; plan: FieldPlan | null; configuredRoute?: string | null; disabled?: boolean; stale?: boolean }>();
const emit = defineEmits<{ busy: [value: boolean]; changed: []; review: [] }>();
const busy = ref(false), error = ref(''), notice = ref('');
const blocked = computed(() => busy.value || props.disabled);
const result = ref<PublicBindingResult | null>(null);
const requiresCurrentPlan = computed(() => !!props.stale || !!result.value?.requiresCurrentPlan);
const routeDraft = ref('');
const routePreview = ref<PublicBindingResult | null>(null);
watch(routeDraft, () => { routePreview.value = null; }, { flush: 'sync' });
const homeOptions = ref<RootApplicationOptions | null>(null), homeResult = ref<RootApplicationResult | null>(null);
const homeSwitch = ref<RootSwitchOptions | null>(null);
const homeSwitchError = ref('');
type ActionDraft = Pick<DetailFormAction, 'inputId'> & Partial<Pick<DetailFormAction, 'targetMenuId' | 'formCategoryId'>>;
const actionEvidence = ref<DetailFormActionEvidence | null>(null);
const collectionEvidence = ref<DetailCollectionEvidence | null>(null);
const collectionError = ref('');
const collectionDraft = ref<string[]>([]);
const collectionPreview = ref<PublicBindingResult | null>(null);
watch(collectionDraft, () => { collectionPreview.value = null; }, { flush: 'sync' });
const unknownCollections = computed(() => collectionDraft.value.filter(id => !collectionEvidence.value?.collections.some(item => item.regionId === id)));
const collectionsDirty = computed(() => {
  const saved = collectionEvidence.value?.selectedCollectionRegionIds || [];
  return saved.length !== collectionDraft.value.length || collectionDraft.value.some(id => !saved.includes(id));
});
const firstSingleRoute = computed(() => props.plan?.pageType === 0 && props.plan.menuId !== props.rootMenuId
  ? props.configuredRoute?.trim() || '' : '');
const previewFirstSingle = computed(() => !!result.value && !result.value.applied
  && !!firstSingleRoute.value && !collectionsDirty.value);
watch(firstSingleRoute, value => {
  if (result.value && !result.value.applied) routeDraft.value = value;
}, { flush: 'sync' });
const collectionsReady = computed(() => !unknownCollections.value.length && collectionDraft.value.every(id =>
  collectionEvidence.value?.collections.some(item => item.regionId === id && !item.unavailableReason)));
const pageListDirty = computed(() => collectionEvidence.value?.collections.some(item =>
  item.mode === 'PAGE_LIST' && collectionDraft.value.includes(item.regionId) !==
    collectionEvidence.value?.selectedCollectionRegionIds.includes(item.regionId)) ?? false);
const neighborEvidence = ref<DetailNeighborEvidence | null>(null);
const neighborDraft = ref('');
const neighborError = ref('');
const neighborPreview = ref<PublicBindingResult | null>(null);
const activeNeighborsEditable = computed(() => result.value?.applied && result.value.activePlanId === props.plan?.id && !requiresCurrentPlan.value);
const neighborCandidate = computed(() => neighborEvidence.value?.candidates.find(item => item.id === neighborDraft.value));
const neighborsReady = computed(() => !neighborDraft.value || !!neighborCandidate.value);
const neighborsDirty = computed(() => neighborDraft.value !== (neighborEvidence.value?.selectedCandidateId || ''));
watch(neighborDraft, () => { neighborPreview.value = null; }, { flush: 'sync' });
const hasUnsavedChanges = computed(() => actionsDirty.value || collectionsDirty.value || neighborsDirty.value
  || !!result.value?.currentRoute && routeDraft.value !== result.value.currentRoute);
function setCollection(id: string, enabled: boolean) {
  if (blocked.value || result.value?.applied && props.plan?.pageType === 1) return;
  collectionDraft.value = enabled ? [...collectionDraft.value, id] : collectionDraft.value.filter(value => value !== id);
}
function installCollections(evidence: DetailCollectionEvidence | null) {
  collectionEvidence.value = evidence;
  collectionDraft.value = [...(evidence?.selectedCollectionRegionIds ?? [])];
}
function installNeighbors(evidence: DetailNeighborEvidence | null) {
  neighborPreview.value = null;
  neighborEvidence.value = evidence;
  neighborDraft.value = evidence?.selectedCandidateId || '';
}
const categories = ref<CategoryNode[]>([]);
const actionDraft = ref<Record<string, ActionDraft>>({});
const targets = ref<Record<string, { selection: DetailFormAction; result: QueryFormActionTargetResult }>>({});
function selection(sourceId: string): DetailFormAction | null {
  const draft = actionDraft.value[sourceId];
  if (!draft?.inputId || !draft.targetMenuId || !draft.formCategoryId) return null;
  return { sourceId, fieldName: 'title', inputId: draft.inputId, targetMenuId: draft.targetMenuId, formCategoryId: draft.formCategoryId };
}
function sameSelection(left: DetailFormAction, right: DetailFormAction | null) {
  return !!right && left.sourceId === right.sourceId && left.fieldName === right.fieldName && left.inputId === right.inputId
    && left.targetMenuId === right.targetMenuId && left.formCategoryId === right.formCategoryId;
}
function matchesSaved(sourceId: string) {
  return actionEvidence.value?.selectedActions.some(action => sameSelection(action, selection(sourceId))) ?? false;
}
function targetPreview(sourceId: string) {
  const checked = targets.value[sourceId];
  return checked && sameSelection(checked.selection, selection(sourceId)) ? checked.result : null;
}
const actionsDirty = computed(() => {
  const ids = Object.keys(actionDraft.value);
  return ids.length !== (actionEvidence.value?.selectedActions.length ?? 0) || ids.some(id => !matchesSaved(id));
});
const actionsReady = computed(() => Object.keys(actionDraft.value).every(id => {
  const target = targetPreview(id);
  return matchesSaved(id) || !!target?.applied && !!target.submissionReady && target.formCategoryId === selection(id)?.formCategoryId;
}));
defineExpose({ hasUnsavedChanges });
function setActionEnabled(action: QueryFormActionEvidence, enabled: boolean) {
  if (blocked.value || result.value?.applied) return;
  if (enabled) {
    const saved = actionEvidence.value?.selectedActions.find(item => item.sourceId === action.sourceId);
    actionDraft.value[action.sourceId] = saved ? { ...saved } : { inputId: action.suggestedInputId ?? '' };
  } else delete actionDraft.value[action.sourceId];
  delete targets.value[action.sourceId];
}
function installActions(evidence: DetailFormActionEvidence | null) {
  actionEvidence.value = evidence;
  actionDraft.value = Object.fromEntries((evidence?.selectedActions ?? []).map(action => [action.sourceId, { ...action }]));
  targets.value = {};
}
let revision = 0;
watch(() => [props.siteId, props.plan?.id, props.plan?.packageVersion, props.plan?.unitsRevision, props.plan?.status, props.plan?.afterFieldConfigDigest], () => { revision++; result.value = null; routeDraft.value = ''; homeOptions.value = null; homeResult.value = null; homeSwitch.value = null; homeSwitchError.value = ''; error.value = ''; notice.value = ''; collectionError.value=''; installActions(null); installCollections(null); installNeighbors(null); neighborError.value = ''; categories.value = []; busy.value = false; }, { flush: 'sync' });
watch(busy, value => emit('busy', value), { flush: 'sync' });
onBeforeUnmount(() => { revision++; if (busy.value) emit('busy', false); });
async function check() {
  const plan = props.plan; if (!plan || blocked.value) return;
  const siteId = props.siteId;
  const token = ++revision; busy.value = true; error.value = '';
  try {
    if (hasUnsavedChanges.value) await ElMessageBox.confirm('重新读取会放弃本地目标地址和详情模板修改。', '保护详情模板修改', { type: 'warning' });
    if (token !== revision) return;
    const detail = !props.stale && plan.pageType === 1 && plan.status !== 'DRAFT';
    neighborError.value = ''; collectionError.value='';
    const [value, evidence, collections, neighbors] = await Promise.all([
      inspectPublicBinding(siteId, plan.id),
      detail ? inspectDetailFormActions(siteId, plan.id) : Promise.resolve(null),
      !props.stale && plan.status !== 'DRAFT' ? inspectDetailCollections(siteId, plan.id).catch(cause=>{
        if (detail) throw cause;
        if (token===revision) collectionError.value='当前计划的推荐链接证据不可用。可重读当前结构方案后核对。';
        return null;
      }) : Promise.resolve(null),
      detail ? inspectDetailNeighbors(siteId, plan.id).catch(() => {
        if (token === revision) neighborError.value = '无法读取详情导航证据，请重试检查公开应用。';
        return null;
      }) : Promise.resolve(null),
    ]);
    if (token !== revision) return;
    const forms = evidence?.actions.length ? await formCategoryApi.all() : [];
    if (token !== revision) return;
    result.value = value;
    routeDraft.value = value.currentRoute || firstSingleRoute.value;
    categories.value = forms;
    installActions(evidence);
    installCollections(collections);
    installNeighbors(neighbors);
  }
  catch (e) { if (token === revision && e !== 'cancel' && e !== 'close') error.value = e instanceof Error ? e.message : '无法读取公开应用'; }
  finally { if (token === revision) busy.value = false; }
}
async function checkActionTarget(sourceId: string, apply: boolean) {
  const plan = props.plan, action = selection(sourceId), previous = targetPreview(sourceId);
  if (!plan || !action || blocked.value || (apply && (!previous?.submissionReady || previous.applied || result.value?.applied))) return;
  const siteId = props.siteId, token = ++revision;
  const request = { detailPlanId: plan.id, action, formCategoryId: action.formCategoryId };
  busy.value = true; error.value = '';
  try {
    if (apply && previous) {
      const menu = actionEvidence.value?.targetMenus.find(item => item.id === action.targetMenuId)?.name;
      const impact = previous.previousTemplate ? `替换当前模板 ${previous.previousTemplate}` : '设置栏目模板';
      await ElMessageBox.confirm(`将为「${menu}」${impact}，让分类 #${action.formCategoryId} 的输入框 ${action.inputId} 接收详情标题。公开路径 ${previous.publicUrl}。`, '确认接入目标表单预填', { type: 'warning' });
      if (token !== revision) return;
      for (const [id, target] of Object.entries(targets.value)) {
        if (target.selection.targetMenuId === action.targetMenuId) delete targets.value[id];
      }
      await applyQueryFormTarget(siteId, { ...request, expectedTemplate: previous.previousTemplate, confirmed: true });
      if (token !== revision) return;
    }
    const value = await previewQueryFormTarget(siteId, request);
    if (token === revision) targets.value[sourceId] = { selection: action, result: value };
  } catch (e) {
    if (token === revision && e !== 'cancel' && e !== 'close') {
      delete targets.value[sourceId];
      error.value = `${e instanceof Error ? e.message : '无法核对目标表单'}。请在「站点内容 → 表单设置」核对分类和字段；原页表单可从工作台导入。`;
    }
  } finally { if (token === revision) busy.value = false; }
}
async function checkRoute(neighborsOnly = false) {
  const plan = props.plan;
  if (!plan || blocked.value) return;
  if (neighborsOnly ? !activeNeighborsEditable.value || !neighborsDirty.value || !neighborsReady.value
    : !(result.value?.applied || previewFirstSingle.value) || !routeDraft.value.trim()) return;
  const preview = neighborsOnly ? neighborPreview : routePreview;
  const draft = neighborsOnly ? { neighborCandidateId: neighborDraft.value } : { routeUrl: routeDraft.value.trim() };
  const token = ++revision;
  busy.value = true;
  error.value = '';
  preview.value = null;
  try {
    const value = await previewPublicBinding(props.siteId, plan.id, draft);
    if (token === revision) preview.value = value;
  } catch (e) {
    if (token === revision) error.value = e instanceof Error ? e.message : '试编译失败';
  } finally {
    if (token === revision) busy.value = false;
  }
}
async function act(mode: 'apply' | 'withdraw' | 'route' | 'neighbors') {
  const plan = props.plan; if (!plan || blocked.value) return;
  const withdraw = mode === 'withdraw', switchRoute = mode === 'route', updateNeighbors = mode === 'neighbors';
  const changeDigest = (mode === 'apply' ? collectionPreview.value
    : updateNeighbors ? neighborPreview.value : routePreview.value)?.changeDigest;
  if (switchRoute && (!changeDigest || requiresCurrentPlan.value || !(result.value?.applied || previewFirstSingle.value))) return;
  if (updateNeighbors && (!changeDigest || !activeNeighborsEditable.value || !neighborsDirty.value || !neighborsReady.value)) return;
  if (mode === 'apply' && plan.pageType === 1 && (!actionEvidence.value || !collectionEvidence.value || result.value?.applied || !actionsReady.value || !collectionsReady.value || !neighborsReady.value)) return;
  if (mode === 'apply' && plan.pageType === 0 && (!collectionsReady.value || pageListDirty.value && !changeDigest)) return;
  const actions = mode === 'apply' && actionsDirty.value ? Object.keys(actionDraft.value).map(selection).filter((action): action is DetailFormAction => action !== null) : undefined;
  const collectionRegionIds = mode === 'apply' && collectionsDirty.value ? [...collectionDraft.value] : undefined;
  const neighborCandidateId = (mode === 'apply' || updateNeighbors) && neighborsDirty.value ? neighborDraft.value : undefined;
  const siteId = props.siteId, token = ++revision; busy.value = true; error.value = ''; notice.value = '';
  try {
    const actionSummary = actions ? (actions.length ? ` 同时应用 ${actions.length} 个已核对的详情按钮预填。` : ' 同时清除本计划的详情按钮预填。') : '';
    const collectionSummary = collectionRegionIds ? (collectionRegionIds.length ? ` 同时应用 ${collectionRegionIds.length} 个集合区域。` : ' 同时清除本计划的集合绑定。') : '';
    const neighborSummary = neighborCandidateId === undefined ? '' : neighborCandidateId ? ' 同时应用已核对的详情导航区域。' : ' 同时清除本计划的详情导航绑定。';
    if (updateNeighbors) {
      const impact = neighborCandidateId ? '将接入选定的详情导航区域。' : '将移除本计划的详情导航绑定。';
      await ElMessageBox.confirm(`${impact}现有公开路径 ${neighborPreview.value?.publicUrl}。请在应用后核对实际页面。`, '确认修改详情导航', { type: 'warning' });
    } else {
      await ElMessageBox.confirm(withdraw ? '将恢复此前的模板和地址。确认后请从前台核验。' : switchRoute ? `将在 ${routePreview.value?.publicUrl} 应用预览模板。请核对预览和目标地址。` : `将为当前已发布单页或详情接入受控模板，保留原 UI 包和人工内容。${actionSummary}${collectionSummary}${neighborSummary}`, withdraw ? '确认撤回' : '确认公开应用', { type: 'warning' });
    }
    if (token !== revision) return;
    const value = withdraw
      ? await withdrawPublicBinding(siteId, result.value?.activePlanId ?? plan.id, plan.packageVersion)
      : await applyPublicBinding(siteId, plan.id, actions, collectionRegionIds, neighborCandidateId,
          changeDigest ? { routeUrl: switchRoute ? routeDraft.value.trim() : undefined, changeDigest } : undefined);
    if (token !== revision) return;
    result.value = value;
    if (!updateNeighbors) routeDraft.value = value.currentRoute ?? '';
    routePreview.value = null;
    neighborPreview.value = null;
    if (actions && actionEvidence.value) installActions({ ...actionEvidence.value, selectedActions: actions });
    if (collectionRegionIds && collectionEvidence.value) installCollections({ ...collectionEvidence.value, selectedCollectionRegionIds: collectionRegionIds });
    if (neighborCandidateId !== undefined && neighborEvidence.value)
      installNeighbors({ ...neighborEvidence.value, selectedCandidateId: neighborCandidateId || null });
    emit('changed');
    const reloaded = await reloadSiteRoutes(siteId);
    if (token === revision) notice.value = reloaded ? '路由已重新加载。请读取实际公开路径核对结果；服务返回成功不替代公开页面验收。' : '模板已保存，但路由未重载；请检查现有站点服务状态后重试，不会自动启动服务。';
  } catch (e) {
    if (token === revision && e !== 'cancel' && e !== 'close') error.value = e instanceof Error ? e.message : '操作未完成，请重新检查当前应用与路由';
  } finally { if (token === revision) busy.value = false; }
}
async function checkCollections() {
  const plan = props.plan;
  if (!plan || blocked.value || !collectionsReady.value) return;
  const token = ++revision;
  busy.value = true;
  collectionError.value = '';
  try {
    const value = await previewPublicBinding(props.siteId, plan.id, { collectionRegionIds: [...collectionDraft.value] });
    if (token === revision) collectionPreview.value = value;
  } catch (error) {
    if (token === revision) collectionError.value = error instanceof Error ? error.message : '重复区域试编译失败';
  } finally {
    if (token === revision) busy.value = false;
  }
}
async function reload() {
  if (blocked.value) return; const token = ++revision; busy.value = true; error.value = '';
  try { const ok = await reloadSiteRoutes(props.siteId); if (token === revision) notice.value = ok ? '路由已重新加载，请核对实际公开页面。' : '路由未重载，请检查已有站点服务状态。'; }
  catch (e) { if (token === revision) error.value = e instanceof Error ? e.message : '路由重载失败'; }
  finally { if (token === revision) busy.value = false; }
}
async function checkHome() {
  const plan = props.plan; if (!plan || plan.pageType !== 0 || blocked.value) return;
  const token = ++revision; busy.value = true; error.value = '';
  try { await readHomeState(props.siteId, plan.id, token); }
  finally { if (token === revision) busy.value = false; }
}
async function readHomeState(siteId: number, planId: string, token: number) {
  const [options, state] = await Promise.allSettled([inspectPublicHome(siteId, planId), inspectPublicHomeSwitch(siteId, planId)]);
  if (token !== revision) return;
  homeOptions.value = options.status === 'fulfilled' ? options.value : null;
  if (options.status === 'rejected') error.value = options.reason instanceof Error ? options.reason.message : '无法读取首页入口';
  homeSwitch.value = state.status === 'fulfilled' ? state.value : null;
  homeSwitchError.value = state.status === 'rejected'
    ? state.reason instanceof Error ? state.reason.message : '无法读取首页版本'
    : '';
}
async function homeAction(withdraw: boolean) {
  const plan = props.plan, state = homeOptions.value;
  if (!plan || plan.pageType !== 0 || !state || blocked.value || (withdraw ? !homeSwitch.value || homeSwitch.value.canRollback : !result.value?.applied)) return;
  const siteId = props.siteId, token = ++revision; busy.value = true; error.value = ''; notice.value = '';
  try {
    await ElMessageBox.confirm(withdraw ? '仅恢复本计划原根模板指针，保留来源单页和根字段；请逐一核对原入口。' : `将根路径 /、当前根路由与 ${state.availableAliases.join('、') || '无额外 index 入口'} 一起展示此已公开单页。原包文件保留，其他首页应用须先撤回。`, withdraw ? '确认撤回首页' : '确认首页来源与全部入口', { type: 'warning' });
    if (token !== revision) return;
    const value = withdraw ? await withdrawPublicHome(siteId, plan.id, plan.packageVersion) : await applyPublicHome(siteId, plan.id, state.availableAliases);
    if (token !== revision) return;
    homeResult.value = value;
    emit('changed');
    const reloaded = await reloadSiteRoutes(siteId);
    if (token !== revision) return;
    notice.value = reloaded ? '首页路由已重载，请逐一读取上列公开路径核对效果。' : '首页设置已保存，但路由未重载；请检查现有站点服务后重试。';
    await readHomeState(siteId, plan.id, token);
  } catch (e) { if (token === revision && e !== 'cancel' && e !== 'close') error.value = e instanceof Error ? e.message : '首页操作未完成，请核对当前状态'; }
  finally { if (token === revision) busy.value = false; }
}
async function changeHomeVersion(rollback: boolean) {
  const plan = props.plan, state = homeSwitch.value;
  if (!plan || plan.pageType !== 0 || !state || blocked.value || !(rollback ? state.canRollback : state.canSwitch)) return;
  const siteId = props.siteId, token = ++revision;
  const action = rollback ? '恢复上一版首页' : '切换到本计划';
  busy.value = true; error.value = ''; notice.value = ''; homeSwitchError.value = '';
  try {
    await ElMessageBox.confirm(
      `确认${action}？将影响 ${state.publicUrls.join('、')}。保存后请重载首页路由、核对访客页面，并重新生成静态页。`,
      action, { type: 'warning', confirmButtonText: action, cancelButtonText: '取消' },
    );
    if (token !== revision) return;
    const value = await switchPublicHome(siteId, plan.id, state, rollback);
    if (token !== revision) return;
    homeResult.value = value;
    notice.value = '首页版本已保存。请重载首页路由，逐一核对访客入口，再重新生成静态页。';
    emit('changed');
    await readHomeState(siteId, plan.id, token);
  } catch (e) {
    if (token !== revision || e === 'cancel' || e === 'close') return;
    homeSwitch.value = null;
    homeOptions.value = null;
    homeResult.value = null;
    homeSwitchError.value = `${e instanceof Error ? e.message : '首页操作未完成'}。请重新读取真实首页入口。`;
  } finally {
    if (token === revision) busy.value = false;
  }
}
</script>
<style scoped>
.detail-form-action { margin-block: 12px; padding: 12px; border: 1px solid var(--admin-border-subtle); }
.detail-form-fields { display: flex; flex-wrap: wrap; gap: 12px; margin-block: 8px 12px; }
.detail-form-fields label { display: grid; gap: 4px; flex: 1 1 180px; }
.detail-neighbor-evidence { overflow-wrap: anywhere; }
.home-application { overflow-wrap: anywhere; }
</style>
