<template>
  <section id="workbench-navigation-landing" tabindex="-1" class="landing-panel" aria-labelledby="landing-heading" v-loading="reading">
    <h4 id="landing-heading">栏目概览审核</h4>
    <p>核对父栏目原页中的完整卡片列表与实际子栏目。原图和摘要保留，尚无有效公开地址的卡片将禁用跳转。</p>
    <p>当前父栏目 {{ node.name }} · #{{ node.menuId }}，来源 {{ node.sourcePath }}</p>
    <p>概览关联的导航计划 {{ applicationPlan.id }}<template v-if="preview"> · 修订 {{ preview.planRevision }}</template></p>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-alert v-if="notice" :title="notice" type="info" :closable="false" />
    <el-button :disabled="locked" @click="discover">刷新卡片候选</el-button>
    <el-button v-if="preview" :disabled="locked" @click="read">刷新目标与锚点状态</el-button>
    <el-form-item v-if="candidates.length > 1" label="请选择要审核的原页卡片区域">
      <el-select v-model="containerSelector" :disabled="locked" placeholder="存在多个完整区域，请明确选择" @change="read">
        <el-option v-for="candidate in candidates" :key="candidate.selector" :value="candidate.selector" :label="candidate.labels.join('、')" />
      </el-select>
    </el-form-item>
    <section v-if="searched && !reading && !error && !candidates.length && !preview" class="landing-missing" aria-label="概览原页待审核">
      <p role="status">当前原页没有找到与已审直接子栏目一一对应的完整卡片组。先确认父入口要展示子栏目概览，还是当前原页中的内容列表。</p>
      <p v-if="reviewedListOwners.length">确认计划已将同页重复区域用于 {{ reviewedListOwners.map(owner => owner.name + ' · #' + owner.menuId).join('、') }}。可核对这些区域的实际用途与父入口职责。</p>
      <el-button v-if="node.sourcePath && node.menuId" type="primary" :disabled="locked" @click="emit('open-source', node.sourcePath, node.menuId)">核对频道原页</el-button>
      <el-button :disabled="locked" @click="emit('review-structure', node)">核对子栏目与结构</el-button>
    </section>
    <details>
      <summary>高级定位修正</summary>
      <p>需要修正识别区域时，可输入原页唯一卡片列表的定位并重新审核。</p>
      <el-form label-position="top" @submit.prevent="read">
        <el-form-item label="原页完整卡片列表的 CSS 定位">
          <el-input v-model="containerSelector" :disabled="locked" maxlength="200" placeholder="输入原页中唯一列表的定位" @input="invalidate" />
        </el-form-item>
        <el-button :disabled="locked || !containerSelector.trim()" @click="read">核对修正区域</el-button>
      </el-form>
    </details>
    <p v-if="column">父栏目当前入口 <code>{{ column.listPageUrl || '未配置' }}</code></p>
    <p v-if="preview && column && (!column.hasFrontChannel || !column.listPageUrl?.endsWith('.html'))">
      请在下方「栏目路由与内容发布」为父栏目配置固定 .html 路由并开放前台，再重新读取概览状态。
    </p>
    <p v-if="application" role="status">{{ application.message }}</p>
    <template v-if="preview">
      <p>共 {{ preview.cards.length }} 张原卡片。来源与定位经服务端核对，请逐条审核以下内容。</p>
      <p v-if="pendingCount" role="status">{{ pendingCount }} 个必需目标待处理。可应用概览供预览，待办保留到目标就绪或改为经审核的纯展示。</p>
      <el-alert v-if="hasRepeatedSummary" title="多张原卡片的简介文字重复，请核对其适用范围。当前展示原页原文，尚需审核各类简介的准确性。" type="warning" :closable="false" />
      <el-table :data="preview.cards" row-key="nodeKey" border>
        <el-table-column label="原卡片内容" min-width="260">
          <template #default="{ row }">
            <strong :id="`landing-card-${row.nodeKey}`" tabindex="-1" :ref="el => focusReturnedCard(el, row.nodeKey)">{{ row.label }}</strong>
            <p>{{ row.summary }}</p>
            <p>图片来源 <code>{{ row.imageSource }}</code></p>
            <p>原链接 <code>{{ row.rawHref }}</code></p>
            <details><summary>查看原文定位</summary><code>{{ row.selector }}</code></details>
          </template>
        </el-table-column>
        <el-table-column label="真实目的地" min-width="240">
          <template #default="{ row }">
            <p>{{ row.label }} · #{{ row.menuId }}</p>
            <p>{{ nodes.find(node => node.key === row.nodeKey)?.sourcePath || '待对应原页' }}</p>
            <el-tag :type="displayOnly.includes(row.nodeKey) ? 'info' : row.state === 'READY' ? 'success' : 'warning'">
              {{ displayOnly.includes(row.nodeKey) ? '纯展示' : cardStates[row.state as NavigationLandingCardState] }}
            </el-tag>
            <template v-if="!displayOnly.includes(row.nodeKey)">
              <p v-if="row.state === 'READY' && row.publicUrl"><code>{{ row.publicUrl }}</code></p>
              <template v-else>
                <p>{{ nextSteps[row.state as NavigationLandingCardState] }}</p>
                <el-button link type="primary" :disabled="locked" @click="openTarget(row.nodeKey)">处理目标栏目</el-button>
              </template>
            </template>
            <el-checkbox :model-value="displayOnly.includes(row.nodeKey)" :disabled="locked"
              @change="setDisplay(row.nodeKey, $event === true)">审核为纯展示</el-checkbox>
          </template>
        </el-table-column>
      </el-table>
      <p><el-checkbox v-model="reviewed" :disabled="locked">我已核对全部 {{ preview.cards.length }} 张卡片的栏目身份、可用目的地与纯展示决定。</el-checkbox></p>
      <el-button v-if="application" type="primary" :loading="writing" :disabled="locked || !canApply" @click="write(false)">{{ application.applied ? '确认并更新目标链接' : '审核并应用栏目概览' }}</el-button>
    </template>
    <el-button v-if="application?.applied" :loading="writing" :disabled="locked" @click="write(true)">撤回本概览模板</el-button>
    <template v-if="application?.applied">
      <p>概览已应用，可在站点服务核对 <code>{{ application.publicUrl }}</code>。核对后进入「静态发布」生成页面。</p>
      <p>目标栏目配置变化后，刷新目标与锚点状态，再审核更新当前概览。</p>
    </template>
  </section>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { ElMessageBox } from 'element-plus';
