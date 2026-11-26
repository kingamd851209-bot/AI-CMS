import { ref, type Ref } from 'vue';
import { createTableLoader } from '@/utils/tableLoader';
import { http } from '@/api/http';

export interface FieldRow {
  datePrecision?: 'DAY' | 'SECOND' | null;
  fieldName: string;
  fromName: string;
  fieldType: string;
  title?: string;
  defaultValue?: string;
  inputType: string;
  /** 仅本地新建、尚未保存；不提交后端。 */
  newlyAdded?: boolean;
  inputMultiple: boolean;
  allowEmpty: boolean;
  /** 级联：上级字段名（如 singleLine11）；空=不级联 */
  cascadeParentField: string;
  bak?: string;
  maxLength?: number;
  enabled: boolean;
  isList: boolean;
  isSearch: boolean;
  publicSearch: boolean;
  isReadOnly: boolean;
  // 验证选项数组
  validations: string[];
  // 内部标记，用于保存时转换
  isRequired: boolean;
  isTel: boolean;
  isEmail: boolean;
  isIdCard: boolean;
  isNumber: boolean;
  isWebUrl: boolean;
  sort: number;
}

interface CachedFieldData {
  configDigest: string;
  fieldRows: FieldRow[];
  timestamp: number;
}

interface FieldWorkspaceResponse {
  configDigest: string;
  fieldOptions: Array<{
    datePrecision?: 'DAY' | 'SECOND' | null;
    fieldName: string;
    fromName: string;
    fieldType: string;
    title?: string;
    defaultValue?: string;
    inputType?: string;
    inputMultiple?: boolean;
    allowEmpty?: boolean;
    cascadeParentField?: string;
    bak?: string;
    maxLength?: number;
    isOpen?: string;
    isList?: string;
    isSearch?: string;
    publicSearch?: boolean;
    isReadOnly?: string;
    isRequired?: string;
    isTel?: string;
    isEmail?: string;
    isIdCard?: string;
    isNumber?: string;
    isWebUrl?: string;
    sort?: number;
  }>;
  catalog: Array<{
    datePrecision?: 'DAY' | 'SECOND' | null;
    fieldName: string;
    fromName: string;
    fieldType: string;
    title?: string;
    defaultValue?: string;
    bak?: string;
    maxLength?: number;
    sort?: number;
  }>;
}

// 模块级缓存，跨组件实例共享
const fieldConfigCache = new Map<string, CachedFieldData>();

function getCacheKey(menuId: number, type: number): string {
  return `${menuId}:${type}`;
}

function stringFlag(value?: string): boolean {
  return value === 'on' || value === 'true' || value === '1';
}

// 图片/视频/文件/时间字段不支持输入方式配置，输入方式固定为手动输入（''）
function isInputTypeLocked(field: { fieldType?: string; fieldName: string }): boolean {
  const name = field.fieldName.toLowerCase();
  if (name.startsWith('photo') || name.startsWith('media') || name.startsWith('file') || name.startsWith('attachment')) {
    return true;
  }
  if (name.includes('time') || name.includes('date')) {
    return true;
  }
  return ['Image', 'Photo', 'Video', 'File', 'Attachment', 'DateTime'].includes(field.fieldType || '');
}

/** 与服务器投影白名单一致；公开许可不借用后台isSearch。 */
export function supportsPublicSearch(field: { fieldName: string; fieldType: string }): boolean {
  return ['SingleLine', 'MultiLine', 'Editor', 'Text', 'Textarea', 'DateTime'].includes(field.fieldType)
    && /^(?:title|(?:singleLine|note|content)[1-9][0-9]*|ext_[a-f0-9]{32})$/.test(field.fieldName);
}

