<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { ElMessageBox } from 'element-plus';
import PublicListApplicationPanel from '@/components/sites/PublicListApplicationPanel.vue';
import {
  confirmSharedLayout, previewSharedLayout, readQueryBindings, readSharedLayoutChoices,
  type QuerySnapshot, type SharedLayoutChoice,
} from '@/api/templateQueryBinding';
import { useSiteContextStore } from '@/stores/siteContext';
import { applySharedDetailLayout, previewSharedDetailLayout, readPublicListReadiness, withdrawSharedDetailLayout,
  type PublicListReadiness, type SharedDetailLayout } from '@/api/templatePublicList';
import { reloadSiteRoutes } from '@/api/templateRecognition';

const props = defineProps<{
  siteId: number;
  menuId: number;
  sourcePath: string;
  navigationPlanId: string;
  disabled: boolean;
}>();
const emit = defineEmits<{ busy: [boolean]; changed: [] }>();
const siteContext = useSiteContextStore();
const sourceQueryId = ref('');
const choices = ref<SharedLayoutChoice[]>([]);
const readiness = ref<PublicListReadiness | null>(null);
const detail = ref<SharedDetailLayout | null>(null);
const routeNotice = ref('');
const listActions = computed(() => {
  const display = (preview.value ?? saved.value)?.presentation?.request;
  return !!display && 'actions' in display && !!display.actions?.length;
});
const preview = ref<QuerySnapshot | null>(null);
const saved = ref<QuerySnapshot | null>(null);
const busy = ref(false);
const error = ref('');
let active = true;
const key = computed(() => 'cms.workbench.shared-list.v1:' + JSON.stringify([
  siteContext.currentAdmin?.id, props.siteId, props.menuId, props.sourcePath, props.navigationPlanId,
]));
const request = computed(() => ({
  menuId: props.menuId,
  sourcePath: props.sourcePath,
  layout: { sourceQueryId: sourceQueryId.value.trim(), navigationPlanId: props.navigationPlanId },
}));

async function run(action: () => Promise<void>) {
  if (busy.value || props.disabled) return;
  busy.value = true;
  emit('busy', true);
  error.value = '';
  try {
    await action();
  } catch (cause) {
    if (active) error.value = cause instanceof Error ? cause.message : String(cause);
  } finally {
    if (active) {
      busy.value = false;
      emit('busy', false);
    }
  }
}
async function inspect() {
  await run(async () => {
    preview.value = null;
    const result = await previewSharedLayout(props.siteId, request.value);
    if (active) preview.value = result;
  });
}
async function confirm() {
  if (!preview.value) return;
  const expectedId = preview.value.id;
  await run(async () => {
    const result = await confirmSharedLayout(props.siteId, { ...request.value, expectedId });
    if (active) {
      saved.value = result;
      try { localStorage.setItem(key.value, JSON.stringify({ source: sourceQueryId.value, id: result.id })); }
      catch { /* 本次确认已保存在服务端。 */ }
      readiness.value = await readPublicListReadiness(props.siteId, result.id);
    }
  });
}
async function loadChoices() {
  await run(async () => {
    const values = await readSharedLayoutChoices(props.siteId, props.menuId, props.sourcePath);
    if (!active) return;
    choices.value = values.filter(value => !value.current);
    let id = values.find(value => value.current)?.queryId;
    if (!id) {
      try {
        const raw = localStorage.getItem(key.value);
        if (raw) id = JSON.parse(raw).id;
      } catch { /* 应用回执可直接从服务端读取。 */ }
    }
    if (!id) {
      if (choices.value.length === 1) sourceQueryId.value = choices.value[0].queryId;
      return;
    }
    const result = await readQueryBindings(props.siteId, id);
    if (active && result.menuId === props.menuId && result.sourcePath === props.sourcePath && result.sharedLayout) {
      sourceQueryId.value = result.sharedLayout.sourceQueryId;
      saved.value = result;
      readiness.value = await readPublicListReadiness(props.siteId, result.id);
    }
  });
}
async function inspectDetail() {
  const id = saved.value?.id;
  if (!id) return;
  await run(async () => {
    detail.value = null;
    const result = await previewSharedDetailLayout(props.siteId, id);
    if (active) detail.value = result;
  });
}
async function applyDetail() {
  const id = saved.value?.id, token = detail.value?.token;
  if (!id || !token) return;
  await run(async () => {
    const result = await applySharedDetailLayout(props.siteId, id, token);
    if (!active) return;
    detail.value = result;
    const reloaded = await reloadSiteRoutes(props.siteId);
    if (!active) return;
    routeNotice.value = reloaded ? '路由已重载。' : '应用已保存，请在站点服务中重载路由。';
    readiness.value = await readPublicListReadiness(props.siteId, id);
    if (active) emit('changed');
  });
}
async function withdrawDetail() {
  const query = saved.value;
  if (!query) return;
  try {
    await ElMessageBox.confirm('恢复本栏目应用共享详情前的模板，随后重载现有路由。', '确认撤回共享详情', { type: 'warning' });
  } catch { return; }
  if (!active) return;
  await run(async () => {
    await withdrawSharedDetailLayout(props.siteId, query.id, query.packageVersion);
    if (!active) return;
    detail.value = null;
    const reloaded = await reloadSiteRoutes(props.siteId);
    if (!active) return;
    routeNotice.value = reloaded ? '详情已撤回，路由已重载。' : '详情已撤回，请在站点服务中重载路由。';
    readiness.value = await readPublicListReadiness(props.siteId, query.id);
    if (active) emit('changed');
  });
}
onMounted(loadChoices);
onBeforeUnmount(() => {
  active = false;
  if (busy.value) emit('busy', false);
});
</script>

