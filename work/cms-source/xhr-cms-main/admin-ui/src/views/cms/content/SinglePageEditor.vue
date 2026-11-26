<template>
  <div class="single-page-editor">
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <div v-if="loading" class="single-page-editor__loading">
      <el-icon class="is-loading"><Loading /></el-icon>
      <span>加载中...</span>
    </div>
    <el-form v-else-if="fields.length" :inert="saving || auditSubmitting" label-position="top" class="single-page-editor__form" @submit.prevent>
      <Teleport :to="statusTarget">
        <el-tag v-if="pageStatus" size="small" :type="statusTagType(pageStatus)">{{ statusLabel(pageStatus) }}</el-tag>
      </Teleport>
      <el-form-item
        v-for="field in fields"
        :key="field.fieldName"
      >
        <template #label>
          <span>{{ field.title || field.fromName || field.fieldName }}</span>
          <el-tag size="small" type="info" effect="plain" class="single-page-editor__field-name-tag" title="双击复制字段名" @dblclick="copyFieldName(field.fieldName)">{{ field.fieldName }}</el-tag>
          <template v-for="hit in sensitiveHits[field.fieldName] ?? []" :key="`${field.fieldName}-${hit.word}-${hit.action}`">
            <el-tag
              size="small"
              :type="sensitiveHitTagType(hit.action)"
              effect="light"
              class="single-page-editor__sensitive-tag"
            >{{ formatSensitiveWordHit(hit) }}</el-tag>
            <el-button
              v-if="isFixableHit(hit)"
              size="small"
              text
              type="primary"
              class="single-page-editor__sensitive-confirm"
              @click="confirmSensitiveFix(field, hit)"
            >确认</el-button>
          </template>
          <span v-if="field.bak" class="single-page-editor__field-hint"> - {{ field.bak }}</span>
        </template>
        <el-input
          v-if="isLargeTextareaField(field)"
          v-model="formValues[field.fieldName]"
          type="textarea"
          :rows="10"
        />
        <CmsRichTextField
          v-else-if="isRichTextField(field)"
          v-model="formValues[field.fieldName]"
          :field="field"
          @blur="onFieldBlur(field)"
        />
        <el-input
          v-else-if="isSmallTextareaField(field)"
          v-model="formValues[field.fieldName]"
          type="textarea"
          :rows="4"
          :data-testid="`field-control-${field.fieldName}`"
          @blur="onFieldBlur(field)"
        />
        <CmsContentReferenceField
          v-else-if="isExtContentReferenceField(field)"
          v-model="formValues[field.fieldName]"
          :field="field" :menu-id="props.menuId"
          :label="field.title || field.fromName || field.fieldName"
          :control-test-id="`field-control-${field.fieldName}`"
        />
        <el-select
          v-else-if="field.fieldName.startsWith('ext_') && field.inputType === 'enum'"
          :model-value="field.inputMultiple ? unpackMultiValue(String(formValues[field.fieldName] ?? '')) : formValues[field.fieldName]"
          :multiple="Boolean(field.inputMultiple)"
          clearable
          style="width: 100%"
          @update:model-value="(value: string | string[] | null) => formValues[field.fieldName] = field.inputMultiple ? packMultiValue(Array.isArray(value) ? value : []) : String(value ?? '')"
        >
          <el-option v-for="option in (field.defaultValue || '').split(',').filter(Boolean)" :key="option" :label="option" :value="option" />
        </el-select>
        <el-date-picker
          v-else-if="isDateTimeField(field)"
          v-model="formValues[field.fieldName]"
          :type="datePickerType(field)"
            :editable="!isDayDateField(field)"
            :disabled-date="isDayDateField(field) ? dayDateDisabled : undefined"
          :value-format="datePickerFormat(field)"
          :format="datePickerFormat(field)"
          style="width: 100%"
        />
        <el-input-number
          v-else-if="isNumberField(field)"
          v-model="formValues[field.fieldName]"
          :min="-999999999"
          :max="999999999"
          style="width: 100%"
        />
        <CmsResourceUploadField
          v-else-if="isResourceField(field)"
          v-model="formValues[field.fieldName]"
          :field="field"
        />
        <el-input
          v-else
          v-model="formValues[field.fieldName]"
          :placeholder="fieldPlaceholder(field)"
        />
      </el-form-item>
      <div class="single-page-editor__footer">
        <span role="status">{{ dirty ? '单页修改尚未保存' : '字段已同步' }}</span>
        <el-button v-if="dirty" @click="confirmLeave">放弃修改</el-button>
        <el-button v-if="siteContext.canOperate(props.menuId, 4)" type="primary" plain :loading="saving" @click="publishAs">存为发布</el-button>
        <el-button v-if="siteContext.canOperate(props.menuId, 4)" plain :loading="saving" @click="saveAsDraft">存为草稿</el-button>
        <el-button v-if="siteContext.canOperate(props.menuId, 23)" type="success" plain :loading="saving" @click="openAuditDialog">审核</el-button>
        <el-button v-if="siteContext.canOperate(props.menuId, 23)" plain @click="openHistoryDrawer">审核信息</el-button>
        <el-button v-if="siteContext.canOperate(props.menuId, 2)" type="primary" :loading="saving" @click="save">保存</el-button>
      </div>
    </el-form>
    <el-empty v-else description="该栏目未配置单页字段" />

    <!-- 审核弹窗 -->
    <el-dialog v-model="auditDialogVisible" title="审核内容" width="500px">
      <el-form :model="auditForm" label-width="100px">
        <el-form-item label="审核状态">
          <el-select v-model="auditForm.checkStatus" placeholder="请选择审核状态" style="width: 100%">
            <el-option label="待审批" :value="0" />
            <el-option label="通过" :value="1" />
            <el-option label="未通过" :value="2" />
            <el-option label="作废" :value="3" />
          </el-select>
        </el-form-item>
        <el-form-item label="审核备注">
          <el-input v-model="auditForm.checkDescription" type="textarea" :rows="3" placeholder="请输入审核备注" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="auditDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="auditSubmitting" @click="submitAuditV2">提交</el-button>
      </template>
    </el-dialog>

    <!-- 审核信息抽屉 -->
    <el-drawer v-model="historyDrawerVisible" title="审核信息" size="500px">
      <div v-loading="historyLoading" class="single-page-editor__history">
        <el-empty v-if="!historyLoading && !historyAuditTrail.length" description="暂无审核记录" />
        <el-timeline v-else>
          <el-timeline-item v-for="item in historyAuditTrail" :key="item.id" :timestamp="item.submitTime">
            <strong>{{ item.contentTitle }}</strong>
            <p v-if="item.submitterName">提交人：{{ item.submitterName }}</p>
            <p v-if="item.submitDescription">提交说明：{{ item.submitDescription }}</p>
            <p v-if="item.checkerName">审核人：{{ item.checkerName }}</p>
            <p v-if="item.checkTime">审核时间：{{ item.checkTime }}</p>
            <p v-if="item.checkDescription">审核说明：{{ item.checkDescription }}</p>
            <p>状态：{{ item.statusText }}</p>
          </el-timeline-item>
        </el-timeline>
      </div>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Loading } from '@element-plus/icons-vue';
