<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useSiteContextStore } from '@/stores/siteContext';
import { ElMessageBox } from 'element-plus';
import QueryBindingPanel from '@/components/sites/QueryBindingPanel.vue';
import SharedListLayoutPanel from '@/components/sites/SharedListLayoutPanel.vue';
import PairedInitializationPanel from '@/components/sites/PairedInitializationPanel.vue';
import IndependentTimelinePanel from '@/components/sites/IndependentTimelinePanel.vue';
import type { MenuNode } from '@/api/uiPackage';
import type { FieldPlanSummary } from '@/api/templateFieldPlan';
import type { SiteModelPlan, SiteNavigationNode } from '@/api/siteModel';
import { listPairingContainers, inspectListPairing, savePairingReview, reviewedPairingDetails, type PairingCandidates, type PairingDecision, type PairingFact, type PairingInspection, type PairingItem, type PairingOwnership, type SharedDetailTemplateScope } from '@/api/templateListPairing';

const props = defineProps<{ siteId: number | null; sourcePath: string | null; sourcePaths: string[]; columns: MenuNode[]; disabled: boolean; preferredMenuId?: number | null; detailPlan?: {id:string;sourcePath:string;menuId:number} | null; navigationPlanId?: string; packageVersion?: string; reviewedPlan?: SiteModelPlan | null; reviewedNodes?: SiteNavigationNode[] }>();
const emit = defineEmits<{ 'configure-routes':[number]; 'public-list-changed':[]; 'open-list-seed':[string,number]; 'manage-content':[number]; 'open-source':[string,number]; 'review-detail':[string,number,SharedDetailTemplateScope?] }>();
const siteContext = useSiteContextStore();
const memoryNotice = ref('');
let menuMemoryScope = '';
const selectedCard = ref('');
const displayCard = ref('');
const decisions = ref<Record<string, PairingDecision>>({});
const reviewStatus = ref('');
const ownerships: Record<PairingOwnership, string> = {
  PLACEHOLDER: '重复占位', EXISTING_RECORD: '关联已有记录', OTHER_DETAIL: '另选独立详情', LIST_ONLY: '仅维护列表',
  SHARED_DETAIL: '复用共享详情正文',
};
const pairedBusy = ref(false);
const queryBusy = ref(false);
const menuId = ref<number | null>(null);
const listMode = ref<'paired' | 'independent' | null>(null);
const reuseLayout = ref(false);
const candidates = ref<PairingCandidates | null>(null);
const containerId = ref('');
const result = ref<PairingInspection | null>(null);
const sharedSamples = computed(() => {
  const groups = new Map<string, number>();
  const inspection = result.value;
  if (!inspection) return groups;
  for (const decision of inspection.review?.decisions ?? []) {
    if (decision.ownership !== 'SHARED_DETAIL') continue;
    const source = inspection.items.find(item => item.id === decision.cardId)?.detail?.sourcePath;
    if (source) groups.set(source, (groups.get(source) ?? 0) + 1);
  }
  return groups;
});
const reviewedDetails = ref<FieldPlanSummary[]>([]);
const reviewedDetailId = ref('');
const pairingRequest = computed(() => {
  const inspected = result.value;
  return inspected ? { menuId: inspected.menuId, listSourcePath: inspected.listSourcePath,
    listDigest: inspected.listDigest, containerId: inspected.container.id,
    detailPlanId: reviewedDetailId.value || undefined } : undefined;
});
const error = ref('');
const loading = ref(false);
let returnToReview: { scope: string; containerId: string } | undefined;
let generation = 0;
const menus = computed(() => props.columns.filter(menu => menu.pageType === 1 && menu.id));
const targetMenu = computed(() => menus.value.find(menu => Number(menu.id) === menuId.value));
const scopedProposal = computed(() => {
  const plan = props.reviewedPlan;
  return plan?.status === 'CONFIRMED' && plan.siteId === props.siteId && plan.packageVersion === props.packageVersion
    ? plan.proposal : null;
});
const sampleOwners = computed(() => {
  const proposal = scopedProposal.value;
  if (!proposal || !props.sourcePath) return [];
  const keys = new Set(proposal.regions.filter(region => region.sourcePath === props.sourcePath && region.mode === 'LIST'
    && !region.maintenance && region.records.length > 0).map(region => region.modelKey));
  return [...new Set(proposal.models.filter(model => model.menuId && keys.has(model.key)).map(model => model.menuId!))];
});
const ownListSample = computed(() => !!menuId.value && sampleOwners.value.includes(menuId.value));
const independentPlanId = computed(() => {
  const proposal = scopedProposal.value;
  if (!proposal || !menuId.value) return;
  const keys = new Set(proposal.models.filter(model => model.kind === 'LIST' && model.menuId === menuId.value).map(model => model.key));
  if (proposal.regions.some(region => region.mode === 'LIST' && region.sourcePath === props.sourcePath && keys.has(region.modelKey)))
    return props.reviewedPlan?.id;
});
const otherSample = computed(() => sampleOwners.value.length === 1 && sampleOwners.value[0] !== menuId.value
  ? sampleOwners.value[0] : undefined);
