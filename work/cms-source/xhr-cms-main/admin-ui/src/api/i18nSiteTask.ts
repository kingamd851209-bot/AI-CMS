import { http } from '@/api/http';

export type I18nSyncStatus = 'PENDING' | 'RUNNING' | 'SUCCESS' | 'FAILED' | 'PARTIAL';

export interface I18nSiteTask {
  id: number;
  sourceSiteId?: number;
  sourceSiteName?: string;
  sourceLanguage?: string;
  targetSiteId?: number;
  targetSiteName?: string;
  targetLanguage?: string;
  columnStatus?: I18nSyncStatus;
  modelSyncStatus?: I18nSyncStatus;
  templateSyncStatus?: I18nSyncStatus;
  contentSyncStatus?: I18nSyncStatus;
  assetSyncStatus?: I18nSyncStatus;
  remark?: string;
  operatorName?: string;
  createTime?: string;
}

export interface I18nSiteTaskDetail {
  id: number;
  taskId: number;
  sourceMenuId?: number;
  sourceBizCode?: string;
  sourceMenuName?: string;
  sourceMenuType?: number;
  sourceParentId?: number | null;
  sourceParentName?: string | null;
  sourceModelJson?: string;
  sourceListTemplate?: string | null;
  sourceDetailTemplate?: string | null;
  sourceContentCount?: number;
  targetMenuId?: number;
  targetBizCode?: string;
  targetMenuName?: string;
  targetMenuType?: number;
  targetParentId?: number | null;
  targetParentName?: string | null;
  targetModelJson?: string;
  targetListTemplate?: string | null;
  targetDetailTemplate?: string | null;
  targetContentCount?: number;
  modelSynced?: boolean;
  templateSynced?: boolean;
  contentSynced?: boolean;
  syncNote?: string;
  syncTime?: string;
  /** 前端树形展示用：子栏目明细 */
  children?: I18nSiteTaskDetail[];
}

export interface I18nSiteTaskDetailResponse {
  task: I18nSiteTask;
  details: I18nSiteTaskDetail[];
}

export async function listI18nSiteTasks(keyword?: string): Promise<I18nSiteTask[]> {
  return http.get<I18nSiteTask[]>('/api/admin/v2/i18n-site-tasks', {
    params: keyword ? { keyword } : {}
  });
}

export async function getI18nSiteTask(taskId: number): Promise<I18nSiteTaskDetailResponse> {
  return http.get<I18nSiteTaskDetailResponse>(`/api/admin/v2/i18n-site-tasks/${taskId}`);
}

/** 内容同步方式：增量（只补目标缺失内容）/ 覆盖（先删目标旧内容再整体复制） */
export type ContentSyncMode = 'increment' | 'overwrite';
/** 翻译方式：原文同步 / AI 翻译后同步 */
export type TranslationMode = 'none' | 'ai';
/** 增量命中已存在内容时的处理：跳过 / 更新 */
export type ExistingContentAction = 'skip' | 'update';

export interface I18nSyncOptions {
  contentMode: ContentSyncMode;
  contentTranslation: TranslationMode;
  modelTranslation: TranslationMode;
  /** 增量命中已存在内容时的处理（contentMode=increment 时生效），默认跳过 */
  existingAction?: ExistingContentAction;
}

/** 按逐条明细各自勾选的维度同步（会覆盖目标站点对应数据） */
export async function syncI18nSiteTask(
  taskId: number,
  detailDimensions: Record<number, string[]>,
  options: I18nSyncOptions
): Promise<void> {
  await http.post(`/api/admin/v2/i18n-site-tasks/${taskId}/sync`, {
    detailDimensions,
    contentMode: options.contentMode,
    contentTranslation: options.contentTranslation,
    modelTranslation: options.modelTranslation,
    existingAction: options.existingAction
  });
}

export interface TranslationReady {
  ready: boolean;
  message?: string | null;
}

/** 检查（必要时自动启动）AI 翻译服务；ready=false 时 message 为不可用原因 */
export async function checkI18nTranslationReady(): Promise<TranslationReady> {
  return http.get<TranslationReady>('/api/admin/v2/i18n-site-tasks/ai-translation-ready');
}

/** 同步静态资源：把原站点 UIPackage 目录复制到目标站点（会覆盖目标站点静态资源），后台异步执行 */
export async function syncI18nSiteTaskAssets(taskId: number): Promise<I18nSiteTask> {
  return http.post<I18nSiteTask>(`/api/admin/v2/i18n-site-tasks/${taskId}/sync-assets`);
}

export interface StopI18nSyncResult {
  /** 是否确实有进行中的同步并已发出停止 */
  stopped: boolean;
  task: I18nSiteTask;
}

/** 停止正在进行的同步（协作式取消：停在当前栏目/当前文件之后） */
export async function stopI18nSiteTaskSync(taskId: number): Promise<StopI18nSyncResult> {
  return http.post<StopI18nSyncResult>(`/api/admin/v2/i18n-site-tasks/${taskId}/sync-stop`);
}