import { packMultiValue, unpackMultiValue } from '@/utils/multiValue';
import { http } from '@/api/http';
import { useSiteContextStore } from '@/stores/siteContext';
import { applySensitiveWordFix, checkSensitiveText, formatSensitiveWordHit, isFixableHit, type SensitiveWordHit } from '@/utils/sensitiveWord';
import CmsResourceUploadField from './CmsResourceUploadField.vue';
import CmsRichTextField from './CmsRichTextField.vue';
import CmsContentReferenceField from './CmsContentReferenceField.vue';
import { isExtContentReferenceField } from './contentReference';

const siteContext = useSiteContextStore();

interface FieldOption {
  datePrecision?: 'DAY' | 'SECOND' | null;
  fieldName: string;
  fromName?: string;
  fieldType?: string;
  title?: string;
  bak?: string;
  defaultValue?: string;
  inputType?: string;
  inputMultiple?: boolean;
  allowEmpty?: boolean;
  isReadOnly?: string | boolean;
}

interface SinglePageResponse {
  menu?: {
    id: number;
    className: string;
    pageType?: number | null;
  };
  fields?: FieldOption[];
  content?: {
    id: number;
    menuId: number;
    className?: string;
    values?: Record<string, string | number | null>;
  };
  /** 简单派生状态：DISABLED(未启用) / DRAFT(草稿) / PUBLISHED(已发布) */
  status?: string;
}

interface AuditTrailItem {
  id: number;
  contentId: number;
  contentTitle: string;
  submitterName: string;
  submitDescription?: string;
  submitTime: string;
  checkerName: string;
  checkDescription?: string;
  checkTime: string;
  status: number;
  statusText: string;
}

const props = defineProps<{
  menuId: number;
  languageId?: number;
  statusTarget: string;
}>();

const emit = defineEmits<{
  saved: [];
}>();