<template>
  <section aria-label="共享列表版式审核">
    <h4>共享列表版式</h4>
    <p>原页 {{ sourcePath }} · 目标栏目 #{{ menuId }}。查询读取目标栏目已发布的有效记录。</p>
    <el-select v-model="sourceQueryId" aria-label="原已确认列表版式" placeholder="选择已应用的列表版式"
      :disabled="disabled || busy" @change="preview = null; saved = null; readiness = null; detail = null">
      <el-option v-for="choice in choices" :key="choice.queryId" :value="choice.queryId"
        :label="`${choice.name || '栏目'} #${choice.menuId}`" />
    </el-select>
    <el-button :disabled="disabled || busy" @click="loadChoices">读取已确认来源与当前绑定</el-button>
    <p v-if="!busy && !choices.length && !saved">当前原页暂无已公开应用的列表版式可选。</p>
    <el-button :disabled="disabled || busy || !sourceQueryId.trim()" @click="inspect">预览本栏目查询</el-button>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <p v-if="preview">查询计划 {{ preview.id }} · 列表路由 {{ preview.firstUrl }} · 展示字段 {{ preview.presentation?.fields.map(field => field.name).join('、') }}</p>
    <p v-if="listActions">
      复用已审核的站内表单动作，预填值取自本栏目记录。
    </p>
    <p v-if="preview?.presentation?.deferredRegion">原确认方案中暂缓发布的区域仍省略。</p>
    <el-button v-if="preview" type="primary" :disabled="disabled || busy" @click="confirm">确认本栏目查询</el-button>
    <template v-if="saved">
      <p role="status">查询 {{ saved.id }} 已保存。目标栏目 #{{ saved.menuId }}，版式来源查询 {{ sourceQueryId }}。</p>
      <p v-if="readiness" role="status">有效公开记录 {{ readiness.publishedRecords }}{{ readiness.moreRecords ? '+' : '' }} 条。{{ readiness.message }}</p>
      <el-button :disabled="disabled || busy" @click="inspectDetail">预览本栏目共享详情版式</el-button>
      <template v-if="detail">
        <p>详情版式来自 #{{ detail.sourceMenuId }}。本栏目详情路由 {{ detail.targetRoute }}。</p>
        <p v-if="detail.returnUrl">返回链接 {{ detail.returnUrl }}</p>
        <template v-if="detail.omittedRegions?.length">
          <p>本栏目详情将省略以下原页静态样稿区域。</p>
          <ul><li v-for="region in detail.omittedRegions" :key="region.selector">
            <code>{{ region.selector }}</code> {{ region.preview }}
          </li></ul>
        </template>
        <el-button v-if="!detail.applied && !detail.replacementRequiresWithdrawal" type="primary"
          :disabled="disabled || busy" @click="applyDetail">确认应用本栏目详情</el-button>
        <p v-else-if="detail.replacementRequiresWithdrawal" role="status">当前详情已有应用，请先撤回旧版。</p>
        <template v-if="detail.applied || detail.replacementRequiresWithdrawal">
          <p v-if="detail.applied" role="status">本栏目共享详情已应用。</p>
          <el-button :disabled="disabled || busy" @click="withdrawDetail">撤回共享详情应用</el-button>
        </template>
      </template>
      <p v-if="routeNotice" role="status">{{ routeNotice }}</p>
      <PublicListApplicationPanel :site-id="siteId" :query-id="saved.id" :package-version="saved.packageVersion"
        :complete="true" :disabled="disabled || busy" :has-form-actions="listActions" :grouped-menu-id="menuId"
        @busy="emit('busy', $event)" @changed="emit('changed')" />
    </template>
  </section>
</template>
