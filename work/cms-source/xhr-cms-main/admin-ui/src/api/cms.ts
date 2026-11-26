import { http } from './http';

export type NativeWorkflowStatus = 'DRAFT' | 'PENDING_REVIEW' | 'APPROVED' | 'REJECTED' | 'PUBLISHED' | 'ARCHIVED';

export interface NativeLocale {
  languageId: number;
  languageCode: string;
  languageName: string;
  defaultLanguage: boolean;
  enabled: boolean;
  requiredForPublish: boolean;
}

export interface NativeFieldDefinition {
  datePrecision?: 'DAY' | 'SECOND' | null;
  fieldKey: string;
  displayName: string;
  fieldType: string;
  required: boolean;
  translatable: boolean;
  enumValues: string[];
  bak?: string;
  selectQuery?: string;
  sort?: number;
  /** 输入方式：''=手动输入，enum=固定枚举，content=栏目内容 */
  inputType?: string;
  /** 选择方式：true=多选（仅 enum/content 有效） */
  inputMultiple?: boolean;
  /** 允许不选：true=下拉存在空选项（仅 content 有效） */
  allowEmpty?: boolean;
  /** 级联：上级字段名（如 singleLine11），本字段下拉随其联动过滤；空=不级联 */
  cascadeParentField?: string;
}

export interface ListFieldConfig {
  fieldName: string;
  title: string;
  fieldType: string;
  sort: number;
  /** 输入方式：''=手动输入，enum=固定枚举，content=栏目内容 */
  inputType?: string;
  /** 栏目内容字段的动态下拉查询标识（{$...$}） */
  selectQuery?: string;
  /** 选择方式：true=多选 */
  inputMultiple?: boolean;
}

/** 内容管理动态搜索字段配置（由栏目 modelSearch 派生）。 */
export interface NativeSearchField {
  datePrecision?: 'DAY' | 'SECOND' | null;
  fieldName: string;
  title: string;
  fieldType: string;
  /** 是否为动态关联下拉（{$...$}），是则用 select 控件 */
  select: boolean;
  /** 静态枚举选项（select=false 且 defaultValue 为逗号枚举时） */
  options?: string[];
  /** 动态下拉查询标识（select=true 时） */
  selectQuery?: string;
  /** 输入方式：''=手动输入，enum=固定枚举，content=栏目内容 */
  inputType?: string;
  /** 选择方式：true=多选（仅 enum/content 有效） */
  inputMultiple?: boolean;
  sort: number;
}

export interface NativeContentRow {
  recordId: number;
  rowVersion?: string | null;
  variantId?: number | null;
  menuId: number;
  modelKey: string;
  languageId: number;
  workflowStatus?: NativeWorkflowStatus | null;
  activeVersionId?: number | null;
  publishedVersionId?: number | null;
  lockVersion?: number | null;
  title: string;
  updatedAt?: string;
  variantExists: boolean;
  variantState: 'MISSING' | 'EXISTS';
  translationComplete: boolean;
  completedRequiredFields: number;
  requiredTranslatableFields: number;
  languageState: 'DEFAULT' | 'INDEPENDENT' | 'MISSING';
  defaultLanguage: boolean;
  requiredForPublish: boolean;
  sharedValues: Record<string, unknown>;
  fieldValues: Record<string, unknown>;
  sort: number;
  hits: number;
  indexTop: boolean;
  indexElite: boolean;
  indexHot: boolean;
  menuTop: boolean;
  menuElite: boolean;
  menuHot: boolean;
  channelTop: boolean;
  channelElite: boolean;
  channelHot: boolean;
  navElite: boolean;
  rightElite: boolean;
  leftElite: boolean;
}

export interface NativeContentListResponse { rows: NativeContentRow[]; total: number; page: number; limit: number; listFields: ListFieldConfig[]; searchFields?: NativeSearchField[] }
export interface NativeContentEditorDetail {
  recordId?: number | null; rowVersion?: string | null; menuId: number; languageId: number; modelKey: string;
  workflowStatus: NativeWorkflowStatus; fields: NativeFieldDefinition[];
  sharedPayload: Record<string, unknown>; payload: Record<string, unknown>; relations: Record<string, unknown>;
  localeState: { languageId: number; enabled: boolean; defaultLanguage: boolean; requiredForPublish: boolean; variantExists: boolean; workflowStatus?: NativeWorkflowStatus | null };
}

export interface NativeMutationResult {
  recordId: number;
  variantId: number;
  activeVersionId: number;
  publishedVersionId?: number;
  workflowStatus: NativeWorkflowStatus;
  lockVersion: number;
}
export interface NativePublishedContentResponse {
  siteId: number; menuId: number; requestedLanguageId: number; resolvedLanguageId: number; fallbackApplied: boolean;
  contents: Array<Record<string, unknown>>;
}
export interface SaveNativeDraftRequest {
  recordId?: number; rowVersion?: string; menuId: number;
  sharedPayload: Record<string, unknown>; payload: Record<string, unknown>;
}