const loading = ref(false);
const saving = ref(false);
const error = ref('');
const fields = ref<FieldOption[]>([]);
const pageStatus = ref('DRAFT');
const formValues = reactive<Record<string, string | number | null>>({});
/** note/content 类字段的敏感词比对结果，key 为字段名 */
const sensitiveHits = reactive<Record<string, SensitiveWordHit[]>>({});
const auditDialogVisible = ref(false);
const auditSubmitting = ref(false);
const auditForm = reactive({ checkStatus: 0, checkDescription: '' });
const historyDrawerVisible = ref(false);
const historyLoading = ref(false);
const historyAuditTrail = ref<AuditTrailItem[]>([]);

const baseline = ref('{}');
const dirty = computed(() => fields.value.length > 0 && JSON.stringify(formValues) !== baseline.value);
const writing = computed(() => saving.value || auditSubmitting.value);
let generation = 0;
async function confirmLeave() {
  if (writing.value) { ElMessage.warning('正在处理单页，请稍候'); return false; }
  if (!dirty.value) return true;
  try {
    await ElMessageBox.confirm('放弃当前单页尚未保存的修改？', '有未保存的修改', { confirmButtonText: '放弃修改', cancelButtonText: '继续编辑' });
    replaceFormValues(JSON.parse(baseline.value));
    return true;
  } catch { return false; }
}
defineExpose({ confirmLeave, dirty, writing });
watch(() => [props.menuId, props.languageId, siteContext.currentSite?.id], () => {
  ++generation;
  fields.value = [];
  clearSensitiveHits();
  void load();
}, { immediate: true, flush: 'sync' });
onBeforeUnmount(() => { ++generation; });

async function load() {
  const token = generation;
  if (!props.menuId) return;
  loading.value = true;
  error.value = '';
  try {
    const data = await http.get<SinglePageResponse>(`/api/admin/v2/cms/single/${props.menuId}`);
    if (token !== generation) return;
    fields.value = data.fields ?? [];
    pageStatus.value = data.status ?? 'DRAFT';
    replaceFormValues(data.content?.values ?? {});
    clearSensitiveHits();
  } catch (err) {
    if (token === generation) error.value = errorMessage(err, '单页内容加载失败');
  } finally {
    if (token === generation) loading.value = false;
  }
}

async function save() {
  if (!props.menuId || writing.value || loading.value) return;
  const menuId = props.menuId;
  const token = generation;
  saving.value = true;
  try {
    if (!(await validateSensitiveWordsBeforeSave()) || token !== generation) return;
    const data = await http.post<SinglePageResponse>(`/api/admin/v2/cms/single/${menuId}`, {
      values: { ...formValues }
    });
    if (token !== generation) return;
    fields.value = data.fields ?? fields.value;
    pageStatus.value = data.status ?? pageStatus.value;
    replaceFormValues(data.content?.values ?? { ...formValues });
    ElMessage.success('单页字段已保存');
    emit('saved');
  } catch (err) {
    ElMessage.error(errorMessage(err, '保存失败'));
  } finally {
    saving.value = false;
  }
}

function replaceFormValues(values: Record<string, string | number | null>) {
  Object.keys(formValues).forEach(key => delete formValues[key]);
  fields.value.forEach(field => {
    formValues[field.fieldName] = values[field.fieldName] ?? '';
  });
  baseline.value = JSON.stringify(formValues);
}

function sensitiveFieldCheckMode(field: FieldOption): 'html' | 'text' | null {
  if (isRichTextField(field)) return 'html';
  if (isSmallTextareaField(field)) return 'text';
  return null;
}

function isSensitiveCheckField(field: FieldOption) {
  return sensitiveFieldCheckMode(field) !== null;
}

/** 失焦时调用：比对当前字段内容与敏感词规则，结果展示在字段标题旁 */
async function checkFieldSensitiveWords(field: FieldOption): Promise<SensitiveWordHit[]> {
  const mode = sensitiveFieldCheckMode(field);
  if (!mode) return [];
  try {
    const hits = await checkSensitiveText(String(formValues[field.fieldName] ?? ''), { html: mode === 'html' });
    if (hits.length) {
      sensitiveHits[field.fieldName] = hits;
    } else {
      delete sensitiveHits[field.fieldName];
    }
    return hits;
  } catch {
    // 规则拉取失败时不阻塞编辑，保留上次结果
    return sensitiveHits[field.fieldName] ?? [];
  }
}

function onFieldBlur(field: FieldOption) {
  void checkFieldSensitiveWords(field);
}

