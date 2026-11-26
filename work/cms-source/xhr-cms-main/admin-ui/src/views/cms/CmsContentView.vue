<template>
  <section class="cms-page cms-content admin-responsive-page">
    <header class="cms-page__hero">
      <div class="cms-content__hero-copy"><p class="admin-eyebrow">内容管理</p><h1>内容工作台</h1><p>{{ currentMenu?.className || '请选择内容栏目' }}</p></div>
      <div class="cms-content__hero-actions admin-toolbar">
        <el-button size="small" :loading="loading" @click="load">刷新</el-button>
        <el-button size="small" type="primary" data-testid="native-create-button" :disabled="!menuId || !languageId" @click="openCreate">新增内容</el-button>
      </div>
    </header>
    <main class="cms-card cms-content__workspace">
      <el-alert v-if="error" :title="error" type="error" :closable="false" />
      <div v-if="menuId" class="cms-content__header admin-table-toolbar">
        <div class="cms-content__filter-heading">筛选条件</div>
        <el-select size="small" v-model="languageId" class="cms-content__filter-control cms-content__filter-control--language" data-testid="native-language-select" @change="onLanguageChanged">
          <el-option v-for="locale in locales" :key="locale.languageId" :label="`${locale.languageName}${locale.defaultLanguage ? '（默认）' : ''}`" :value="locale.languageId" />
        </el-select>
        <el-select size="small" v-model="statuses" class="cms-content__filter-control cms-content__filter-control--status" multiple clearable placeholder="工作流状态" @change="applyFilters">
          <el-option v-for="item in statusOptions" :key="item.value" :label="item.label" :value="item.value" />
        </el-select>
        <el-select size="small" v-model="variantState" class="cms-content__filter-control cms-content__filter-control--variant" clearable placeholder="翻译创建状态" data-testid="native-variant-state-filter" @change="applyFilters">
          <el-option label="未创建" value="MISSING" />
          <el-option label="已创建" value="EXISTS" />
        </el-select>
        <el-input size="small" v-model="keyword" class="cms-content__filter-control cms-content__filter-control--keyword" placeholder="按标题搜索" clearable @keyup.enter="applyFilters" />
        <el-button size="small" @click="applyFilters">查询</el-button>
      </div>
      <section v-if="!menuId" class="admin-empty-state"><h3>请从左侧内容管理栏目选择一个栏目</h3></section>
      <section v-else-if="!languageId" class="admin-empty-state"><h3>当前站点未启用内容语言</h3></section>
      <section v-else-if="!loading && !error && total === 0" class="admin-empty-state" role="status">
        <h3>{{ appliedFilters ? '没有符合筛选条件的内容' : '当前栏目在所选语言下暂无内容' }}</h3>
        <el-button v-if="appliedFilters" @click="resetFilters">清除筛选</el-button>
      </section>
      <ContentListPanel v-else :rows="rows" :loading="loading" :total="total" :page="page" :page-size="limit" :pending-review-count="pendingReviewCount" :list-fields="listFields" :menu-id="Number(menuId) || 0" @edit="openEdit" @delete="deleteRecord" @update:page="page = $event" @reload="load" />
    </main>

    <ContentEditorDrawer :visible="editorVisible" :title="editorTitle" :saving="saving" native-mode :fields="editorFields" :content-form="contentForm" :menu-id="Number(menuId) || 0" @update:visible="editorVisible = $event" @save="save" />

  </section>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue';
import { createTableLoader } from '@/utils/tableLoader';
import { useRoute } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import ContentEditorDrawer from './content/ContentEditorDrawer.vue';
import ContentListPanel from './content/ContentListPanel.vue';
import {
  listNativeContent, listNativeLocales, loadNativeEditor,
  saveNativeDraft, toContentEditorField, type ListFieldConfig, type NativeContentEditorDetail, type NativeContentRow, type NativeFieldDefinition,
  type NativeLocale, type NativeWorkflowStatus
} from '@/api/cms';
import { http } from '@/api/http';
import { flattenContentMenus, useSiteContextStore } from '@/stores/siteContext';