export function toContentEditorField(field: NativeFieldDefinition) {
  return { fieldName: field.fieldKey, fromName: field.displayName, title: field.displayName, fieldType: field.fieldType, datePrecision: field.datePrecision,
    bak: field.bak, enumValues: field.enumValues, translatable: field.translatable, required: field.required,
    selectQuery: field.selectQuery, sort: field.sort, inputType: field.inputType, inputMultiple: field.inputMultiple, allowEmpty: field.allowEmpty, cascadeParentField: field.cascadeParentField };
}

function query(params: Record<string, string | number | boolean | undefined | null>) {
  const entries = Object.entries(params).filter(([, value]) => value !== undefined && value !== null && value !== '');
  return new URLSearchParams(entries.map(([key, value]) => [key, String(value)])).toString();
}

export const listNativeLocales = (siteId: number | string) => http.get<NativeLocale[]>(`/api/admin/v2/sites/${siteId}/locales`);
export interface NativeContentGroup {value:string; count:number}
export const listNativeContentGroups = (menuId:number, groupField:string) =>
  http.get<NativeContentGroup[]>(`/api/admin/v2/cms/native-content/groups?${query({menuId, groupField})}`);
export const assignNativeContentGroup = (body: {
  menuId: number;
  groupField: string;
  groupValue: string;
  records: Array<{ recordId: number; rowVersion: string }>;
}) => http.post<{ updatedCount: number }>('/api/admin/v2/cms/native-content/groups/assign', body);
export const listNativeContent = (params: { menuId: number; languageId?: number; statuses?: NativeWorkflowStatus[]; variantState?: 'MISSING' | 'EXISTS'; keyword?: string; page?: number; limit?: number; filters?: Record<string, string> }) => {
  const { filters, languageId, ...base } = params;
  const queryParams: Record<string, string | number | boolean | undefined | null> = {
    ...base,
    statuses: params.statuses?.join(',')
  };
  if (filters) {
    for (const [key, value] of Object.entries(filters)) {
      queryParams[key] = value;
    }
  }
  const groupEmpty = filters?.groupValue === '' ? '&groupValue=' : '';
  return http.get<NativeContentListResponse>(`/api/admin/v2/cms/native-content?${query(queryParams)}${groupEmpty}`);
};
/** 修改内容排序号（t_contents.sort），与老系统 model_manage 排序列行为一致。 */
export const updateNativeContentSort = (menuId: number, recordId: number, sort: number) =>
  http.post<unknown>(`/api/admin/v2/cms/content/${menuId}/${recordId}/sort`, { sort });
export const loadNativeEditor = (params: { menuId: number; languageId: number; recordId?: number; modelVersionId?: number; modelKey?: string }) =>
  http.get<NativeContentEditorDetail>(`/api/admin/v2/cms/native-content/editor?${query(params)}`);
export const saveNativeDraft = (body: SaveNativeDraftRequest) => http.post<NativeMutationResult>('/api/admin/v2/cms/native-content/drafts', body);

export interface SelectOptionItem {
  value: string;
  label: string;
  /** 来源分组（栏目内容多栏目时=来源栏目名），无分组为 null */
  group?: string | null;
}

export const fetchNativeSelectOptions = (menuId: number, selectQuery: string, parentValue?: string) => {
  const pv = parentValue !== undefined && parentValue !== null && parentValue !== ''
    ? `&parentValue=${encodeURIComponent(parentValue)}`
    : '';
  return http.get<SelectOptionItem[]>(`/api/admin/v2/cms/native-content/select-options?menuId=${menuId}&query=${encodeURIComponent(selectQuery)}${pv}`);
};

export interface AuditContentV2Request {
  ids: number[];
  checkStatus: number;
  checkDescription?: string;
}

export interface BatchOperationResponse {
  total: number;
  successCount: number;
  failureCount: number;
  successes: Array<{ id: number; title: string; message: string }>;
  failures: Array<{ id: number; title: string; message: string }>;
}

export const auditContentV2 = (classId: number, body: AuditContentV2Request) =>
  http.post<BatchOperationResponse>(`/api/admin/v2/cms/content/${classId}/audit-v2`, body);

export interface ContentTranslatePreviewRequest {
  menuId: number;
  recordId: number;
  languageId: number;
  targetLanguage: string;
  engine: 'SPRING_AI';
  fieldKeys: string[];
}

export interface ContentTranslationItem {
  fieldKey: string;
  sourceText: string;
  translatedText: string;
}

export interface ContentTranslatePreviewResult {
  items: ContentTranslationItem[];
  prompt: string | null;
  rawResponse: string | null;
}

/** LLM 引擎：后端同步翻译内容字段。 */
export const translateContentPreview = (body: ContentTranslatePreviewRequest) =>
  http.post<ContentTranslatePreviewResult>('/api/admin/v2/cms/native-content/translate-preview', body, {
    timeout: 310000
  });