const hasDetailEvidence = computed(() => {
  const proposal = scopedProposal.value;
  return !!proposal?.regions.some(region => region.mode === 'DETAIL' && region.records.length > 0
    && proposal.models.some(model => model.key === region.modelKey && model.menuId === menuId.value));
});
const sourceOwnershipPending = computed(() => !!otherSample.value && props.detailPlan?.menuId !== menuId.value);
const canReuseLayout = computed(() => {
  return !!props.navigationPlanId && !!props.sourcePath && !!props.reviewedNodes?.some(node =>
    !node.drift && node.kind === 'LIST' && node.sourcePath === props.sourcePath && node.menuId === menuId.value);
});
const defaultMode = computed(() => props.detailPlan?.menuId === menuId.value ? 'paired'
  : (ownListSample.value || independentPlanId.value) && !hasDetailEvidence.value ? 'independent' : null);
function menuLabel(menu: MenuNode) {
  const parent = props.columns.find(item => item.id === menu.parentId);
  const parentLabel = parent?.className || parent?.name;
  return `${parentLabel ? parentLabel + ' / ' : ''}${menu.className || menu.name || '栏目'}（ID ${menu.id}）`;
}
const coverageWarnings = computed(() => [...new Set([...(candidates.value?.warnings || []), ...(result.value?.warnings || [])])]);
const pendingCount = computed(() => result.value?.items.filter(item => item.status === 'PENDING').length || 0);
const sharedPendingCount = computed(() => result.value?.items.filter(item =>
  item.status === 'SHARED_DETAIL' && !reviewedDecision(item.id)).length || 0);
const uncoveredCount = computed(() => result.value ? Math.max(0, result.value.totalCards - result.value.inspectedCards) : 0);
const sharedDetails = computed(() => {
  const groups = new Map<string, NonNullable<typeof result.value>['items']>();
  for (const item of result.value?.items ?? []) {
    if (!item.detail) continue;
    const key = item.detail.sourcePath.toLowerCase();
    const group = groups.get(key) ?? [];
    group.push(item);
    groups.set(key, group);
  }
  return [...groups.entries()].filter(([, items]) => items.length > 1);
});
const reviewGroups = computed(() => {
  const groups = [...sharedDetails.value];
  const groupedIds = new Set(groups.flatMap(([, items]) => items.map(item => item.id)));
  for (const item of result.value?.items ?? []) {
    if (!groupedIds.has(item.id)) groups.push([item.id, [item]]);
  }
  return groups;
});
const factLabels: Record<PairingFact['result'], string> = {
  MATCH: '吻合', DIFFERENT: '不一致', PRESENT: '有原文', UNAVAILABLE: '待核',
};
const ownRecordCandidates = (item: PairingItem) => item.recordCandidates?.filter(row => row.menuId === menuId.value) ?? [];
const reviewedDecision = (id: string) => result.value?.review?.decisions.find(value => value.cardId === id);
const canInitialize = (item: PairingItem) => {
  const decision = reviewedDecision(item.id);
  if (!decision) return item.status === 'MATCHED';
  return decision.ownership === 'OTHER_DETAIL' && !!decision.detailDigest
    || decision.ownership === 'SHARED_DETAIL' && item.status === 'SHARED_DETAIL';
};
const selectedItem = computed(() => result.value?.items.find(item => item.id === selectedCard.value));
const alternatePaths = computed(() => props.sourcePaths.filter(path => path !== props.sourcePath
  && !sharedDetails.value.some(([key]) => key === path.toLowerCase())));
const orderedDecisions = computed(() => result.value?.items.flatMap(card => decisions.value[card.id] ?? []) ?? []);
const reviewDirty = computed(() => JSON.stringify(orderedDecisions.value) !== JSON.stringify(result.value?.review?.decisions ?? []));
const reviewIncomplete = computed(() => orderedDecisions.value.some(value =>
  value.ownership === 'EXISTING_RECORD' && !value.recordId || value.ownership === 'OTHER_DETAIL' && !value.detailPath));