/** 点击“确认”：替换或删除命中敏感词，并重新比对刷新结果 */
async function confirmSensitiveFix(field: FieldOption, hit: SensitiveWordHit) {
  if (!sensitiveFieldCheckMode(field)) return;
  const key = field.fieldName;
  const next = applySensitiveWordFix(String(formValues[key] ?? ''), hit);
  formValues[key] = next;
  await checkFieldSensitiveWords(field);
}

function clearSensitiveHits() {
  Object.keys(sensitiveHits).forEach(key => delete sensitiveHits[key]);
}

function sensitiveHitTagType(action: string): 'danger' | 'warning' | 'primary' {
  if (action === 'reject') return 'danger';
  if (action === 'alert') return 'warning';
  return 'primary';
}

/**
 * 保存前对全部 note/content 类字段统一比对敏感词并提示结果；
 * 存在“拒绝”类命中且 blockOnReject 时返回 false，不允许提交。
 */
async function validateSensitiveWordsBeforeSave({ blockOnReject = true }: { blockOnReject?: boolean } = {}): Promise<boolean> {
  const problems: string[] = [];
  const rejected: string[] = [];
  for (const field of fields.value) {
    if (!isSensitiveCheckField(field)) continue;
    const hits = await checkFieldSensitiveWords(field);
    const fieldLabel = field.title || field.fromName || field.fieldName;
    for (const hit of hits) {
      const text = `${fieldLabel}：${formatSensitiveWordHit(hit)}`;
      problems.push(text);
      if (hit.action === 'reject') rejected.push(text);
    }
  }
  if (rejected.length && blockOnReject) {
    ElMessage.error(`内容包含拒绝类敏感词，不允许提交：${rejected.join('；')}`);
    return false;
  }
  if (problems.length) {
    ElMessage.warning(`敏感词比对提醒：${problems.join('；')}`);
  }
  return true;
}

function statusLabel(status: string): string {
  return { DISABLED: '未启用', DRAFT: '草稿', PENDING_REVIEW: '待审核', APPROVED: '已审核', PUBLISHED: '已发布' }[status] ?? status;
}

function statusTagType(status: string): 'primary' | 'warning' | 'info' | 'success' {
  return status === 'PUBLISHED' ? 'success'
    : status === 'APPROVED' ? 'primary'
    : status === 'PENDING_REVIEW' ? 'warning'
    : status === 'DRAFT' ? 'info'
    : 'warning';
}

function openAuditDialog() {
  auditForm.checkStatus = 0;
  auditForm.checkDescription = '';
  auditDialogVisible.value = true;
}

async function submitAuditV2() {
  if (!props.menuId || writing.value || loading.value) return;
  if (dirty.value) { ElMessage.warning('请先保存单页字段，再提交审核'); return; }
  const token = generation;
  auditSubmitting.value = true;
  try {
    const data = await http.post<SinglePageResponse>(`/api/admin/v2/cms/single/${props.menuId}/audit-v2`, {
      checkStatus: auditForm.checkStatus,
      checkDescription: auditForm.checkDescription || undefined
    });
    if (token !== generation) return;
    pageStatus.value = data.status ?? pageStatus.value;
    fields.value = data.fields ?? fields.value;
    replaceFormValues(data.content?.values ?? { ...formValues });
    auditDialogVisible.value = false;
    ElMessage.success('审核已提交');
    emit('saved');
  } catch (err) {
    ElMessage.error(errorMessage(err, '审核操作失败'));
  } finally {
    auditSubmitting.value = false;
  }
}

async function openHistoryDrawer() {
  if (!props.menuId) return;
  historyDrawerVisible.value = true;
  historyLoading.value = true;
  try {
    const data = await http.get<{ histories: unknown[]; auditTrail: AuditTrailItem[] }>(`/api/admin/v2/cms/single/${props.menuId}/history`);
    historyAuditTrail.value = data.auditTrail ?? [];
  } catch (err) {
    ElMessage.error(errorMessage(err, '审核信息加载失败'));
  } finally {
    historyLoading.value = false;
  }
}

async function publishAs() {
  await runStatusAction('publish', '已存为发布');
}

async function saveAsDraft() {
  await runStatusAction('unpublish', '已存为草稿');
}

