<script setup lang="ts">
import { createTableLoader } from '@/utils/tableLoader';
import { computed, onMounted, ref, watch } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { ArrowDown } from '@element-plus/icons-vue';
import {
  caseCategoryApi,
  newsCategoryApi,
  productCategoryApi,
  siteCatalogApi,
  solutionCategoryApi,
  type CategoryNode,
  type PlatformPushConfig,
  type SiteCatalogOption,
  type SiteColumnNode
} from '@/api/cmsPlatform';
import {
  type ModelField,
  DATA_TYPE_LABELS,
  FIELD_TYPE_NAMES,
  INPUT_TYPE_NAMES,
  INPUT_TYPE_OPTIONS,
  VALIDATION_OPTIONS,
  buildModelPayload,
  buildFormModelPayload,
  categoryScopeOf,
  dataTypeOfConfigType,
  defaultRowsFromPool,
  isContentInputType,
  loadFormModelRows,
  needsCategoryScope,
  supportsMultipleChoice,
  parseModelField
} from './modelField';
import FormServiceApiPanel from './FormServiceApiPanel.vue';

interface CategoryApi {
  tree: (parentId?: number) => Promise<CategoryNode[]>;
  all: () => Promise<CategoryNode[]>;
  detail: (id: number) => Promise<CategoryNode>;
  create: (data: Partial<CategoryNode>) => Promise<CategoryNode>;
  update: (id: number, data: Partial<CategoryNode>) => Promise<CategoryNode>;
  remove: (id: number) => Promise<number>;
  sort: (id: number, sort: number) => Promise<boolean>;
}

interface PushConfigApi {
  list: (categoryId: number) => Promise<PlatformPushConfig[]>;
  create: (data: Partial<PlatformPushConfig>) => Promise<PlatformPushConfig>;
  update: (id: number, data: Partial<PlatformPushConfig>) => Promise<PlatformPushConfig>;
  remove: (id: number) => Promise<boolean>;
}

const props = defineProps<{
  categoryApi: CategoryApi;
  pushConfigApi: PushConfigApi;
  configType: 'product' | 'news' | 'form' | 'solution' | 'case';
  moduleTitle: string;
  initialCategoryId?: number;
}>();

/** 表单模块：推送设置目标站点字段改名为「推送类别」 */
const pushTargetLabel = computed(() => (props.configType === 'form' ? '推送类别' : '目标站点'));

/** 源字段名称随配置类型变化（产品字段/新闻字段/解决方案字段/案例字段/表单字段） */
const sourceFieldLabel = computed(() => {
  switch (props.configType) {
    case 'news':
      return '新闻字段';
    case 'solution':
      return '解决方案字段';
    case 'case':
      return '案例字段';
    case 'form':
      return '表单字段';
    default:
      return '产品字段';
  }
});

const loading = ref(false);

/** 系统字段：不参与字段配置，即使历史配置中包含也会被过滤 */
const SYSTEM_FIELDS = ['tags', 'hits', 'sort', 'status'];
const menus = ref<CategoryNode[]>([]);
const selectedId = ref<number | null>(null);
const selectedCategory = ref<CategoryNode | null>(null);
const fields = ref<ModelField[]>([]);
const fieldLoading = ref(false);
const saving = ref(false);

// 字段筛选（对齐栏目管理：上方筛选分组 + 下方选中字段列表）
const fieldFilterExpanded = ref(true);
const visibleFieldNames = ref<string[]>([]);
const expandedFieldGroups = ref<string[]>(['singleLine', 'note', 'photo', 'media', 'content']);

const createDialog = ref(false);
const createForm = ref<{ className: string; parentId: number }>({ className: '', parentId: 0 });

interface TreeData {
  id: number;
  className: string;
  parentId: number;
  sort: number;
  children: TreeData[];
}