const targetActionsBlocked = computed(() => props.disabled || loading.value || pairedBusy.value || queryBusy.value || !props.siteId || !menuId.value);
const blocked = computed(() => targetActionsBlocked.value || !props.sourcePath);
watch(() => [siteContext.currentAdmin?.id, props.siteId, props.packageVersion, props.sourcePath, menuId.value], () => {
  generation++; selectedCard.value=''; displayCard.value=''; candidates.value = null; containerId.value = ''; result.value = null; error.value = ''; loading.value = false;
  pairedBusy.value = false; queryBusy.value = false;
  reuseLayout.value = false;
  reviewedDetails.value = [];
  reviewedDetailId.value = '';
});
watch(() => [siteContext.currentAdmin?.id, props.siteId, props.packageVersion, props.sourcePath, menuId.value], () => {
  listMode.value = defaultMode.value;
}, { immediate: true });
watch(defaultMode, (mode) => {
  if (!listMode.value || props.detailPlan?.menuId === menuId.value) listMode.value = mode;
});
function memoryKey(){const admin=siteContext.currentAdmin?.id;return admin&&props.siteId&&props.sourcePath?'cms.workbench.list-menu.v1:'+JSON.stringify([admin,props.siteId,props.sourcePath]):'';}
function availableMenu(id:number){return Number.isInteger(id)&&id>0&&menus.value.some(menu=>Number(menu.id)===id);}
function restoreMenu() {
  const key = memoryKey(), scope = JSON.stringify([key, props.packageVersion]);
  if (scope !== menuMemoryScope) {
    menuMemoryScope = scope;
    menuId.value = null;
    memoryNotice.value = '';
  }
  if (props.preferredMenuId) {
    menuId.value = availableMenu(props.preferredMenuId) ? props.preferredMenuId : null;
    memoryNotice.value = menuId.value ? '' : `目标栏目 #${props.preferredMenuId} 当前不在本站可选列表栏目中，请核对结构与栏目类型。`;
    return;
  }
  if (menuId.value && availableMenu(menuId.value)) return;
  menuId.value = null;
  if (!key) return;
  try {
    const saved = localStorage.getItem(key);
    if (!saved) return;
    const id = Number(saved);
    if (availableMenu(id)) {
      menuId.value = id;
      memoryNotice.value = '已恢复本页目标列表栏目，可按列表用途继续。';
    } else memoryNotice.value = '原记忆栏目当前不可用，请重新选择目标列表栏目。';
  } catch { memoryNotice.value = '浏览器记忆不可用，可手动选择目标列表栏目。'; }
}
watch(() => [siteContext.currentAdmin?.id, props.siteId, props.packageVersion, props.sourcePath, props.preferredMenuId, menus.value.map(menu => menu.id).join(',')], restoreMenu, { immediate: true });
watch(menuId,value=>{const key=memoryKey();if(!key||value==null||!availableMenu(value))return;try{localStorage.setItem(key,String(value));}catch{memoryNotice.value='浏览器记忆不可用，本次栏目选择仍可正常使用。';}});
function message(cause: unknown) { return cause instanceof Error ? cause.message : String(cause); }
watch(() => result.value?.review, review => {
  decisions.value = Object.fromEntries((review?.decisions ?? []).map(row => [row.cardId, { ...row }]));
  reviewStatus.value = review?.revision ? `已读取归属审核修订 ${review.revision}` : '';
});
function decide(cardId: string, ownership?: PairingOwnership) {
  if (ownership) decisions.value[cardId] = { cardId, ownership, recordId: undefined, detailPath: undefined };
  else delete decisions.value[cardId];
}
function sharedCandidates(items: PairingItem[]) {
  return items.filter(item => item.status === 'SHARED_DETAIL');
}
function decideSharedGroup(items: PairingItem[]) {
  for (const item of sharedCandidates(items)) decide(item.id, 'SHARED_DETAIL');
}
function cardLabel(card: PairingItem) {
  return card.comparisons?.find(fact => fact.name === '标题')?.list?.value
    || card.list.evidence.find(item => item.kind === 'TEXT')?.preview || card.selector;
}
async function saveReview() {
  const data = result.value;
  if (!props.siteId || !data?.review || blocked.value || !reviewDirty.value || reviewIncomplete.value) return;
  const token = generation;
  const pairing = pairingRequest.value;
  if (!pairing) return;
  loading.value = true;
  error.value = '';
  try {
    const review = await savePairingReview(props.siteId, pairing, { ...data.review, decisions: orderedDecisions.value });
    if (token === generation) result.value = { ...data, review };
  } catch (cause) { if (token === generation) error.value = message(cause); }
  finally { if (token === generation) loading.value = false; }
}
async function load() {
  if (blocked.value || !props.siteId || !props.sourcePath || !menuId.value) return;
  const version = ++generation;
  loading.value = true; error.value = ''; result.value = null; candidates.value = null; containerId.value = '';
  reviewedDetails.value = [];
  reviewedDetailId.value = '';
  try {
    const data = await listPairingContainers(props.siteId, menuId.value, props.sourcePath);
    if (version === generation) candidates.value = data;
  } catch (cause) { if (version === generation) error.value = message(cause); }
  finally { if (version === generation) loading.value = false; }
}
async function inspect(confirmRange = true) {
  if (blocked.value || !props.siteId || !menuId.value || !candidates.value || !containerId.value) return;
  const version = generation;
  if (confirmRange) {
    try { await ElMessageBox.confirm('请确认所选容器是业务列表，包含正确的卡片范围。本次仅核对静态链接与来源证据，不保存字段或创建内容。', '确认列表卡片范围', { type: 'info' }); }
    catch { return; }
  }
  if (version !== generation || blocked.value) return;
  loading.value = true; error.value = ''; result.value = null;
  try {
    const data = await inspectListPairing(props.siteId, menuId.value, candidates.value,
      containerId.value, reviewedDetailId.value || undefined);
    if (version !== generation) return;
    result.value = data;
    if (!reviewedDetailId.value && pairingRequest.value) {
      const details = await reviewedPairingDetails(props.siteId, pairingRequest.value);
      if (version === generation) reviewedDetails.value = details;
    }
  } catch (cause) { if (version === generation) error.value = message(cause); }
  finally { if (version === generation) loading.value = false; }
}
const reviewScope = computed(() => JSON.stringify([memoryKey(), props.packageVersion, menuId.value]));
function openAlternateDetail(path: string, shared?: PairingItem[]) {
  if (!menuId.value || !result.value || reviewDirty.value) return;
  returnToReview = { scope: reviewScope.value, containerId: result.value.container.id };
  const pairing = pairingRequest.value;
  const cardIds = shared?.filter(item => item.status === 'SHARED_DETAIL' &&
    reviewedDecision(item.id)?.ownership === 'SHARED_DETAIL').map(item => item.id);
  emit('review-detail', path, menuId.value, pairing && cardIds?.length ? { pairing, cardIds } : undefined);
}
watch([reviewScope, blocked], async () => {
  if (blocked.value || result.value || !returnToReview || returnToReview.scope !== reviewScope.value) return;
  const saved = returnToReview;
  returnToReview = undefined;
  listMode.value = 'paired';
  await load();
  if (saved.scope !== reviewScope.value || !candidates.value?.containers.some(item => item.id === saved.containerId)) return;
  containerId.value = saved.containerId;
  await inspect(false);
}, { flush: 'post' });
</script>