import { http } from '@/api/http';
import { reloadSiteRoutes } from '@/api/templateRecognition';
import { siteModelApi, type NavigationLandingCardState, type NavigationLandingPreview,
  type NavigationLandingApplication, type NavigationLandingCandidate, type SiteModelPlan, type SiteNavigationNode } from '@/api/siteModel';

const props = defineProps<{ siteId: number; plan: SiteModelPlan; node: SiteNavigationNode; nodes: SiteNavigationNode[]; navigationRevision: number; disabled?: boolean; focusCard?: string; originPlanId?: string | null }>();
const emit = defineEmits<{ busy: [active: boolean]; changed: []; 'open-source': [path: string, menuId: number]; 'review-structure': [node: SiteNavigationNode]; prerequisite: [node: SiteNavigationNode, card: string] }>();
const containerSelector = ref('');
const preview = ref<NavigationLandingPreview | null>(null);
const candidates = ref<NavigationLandingCandidate[]>([]);
const searched = ref(false);
const reviewed = ref(false);
const displayOnly = ref<string[]>([]);
const reading = ref(false);
const writing = ref(false);
const error = ref('');
const notice = ref('');
const application = ref<NavigationLandingApplication | null>(null);
const column = ref<{ id: number; siteProjectId: number; hasFrontChannel: boolean; listPageUrl: string | null; listPageTemplate: string | null } | null>(null);
const locked = computed(() => props.disabled || reading.value || writing.value);
let focusedCard: string | undefined;
watch(() => props.focusCard, () => { focusedCard = undefined; }, {flush:'sync'});
function focusReturnedCard(element: unknown, key: string) {
  if (props.focusCard !== key || !(element instanceof HTMLElement) || focusedCard === key) return;
  requestAnimationFrame(() => {
    if (element.isConnected && props.focusCard === key && focusedCard !== key) {
      focusedCard = key;
      element.focus({preventScroll:true});
      element.scrollIntoView({block:'center'});
    }
  });
}
const applicationPlan = computed(() => props.originPlanId ? { ...props.plan, id: props.originPlanId } : props.plan);
const pendingCount = computed(() => preview.value?.cards.filter(card => card.state !== 'READY' && !displayOnly.value.includes(card.nodeKey)).length ?? 0);
const canApply = computed(() => reviewed.value && !!preview.value?.cards.length && !!column.value?.hasFrontChannel &&
  !!column.value.listPageUrl?.startsWith('/') && column.value.listPageUrl.endsWith('.html'));