const treeData = computed<TreeData[]>(() => {
  const map = new Map<number, TreeData>();
  const sorted = [...menus.value].sort((a, b) => (a.sort ?? 0) - (b.sort ?? 0));
  for (const m of sorted) {
    map.set(m.id, {
      id: m.id,
      className: m.className,
      parentId: m.parentId,
      sort: m.sort ?? 0,
      children: []
    });
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
  loading.value = true;
  try {
    menus.value = await props.categoryApi.all();
  } finally {
    loading.value = false;
  }
}

const selectMenuLoader = createTableLoader(fieldLoading);

async function selectMenu(node: TreeData) {
  selectedId.value = node.id;
  const request = selectMenuLoader.begin(() => {
    fields.value = [];
    selectedCategory.value = null;
    loadPushListLoader.invalidate();
    pushConfigs.value = [];
  });
  try {
    const detail = await props.categoryApi.detail(node.id);
    if (!request.isCurrent()) return;
    selectedCategory.value = detail;
    loadFields(detail);
    loadBasicForm(detail);
    if (props.configType !== 'form') await loadPushList();
  } catch (error) {
    if (request.isCurrent()) throw error;
  } finally {
    request.finish();
  }
}

function loadFields(category: CategoryNode) {
  if (props.configType === 'form') {
    fields.value = loadFormModelRows(category).filter(row => !SYSTEM_FIELDS.includes(row.fieldName));
    fieldFilterExpanded.value = parseModelField(category.modelField).length > 0;
    visibleFieldNames.value = fields.value.filter(row => row.enabled).map(row => row.fieldName);
    return;
  }
  const configured = parseModelField(typeof category.modelField === 'string' ? category.modelField : null)
    .filter(row => !SYSTEM_FIELDS.includes(row.fieldName));
  const pool = defaultRowsFromPool();
  const byName = new Map(pool.map(r => [r.fieldName, r]));
  if (configured.length > 0) {
    for (const row of configured) {
      const p = byName.get(row.fieldName);
      if (p) {
        Object.assign(p, row);
      } else {
        pool.push(row);
      }
    }
  }
  // 兼容历史配置：补齐新属性的默认值，避免多选/枚举渲染拿到 undefined
  fields.value = pool.map(row => ({
    ...row,
    validations: row.validations ?? [],
    enumValues: row.enumValues ?? []
  }));
  fieldFilterExpanded.value = configured.length > 0;
  visibleFieldNames.value = fields.value.filter(r => r.enabled).map(r => r.fieldName);
}

function openCreate(parentId: number) {
  createForm.value = { className: '', parentId };
  createDialog.value = true;
}

async function confirmCreate() {
  if (!createForm.value.className.trim()) {
    ElMessage.warning('请输入分类名称');
    return;
  }
  try {
    await props.categoryApi.create({ className: createForm.value.className.trim(), parentId: createForm.value.parentId });
    createDialog.value = false;
    ElMessage.success('分类创建成功');
    createForm.value = { className: '', parentId: 0 };
    await loadMenus();
  } catch (err) {
    ElMessage.error(`创建失败：${(err as Error).message ?? String(err)}`);
  }
}

async function confirmRemove(node: TreeData) {
  await ElMessageBox.confirm(`确认删除分类「${node.className}」及其全部子分类？`, '删除确认', {
    type: 'warning'
  });
  await props.categoryApi.remove(node.id);
  if (selectedId.value === node.id) {
    selectedId.value = null;
    selectedCategory.value = null;
  }
  ElMessage.success('删除成功');
  await loadMenus();
}

async function saveFieldConfig() {
  const categoryId = selectedId.value;
  if (!categoryId) return;
  saving.value = true;
  try {
    // 后端按「非 null 字段」整实体更新；parentId/sort 是基本类型，不带会被当作 0 写回
    const payload = {
      ...(props.configType === 'form' && selectedCategory.value
        ? buildFormModelPayload(fields.value, selectedCategory.value)
        : buildModelPayload(fields.value)),
      parentId: Number(selectedCategory.value?.parentId ?? 0),
      sort: Number(selectedCategory.value?.sort ?? 0),
      parentListFilterField: jumpFilterFieldName.value
    };
    await props.categoryApi.update(categoryId, payload);
    ElMessage.success('字段配置已保存');
    const detail = await props.categoryApi.detail(categoryId);
    if (selectedId.value === categoryId) {
      selectedCategory.value = detail;
      if (props.configType === 'form') loadFields(detail);
    }
  } finally {
    saving.value = false;
  }
}

/** 输入方式弹窗内的「保存」：保存字段配置（含关联字段名）并关闭弹窗 */
async function savePickerConfig() {
  await saveFieldConfig();
  categoryPickerVisible.value = false;
}

function fieldTypeName(type: string): string {
  return FIELD_TYPE_NAMES[type as keyof typeof FIELD_TYPE_NAMES] ?? type;
}

// ================= 输入方式 / 字段验证 / 类别引用 =================
/** 当前中心的数据类型；表单中心无 data_type，用 0 表示 */
const currentDataType = computed(() => dataTypeOfConfigType(props.configType) ?? 0);

/** 数据中心 → 分类接口（用于「跨类类别」选择其他中心的分类） */
const CENTER_CATEGORY_APIS: Record<number, { all: () => Promise<CategoryNode[]> }> = {
  1: productCategoryApi,
  2: newsCategoryApi,
  3: caseCategoryApi,
  4: solutionCategoryApi
};

function centerLabel(dataType: number): string {
  return dataType === 0 ? '表单' : DATA_TYPE_LABELS[dataType] ?? String(dataType);
}

/** 跨类可选中心：产品/新闻/案例/解决方案，排除当前中心 */
const crossCenterOptions = computed(() =>
  [1, 2, 3, 4]
    .filter(dt => dt !== currentDataType.value)
    .map(dt => ({ value: dt, label: centerLabel(dt) }))
);

async function categoriesOf(dataType: number): Promise<CategoryNode[]> {
  if (dataType === currentDataType.value) {
    return props.categoryApi.all();
  }
  const api = CENTER_CATEGORY_APIS[dataType];
  if (!api) return [];
  try {
    return await api.all();
  } catch {
    return [];
  }
}

const inputTypeLockedTypes = ['image', 'video', 'file', 'datetime'];
function isInputTypeLocked(row: ModelField): boolean {
  const name = row.fieldName.toLowerCase();
  if (name.startsWith('photo') || name.startsWith('media') || name.startsWith('file') || name.startsWith('attachment')) {
    return true;
  }
  if (name.includes('time') || name.includes('date')) {
    return true;
  }
  return inputTypeLockedTypes.includes(row.fieldType);
}

function inputTypeOf(row: ModelField | null): string {
  return row?.inputType ?? '';
}

function onInputTypeChange(row: ModelField, val: string) {
  row.inputType = val;
  // 离开支持多选的输入方式时复位选择方式
  if (!supportsMultipleChoice(val)) {
    row.inputMultiple = false;
  }
  if (val === 'enum') {
    openEnumConfig(row);
  } else if (needsCategoryScope(val)) {
    openCategoryConfig(row);
  }
}

/** 选择方式标注（仅对支持多选的输入方式展示） */
function choiceModeLabel(row: ModelField): string {
  if (!supportsMultipleChoice(inputTypeOf(row))) return '';
  return row.inputMultiple ? ' · 多选' : ' · 单选';
}

/** 跨类输入方式：表单中心无跨类概念，不提供 */
const CROSS_INPUT_TYPES = ['categoryCross', 'contentCross'];

/**
 * 当前字段可选的输入方式。
 * 表单中心剔除跨类类别 / 跨类类别内容；历史已配置跨类的字段保留该项以便正常回显。
 */
function inputTypeOptionsFor(row: ModelField): Array<{ label: string; value: string }> {
  if (props.configType !== 'form') {
    return INPUT_TYPE_OPTIONS;
  }
  const current = inputTypeOf(row);
  return INPUT_TYPE_OPTIONS.filter(opt => !CROSS_INPUT_TYPES.includes(opt.value) || opt.value === current);
}

function openInputTypeConfig(row: ModelField) {
  const type = inputTypeOf(row);
  if (type === 'enum') openEnumConfig(row);
  else if (needsCategoryScope(type)) openCategoryConfig(row);
}

/** 已配置的枚举/类别摘要，展示在「输入方式」列 */
function inputTypeSummary(row: ModelField): string {
  const type = inputTypeOf(row);
  if (type === 'enum') {
    const values = row.enumValues ?? [];
    return (values.length ? `枚举 ${values.length} 项` : '未设置枚举值') + choiceModeLabel(row);
  }
  if (needsCategoryScope(type)) {
    const ref = row.categoryRef;
    if (!ref) return '未选择类别' + choiceModeLabel(row);
    return `${centerLabel(ref.dataType)} · ${ref.categoryName ?? ''}（${ref.categoryId}）${choiceModeLabel(row)}`;
  }
  return '';
}

/** 输入方式说明（悬停展示完整配置） */
function inputTypeDetail(row: ModelField): string {
  const type = inputTypeOf(row);
  const name = INPUT_TYPE_NAMES[type] ?? '手动输入';
  if (type === 'enum') {
    const values = row.enumValues ?? [];
    return (values.length ? `${name}：${values.join('、')}` : `${name}：未设置枚举值`) + choiceModeLabel(row);
  }
  if (needsCategoryScope(type)) {
    const ref = row.categoryRef;
    if (!ref) return `${name}：未选择类别` + choiceModeLabel(row);
    const target = isContentInputType(type) ? '下拉取该类别下的内容' : '下拉取该类别及其子类别';
    return `${name}：${centerLabel(ref.dataType)} · ${ref.categoryName ?? ''}（${ref.categoryId}），${target}${choiceModeLabel(row)}`;
  }
  return '';
}

// ---- 固定枚举编辑 ----
const enumEditorVisible = ref(false);
const enumEditingRow = ref<ModelField | null>(null);
const enumEditorRows = ref<Array<{ value: string }>>([]);

function openEnumConfig(row: ModelField) {
  enumEditingRow.value = row;
  row.inputMultiple = Boolean(row.inputMultiple);
  const values = row.enumValues ?? [];
  enumEditorRows.value = values.length ? values.map(v => ({ value: v })) : [{ value: '' }];
  enumEditorVisible.value = true;
}

function saveEnumConfig() {
  if (!enumEditingRow.value) return;
  const values = enumEditorRows.value.map(r => r.value.trim()).filter(Boolean);
  if (values.length === 0) {
    ElMessage.warning('请至少设置一个枚举值');
    return;
  }
  enumEditingRow.value.inputType = 'enum';
  enumEditingRow.value.enumValues = values;
  enumEditorVisible.value = false;
}

// ---- 类别引用选择（同类 / 跨类）----
const categoryPickerVisible = ref(false);
const categoryEditingRow = ref<ModelField | null>(null);
const pickerDataType = ref<number | null>(null);
const pickerNodes = ref<CategoryNode[]>([]);
const pickerLoading = ref(false);

interface PickerNode { id: number; className: string; parentId: number; children: PickerNode[] }

const pickerTree = computed<PickerNode[]>(() => {
  const map = new Map<number, PickerNode>();
  for (const c of pickerNodes.value) {
    map.set(c.id, { id: c.id, className: c.className, parentId: c.parentId, children: [] });
  }
  const roots: PickerNode[] = [];
  for (const node of map.values()) {
    if (node.parentId === 0 || !map.has(node.parentId)) {
      roots.push(node);
    } else {
      map.get(node.parentId)!.children.push(node);
    }
  }
  return roots;
});

const pickerTreeProps = { children: 'children', label: 'className' };

/** 当前选择弹窗是否为「跨类」（跨类需先选所属中心） */
const pickerIsCross = computed(() => categoryScopeOf(inputTypeOf(categoryEditingRow.value)) === 'cross');

/** 当前选择弹窗是否为「类别内容」（取其下内容） */
const pickerIsContent = computed(() => isContentInputType(inputTypeOf(categoryEditingRow.value)));

async function loadPickerNodes(dataType: number) {
  pickerLoading.value = true;
  try {
    pickerNodes.value = await categoriesOf(dataType);
  } finally {
    pickerLoading.value = false;
  }
}

async function openCategoryConfig(row: ModelField) {
  categoryEditingRow.value = row;
  row.inputMultiple = Boolean(row.inputMultiple);
  const isSame = categoryScopeOf(inputTypeOf(row)) === 'same';
  const dataType = isSame
    ? currentDataType.value
    : (row.categoryRef?.dataType ?? crossCenterOptions.value[0]?.value ?? currentDataType.value);
  pickerDataType.value = dataType;
  categoryPickerVisible.value = true;
  await loadPickerNodes(dataType);
}

async function onPickerDataTypeChange(value: number) {
  pickerDataType.value = value;
  await loadPickerNodes(value);
}

function onPickCategory(node: PickerNode) {
  if (!categoryEditingRow.value || pickerDataType.value == null) return;
  categoryEditingRow.value.categoryRef = {
    dataType: pickerDataType.value,
    categoryId: node.id,
    categoryName: node.className
  };
  categoryPickerVisible.value = false;
}

interface FieldGroup { key: string; label: string; fields: ModelField[] }

const fieldGroups = computed<FieldGroup[]>(() => {
  const groups: Record<string, ModelField[]> = {
    singleLine: [], note: [], photo: [], media: [], file: [], content: [], other: []
  };
  const singleLineNames = ['title', 'tags', 'hits', 'sort', 'status'];
  fields.value.forEach(row => {
    const name = row.fieldName.toLowerCase();
    if (name.startsWith('singleline')) {
      groups.singleLine.push(row);
    } else if (singleLineNames.includes(name)) {
      groups.singleLine.push(row);
    } else if (name.startsWith('note')) {
      groups.note.push(row);
    } else if (name.startsWith('photo')) {
      groups.photo.push(row);
    } else if (name.startsWith('media')) {
      groups.media.push(row);
    } else if (name.startsWith('file') || name.startsWith('attachment')) {
      groups.file.push(row);
    } else if (name.startsWith('content')) {
      groups.content.push(row);
    } else {
      groups.other.push(row);
    }
  });
  const groupLabels: Record<string, string> = {
    singleLine: '单行文本 (singleLine)',
    note: '多行文本 (note)',
    photo: '图片字段 (photo)',
    media: '视频字段 (media)',
    file: '文件字段 (file)',
    content: '富文本 (content)',
    other: '其他字段'
  };
  return Object.entries(groups)
    .filter(([, f]) => f.length > 0)
    .map(([key, f]) => ({ key, label: groupLabels[key] || key, fields: f }));
});

function getGroupSelectedCount(groupKey: string): number {
  const group = fieldGroups.value.find(g => g.key === groupKey);
  if (!group) return 0;
  return group.fields.filter(f => visibleFieldNames.value.includes(f.fieldName)).length;
}

const filteredFieldRows = computed(() => {
  if (visibleFieldNames.value.length === 0) return [];
  return fields.value.filter(row => visibleFieldNames.value.includes(row.fieldName));
});

function onVisibleChange() {
  // 新勾选的字段自动启用
  fields.value.forEach(row => {
    if (visibleFieldNames.value.includes(row.fieldName)) {
      row.enabled = true;
    }
  });
}

// ================= 三个 TAB =================
const activeTab = ref<'metadata' | 'edit' | 'push' | 'service'>('metadata');

// ---- TAB 2: 基本信息编辑 ----
const basicForm = ref<Record<string, unknown>>({});
const isImportedForm = computed(() => {
  const identifies = selectedCategory.value?.identifies;
  return props.configType === 'form' && typeof identifies === 'string' && identifies.startsWith('cms-form-import-v1:');
});

/** 父栏目跳转筛选字段名：在元数据配置的「输入方式」弹窗里把某个字段设为它 */
const jumpFilterFieldName = ref('');

function isJumpFilterField(row: ModelField | null): boolean {
  return !!row && jumpFilterFieldName.value === row.fieldName;
}

function setJumpFilterField(row: ModelField | null, on: boolean) {
  jumpFilterFieldName.value = on && row ? row.fieldName : '';
}

/** 弹窗中针对当前编辑字段的「父栏目跳转筛选字段」开关 */
const jumpFilterChecked = computed({
  get: () => isJumpFilterField(categoryEditingRow.value),
  set: (on: boolean) => setJumpFilterField(categoryEditingRow.value, on)
});

/** 关联字段的回显文案：业务名称（字段标题）优先，附加字段名 */
const jumpFilterFieldText = computed(() => {
  const name = jumpFilterFieldName.value;
  if (!name) return '';
  const row = fields.value.find(r => r.fieldName === name);
  const label = row?.title || row?.fromName || '';
  return label ? `${label}（${name}）` : name;
});

function loadBasicForm(category: CategoryNode) {
  basicForm.value = {
    className: category.className ?? '',
    classEnName: category.classEnName ?? '',
    parentId: Number(category.parentId ?? 0),
    pageType: Number(category.pageType ?? 1),
    sort: Number(category.sort ?? 0),
    isCanEdit: category.isCanEdit ?? true,
    isCanSort: category.isCanSort ?? true,
    hasManageChannel: category.hasManageChannel ?? true,
    hasFrontChannel: category.hasFrontChannel ?? true,
    showPosition: category.showPosition ?? '',
    isSubMenu: Boolean(category.isSubMenu ?? false),
    formCaptcha: Number(category.formCaptcha ?? 0)
  };
  jumpFilterFieldName.value = String(category.parentListFilterField ?? '');
}

// 切到「基本信息编辑」Tab 时确保父级下拉按当前选中分类重新初始化
watch(activeTab, tab => {
  if (tab === 'edit' && selectedCategory.value) {
    loadBasicForm(selectedCategory.value);
  }
});

/** 可选父级分类：排除自身及其全部子孙，避免成环 */
const parentOptions = computed(() => {
  const descendants = new Set<number>();
  const gather = (id: number) => {
    menus.value.filter(m => m.parentId === id).forEach(c => {
      descendants.add(c.id);
      gather(c.id);
    });
  };
  if (selectedId.value) {
    gather(selectedId.value);
  }
  return menus.value.filter(m => m.id !== selectedId.value && !descendants.has(m.id));
});

async function saveBasic() {
  if (!selectedId.value) return;
  const payload = { ...basicForm.value };
  if (isImportedForm.value) delete payload.className;
  await props.categoryApi.update(selectedId.value, payload);
  ElMessage.success('基本信息已保存');
  selectedCategory.value = await props.categoryApi.detail(selectedId.value);
  loadBasicForm(selectedCategory.value);
  await loadMenus();
}

// ---- TAB 3: 推送设置（列表 + 新增/编辑对话框） ----
const pushConfigs = ref<PlatformPushConfig[]>([]);
const pushLoading = ref(false);

const pushDialogVisible = ref(false);
const pushSaving = ref(false);
const editingPushId = ref<number | null>(null);

const sites = ref<SiteCatalogOption[]>([]);
const pushSiteId = ref<number | null>(null);
const pushColumns = ref<SiteColumnNode[]>([]);
const pushColumnId = ref<number | null>(null);
const pushColumnFields = ref<ModelField[]>([]);
const mappingRows = ref<Array<{ productField: string; productLabel: string; siteField: string }>>([]);
const pushTargetStatus = ref(0);

interface SiteTree { id: number; className: string; parentId: number; children: SiteTree[] }

const pushColumnsTree = computed<SiteTree[]>(() => {
  const map = new Map<number, SiteTree>();
  for (const c of pushColumns.value) {
    map.set(c.id, { id: c.id, className: c.className, parentId: c.parentId, children: [] });
  }
  const roots: SiteTree[] = [];
  for (const node of map.values()) {
    if (node.parentId === 0 || !map.has(node.parentId)) {
      roots.push(node);
    } else {
      map.get(node.parentId)!.children.push(node);
    }
  }
  return roots;
});

const pushTreeProps = { children: 'children', label: 'className' };

const loadPushListLoader = createTableLoader(pushLoading);

async function loadPushList() {
  if (selectedId.value == null) {
    loadPushListLoader.invalidate();
    return;
  }
  const request = loadPushListLoader.begin(() => {
    pushConfigs.value = [];
  });
  try {
    const loadedRows = await props.pushConfigApi.list(selectedId.value);
    if (!request.isCurrent()) return;
    pushConfigs.value = loadedRows;
    await refreshNameMaps();
  } catch (error) {
    if (request.isCurrent()) throw error;
  } finally {
    request.finish();
  }
}

// ------- 推送列表展示辅助 -------
const siteNameMap = ref(new Map<number, string>());
const columnNameMap = ref(new Map<number, string>());

async function refreshNameMaps() {
  if (!sites.value.length) {
    try {
      sites.value = await siteCatalogApi.sites();
    } catch { /* 忽略 */ }
  }
  sites.value.forEach(s => siteNameMap.value.set(Number(s.id), s.name));
  for (const row of pushConfigs.value) {
    try {
      const cols = await siteCatalogApi.columns(row.siteId);
      cols.forEach(c => columnNameMap.value.set(Number(c.id), c.className));
    } catch { /* 忽略 */ }
  }
}

function siteNameOf(id: number): string {
  return siteNameMap.value.get(Number(id)) ?? String(id);
}
function columnNameOf(id: number): string {
  return columnNameMap.value.get(Number(id)) ?? String(id);
}
function statusName(s: number): string {
  switch (Number(s)) {
    case 1: return '待审核';
    case 2: return '已审核';
    case 3: return '已发布';
    default: return '草稿';
  }
}
function mappingCountOf(row: PlatformPushConfig): number {
  if (!row.fieldMapping) return 0;
  try {
    const arr = JSON.parse(row.fieldMapping);
    return Array.isArray(arr) ? arr.length : 0;
  } catch {
    return 0;
  }
}

function normalizeField(name: string): string {
  return name.replace(/[_\-]/g, '').toLowerCase();
}

function openPushCreate() {
  editingPushId.value = null;
  pushSiteId.value = null;
  pushColumnId.value = null;
  pushColumns.value = [];
  pushColumnFields.value = [];
  pushTargetStatus.value = 0;
  mappingRows.value = generateDefaultMapping();
  pushDialogVisible.value = true;
  loadSites();
}

const pushTreeRef = ref<{ setCurrentKey: (key: string | number) => void } | null>(null);

function openPushEdit(row: PlatformPushConfig) {
  editingPushId.value = row.id ?? null;
  pushTargetStatus.value = row.targetStatus ?? 0;
  pushDialogVisible.value = true;
  loadSites().then(async () => {
    pushSiteId.value = row.siteId;
    await reloadColumns();
    pushColumnId.value = row.siteColumnId;
    const column = pushColumns.value.find(c => c.id === pushColumnId.value);
    pushColumnFields.value = column
      ? parseModelField(typeof column.modelField === 'string' ? column.modelField : null)
      : [];
    mappingRows.value = generateDefaultMapping();
    applySavedMapping(row);
    requestAnimationFrame(() => {
      pushTreeRef.value?.setCurrentKey(row.siteColumnId);
    });
  });
}

function generateDefaultMapping(): Array<{ productField: string; productLabel: string; siteField: string }> {
  return fields.value
    .filter(f => f.enabled)
    .map(f => ({ productField: f.fieldName, productLabel: f.title || f.fromName, siteField: '' }));
}

async function loadSites() {
  try {
    sites.value = await siteCatalogApi.sites();
  } catch (e) {
    ElMessage.error('站点加载失败');
  }
}

async function reloadColumns() {
  if (!pushSiteId.value) {
    pushColumns.value = [];
    pushColumnFields.value = [];
    pushColumnId.value = null;
    return;
  }
  pushColumns.value = await siteCatalogApi.columns(pushSiteId.value);
}

async function onSiteChange() {
  pushColumnId.value = null;
  pushColumnFields.value = [];
  await reloadColumns();
}

function onColumnClick(data: SiteTree) {
  pushColumnId.value = data.id;
  const column = pushColumns.value.find(c => c.id === data.id);
  pushColumnFields.value = column
    ? parseModelField(typeof column.modelField === 'string' ? column.modelField : null)
    : [];
  applyDefaultMapping();
}

/** 默认映射：产品字段与站点栏目字段按归一化名称自动匹配 */
function applyDefaultMapping() {
  const siteByNorm = new Map<string, string>();
  pushColumnFields.value.forEach(f => siteByNorm.set(normalizeField(f.fieldName), f.fieldName));
  mappingRows.value.forEach(row => {
    row.siteField = siteByNorm.get(normalizeField(row.productField)) ?? '';
    if (mappingRows.value.some(r => r !== row && r.siteField === row.siteField && row.siteField)) {
      row.siteField = '';
    }
  });
}

/** 编辑回填：解析已保存的字段映射 */
function applySavedMapping(row: PlatformPushConfig) {
  let saved: Array<{ productField: string; siteField: string }> = [];
  try {
    if (row.fieldMapping) {
      saved = JSON.parse(row.fieldMapping);
    }
  } catch {
    saved = [];
  }
  const siteByNorm = new Map<string, string>();
  pushColumnFields.value.forEach(f => siteByNorm.set(normalizeField(f.fieldName), f.fieldName));
  const savedMap = new Map(saved.map(s => [normalizeField(s.productField), s.siteField]));
  mappingRows.value.forEach(r => {
    r.siteField = savedMap.get(normalizeField(r.productField)) as string
      ?? siteByNorm.get(normalizeField(r.productField))
      ?? '';
  });
}

async function savePush() {
  if (!pushSiteId.value) {
    ElMessage.warning('请选择目标站点');
    return;
  }
  if (!pushColumnId.value) {
    ElMessage.warning('请选择目标站点栏目');
    return;
  }
  pushSaving.value = true;
  try {
    const payload = {
      configType: props.configType,
      categoryId: selectedId.value as number,
      siteId: pushSiteId.value,
      siteColumnId: pushColumnId.value,
      targetStatus: pushTargetStatus.value,
      fieldMapping: JSON.stringify(mappingRows.value.map(r => ({ productField: r.productField, siteField: r.siteField })))
    };
    if (editingPushId.value != null) {
      await props.pushConfigApi.update(editingPushId.value, payload);
    } else {
      await props.pushConfigApi.create(payload);
    }
    pushDialogVisible.value = false;
    ElMessage.success('推送配置已保存');
    await loadPushList();
  } catch (e) {
    ElMessage.error(`保存失败：${(e as Error).message ?? String(e)}`);
  } finally {
    pushSaving.value = false;
  }
}

async function removePush(row: PlatformPushConfig) {
  await ElMessageBox.confirm('确认删除该推送配置？', '删除确认', { type: 'warning' });
  await props.pushConfigApi.remove(row.id!);
  ElMessage.success('删除成功');
  await loadPushList();
}

// ------- 推送配置：下载模版 / 导入配置（CSV） -------
const importPushInputRef = ref<HTMLInputElement | null>(null);
const importErrorVisible = ref(false);
const importErrors = ref<string[]>([]);

function csvCell(value: string): string {
  const v = value ?? '';
  return /[",\r\n]/.test(v) ? `"${v.replace(/"/g, '""')}"` : v;
}

/** 极简 CSV 解析：支持双引号包裹与 "" 转义，忽略 BOM */
function parseCsv(text: string): string[][] {
  const content = text.replace(/^\uFEFF/, '');
  const rows: string[][] = [];
  let row: string[] = [];
  let cell = '';
  let inQuotes = false;
  for (let i = 0; i < content.length; i++) {
    const ch = content[i];
    if (inQuotes) {
      if (ch === '"') {
        if (content[i + 1] === '"') {
          cell += '"';
          i++;
        } else {
          inQuotes = false;
        }
      } else {
        cell += ch;
      }
    } else if (ch === '"') {
      inQuotes = true;
    } else if (ch === ',') {
      row.push(cell);
      cell = '';
    } else if (ch === '\n') {
      row.push(cell);
      rows.push(row);
      row = [];
      cell = '';
    } else if (ch !== '\r') {
      cell += ch;
    }
  }
  if (cell !== '' || row.length) {
    row.push(cell);
    rows.push(row);
  }
  return rows;
}

/** 状态文本/数字 → 0草稿 1待审 2已审核 3已发布 */
function parsePushStatus(raw: string): number | null {
  const v = raw.trim();
  if (!v) return null;
  if (/^\d+$/.test(v)) {
    const n = Number(v);
    return n >= 0 && n <= 3 ? n : null;
  }
  const map: Record<string, number> = { 草稿: 0, 待审核: 1, 已审核: 2, 已发布: 3 };
  return Object.prototype.hasOwnProperty.call(map, v) ? map[v] : null;
}

/** "产品字段:站点字段;产品字段:站点字段" → 映射数组 */
function parseMappingCell(raw: string): Array<{ productField: string; siteField: string }> {
  return raw
    .split(/[;；]/)
    .map((seg) => seg.trim())
    .filter(Boolean)
    .map((seg) => {
      const idx = seg.search(/[:：]/);
      if (idx < 0) return null;
      const productField = seg.slice(0, idx).trim();
      const siteField = seg.slice(idx + 1).trim();
      return productField && siteField ? { productField, siteField } : null;
    })
    .filter((m): m is { productField: string; siteField: string } => m !== null);
}

async function downloadPushTemplate() {
  if (selectedId.value == null) {
    ElMessage.warning('请先选择分类');
    return;
  }
  if (!sites.value.length) {
    await loadSites();
  }
  const productFields = fields.value.filter((f) => f.enabled);
  const sampleFieldNames = productFields.slice(0, 3).map((f) => f.fieldName);
  const mappingSample = sampleFieldNames.length
    ? sampleFieldNames.map((f) => `${f}:${f}`).join(';')
    : 'title:title;photo1:photo1;singleLine1:singleLine1';
  const sampleSite = sites.value[0];
  const rows = [
    ['目标站点编码', '目标栏目ID', '推送目标状态(草稿/待审核/已审核/已发布)', '字段映射(源字段:站点字段;多个用分号分隔)'],
    [
      sampleSite?.code ?? 'SITE_CODE',
      '123',
      '已发布',
      mappingSample
    ]
  ];
  const csv = '\uFEFF' + rows.map((r) => r.map(csvCell).join(',')).join('\r\n');
  const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = `推送配置模版-${selectedCategory.value?.className ?? 'category'}.csv`;
  a.click();
  URL.revokeObjectURL(url);
}

function triggerImportPush() {
  if (selectedId.value == null) {
    ElMessage.warning('请先选择分类');
    return;
  }
  importPushInputRef.value?.click();
}

async function onImportPushFile(e: Event) {
  const input = e.target as HTMLInputElement;
  const file = input.files?.[0];
  input.value = '';
  if (!file || selectedId.value == null) return;

  const errors: string[] = [];
  const payloads: Array<Record<string, unknown>> = [];

  let text: string;
  try {
    text = await file.text();
  } catch {
    ElMessage.error('读取文件失败');
    return;
  }

  const rows = parseCsv(text);
  if (rows.length < 2) {
    importErrors.value = ['文件为空或只有表头，没有可导入的配置'];
    importErrorVisible.value = true;
    return;
  }

  if (!sites.value.length) {
    await loadSites();
  }
  const siteByCode = new Map(sites.value.map((s) => [String(s.code).trim().toLowerCase(), s]));
  const columnCache = new Map<number, SiteColumnNode[]>();
  const productFieldSet = new Set(fields.value.filter((f) => f.enabled).map((f) => f.fieldName));

  for (let i = 1; i < rows.length; i++) {
    const lineNo = i + 1;
    const cells = rows[i];
    if (cells.every((c) => !c.trim())) continue;
    if (cells.length < 4) {
      errors.push(`第 ${lineNo} 行：列数不足，应为「目标站点编码,目标栏目ID,推送目标状态,字段映射」4 列`);
      continue;
    }
    const [siteCodeRaw, columnIdRaw, statusRaw, mappingRaw] = cells.map((c) => c.trim());

    const site = siteByCode.get(siteCodeRaw.toLowerCase());
    if (!site) {
      errors.push(`第 ${lineNo} 行：目标站点编码「${siteCodeRaw}」不存在`);
      continue;
    }

    const columnId = Number(columnIdRaw);
    if (!Number.isInteger(columnId) || columnId <= 0) {
      errors.push(`第 ${lineNo} 行：目标栏目ID「${columnIdRaw}」不是合法数字`);
      continue;
    }

    let cols = columnCache.get(Number(site.id));
    if (!cols) {
      try {
        cols = await siteCatalogApi.columns(Number(site.id));
      } catch {
        cols = [];
      }
      columnCache.set(Number(site.id), cols);
    }
    const column = cols.find((c) => c.id === columnId);
    if (!column) {
      errors.push(`第 ${lineNo} 行：栏目ID ${columnId} 在站点「${site.name}」下不存在`);
      continue;
    }

    const status = parsePushStatus(statusRaw);
    if (status == null) {
      errors.push(`第 ${lineNo} 行：推送目标状态「${statusRaw}」无效，应为 草稿/待审核/已审核/已发布 或 0-3`);
      continue;
    }

    const mapping = parseMappingCell(mappingRaw);
    if (!mapping.length) {
      errors.push(`第 ${lineNo} 行：字段映射为空或格式错误，应为「${sourceFieldLabel.value}:站点字段;${sourceFieldLabel.value}:站点字段」`);
      continue;
    }

    const siteFieldSet = new Set(
      parseModelField(typeof column.modelField === 'string' ? column.modelField : null).map((f) => f.fieldName)
    );
    let mappingError: string | null = null;
    for (const m of mapping) {
      if (!productFieldSet.has(m.productField)) {
        mappingError = `${sourceFieldLabel.value}「${m.productField}」不属于当前分类`;
        break;
      }
      if (!siteFieldSet.has(m.siteField)) {
        mappingError = `站点栏目字段「${m.siteField}」在目标栏目中不存在`;
        break;
      }
    }
    if (mappingError) {
      errors.push(`第 ${lineNo} 行：${mappingError}`);
      continue;
    }

    payloads.push({
      configType: props.configType,
      categoryId: selectedId.value,
      siteId: Number(site.id),
      siteColumnId: columnId,
      targetStatus: status,
      fieldMapping: JSON.stringify(mapping)
    });
  }

  if (errors.length) {
    importErrors.value = errors;
    importErrorVisible.value = true;
    return;
  }
  if (!payloads.length) {
    ElMessage.warning('没有可导入的配置');
    return;
  }

  let saved = 0;
  for (const payload of payloads) {
    try {
      await props.pushConfigApi.create(payload);
      saved++;
    } catch (err) {
      errors.push(`保存失败：${(err as Error).message ?? String(err)}`);
    }
  }
  await loadPushList();
  if (errors.length) {
    importErrors.value = errors;
    importErrorVisible.value = true;
  } else {
    ElMessage.success(`导入成功 ${saved} 条配置`);
  }
}

onMounted(async () => {
  await loadMenus();
  const category = menus.value.find(item => item.id === props.initialCategoryId);
  if (category) {
    await selectMenu({ id:category.id, className:category.className, parentId:category.parentId, sort:category.sort, children:[] });
  }
});
</script>

<template>
  <section class="cms-platform-config">
    <header class="cms-platform-config__header">
      <div>
        <h1>{{ props.moduleTitle }}</h1>
        <p>跨站点分类与元数据配置，不随站点过滤。</p>
      </div>
      <div class="cms-platform-config__header-actions">
        <el-button :loading="loading" @click="loadMenus">刷新</el-button>
        <el-button type="primary" @click="openCreate(0)">新建根分类</el-button>
      </div>
    </header>

    <div class="cms-platform-config__layout">
      <aside class="cms-platform-config__tree">
        <div class="cms-platform-config__tree-header">
          <span>分类导航</span>
          <span>{{ menus.length }} 项</span>
        </div>
        <div class="cms-platform-config__tree-body">
          <el-empty v-if="!loading && menus.length === 0" description="暂无分类，请新建" />
          <el-tree
            v-else
            v-loading="loading"
            :data="treeData"
            :props="treeProps"
            node-key="id"
            :current-node-key="selectedId ?? undefined"
            default-expand-all
            highlight-current
            @node-click="selectMenu"
          >
            <template #default="{ data }">
              <span class="cms-platform-config__tree-node">
                <span class="cms-platform-config__tree-node-label" :title="data.className">{{ data.className }}<el-tag size="small" type="info" style="margin-left: 6px">{{ data.id }}</el-tag></span>
                <span class="cms-platform-config__node-actions">
                  <el-button size="small" text type="primary" @click.stop="openCreate(data.id)">+</el-button>
                  <el-button size="small" text type="danger" @click.stop="confirmRemove(data)">删</el-button>
                </span>
              </span>
            </template>
          </el-tree>
        </div>
      </aside>

      <main class="cms-platform-config__main">
        <el-empty v-if="!selectedId" description="请先在左侧选择或新建分类" />
        <div v-else>
          <el-tabs v-model="activeTab" class="cms-platform-config__tabs">
            <el-tab-pane label="元数据设置" name="metadata">
          <div class="cms-platform-config__section-header">
            <h2>字段设置{{ selectedCategory ? ` · ${selectedCategory.className}` : '' }}</h2>
            <el-tag v-if="selectedCategory">{{ selectedCategory.id }}</el-tag>
          </div>

          <!-- 字段筛选（上方）：分组勾选 -->
          <div v-if="!fieldLoading" class="cms-platform-config__field-filter">
            <button type="button" class="cms-platform-config__field-filter-header" :aria-expanded="fieldFilterExpanded" @click="fieldFilterExpanded = !fieldFilterExpanded">
              <span>字段筛选</span>
              <span class="cms-platform-config__field-filter-count">{{ visibleFieldNames.length }}/{{ fields.length }}</span>
              <el-icon :class="{ 'is-expanded': fieldFilterExpanded }"><ArrowDown /></el-icon>
            </button>
            <el-collapse-transition>
              <div v-show="fieldFilterExpanded" class="cms-platform-config__field-filter-content">
                <el-collapse v-model="expandedFieldGroups" class="cms-platform-config__field-collapse">
                  <el-collapse-item
                    v-for="group in fieldGroups"
                    :key="group.key"
                    :name="group.key"
                  >
                    <template #title>
                      <div class="cms-platform-config__field-group-title">
                        <span>{{ group.label }}</span>
                        <span class="cms-platform-config__field-group-count">
                          {{ getGroupSelectedCount(group.key) }}/{{ group.fields.length }}
                        </span>
                      </div>
                    </template>
                    <el-checkbox-group v-model="visibleFieldNames" class="cms-platform-config__field-group-items" @change="onVisibleChange">
                      <el-checkbox
                        v-for="field in group.fields"
                        :key="field.fieldName"
                        :value="field.fieldName"
                      >
                        {{ field.fromName }}（{{ field.fieldName }}）
                      </el-checkbox>
                    </el-checkbox-group>
                  </el-collapse-item>
                </el-collapse>
              </div>
            </el-collapse-transition>
          </div>

          <el-table element-loading-text="正在加载数据…"
            v-loading="fieldLoading"
            :data="filteredFieldRows"
            stripe
            max-height="640px"
            style="width: 100%"
          >
            <el-table-column label="启用" width="60">
              <template #default="{ row }">
                <el-checkbox v-model="row.enabled" />
              </template>
            </el-table-column>
            <el-table-column label="字段名称" prop="fieldName" width="140" />
            <el-table-column label="字段标题" prop="fromName" width="130" />
            <el-table-column label="字段类型" width="110">
              <template #default="{ row }">{{ fieldTypeName(row.fieldType) }}</template>
            </el-table-column>
            <el-table-column label="列表展示" width="90">
              <template #default="{ row }">
                <el-checkbox v-model="row.isList" :disabled="!row.enabled" />
              </template>
            </el-table-column>
            <el-table-column label="搜索" width="90">
              <template #default="{ row }">
                <el-checkbox v-model="row.isSearch" :disabled="!row.enabled || ['image', 'video', 'file', 'rich', 'status'].includes(row.fieldType)" />
              </template>
            </el-table-column>
            <el-table-column label="业务名称" width="160">
              <template #default="{ row }">
                <el-input v-model="row.title" size="small" :disabled="!row.enabled" />
              </template>
            </el-table-column>
            <el-table-column label="只读" width="60">
              <template #default="{ row }">
                <el-checkbox v-model="row.isReadOnly" :disabled="!row.enabled" />
              </template>
            </el-table-column>
            <el-table-column label="字段验证" width="200">
              <template #default="{ row }">
                <el-select
                  v-model="row.validations"
                  multiple
                  size="small"
                  placeholder="选择验证"
                  :disabled="!row.enabled"
                  style="width: 100%"
                >
                  <el-option v-for="opt in VALIDATION_OPTIONS" :key="opt.value" :label="opt.label" :value="opt.value" />
                </el-select>
              </template>
            </el-table-column>
            <el-table-column label="默认值" width="130">
              <template #default="{ row }">
                <el-input v-model="row.defaultValue" size="small" :disabled="!row.enabled || inputTypeOf(row) !== ''" />
              </template>
            </el-table-column>
            <el-table-column label="输入方式" width="280">
              <template #default="{ row }">
                <div class="cms-platform-config__input-type">
                  <el-select
                    :model-value="inputTypeOf(row)"
                    size="small"
                    :disabled="!row.enabled || isInputTypeLocked(row)"
                    placeholder="手动输入"
                    style="width: 140px"
                    @change="(val: string) => onInputTypeChange(row, val)"
                  >
                    <el-option v-for="opt in inputTypeOptionsFor(row)" :key="opt.value" :label="opt.label" :value="opt.value" />
                  </el-select>
                  <el-button
                    v-if="(inputTypeOf(row) === 'enum' || needsCategoryScope(inputTypeOf(row))) && !isInputTypeLocked(row)"
                    size="small"
                    text
                    type="primary"
                    @click="openInputTypeConfig(row)"
                  >配置</el-button>
                  <el-tooltip
                    :content="inputTypeDetail(row)"
                    :disabled="!inputTypeDetail(row)"
                    placement="top"
                    effect="dark"
                  >
                    <span class="cms-platform-config__input-type-summary">{{ inputTypeSummary(row) }}</span>
                  </el-tooltip>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="提示信息" width="130">
              <template #default="{ row }">
                <el-input v-model="row.bak" size="small" :disabled="!row.enabled" />
              </template>
            </el-table-column>
            <el-table-column label="最大字符" width="120">
              <template #default="{ row }">
                <el-input-number v-model="row.maxLength" size="small" :min="0" :max="999999" controls-position="right" :disabled="!row.enabled" />
              </template>
            </el-table-column>
            <el-table-column label="排序" width="100">
              <template #default="{ row }">
                <el-input-number v-model="row.sort" size="small" :min="-9999" :max="99999" controls-position="right" :disabled="!row.enabled" />
              </template>
            </el-table-column>
          </el-table>

          <div style="margin-top: 16px">
            <el-button type="primary" :loading="saving" @click="saveFieldConfig">保存字段配置</el-button>
          </div>
            </el-tab-pane>

            <el-tab-pane label="基本信息编辑" name="edit">
              <el-form label-width="110px" style="max-width: 620px">
                <el-form-item label="分类名称" required>
                  <el-input v-model="basicForm.className" :readonly="isImportedForm" />
                  <el-text v-if="isImportedForm" type="info" size="small">分类名称用于验证导入来源，导入后固定。</el-text>
                </el-form-item>
                <el-form-item label="父级分类">
                  <el-select v-model="basicForm.parentId" filterable placeholder="选择父级分类" style="width: 100%">
                    <el-option :value="0" label="根级" />
                    <el-option v-for="m in parentOptions" :key="m.id" :label="m.className" :value="m.id" />
                  </el-select>
                </el-form-item>
                <el-form-item v-if="props.configType === 'form'" label="分类类型">
                  <el-segmented v-model="basicForm.pageType" :options="[{ label: '单篇', value: 0 }, { label: '列表', value: 1 }]" />
                </el-form-item>
                <el-form-item v-if="props.configType === 'form'" label="提交需验证码">
                  <el-switch v-model="basicForm.formCaptcha" :active-value="1" :inactive-value="0" />
                  <span style="margin-left: 8px; color: #909399; font-size: 12px;">开启后站点服务表单提交接口需带图形验证码</span>
                </el-form-item>
                <el-form-item label="可修改">
                  <el-switch v-model="basicForm.isCanEdit" />
                </el-form-item>
                <el-form-item label="可排序">
                  <el-switch v-model="basicForm.isCanSort" />
                </el-form-item>
                <el-form-item label="二级栏目">
                  <el-switch v-model="basicForm.isSubMenu" />
                  <span style="margin-left: 8px; color: #909399; font-size: 12px;">开启后，本分类在父分类的内容列表里显示为跳转列</span>
                  <template v-if="basicForm.isSubMenu">
                    <el-tag v-if="jumpFilterFieldName" size="small" type="success" style="margin-left: 8px;">关联字段名 {{ jumpFilterFieldText }}</el-tag>
                    <span v-else style="margin-left: 8px; color: #e6a23c; font-size: 12px;">未设置关联字段名（请在字段配置的「输入方式」弹窗中设置）</span>
                  </template>
                </el-form-item>
                <el-form-item>
                  <el-button type="primary" @click="saveBasic">保存基本信息</el-button>
                </el-form-item>
              </el-form>
            </el-tab-pane>

            <el-tab-pane v-if="configType !== 'form'" label="推送设置" name="push">
              <div class="cms-platform-config__push-toolbar">
                <span>已配置 {{ pushConfigs.length }} 条推送</span>
                <div class="cms-platform-config__push-toolbar-actions">
                  <el-button size="small" @click="downloadPushTemplate">下载模版</el-button>
                  <el-button size="small" @click="triggerImportPush">导入配置</el-button>
                  <el-button type="primary" size="small" @click="openPushCreate">新增推送配置</el-button>
                </div>
              </div>
              <input
                ref="importPushInputRef"
                type="file"
                accept=".csv,text/csv"
                style="display: none"
                @change="onImportPushFile"
              />
              <el-table element-loading-text="正在加载数据…" v-loading="pushLoading" :data="pushConfigs" stripe>
                <el-table-column label="序号" width="80">
                  <template #default="{ $index }">{{ $index + 1 }}</template>
                </el-table-column>
                <el-table-column :label="pushTargetLabel" width="200">
                  <template #default="{ row }">{{ siteNameOf(row.siteId) }}</template>
                </el-table-column>
                <el-table-column label="目标栏目" min-width="200" show-overflow-tooltip>
                  <template #default="{ row }">{{ columnNameOf(row.siteColumnId) }}</template>
                </el-table-column>
                <el-table-column label="推送目标状态" width="130">
                  <template #default="{ row }">{{ statusName(row.targetStatus) }}</template>
                </el-table-column>
                <el-table-column label="字段映射" width="100">
                  <template #default="{ row }">{{ mappingCountOf(row) }} 项</template>
                </el-table-column>
                <el-table-column label="操作" width="140">
                  <template #default="{ row }">
                    <el-button size="small" text type="primary" @click="openPushEdit(row)">设置</el-button>
                    <el-button size="small" text type="danger" @click="removePush(row)">删除</el-button>
                  </template>
                </el-table-column>
              </el-table>
            </el-tab-pane>

            <el-tab-pane v-if="props.configType === 'form'" label="服务接口" name="service">
              <FormServiceApiPanel />
            </el-tab-pane>
          </el-tabs>
        </div>
      </main>
    </div>

    <el-dialog v-model="createDialog" title="新建分类" width="420px">
      <el-form label-width="90px">
        <el-form-item label="分类名称" required>
          <el-input v-model="createForm.className" placeholder="请输入分类名称" />
        </el-form-item>
        <el-form-item label="父级分类">
          <el-input :model-value="createForm.parentId === 0 ? '根级' : '(指定父级)'" disabled />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createDialog = false">取消</el-button>
        <el-button type="primary" @click="confirmCreate">确定</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="enumEditorVisible" title="设置固定枚举值" width="420px">
      <div v-if="enumEditingRow">
        <p class="cms-platform-config__dialog-hint">字段：{{ enumEditingRow.fromName }}（{{ enumEditingRow.fieldName }}），选项值即存储与显示值</p>
        <el-form label-width="80px">
          <el-form-item label="选择方式">
            <el-radio-group v-model="enumEditingRow.inputMultiple">
              <el-radio :value="false">单选</el-radio>
              <el-radio :value="true">多选</el-radio>
            </el-radio-group>
          </el-form-item>
        </el-form>
        <div v-for="(item, index) in enumEditorRows" :key="index" class="cms-platform-config__enum-row">
          <el-input v-model="item.value" placeholder="枚举值" />
          <el-button v-if="enumEditorRows.length > 1" type="danger" text size="small" @click="enumEditorRows.splice(index, 1)">移除</el-button>
        </div>
        <el-button size="small" type="primary" plain @click="enumEditorRows.push({ value: '' })">添加枚举值</el-button>
      </div>
      <template #footer>
        <el-button @click="enumEditorVisible = false">取消</el-button>
        <el-button type="primary" @click="saveEnumConfig">确定</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="categoryPickerVisible" :title="pickerIsContent ? '选择类别（取其下内容）' : '选择范围类别'" width="620px">
      <el-form label-width="100px">
        <el-form-item v-if="pickerIsCross" label="所属中心">
          <el-select :model-value="pickerDataType ?? undefined" style="width: 100%" @change="(v: number) => onPickerDataTypeChange(v)">
            <el-option v-for="opt in crossCenterOptions" :key="opt.value" :label="opt.label" :value="opt.value" />
          </el-select>
        </el-form-item>
        <el-form-item v-else label="所属中心">
          <el-input :model-value="centerLabel(currentDataType)" disabled />
        </el-form-item>
        <el-form-item label="选择方式">
          <el-radio-group v-if="categoryEditingRow" v-model="categoryEditingRow.inputMultiple">
            <el-radio :value="false">单选</el-radio>
            <el-radio :value="true">多选</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="父栏目跳转">
          <el-checkbox v-model="jumpFilterChecked">设为父栏目跳转筛选字段</el-checkbox>
        </el-form-item>
      </el-form>
      <p class="cms-platform-config__dialog-hint">
        {{ pickerIsContent
          ? '选择后该字段在内容编辑器渲染为内容下拉（取该类别下内容，显示标题、存内容 id）'
          : '选择后该字段在内容编辑器渲染为类别下拉（取该类别及其子类别，存所选类别 id）' }}
        <br />勾选「设为父栏目跳转筛选字段」后，点击「保存字段配置」保存。
      </p>
      <div v-loading="pickerLoading" class="cms-platform-config__picker">
        <el-tree
          v-if="pickerTree.length"
          :data="pickerTree"
          :props="pickerTreeProps"
          node-key="id"
          default-expand-all
          highlight-current
          @node-click="onPickCategory"
        >
          <template #default="{ data }">
            <span>{{ data.className }}<el-tag size="small" type="info" style="margin-left: 6px">{{ data.id }}</el-tag></span>
          </template>
        </el-tree>
        <el-empty v-else-if="!pickerLoading" description="该中心暂无分类" :image-size="60" />
      </div>
      <template #footer>
        <el-button @click="categoryPickerVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="savePickerConfig">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-if="configType !== 'form'" v-model="pushDialogVisible" :title="editingPushId != null ? '编辑推送配置' : '新增推送配置'" width="920px">
      <el-form label-width="90px" style="margin-bottom: 12px">
        <el-form-item :label="pushTargetLabel" required>
          <el-select v-model="pushSiteId" placeholder="请选择站点" filterable style="width: 320px" @change="onSiteChange">
            <el-option v-for="s in sites" :key="s.id" :label="`${s.name} (${s.code})`" :value="Number(s.id)" />
          </el-select>
        </el-form-item>
        <el-form-item label="目标栏目" required>
          <div v-if="pushColumnsTree.length" class="cms-platform-config__push-tree">
            <el-tree
              ref="pushTreeRef"
              :data="pushColumnsTree"
              :props="pushTreeProps"
              node-key="id"
              default-expand-all
              highlight-current
              @node-click="onColumnClick"
            >
              <template #default="{ data }">
                <span>{{ data.className }}<el-tag size="small" type="info" style="margin-left: 6px">{{ data.id }}</el-tag></span>
              </template>
            </el-tree>
          </div>
          <el-empty v-else description="请先选择站点" :image-size="60" />
        </el-form-item>
        <el-form-item label="推送目标状态">
          <el-select v-model="pushTargetStatus" style="width: 200px">
            <el-option label="草稿" :value="0" />
            <el-option label="待审核" :value="1" />
            <el-option label="已审核" :value="2" />
            <el-option label="已发布" :value="3" />
          </el-select>
        </el-form-item>
      </el-form>

      <div class="cms-platform-config__section-header">
        <h2>字段映射</h2>
        <span v-if="pushColumnFields.length" class="cms-platform-config__push-hint">
          已按{{ sourceFieldLabel }}与所选栏目字段自动匹配同名，可手动修改
        </span>
      </div>
      <el-table :data="mappingRows" stripe max-height="360px">
        <el-table-column :label="sourceFieldLabel" width="220">
          <template #default="{ row }">
            <span>{{ row.productLabel }}（{{ row.productField }}）</span>
          </template>
        </el-table-column>
        <el-table-column label="→ 站点栏目字段">
          <template #default="{ row }">
            <el-select v-model="row.siteField" placeholder="选择映射字段" filterable clearable style="width: 100%">
              <el-option v-for="f in pushColumnFields" :key="f.fieldName" :label="`${f.title || f.fromName}（${f.fieldName}）`" :value="f.fieldName" />
            </el-select>
          </template>
        </el-table-column>
      </el-table>

      <template #footer>
        <el-button @click="pushDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="pushSaving" @click="savePush">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="importErrorVisible" title="导入校验未通过" width="600px">
      <ul class="cms-platform-config__import-errors">
        <li v-for="(msg, idx) in importErrors" :key="idx">{{ msg }}</li>
      </ul>
      <template #footer>
        <el-button type="primary" @click="importErrorVisible = false">知道了</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style>
.cms-platform-config {
  display: flex;
  flex-direction: column;
  height: 100%;
  padding: 0;
  gap: var(--admin-card-gap);
}

.cms-platform-config__header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.cms-platform-config__header h1 { margin: 0; font-size: var(--admin-font-size-page); }
.cms-platform-config__header p { margin: 4px 0 0; color: var(--el-text-color-secondary); font-size: 13px; }
.cms-platform-config__header-actions { display: flex; flex-wrap: wrap; justify-content: flex-end; gap: 8px; }

.cms-platform-config__layout {
  display: flex;
  flex: 1;
  gap: var(--admin-card-gap);
  min-height: 0;
}

.cms-platform-config__tree {
  width: 320px;
  flex-shrink: 0;
  border: 1px solid var(--el-border-color-light);
  border-radius: var(--admin-card-radius);
  background: var(--el-bg-color);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.cms-platform-config__tree-header {
  padding: 10px 12px;
  border-bottom: 1px solid var(--el-border-color-light);
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-weight: 600;
}
.cms-platform-config__tree-body {
  flex: 1;
  overflow: auto;
  padding: 8px;
}
.cms-platform-config__tree .el-tree-node__content {
  height: 30px;
}
.cms-platform-config__tree-node {
  flex: 1;
  display: flex;
  justify-content: space-between;
  align-items: center;
  min-width: 0;
}
.cms-platform-config__tree-node-label {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.cms-platform-config__tree-node .cms-platform-config__node-actions {
  visibility: hidden;
  white-space: nowrap;
}
.cms-platform-config__tree-node:hover .cms-platform-config__node-actions {
  visibility: visible;
}
.cms-platform-config__tree-node:focus-within .cms-platform-config__node-actions {
  visibility: visible;
}

.cms-platform-config__main {
  flex: 1;
  border: 1px solid var(--el-border-color-light);
  border-radius: var(--admin-card-radius);
  background: var(--el-bg-color);
  overflow: auto;
  padding: var(--admin-card-padding-compact);
  min-width: 0;
}

.cms-platform-config__section-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}
.cms-platform-config__section-header h2 { margin: 0; font-size: 16px; }

.cms-platform-config__field-filter {
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  margin-bottom: 12px;
}
.cms-platform-config__field-filter-header {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 12px;
  cursor: pointer;
  font-weight: 600;
  user-select: none;
  width: 100%;
  border: 0;
  background: transparent;
  color: inherit;
  font: inherit;
  text-align: left;
}
.cms-platform-config__field-filter-count {
  color: var(--el-text-color-secondary);
  font-weight: 400;
  font-size: 12px;
}
.cms-platform-config__field-filter-header .el-icon {
  transition: transform 0.2s ease;
  margin-left: auto;
}
.cms-platform-config__field-filter-header .el-icon.is-expanded {
  transform: rotate(180deg);
}
.cms-platform-config__field-filter-content {
  border-top: 1px solid var(--el-border-color-lighter);
  padding: 4px 12px 12px;
}
.cms-platform-config__field-group-title {
  display: flex;
  justify-content: space-between;
  align-items: center;
  width: 100%;
  padding-right: 8px;
}
.cms-platform-config__field-group-count {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.cms-platform-config__field-group-items {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: 4px 12px;
}
.cms-platform-config__field-group-items .el-checkbox {
  margin-right: 0;
}

.cms-platform-config__tabs {
  height: 100%;
}
.cms-platform-config__push-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}
.cms-platform-config__push-toolbar-actions {
  display: flex;
  gap: 8px;
}
.cms-platform-config__import-errors {
  margin: 0;
  padding-left: 18px;
  max-height: 320px;
  overflow: auto;
  line-height: 1.9;
  color: var(--el-color-danger);
}
.cms-platform-config__push-tree {
  max-height: 320px;
  overflow: auto;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  padding: 8px;
  width: 100%;
}
.cms-platform-config__push-hint {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.cms-platform-config__input-type {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}
.cms-platform-config__input-type-summary {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.cms-platform-config__dialog-hint {
  margin: 0 0 10px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.cms-platform-config__enum-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}
.cms-platform-config__picker {
  max-height: 320px;
  overflow: auto;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  padding: 8px;
  min-height: 120px;
}

@media (max-width: 900px) {
  .cms-platform-config { height: auto; min-height: 100%; padding: 0; }
  .cms-platform-config__header { align-items: flex-start; gap: 12px; flex-wrap: wrap; }
  .cms-platform-config__layout { flex-direction: column; }
  .cms-platform-config__tree { width: auto; min-height: 180px; max-height: 300px; }
  .cms-platform-config__main { min-height: 520px; }
}

@media (max-width: 640px) {
  .cms-platform-config__header-actions { justify-content: flex-start; }
  .cms-platform-config__field-group-items { grid-template-columns: 1fr; }
  .cms-platform-config__push-toolbar, .cms-platform-config__section-header { align-items: flex-start; flex-wrap: wrap; gap: 8px; }
}
</style>