<template>
  <el-card shadow="never" class="pairing-panel">
    <template #header><strong>列表与查询</strong></template>
    <div class="controls">
      <el-select v-model="menuId" aria-label="配对目标列表栏目" placeholder="选择目标列表栏目" :disabled="disabled || loading || pairedBusy || queryBusy">
        <el-option v-for="menu in menus" :key="menu.id" :value="Number(menu.id)" :label="menuLabel(menu)" />
      </el-select>
      <span>来源：{{ sourcePath || '请先在上方选页' }}</span>
    </div>
    <p v-if="targetMenu">列表路由 {{ targetMenu.listPageUrl || '待配置' }} · 详情路由 {{ targetMenu.detailPageUrl || '待配置' }} · 前台{{ targetMenu.hasFrontChannel ? '已开放' : '未开放' }}。<el-button v-if="menuId" link type="primary" :disabled="targetActionsBlocked" @click="emit('configure-routes',menuId)">在工作台配置路由</el-button></p>
    <p v-if="memoryNotice" role="status">{{ memoryNotice }}</p>
    <p v-if="targetMenu && !sourcePath" role="status">
      当前栏目缺少已定位的列表原页。请先选定对应原页，也可直接
      <el-button link type="primary" :disabled="targetActionsBlocked" @click="emit('manage-content', Number(targetMenu.id))">打开此栏目的内容管理</el-button>。
    </p>
    <section v-if="targetMenu && sourcePath" class="list-path" aria-label="当前列表的处理方式">
      <p v-if="otherSample" role="status">当前确认方案将这份原页的重复样本归到 #{{ otherSample }}。请先核对本栏目的真实记录及来源，再推进配对或初始化。</p>
      <p v-else-if="ownListSample && !hasDetailEvidence" role="status">此栏目已有审核的重复区域，现有计划尚无该栏目的详情证据。可以先核对列表记录和初值。</p>
      <el-button link type="primary" :disabled="blocked" @click="emit('open-source', sourcePath, Number(targetMenu.id))">核对本栏目的列表原页</el-button>
      <el-button v-if="sourceOwnershipPending" :disabled="blocked" @click="emit('manage-content', Number(targetMenu.id))">核对此栏目的实际记录</el-button>
      <el-radio-group v-else v-model="listMode" aria-label="列表处理方式" :disabled="blocked">
        <el-radio-button value="paired">有详情链接与分页</el-radio-button>
        <el-radio-button value="independent">独立列表（无详情或无分页）</el-radio-button>
      </el-radio-group>
      <el-button v-if="canReuseLayout && !reuseLayout" :disabled="blocked" @click="reuseLayout=true">复用已确认的列表版式</el-button>
      <el-button v-if="reuseLayout" :disabled="blocked" @click="reuseLayout=false">返回本栏目审核</el-button>
      <p v-if="reuseLayout" role="status">当前栏目选用同一原页版式，公开列表读取本栏目独立维护的记录。</p>
      <p v-else-if="sourceOwnershipPending" role="status">审核本栏目对应的原页或实际记录后，再回到此处配置列表。</p>
      <p v-else-if="!listMode" role="status">根据原页选择列表用途。有详情与分页可核对卡片配对；独立列表可直接核对栏目内容和初值。</p>
      <template v-else-if="listMode === 'independent'">
        <p role="status">先核对栏目记录和初值。原页包含分组导航与事件时，可配置独立分组列表。</p>
        <div class="controls">
          <el-button v-if="!otherSample" type="primary" :disabled="blocked" @click="emit('open-list-seed', sourcePath, Number(targetMenu.id))">核对重复区域与初值</el-button>
          <el-button :disabled="blocked" @click="emit('manage-content', Number(targetMenu.id))">打开此栏目的内容管理</el-button>
        </div>
        <IndependentTimelinePanel v-if="siteId && menuId && packageVersion"
          :key="JSON.stringify([siteContext.currentAdmin?.id, siteId, menuId, sourcePath, packageVersion, reviewedPlan?.id, reviewedPlan?.revision, targetMenu.listPageUrl])"
          :site-id="siteId" :menu-id="menuId" :source-path="sourcePath" :package-version="packageVersion"
          :source-plan-id="independentPlanId" :has-list-route="!!targetMenu.listPageUrl?.trim() && !targetMenu.listPageUrl.includes('{pageIndex}')"
          :disabled="disabled || loading || pairedBusy" @busy="queryBusy=$event"
          @fields="emit('open-list-seed', sourcePath, Number(targetMenu.id))"
          @routes="emit('configure-routes', Number(targetMenu.id))" @changed="emit('public-list-changed')" />
      </template>
      <p v-else>先检查卡片容器和站内详情链接，再按已确认的详情计划核对动态卡片及分页。配对初始化用于生成私有详情草稿。</p>
    </section>
    <SharedListLayoutPanel v-if="reuseLayout && canReuseLayout && siteId && menuId && sourcePath && navigationPlanId"
      :key="`${siteId}:${menuId}:${packageVersion}:${sourcePath}`" :site-id="siteId" :menu-id="menuId"
      :source-path="sourcePath" :navigation-plan-id="navigationPlanId" :disabled="disabled"
      @busy="queryBusy=$event" @changed="emit('public-list-changed')" />
    <template v-if="!reuseLayout && listMode === 'paired' && !sourceOwnershipPending">
    <el-button :disabled="blocked" :loading="loading" @click="load">检查候选卡片容器</el-button>
    <el-alert v-if="error" type="error" :title="error" :closable="false" />
    <template v-if="candidates">
      <p v-if="!candidates.containers.length">本次有界扫描未找到重复卡片容器，不能据此认定全页没有业务列表。</p>
      <div class="controls">
        <el-select v-model="containerId" aria-label="确认卡片容器" placeholder="根据位置、数量和示例选择业务卡片" :disabled="disabled || loading || pairedBusy || queryBusy" class="container-select" @change="result = null; selectedCard = ''; displayCard = ''">
          <el-option v-for="container in candidates.containers" :key="container.id" :value="container.id" :label="`${container.selector} > ${container.cardTag}${container.cardClass ? '.' + container.cardClass : ''} · ${container.count}张 · ${container.example}`" />
        </el-select>
        <el-button type="primary" :disabled="blocked || !containerId" @click="inspect()">确认范围并检查链接</el-button>
      </div>
    </template>
    <p v-if="candidates || result">仅核对有界静态来源，未证明全页业务语义；范围不足与歧义请展开查看。</p>
    <el-collapse v-if="coverageWarnings.length">
      <el-collapse-item :title="`识别范围与待处理说明（${coverageWarnings.length}）`" name="coverage">
        <p v-for="warning in coverageWarnings" :key="warning" class="warning">{{ warning }}</p>
        <p v-if="result">包版本 {{ result.packageVersion }} · 字段配置摘要 {{ result.fieldConfigDigest.slice(0, 12) }}</p>
      </el-collapse-item>
    </el-collapse>
    <template v-if="result">
      <div v-if="reviewedDetails.length" class="controls">
        <el-select v-model="reviewedDetailId" clearable :disabled="blocked || reviewDirty"
          aria-label="已确认详情证据" placeholder="可选：使用本栏目已确认详情" @change="inspect(false)">
          <el-option v-for="choice in reviewedDetails" :key="choice.id" :value="choice.id"
            :label="`${choice.sourcePath} · ${choice.id.slice(0, 8)}`" />
        </el-select>
        <span>选择详情后，卡片的样品申请等其他链接仍显示为原动作。</span>
      </div>
      <p>本次检查 {{ result.inspectedCards }}/{{ result.totalCards }} 张，待处理 {{ pendingCount }} 张，未检查 {{ uncoveredCount }} 张。本次只读检查未写入内容。</p>
      <p v-if="sharedPendingCount" role="status">有 {{ sharedPendingCount }} 张卡片可复用详情正文，保存人工决定后可继续双来源初始化。</p>
      <section v-if="reviewGroups.length" aria-label="审核列表卡片归属">
        <h4>列表卡片归属</h4>
        <p>逐张确认业务归属。审核回执用于后续配对，保存时只写归属决定。</p>
        <div v-for="[path, items] in reviewGroups" :key="path" class="list-path">
          <strong>{{ items[0]?.detail?.sourcePath || '没有可确认的原详情' }} · {{ items.length }} 张卡片</strong>
          <p v-if="sharedCandidates(items).length">共享来源只提供正文。各卡片的标题、日期、图片、分类等字段从各自列表取值，分别建立记录。</p>
          <div v-if="items.length > 1" class="controls">
            <el-button v-if="sharedCandidates(items).length > 1" :disabled="blocked" @click="decideSharedGroup(items)">本组合格的 {{ sharedCandidates(items).length }} 张复用共享正文</el-button>
            <el-button :disabled="blocked" @click="items.forEach(item => decide(item.id, 'PLACEHOLDER'))">整组设为重复占位</el-button>
            <el-button :disabled="blocked" @click="items.forEach(item => decide(item.id, 'LIST_ONLY'))">整组设为仅维护列表</el-button>
            <el-button :disabled="blocked || !menuId" @click="menuId && emit('manage-content', menuId)">核对本栏目已有记录</el-button>
          </div>
          <el-button v-if="items[0]?.detail && items.some(item => reviewedDecision(item.id)?.ownership === 'SHARED_DETAIL')"
            link type="primary" :disabled="blocked || reviewDirty" @click="openAlternateDetail(items[0].detail.sourcePath, items)">审核共享详情正文</el-button>
          <el-table :data="items" row-key="id" size="small">
            <el-table-column label="原卡片" min-width="190"><template #default="{ row, $index }">
              {{ $index + 1 }} · {{ cardLabel(row) }}
            </template></el-table-column>
            <el-table-column label="审核决定" min-width="190"><template #default="{ row, $index }">
              <el-select :model-value="decisions[row.id]?.ownership" :aria-label="`第 ${$index + 1} 张卡片归属`"
                :disabled="blocked" clearable placeholder="待核对" @change="decide(row.id, $event || undefined)">
                <template v-for="(label, value) in ownerships" :key="value">
                  <el-option v-if="value !== 'SHARED_DETAIL' || row.status === 'SHARED_DETAIL'" :value="value" :label="label" />
                </template>
              </el-select>
            </template></el-table-column>
            <el-table-column label="处理目标" min-width="245"><template #default="{ row, $index }">
              <template v-if="decisions[row.id]?.ownership === 'EXISTING_RECORD'">
                <el-select v-if="ownRecordCandidates(row).length"
                  v-model="decisions[row.id]!.recordId" placeholder="选择本栏目已有记录" :disabled="blocked">
                  <el-option v-for="candidate in ownRecordCandidates(row)"
                    :key="candidate.recordId" :value="candidate.recordId" :label="`${candidate.title} #${candidate.recordId}`" />
                </el-select>
                <el-input-number v-else v-model="decisions[row.id]!.recordId"
                  :min="1" :max="2147483647" :precision="0" :disabled="blocked" :aria-label="`第 ${$index + 1} 张卡片的已有记录编号`" />
              </template>
              <template v-else-if="decisions[row.id]?.ownership === 'OTHER_DETAIL'">
                <el-select v-model="decisions[row.id]!.detailPath" filterable :disabled="blocked" :aria-label="`第 ${$index + 1} 张卡片的独立详情来源`" placeholder="选择独立详情页">
                  <el-option v-for="path in alternatePaths" :key="path" :value="path" :label="path" />
                </el-select>
                <el-button link type="primary" :disabled="blocked || reviewDirty || !decisions[row.id]?.detailPath"
                  @click="openAlternateDetail(decisions[row.id]!.detailPath!)">审核详情字段</el-button>
              </template>
              <span v-else-if="decisions[row.id]?.ownership === 'SHARED_DETAIL'">共享正文 · {{ row.detail?.sourcePath }}</span>
            </template></el-table-column>
          </el-table>
        </div>
        <el-button type="primary" :disabled="blocked || !result.review || !reviewDirty || reviewIncomplete" @click="saveReview">保存本批归属审核</el-button>
        <el-button :disabled="blocked || reviewDirty" @click="listMode = 'independent'">继续维护列表</el-button>
        <span v-if="reviewStatus" role="status">{{ reviewStatus }} · 已审 {{ result.review?.decisions.length }} 张</span>
        <p v-if="reviewDirty" role="status">{{ reviewIncomplete ? '请为选定的归属填写记录编号或独立详情。' : '有未保存的归属决定。' }}</p>
      </section>
      <p v-if="result.items.length && result.items.every(item => item.status === 'PENDING')" role="status">
        所选范围内未确认唯一详情页。如原页属于独立列表，<el-button link type="primary" :disabled="blocked" @click="listMode = 'independent'">改用独立列表路径</el-button>。
      </p>
      <p v-for="[path, count] in sharedSamples" :key="path" class="sample-summary">
        已审归属：{{ count }} 张卡片分别维护，复用 1 份详情样文（{{ path }}）。各条标题、日期及封面取自自身卡片；样文可独立替换。实际写入以初始化回执为准。
      </p>
      <el-table :data="result.items" row-key="id">
        <el-table-column type="expand">
          <template #default="{ row }">
            <section v-if="row.comparisons?.length" class="evidence" aria-label="列表和详情证据对照">
              <article v-for="fact in row.comparisons" :key="fact.name" class="field-diff">
                <strong>{{ fact.name }} · {{ factLabels[fact.result as PairingFact['result']] }}</strong>
                <div class="diff-sides">
                  <div v-for="side in [{label:'列表', value:fact.list, path:row.list.sourcePath},{label:'详情', value:fact.detail, path:row.detail?.sourcePath}]" :key="side.label">
                    <small>{{ side.label }} · {{ side.path || '来源待核' }}</small>
                    <p>{{ side.value?.value?.slice(0,120) || '缺失' }}</p>
                    <details v-if="side.value?.value?.length > 120"><summary>展开完整对照值</summary>{{ side.value.value }}</details>
                    <details v-if="side.value?.selector"><summary>查看定位依据</summary><code>{{ side.value.selector }}</code></details>
                  </div>
                </div>
              </article>
            </section>
            <section v-if="row.recordCandidates?.length" class="evidence" aria-label="已存在的同题记录">
              <strong>同题候选记录</strong>
              <p v-for="candidate in row.recordCandidates" :key="candidate.recordId">
                栏目 #{{ candidate.menuId }} · 记录 #{{ candidate.recordId }} · {{ candidate.title }} · {{ candidate.date || '日期待核' }} ·
                {{ candidate.status === 1 ? '已发布' : '未发布' }} · {{ candidate.bodyExcerptMatched ? '正文开头片段吻合' : '正文证据待核' }}。
                {{ candidate.menuId !== menuId ? '其他栏目候选，不能关联。' : '' }}
              </p>
            </section>
            <details v-for="source in [row.list, row.detail].filter(Boolean)" :key="source.sourcePath" class="evidence">
              <summary>原页证据 · {{ source.sourcePath }}</summary><code>{{ source.digest }}</code>
              <p v-if="!source.evidenceComplete" class="warning">此处来源条目仅部分展示，不能视为完整内容覆盖。</p>
              <p v-for="item in source.evidence" :key="item.sourceId"><code>{{ item.sourceId }} · {{ item.kind }} · {{ item.selector }}</code> · 原值{{ item.originalLength }}字符<br />{{ item.preview }}{{ item.originalLength > item.preview.length ? '（截取预览）' : '' }}</p>
            </details>
          </template>
        </el-table-column>
        <el-table-column label="列表卡片" min-width="170"><template #default="{row,$index}">第 {{ $index+1 }} 张 · {{ row.comparisons?.find((fact: PairingFact) => fact.name === '标题')?.list?.value || '标题待核' }}</template></el-table-column>
        <el-table-column label="原链接 → 包内详情" min-width="210"><template #default="{ row }"><p v-for="href in row.hrefs" :key="href">{{ href }}</p><strong>{{ row.detail?.sourcePath || '尚未确认' }}</strong></template></el-table-column>
        <el-table-column label="结果" min-width="240"><template #default="{ row }"><el-tag class="pairing-status" effect="plain" :type="row.status === 'MATCHED' ? 'success' : 'warning'">{{ row.status === 'MATCHED' ? '静态链接唯一' : row.status === 'SHARED_DETAIL' ? '可复用的共享详情' : '待处理' }}</el-tag><p>{{ row.reason }}</p></template></el-table-column>
        <el-table-column label="用途" min-width="240"><template #default="{ row }"><el-button :type="displayCard===row.id?'primary':undefined" :disabled="disabled || loading || pairedBusy || queryBusy" @click="displayCard=row.id">选为展示原型</el-button><el-button v-if="!reuseLayout && (detailPlan?.menuId!==menuId || row.status==='SHARED_DETAIL')" link :disabled="disabled || loading || pairedBusy || queryBusy || !canInitialize(row) || reviewDirty" @click="selectedCard=row.id; displayCard=row.id">配对初始化</el-button><p v-if="reviewedDecision(row.id)?.ownership==='EXISTING_RECORD'">已关联本栏目记录 #{{ reviewedDecision(row.id)?.recordId }}</p></template></el-table-column>
      </el-table>
      <PairedInitializationPanel v-if="selectedItem && canInitialize(selectedItem) && siteId && menuId" :key="`${siteId}:${menuId}:${result.listDigest}:${result.review?.revision}`" :site-id="siteId" :menu-id="menuId" :inspection="result" :card-id="selectedCard" :reviewed-detail-plan-id="reviewedDetailId || undefined" :disabled="disabled || loading || queryBusy || reviewDirty" @busy="pairedBusy=$event" />
    </template>
    <QueryBindingPanel v-if="siteId && menuId && sourcePath" :key="`query:${siteId}:${menuId}:${sourcePath}`" :site-id="siteId" :menu-id="menuId" :source-path="sourcePath" :pairing="pairingRequest" :card-id="displayCard || undefined" :detail-path="detailPlan?.menuId===menuId ? detailPlan.sourcePath : result?.items.find(item=>item.id===displayCard)?.detail?.sourcePath || null" :preferred-plan-id="reviewedDetailId || (detailPlan?.menuId===menuId ? detailPlan.id : undefined)" :navigation-plan-id="navigationPlanId" :disabled="disabled || loading || pairedBusy" @busy="queryBusy=$event" @public-list-changed="emit('public-list-changed')" />
    </template>
  </el-card>
</template>

<style scoped>
.controls { display: flex; flex-wrap: wrap; align-items: center; gap: 12px; margin-bottom: 12px; }
.controls .el-select { width: 300px; }
.controls .container-select { width: min(700px, 100%); }
.list-path { margin: 16px 0; }
.pairing-status { color: var(--el-text-color-primary); --el-tag-text-color: var(--el-text-color-primary); font-weight: 600; font-size: 14px; line-height: 20px; height: auto; min-height: 28px; padding: 3px 8px; }
.warning { color: var(--el-color-warning-dark-2); overflow-wrap: anywhere; }
.evidence { padding: 12px 24px; white-space: pre-wrap; overflow-wrap: anywhere; }
.pairing-panel :deep(.el-select__wrapper:not(.is-disabled) .el-select__placeholder) { color: var(--el-text-color-regular); }
.pairing-panel :deep(.cell) { overflow-wrap: anywhere; }
.field-diff { margin-block: 1em; }
.diff-sides { display: grid; grid-template-columns: repeat(auto-fit,minmax(min(100%,16rem),1fr)); gap: 12px; }
.diff-sides p { margin: 4px 0; }
.evidence summary { cursor: pointer; }
</style>
