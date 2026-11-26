export interface ImportField {
  fieldName: string;
  fieldType: string;
  fromName?: string;
  title?: string;
  isOpen: string;
  sort?: number;
  publicSearch?: boolean;
  isRequired?: string;
  isList?: string;
  isSearch?: string;
}

export interface ImportRecord {
  sort: number;
  values: Record<string, string | number | boolean | null> & { title: string };
}

export interface ImportSection {
  sectionMenuId: number;
  listMenuId: number;
  listFields: ImportField[];
  sectionFields?: ImportField[];
  records: ImportRecord[];
}

export interface ReviewedListImport {
  siteId: number;
  packageVersion: string;
  parentMenuId?: number;
  sections: ImportSection[];
}

interface ExistingRow {
  id: number;
  title: string;
  sort: number;
  status: number;
  values: Record<string, unknown>;
}

const recordField = /^(?:title|singleLine[1-9]\d*|note[1-9]\d*|photo[1-9]\d*|content[1-9]\d*|file[1-9]\d*|media[1-9]\d*|ext\.[a-zA-Z][a-zA-Z0-9_]{0,47})$/;

function object(value: unknown, label: string): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error(label + '须为对象');
  return value as Record<string, unknown>;
}

function id(value: unknown, label: string): number {
  if (typeof value !== 'number' || !Number.isInteger(value) || value < 1 || value > 2147483647) throw new Error(label + '须为正整数');
  return value;
}

function fields(value: unknown, type: number): ImportField[] {
  const definition = object(value, '字段请求');
  if (definition.type !== type || !Array.isArray(definition.fieldOptions) || !definition.fieldOptions.length)
    throw new Error('字段请求类型或字段清单无效');
  const names = new Set<string>();
  return definition.fieldOptions.map(item => {
    const field = object(item, '字段');
    const {fieldName, fieldType, isOpen, fromName, title, sort, publicSearch} = field;
    if (typeof fieldName !== 'string' || !recordField.test(fieldName) || names.has(fieldName)
        || typeof fieldType !== 'string' || !/^[A-Za-z][A-Za-z0-9]*$/.test(fieldType) || isOpen !== 'on')
      throw new Error('字段名称、类型或启用状态无效：' + String(fieldName));
    names.add(fieldName);
    return {
      fieldName, fieldType, isOpen,
      fromName: typeof fromName === 'string' ? fromName : fieldName,
      title: typeof title === 'string' ? title : fieldName,
      sort: sort === undefined ? undefined : id(sort, '字段排序'),
      publicSearch: publicSearch === true
    };
  });
}

export function parseReviewedListImport(raw: unknown): ReviewedListImport {
  const input = object(raw, '导入内容');
  const siteId = id(input.siteId, '站点 ID');
  const parentMenuId = input.parentMenuId === undefined ? undefined : id(input.parentMenuId, '聚合栏目 ID');
  if (typeof input.packageVersion !== 'string' || !input.packageVersion.trim()) throw new Error('缺少已审来源整包版本');
  if (!Array.isArray(input.sections) || !input.sections.length) throw new Error('缺少目标栏目');
  const commonFields = input.listFieldRequest === undefined ? undefined : fields(input.listFieldRequest, 0);
  const listIds = new Set<number>();
  const sections = input.sections.map((item): ImportSection => {
    const section = object(item, '栏目');
    const sectionMenuId = id(section.sectionMenuId, '父栏目 ID');
    const listMenuId = id(section.listMenuId, '列表栏目 ID');
    if (sectionMenuId === listMenuId || listIds.has(listMenuId)) throw new Error('列表栏目重复或父子关系无效：' + listMenuId);
    listIds.add(listMenuId);
    const listFields = section.listFieldRequest === undefined ? commonFields : fields(section.listFieldRequest, 0);
    if (!listFields?.some(f => f.fieldName === 'title')) throw new Error('未声明原生标题字段：' + listMenuId);
    const sectionFields = section.sectionFieldRequest === undefined ? undefined : fields(section.sectionFieldRequest, 1);
    const keys = new Set(listFields.map(f => f.fieldName));
    if (!Array.isArray(section.records) || !section.records.length) throw new Error('缺少已审列表记录：' + listMenuId);
    const records = section.records.map((item): ImportRecord => {
      const record = object(item, '记录');
      const values = object(record.values, '记录字段');
      for (const [key, value] of Object.entries(values)) {
        if (!keys.has(key) || (value !== null && !['string', 'number', 'boolean'].includes(typeof value)))
          throw new Error('记录含未声明或无法导入的字段：' + key);
      }
      if (typeof values.title !== 'string' || !values.title.trim() || values.title.trim() !== values.title)
        throw new Error('记录须有明确标题且首尾无空格');
      return { sort: id(record.sort, '记录排序'), values: values as ImportRecord['values'] };
    });
    return { sectionMenuId, listMenuId, listFields, sectionFields, records };
  });
  return { siteId, parentMenuId, packageVersion: input.packageVersion, sections };
}

