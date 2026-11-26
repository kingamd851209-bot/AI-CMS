<script setup lang="ts">
import { createTableLoader } from '@/utils/tableLoader';
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import { CaretBottom, CaretRight, CircleCheckFilled, Refresh } from '@element-plus/icons-vue';
import I18nModelFieldsDialog from './I18nModelFieldsDialog.vue';
import I18nTemplateContentDialog from './I18nTemplateContentDialog.vue';
import {
  listI18nSiteTasks,
  getI18nSiteTask,
  syncI18nSiteTask,
  syncI18nSiteTaskAssets,
  stopI18nSiteTaskSync,
  checkI18nTranslationReady,
  type ContentSyncMode,
  type TranslationMode,
  type ExistingContentAction,
  type I18nSiteTask,
  type I18nSiteTaskDetail
} from '@/api/i18nSiteTask';

const route = useRoute();
const router = useRouter();

const loading = ref(false);
const keyword = ref('');
const tasks = ref<I18nSiteTask[]>([]);

const detailVisible = ref(false);
const detailLoading = ref(false);
const currentTask = ref<I18nSiteTask | null>(null);
const details = ref<I18nSiteTaskDetail[]>([]);
const syncing = ref(false);

/** 每行目标栏目要同步的维度勾选（id -> 勾选状态） */
interface RowChecks { model: boolean; template: boolean; content: boolean; }
const EMPTY_CHECKS: RowChecks = { model: false, template: false, content: false };
const rowChecks = reactive<Record<number, RowChecks>>({});

/** 数据模型字段设置弹窗 */
const fieldsDialogVisible = ref(false);
const fieldsDialogRow = ref<I18nSiteTaskDetail | null>(null);
/** 栏目模版内容弹窗 */
const tplDialogVisible = ref(false);
const tplDialogRow = ref<I18nSiteTaskDetail | null>(null);

/** 同步确认弹窗：内容方式与翻译方式 */
const syncDialogVisible = ref(false);
const syncPayload = ref<Record<number, string[]>>({});
const syncRowCount = ref(0);
const syncDimLabels = ref<string[]>([]);
const syncHasModelDim = ref(false);
const syncHasContentDim = ref(false);
const syncContentMode = ref<ContentSyncMode>('overwrite');
const syncContentTranslation = ref<TranslationMode>('none');
const syncModelTranslation = ref<TranslationMode>('none');
const syncExistingAction = ref<ExistingContentAction>('skip');
const aiChecking = ref(false);
const aiReady = ref<boolean | null>(null);
const aiMessage = ref('');

const statusLabels: Record<string, string> = {
  PENDING: '待同步',
  RUNNING: '进行中',
  SUCCESS: '已完成',
  FAILED: '失败',
  PARTIAL: '部分完成'
};

function statusTagType(status?: string): 'success' | 'info' | 'warning' | 'danger' {
  switch (status) {
    case 'SUCCESS': return 'success';
    case 'PARTIAL': return 'warning';
    case 'FAILED': return 'danger';
    default: return 'info';
  }
}

function statusLabel(status?: string): string {
  return statusLabels[status ?? ''] ?? '待同步';
}

function menuTypeLabel(type?: number): string {
  if (type === 0) return '单页';
  if (type === 1) return '列表';
  return '-';
}

/** 后端返回 ISO 格式（2026-09-20T13:40:24），展示为 2026-09-20 13:40:24 */
function formatDateTime(value?: string | null): string {
  return value ? value.replace('T', ' ').slice(0, 19) : '-';
}

