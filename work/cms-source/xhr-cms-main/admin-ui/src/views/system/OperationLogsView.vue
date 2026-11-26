<template>
  <section class="work-page">
    <header class="work-hero">
      <div>
        <p class="admin-eyebrow">系统管理</p>
        <h1>操作日志</h1>
        <p>查询内容与栏目的操作记录，并查看变更明细和入参数据。</p>
      </div>
      <el-button size="small" :loading="loading" @click="loadLogs">刷新</el-button>
    </header>

    <section class="work-card">
      <div class="filters admin-table-toolbar">
        <el-input size="small" v-model="query.key" placeholder="按动作搜索" clearable @keyup.enter="loadLogs" />
        <el-date-picker size="small" v-model="range" type="daterange" start-placeholder="开始日期" end-placeholder="结束日期" value-format="YYYY-MM-DD" />
        <el-button size="small" type="primary" @click="loadLogs">查询</el-button>
      </div>

      <el-table element-loading-text="正在加载数据…" v-loading="loading" :data="rows" stripe>
        <el-table-column prop="id" label="ID" width="90" />
        <el-table-column prop="action" label="动作" min-width="220" />
        <el-table-column prop="userName" label="操作人" width="130" />
        <el-table-column prop="classId" label="栏目ID" width="110" />
        <el-table-column prop="infoId" label="数据ID" width="110" />
        <el-table-column prop="ip" label="IP" width="140" />
        <el-table-column label="时间" width="180">
          <template #default="{ row }">{{ formatTime(row.created) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="100" fixed="right">
          <template #default="{ row }">
            <el-button size="small" @click="openDetail(row.id)">详情</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="pager">
        <el-pagination v-if="!loading" v-model:current-page="query.page" v-model:page-size="query.pageSize" :total="total" :page-sizes="[20, 50, 100]" layout="total, sizes, prev, pager, next" @change="loadLogs" />
      </div>
    </section>

    <el-dialog v-model="detailVisible" title="操作日志详情" width="760px">
      <el-descriptions v-if="detail" :column="2" border>
        <el-descriptions-item label="动作" :span="2">{{ detail.action }}</el-descriptions-item>
        <el-descriptions-item label="操作人">{{ detail.userName }}</el-descriptions-item>
        <el-descriptions-item label="IP">{{ detail.ip }}</el-descriptions-item>
        <el-descriptions-item label="栏目ID" :span="2">{{ detail.classId }}</el-descriptions-item>
        <el-descriptions-item label="数据ID" :span="2">{{ detail.infoId }}</el-descriptions-item>
        <el-descriptions-item label="时间" :span="2">{{ formatTime(detail.created) }}</el-descriptions-item>
        <el-descriptions-item v-if="detail.requestData" label="入参数据" :span="2">
          <pre>{{ detail.requestData }}</pre>
        </el-descriptions-item>
        <el-descriptions-item v-if="detail.responseData" label="变更明细" :span="2">
          <div v-html="detail.responseData" class="detail-html" />
        </el-descriptions-item>
      </el-descriptions>
    </el-dialog>
  </section>
</template>

<script setup lang="ts">
import { createTableLoader } from '@/utils/tableLoader';
import { onMounted, reactive, ref } from 'vue';
import { ElMessage } from 'element-plus';
import { http } from '@/api/http';

interface OperationLogRow {
  id: number;
  action: string;
  requestData?: string;
  responseData?: string;
  userName?: string;
  ip?: string;
  created?: string;
  classId?: number;
  infoId?: number;
}
interface OperationLogList { rows: OperationLogRow[]; total: number }

const loading = ref(false);
const rows = ref<OperationLogRow[]>([]);
const total = ref(0);
const range = ref<[string, string] | null>(null);
const detail = ref<OperationLogRow | null>(null);
const detailVisible = ref(false);
const query = reactive({ key: '', page: 1, pageSize: 20 });

onMounted(() => {
  void loadLogs();
});

const loadLogsLoader = createTableLoader(loading);

async function loadLogs() {
  const request = loadLogsLoader.begin(() => {
    rows.value = [];
    total.value = 0;
  });
  try {
    const params = new URLSearchParams({ page: String(query.page), limit: String(query.pageSize) });
    if (query.key.trim()) params.set('key', query.key.trim());
    if (range.value?.[0]) params.set('beginTime', range.value[0]);
    if (range.value?.[1]) params.set('endTime', range.value[1]);
    const data = await http.get<OperationLogList>(`/api/admin/v2/system/operation-log-list?${params.toString()}`);
    if (!request.isCurrent()) return;
    rows.value = data.rows ?? [];
    total.value = data.total ?? 0;
  } catch (error) {
    if (!request.isCurrent()) return;
    ElMessage.error(errorMessage(error, '操作日志加载失败'));
  } finally {
    request.finish();
  }
}

async function openDetail(id: number) {
  try {
    detail.value = await http.get<OperationLogRow>(`/api/admin/v2/system/operation-logs/${id}`);
    detailVisible.value = true;
  } catch (error) {
    detail.value = null;
    detailVisible.value = false;
    ElMessage.error(errorMessage(error, '操作日志详情加载失败'));
  }
}

function formatTime(value?: string) {
  if (!value) return '';
  return value.replace('T', ' ').slice(0, 19);
}

function errorMessage(error: unknown, fallback: string) {
  return error instanceof Error ? error.message : fallback;
}
</script>

<style scoped>
.work-page { display: grid; gap: var(--admin-card-gap); }
.work-hero, .work-card { border: var(--admin-card-border); border-radius: var(--admin-card-radius); background: var(--admin-bg-surface); box-shadow: var(--admin-shadow-md); }
.work-hero { display: flex; justify-content: space-between; gap: 20px; padding: var(--admin-card-padding-roomy); }
.work-hero h1, .work-hero p { margin: 0; }
.work-hero p { color: var(--admin-text-muted); }
.work-card { padding: var(--admin-card-padding); }
.filters { display: grid; grid-template-columns: minmax(180px, 1fr) 320px auto; gap: 12px; margin-bottom: 18px; }
.pager { display: flex; justify-content: flex-end; margin-top: 16px; }
pre { max-height: 240px; overflow: auto; white-space: pre-wrap; word-break: break-word; }
.detail-html :deep(table) { width: 100%; border-collapse: collapse; font-size: 13px; }
.detail-html :deep(th), .detail-html :deep(td) { border: 1px solid var(--admin-border-subtle); padding: 8px; text-align: left; }
.detail-html :deep(th) { background: var(--admin-bg-page); font-weight: 600; }
@media (max-width: 1180px) { .filters { grid-template-columns: 1fr; } }
</style>