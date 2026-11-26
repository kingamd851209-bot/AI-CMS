/**
 * 内容中台 产品/新闻 分类的元数据字段模型。
 * 配置端把用户勾选的字段组装为 model_field/model_list/model_search JSON 存入 t_*_oneshot；
 * 中心端按同样结构解析动态字段渲染表单与列表。
 */

export interface CategoryRef {
  /** 类别所属中心：0表单 1产品 2新闻 3案例 4解决方案 */
  dataType: number;
  /** 所选范围类别 id（类别字段取其子类别，类别内容字段取其下内容） */
  categoryId: number;
  /** 所选范围类别名称（配置端展示用） */
  categoryName?: string;
}

export interface ModelField {
  fieldName: string;
  fromName: string;
  fieldType: string;
  title: string;
  defaultValue?: string;
  /**
   * 输入方式：
   * ''=手动输入，enum=固定枚举，categorySame=同类类别，categoryCross=跨类类别，
   * contentSame=同类类别内容，contentCross=跨类类别内容
   */
  inputType?: string;
  /** 固定枚举值（inputType=enum 时） */
  enumValues?: string[];
  /** 范围类别（类别类或类别内容类输入方式时使用） */
  categoryRef?: CategoryRef;
  /**
   * 选择方式：true=多选。仅对枚举/类别/类别内容类输入方式有效，
   * 多选值以 ,a,b, 形式存入文本列。
   */
  inputMultiple?: boolean;
  /** 字段验证：required/tel/email/idCard/number/webUrl */
  validations?: string[];
  /** 提示信息，内容编辑器用作 placeholder */
  bak?: string;
  maxLength?: number;
  enabled: boolean;
  isList: boolean;
  isSearch: boolean;
  isReadOnly: boolean;
  sort: number;
}

/** 输入方式选项 */
export const INPUT_TYPE_OPTIONS: Array<{ label: string; value: string }> = [
  { label: '手动输入', value: '' },
  { label: '固定枚举', value: 'enum' },
  { label: '同类类别', value: 'categorySame' },
  { label: '跨类类别', value: 'categoryCross' },
  { label: '同类类别内容', value: 'contentSame' },
  { label: '跨类类别内容', value: 'contentCross' }
];

export const INPUT_TYPE_NAMES: Record<string, string> = {
  '': '手动输入',
  enum: '固定枚举',
  categorySame: '同类类别',
  categoryCross: '跨类类别',
  contentSame: '同类类别内容',
  contentCross: '跨类类别内容'
};

/** 是否为类别类输入方式（下拉取范围类别下的子类别） */
export function isCategoryInputType(type?: string): boolean {
  return type === 'categorySame' || type === 'categoryCross';
}

/** 是否为类别内容类输入方式（下拉取范围类别下的内容） */
export function isContentInputType(type?: string): boolean {
  return type === 'contentSame' || type === 'contentCross';
}

/** 是否需要选择范围类别 */
export function needsCategoryScope(type?: string): boolean {
  return isCategoryInputType(type) || isContentInputType(type);
}

/** 是否支持「单选 / 多选」设置（固定枚举与类别/类别内容类输入方式） */
export function supportsMultipleChoice(type?: string): boolean {
  return type === 'enum' || isCategoryInputType(type) || isContentInputType(type);
}

/**
 * 多选值序列化为 ,a,b, / 解析回数组；实现见 utils/multiValue（站点侧与中台共用）。
 */
export { packMultiValue, unpackMultiValue } from '@/utils/multiValue';

/** 范围类别来源：same=本中心，cross=其他中心 */
export function categoryScopeOf(type?: string): 'same' | 'cross' | '' {
  if (type === 'categorySame' || type === 'contentSame') return 'same';
  if (type === 'categoryCross' || type === 'contentCross') return 'cross';
  return '';
}

/** 取 rootId 及其全部子孙 id（用于「类别」字段下拉范围） */
export function subtreeIdsOf(nodes: Array<{ id: number; parentId: number }>, rootId: number): Set<number> {
  const childrenOf = new Map<number, number[]>();
  for (const node of nodes) {
    const arr = childrenOf.get(node.parentId) ?? [];
    arr.push(node.id);
    childrenOf.set(node.parentId, arr);
  }
  const result = new Set<number>();
  const stack = [rootId];
  while (stack.length) {
    const current = stack.pop() as number;
    if (result.has(current)) continue;
    result.add(current);
    for (const child of childrenOf.get(current) ?? []) stack.push(child);
  }
  return result;
}