const route = useRoute();
const siteContext = useSiteContextStore();
const loading = ref(false); const saving = ref(false); const error = ref('');
const locales = ref<NativeLocale[]>([]); const rows = ref<NativeContentRow[]>([]); const listFields = ref<ListFieldConfig[]>([]); const total = ref(0);
const page = ref(1); const limit = 20; const keyword = ref(''); const statuses = ref<NativeWorkflowStatus[]>([]); const languageId = ref<number>();
const variantState = ref<'MISSING' | 'EXISTS'>();
const editorVisible = ref(false); const editor = ref<NativeContentEditorDetail>();
const contentForm = reactive({ title: '', sort: 0, seoTitle: '', seoKeywords: '', seoDescription: '', detailPageUrl: '', indexTop: false, indexElite: false, menuTop: false, menuElite: false, xgid: null as number | null, values: {} as Record<string, string | number | boolean | null>, relations: {} as Record<string, string> });

const menuId = computed(() => { const raw = Number(route.params.menuId); return Number.isSafeInteger(raw) && raw > 0 ? raw : undefined; });
const siteId = computed(() => {
  const value = Number(siteContext.currentSite?.id);
  return Number.isSafeInteger(value) && value > 0 ? value : undefined;
});
const currentMenu = computed(() => flattenContentMenus(siteContext.contentMenus).find((item) => item.id === menuId.value));
const editorFields = computed(() => (editor.value?.fields ?? []).map(toContentEditorField));
const editorTitle = computed(() => editor.value?.recordId ? '编辑内容' : '新增内容');
const pendingReviewCount = computed(() => rows.value.filter((row) => row.workflowStatus === 'PENDING_REVIEW').length);
const statusOptions = [{ label: '草稿', value: 'DRAFT' }, { label: '待审核', value: 'PENDING_REVIEW' }, { label: '已批准', value: 'APPROVED' }, { label: '已发布', value: 'PUBLISHED' }, { label: '需更新', value: 'REJECTED' }] as const;

const listLoader = createTableLoader(loading);
let bootstrapVersion = 0;

function clearList() {
  rows.value = [];
  total.value = 0;
}

watch([menuId, siteId], () => { page.value = 1; void bootstrap(); }, { immediate: true });

async function bootstrap() {
  const version = ++bootstrapVersion;
  listLoader.invalidate();
  clearList();
  listFields.value = [];
  const id = siteId.value;
  if (!menuId.value || !id) return;
  error.value = '';
  loading.value = true;
  let contentStarted = false;
  try {
    const data = await listNativeLocales(id);
    if (version !== bootstrapVersion) return;
    locales.value = data;
    const requestedLanguageId = Number(route.query.languageId);
    languageId.value = Number.isSafeInteger(requestedLanguageId) && locales.value.some((item) => item.languageId === requestedLanguageId)
      ? requestedLanguageId : languageId.value && locales.value.some((item) => item.languageId === languageId.value)
        ? languageId.value : locales.value.find((item) => item.defaultLanguage)?.languageId ?? locales.value[0]?.languageId;
    contentStarted = true;
    await load();
    if (version !== bootstrapVersion) return;
    await openRequestedRecord();
  } catch (cause) {
    if (version === bootstrapVersion) error.value = message(cause, '站点语言加载失败');
  } finally {
    if (version === bootstrapVersion && !contentStarted) loading.value = false;
  }
}
const appliedFilters = ref(false);
async function applyFilters() { page.value = 1; await load(); }
async function resetFilters() { keyword.value = ''; statuses.value = []; variantState.value = undefined; await applyFilters(); }
async function onLanguageChanged() { page.value = 1; await load(); }
async function load() {
  const request = listLoader.begin(clearList);
  error.value = '';
  try {
    if (!menuId.value || !languageId.value) return;
    const keywordAtRequest = keyword.value.trim(); const statusesAtRequest = statuses.value.length; const variantAtRequest = variantState.value;
    const data = await listNativeContent({ menuId: menuId.value, languageId: languageId.value, statuses: statuses.value, variantState: variantState.value, keyword: keyword.value.trim(), page: page.value, limit });
    if (!request.isCurrent()) return;
    appliedFilters.value = Boolean(keywordAtRequest || statusesAtRequest || variantAtRequest);
    rows.value = data.rows; total.value = data.total; listFields.value = data.listFields || [];
  } catch (cause) {
    if (!request.isCurrent()) return;
    clearList();
    listFields.value = [];
    error.value = message(cause, '原生内容列表加载失败');
  } finally {
    request.finish();
  }
}