/** 明细按栏目父子关系组装成树（站点根栏目为顶层），并记录每个节点的整棵子树 */
const treeModel = computed(() => {
  // id 统一按数字建/查索引，避免 JSON 中 id 以字符串返回时 Map 命中失败
  const idKey = (value?: number | null): number | undefined => (value == null ? undefined : Number(value));
  const bySourceId = new Map<number, I18nSiteTaskDetail>();
  const byTargetId = new Map<number, I18nSiteTaskDetail>();
  for (const d of details.value) {
    const sourceKey = idKey(d.sourceMenuId);
    if (sourceKey != null) bySourceId.set(sourceKey, d);
    const targetKey = idKey(d.targetMenuId);
    if (targetKey != null) byTargetId.set(targetKey, d);
  }
  const childrenOf = new Map<number, I18nSiteTaskDetail[]>();
  const roots: I18nSiteTaskDetail[] = [];
  for (const d of details.value) {
    // 优先按源栏目父子关系挂载；源栏目解析不到（如已删除）时退回目标栏目父子关系（目标为完整副本）
    const sourceParentKey = idKey(d.sourceParentId);
    let parent = sourceParentKey != null ? bySourceId.get(sourceParentKey) : undefined;
    const targetParentKey = idKey(d.targetParentId);
    if (!parent && targetParentKey != null) {
      parent = byTargetId.get(targetParentKey);
    }
    if (parent && parent !== d) {
      const siblings = childrenOf.get(parent.id) ?? [];
      siblings.push(d);
      childrenOf.set(parent.id, siblings);
    } else {
      roots.push(d);
    }
  }

  const attachedIds = new Set<number>();
  const attach = (node: I18nSiteTaskDetail, path: Set<number>): I18nSiteTaskDetail => {
    attachedIds.add(node.id);
    const children = childrenOf.get(node.id);
    if (!children || children.length === 0) {
      return { ...node };
    }
    path.add(node.id);
    const nested = children.filter(c => !path.has(c.id)).map(c => attach(c, path));
    path.delete(node.id);
    return nested.length > 0 ? { ...node, children: nested } : { ...node };
  };
  const tree = roots.map(node => attach(node, new Set<number>()));
  // 兜底：父子关系成环等异常数据下，未被挂载的行以顶层展示，避免整行丢失
  for (const d of details.value) {
    if (!attachedIds.has(d.id)) {
      attachedIds.add(d.id);
      tree.push({ ...d });
    }
  }

  // 明细ID -> 自身及全部后代明细ID（树的先后顺序）
  const subtreeIds = new Map<number, number[]>();
  const collect = (node: I18nSiteTaskDetail): number[] => {
    const ids = [node.id];
    for (const child of node.children ?? []) {
      ids.push(...collect(child));
    }
    subtreeIds.set(node.id, ids);
    return ids;
  };
  tree.forEach(collect);

  return { tree, subtreeIds };
});

function subtreeOf(id: number): number[] {
  return treeModel.value.subtreeIds.get(id) ?? [id];
}

/**
 * 展开态：不依赖 el-table 的树模式（其行内缩进/展开在部分场景下不生效），
 * 直接按后端返回的先后顺序平铺，并自行计算层级做缩进渲染。
 */
const collapsedIds = reactive(new Set<number>());

function toggleCollapse(id: number) {
  if (collapsedIds.has(id)) {
    collapsedIds.delete(id);
  } else {
    collapsedIds.add(id);
  }
}

interface FlatRow extends I18nSiteTaskDetail {
  depth: number;
  hasChildren: boolean;
}

const flatRows = computed<FlatRow[]>(() => {
  const rows: FlatRow[] = [];
  const walk = (node: I18nSiteTaskDetail, depth: number) => {
    const children = node.children ?? [];
    const { children: _children, ...rest } = node;
    rows.push({ ...rest, depth, hasChildren: children.length > 0 });
    if (children.length > 0 && !collapsedIds.has(node.id)) {
      children.forEach(child => walk(child, depth + 1));
    }
  };
  treeModel.value.tree.forEach(node => walk(node, 1));
  return rows;
});

function checkState(id: number): RowChecks {
  return rowChecks[id] ?? EMPTY_CHECKS;
}

/** 勾选单个同步维度 */
function setCheck(id: number, dim: keyof RowChecks, value: boolean) {
  const state = rowChecks[id] ?? { ...EMPTY_CHECKS };
  state[dim] = value;
  rowChecks[id] = state;
}

/** 树节点多选框：本行及所有子栏目一起选中/取消（整行三个同步项） */
function setRowCheck(id: number, value: boolean) {
  for (const rowId of subtreeOf(id)) {
    rowChecks[rowId] = { model: value, template: value, content: value };
  }
}

function isRowFullyChecked(id: number): boolean {
  const state = checkState(id);
  return state.model && state.template && state.content;
}

function rowCheckAll(id: number): boolean {
  const ids = subtreeOf(id);
  return ids.length > 0 && ids.every(isRowFullyChecked);
}

function rowCheckIndeterminate(id: number): boolean {
  const ids = subtreeOf(id);
  const full = ids.filter(isRowFullyChecked).length;
  return full > 0 && full < ids.length;
}

function resetRowChecks() {
  Object.keys(rowChecks).forEach(key => delete rowChecks[Number(key)]);
  for (const d of details.value) {
    rowChecks[d.id] = { ...EMPTY_CHECKS };
  }
}

const checkedCount = computed(() =>
  details.value.filter(d => {
    const state = rowChecks[d.id];
    return Boolean(state && (state.model || state.template || state.content));
  }).length
);

