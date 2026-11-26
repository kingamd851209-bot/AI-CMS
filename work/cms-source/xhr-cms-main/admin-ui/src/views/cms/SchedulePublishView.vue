<script setup lang="ts">
import { createTableLoader } from '@/utils/tableLoader';
import { computed, nextTick, onMounted, ref, watch } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Plus, Refresh } from '@element-plus/icons-vue';
import {
  schedulePublishApi,
  siteCatalogApi,
  type SchedulePublishConfig,
  type ScheduleRunHistory
} from '@/api/cmsPlatform';
import { useSiteContextStore } from '@/stores/siteContext';

const siteContext = useSiteContextStore();
const siteId = computed(() => {
  const id = siteContext.currentSite?.id;
  return id ? Number(id) : 0;
});

const rows = ref<SchedulePublishConfig[]>([]);
const loading = ref(false);
const keyword = ref('');

interface ColNode {
  id: number;
  className: string;
  parentId: number;
  children?: ColNode[];
}
const columnTree = ref<ColNode[]>([]);

function loadColumnTree() {
  if (!siteId.value) {
    columnTree.value = [];
    return Promise.resolve();
  }
  return siteCatalogApi.columns(siteId.value).then((list) => {
    const map = new Map<number, ColNode>();
    for (const m of list) {
      map.set(m.id, { id: m.id, className: m.className, parentId: m.parentId, children: [] });
    }
    const roots: ColNode[] = [];
    for (const n of map.values()) {
      if (n.parentId === 0 || !map.has(n.parentId)) {
        roots.push(n);
      } else {
        map.get(n.parentId)!.children!.push(n);
      }
    }
    columnTree.value = roots;
  });
}

const loadLoader = createTableLoader(loading);

async function load() {
  if (!siteId.value) {
    loadLoader.invalidate();
    rows.value = [];
    return;
  }
  const request = loadLoader.begin(() => {
    rows.value = [];
  });
  try {
    const loadedRows = await schedulePublishApi.list(siteId.value, keyword.value || undefined);
    if (!request.isCurrent()) return;
    rows.value = loadedRows;
  } catch (error) {
    if (request.isCurrent()) throw error;
  } finally {
    request.finish();
  }
}

// ---------- 新增/编辑弹窗 ----------
const dialogVisible = ref(false);
const dialogTitle = ref('新增定时发布');
const editingId = ref<number | null>(null);
const treeRef = ref<any>(null);
const saving = ref(false);
const form = ref<{
  cronExpression: string;
  cronDescription: string;
  remark: string;
  enabled: number;
  columnIds: number[];
}>({ cronExpression: '', cronDescription: '', remark: '', enabled: 1, columnIds: [] });

const CRON_PRESETS = [
  { label: '每分钟', value: '* * * * *' },
  { label: '每小时', value: '0 * * * *' },
  { label: '每天 08:00', value: '0 8 * * *' },
  { label: '每天 18:00', value: '0 18 * * *' },
  { label: '每周一 08:00', value: '0 8 * * 1' },
  { label: '每月1日 08:00', value: '0 8 1 * *' }
];
const cronPreset = ref('');

function onPresetChange() {
  form.value.cronExpression = cronPreset.value;
  generateCronDesc();
}

function padTwo(n: string): string {
  const v = Number(n);
  return Number.isFinite(v) ? String(v).padStart(2, '0') : n;
}

function minText(min: string): string {
  if (min === '*') return '每分钟';
  if (min.startsWith('*/')) return `每 ${min.slice(2)} 分钟`;
  return `第 ${min} 分钟`;
}

function dowText(dow: string): string {
  const map: Record<string, string> = {
    '1': '周一', '2': '周二', '3': '周三', '4': '周四',
    '5': '周五', '6': '周六', '7': '周日', '0': '周日'
  };
  return dow.includes(',') ? dow.split(',').map(d => map[d.trim()] ?? d.trim()).join('、') : (map[dow] ?? dow);
}

function describeCron(expr: string): string {
  const parts = expr.trim().split(/\s+/);
  if (parts.length === 6) parts.shift();
  if (parts.length === 5) {
    const [min, hour, dom, mon, dow] = parts;
    const hh = hour === '*' ? '每时' : padTwo(hour);
    const mm = min === '*' ? '' : `:${padTwo(min)}`;
    if (min === '*' && hour === '*' && dom === '*' && mon === '*' && dow === '*') return '每分钟';
    if (hour === '*' && dom === '*' && mon === '*' && dow === '*') return `每小时${min === '*' ? '' : `的第 ${min} 分钟`}`;
    if (dom === '*' && mon === '*' && dow === '*') return `每天 ${hh}${mm}`;
    if (dom === '*' && mon === '*' && dow !== '*') return `每周 ${dowText(dow)} ${hh}${mm}`;
    if (mon === '*' && dow === '*') return `每月 ${dom.replace(/[*,\s]/g, '')} 日 ${hh}${mm}`;
    if (dom === '*' && dow === '*') return `每年 ${mon} 月 ${hh}${mm}`;
    return `${minText(min)} ${hh}${mm}`;
  }
  return '自定义表达式';
}