async function openCreate() {
  try { await openEditor(undefined, true); } catch (cause) { error.value = message(cause, '内容编辑器加载失败'); }
}
async function openEdit(row: NativeContentRow) {
  try { await openEditor(row.recordId, true); } catch (cause) { error.value = message(cause, '原生内容详情加载失败'); }
}
async function openEditor(recordId?: number, showDrawer = true) {
  if (!menuId.value || !languageId.value) return;
  const data = await loadNativeEditor({ menuId: menuId.value, languageId: languageId.value, recordId });
  editor.value = data; contentForm.values = {}; contentForm.relations = {};
  for (const field of data.fields) contentForm.values[field.fieldKey] = formValue((field.translatable ? data.payload : data.sharedPayload)[field.fieldKey], field);
  editorVisible.value = showDrawer;
}
async function save() {
  if (!editor.value) return;
  saving.value = true;
  try {
    const sharedPayload: Record<string, unknown> = {}; const payload: Record<string, unknown> = {};
    for (const field of editor.value.fields) (field.translatable ? payload : sharedPayload)[field.fieldKey] = contentForm.values[field.fieldKey];
    await saveNativeDraft({ recordId: editor.value.recordId ?? undefined, menuId: editor.value.menuId, sharedPayload, payload });
    ElMessage.success('草稿已保存'); editorVisible.value = false; await load();
  } catch (cause) { error.value = message(cause, '原生内容保存失败'); }
  finally { saving.value = false; }
}
async function deleteRecord(row: NativeContentRow) {
  try {
    await ElMessageBox.confirm(`确认删除内容记录 ${row.title || row.recordId}？`, '删除内容', { type: 'warning' });
    await http.delete(`/api/admin/v2/cms/content/${menuId.value}/${row.recordId}`);
    ElMessage.success('内容已删除');
    await load();
  } catch (cause) {
    if (cause === 'cancel' || cause === 'close') return;
    error.value = message(cause, '内容删除失败');
  }
}
async function openRequestedRecord() {
  const recordId = Number(route.query.recordId);
  if (!Number.isSafeInteger(recordId) || recordId <= 0) return;
  try { await openEditor(recordId, true); }
  catch (cause) { error.value = message(cause, '目标原生内容不存在或不属于当前站点'); }
}
function formValue(value: unknown, field: NativeFieldDefinition): string | number | boolean | null { if (value === null || value === undefined) return defaultValue(field); if (typeof value === 'string' || typeof value === 'number' || typeof value === 'boolean') return value; return String(value); }
function defaultValue(field: NativeFieldDefinition): string | boolean | null {
  const type = field.fieldType.toLowerCase();
  if (['boolean', 'bool'].includes(type)) return false;
  if (['number', 'int', 'integer', 'decimal'].includes(type)) return null;
  return '';
}
function message(cause: unknown, fallback: string) { return cause instanceof Error ? cause.message : fallback; }
</script>

<style scoped>
.cms-page {
  display: grid;
  gap: var(--admin-card-gap);
  min-width: 0;
}

.cms-page__hero,
.cms-card {
  border: var(--admin-card-border);
  border-radius: var(--admin-card-radius);
  background: var(--admin-surface);
  box-shadow: var(--admin-shadow-card);
}

.cms-page__hero {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--admin-space-4);
  padding: var(--admin-card-padding);
}

.cms-content__hero-copy { min-width: 0; }
.cms-page__hero h1,
.cms-page__hero p { margin: 0; }
.cms-page__hero h1 { font-size: var(--admin-font-size-page); }
.cms-page__hero p { color: var(--admin-text-muted); }
.cms-content__hero-actions { flex-shrink: 0; }

.cms-content__workspace {
  display: flex;
  flex-direction: column;
  gap: var(--admin-space-4);
  min-width: 0;
  min-height: 0;
  padding: var(--admin-space-5);
}

.cms-content__header {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--admin-space-3);
  min-width: 0;
  padding: var(--admin-space-3) var(--admin-space-4);
  border: 1px solid var(--admin-border);
  border-radius: var(--admin-radius-md);
  background: var(--admin-bg-subtle);
}

.cms-content__filter-heading {
  color: var(--admin-text-muted);
  font-size: var(--admin-font-size-small);
  white-space: nowrap;
}

.cms-content__filter-control { flex: 0 1 auto; }
.cms-content__filter-control--language { width: 160px; }
.cms-content__filter-control--status { width: 220px; }
.cms-content__filter-control--variant { width: 160px; }
.cms-content__filter-control--keyword { width: min(280px, 100%); }

@media (max-width: 760px) {
  .cms-page__hero { flex-direction: column; padding: var(--admin-card-padding-compact); }
  .cms-content__workspace { padding: var(--admin-space-4); }
  .cms-content__hero-actions { width: 100%; }
  .cms-content__header { align-items: stretch; }
  .cms-content__filter-heading { flex-basis: 100%; }
  .cms-content__filter-control { width: 100%; }
}
</style>