export function useFieldConfig(menuIdRef: Ref<number>, typeRef: Ref<number>) {
  const loading = ref(false);
  const saving = ref(false);
  const saved = ref(false);
  const fieldRows = ref<FieldRow[]>([]);
  const cached = ref(false);
  const errorMessage = ref('');
  const configDigest = ref('');

  const loader = createTableLoader(loading);

  async function load(): Promise<void> {
    const request = loader.begin(() => {
      fieldRows.value = [];
      configDigest.value = '';
      cached.value = false;
      errorMessage.value = '';
    });
    const menuId = menuIdRef.value;
    const type = typeRef.value;
    const cacheKey = getCacheKey(menuId, type);
    const cachedData = fieldConfigCache.get(cacheKey);

    if (cachedData) {
      fieldRows.value = cachedData.fieldRows;
      configDigest.value = cachedData.configDigest;
      cached.value = true;
      request.finish();
      return;
    }

    try {
      const data = await http.get<FieldWorkspaceResponse>(
        `/api/admin/v2/cms/fields/menus/${menuId}?type=${type}`
      );

      if (!request.isCurrent()) return;
      configDigest.value = data.configDigest || '';
      const selected = new Map((data.fieldOptions ?? []).map(f => [f.fieldName, f]));

      fieldRows.value = (data.catalog ?? []).map((field, index) => {
        const current = selected.get(field.fieldName);
        const isRequired = stringFlag(current?.isRequired);
        const isTel = stringFlag(current?.isTel);
        const isEmail = stringFlag(current?.isEmail);
        const isIdCard = stringFlag(current?.isIdCard);
        const isNumber = stringFlag(current?.isNumber);
        const isWebUrl = stringFlag(current?.isWebUrl);
        // 构建验证数组
        const validations: string[] = [];
        if (isRequired) validations.push('required');
        if (isTel) validations.push('tel');
        if (isEmail) validations.push('email');
        if (isIdCard) validations.push('idCard');
        if (isNumber) validations.push('number');
        if (isWebUrl) validations.push('webUrl');
        return {
          fieldName: field.fieldName,
          fromName: field.fromName,
          fieldType: field.fieldType,
          datePrecision: current?.datePrecision ?? field.datePrecision ?? null,
          title: current?.title || field.title || field.fromName,
          defaultValue: current?.defaultValue ?? field.defaultValue ?? '',
          inputType: isInputTypeLocked(field) ? '' : (current?.inputType ?? ''),
          newlyAdded: false,
          inputMultiple: isInputTypeLocked(field) ? false : Boolean(current?.inputMultiple),
          allowEmpty: isInputTypeLocked(field) ? false : Boolean(current?.allowEmpty),
          cascadeParentField: current?.cascadeParentField ?? '',
          bak: current?.bak ?? field.bak ?? '',
          maxLength: current?.maxLength ?? field.maxLength ?? 0,
          enabled: field.fieldName.startsWith('ext_') ? stringFlag(current?.isOpen) : Boolean(current) || stringFlag(current?.isOpen),
          isList: stringFlag(current?.isList),
          isSearch: stringFlag(current?.isSearch),
          publicSearch: supportsPublicSearch(field) && current?.publicSearch === true,
          isReadOnly: stringFlag(current?.isReadOnly),
          validations,
          isRequired,
          isTel,
          isEmail,
          isIdCard,
          isNumber,
          isWebUrl,
          sort: current?.sort ?? field.sort ?? (1000 - index * 10)
        };
      });

      fieldConfigCache.set(cacheKey, {
        configDigest: configDigest.value,
        fieldRows: [...fieldRows.value],
        timestamp: Date.now()
      });
    } catch (error) {
      if (!request.isCurrent()) return;
      errorMessage.value = error instanceof Error ? error.message : '字段配置加载失败';
      throw error;
    } finally {
      request.finish();
    }
  }

  async function save(): Promise<void> {
    const menuId = menuIdRef.value;
    const type = typeRef.value;
    saving.value = true;
    errorMessage.value = '';

    try {
      // 扩展定义停用后仍保留，避免重新加载时丢失字段身份。
      const enabledRows = fieldRows.value.filter(row => row.enabled || row.fieldName.startsWith('ext_'));
      const fieldOptions = enabledRows.map(row => {
        // 列表模式 (type=0) 下，isList 和 isSearch 根据复选框状态决定
        // 单页模式 (type=1) 下，isList 和 isSearch 为空
        const isListValue = type === 0 && row.enabled ? (row.isList ? 'on' : '') : '';
        const isSearchValue = type === 0 && row.enabled ? (row.isSearch ? 'on' : '') : '';
        // 从 validations 数组转换回布尔值
        const validations = row.validations || [];
        return {
          fieldName: row.fieldName,
          fromName: row.fromName,
          fieldType: row.fieldType,
          datePrecision: row.datePrecision ?? null,
          title: row.title || row.fromName,
          defaultValue: row.defaultValue ?? '',
          inputType: row.inputType ?? '',
          inputMultiple: Boolean(row.inputMultiple),
          allowEmpty: Boolean(row.allowEmpty),
          cascadeParentField: row.cascadeParentField || '',
          bak: row.bak ?? '',
          maxLength: row.maxLength ?? 0,
          isOpen: row.enabled ? 'on' : '',
          isList: isListValue,
          isSearch: isSearchValue,
          publicSearch: row.enabled && supportsPublicSearch(row) && row.publicSearch === true,
          isReadOnly: row.isReadOnly ? 'on' : '',
          isRequired: validations.includes('required') ? 'on' : '',
          isTel: validations.includes('tel') ? 'on' : '',
          isEmail: validations.includes('email') ? 'on' : '',
          isIdCard: validations.includes('idCard') ? 'on' : '',
          isNumber: validations.includes('number') ? 'on' : '',
          isWebUrl: validations.includes('webUrl') ? 'on' : '',
          sort: row.sort
        };
      });

      const fieldListDisplay = fieldOptions.filter(f => f.isList === 'on');
      const fieldSearch = fieldOptions.filter(f => f.isSearch === 'on');

      if (!configDigest.value) throw new Error('字段配置缺少版本摘要，请重新读取后保存');
      const updated = await http.post<FieldWorkspaceResponse>(`/api/admin/v2/cms/fields/menus/${menuId}`, {
        id: menuId,
        type,
        fieldOptions,
        fieldListDisplay,
        fieldSearch
      }, {headers: {'X-CMS-Field-Config-Digest': configDigest.value}});
      configDigest.value = updated.configDigest || '';

      // 清除缓存
      fieldConfigCache.delete(getCacheKey(menuId, type));

      fieldRows.value.forEach(row => { row.newlyAdded = false; });
      saved.value = true;
    } catch (error) {
      // 失败后重开必须读取服务器版本，不能反复命中已陈旧的字段缓存。
      fieldConfigCache.delete(getCacheKey(menuId, type));
      errorMessage.value = error instanceof Error ? error.message : '字段配置保存失败';
      throw error;
    } finally {
      saving.value = false;
    }
  }

  function addBusinessField(title: string, fieldType: 'SingleLine' | 'MultiLine' | 'Editor' | 'Image' | 'Video' | 'File' | 'DateTime' = 'SingleLine', datePrecision: 'DAY' | 'SECOND' = 'SECOND'): string {
    const businessName = title.trim();
    if (!businessName || businessName.length > 100) {
      throw new Error('请填写不超过100字的业务名称');
    }
    const fieldName = `ext_${crypto.randomUUID().replaceAll('-', '')}`;
    fieldRows.value.push({
      fieldName, fromName: businessName, title: businessName, fieldType,
      datePrecision: fieldType === 'DateTime' ? datePrecision : null,
      defaultValue: '', inputType: '', newlyAdded: true, inputMultiple: false, allowEmpty: false,
      cascadeParentField: '', bak: '', maxLength: { SingleLine: 500, MultiLine: 10000, Editor: 200000, Image: 2048, Video: 2048, File: 2048, DateTime: datePrecision === 'DAY' ? 10 : 19 }[fieldType], enabled: true,
      isList: false, isSearch: false, publicSearch: false, isReadOnly: false, validations: [],
      isRequired: false, isTel: false, isEmail: false, isIdCard: false,
      isNumber: false, isWebUrl: false,
      sort: Math.max(0, ...fieldRows.value.map(row => row.sort)) + 10
    });
    saved.value = false;
    return fieldName;
  }

  function resetSaved(): void {
    saved.value = false;
  }

  function reset(): void {
    loader.invalidate();
    fieldRows.value = [];
    configDigest.value = '';
    cached.value = false;
    errorMessage.value = '';
  }

  function clearCache(): void {
    fieldConfigCache.delete(getCacheKey(menuIdRef.value, typeRef.value));
  }

  return {
    loading,
    saving,
    saved,
    fieldRows,
    cached,
    errorMessage,
    load,
    save,
    addBusinessField,
    resetSaved,
    reset,
    clearCache
  };
}

// 导出缓存清理函数（用于测试）
export function clearAllFieldConfigCache(): void {
  fieldConfigCache.clear();
}