async function generateCronDesc() {
  const expr = form.value.cronExpression.trim();
  if (!expr) {
    ElMessage.warning('请先输入 cron 表达式');
    return;
  }
  const valid = await schedulePublishApi.validateCron(expr);
  if (!valid) {
    ElMessage.error('cron 表达式不合法');
    form.value.cronDescription = '';
    return;
  }
  form.value.cronDescription = describeCron(expr);
  ElMessage.success('已生成 cron 描述');
}

function openCreate() {
  editingId.value = null;
  form.value = { cronExpression: '', cronDescription: '', remark: '', enabled: 1, columnIds: [] };
  dialogTitle.value = '新增定时发布';
  dialogVisible.value = true;
  loadColumnTree();
  nextTick(() => treeRef.value?.setCheckedKeys([]));
}

async function openEdit(row: SchedulePublishConfig) {
  editingId.value = row.id ?? null;
  form.value = {
    cronExpression: row.cronExpression ?? '',
    cronDescription: row.cronDescription ?? '',
    remark: row.remark ?? '',
    enabled: row.enabled ?? 1,
    columnIds: parseColumnIds(row.columnIds)
  };
  dialogTitle.value = '编辑定时发布';
  dialogVisible.value = true;
  await loadColumnTree();
  nextTick(() => treeRef.value?.setCheckedKeys(form.value.columnIds));
}

function parseColumnIds(s?: string): number[] {
  if (!s) return [];
  try {
    const v = JSON.parse(s);
    return Array.isArray(v) ? v.map(Number) : [];
  } catch {
    return [];
  }
}

function checkedColumnNames(): string {
  const keys = (treeRef.value?.getCheckedKeys() ?? []).map(Number);
  const names: string[] = [];
  const walk = (nodes: ColNode[]) => {
    for (const n of nodes) {
      if (keys.includes(n.id)) names.push(n.className);
      if (n.children) walk(n.children);
    }
  };
  walk(columnTree.value);
  return names.join('、');
}

async function save() {
  if (!siteId.value) {
    ElMessage.warning('请先选择站点');
    return;
  }
  if (!form.value.cronExpression.trim()) {
    ElMessage.warning('请输入 cron 表达式');
    return;
  }
  if (!form.value.cronDescription.trim()) {
    ElMessage.warning('请先生成 cron 描述');
    return;
  }
  const keys = (treeRef.value?.getCheckedKeys() ?? []).map(Number);
  if (!keys.length) {
    ElMessage.warning('请选择栏目范围');
    return;
  }
  const payload = {
    cronExpression: form.value.cronExpression.trim(),
    cronDescription: form.value.cronDescription.trim(),
    columnIds: JSON.stringify(keys),
    columnNames: checkedColumnNames().slice(0, 1024),
    remark: form.value.remark,
    enabled: form.value.enabled
  };
  saving.value = true;
  try {
    if (editingId.value) {
      await schedulePublishApi.update(editingId.value, payload);
    } else {
      await schedulePublishApi.create(siteId.value, payload);
    }
    ElMessage.success(editingId.value ? '已更新' : '已新增');
    dialogVisible.value = false;
    await load();
  } catch (e) {
    ElMessage.error(`保存失败：${(e as Error).message ?? String(e)}`);
  } finally {
    saving.value = false;
  }
}

async function toggleEnabled(row: SchedulePublishConfig) {
  const next = row.enabled ? 0 : 1;
  const ok = await schedulePublishApi.toggleEnabled(row.id!, next);
  if (ok) {
    row.enabled = next;
    ElMessage.success(next ? '已启用' : '已停用');
  } else {
    ElMessage.error('操作失败');
  }
}

async function removeRow(row: SchedulePublishConfig) {
  await ElMessageBox.confirm(`确认删除该条定时发布配置？删除后不可恢复。`, '删除确认', { type: 'warning' });
  await schedulePublishApi.remove(row.id!);
  ElMessage.success('已删除');
  await load();
}

// ---------- 发布历史弹窗 ----------
const historyVisible = ref(false);
const historyRows = ref<ScheduleRunHistory[]>([]);
const historyLoading = ref(false);
const historyTitle = ref('发布历史');

const openHistoryLoader = createTableLoader(historyLoading);

async function openHistory(row: SchedulePublishConfig) {
  historyTitle.value = `发布历史 - ${row.cronDescription || row.cronExpression || ''}`;
  historyVisible.value = true;
  const request = openHistoryLoader.begin(() => {
    historyRows.value = [];
  });
  try {
    const loadedRows = await schedulePublishApi.history(row.id!);
    if (!request.isCurrent()) return;
    historyRows.value = loadedRows;
  } catch (error) {
    if (request.isCurrent()) throw error;
  } finally {
    request.finish();
  }
}

watch(siteId, () => {
  openHistoryLoader.invalidate();
  historyRows.value = [];
  historyVisible.value = false;
  void load();
});

onMounted(load);
</script>