/** 字段验证选项（与 /admin/v2/cms/menus 字段设置一致） */
export const VALIDATION_OPTIONS: Array<{ label: string; value: string }> = [
  { label: '必填', value: 'required' },
  { label: '电话', value: 'tel' },
  { label: '邮箱', value: 'email' },
  { label: '身份证', value: 'idCard' },
  { label: '正整数', value: 'number' },
  { label: '网址', value: 'webUrl' }
];

/** 字段验证规则与方法 */
export const VALIDATION_RULES: Record<string, { test: RegExp; message: string }> = {
  tel: { test: /^(\+?\d{6,20}|1[3-9]\d{9})$/, message: '请填写正确的电话' },
  email: { test: /^[\w.+-]+@[\w-]+(\.[\w-]+)+$/, message: '请填写正确的邮箱' },
  idCard: { test: /^\d{15}(\d{2}[\dXx])?$/, message: '请填写正确的身份证号' },
  number: { test: /^\d+$/, message: '请填写正整数' },
  webUrl: { test: /^(https?:\/\/|\/\/)[^\s]+$/i, message: '请填写正确的网址' }
};

/** 中心数据类型：1产品 2新闻 3案例 4解决方案 */
export const DATA_TYPE_LABELS: Record<number, string> = {
  1: '产品',
  2: '新闻',
  3: '案例',
  4: '解决方案'
};

/** 数据类型 → 配置模块段（用于访问对应中心的分类接口） */
export const DATA_TYPE_MODULES: Record<number, 'product' | 'news' | 'case' | 'solution'> = {
  1: 'product',
  2: 'news',
  3: 'case',
  4: 'solution'
};

/** 配置类型 → 数据类型；表单中心无 data_type，返回 null */
export function dataTypeOfConfigType(configType: string): number | null {
  const map: Record<string, number> = { product: 1, news: 2, case: 3, solution: 4 };
  return map[configType] ?? null;
}

/**
 * 校验单个字段值，返回首个错误信息；通过返回 null。
 * 枚举/类别字段由下拉保证取值，只做必填校验。
 */
export function validateFieldValue(field: ModelField, value: unknown): string | null {
  const validations = field.validations ?? [];
  if (validations.length === 0) return null;
  const text = value == null ? '' : String(value).trim();
  if (validations.includes('required') && text === '') {
    return `${field.title || field.fromName || field.fieldName}为必填`;
  }
  if (text === '') return null;
  for (const key of validations) {
    const rule = VALIDATION_RULES[key];
    if (rule && !rule.test.test(text)) {
      return `${field.title || field.fromName || field.fieldName}：${rule.message}`;
    }
  }
  return null;
}

export type FieldType = 'text' | 'textarea' | 'rich' | 'image' | 'video' | 'file' | 'datetime' | 'number' | 'status';

export interface FieldPoolItem {
  fieldName: string;
  fromName: string;
  fieldType: FieldType;
  always?: boolean;
}

/** 字段池：与 t_*_contents 的全部字符段对应（对齐栏目管理字段筛选数量；tags/hits/sort/status 为系统字段不参与配置） */
function buildPool(): FieldPoolItem[] {
  const pool: FieldPoolItem[] = [
    { fieldName: 'title', fromName: '标题', fieldType: 'text', always: true },
    { fieldName: 'beginTime', fromName: '开始时间', fieldType: 'datetime' },
    { fieldName: 'endTime', fromName: '结束时间', fieldType: 'datetime' },
    { fieldName: 'createTime', fromName: '创建时间', fieldType: 'datetime' }
  ];
  for (let i = 1; i <= 20; i++) pool.push({ fieldName: `singleLine${i}`, fromName: `单行文本${i}`, fieldType: 'text' });
  for (let i = 1; i <= 15; i++) pool.push({ fieldName: `note${i}`, fromName: `多行文本${i}`, fieldType: 'textarea' });
  for (let i = 1; i <= 15; i++) pool.push({ fieldName: `photo${i}`, fromName: `图片${i}`, fieldType: 'image' });
  for (let i = 1; i <= 6; i++) pool.push({ fieldName: `content${i}`, fromName: `富文本${i}`, fieldType: 'rich' });
  for (let i = 1; i <= 4; i++) pool.push({ fieldName: `media${i}`, fromName: `视频${i}`, fieldType: 'video' });
  for (let i = 1; i <= 4; i++) pool.push({ fieldName: `file${i}`, fromName: `文件${i}`, fieldType: 'file' });
  return pool;
}

export const MODEL_FIELD_POOL: FieldPoolItem[] = buildPool();

export const FIELD_TYPE_NAMES: Record<FieldType, string> = {
  text: '单行文本',
  textarea: '多行文本',
  rich: '富文本',
  image: '图片',
  video: '视频',
  file: '文件',
  datetime: '时间',
  number: '数字',
  status: '状态'
};