const hasRepeatedSummary = computed(() => {
  const summaries = preview.value?.cards.map(card => card.summary.trim()).filter(Boolean) || [];
  return new Set(summaries).size < summaries.length;
});
const reviewedListOwners = computed(() => {
  if (props.plan.status !== 'CONFIRMED') return [];
  const { models, regions } = props.plan.proposal;
  const keys = new Set(regions.filter(region => region.sourcePath === props.node.sourcePath &&
    region.mode === 'LIST' && !region.maintenance && region.records.length > 0).map(region => region.modelKey));
  return models.filter(model => model.menuId && keys.has(model.key));
});
const cardStates: Record<NavigationLandingCardState, string> = {
  READY: '可链接', FRONT_DISABLED: '目标前台未开放', ROUTE_MISSING: '目标路由未配置',
  TEMPLATE_MISSING: '目标模板未配置', ROUTE_CONFLICT: '目标路由冲突',
  INDEPENDENT_SOURCE_REQUIRED: '缺少已审独立页面证据',
  QUERY_REQUIRED: '列表查询待核对',
  ANCHOR_MISSING: '目标锚点缺失或不稳定', DISPLAY_ONLY: '纯展示',
};
const nextSteps: Record<NavigationLandingCardState, string> = {
  READY: '请核对实际地址。', FRONT_DISABLED: '卡片禁用跳转，可在目标栏目完成前台开放后重新审核。',
  ROUTE_MISSING: '卡片禁用跳转，可在目标栏目配置正式路由后重新审核。',
  TEMPLATE_MISSING: '卡片禁用跳转，可在目标栏目应用正式模板后重新审核。',
  ROUTE_CONFLICT: '卡片禁用跳转，请先处理目标栏目的路由冲突，再重新审核。',
  INDEPENDENT_SOURCE_REQUIRED: '卡片禁用跳转，请先审核此子栏目的独立页面来源，再重新核对目的地。',
  QUERY_REQUIRED: '进入目标栏目，在「列表与查询」核对并应用完整列表，再回来刷新。',
  ANCHOR_MISSING: '请在目标模板设置唯一、固定显示的锚点，再回来刷新。', DISPLAY_ONLY: '经审核保留卡片内容。',
};
function setDisplay(key: string, selected: boolean) {
  reviewed.value = false;
  displayOnly.value = selected ? [...new Set([...displayOnly.value, key])] : displayOnly.value.filter(item => item !== key);
}
function openTarget(key: string) {
  const target = props.nodes.find(item => item.key === key);
  if (target) emit('prerequisite', target, key);
}
let generation = 0;
async function discover() {
  if (locked.value || !props.node.sourcePath) return;
  const token = ++generation;
  reading.value = true;
  preview.value = null;
  application.value = null;
  column.value = null;
  candidates.value = [];
  searched.value = false;
  containerSelector.value = '';
  reviewed.value = false;
  error.value = '';
  try {
    const rows = await siteModelApi.navigationLandingCandidates(props.siteId, applicationPlan.value,
      { sourcePath: props.node.sourcePath, nodeKey: props.node.key });
    if (token !== generation) return;
    candidates.value = rows;
    searched.value = true;
    if (rows.length === 1) {
      containerSelector.value = rows[0]!.selector;
      reading.value = false;
      await read();
    }
  } catch (cause) {
    if (token === generation) error.value = cause instanceof Error ? cause.message : '读取卡片候选失败';
  } finally {
    if (token === generation) reading.value = false;
  }
}
function invalidate() {
  ++generation;
  preview.value = null;
  application.value = null;
  column.value = null;
  reviewed.value = false;
  error.value = '';
  notice.value = '';
}
async function read() {
  if (locked.value || !props.node.sourcePath || !containerSelector.value.trim()) return;
  const token = ++generation;
  const { siteId, node } = props;
  const plan = applicationPlan.value;
  const selection = { sourcePath: node.sourcePath!, nodeKey: node.key, containerSelector: containerSelector.value.trim() };
  reading.value = true;
  preview.value = null;
  reviewed.value = false;
  error.value = '';
  try {
    const [result, current, menu] = await Promise.all([
      siteModelApi.navigationLanding(siteId, plan, selection),
      siteModelApi.inspectNavigationLanding(siteId, plan, selection),
      http.get<NonNullable<typeof column.value>>(`/api/admin/v2/cms/menus/${node.menuId}`),
    ]);
    if (token !== generation) return;
    if (result.menuId !== node.menuId || result.nodeKey !== node.key || result.sourcePath !== node.sourcePath ||
      result.navigationRevision !== props.navigationRevision || menu.id !== node.menuId || menu.siteProjectId !== siteId) {
      throw new Error('栏目或计划修订已变化，请刷新工作台目标后重新读取。');
    }
    preview.value = result;
    application.value = current;
    displayOnly.value = current.displayOnlyNodeKeys ?? [];
    column.value = menu;
  } catch (cause) {
    if (token === generation) error.value = cause instanceof Error ? cause.message : '读取栏目概览失败';
  } finally {
    if (token === generation) reading.value = false;
  }
}
async function write(withdraw: boolean) {
  if (locked.value || !props.node.sourcePath || !application.value ||
    (withdraw ? !application.value.applied : !canApply.value)) return;
  const { siteId, node } = props;
  const plan = applicationPlan.value;
  const selected = preview.value;
  const menu = column.value;
  const selection = { sourcePath: node.sourcePath!, nodeKey: node.key, containerSelector: containerSelector.value.trim() };
  const token = ++generation;
  writing.value = true;
  emit('busy', true);
  error.value = '';
  let changed = false;
  try {
    await ElMessageBox.confirm(withdraw
      ? '恢复本次概览应用前的父栏目模板。请核对运行路由和既有静态页面。'
      : `将已核对的 ${selected!.cards.length} 张卡片接入 ${menu!.listPageUrl}。${displayOnly.value.length} 张纯展示，${pendingCount.value} 个必需目标仍待处理。`,
    withdraw ? '确认撤回栏目概览' : '确认应用栏目概览', { type: 'warning' });
    if (token !== generation) return;
    const result = withdraw
      ? await siteModelApi.withdrawNavigationLanding(siteId, plan, selection)
      : await siteModelApi.applyNavigationLanding(siteId, plan, selected!, menu!.listPageTemplate ?? null, menu!.listPageUrl!, displayOnly.value);
    if (token !== generation) return;
    changed = true;
    application.value = result;
    reviewed.value = false;
    const reloaded = !result.requiresRouteReload || await reloadSiteRoutes(siteId).catch(() => false);
    if (token !== generation) return;
    notice.value = reloaded ? result.message : '模板已保存，运行路由尚未重载，请在站点服务中处理并核对实际地址。';
    writing.value = false;
    await read();
  } catch (cause) {
    if (token === generation && cause !== 'cancel' && cause !== 'close') {
      error.value = cause instanceof Error ? cause.message : '栏目概览操作未完成，请刷新状态';
    }
  } finally {
    writing.value = false;
    emit('busy', false);
    if (changed) emit('changed');
  }
}
watch(() => [props.siteId, applicationPlan.value.id, applicationPlan.value.revision, props.node.key, props.node.menuId, props.node.sourcePath, props.navigationRevision], () => {
  invalidate();
  containerSelector.value = '';
  candidates.value = [];
  reading.value = false;
  if (writing.value) emit('busy', false);
  writing.value = false;
  void discover();
}, { flush: 'sync', immediate: true });
watch(() => props.disabled, disabled => {
  if (!disabled && !reading.value && !writing.value && !preview.value && !candidates.value.length) void discover();
});
onBeforeUnmount(() => { ++generation; if (writing.value) emit('busy', false); });
</script>

<style scoped>
.landing-panel { margin-top: var(--admin-space-4); padding: var(--admin-space-4); border: 1px solid var(--admin-border-subtle); }
.landing-panel code { overflow-wrap: anywhere; }
.landing-panel strong:focus { outline: 2px solid var(--el-color-primary); outline-offset: 3px; }
.landing-panel .el-checkbox { height: auto; white-space: normal; }
.landing-panel :deep(.el-checkbox__label) { white-space: normal; }
</style>