function parentLabel(id?: number | null, name?: string | null): string {
  if (id == null || id === 0) return '无上级';
  return `${id} - ${name ?? '-'}`;
}

function shortPath(path?: string | null): string {
  if (!path) return '';
  const parts = path.split('/');
  return parts[parts.length - 1] || path;
}

function syncSummary(detail: I18nSiteTaskDetail): string {
  if (detail.syncNote) return detail.syncNote;
  const parts: string[] = [];
  if (detail.modelSynced) parts.push('数据模型');
  if (detail.templateSynced) parts.push('模版');
  if (detail.contentSynced) parts.push('内容');
  return parts.length === 0 ? '未同步' : `已同步：${parts.join('、')}`;
}

const fieldsShowTarget = computed(() => Boolean(fieldsDialogRow.value?.modelSynced));

const tplDialogKind = ref<'list' | 'detail'>('list');
const tplSourcePath = computed(() => tplDialogKind.value === 'list'
  ? tplDialogRow.value?.sourceListTemplate
  : tplDialogRow.value?.sourceDetailTemplate);
const tplTargetPath = computed(() => tplDialogKind.value === 'list'
  ? tplDialogRow.value?.targetListTemplate
  : tplDialogRow.value?.targetDetailTemplate);
const tplShowTarget = computed(() => Boolean(tplDialogRow.value?.templateSynced && tplTargetPath.value));

function openModelFields(row: I18nSiteTaskDetail) {
  fieldsDialogRow.value = row;
  fieldsDialogVisible.value = true;
}

function openTemplate(row: I18nSiteTaskDetail, kind: 'list' | 'detail') {
  tplDialogRow.value = row;
  tplDialogKind.value = kind;
  tplDialogVisible.value = true;
}

// 同步为后台异步执行：轮询任务状态直到不再 RUNNING
let pollingTimer: ReturnType<typeof setInterval> | null = null;
const POLL_INTERVAL = 2000;
const POLL_MAX_MS = 10 * 60 * 1000;

function isRunningTask(task: I18nSiteTask | null): boolean {
  if (!task) return false;
  return [task.modelSyncStatus, task.templateSyncStatus, task.contentSyncStatus, task.assetSyncStatus]
    .some(s => s === 'RUNNING');
}

const columnRunning = computed(() => {
  const task = currentTask.value;
  if (!task) return false;
  return [task.modelSyncStatus, task.templateSyncStatus, task.contentSyncStatus].some(s => s === 'RUNNING');
});

const assetRunning = computed(() => currentTask.value?.assetSyncStatus === 'RUNNING');
const assetSynced = computed(() => currentTask.value?.assetSyncStatus === 'SUCCESS');
const assetSyncing = ref(false);
const stopping = ref(false);

let pollingVersion = 0;

function stopPolling() {
  pollingVersion++;
  if (pollingTimer !== null) {
    clearInterval(pollingTimer);
    pollingTimer = null;
  }
}

function startPolling(taskId: number, doneMessage = '') {
  stopPolling();
  const version = pollingVersion;
  let elapsed = 0;
  pollingTimer = setInterval(async () => {
    elapsed += POLL_INTERVAL;
    try {
      const data = await getI18nSiteTask(taskId);
      if (version !== pollingVersion || !detailVisible.value) return;
      currentTask.value = data.task;
      const stillRunning = isRunningTask(data.task);
      if (!stillRunning || elapsed >= POLL_MAX_MS) {
        stopPolling();
        details.value = data.details ?? [];
        await loadTasks();
        if (doneMessage && !stillRunning) {
          ElMessage.success(doneMessage);
        }
      }
    } catch {
      if (version !== pollingVersion) return;
      stopPolling();
    }
  }, POLL_INTERVAL);
}

const loadTasksLoader = createTableLoader(loading);

async function loadTasks() {
  const request = loadTasksLoader.begin(() => {
    tasks.value = [];
  });
  try {
    const loadedRows = await listI18nSiteTasks(keyword.value.trim() || undefined);
    if (!request.isCurrent()) return;
    tasks.value = loadedRows;
  } catch (e) {
    if (!request.isCurrent()) return;
    ElMessage.error(e instanceof Error ? e.message : '加载多语言站点任务失败');
  } finally {
    request.finish();
  }
}

const openDetailLoader = createTableLoader(detailLoading);

