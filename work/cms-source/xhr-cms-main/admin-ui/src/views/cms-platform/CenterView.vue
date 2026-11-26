<script setup lang="ts">
import { createTableLoader } from '@/utils/tableLoader';
import { computed, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import {
  caseCategoryApi,
  caseCenterApi,
  newsCategoryApi,
  newsCenterApi,
  platformAuditApi,
  platformPushApi,
  productCategoryApi,
  productCenterApi,
  siteCatalogApi,
  solutionCategoryApi,
  solutionCenterApi,
  type CategoryNode,
  type ContentListResult,
  type PlatformPushConfig,
  type PlatformPushRecord
} from '@/api/cmsPlatform';
import { staticAssetApi } from '@/api/staticAssets';
import {
  parseModelField,
  dataTypeOfConfigType,
  isCategoryInputType,
  isContentInputType,
  needsCategoryScope,
  packMultiValue,
  supportsMultipleChoice,
  subtreeIdsOf,
  unpackMultiValue,
  validateFieldValue,
  type ModelField
} from './modelField';
import CmsRichTextField from '@/views/cms/content/CmsRichTextField.vue';
import StaticAssetPickerDialog from './StaticAssetPickerDialog.vue';
import { checkSensitiveText, applySensitiveWordFix, isFixableHit, isRejectHit, formatSensitiveWordHit, type SensitiveWordHit } from '@/utils/sensitiveWord';

interface CategoryApi {
  all: () => Promise<CategoryNode[]>;
  detail: (id: number) => Promise<CategoryNode>;
}

interface CenterApi {
  contents: (classId: number, params: { page?: number; size?: number; status?: number; keyword?: string }) => Promise<ContentListResult>;
  detail: (id: number) => Promise<Record<string, unknown>>;
  create: (data: Record<string, unknown>) => Promise<Record<string, unknown>>;
  update: (id: number, data: Record<string, unknown>) => Promise<boolean>;
  remove: (id: number) => Promise<boolean>;
  batchOption: (ids: number[], option: string, classId?: number, status?: number, note?: string) => Promise<number>;
}

interface PushConfigApi {
  list: (categoryId: number) => Promise<PlatformPushConfig[]>;
}

const props = defineProps<{
  categoryApi: CategoryApi;
  centerApi: CenterApi;
  pushConfigApi?: PushConfigApi;
  moduleTitle: string;
  type: 'product' | 'news' | 'form' | 'solution' | 'case';
  moduleDescription?: string;
}>();

/** 表单模块：不展示发布/草稿/置顶/推荐 */
const isFormType = computed(() => props.type === 'form');

const STATUS_OPTIONS = [
  { label: '全部', value: '' },
  { label: '草稿', value: 0 },
  { label: '待审核', value: 1 },
  { label: '已审核', value: 2 },
  { label: '已发布', value: 3 }
];

/** 系统字段：标题(title)已单独渲染、其余由工作流/系统控制，不重复渲染 */
const SYSTEM_FIELD_NAMES = ['title', 'tags', 'hits', 'sort', 'status'];

const statusLabel = (s: unknown): string => {
  switch (Number(s)) {
    case 0: return '草稿';
    case 1: return '待审核';
    case 2: return '已审核';
    case 3: return '已发布';
    default: return '未知';
  }
};

const statusTagType = (s: unknown): string => {
  switch (Number(s)) {
    case 3: return 'success';
    case 2: return 'primary';
    case 1: return 'warning';
    default: return 'info';
  }
};

const loading = ref(false);
const listLoading = ref(false);
const menus = ref<CategoryNode[]>([]);
const selectedId = ref<number | null>(null);
const selectedCategory = ref<CategoryNode | null>(null);
const modelColumns = ref<ModelField[]>([]);
const modelSearch = ref<ModelField[]>([]);
const modelFields = ref<ModelField[]>([]);
const searchForm = ref<Record<string, string | string[]>>({});

const rows = ref<Record<string, unknown>[]>([]);
const total = ref(0);
const page = ref(1);
const size = ref(20);
const filterStatus = ref<'' | number>('');
const keyword = ref('');
const selectedRows = ref<number[]>([]);

const editorVisible = ref(false);
const editorSaving = ref(false);
const editingId = ref<number | null>(null);
const form = ref<Record<string, unknown>>({});

/** 各字段敏感词命中结果：fieldName -> hits */
const sensitiveHits = ref<Record<string, SensitiveWordHit[]>>({});

interface TreeData { id: number; className: string; parentId: number; children: TreeData[] }

const treeData = computed<TreeData[]>(() => {
  const map = new Map<number, TreeData>();
  for (const m of menus.value) {
    map.set(m.id, { id: m.id, className: m.className, parentId: m.parentId, children: [] });
  }
  const roots: TreeData[] = [];
  for (const node of map.values()) {
    if (node.parentId === 0 || !map.has(node.parentId)) {
      roots.push(node);
    } else {
      map.get(node.parentId)!.children.push(node);
    }
  }
  return roots;
});

const treeProps = { children: 'children', label: 'className' };

async function loadMenus() {
  menus.value = await props.categoryApi.all();
}

let selectionVersion = 0;

async function selectMenu(node: TreeData, presetFilter?: { field: string; value: string }) {
  const version = ++selectionVersion;
  loadContentsLoader.invalidate();
  rows.value = [];
  total.value = 0;
  selectedRows.value = [];
  modelColumns.value = [];
  modelSearch.value = [];
  modelFields.value = [];
  selectedCategory.value = null;
  listLoading.value = true;
  selectedId.value = node.id;
  let contentStarted = false;
  try {
    const category = await props.categoryApi.detail(node.id);
    if (version !== selectionVersion) return;
    selectedCategory.value = category;
    modelColumns.value = parseModelField(typeof selectedCategory.value?.modelList === 'string' ? selectedCategory.value.modelList : null);
    modelSearch.value = parseModelField(typeof selectedCategory.value?.modelSearch === 'string' ? selectedCategory.value.modelSearch : null);
    modelFields.value = parseModelField(typeof selectedCategory.value?.modelField === 'string' ? selectedCategory.value.modelField : null);
    const form: Record<string, string | string[]> = {};
    modelSearch.value
      .filter(f => ['text', 'textarea', 'number'].includes(f.fieldType))
      .forEach(f => { form[f.fieldName] = f.inputMultiple ? [] : ''; });
    searchForm.value = form;
    if (presetFilter && presetFilter.field) {
      searchForm.value[presetFilter.field] = presetFilter.value;
    }
    page.value = 1;
    void preloadFieldReferences();
    contentStarted = true;
    await loadContents();
  } catch (error) {
    if (version === selectionVersion) ElMessage.error(error instanceof Error ? error.message : '分类加载失败');
  } finally {
    if (version === selectionVersion && !contentStarted) listLoading.value = false;
  }
}

function onTreeNodeClick(node: TreeData) {
  void selectMenu(node);
}

const route = useRoute();
const router = useRouter();

/** 当前栏目的子栏目跳转列：子栏目开启「父栏目跳转列」时，在父栏目内容列表里显示 */
const jumpColumns = computed<CategoryNode[]>(() => {
  if (selectedId.value == null) return [];
  return menus.value.filter(m => Number(m.parentId) === selectedId.value && m.isSubMenu === true);
});

/** 点击跳转列：切到子栏目，并用本行内容 id 预置筛选值 */
function gotoSubMenu(sub: CategoryNode, row: Record<string, unknown>) {
  const query: Record<string, string> = { menuId: String(sub.id) };
  const field = String(sub.parentListFilterField ?? '');
  if (field) {
    query.filterField = field;
    query.filterValue = String(row.id ?? '');
  }
  void router.push({ path: route.path, query });
}

/** 从跳转列进入的二级栏目：返回上级时回到父栏目列表 */
const jumpReturnTarget = computed<number | null>(() => {
  if (!route.query.filterField || !route.query.filterValue) return null;
  const parentId = Number(selectedCategory.value?.parentId ?? 0);
  return parentId > 0 ? parentId : null;
});

function backToParent() {
  if (jumpReturnTarget.value == null) return;
  void router.push({ path: route.path, query: { menuId: String(jumpReturnTarget.value) } });
}

const editableFields = computed<ModelField[]>(() =>
  modelFields.value.filter(f => f.enabled && !SYSTEM_FIELD_NAMES.includes(f.fieldName))
);

/** 按 modelSearch 渲染的动态搜索字段 */
const searchFields = computed<ModelField[]>(() =>
  modelSearch.value.filter(f => ['text', 'textarea', 'number'].includes(f.fieldType))
);

/** 列表列：优先 modelList，未配置时退回 modelField 中启用的字段 */
const listColumns = computed<ModelField[]>(() => {
  if (modelColumns.value.length > 0) return modelColumns.value;
  return modelFields.value.filter(f => f.enabled);
});

function displayableColumn(field: ModelField): boolean {
  return !['title', 'sort', 'hits', 'status', 'classid'].includes(field.fieldName);
}

const loadContentsLoader = createTableLoader(listLoading);

async function loadContents() {
  if (selectedId.value == null) {
    loadContentsLoader.invalidate();
    return;
  }
  const request = loadContentsLoader.begin(() => {
    rows.value = [];
    total.value = 0;
    selectedRows.value = [];
  });
  try {
    const filters: Record<string, unknown> = {};
    for (const [k, v] of Object.entries(searchForm.value)) {
      if (Array.isArray(v)) {
        if (v.length) filters[k] = packMultiValue(v);
      } else if (v) {
        filters[k] = v;
      }
    }
    const result = await props.centerApi.contents(selectedId.value, {
      page: page.value,
      size: size.value,
      status: filterStatus.value === '' ? undefined : filterStatus.value,
      keyword: keyword.value || undefined,
      ...filters
    });
    if (!request.isCurrent()) return;
    rows.value = result.list ?? [];
    total.value = result.total ?? 0;
  } catch (error) {
    if (request.isCurrent()) throw error;
  } finally {
    request.finish();
  }
}

function onPageChange(p: number) {
  page.value = p;
  loadContents();
}

function refreshSearch() {
  page.value = 1;
  loadContents();
}

function resetFilters() {
  filterStatus.value = '';
  keyword.value = '';
  Object.keys(searchForm.value).forEach(k => {
    searchForm.value[k] = Array.isArray(searchForm.value[k]) ? [] : '';
  });
  selectedRows.value = [];
  refreshSearch();
}

function openCreate() {
  editingId.value = null;
  form.value = { title: '', classid: selectedId.value, status: 0 };
  // 多选字段初始化为空数组，避免 el-select multiple 绑定 undefined
  for (const field of editableFields.value) {
    if (field.inputMultiple && supportsMultipleChoice(field.inputType ?? '')) {
      form.value[field.fieldName] = [];
    }
  }
  sensitiveHits.value = {};
  applyDefaultValues();
  editorVisible.value = true;
  void preloadFieldReferences();
}

async function openEdit(row: Record<string, unknown>) {
  editingId.value = Number(row.id);
  const next: Record<string, unknown> = { ...row };
  // 多选字段存储为 ,a,b,，回显为数组以适配 el-select multiple
  for (const field of modelFields.value) {
    if (!field.inputMultiple || !supportsMultipleChoice(field.inputType ?? '')) continue;
    const raw = next[field.fieldName];
    if (typeof raw !== 'string') continue;
    const parts = unpackMultiValue(raw);
    // 枚举选项值为字符串；类别/类别内容选项值为数字 id
    next[field.fieldName] = field.inputType === 'enum' ? parts : parts.map(p => Number(p));
  }
  form.value = next;
  sensitiveHits.value = {};
  editorVisible.value = true;
  await preloadFieldReferences();
}

// ================= 字段属性：输入方式 / 默认值 / 提示 / 最大字符 / 验证 =================
/** 当前中心的数据类型；表单中心无 data_type，用 0 表示 */
const currentDataType = computed(() => dataTypeOfConfigType(props.type) ?? 0);

const CENTER_CATEGORY_APIS: Record<number, { all: () => Promise<CategoryNode[]> }> = {
  1: productCategoryApi,
  2: newsCategoryApi,
  3: caseCategoryApi,
  4: solutionCategoryApi
};

/** 类别下拉数据源：dataType -> 分类列表 */
const centerCategories = ref<Record<number, CategoryNode[]>>({});

async function ensureCategories(dataType: number) {
  if (centerCategories.value[dataType]) return;
  let list: CategoryNode[] = [];
  if (dataType === currentDataType.value) {
    list = menus.value;
  } else {
    const api = CENTER_CATEGORY_APIS[dataType];
    if (api) {
      try {
        list = await api.all();
      } catch {
        list = [];
      }
    }
  }
  centerCategories.value = { ...centerCategories.value, [dataType]: list };
}

/** 数据中心 → 内容接口（用于「跨类类别内容」拉取其他中心的内容） */
const CENTER_APIS: Record<number, { contents: (classId: number, params: { page?: number; size?: number }) => Promise<ContentListResult> }> = {
  1: productCenterApi,
  2: newsCenterApi,
  3: caseCenterApi,
  4: solutionCenterApi
};

interface ScopeContent { id: number; title: string }

/** 范围类别下的内容：`${dataType}:${categoryId}` -> 内容列表 */
const scopeContents = ref<Record<string, ScopeContent[]>>({});

function scopeKey(dataType: number, categoryId: number): string {
  return `${dataType}:${categoryId}`;
}

async function ensureScopeContents(dataType: number, categoryId: number) {
  const key = scopeKey(dataType, categoryId);
  if (scopeContents.value[key]) return;
  let list: ScopeContent[] = [];
  const api = dataType === currentDataType.value ? props.centerApi : CENTER_APIS[dataType];
  if (api) {
    try {
      const result = await api.contents(categoryId, { page: 1, size: 500 });
      list = (result.list ?? []).map(r => ({ id: Number(r.id), title: String(r.title ?? '') }));
    } catch {
      list = [];
    }
  }
  scopeContents.value = { ...scopeContents.value, [key]: list };
}

function inputTypeOf(field: ModelField): string {
  return field.inputType ?? '';
}

const inputTypeLockedTypes = ['image', 'video', 'file', 'datetime'];
function isInputTypeLocked(field: ModelField): boolean {
  const name = field.fieldName.toLowerCase();
  if (name.startsWith('photo') || name.startsWith('media') || name.startsWith('file') || name.startsWith('attachment')) return true;
  if (name.includes('time') || name.includes('date')) return true;
  return inputTypeLockedTypes.includes(field.fieldType);
}

/** 固定枚举 / 类别 / 类别内容：以结构化下拉替代自由输入 */
function structuredInputOf(field: ModelField): '' | 'enum' | 'category' | 'content' {
  if (isInputTypeLocked(field)) return '';
  const type = inputTypeOf(field);
  if (type === 'enum') return 'enum';
  if (isCategoryInputType(type)) return 'category';
  if (isContentInputType(type)) return 'content';
  return '';
}

/** 范围类别及其子类别（含自身） */
function categoryOptionsOf(field: ModelField): Array<{ id: number; label: string }> {
  const ref = field.categoryRef;
  if (!ref) return [];
  const all = centerCategories.value[ref.dataType] ?? [];
  const scope = subtreeIdsOf(all, ref.categoryId);
  return all.filter(c => scope.has(Number(c.id))).map(c => ({ id: Number(c.id), label: c.className }));
}

function contentOptionsOf(field: ModelField): Array<{ id: number; label: string }> {
  const ref = field.categoryRef;
  if (!ref) return [];
  return (scopeContents.value[scopeKey(ref.dataType, ref.categoryId)] ?? []).map(c => ({ id: c.id, label: c.title }));
}

/** 列表渲染：类别 id → 类别名称 */
const categoryNameMap = computed(() => {
  const map = new Map<number, string>();
  for (const list of Object.values(centerCategories.value)) {
    for (const c of list) map.set(Number(c.id), c.className);
  }
  return map;
});

/** 列表渲染：内容 id → 内容标题 */
const contentTitleMap = computed(() => {
  const map = new Map<number, string>();
  for (const list of Object.values(scopeContents.value)) {
    for (const c of list) map.set(c.id, c.title);
  }
  return map;
});

/** 列表展示：多选值按逗号拆分（单选值原样返回） */
function splitDisplayValue(value: unknown, multiple?: boolean): string[] {
  const text = value == null ? '' : String(value);
  if (text === '') return [];
  return multiple ? unpackMultiValue(text) : [text];
}

function categoryLabelOf(value: unknown, field: ModelField): string {
  return splitDisplayValue(value, field.inputMultiple)
    .map(p => categoryNameMap.value.get(Number(p)) ?? p)
    .join('、');
}

function contentLabelOf(value: unknown, field: ModelField): string {
  return splitDisplayValue(value, field.inputMultiple)
    .map(p => contentTitleMap.value.get(Number(p)) ?? p)
    .join('、');
}

function enumLabelOf(value: unknown, field: ModelField): string {
  return splitDisplayValue(value, field.inputMultiple).join('、');
}

/** 加载字段所需的类别与内容（列表渲染、搜索下拉与编辑器下拉共用） */
async function preloadFieldReferences() {
  const categoryTypes = new Set<number>();
  const contentScopes = new Map<string, { dataType: number; categoryId: number }>();
  const allFields = [...editableFields.value, ...searchFields.value];
  for (const field of allFields) {
    const ref = field.categoryRef;
    if (!ref || !needsCategoryScope(inputTypeOf(field))) continue;
    categoryTypes.add(ref.dataType);
    if (isContentInputType(inputTypeOf(field))) {
      contentScopes.set(scopeKey(ref.dataType, ref.categoryId), { dataType: ref.dataType, categoryId: ref.categoryId });
    }
  }
  for (const type of categoryTypes) {
    await ensureCategories(type);
  }
  for (const scope of contentScopes.values()) {
    await ensureScopeContents(scope.dataType, scope.categoryId);
  }
}

/** 新增时按默认值预填 */
function applyDefaultValues() {
  for (const field of editableFields.value) {
    if (inputTypeOf(field) !== '') continue;
    const dv = field.defaultValue;
    if (dv == null || dv === '') continue;
    if (!['text', 'textarea', 'number'].includes(field.fieldType)) continue;
    form.value[field.fieldName] = field.fieldType === 'number' ? Number(dv) : dv;
  }
}

/** 保存前按字段验证校验，返回 false 表示存在校验失败 */
function validateFields(): boolean {
  for (const field of editableFields.value) {
    const error = validateFieldValue(field, form.value[field.fieldName]);
    if (error) {
      ElMessage.error(error);
      return false;
    }
  }
  return true;
}

function displayValue(row: Record<string, unknown>, field: ModelField): string {
  const v = row[field.fieldName];
  if (v == null) return '';
  if (['datetime', 'beginTime', 'endTime', 'createTime'].includes(field.fieldName) || field.fieldType === 'datetime') {
    return String(v);
  }
  return String(v);
}

function stripHtml(html: string): string {
  return html
    .replace(/<[^>]*>/g, ' ')
    .replace(/&nbsp;/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
}

function fileNameOf(value: unknown): string {
  const s = String(value ?? '');
  return s.includes('/') ? s.split('/').pop() || s : s;
}

function previewUrl(value: unknown): string {
  if (!value || typeof value !== 'string') return '';
  if (value.startsWith('http') || value.startsWith('data:')) return value;
  if (value.startsWith('/')) return value;
  return staticAssetApi.downloadUrl(value);
}

const mediaPreviewVisible = ref(false);
const mediaPreviewSrc = ref('');
const mediaPreviewType = ref<'image' | 'video'>('image');

function openMediaPreview(type: 'image' | 'video', src: string) {
  if (!src) return;
  mediaPreviewType.value = type;
  mediaPreviewSrc.value = src;
  mediaPreviewVisible.value = true;
}

async function uploadField(field: ModelField, file: File | undefined) {
  if (!file) return;
  const rel = await staticAssetApi.upload(file);
  form.value[field.fieldName] = rel;
  ElMessage.success('上传成功');
}

function removeFieldValue(field: ModelField) {
  form.value[field.fieldName] = '';
}

const pickerVisible = ref(false);
const pickerField = ref<ModelField | null>(null);
const pickerAccept = ref<string[]>([]);

function openPicker(field: ModelField) {
  pickerField.value = field;
  pickerAccept.value = field.fieldType === 'image'
    ? ['images']
    : field.fieldType === 'video'
      ? ['videos']
      : [];
  pickerVisible.value = true;
}

function onPickerSelect(path: string) {
  if (pickerField.value) {
    form.value[pickerField.value.fieldName] = path;
  }
}

async function saveEditor() {
  if (!selectedId.value) return;
  const title = String(form.value.title ?? '').trim();
  if (!title) {
    ElMessage.warning('请输入标题');
    return;
  }
  if (!validateFields()) return;
  if (!isFormType.value && !(await validateSensitiveBeforeSave())) return;
  editorSaving.value = true;
  try {
    const payload: Record<string, unknown> = { ...form.value, classid: selectedId.value };
    // 多选字段打包为 ,a,b,
    for (const field of editableFields.value) {
      if (field.inputMultiple && supportsMultipleChoice(field.inputType ?? '')) {
        const v = payload[field.fieldName];
        if (Array.isArray(v)) payload[field.fieldName] = packMultiValue(v);
      }
    }
    if (editingId.value != null) {
      await props.centerApi.update(editingId.value, payload);
    } else {
      await props.centerApi.create(payload);
    }
    editorVisible.value = false;
    ElMessage.success('保存成功');
    await loadContents();
  } finally {
    editorSaving.value = false;
  }
}

/** 失焦时对 note/rich 字段做全局敏感词比对，结果存入 sensitiveHits */
async function checkFieldSensitive(field: ModelField) {
  const value = String(form.value[field.fieldName] ?? '');
  const html = field.fieldType === 'rich';
  const hits = await checkSensitiveText(value, { html, scope: 'global' });
  sensitiveHits.value[field.fieldName] = hits;
}

/** 确认修复：replace 替换为替换词，reject 删除敏感词 */
function fixFieldSensitive(field: ModelField, hit: SensitiveWordHit) {
  const value = String(form.value[field.fieldName] ?? '');
  form.value[field.fieldName] = applySensitiveWordFix(value, hit);
  checkFieldSensitive(field);
}

/**
 * 保存前对 textarea/rich 字段统一做全局敏感词比对；
 * 存在“拒绝”类命中时返回 false，不允许提交。
 */
async function validateSensitiveBeforeSave(): Promise<boolean> {
  const problems: string[] = [];
  const rejected: string[] = [];
  for (const field of editableFields.value) {
    if (field.fieldType !== 'textarea' && field.fieldType !== 'rich') continue;
    const value = String(form.value[field.fieldName] ?? '');
    const html = field.fieldType === 'rich';
    const hits = await checkSensitiveText(value, { html, scope: 'global' });
    sensitiveHits.value[field.fieldName] = hits;
    const fieldLabel = field.title || field.fromName || field.fieldName;
    for (const hit of hits) {
      const text = `${fieldLabel}：${formatSensitiveWordHit(hit)}`;
      problems.push(text);
      if (isRejectHit(hit)) rejected.push(text);
    }
  }
  if (rejected.length) {
    ElMessage.error(`内容包含拒绝类敏感词，不允许提交：${rejected.join('；')}`);
    return false;
  }
  if (problems.length) {
    ElMessage.warning(`敏感词比对提醒：${problems.join('；')}`);
  }
  return true;
}

function hitTagType(hit: SensitiveWordHit): 'danger' | 'warning' | 'info' {
  if (hit.action === 'reject') return 'danger';
  if (hit.action === 'alert') return 'warning';
  return 'info';
}

async function removeRows(ids: number[]) {
  if (ids.length === 0) return;
  await ElMessageBox.confirm(`确认删除选中的 ${ids.length} 条内容？`, '删除确认', { type: 'warning' });
  await props.centerApi.batchOption(ids, 'delete', selectedId.value ?? undefined);
  ElMessage.success('删除成功');
  selectedRows.value = [];
  await loadContents();
}

async function batch(option: string) {
  const ids = selectedRows.value;
  if (ids.length === 0) {
    ElMessage.warning('请先选择内容');
    return;
  }
  await props.centerApi.batchOption(ids, option, selectedId.value ?? undefined);
  ElMessage.success('操作成功');
  await loadContents();
}

// ================= 审批 =================
const auditVisible = ref(false);
const auditStatus = ref(0);
const auditNote = ref('');

function openAudit() {
  if (selectedRows.value.length === 0) {
    ElMessage.warning('请先选择要审批的内容');
    return;
  }
  auditStatus.value = 0;
  auditNote.value = '';
  auditVisible.value = true;
}

async function submitAudit() {
  await platformAuditApi.audit({
    configType: props.type,
    categoryId: selectedId.value as number,
    ids: [...selectedRows.value],
    status: auditStatus.value,
    note: auditNote.value
  });
  ElMessage.success('审核已提交');
  auditVisible.value = false;
  selectedRows.value = [];
  await loadContents();
}

// ================= 一键分发 =================
const pushDialogVisible = ref(false);
const pushConfigs = ref<PlatformPushConfig[]>([]);
const pushLoading = ref(false);
const pushExecutingId = ref<number | null>(null);
const selectedPush = ref<PlatformPushConfig[]>([]);
const colNameMap = ref(new Map<number, string>());
const siteNameMap = ref(new Map<number, string>());
const mappingDetailVisible = ref(false);
const mappingDetailTitle = ref('');
const mappingDetailRows = ref<Array<{ productField: string; siteField: string }>>([]);

const recordVisible = ref(false);
const recordLoading = ref(false);
const recordRows = ref<PlatformPushRecord[]>([]);
const recordTitle = ref('');
const recordContentVisible = ref(false);
const recordContentTitle = ref('');
const recordContentText = ref('');

const openPushDialogLoader = createTableLoader(pushLoading);

async function openPushDialog() {
  if (!props.pushConfigApi) return;
  if (selectedRows.value.length === 0) {
    ElMessage.warning('请先勾选要推送的内容');
    return;
  }
  if (selectedId.value == null) return;
  pushConfigs.value = [];
  selectedPush.value = [];
  pushDialogVisible.value = true;
  const request = openPushDialogLoader.begin(() => {
    pushConfigs.value = [];
    selectedPush.value = [];
  });
  try {
    const loadedRows = await props.pushConfigApi.list(selectedId.value);
    if (!request.isCurrent()) return;
    pushConfigs.value = loadedRows;
    await refreshColumnNames();
  } catch (error) {
    if (request.isCurrent()) throw error;
  } finally {
    request.finish();
  }
}

async function refreshColumnNames() {
  try {
    const sites = await siteCatalogApi.sites();
    sites.forEach(s => siteNameMap.value.set(Number(s.id), s.name));
  } catch {
    /* 忽略站点列表加载失败 */
  }
  for (const row of pushConfigs.value) {
    try {
      const cols = await siteCatalogApi.columns(row.siteId);
      cols.forEach(c => colNameMap.value.set(Number(c.id), c.className));
    } catch {
      /* 忽略单个站点加载失败 */
    }
  }
}

function columnDisplay(id: number): string {
  const name = colNameMap.value.get(Number(id));
  return name ? `${name}（${id}）` : String(id);
}

function siteDisplay(id?: number | null): string {
  if (id == null) {
    return '-';
  }
  const name = siteNameMap.value.get(Number(id));
  return name ? `${name}（${id}）` : String(id);
}

function viewMapping(row: PlatformPushConfig) {
  let arr: Array<{ productField: string; siteField: string }> = [];
  if (row.fieldMapping) {
    try {
      arr = JSON.parse(row.fieldMapping);
    } catch {
      arr = [];
    }
  }
  mappingDetailRows.value = arr;
  mappingDetailTitle.value = `字段映射（${columnDisplay(row.siteColumnId)}）`;
  mappingDetailVisible.value = true;
}

function pushStatusName(s: number): string {
  switch (Number(s)) {
    case 1: return '待审核';
    case 2: return '已审核';
    case 3: return '已发布';
    default: return '草稿';
  }
}

const openRecordsLoader = createTableLoader(recordLoading);

async function openRecords(row: Record<string, unknown>) {
  const id = Number(row.id);
  recordTitle.value = `推送记录（内容ID ${id}）`;
  recordRows.value = [];
  recordVisible.value = true;
  const request = openRecordsLoader.begin(() => {
    recordRows.value = [];
  });
  try {
    const loadedRows = await platformPushApi.records(props.type, id);
    if (!request.isCurrent()) return;
    recordRows.value = loadedRows;
  } catch (e) {
    if (!request.isCurrent()) return;
    ElMessage.error(e instanceof Error ? e.message : '加载推送记录失败');
  } finally {
    request.finish();
  }
}

function recordMappingCount(row: PlatformPushRecord): number {
  if (!row.fieldMapping) {
    return 0;
  }
  try {
    const arr = JSON.parse(row.fieldMapping);
    return Array.isArray(arr) ? arr.length : 0;
  } catch {
    return 0;
  }
}

function viewRecordMapping(row: PlatformPushRecord) {
  let arr: Array<{ productField: string; siteField: string }> = [];
  if (row.fieldMapping) {
    try {
      arr = JSON.parse(row.fieldMapping);
    } catch {
      arr = [];
    }
  }
  mappingDetailRows.value = arr;
  mappingDetailTitle.value = `字段映射（内容ID ${row.sourceContentId}）`;
  mappingDetailVisible.value = true;
}

function viewRecordContent(row: PlatformPushRecord) {
  let text = row.fieldContent || '';
  try {
    text = JSON.stringify(JSON.parse(text), null, 2);
  } catch {
    /* 非 JSON 则原样展示 */
  }
  recordContentText.value = text || '（无字段内容）';
  recordContentTitle.value = `推送内容（内容ID ${row.sourceContentId}）`;
  recordContentVisible.value = true;
}

function pushMappingCount(row: PlatformPushConfig): number {
  if (!row.fieldMapping) return 0;
  try {
    const arr = JSON.parse(row.fieldMapping);
    return Array.isArray(arr) ? arr.length : 0;
  } catch {
    return 0;
  }
}

async function executePush(row: PlatformPushConfig): Promise<number> {
  if (row.id == null) return 0;
  pushExecutingId.value = row.id;
  try {
    return await platformPushApi.execute({
      configType: props.type,
      pushConfigId: row.id,
      contentIds: [...selectedRows.value]
    });
  } catch (e) {
    ElMessage.error(`推送失败：${(e as Error).message ?? String(e)}`);
    return 0;
  } finally {
    pushExecutingId.value = null;
  }
}

async function batchExecutePush() {
  const list = [...selectedPush.value];
  if (list.length === 0) {
    ElMessage.warning('请先勾选要推送的配置');
    return;
  }
  pushLoading.value = true;
  let total = 0;
  try {
    for (const row of list) {
      total += await executePush(row);
    }
  } finally {
    pushLoading.value = false;
  }
  ElMessage.success(`推送完成：${total} 条`);
  pushDialogVisible.value = false;
  selectedRows.value = [];
  await loadContents();
}

onMounted(async () => {
  await loadMenus();
  await applyRouteQuery();
});

// 同一页面内经跳转列二次导航时，路由 query 变化需要重新加载列表
watch(() => route.fullPath, () => { void applyRouteQuery(); });

/** 从路由 query 恢复：指定分类 + 预置跳转筛选值（父栏目跳转列跳转进来时） */
async function applyRouteQuery() {
  const qMenuId = Number(route.query.menuId);
  if (!Number.isFinite(qMenuId) || qMenuId <= 0) return;
  const target = menus.value.find(m => m.id === qMenuId);
  if (!target) return;
  const field = typeof route.query.filterField === 'string' ? route.query.filterField : '';
  const value = typeof route.query.filterValue === 'string' ? route.query.filterValue : '';
  await selectMenu(
    { id: target.id, className: target.className, parentId: target.parentId, children: [] },
    field && value ? { field, value } : undefined
  );
}
</script>

<template>
  <section class="cms-platform-center">
    <header class="cms-platform-center__header">
      <div>
        <h1>{{ props.moduleTitle }}</h1>
        <p>{{ props.moduleDescription ?? '跨站点内容中心，不随站点过滤。' }}</p>
      </div>
      <div class="cms-platform-center__header-actions">
        <slot name="header-actions" />
        <el-button @click="loadMenus">刷新分类</el-button>
      </div>
    </header>

    <div class="cms-platform-center__layout">
      <aside class="cms-platform-center__tree">
        <div class="cms-platform-center__tree-header">
          <span>分类导航</span>
          <span>{{ menus.length }} 项</span>
        </div>
        <div class="cms-platform-center__tree-body">
          <el-empty v-if="menus.length === 0" description="请先到配置页创建分类" />
          <el-tree
            v-else
            :data="treeData"
            :props="treeProps"
            node-key="id"
            default-expand-all
            highlight-current
            @node-click="onTreeNodeClick"
          >
            <template #default="{ data }">
              <span class="cms-platform-center__tree-node" :title="data.className">{{ data.className }}<el-tag size="small" type="info" style="margin-left: 6px">{{ data.id }}</el-tag></span>
            </template>
          </el-tree>
        </div>
      </aside>

      <main class="cms-platform-center__main">
        <el-empty v-if="!selectedId" description="请选择左侧分类" />
        <template v-else>
          <div class="cms-platform-center__actions">
            <el-button size="small" v-if="!isFormType" type="success" :disabled="selectedRows.length === 0" @click="batch('publish')">存为发布</el-button>
            <el-button size="small" v-if="!isFormType" :disabled="selectedRows.length === 0" @click="batch('draft')">存为草稿</el-button>
            <el-button size="small" :disabled="selectedRows.length === 0" @click="batch('audit')">提交审核</el-button>
            <el-button size="small" type="primary" plain :disabled="selectedRows.length === 0" @click="openAudit">审批</el-button>
            <!-- <el-button type="primary" plain :disabled="selectedRows.length === 0" @click="batch('approve')">审核通过</el-button> -->
            <el-button size="small" v-if="!isFormType" type="warning" plain :disabled="selectedRows.length === 0" @click="batch('top')">置顶</el-button>
            <el-button size="small" v-if="!isFormType" type="danger" plain :disabled="selectedRows.length === 0" @click="batch('recommend')">推荐</el-button>
            <el-button size="small" type="danger" :disabled="selectedRows.length === 0" @click="removeRows(selectedRows)">删除</el-button>
            <el-button v-if="pushConfigApi" size="small" type="warning" plain :disabled="selectedRows.length === 0" @click="openPushDialog">一键分发</el-button>
            <div style="flex: 1"></div>
            <el-button size="small" v-if="jumpReturnTarget" @click="backToParent">返回上级</el-button>
            <el-button size="small" type="primary" @click="openCreate">新增内容</el-button>
          </div>
          <div class="cms-platform-center__filters admin-table-toolbar">
            <el-input size="small" v-model="keyword" placeholder="按标题搜索" clearable style="width: 200px" @keyup.enter="refreshSearch" @clear="refreshSearch" />
            <template v-for="field in searchFields" :key="field.fieldName">
              <el-select size="small"
                v-if="structuredInputOf(field) === 'enum'"
                v-model="searchForm[field.fieldName]"
                :multiple="field.inputMultiple"
                collapse-tags
                collapse-tags-tooltip
                :placeholder="field.title || field.fromName"
                clearable
                style="width: 180px"
                @change="refreshSearch"
                @clear="refreshSearch"
              >
                <el-option v-for="opt in field.enumValues ?? []" :key="opt" :label="opt" :value="opt" />
              </el-select>
              <el-select size="small"
                v-else-if="structuredInputOf(field) === 'category'"
                v-model="searchForm[field.fieldName]"
                :multiple="field.inputMultiple"
                collapse-tags
                collapse-tags-tooltip
                :placeholder="field.title || field.fromName"
                clearable
                filterable
                style="width: 200px"
                @change="refreshSearch"
                @clear="refreshSearch"
              >
                <el-option
                  v-for="opt in categoryOptionsOf(field)"
                  :key="opt.id"
                  :label="`${opt.label}（${opt.id}）`"
                  :value="String(opt.id)"
                />
              </el-select>
              <el-select size="small"
                v-else-if="structuredInputOf(field) === 'content'"
                v-model="searchForm[field.fieldName]"
                :multiple="field.inputMultiple"
                collapse-tags
                collapse-tags-tooltip
                :placeholder="field.title || field.fromName"
                clearable
                filterable
                style="width: 200px"
                @change="refreshSearch"
                @clear="refreshSearch"
              >
                <el-option
                  v-for="opt in contentOptionsOf(field)"
                  :key="opt.id"
                  :label="opt.label"
                  :value="String(opt.id)"
                />
              </el-select>
              <el-input size="small"
                v-else
                v-model="searchForm[field.fieldName]"
                :placeholder="field.title || field.fromName"
                clearable
                style="width: 180px"
                @keyup.enter="refreshSearch"
                @clear="refreshSearch"
              />
            </template>
            <el-select size="small" v-model="filterStatus" placeholder="状态" clearable style="width: 130px" @change="refreshSearch">
              <el-option v-for="opt in STATUS_OPTIONS" :key="String(opt.value)" :label="opt.label" :value="opt.value" />
            </el-select>
            <el-button size="small" type="primary" @click="refreshSearch">查询</el-button>
            <el-button size="small" @click="resetFilters">重置</el-button>
          </div>

          <div class="cms-platform-center__list">
            <el-table element-loading-text="正在加载数据…"
              v-loading="listLoading"
              :data="rows"
              stripe
              @selection-change="(val: any[]) => (selectedRows = val.map(r => Number(r.id)))"
            >
              <el-table-column type="selection" width="46" />
              <el-table-column label="ID" prop="id" width="90" />
              <el-table-column label="标题" min-width="180" show-overflow-tooltip>
                <template #default="{ row }">{{ String(row.title ?? '') }}</template>
              </el-table-column>
              <template v-for="field in listColumns" :key="field.fieldName">
                <el-table-column
                  v-if="displayableColumn(field)"
                  :label="field.title || field.fromName"
                  :min-width="field.fieldType === 'image' ? 90 : field.fieldType === 'video' ? 150 : 130"
                  show-overflow-tooltip
                >
                  <template #default="{ row }">
                    <span v-if="structuredInputOf(field) === 'category'">{{ categoryLabelOf(row[field.fieldName], field) }}</span>
                    <span v-else-if="structuredInputOf(field) === 'content'">{{ contentLabelOf(row[field.fieldName], field) }}</span>
                    <span v-else-if="structuredInputOf(field) === 'enum'">{{ enumLabelOf(row[field.fieldName], field) }}</span>
                    <div
                      v-else-if="field.fieldType === 'image' && row[field.fieldName]"
                      class="cms-platform-center__media-link"
                      role="button"
                      tabindex="0"
                      @click="openMediaPreview('image', previewUrl(row[field.fieldName]))"
                      @keydown.enter="openMediaPreview('image', previewUrl(row[field.fieldName]))"
                      @keydown.space.prevent="openMediaPreview('image', previewUrl(row[field.fieldName]))"
                    >
                      <el-image
                        :src="previewUrl(row[field.fieldName])"
                        style="width: 48px; height: 48px; border-radius: 4px"
                        fit="cover"
                      />
                    </div>
                    <span v-else-if="field.fieldType === 'rich'">{{ stripHtml(String(row[field.fieldName] ?? '')) }}</span>
                    <div
                      v-else-if="field.fieldType === 'video' && row[field.fieldName]"
                      class="cms-platform-center__media-link"
                      role="button"
                      tabindex="0"
                      @click="openMediaPreview('video', previewUrl(row[field.fieldName]))"
                      @keydown.enter="openMediaPreview('video', previewUrl(row[field.fieldName]))"
                      @keydown.space.prevent="openMediaPreview('video', previewUrl(row[field.fieldName]))"
                    >
                      <video
                        :src="previewUrl(row[field.fieldName])"
                        muted
                        playsinline
                        preload="metadata"
                        style="width: 132px; height: 72px; border-radius: 4px; background: #000; pointer-events: none"
                      />
                    </div>
                    <span v-else-if="field.fieldType === 'file' && row[field.fieldName]" :title="String(row[field.fieldName])">
                      文件·{{ fileNameOf(row[field.fieldName]) }}
                    </span>
                    <el-tag v-else-if="field.fieldName === 'status' || field.fieldType === 'status'" :type="statusTagType(row[field.fieldName])" size="small">
                      {{ statusLabel(row[field.fieldName]) }}
                    </el-tag>
                    <span v-else>{{ displayValue(row, field) }}</span>
                  </template>
                </el-table-column>
              </template>
              <el-table-column label="状态" width="90">
                <template #default="{ row }">
                  <el-tag :type="statusTagType(row.status)" size="small">{{ statusLabel(row.status) }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column
                v-for="sub in jumpColumns"
                :key="sub.id"
                :label="sub.className"
                width="110"
              >
                <template #default="{ row }">
                  <el-button size="small" text type="primary" @click="gotoSubMenu(sub, row)">进入</el-button>
                </template>
              </el-table-column>
              <el-table-column label="操作" width="150" fixed="right">
                <template #default="{ row }">
                  <el-button size="small" type="primary" @click="openEdit(row)">编辑</el-button>
                  <el-button size="small" type="danger" @click="removeRows([Number(row.id)])">删除</el-button>
                  <br>
                  <el-button v-if="pushConfigApi" size="small" text type="warning" @click="openRecords(row)">推送记录</el-button>
                </template>
              </el-table-column>
            </el-table>
          </div>

          <div class="cms-platform-center__pagination">
            <el-pagination v-if="!listLoading"
              v-model:current-page="page"
              v-model:page-size="size"
              layout="total, prev, pager, next, sizes"
              :total="total"
              :page-sizes="[20, 50, 100]"
              @change="loadContents"
            />
          </div>
        </template>
      </main>
    </div>

    <el-drawer v-model="editorVisible" :title="editingId != null ? '编辑内容' : '新增内容'" size="820px">
      <el-form v-if="form" label-width="120px">
        <el-form-item label="标题" required>
          <el-input v-model="form.title" placeholder="请输入标题" />
        </el-form-item>
        <template v-for="field in editableFields" :key="field.fieldName">
          <el-form-item v-if="structuredInputOf(field) === 'enum'">
            <template #label>
              <span class="cms-platform-center__form-label">{{ field.title || field.fromName }}<span class="cms-platform-center__field-name">{{ field.fieldName }}</span></span>
            </template>
            <el-select v-model="form[field.fieldName]" :multiple="field.inputMultiple" collapse-tags collapse-tags-tooltip :placeholder="field.bak || '请选择'" clearable style="width: 100%">
              <el-option v-for="opt in field.enumValues ?? []" :key="opt" :label="opt" :value="opt" />
            </el-select>
          </el-form-item>
          <el-form-item v-else-if="structuredInputOf(field) === 'category'">
            <template #label>
              <span class="cms-platform-center__form-label">{{ field.title || field.fromName }}<span class="cms-platform-center__field-name">{{ field.fieldName }}</span></span>
            </template>
            <el-select
              v-model="form[field.fieldName]"
              :multiple="field.inputMultiple"
              collapse-tags
              collapse-tags-tooltip
              :placeholder="field.bak || '请选择类别'"
              clearable
              filterable
              style="width: 100%"
            >
              <el-option
                v-for="opt in categoryOptionsOf(field)"
                :key="opt.id"
                :label="`${opt.label}（${opt.id}）`"
                :value="opt.id"
              />
            </el-select>
          </el-form-item>
          <el-form-item v-else-if="structuredInputOf(field) === 'content'">
            <template #label>
              <span class="cms-platform-center__form-label">{{ field.title || field.fromName }}<span class="cms-platform-center__field-name">{{ field.fieldName }}</span></span>
            </template>
            <el-select
              v-model="form[field.fieldName]"
              :multiple="field.inputMultiple"
              collapse-tags
              collapse-tags-tooltip
              :placeholder="field.bak || '请选择内容'"
              clearable
              filterable
              style="width: 100%"
            >
              <el-option v-for="opt in contentOptionsOf(field)" :key="opt.id" :label="opt.label" :value="opt.id" />
            </el-select>
          </el-form-item>
          <el-form-item v-else-if="field.fieldType === 'text'">
            <template #label>
              <span class="cms-platform-center__form-label">{{ field.title || field.fromName }}<span class="cms-platform-center__field-name">{{ field.fieldName }}</span></span>
            </template>
            <el-input
              v-model="form[field.fieldName]"
              :placeholder="field.bak || ''"
              :maxlength="field.maxLength || undefined"
              :show-word-limit="Boolean(field.maxLength)"
            />
          </el-form-item>
          <el-form-item v-else-if="field.fieldType === 'number'">
            <template #label>
              <span class="cms-platform-center__form-label">{{ field.title || field.fromName }}<span class="cms-platform-center__field-name">{{ field.fieldName }}</span></span>
            </template>
            <el-input-number v-model="form[field.fieldName]" :min="-999999" :max="999999" />
          </el-form-item>
          <el-form-item v-else-if="field.fieldType === 'textarea'">
            <template #label>
              <span class="cms-platform-center__form-label">{{ field.title || field.fromName }}<span class="cms-platform-center__field-name">{{ field.fieldName }}</span></span>
            </template>
            <div class="cms-platform-center__field">
              <el-input
                v-model="form[field.fieldName]"
                type="textarea"
                :rows="4"
                :placeholder="field.bak || ''"
                :maxlength="field.maxLength || undefined"
                :show-word-limit="Boolean(field.maxLength)"
                @blur="checkFieldSensitive(field)"
              />
              <div v-if="sensitiveHits[field.fieldName]?.length" class="cms-platform-center__sensitive-hits">
                <template v-for="hit in sensitiveHits[field.fieldName]" :key="`${hit.word}:${hit.action}`">
                  <el-tag size="small" :type="hitTagType(hit)">{{ formatSensitiveWordHit(hit) }}</el-tag>
                  <el-button v-if="isFixableHit(hit)" size="small" type="primary" @click="fixFieldSensitive(field, hit)">确认</el-button>
                </template>
              </div>
            </div>
          </el-form-item>
          <el-form-item v-else-if="field.fieldType === 'rich'">
            <template #label>
              <span class="cms-platform-center__form-label">{{ field.title || field.fromName }}<span class="cms-platform-center__field-name">{{ field.fieldName }}</span></span>
            </template>
            <div class="cms-platform-center__field">
              <CmsRichTextField
                :field="{ fieldName: field.fieldName }"
                :model-value="String(form[field.fieldName] ?? '')"
                @update:model-value="(v: string) => (form[field.fieldName] = v)"
                @blur="checkFieldSensitive(field)"
              />
              <div v-if="sensitiveHits[field.fieldName]?.length" class="cms-platform-center__sensitive-hits">
                <template v-for="hit in sensitiveHits[field.fieldName]" :key="`${hit.word}:${hit.action}`">
                  <el-tag size="small" :type="hitTagType(hit)">{{ formatSensitiveWordHit(hit) }}</el-tag>
                  <el-button v-if="isFixableHit(hit)" size="small" type="primary" @click="fixFieldSensitive(field, hit)">确认</el-button>
                </template>
              </div>
            </div>
          </el-form-item>
          <el-form-item v-else-if="field.fieldType === 'datetime'">
            <template #label>
              <span class="cms-platform-center__form-label">{{ field.title || field.fromName }}<span class="cms-platform-center__field-name">{{ field.fieldName }}</span></span>
            </template>
            <el-date-picker
              v-model="form[field.fieldName]"
              type="datetime"
              value-format="YYYY-MM-DD HH:mm:ss"
              placeholder="选择时间"
            />
          </el-form-item>
          <el-form-item v-else-if="field.fieldType === 'image'">
            <template #label>
              <span class="cms-platform-center__form-label">{{ field.title || field.fromName }}<span class="cms-platform-center__field-name">{{ field.fieldName }}</span></span>
            </template>
            <div style="display: flex; gap: 8px; align-items: center">
              <el-image
                v-if="form[field.fieldName]"
                :src="previewUrl(form[field.fieldName])"
                style="width: 64px; height: 64px; border-radius: 4px"
                fit="cover"
              />
              <el-button class="cms-platform-center__resource-button" v-if="form[field.fieldName]" size="small" style="align-self: flex-end" @click="removeFieldValue(field)">清除</el-button>
              <el-upload
                :auto-upload="false"
                :show-file-list="false"
                accept="image/*"
                :on-change="(file: any) => uploadField(field, file.raw)"
              >
                <el-button class="cms-platform-center__resource-button" size="small">本地上传</el-button>
              </el-upload>
              <el-button class="cms-platform-center__resource-button" size="small" @click="openPicker(field)">从资源库中选择</el-button>
            </div>
          </el-form-item>
          <el-form-item v-else-if="field.fieldType === 'video'">
            <template #label>
              <span class="cms-platform-center__form-label">{{ field.title || field.fromName }}<span class="cms-platform-center__field-name">{{ field.fieldName }}</span></span>
            </template>
            <div>
              <div style="display: flex; gap: 8px; align-items: flex-end">
                <video
                  v-if="form[field.fieldName]"
                  :src="previewUrl(form[field.fieldName])"
                  controls
                  preload="metadata"
                  style="max-width: 340px; max-height: 200px; border-radius: 6px; background: #000"
                />
                <el-button class="cms-platform-center__resource-button" v-if="form[field.fieldName]" size="small" style="align-self: flex-end" @click="removeFieldValue(field)">清除</el-button>
              </div>
              <div style="display: flex; gap: 8px; align-items: center; margin-top: 8px">
                <el-upload
                  :auto-upload="false"
                  :show-file-list="false"
                  accept="video/*"
                  :on-change="(file: any) => uploadField(field, file.raw)"
                >
                  <el-button class="cms-platform-center__resource-button" size="small">本地上传</el-button>
                </el-upload>
                <el-button class="cms-platform-center__resource-button" size="small" @click="openPicker(field)">从资源库中选择</el-button>
              </div>
            </div>
          </el-form-item>
          <el-form-item v-else-if="field.fieldType === 'file'">
            <template #label>
              <span class="cms-platform-center__form-label">{{ field.title || field.fromName }}<span class="cms-platform-center__field-name">{{ field.fieldName }}</span></span>
            </template>
            <div style="display: flex; gap: 8px; align-items: center">
              <span v-if="form[field.fieldName]" style="font-size: 13px">{{ String(form[field.fieldName]) }}</span>
              <el-button class="cms-platform-center__resource-button" v-if="form[field.fieldName]" size="small" style="align-self: flex-end" @click="removeFieldValue(field)">清除</el-button>
              <el-upload
                :auto-upload="false"
                :show-file-list="false"
                :on-change="(file: any) => uploadField(field, file.raw)"
              >
                <el-button class="cms-platform-center__resource-button" size="small">本地上传</el-button>
              </el-upload>
              <el-button class="cms-platform-center__resource-button" size="small" @click="openPicker(field)">从资源库中选择</el-button>
            </div>
          </el-form-item>
        </template>
      </el-form>
      <template #footer>
        <el-button @click="editorVisible = false">取消</el-button>
        <el-button type="primary" :loading="editorSaving" @click="saveEditor">保存</el-button>
      </template>
    </el-drawer>

    <el-drawer v-if="pushConfigApi" v-model="pushDialogVisible" title="一键分发（选择推送配置，可多选）" size="760px">
      <el-empty v-if="!pushLoading && pushConfigs.length === 0" description="当前分类未配置推送，请先到配置页添加" />
      <el-table element-loading-text="正在加载数据…"
        v-else
        v-loading="pushLoading"
        :data="pushConfigs"
        stripe
        @selection-change="(val: any[]) => (selectedPush = val as PlatformPushConfig[])"
      >
        <el-table-column type="selection" width="46" />
        <el-table-column label="配置ID" prop="id" width="90" />
        <el-table-column label="目标站点" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">{{ siteDisplay(row.siteId) }}</template>
        </el-table-column>
        <el-table-column label="目标站点栏目" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">{{ columnDisplay(row.siteColumnId) }}</template>
        </el-table-column>
        <el-table-column label="目标状态" width="110">
          <template #default="{ row }">{{ pushStatusName(row.targetStatus) }}</template>
        </el-table-column>
        <el-table-column label="字段映射" width="110">
          <template #default="{ row }">
            <el-button size="small" text type="primary" @click="viewMapping(row)">
              {{ pushMappingCount(row) }} 项
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      <p v-if="pushConfigs.length" class="cms-platform-center__push-hint">
        已选 {{ selectedPush.length }} 个推送配置，将把勾选的 {{ selectedRows.length }} 条内容按各自字段映射新增到目标站点栏目，未映射字段默认为空。
      </p>
      <template #footer>
        <el-button @click="pushDialogVisible = false">取消</el-button>
        <el-button type="warning" plain :loading="pushLoading" :disabled="selectedPush.length === 0" @click="batchExecutePush">
          一键分发（{{ selectedPush.length }}）
        </el-button>
      </template>
    </el-drawer>

    <el-dialog v-model="mappingDetailVisible" :title="mappingDetailTitle" width="560px">
      <el-empty v-if="mappingDetailRows.length === 0" description="无字段映射（推送时目标字段均置空）" />
      <el-table v-else :data="mappingDetailRows" stripe max-height="420px">
        <el-table-column label="源字段" prop="productField" />
        <el-table-column label="→" width="50" align="center">
          <template #default>→</template>
        </el-table-column>
        <el-table-column label="目标字段" prop="siteField" />
      </el-table>
    </el-dialog>

    <el-drawer v-if="pushConfigApi" v-model="recordVisible" :title="recordTitle" size="1080px" direction="rtl">
      <el-empty v-if="!recordLoading && recordRows.length === 0" description="暂无推送记录" />
      <el-table element-loading-text="正在加载数据…" v-else v-loading="recordLoading" :data="recordRows" stripe max-height="calc(100vh - 140px)">
        <el-table-column label="中台数据ID" prop="sourceContentId" width="110" />
        <el-table-column label="数据类型" prop="contentType" width="100" />
        <el-table-column label="推送配置ID" prop="pushConfigId" width="110" />
        <el-table-column label="目标站点" min-width="140" show-overflow-tooltip>
          <template #default="{ row }">{{ siteDisplay(row.siteId) }}</template>
        </el-table-column>
        <el-table-column label="目标栏目" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">{{ row.columnId ? columnDisplay(row.columnId) : '-' }}</template>
        </el-table-column>
        <el-table-column label="目标内容ID" prop="targetContentId" width="110">
          <template #default="{ row }">{{ row.targetContentId ?? '-' }}</template>
        </el-table-column>
        <el-table-column label="类型" width="90">
          <template #default="{ row }">
            <el-tag :type="row.pushType === 'UPDATE' ? 'warning' : 'success'" size="small">
              {{ row.pushType === 'UPDATE' ? '更新' : '新增' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="推送状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.pushStatus === 'SUCCESS' ? 'success' : 'danger'" size="small">
              {{ row.pushStatus === 'SUCCESS' ? '成功' : '失败' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="目标状态" width="100">
          <template #default="{ row }">{{ row.targetStatus == null ? '-' : pushStatusName(row.targetStatus) }}</template>
        </el-table-column>
        <el-table-column label="字段映射" width="100">
          <template #default="{ row }">
            <el-button size="small" text type="primary" @click="viewRecordMapping(row)">
              {{ recordMappingCount(row) }} 项
            </el-button>
          </template>
        </el-table-column>
        <el-table-column label="推送内容" width="100">
          <template #default="{ row }">
            <el-button size="small" text type="primary" @click="viewRecordContent(row)">查看</el-button>
          </template>
        </el-table-column>
        <el-table-column label="推送时间" prop="pushTime" width="170" />
        <el-table-column label="推送人" prop="pushUser" width="110" show-overflow-tooltip />
        <el-table-column label="失败原因" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">{{ row.errorMessage || '-' }}</template>
        </el-table-column>
      </el-table>
    </el-drawer>

    <el-dialog v-model="recordContentVisible" :title="recordContentTitle" width="720px" top="6vh">
      <pre class="cms-platform-center__push-content">{{ recordContentText }}</pre>
    </el-dialog>

    <el-drawer v-model="auditVisible" title="审批" size="420px">
      <el-form label-width="90px">
        <el-form-item label="审核状态" required>
          <el-select v-model="auditStatus" style="width: 100%">
            <el-option label="草稿" :value="0" />
            <el-option label="待审核" :value="1" />
            <el-option label="已审核" :value="2" />
            <el-option label="已发布" :value="3" />
          </el-select>
        </el-form-item>
        <el-form-item label="审核备注">
          <el-input v-model="auditNote" type="textarea" :rows="4" placeholder="请输入审核备注" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="auditVisible = false">取消</el-button>
        <el-button type="primary" @click="submitAudit">提交</el-button>
      </template>
    </el-drawer>

    <el-dialog v-model="mediaPreviewVisible" :title="mediaPreviewType === 'image' ? '图片预览' : '视频预览'" width="560px" :show-close="true" align-center>
      <el-image
        v-if="mediaPreviewType === 'image' && mediaPreviewSrc"
        :src="mediaPreviewSrc"
        fit="contain"
        style="width: 100%; max-height: 60vh; border-radius: 6px"
      />
      <video
        v-else-if="mediaPreviewType === 'video' && mediaPreviewSrc"
        :src="mediaPreviewSrc"
        controls
        autoplay
        style="width: 100%; max-height: 60vh; border-radius: 6px; background: #000"
      />
      <template #footer>
        <el-button @click="mediaPreviewVisible = false">关闭</el-button>
      </template>
    </el-dialog>

    <StaticAssetPickerDialog
      v-model="pickerVisible"
      :accept="pickerAccept"
      title="从静态资源库选择"
      @select="onPickerSelect"
    />
  </section>
</template>

<style>
.cms-platform-center__resource-button.el-button {
  height: 24px;
  min-height: 24px;
  padding: 0 8px;
  font-family: inherit;
  font-size: 12px;
  font-weight: 500;
  line-height: 1;
  color: var(--admin-text);
}

.cms-platform-center {
  display: flex;
  flex-direction: column;
  height: 100%;
  padding: 0;
  gap: var(--admin-card-gap);
}

.cms-platform-center__header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.cms-platform-center__header h1 { margin: 0; font-size: var(--admin-font-size-page); }
.cms-platform-center__header p { margin: 4px 0 0; color: var(--el-text-color-secondary); font-size: 13px; }
.cms-platform-center__header-actions { display: flex; flex-wrap: wrap; gap: 8px; }

.cms-platform-center__layout {
  display: flex;
  flex: 1;
  gap: var(--admin-card-gap);
  min-height: 0;
}

.cms-platform-center__tree {
  width: 280px;
  flex-shrink: 0;
  border: 1px solid var(--el-border-color-light);
  border-radius: var(--admin-card-radius);
  background: var(--el-bg-color);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.cms-platform-center__tree-header {
  padding: 10px 12px;
  border-bottom: 1px solid var(--el-border-color-light);
  font-weight: 600;
  display: flex;
  justify-content: space-between;
}
.cms-platform-center__tree-body {
  flex: 1;
  overflow: auto;
  padding: 8px;
}
.cms-platform-center__tree-node {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.cms-platform-center__main {
  flex: 1;
  border: 1px solid var(--el-border-color-light);
  border-radius: var(--admin-card-radius);
  background: var(--el-bg-color);
  overflow: hidden;
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.cms-platform-center__actions {
  padding: 12px 12px 8px;
  display: flex;
  gap: 8px;
  align-items: center;
  flex-wrap: wrap;
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.cms-platform-center__filters {
  padding: 8px 12px 12px;
  display: flex;
  gap: 8px;
  align-items: center;
  flex-wrap: wrap;
  border-bottom: 1px solid var(--el-border-color-light);
}

.cms-platform-center__list {
  flex: 1;
  overflow: auto;
  padding: 12px;
}

.cms-platform-center__pagination {
  padding: 12px;
  display: flex;
  justify-content: flex-end;
}

.cms-platform-center__push-hint {
  margin: 12px 0 0;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.cms-platform-center__push-content {
  margin: 0;
  max-height: 60vh;
  overflow: auto;
  padding: 12px;
  background: var(--el-fill-color-light);
  border-radius: 6px;
  font-family: 'Consolas', 'Monaco', 'Courier New', monospace;
  font-size: 12px;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-all;
}

.cms-platform-center__media-link {
  cursor: zoom-in;
  display: inline-block;
  line-height: 0;
}
.cms-platform-center__media-link:focus-visible { outline: 2px solid var(--el-color-primary); outline-offset: 2px; }

.cms-platform-center__field {
  width: 100%;
}

/* 字段标签：标题在上、字段名在下，保证逐项对齐 */
.cms-platform-center__form-label {
  display: inline-flex;
  flex-direction: column;
  align-items: flex-end;
  white-space: nowrap;
  line-height: 1.35;
}

.cms-platform-center__field-name {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.cms-platform-center__sensitive-hits {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  margin-top: 8px;
}

@media (max-width: 900px) {
  .cms-platform-center { height: auto; min-height: 100%; padding: 0; }
  .cms-platform-center__header { align-items: flex-start; gap: 12px; flex-wrap: wrap; }
  .cms-platform-center__layout { flex-direction: column; }
  .cms-platform-center__tree { width: auto; min-height: 180px; max-height: 300px; }
  .cms-platform-center__main { min-height: 520px; }
}

@media (max-width: 640px) {
  .cms-platform-center__actions, .cms-platform-center__filters { align-items: flex-start; }
  .cms-platform-center__pagination { justify-content: flex-start; overflow-x: auto; }
}
</style>