type StoredField = Record<string, unknown> & {fieldName:string};
type FormModel = {modelField?:string;modelList?:string;modelSearch?:string};
const nativeValidationKeys = {
  required:'isRequired', tel:'isTel', email:'isEmail', idCard:'isIdCard', number:'isNumber', webUrl:'isWebUrl'
} as const;
const nativeTypes: Record<string,string> = {SingleLine:'text', MultiLine:'textarea'};

function readStoredFields(json?: string | null): StoredField[] {
  if (!json) {
    return [];
  }
  try {
    const parsed = JSON.parse(json);
    if (!Array.isArray(parsed)) {
      return [];
    }
    return parsed.filter(row => row && typeof row.fieldName === 'string');
  } catch {
    return [];
  }
}

export function parseModelField(json?: string | null): ModelField[] {
  return readStoredFields(json).map((raw, index) => {
    const row = raw as unknown as ModelField;
    const validations = Array.isArray(row.validations) ? row.validations : [];
    return {
      ...row,
      fieldType: nativeTypes[row.fieldType] ?? row.fieldType,
      enabled: 'isOpen' in raw ? raw.isOpen === 'on' : row.enabled === true,
      isList: row.isList === true || raw.isList === 'on',
      isSearch: row.isSearch === true || raw.isSearch === 'on',
      isReadOnly: row.isReadOnly === true || raw.isReadOnly === 'on',
      sort: typeof row.sort === 'number' ? row.sort : index,
      inputMultiple: row.inputMultiple === true,
      validations: [
        ...validations.filter(value => !(value in nativeValidationKeys)),
        ...Object.entries(nativeValidationKeys).filter(([value, key]) =>
          key in raw ? raw[key] === 'on' : validations.includes(value)
        ).map(([value]) => value)
      ],
      enumValues: row.inputType === 'enum' && typeof row.defaultValue === 'string' &&
          ('isOpen' in raw || !Array.isArray(row.enumValues))
        ? [...new Set(row.defaultValue.split(',').map(value => value.trim()).filter(Boolean))]
        : Array.isArray(row.enumValues) ? row.enumValues : []
    };
  });
}

export function defaultRowsFromPool(): ModelField[] {
  return MODEL_FIELD_POOL.map((item, index) => ({
    fieldName: item.fieldName,
    fromName: item.fromName,
    fieldType: item.fieldType,
    title: item.fromName,
    defaultValue: '',
    inputType: '',
    enumValues: [],
    categoryRef: undefined,
    inputMultiple: false,
    validations: [],
    bak: '',
    maxLength: 0,
    enabled: Boolean(item.always),
    isList: item.fieldName === 'title',
    isSearch: item.fieldName === 'title',
    isReadOnly: false,
    sort: index
  }));
}

/**
 * 把行合并入当前分类的配置：未出现在池中的字段保留，池中字段按勾选状态覆盖。
 */
export function togglePoolRows(rows: ModelField[], poolRows: ModelField[]): ModelField[] {
  const byName = new Map(poolRows.map(r => [r.fieldName, r]));
  const result = [...poolRows];
  const existing = new Map(rows.map(r => [r.fieldName, r]));
  for (const poolRow of poolRows) {
    const cur = existing.get(poolRow.fieldName);
    if (cur) {
      Object.assign(poolRow, cur);
    }
    byName.set(poolRow.fieldName, poolRow);
  }
  // 保留池子外已配置字段（避免丢数据）
  for (const row of rows) {
    if (!byName.has(row.fieldName)) {
      result.push(row);
    }
  }
  return result;
}

/** 组装三份 JSON 存入分类表 */
export function buildModelPayload(rows: ModelField[]) {
  const enabled = rows
    .filter(r => r.enabled)
    .sort((a, b) => a.sort - b.sort)
    .map(r => ({ ...r }));
  return {
    modelField: JSON.stringify(enabled),
    modelList: JSON.stringify(enabled.filter(r => r.isList)),
    modelSearch: JSON.stringify(enabled.filter(r => r.isSearch))
  };
}

export function loadFormModelRows(model: FormModel): ModelField[] {
  const configured = parseModelField(model.modelField);
  const pool = defaultRowsFromPool();
  if (!configured.length) return pool;
  const listNames = new Set(readStoredFields(model.modelList).map(row => row.fieldName));
  const searchNames = new Set(readStoredFields(model.modelSearch).map(row => row.fieldName));
  const rows = configured.map(row => ({
    ...row,
    // 原生枚举借用 defaultValue 存选项，手动输入的默认值由编辑者单独设置。
    defaultValue: 'isOpen' in row && row.inputType === 'enum' ? '' : row.defaultValue,
    isList: model.modelList == null ? row.isList : listNames.has(row.fieldName),
    isSearch: model.modelSearch == null ? row.isSearch : searchNames.has(row.fieldName)
  }));
  const configuredNames = new Set(rows.map(row => row.fieldName));
  return [...rows, ...pool.filter(row => !configuredNames.has(row.fieldName)).map(row => ({
    ...row, enabled:false, isList:false, isSearch:false
  }))];
}