export function planFields(current: ImportField[], requested: ImportField[], catalog: ImportField[], narrow = false) {
  const next = [...current];
  const enabled: string[] = [];
  const disabled: string[] = [];
  const conflicts: string[] = [];
  for (const field of requested) {
    const index = next.findIndex(option => option.fieldName === field.fieldName);
    const old = next[index];
    if (old && old.fieldType !== field.fieldType) {
      conflicts.push(field.fieldName + ' 已配置为 ' + old.fieldType);
      continue;
    }
    if (!old && !catalog.some(option => option.fieldName === field.fieldName && option.fieldType === field.fieldType)) {
      conflicts.push(field.fieldName + ' 不在可用字段目录');
      continue;
    }
    if (!old) next.push(field);
    else if (old.isOpen !== 'on') next[index] = {...old, isOpen: 'on'};
    if (!old || old.isOpen !== 'on') enabled.push(field.fieldName);
  }
  if (narrow && !conflicts.length) {
    const keep = new Set(requested.map(field => field.fieldName));
    next.forEach((old, index) => {
      if (old.isOpen === 'on' && !keep.has(old.fieldName)) {
        next[index] = {...old, isOpen: ''};
        disabled.push(old.fieldName);
      }
    });
  }
  return { next, enabled, disabled, conflicts };
}

export function planRecords(requested: ImportRecord[], existing: ExistingRow[],
  normalizeValue: (key: string, value: unknown) => unknown = (_, value) => value) {
  const titles = new Set<string>();
  const duplicates = new Set<string>();
  for (const { values } of requested) {
    if (titles.has(values.title)) duplicates.add(values.title);
    titles.add(values.title);
  }
  const byTitle = new Map<string, ExistingRow[]>();
  for (const row of existing) {
    if (!titles.has(row.title)) continue;
    byTitle.set(row.title, [...(byTitle.get(row.title) || []), row]);
  }
  return requested.map(record => {
    const existingRows = byTitle.get(record.values.title) ?? [];
    const row = existingRows[0];
    if (duplicates.has(record.values.title) || existingRows.length > 1)
      return { record, action: 'conflict' as const, detail: '有重名记录，无法唯一确认' };
    if (!row) return { record, action: 'create' as const, detail: '新建并审核' };
    const mismatch = Object.entries(record.values).find(([key, value]) =>
      key !== 'title' && normalizeValue(key, row.values[key] ?? '') !== normalizeValue(key, value ?? ''));
    if (mismatch) return { record, action: 'conflict' as const, detail: mismatch[0] + ' 与现有值不同' };
    if (row.sort !== record.sort && !(row.status === 0 && row.sort === 0))
      return { record, action: 'conflict' as const, detail: '现有排序 ' + row.sort + ' 与导入排序不同' };
    if (row.status === 1) return { record, row, action: 'keep' as const, detail: '已发布且值与排序相同' };
    if (row.status === 0 || row.status === 2)
      return { record, row, action: 'resume' as const, detail: '沿用现有未发布记录并审核' };
    return { record, action: 'conflict' as const, detail: '现有记录已驳回或作废' };
  });
}

export function planListQueries(options: ImportField[], existingDisplay: ImportField[], existingSearch: ImportField[]) {
  const enabled = new Set(options.filter(field => field.isOpen === 'on').map(field => field.fieldName));
  const display = existingDisplay.filter(field => enabled.has(field.fieldName));
  const search = existingSearch.filter(field => enabled.has(field.fieldName));
  const addDisplay = !display.length;
  const addSearch = !search.length;
  const title = options.find(field => field.fieldName === 'title' && field.isOpen === 'on');
  if ((addDisplay || addSearch) && !title) throw new Error('列表模式至少需要启用原生标题作为列表及搜索字段');
  const fieldOptions = title && (addDisplay || addSearch)
    ? options.map(field => field === title ? {...field,
        ...(addDisplay ? {isList: 'on'} : {}), ...(addSearch ? {isSearch: 'on'} : {})} : field)
    : options;
  const nativeTitle = fieldOptions.find(field => field.fieldName === 'title');
  return {
    fieldOptions,
    fieldListDisplay: addDisplay ? [nativeTitle!] : display,
    fieldSearch: addSearch ? [nativeTitle!] : search,
    repaired: addDisplay || addSearch || display.length !== existingDisplay.length || search.length !== existingSearch.length
  };
}