<template>
  <section class="cms-page schedule-publish">
    <header class="cms-page__hero">
      <div>
        <p class="admin-eyebrow">站点内容</p>
        <h1>定时发布</h1>
        <p>为当前站点配置定时发布计划、cron 表达式与发布栏目范围。</p>
      </div>
    </header>

    <el-empty
      v-if="!siteContext.currentSite"
      description="请先选择站点，再维护定时发布配置"
    />

    <template v-else>
      <div class="schedule-publish__filters admin-table-toolbar">
        <el-input size="small"
          v-model="keyword"
          placeholder="搜索 cron / 描述 / 备注"
          clearable
          style="width: 300px"
          @keyup.enter="load"
        />
        <el-button size="small" type="primary" @click="load">查询</el-button>
        <div class="schedule-publish__filters-actions">
          <el-button size="small" :loading="loading" :icon="Refresh" @click="load">刷新</el-button>
          <el-button size="small" v-if="siteId" type="primary" :icon="Plus" @click="openCreate">新增</el-button>
        </div>
      </div>

      <el-table element-loading-text="正在加载数据…" v-loading="loading" :data="rows" stripe>
        <el-table-column prop="cronExpression" label="cron 表达式" min-width="140">
          <template #default="{ row }">
            <code class="schedule-publish__cron">{{ row.cronExpression }}</code>
          </template>
        </el-table-column>
        <el-table-column prop="cronDescription" label="cron 描述" min-width="160" show-overflow-tooltip />
        <el-table-column prop="columnNames" label="发布的栏目范围" min-width="220" show-overflow-tooltip />
        <el-table-column prop="remark" label="备注" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">{{ row.remark || '-' }}</template>
        </el-table-column>
        <el-table-column label="生效状态" width="110">
          <template #default="{ row }">
            <el-switch
              :model-value="row.enabled === 1"
              inline-prompt
              active-text="开启"
              inactive-text="关闭"
              @change="toggleEnabled(row)"
            />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button size="small" text type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button size="small" text type="primary" @click="openHistory(row)">发布历史</el-button>
            <el-button size="small" text type="danger" @click="removeRow(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </template>

    <!-- 新增 / 编辑弹窗 -->
    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="760px" destroy-on-close>
      <el-form label-width="110px">
        <el-form-item label="接收站点">
          <el-input :model-value="siteContext.currentSite?.name ?? ''" disabled />
        </el-form-item>
        <el-form-item label="栏目范围" required>
          <div v-loading="!columnTree.length" class="schedule-publish__tree">
            <el-tree
              ref="treeRef"
              :data="columnTree"
              show-checkbox
              :props="{ children: 'children', label: 'className' }"
              node-key="id"
              default-expand-all
            />
          </div>
        </el-form-item>
        <el-form-item label="cron 表达式" required>
          <div class="schedule-publish__cron-row">
            <el-input v-model="form.cronExpression" placeholder="如 0 8 * * 1" style="width: 220px" />
            <el-select v-model="cronPreset" placeholder="便捷设置" clearable style="width: 180px" @change="onPresetChange">
              <el-option v-for="p in CRON_PRESETS" :key="p.value" :label="p.label" :value="p.value" />
            </el-select>
            <el-button @click="generateCronDesc">生成 cron 描述</el-button>
          </div>
        </el-form-item>
        <el-form-item label="cron 描述" required>
          <el-input v-model="form.cronDescription" placeholder="生成的描述将展示在列表" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" type="textarea" :rows="2" placeholder="选填" />
        </el-form-item>
        <el-form-item label="生效状态">
          <el-switch v-model="form.enabled" :active-value="1" :inactive-value="0" active-text="开启" inactive-text="关闭" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <!-- 发布历史弹窗 -->
    <el-dialog v-model="historyVisible" :title="historyTitle" width="720px">
      <el-table element-loading-text="正在加载数据…" v-loading="historyLoading" :data="historyRows" stripe empty-text="暂无执行记录（待调度执行接入后写入）">
        <el-table-column prop="runTime" label="执行时间" width="180" />
        <el-table-column label="状态" width="120">
          <template #default="{ row }">
            <el-tag :type="row.status === 'success' ? 'success' : row.status === 'failed' ? 'danger' : 'info'" size="small">
              {{ row.status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="message" label="执行信息" min-width="220" show-overflow-tooltip />
      </el-table>
    </el-dialog>
  </section>
</template>

<style scoped>
.schedule-publish__filters {
  display: flex;
  gap: 10px;
  align-items: center;
  margin-bottom: 14px;
}

.schedule-publish__filters-actions {
  display: flex;
  gap: 10px;
  align-items: center;
  margin-left: auto;
}

.schedule-publish__cron {
  font-family: Consolas, Monaco, monospace;
  background: var(--admin-bg-surface);
  border: 1px solid var(--admin-border-subtle);
  border-radius: 4px;
  padding: 2px 6px;
}

.schedule-publish__tree {
  width: 100%;
  max-height: 260px;
  overflow: auto;
  border: 1px solid var(--admin-border-subtle);
  border-radius: 6px;
  padding: 8px;
}

.schedule-publish__cron-row {
  display: flex;
  gap: 8px;
  align-items: center;
  flex-wrap: wrap;
}
</style>