async function runStatusAction(action: string, okMessage: string) {
  if (!props.menuId || writing.value || loading.value) return;
  if (dirty.value) { ElMessage.warning('请先保存单页字段，再修改发布状态'); return; }
  const menuId = props.menuId;
  const token = generation;
  saving.value = true;
  try {
    if (!(await validateSensitiveWordsBeforeSave({ blockOnReject: action === 'publish' })) || token !== generation) return;
    const data = await http.post<SinglePageResponse>(`/api/admin/v2/cms/single/${menuId}/${action}`);
    if (token !== generation) return;
    pageStatus.value = data.status ?? pageStatus.value;
    fields.value = data.fields ?? fields.value;
    replaceFormValues(data.content?.values ?? { ...formValues });
    ElMessage.success(okMessage);
    emit('saved');
  } catch (err) {
    ElMessage.error(errorMessage(err, '操作失败'));
  } finally {
    saving.value = false;
  }
}

function normalizedFieldType(field: FieldOption) {
  return (field.fieldType ?? '').replace(/[\s_-]/g, '').toLowerCase();
}

function isLargeTextareaField(field: FieldOption) {
  return ['textarea', 'longtext'].includes(normalizedFieldType(field));
}

function isRichTextField(field: FieldOption) {
  return ['content', 'editor', 'html', 'richtext'].includes(normalizedFieldType(field));
}

function isSmallTextareaField(field: FieldOption) {
  return ['note', 'multiline'].includes(normalizedFieldType(field));
}

function isDateTimeField(field: FieldOption) {
  return ['date', 'datetime', 'time'].includes(normalizedFieldType(field));
}

function isNumberField(field: FieldOption) {
  return ['number', 'int'].includes(normalizedFieldType(field));
}

function isImageField(field: FieldOption) {
  return ['image', 'photo', 'picture'].includes(normalizedFieldType(field));
}

function isMultiImageField(field: FieldOption) {
  return ['multiimage', 'multiimages', 'images', 'gallery', 'photos', 'pictures'].includes(normalizedFieldType(field));
}

function isFileField(field: FieldOption) {
  return ['file', 'attachment', 'download'].includes(normalizedFieldType(field));
}

function isVideoField(field: FieldOption) {
  return ['video', 'media'].includes(normalizedFieldType(field));
}

function isResourceField(field: FieldOption) {
  return isImageField(field) || isMultiImageField(field) || isFileField(field) || isVideoField(field);
}

function isDayDateField(field: FieldOption) {
  return field.fieldName.startsWith('ext_') && field.datePrecision === 'DAY';
}

function dayDateDisabled(value: Date) {
  return value.getFullYear() < 100 || value.getFullYear() > 9999;
}

function datePickerFormat(field: FieldOption) {
  return isDayDateField(field) ? 'YYYY-MM-DD' : 'YYYY-MM-DD HH:mm:ss';
}

function datePickerType(field: FieldOption) {
  if (isDayDateField(field)) return 'date';
  return normalizedFieldType(field) === 'date' ? 'date' : 'datetime';
}

function fieldPlaceholder(field: FieldOption) {
  const type = normalizedFieldType(field);
  if (['image', 'photo', 'picture'].includes(type)) return '图片地址';
  if (['file', 'attachment', 'download'].includes(type)) return '文件地址';
  return '';
}

async function copyFieldName(fieldName: string) {
  try {
    await navigator.clipboard.writeText(fieldName);
    ElMessage.success(`已复制: ${fieldName}`);
  } catch {
    ElMessage.error('复制失败');
  }
}

function errorMessage(err: unknown, fallback: string) {
  return err instanceof Error ? err.message : fallback;
}
</script>

<style scoped>
.single-page-editor {
  display: grid;
  gap: 16px;
}

.single-page-editor__loading {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 40px;
  color: var(--admin-text-muted);
}

.single-page-editor__form {
  display: grid;
  gap: 12px;
}

.single-page-editor__footer {
  display: flex;
  justify-content: flex-end;
  align-items: center;
  gap: 8px;
  padding-top: 12px;
}

.single-page-editor__field-name-tag {
  margin-left: 8px;
  font-family: 'Consolas', 'Monaco', monospace;
  font-size: 11px;
  color: #909399;
  border-color: #e4e7ed;
  cursor: pointer;
  user-select: none;
}

.single-page-editor__field-name-tag:hover {
  color: #409eff;
  border-color: #409eff;
}

.single-page-editor__field-hint {
  color: #f56c6c;
  font-weight: 400;
}

.single-page-editor__sensitive-tag {
  margin-left: 8px;
}

.single-page-editor__sensitive-confirm {
  margin-left: 2px;
}

.single-page-editor__history {
  min-height: 200px;
}

.single-page-editor__history p {
  margin: 2px 0;
  color: var(--admin-text-muted);
}
</style>