async function openDetail(taskId: number) {
  stopPolling();
  detailVisible.value = true;
  const request = openDetailLoader.begin(() => {
    currentTask.value = null;
    details.value = [];
    resetRowChecks();
  });
  try {
    const data = await getI18nSiteTask(taskId);
    if (!request.isCurrent()) return;
    currentTask.value = data.task;
    details.value = data.details ?? [];
    resetRowChecks();
    if (isRunningTask(data.task)) {
      startPolling(taskId);
    }
  } catch (e) {
    if (!request.isCurrent()) return;
    ElMessage.error(e instanceof Error ? e.message : '加载任务明细失败');
  } finally {
    request.finish();
  }
}

/** 打开同步确认弹窗：先在表格里收集勾选的栏目与维度 */
function handleSync() {
  const task = currentTask.value;
  if (!task) return;
  const payload: Record<number, string[]> = {};
  const dims = new Set<string>();
  for (const d of details.value) {
    const state = rowChecks[d.id];
    if (!state) continue;
    const rowDims: string[] = [];
    if (state.model) { rowDims.push('model'); dims.add('model'); }
    if (state.template) { rowDims.push('template'); dims.add('template'); }
    if (state.content) { rowDims.push('content'); dims.add('content'); }
    if (rowDims.length > 0) payload[d.id] = rowDims;
  }
  const rowCount = Object.keys(payload).length;
  if (rowCount === 0) {
    ElMessage.warning('请先在目标栏目行的单元格上勾选要同步的项');
    return;
  }
  syncPayload.value = payload;
  syncRowCount.value = rowCount;
  syncDimLabels.value = [...dims].map(d => ({ model: '数据模型', template: '模版', content: '内容' }[d] ?? d));
  syncHasModelDim.value = dims.has('model');
  syncHasContentDim.value = dims.has('content');
  syncDialogVisible.value = true;
}

const needTranslation = computed(() =>
  syncContentTranslation.value === 'ai' || syncModelTranslation.value === 'ai'
);

async function checkAiTranslationReady() {
  aiChecking.value = true;
  try {
    const result = await checkI18nTranslationReady();
    aiReady.value = result.ready;
    aiMessage.value = result.message ?? '';
  } catch (e) {
    aiReady.value = false;
    aiMessage.value = e instanceof Error ? e.message : 'AI翻译服务检查失败';
  } finally {
    aiChecking.value = false;
  }
}

watch(needTranslation, (need) => {
  if (!need) {
    aiReady.value = null;
    aiMessage.value = '';
    return;
  }
  void checkAiTranslationReady();
});

async function submitSync() {
  const task = currentTask.value;
  if (!task) return;
  syncing.value = true;
  try {
    stopPolling();
    await syncI18nSiteTask(task.id, syncPayload.value, {
      contentMode: syncContentMode.value,
      contentTranslation: syncContentTranslation.value,
      modelTranslation: syncModelTranslation.value,
      existingAction: syncExistingAction.value
    });
    syncDialogVisible.value = false;
    ElMessage.info('同步已提交，正在后台执行…');
    resetRowChecks();
    startPolling(task.id, '同步完成');
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '同步失败');
  } finally {
    syncing.value = false;
  }
}

async function handleSyncAssets() {
  const task = currentTask.value;
  if (!task) return;
  try {
    await ElMessageBox.confirm(
      `即将把原站点【${task.sourceSiteName ?? ''}】的静态资源（UI 包文件）复制到目标站点`
        + `【${task.targetSiteName ?? ''}】，并更新目标站点的 UI 包版本。`
        + '目标站点同名文件会被【覆盖】，且不可撤销。是否继续？',
      '⚠️ 静态资源覆盖同步确认',
      {
        type: 'warning',
        confirmButtonText: '确认覆盖同步',
        cancelButtonText: '取消',
        confirmButtonClass: 'el-button--danger'
      }
    );
  } catch {
    return;
  }
  assetSyncing.value = true;
  try {
    stopPolling();
    await syncI18nSiteTaskAssets(task.id);
    ElMessage.info('静态资源同步已提交，正在后台执行…');
    startPolling(task.id, '静态资源同步完成');
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '静态资源同步失败');
  } finally {
    assetSyncing.value = false;
  }
}

/** 停止进行中的同步：后端为协作式取消，会在当前栏目/当前文件之后停下 */
async function handleStop() {
  const task = currentTask.value;
  if (!task) return;
  stopping.value = true;
  try {
    const result = await stopI18nSiteTaskSync(task.id);
    currentTask.value = result.task;
    if (!result.stopped) {
      ElMessage.info('没有进行中的同步');
      stopPolling();
      return;
    }
    ElMessage.warning('已发出停止，将在当前项执行完后结束');
    // 状态回写后自动结束轮询并刷新明细
    startPolling(task.id);
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '停止同步失败');
  } finally {
    stopping.value = false;
  }
}