/** 保存原生表单时仅写入实际编辑，保留导入回执校验所用的源属性。 */
export function buildFormModelPayload(rows: ModelField[], model: FormModel) {
  const original = new Map(loadFormModelRows(model).map(row => [row.fieldName, row]));
  const changed = (row: ModelField, before: ModelField | undefined, key: keyof ModelField) =>
    JSON.stringify(row[key]) !== JSON.stringify(before?.[key]);
  const fields = [
    'fieldType','title','fromName','bak','maxLength','inputType','categoryRef','inputMultiple','sort'
  ] as const;
  const switches = ['enabled','isList','isSearch'] as const;

  function serialize(key: keyof FormModel, membership?: 'isList' | 'isSearch') {
    const stored = readStoredFields(model[key]);
    const byName = new Map(stored.map(row => [row.fieldName, row]));
    const modelRows = new Map(readStoredFields(model.modelField).map(row => [row.fieldName, row]));
    const included = (row: ModelField) => membership ? row.enabled && row[membership] : row.enabled || byName.has(row.fieldName);
    const relevant = rows.filter(included);
    const wasRelevant = [...original.values()].filter(included);
    const propertyChanged = (row: ModelField, before: ModelField | undefined) =>
      [...fields,'validations','enumValues','defaultValue','isReadOnly','enabled', ...switches.filter(flag => flag in (byName.get(row.fieldName) || {}))]
        .some(property => changed(row, before, property as keyof ModelField));
    if (model[key] != null && (stored.length || !relevant.length) && relevant.length === wasRelevant.length &&
        relevant.every(row => wasRelevant.some(before => before.fieldName === row.fieldName)
          && !propertyChanged(row, original.get(row.fieldName)))) return model[key];

    const names = new Map(relevant.map(row => [row.fieldName, row]));
    const order = [...new Set([...byName.keys(), ...names.keys()])];
    const orderedRows = order.flatMap(name => {
      const row = names.get(name);
      return row ? [row] : [];
    });
    if (relevant.some(row => changed(row, original.get(row.fieldName), 'sort'))) {
      orderedRows.sort((a, b) => (a.sort ?? 0) - (b.sort ?? 0));
    }
    return JSON.stringify(orderedRows.map(row => {
      const name = row.fieldName;
      const raw = byName.get(name);
      const before = original.get(name);
      const result: StoredField = raw ? {...raw} : {fieldName:name};
      const isNative = !raw || 'isOpen' in raw || 'isOpen' in (modelRows.get(name) || {});
      for (const property of fields) if (!raw || changed(row, before, property)) {
        result[property] = property === 'fieldType' && isNative
          ? (row.fieldType === 'text' ? 'SingleLine' : row.fieldType === 'textarea' ? 'MultiLine' : row.fieldType)
          : row[property];
      }
      for (const flag of switches) if ((!raw && !isNative) || (raw && flag in raw)) result[flag] = row[flag];
      if (isNative) result.isOpen = row.enabled ? 'on' : 'off';
      if (!raw || changed(row, before, 'isReadOnly')) result.isReadOnly = isNative ? (row.isReadOnly ? 'on' : 'off') : row.isReadOnly;
      if (!raw || changed(row, before, 'validations')) {
        if (isNative) for (const [value, flag] of Object.entries(nativeValidationKeys)) result[flag] = row.validations?.includes(value) ? 'on' : 'off';
        if (!isNative || 'validations' in result) result.validations = row.validations;
      }
      if (!raw || changed(row, before, 'enumValues') || changed(row, before, 'inputType') || changed(row, before, 'defaultValue')) {
        result.defaultValue = isNative && row.inputType === 'enum' ? row.enumValues?.join(',') : row.defaultValue;
        if (!isNative || 'enumValues' in result) result.enumValues = row.enumValues;
      }
      return result;
    }));
  }
  return {modelField:serialize('modelField'), modelList:serialize('modelList','isList'), modelSearch:serialize('modelSearch','isSearch')};
}

/** 列表展示列（modelList 解析） */
export function listColumnsFrom(json?: string | null): ModelField[] {
  return parseModelField(json).filter(f => f.enabled);
}

/** 搜索字段（modelSearch 解析） */
export function searchFieldsFrom(json?: string | null): ModelField[] {
  return parseModelField(json);
}
