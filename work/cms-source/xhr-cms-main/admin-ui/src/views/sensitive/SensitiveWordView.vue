<template>
  <section class="sensitive-page">
    <header class="sensitive-page__header">
      <h1>敏感词管理</h1>
      <div class="sensitive-page__actions">
        <el-button size="small" :loading="loading" @click="loadList">刷新</el-button>
        <el-button size="small" type="primary" @click="openEdit()">新增</el-button>
      </div>
    </header>

    <div class="sensitive-page__filters admin-table-toolbar">
      <el-select size="small"
        v-model="query.site"
        multiple
        collapse-tags
        collapse-tags-tooltip
        placeholder="应用站点"
        clearable
        style="width: 220px"
      >
        <el-option value="global" label="全局" />
        <el-option v-for="site in sites" :key="String(site.id)" :label="site.name" :value="String(site.id)" />
      </el-select>
      <el-input size="small" v-model="query.word" placeholder="敏感词" clearable style="width: 160px" />
      <el-select size="small" v-model="query.action" placeholder="行动" clearable style="width: 130px">
        <el-option value="reject" label="拒绝" />
        <el-option value="alert" label="告警" />
        <el-option value="replace" label="替换" />
        <el-option value="select_replace" label="选择替换" />
      </el-select>
      <el-select size="small" v-model="query.status" placeholder="状态" clearable style="width: 110px">
        <el-option :value="1" label="启用" />
        <el-option :value="0" label="禁用" />
      </el-select>
      <el-select size="small" v-model="query.level" placeholder="级别" clearable style="width: 110px">
        <el-option :value="1" label="低" />
        <el-option :value="2" label="中" />
        <el-option :value="3" label="高" />
      </el-select>
      <el-button size="small" @click="resetQuery">重置</el-button>
    </div>

    <el-table element-loading-text="正在加载数据…" :data="filteredList" stripe border v-loading="loading">
      <el-table-column prop="id" label="ID" width="80" align="center" />
      <el-table-column prop="word" label="敏感词" min-width="120" />
      <el-table-column label="行动" width="100" align="center">
        <template #default="{ row }">
          {{ actionLabel(row.action) }}
        </template>
      </el-table-column>
      <el-table-column prop="replacement" label="替换词" min-width="120">
        <template #default="{ row }">
          <span v-if="row.action === 'replace' || row.action === 'select_replace'">{{ row.replacement }}</span>
          <span v-else class="text-muted">-</span>
        </template>
      </el-table-column>
      <el-table-column label="敏感级别" width="100" align="center">
        <template #default="{ row }">
          <el-tag :type="levelType(row.level)">{{ levelLabel(row.level) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="应用站点" min-width="160">
        <template #default="{ row }">
          <el-tag v-if="row.scope === 'global'" type="info" size="small">全局</el-tag>
          <span v-else>{{ row.siteNames || '-' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="100" align="center">
        <template #default="{ row }">
          <el-tag :type="row.status === 1 ? 'success' : 'danger'">
            {{ row.status === 1 ? '启用' : '禁用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="createTime" label="创建时间" width="180" />
      <el-table-column label="操作" width="150" fixed="right">
        <template #default="{ row }">
          <el-button size="small" link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button size="small" link type="danger" @click="doDelete(row.id)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-drawer v-model="editVisible" :title="editId ? '编辑' : '新增'" direction="rtl" size="500px" :close-on-click-modal="false">
      <el-form label-width="90px" class="sensitive-form">
        <el-form-item label="应用站点" required>
          <el-radio-group v-model="editForm.scope" style="margin-bottom: 8px">
            <el-radio value="global">全局</el-radio>
            <el-radio value="site">指定站点</el-radio>
          </el-radio-group>
          <el-select
            v-if="editForm.scope === 'site'"
            v-model="editForm.siteIds"
            multiple
            filterable
            placeholder="请选择站点（可多选）"
            style="width: 100%"
          >
            <el-option v-for="site in sites" :key="String(site.id)" :label="site.name" :value="String(site.id)" />
          </el-select>
          <div v-else class="sensitive-form__global-hint">所有站点与内容中台均应用该规则</div>
        </el-form-item>
        <el-form-item label="敏感词" required>
          <el-input v-model="editForm.word" maxlength="128" show-word-limit />
        </el-form-item>
        <el-form-item label="行动" required>
          <el-select v-model="editForm.action" placeholder="请选择行动" style="width: 100%">
            <el-option value="reject" label="拒绝" />
            <el-option value="alert" label="告警" />
            <el-option value="replace" label="替换" />
            <el-option value="select_replace" label="选择替换" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="showReplacement" label="替换词" required>
          <el-input v-model="editForm.replacement" maxlength="128" show-word-limit placeholder="请输入替换词" />
        </el-form-item>
        <el-form-item label="敏感级别">
          <el-radio-group v-model="editForm.level">
            <el-radio :value="1">低</el-radio>
            <el-radio :value="2">中</el-radio>
            <el-radio :value="3">高</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="editForm.remark" type="textarea" :rows="3" maxlength="512" show-word-limit />
        </el-form-item>
        <el-form-item label="状态">
          <el-radio-group v-model="editForm.status">
            <el-radio :value="1">启用</el-radio>
            <el-radio :value="0">禁用</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" @click="doSave">保存</el-button>
      </template>
    </el-drawer>
  </section>
</template>

<script setup lang="ts">
import { createTableLoader } from '@/utils/tableLoader';
import { ref, onMounted, computed, reactive } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { http } from '@/api/http';
import { useSiteContextStore } from '@/stores/siteContext';

const siteContext = useSiteContextStore();
const loading = ref(false);
const list = ref<any[]>([]);
const editVisible = ref(false);
const editId = ref<number | null>(null);

const query = reactive({
  site: [] as string[],
  word: '',
  action: '',
  status: null as number | null,
  level: null as number | null
});

const editForm = ref({
  word: '',
  action: 'reject' as 'reject' | 'alert' | 'replace' | 'select_replace',
  replacement: '',
  level: 1,
  remark: '',
  scope: 'site' as 'global' | 'site',
  siteIds: [] as string[],
  status: 1
});

const sites = computed(() => siteContext.sites);

const showReplacement = computed(() => {
  return editForm.value.action === 'replace' || editForm.value.action === 'select_replace';
});

const filteredList = computed(() => {
  return list.value.filter((row) => {
    if (query.site.length) {
      const hasGlobal = query.site.includes('global') && row.scope === 'global';
      const rowSiteIds = String(row.siteIds ?? '').split(',').map((s) => s.trim()).filter(Boolean);
      const hasSite = query.site.some((s) => s !== 'global' && rowSiteIds.includes(s));
      if (!hasGlobal && !hasSite) return false;
    }
    if (query.word && !String(row.word ?? '').includes(query.word)) return false;
    if (query.action && row.action !== query.action) return false;
    if (query.status != null && row.status !== query.status) return false;
    if (query.level != null && row.level !== query.level) return false;
    return true;
  });
});

onMounted(() => loadList());

const loadListLoader = createTableLoader(loading);

async function loadList() {
  const request = loadListLoader.begin(() => {
    list.value = [];
  });
  try {
    const loadedRows = await http.get<typeof list.value>('/api/admin/v2/sensitive-word');
    if (!request.isCurrent()) return;
    list.value = loadedRows;
  } catch (error) {
    if (request.isCurrent()) throw error;
  } finally {
    request.finish();
  }
}

function resetQuery() {
  query.site = [];
  query.word = '';
  query.action = '';
  query.status = null;
  query.level = null;
}

function openEdit(row?: any) {
  editId.value = row?.id ?? null;
  editForm.value = {
    word: row?.word ?? '',
    action: row?.action ?? 'reject',
    replacement: row?.replacement ?? '',
    level: row?.level ?? 1,
    remark: row?.remark ?? '',
    scope: row?.scope === 'global' ? 'global' : 'site',
    siteIds: String(row?.siteIds ?? '').split(',').map((s: string) => s.trim()).filter(Boolean),
    status: row?.status ?? 1
  };
  editVisible.value = true;
}

async function doSave() {
  if (editForm.value.scope === 'site' && editForm.value.siteIds.length === 0) {
    ElMessage.warning('请选择应用站点');
    return;
  }
  if (!editForm.value.word?.trim()) {
    ElMessage.warning('请输入敏感词');
    return;
  }
  if (showReplacement.value && !editForm.value.replacement?.trim()) {
    ElMessage.warning('请输入替换词');
    return;
  }
  const payload = {
    word: editForm.value.word,
    action: editForm.value.action,
    replacement: editForm.value.replacement,
    level: editForm.value.level,
    remark: editForm.value.remark,
    scope: editForm.value.scope,
    siteIds: editForm.value.scope === 'global' ? null : editForm.value.siteIds.join(','),
    status: editForm.value.status
  };
  try {
    if (editId.value) {
      await http.put(`/api/admin/v2/sensitive-word/${editId.value}`, payload);
    } else {
      await http.post('/api/admin/v2/sensitive-word', payload);
    }
    ElMessage.success('保存成功');
    editVisible.value = false;
    loadList();
  } catch (e: any) {
    ElMessage.error(e.message);
  }
}

async function doDelete(id: number) {
  await ElMessageBox.confirm('确定删除该敏感词？', '提示', { type: 'warning' });
  await http.delete(`/api/admin/v2/sensitive-word/${id}`);
  ElMessage.success('删除成功');
  loadList();
}

function levelLabel(level: number): string {
  const map: Record<number, string> = { 1: '低', 2: '中', 3: '高' };
  return map[level] || '低';
}

function levelType(level: number): string {
  const map: Record<number, string> = { 1: 'info', 2: 'warning', 3: 'danger' };
  return map[level] || 'info';
}

function actionLabel(action: string): string {
  const map: Record<string, string> = {
    reject: '拒绝',
    alert: '告警',
    replace: '替换',
    select_replace: '选择替换'
  };
  return map[action] || action;
}
</script>

<style scoped>
.sensitive-page { padding: 0; }
.sensitive-page__header { display: flex; justify-content: space-between; align-items: center; margin-bottom: var(--admin-card-gap); }
.sensitive-page__header h1 { margin: 0; font-size: 20px; }
.sensitive-page__actions { display: flex; gap: 12px; }
.sensitive-page__filters { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; margin-bottom: 16px; }
.sensitive-form { padding: 0 10px; }
.sensitive-form__global-hint { font-size: 12px; color: var(--el-text-color-secondary); }
.text-muted { color: var(--el-text-color-placeholder); }
</style>