async function openTaskFromQuery() {
  const taskId = route.query.taskId ? Number(route.query.taskId) : undefined;
  if (taskId) {
    await openDetail(taskId);
    // 消费掉 query，避免刷新反复打开
    void router.replace({ path: route.path, query: { ...route.query, taskId: undefined } });
  }
}

watch(() => route.query.taskId, (val) => {
  if (val) void openTaskFromQuery();
});

watch(detailVisible, (visible) => {
  if (!visible) {
    stopPolling();
    openDetailLoader.invalidate();
  }
});

onMounted(async () => {
  await loadTasks();
  await openTaskFromQuery();
});

onUnmounted(stopPolling);
</script>

<template>
  <section class="i18n-task-page">
    <header class="i18n-task-page__header">
      <div>
        <p class="i18n-task-page__eyebrow">站点智能管理</p>
        <h1>多语言站点任务</h1>
        <p class="i18n-task-page__desc">生成多语言站点后产生的任务；可在此查看主任务与逐栏目明细，并对栏目按维度（部分/全部）同步。</p>
      </div>
      <div class="i18n-task-page__actions admin-table-toolbar">
        <el-input size="small"
          v-model="keyword"
          placeholder="按原/目标站点名称搜索"
          clearable
          style="width: 240px"
          @keyup.enter="loadTasks"
        />
        <el-button size="small" type="primary" @click="loadTasks">搜索</el-button>
        <el-button size="small" :icon="Refresh" @click="loadTasks">刷新</el-button>
      </div>
    </header>

    <el-table element-loading-text="正在加载数据…" :data="tasks" v-loading="loading" border stripe class="i18n-task-table">
      <el-table-column prop="id" label="任务ID" width="90" />
      <el-table-column prop="sourceSiteName" label="原站点" min-width="140" />
      <el-table-column prop="sourceLanguage" label="原站点语言" width="120" />
      <el-table-column prop="targetSiteName" label="目标站点" min-width="140" />
      <el-table-column prop="targetLanguage" label="目标站点语言" width="120" />
      <el-table-column label="栏目生成" width="110">
        <template #default="{ row }">
          <el-tag :type="statusTagType(row.columnStatus)" size="small">{{ statusLabel(row.columnStatus) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="数据模型" width="110">
        <template #default="{ row }">
          <el-tag :type="statusTagType(row.modelSyncStatus)" size="small">{{ statusLabel(row.modelSyncStatus) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="模版" width="110">
        <template #default="{ row }">
          <el-tag :type="statusTagType(row.templateSyncStatus)" size="small">{{ statusLabel(row.templateSyncStatus) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="内容" width="110">
        <template #default="{ row }">
          <el-tag :type="statusTagType(row.contentSyncStatus)" size="small">{{ statusLabel(row.contentSyncStatus) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="静态资源" width="110">
        <template #default="{ row }">
          <el-tag :type="statusTagType(row.assetSyncStatus)" size="small">{{ statusLabel(row.assetSyncStatus) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="创建时间" width="170">
        <template #default="{ row }">{{ formatDateTime(row.createTime) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="120" fixed="right">
        <template #default="{ row }">
          <el-button size="small" link type="primary" @click="openDetail(row.id)">任务执行明细</el-button>
        </template>
      </el-table-column>
      <template #empty>
        <el-empty description="暂无多语言站点任务" />
      </template>
    </el-table>

    <el-drawer v-model="detailVisible" title="多语言站点任务明细" size="85%" destroy-on-close>
      <div v-loading="detailLoading" element-loading-text="正在加载数据…" class="i18n-task-detail">
        <div v-if="currentTask" class="i18n-task-detail__summary">
          <el-tag type="info">任务 #{{ currentTask.id }}</el-tag>
          <span>原站点：{{ currentTask.sourceSiteName }}（{{ currentTask.sourceLanguage }}）</span>
          <span>目标站点：{{ currentTask.targetSiteName }}（{{ currentTask.targetLanguage }}）</span>
        </div>

        <div v-if="currentTask" class="i18n-task-detail__assets">
          <span class="i18n-task-detail__assets-label">静态资源同步</span>
          <el-button
            type="primary"
            :loading="assetSyncing || assetRunning"
            :disabled="assetRunning"
            @click="handleSyncAssets"
          >
            同步静态资源
          </el-button>
          <el-button v-if="assetRunning" plain :loading="stopping" @click="handleStop">停止同步</el-button>
          <el-tag v-if="assetSynced" type="success" size="small" effect="dark" class="i18n-task-detail__assets-ok">
            <el-icon><CircleCheckFilled /></el-icon>已同步
          </el-tag>
          <span v-if="assetRunning" class="i18n-task-detail__running">同步进行中…</span>
          <span v-else class="i18n-task-detail__warning">
            ⚠️ 会把原站点 UI 包文件复制到目标站点，覆盖目标站点同名文件
          </span>
        </div>

        <div class="i18n-task-detail__toolbar">
          <span>已勾选 <b>{{ checkedCount }}</b> 个栏目</span>
          <span class="i18n-task-detail__hint">
            勾选首列树节点多选框=本行及所有子栏目全选；勾选各「目标」单元格多选框=按单项同步（「目标类型」随数据模型一起同步）
          </span>
          <el-button type="danger" :loading="syncing || columnRunning" :disabled="columnRunning" @click="handleSync">
            同步勾选栏目
          </el-button>
          <el-button v-if="columnRunning" plain :loading="stopping" @click="handleStop">停止同步</el-button>
          <span v-if="columnRunning" class="i18n-task-detail__running">同步进行中…</span>
          <span v-else class="i18n-task-detail__warning">⚠️ 同步会覆盖目标站点对应数据</span>
        </div>

        <el-table
          :data="flatRows"
          border
          stripe
          size="small"
          class="i18n-task-detail__table"
        >
          <el-table-column label="源栏目名称/ID" min-width="280" fixed="left">
            <template #default="{ row }">
              <span class="i18n-task-detail__tree-node" :style="{ paddingLeft: `${(row.depth - 1) * 20}px` }">
                <el-icon
                  v-if="row.hasChildren"
                  class="i18n-task-detail__caret"
                  @click="toggleCollapse(row.id)"
                >
                  <CaretBottom v-if="!collapsedIds.has(row.id)" />
                  <CaretRight v-else />
                </el-icon>
                <span v-else class="i18n-task-detail__caret-placeholder"></span>
                <el-checkbox
                  :model-value="rowCheckAll(row.id)"
                  :indeterminate="rowCheckIndeterminate(row.id)"
                  title="勾选=本行及所有子栏目全部同步项选中"
                  @change="(v: string | number | boolean) => setRowCheck(row.id, Boolean(v))"
                />
                <span>{{ row.sourceMenuName || '-' }}/{{ row.sourceMenuId ?? '-' }}</span>
              </span>
            </template>
          </el-table-column>
          <el-table-column label="源栏目编码" width="110">
            <template #default="{ row }">{{ row.sourceBizCode || '-' }}</template>
          </el-table-column>
          <el-table-column label="源上级栏目" min-width="170">
            <template #default="{ row }">{{ parentLabel(row.sourceParentId, row.sourceParentName) }}</template>
          </el-table-column>
          <el-table-column label="源类型" width="80">
            <template #default="{ row }">{{ menuTypeLabel(row.sourceMenuType) }}</template>
          </el-table-column>
          <el-table-column label="源数据模型结构" width="130">
            <template #default="{ row }">
              <el-button size="small" link type="primary" @click="openModelFields(row)">查看字段设置</el-button>
            </template>
          </el-table-column>
          <el-table-column label="源栏目模版" min-width="160">
            <template #default="{ row }">
              <div v-if="row.sourceListTemplate" class="i18n-task-detail__link" @click="openTemplate(row, 'list')">
                列表页：{{ shortPath(row.sourceListTemplate) }}
              </div>
              <div v-if="row.sourceDetailTemplate" class="i18n-task-detail__link" @click="openTemplate(row, 'detail')">
                详情页：{{ shortPath(row.sourceDetailTemplate) }}
              </div>
              <span v-if="!row.sourceListTemplate && !row.sourceDetailTemplate">-</span>
            </template>
          </el-table-column>
          <el-table-column label="源栏目内容数" width="115">
            <template #default="{ row }">{{ row.sourceContentCount ?? 0 }}</template>
          </el-table-column>

          <el-table-column label="目标栏目名称/ID" min-width="240">
            <template #default="{ row }">{{ row.targetMenuName || '-' }}/{{ row.targetMenuId ?? '-' }}</template>
          </el-table-column>
          <el-table-column label="目标栏目编码" width="110">
            <template #default="{ row }">{{ row.targetBizCode || '-' }}</template>
          </el-table-column>
          <el-table-column label="目标上级栏目" min-width="170">
            <template #default="{ row }">{{ parentLabel(row.targetParentId, row.targetParentName) }}</template>
          </el-table-column>
          <el-table-column label="目标类型" width="130">
            <template #default="{ row }">
              <div class="i18n-task-detail__cell-with-check">
                <el-checkbox
                  :model-value="checkState(row.id).model"
                  title="栏目类型随数据模型一起同步"
                  @change="(v: string | number | boolean) => setCheck(row.id, 'model', Boolean(v))"
                />
                <span>{{ menuTypeLabel(row.targetMenuType) }}</span>
                <el-icon v-if="row.modelSynced" class="i18n-task-detail__synced" title="已同步"><CircleCheckFilled /></el-icon>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="目标数据模型结构" width="190">
            <template #default="{ row }">
              <div class="i18n-task-detail__cell-with-check">
                <el-checkbox
                  :model-value="checkState(row.id).model"
                  title="勾选后同步目标栏目数据模型结构"
                  @change="(v: string | number | boolean) => setCheck(row.id, 'model', Boolean(v))"
                />
                <el-button size="small" link type="primary" @click="openModelFields(row)">查看字段设置</el-button>
                <el-icon v-if="row.modelSynced" class="i18n-task-detail__synced" title="已同步"><CircleCheckFilled /></el-icon>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="目标栏目模版" min-width="200">
            <template #default="{ row }">
              <div class="i18n-task-detail__cell-with-check">
                <el-checkbox
                  :model-value="checkState(row.id).template"
                  @change="(v: string | number | boolean) => setCheck(row.id, 'template', Boolean(v))"
                />
                <div>
                  <div v-if="row.targetListTemplate" class="i18n-task-detail__link" @click="openTemplate(row, 'list')">
                    列表页：{{ shortPath(row.targetListTemplate) }}
                  </div>
                  <div v-if="row.targetDetailTemplate" class="i18n-task-detail__link" @click="openTemplate(row, 'detail')">
                    详情页：{{ shortPath(row.targetDetailTemplate) }}
                  </div>
                  <span v-if="!row.targetListTemplate && !row.targetDetailTemplate">-</span>
                </div>
                <el-icon v-if="row.templateSynced" class="i18n-task-detail__synced" title="已同步"><CircleCheckFilled /></el-icon>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="目标栏目内容数" width="150">
            <template #default="{ row }">
              <div class="i18n-task-detail__cell-with-check">
                <el-checkbox
                  :model-value="checkState(row.id).content"
                  @change="(v: string | number | boolean) => setCheck(row.id, 'content', Boolean(v))"
                />
                <span>{{ row.targetContentCount ?? 0 }}</span>
                <el-icon v-if="row.contentSynced" class="i18n-task-detail__synced" title="已同步"><CircleCheckFilled /></el-icon>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="同步说明" min-width="180">
            <template #default="{ row }">{{ syncSummary(row) }}</template>
          </el-table-column>
          <el-table-column label="同步时间" width="165">
            <template #default="{ row }">{{ formatDateTime(row.syncTime) }}</template>
          </el-table-column>
        </el-table>

        <I18nModelFieldsDialog
          v-model="fieldsDialogVisible"
          :source-label="`源栏目：${fieldsDialogRow?.sourceMenuName ?? ''}`"
          :source-menu-id="fieldsDialogRow?.sourceMenuId"
          :source-page-type="fieldsDialogRow?.sourceMenuType"
          :target-label="`目标栏目：${fieldsDialogRow?.targetMenuName ?? ''}`"
          :target-menu-id="fieldsDialogRow?.targetMenuId"
          :target-page-type="fieldsDialogRow?.targetMenuType"
          :show-target="fieldsShowTarget"
        />

        <I18nTemplateContentDialog
          v-model="tplDialogVisible"
          :title="tplDialogKind === 'list' ? '栏目列表页模版内容' : '栏目详情页模版内容'"
          :source-label="`源栏目：${tplDialogRow?.sourceMenuName ?? ''}`"
          :source-site-id="currentTask?.sourceSiteId"
          :source-path="tplSourcePath || ''"
          :target-label="`目标栏目：${tplDialogRow?.targetMenuName ?? ''}`"
          :target-site-id="currentTask?.targetSiteId"
          :target-path="tplTargetPath || ''"
          :show-target="tplShowTarget"
        />

        <el-dialog v-model="syncDialogVisible" title="同步勾选栏目" width="680px" top="8vh">
          <el-alert
            type="warning"
            :closable="false"
            show-icon
            title="栏目数据模型结构、模版为覆盖同步，不可撤销；栏目内容按下方所选方式处理"
          />
          <p class="sync-dialog__summary">
            已勾选 <b>{{ syncRowCount }}</b> 个栏目（{{ syncDimLabels.join('、') }}）
          </p>
          <el-form label-width="150px">
            <template v-if="syncHasContentDim">
              <el-form-item label="栏目内容同步方式">
                <el-radio-group v-model="syncContentMode">
                  <el-radio value="increment">增量同步（按来源 id 补新增，已有内容可按下方处理）</el-radio>
                  <el-radio value="overwrite">覆盖同步（先删目标旧内容）</el-radio>
                </el-radio-group>
              </el-form-item>
              <el-form-item v-if="syncContentMode === 'increment'" label="已存在内容处理">
                <el-radio-group v-model="syncExistingAction">
                  <el-radio value="skip">跳过（保留目标现有内容）</el-radio>
                  <el-radio value="update">更新（用源内容更新目标）</el-radio>
                </el-radio-group>
              </el-form-item>
              <el-form-item label="栏目内容翻译方式">
                <el-radio-group v-model="syncContentTranslation">
                  <el-radio value="none">原文同步</el-radio>
                  <el-radio value="ai">AI翻译后同步</el-radio>
                </el-radio-group>
              </el-form-item>
            </template>
            <el-form-item v-if="syncHasModelDim" label="业务名称翻译方式">
              <el-radio-group v-model="syncModelTranslation">
                <el-radio value="none">原文同步</el-radio>
                <el-radio value="ai">AI翻译后同步</el-radio>
              </el-radio-group>
            </el-form-item>
          </el-form>
          <el-alert
            v-if="needTranslation"
            :type="aiReady === false ? 'error' : aiReady === true ? 'success' : 'info'"
            :closable="false"
            show-icon
            :title="aiChecking
              ? '正在检查并启动 AI 翻译服务…'
              : aiReady === true
                ? 'AI 翻译服务可用'
                : (aiMessage || 'AI 翻译服务不可用，无法进行翻译同步')"
          />
          <template #footer>
            <el-button @click="syncDialogVisible = false">取消</el-button>
            <el-button
              type="danger"
              :loading="syncing"
              :disabled="needTranslation && aiReady !== true"
              @click="submitSync"
            >
              确认同步
            </el-button>
          </template>
        </el-dialog>
      </div>
    </el-drawer>
  </section>
</template>

<style scoped>
.i18n-task-page {
  padding: 0;
}
.i18n-task-page__header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
  margin-bottom: var(--admin-card-gap);
}
.i18n-task-page__eyebrow {
  margin: 0;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.i18n-task-page__header h1 {
  margin: 4px 0;
  font-size: 20px;
}
.i18n-task-page__desc {
  margin: 0;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
.i18n-task-page__actions {
  display: flex;
  gap: 8px;
  align-items: center;
}
.i18n-task-detail__summary {
  display: flex;
  gap: 16px;
  align-items: center;
  margin-bottom: 12px;
  font-size: 13px;
}
.i18n-task-detail__assets {
  display: flex;
  gap: 12px;
  align-items: center;
  padding: 10px 12px;
  margin-bottom: 12px;
  border: 1px solid var(--el-color-primary-light-7);
  border-radius: 4px;
  background: var(--el-color-primary-light-9);
}
.i18n-task-detail__assets-label {
  font-size: 13px;
  font-weight: 600;
}
.i18n-task-detail__assets-ok {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.i18n-task-detail__toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
  align-items: center;
  margin-bottom: 12px;
}
.i18n-task-detail__hint {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.i18n-task-detail__table {
  width: 100%;
}
.sync-dialog__summary {
  margin: 12px 0;
  font-size: 13px;
}
.i18n-task-detail__tree-node {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  vertical-align: middle;
}
.i18n-task-detail__caret {
  cursor: pointer;
  color: var(--el-text-color-secondary);
  font-size: 13px;
  flex-shrink: 0;
}
.i18n-task-detail__caret:hover {
  color: var(--el-color-primary);
}
.i18n-task-detail__caret-placeholder {
  display: inline-block;
  width: 13px;
  flex-shrink: 0;
}
.i18n-task-detail__cell-with-check {
  display: flex;
  align-items: center;
  gap: 6px;
}
.i18n-task-detail__synced {
  color: var(--el-color-success);
  font-size: 15px;
  flex-shrink: 0;
}
.i18n-task-detail__link {
  color: var(--el-color-primary);
  cursor: pointer;
  font-size: 12px;
  line-height: 18px;
  white-space: nowrap;
}
.i18n-task-detail__link:hover {
  text-decoration: underline;
}
.i18n-task-detail__warning {
  color: var(--el-color-danger);
  font-size: 13px;
}
.i18n-task-detail__running {
  color: var(--el-color-primary);
  font-size: 13px;
}
</style>
