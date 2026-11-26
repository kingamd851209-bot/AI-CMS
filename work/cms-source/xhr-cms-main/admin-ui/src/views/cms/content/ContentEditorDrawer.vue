<template>
  <el-drawer
    :model-value="visible"
    :with-header="false" :before-close="requestClose"
    direction="rtl"
    class="content-editor-drawer"
    modal-class="content-editor-drawer-modal"
    size="min(1120px, calc(100vw - var(--admin-drawer-mobile-gutter)))"
    @update:model-value="onDrawerVisibleChange"
  >
    <div class="content-editor" data-testid="content-editor-drawer">
      <header class="content-editor__topbar" data-testid="content-editor-topbar">
        <div class="content-editor__topbar-copy">
          <h2 data-testid="content-editor-topbar-title">{{ title }}</h2>
          <p data-testid="content-editor-topbar-meta">
            {{ mode === 'seo' ? 'SEO 信息' : `${renderedFields.length} 个字段` }}
          </p>
        </div>
        <div class="content-editor__topbar-actions">
          <el-tag effect="plain" :type="saving ? 'warning' : 'info'">{{ saving ? '保存中' : hasChanges ? '尚未保存' : '无待保存修改' }}</el-tag>
          <el-button
            class="content-editor__close"
            :icon="Close"
            text
            circle
            aria-label="关闭"
            data-testid="content-editor-close-button"
            @click="requestClose(() => emit('update:visible', false))"
          />
        </div>
      </header>

      <section class="content-editor__shell" data-testid="content-editor-shell">
        <el-form label-position="top" class="cms-form admin-drawer-form content-editor__form" @submit.prevent>
          <div class="content-editor__layout" :class="{ 'content-editor__layout--seo': mode === 'seo' }">
            <main class="content-editor__main" data-testid="content-editor-main-column">
              <section v-if="showBaseInfoCard" class="content-editor__card content-editor__base-card" data-testid="content-editor-primary-card">
                <div class="content-editor__section-label">
                  <h3>基础信息</h3>
                  <p>标题与排序</p>
                </div>
                <div class="cms-form__grid content-editor__base-grid">
                  <el-form-item label="标题">
                    <el-input v-model="contentForm.title" data-testid="content-title-input" />
                  </el-form-item>
                  <el-form-item label="排序">
                    <el-input-number v-model="contentForm.sort" :min="-9999" :max="999999" data-testid="content-sort-input" />
                  </el-form-item>
                </div>
              </section>

              <section v-if="nativeMode && nativeRelations.length" class="content-editor__card">
                <header class="content-editor__section-head"><h3>关联内容</h3></header>
                <el-form-item v-for="relation in nativeRelations" :key="relation.relationKey" :label="relation.displayName">
                  <el-input v-model="contentForm.relations[relation.relationKey]" :placeholder="relation.multiple ? '输入多个记录 ID，以逗号分隔' : '输入关联记录 ID'" :data-testid="`relation-control-${relation.relationKey}`" />
                </el-form-item>
              </section>

              <section v-if="mode !== 'seo'" class="content-editor__card content-editor__fields-card" data-testid="content-editor-fields-card">
                <header class="content-editor__section-head">
                  <h3>内容字段</h3>
                  <p>根据栏目模型动态生成，支持文本、资源、时间和富文本字段。</p>
                </header>

                <el-empty v-if="nativeMode && !renderedFields.length && !nativeRelations.length" description="当前栏目尚未配置内容字段，请先在栏目管理中设置字段。" />
                <div class="content-editor__field-list">
                  <el-form-item
                    v-for="field in renderedFields"
                    :key="field.fieldName"
                    class="content-editor__field-item"
                    :class="{ 'content-editor__field-item--rich': isFullWidthField(field) }"
                  >
                    <template #label>
                      <span>{{ field.title || field.fromName || field.fieldName }}</span>
                      <el-tag size="small" type="info" effect="plain" class="content-editor__field-name-tag" title="双击复制字段名" @dblclick="copyFieldName(field.fieldName)">{{ field.fieldName }}</el-tag>
                      <template v-for="hit in sensitiveHits[field.fieldName] ?? []" :key="`${field.fieldName}-${hit.word}-${hit.action}`">
                        <el-tag
                          size="small"
                          :type="sensitiveHitTagType(hit.action)"
                          effect="light"
                          class="content-editor__sensitive-tag"
                        >{{ formatSensitiveWordHit(hit) }}</el-tag>
                        <el-button
                          v-if="isFixableHit(hit)"
                          size="small"
                          text
                          type="primary"
                          class="content-editor__sensitive-confirm"
                          @click="confirmSensitiveFix(field, hit)"
                        >确认</el-button>
                      </template>
                      <span v-if="field.required" class="content-editor__required-mark">*</span>
                      <span v-if="field.bak && !field.enumValues?.length" class="content-editor__field-hint"> - {{ field.bak }}</span>
                    </template>
                    <div
                      class="cms-form__field-control"
                      :class="{ 'content-editor__field-item--rich': isFullWidthField(field) }"
                      :data-testid="`field-${field.fieldName}`"
                      :data-field-kind="fieldRenderKind(field)"
                      :data-field-rows="textareaRows(field)"
                    :data-field-picker-type="isDateTimeField(field) ? datePickerType(field) : undefined"
                  >
                      <el-tag v-if="nativeMode && field.translatable === false" size="small" type="info" effect="plain">共享字段</el-tag>
                      <el-input v-if="field.fieldName === readonlyField" :model-value="textValue(contentForm.values[field.fieldName])"
                        readonly :data-testid="`field-control-${field.fieldName}`" />
                      <el-input
                        v-else-if="normalizedFieldType(field) === 'password'"
                        v-model="contentForm.values[field.fieldName]"
                        type="password"
                        autocomplete="new-password"
                        :data-testid="`field-control-${field.fieldName}`"
                      />
                      <el-input
                        v-else-if="isLargeTextareaField(field)"
                        v-model="contentForm.values[field.fieldName]"
                        type="textarea"
                        :rows="8"
                        :data-testid="`field-control-${field.fieldName}`"
                      />
                      <CmsRichTextField
                        v-else-if="isRichTextField(field)"
                        :model-value="textValue(contentForm.values[field.fieldName])"
                        @update:model-value="contentForm.values[field.fieldName] = $event"
                        :field="field"
                        @blur="onFieldBlur(field)"
                      />
                      <el-input
                        v-else-if="isSmallTextareaField(field)"
                        v-model="contentForm.values[field.fieldName]"
                        type="textarea"
                        :rows="3"
                        :data-testid="`field-control-${field.fieldName}`"
                        @blur="onFieldBlur(field)"
                      />
                      <el-date-picker
                        v-else-if="isDateTimeField(field)"
                        v-model="contentForm.values[field.fieldName]"
                        :type="datePickerType(field)"
            :editable="!isDayDateField(field)"
            :disabled-date="isDayDateField(field) ? dayDateDisabled : undefined"
                        :value-format="datePickerFormat(field)"
                        :format="datePickerFormat(field)"
                        style="width: 100%"
                        :data-testid="`field-control-${field.fieldName}`"
                      />
                      <el-input-number
                        v-else-if="isNumberField(field)"
                        v-model="contentForm.values[field.fieldName]"
                        :min="-999999999"
                        :max="999999999"
                        style="width: 100%"
                        :data-testid="`field-control-${field.fieldName}`"
                      />
                      <CmsResourceUploadField
                        v-else-if="isResourceField(field)"
                        :model-value="textValue(contentForm.values[field.fieldName])"
                        @update:model-value="contentForm.values[field.fieldName] = $event"
                        :field="field"
                      >
                      </CmsResourceUploadField>
                      <CmsContentReferenceField
                        v-else-if="isExtContentReferenceField(field)"
                        :model-value="contentForm.values[field.fieldName]"
                        @update:model-value="(value: string) => setContentReferenceValue(field, value)"
                        :field="field" :menu-id="menuId" :active="visible" :record-key="recordId"
                        :label="field.title || field.fromName || field.fieldName"
                        :control-test-id="`field-control-${field.fieldName}`"
                      />
                      <el-select
                        v-else-if="isEnumField(field)"
                        :model-value="enumFieldValue(field)"
                        :multiple="isMultiSelectField(field)"
                        collapse-tags
                        collapse-tags-tooltip
                        clearable
                        filterable
                        :data-testid="`field-control-${field.fieldName}`"
                        @update:model-value="(v: unknown) => setEnumFieldValue(field, v)"
                      >
                        <template v-if="enumGroups(field)">
                          <el-option-group
                            v-for="group in enumGroups(field)"
                            :key="group.label"
                            :label="group.label"
                          >
                            <el-option
                              v-for="option in group.options"
                              :key="option.value"
                              :label="option.label"
                              :value="option.value"
                            />
                          </el-option-group>
                        </template>
                        <template v-else>
                          <el-option
                            v-for="option in enumOptions(field)"
                            :key="option.value"
                            :label="option.label"
                            :value="option.value"
                          />
                        </template>
                      </el-select>
                      <el-switch
                        v-else-if="isBooleanField(field)"
                        v-model="contentForm.values[field.fieldName]"
                        :data-testid="`field-control-${field.fieldName}`"
                      />
                      <el-input
                        v-else
                        v-model="contentForm.values[field.fieldName]"
                        :placeholder="fieldPlaceholder(field)"
                        :data-testid="`field-control-${field.fieldName}`"
                      />
                    </div>
                  </el-form-item>
                </div>
              </section>

              <slot name="owned-content" />
              <section v-if="!nativeMode" class="content-editor__advanced-panels" data-testid="content-editor-advanced-panels">
                <details class="content-editor__details" :open="mode === 'seo'">
                  <summary>SEO 信息</summary>
                  <ContentSeoPanel
                    :content-form="contentForm"
                    :preview-url="previewUrl"
                    :preview-url-template="previewUrlTemplate"
                    :seo-only="mode === 'seo'"
                  />
                </details>
                <details v-if="mode !== 'seo'" class="content-editor__details">
                  <summary>关联与推荐</summary>
                  <ContentRelationPanel :relation-summary="relationSummary" :content-form="contentForm" />
                </details>
              </section>
            </main>
          </div>

          <div class="cms-form__footer content-editor__actions" data-testid="content-editor-actions">
            <el-button @click="requestClose(() => emit('update:visible', false))">取消</el-button>
            <el-button type="primary" :loading="saving" :disabled="hasNoEditableFields" data-testid="content-save-button" @click="handleSave">
              {{ mode === 'seo' ? '保存 SEO' : '保存内容' }}
            </el-button>
          </div>
        </el-form>
      </section>
    </div>
  </el-drawer>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Close } from '@element-plus/icons-vue';
import CmsResourceUploadField from './CmsResourceUploadField.vue';
import CmsRichTextField from './CmsRichTextField.vue';
import CmsContentReferenceField from './CmsContentReferenceField.vue';
import { isExtContentReferenceField } from './contentReference';
import ContentRelationPanel from './ContentRelationPanel.vue';
import ContentSeoPanel from './ContentSeoPanel.vue';
import { fetchNativeSelectOptions, type ListFieldConfig, type SelectOptionItem } from '@/api/cms';
import { applySensitiveWordFix, checkSensitiveText, formatSensitiveWordHit, isFixableHit, type SensitiveWordHit } from '@/utils/sensitiveWord';
import { packMultiValue, unpackMultiValue } from '@/utils/multiValue';

interface FieldOption {
  datePrecision?: 'DAY' | 'SECOND' | null;
  fieldName: string;
  fromName?: string;
  fieldType?: string;
  title?: string;
  bak?: string;
  defaultValue?: string;
  enumValues?: string[];
  translatable?: boolean;
  required?: boolean;
  selectQuery?: string;
  inputType?: string;
  /** 选择方式：true=多选（仅固定枚举/栏目内容/栏目选项） */
  inputMultiple?: boolean;
  /** 允许不选：true=下拉存在空选项（仅栏目内容） */
  allowEmpty?: boolean;
  /** 级联：上级字段名，本字段下拉随其联动过滤；空=不级联 */
  cascadeParentField?: string;
}

interface RelationSummary {
  homepageRefs?: string[];
  menuRefs?: string[];
  relatedContents?: string[];
}

interface EnumOption {
  label: string;
  value: string;
  /** 来源分组（栏目内容多栏目时=来源栏目名） */
  group?: string | null;
}

const props = defineProps<{
  visible: boolean;
  title: string;
  saving: boolean;
  mode?: 'content' | 'seo';
  fields: FieldOption[];
  listFields?: ListFieldConfig[];
  contentForm: {
    title: string;
    sort: number;
    seoTitle: string;
    seoKeywords: string;
    seoDescription: string;
    detailPageUrl: string;
    indexTop: boolean;
    indexElite: boolean;
    menuTop: boolean;
    menuElite: boolean;
    xgid: number | null;
    values: Record<string, string | number | boolean | null>;
    relations: Record<string, string>;
  };
  previewUrl?: string;
  previewUrlTemplate?: string;
  relationSummary?: RelationSummary | null;
  nativeMode?: boolean;
  readonlyField?: string;
  nativeRelations?: Array<{ relationKey: string; displayName: string; multiple: boolean }>;
  /** 当前编辑内容所属栏目ID，用于动态下拉（栏目内容）选项查询 */
  menuId?: number;
  recordId?: number;
}>();


const emit = defineEmits<{
  'update:visible': [value: boolean];
  save: [];
}>();

const primaryTitleField = computed(() => props.fields.find(isPrimaryTitleField) ?? null);
// 只有当 listFields 中配置了 title 时才显示基础信息区块
const hasTitleListField = computed(() =>
  props.listFields?.some(f => f.fieldName.toLowerCase() === 'title') ?? false
);
const showBaseInfoCard = computed(() =>
  (props.mode ?? 'content') !== 'seo' && !primaryTitleField.value && hasTitleListField.value
);
const renderedFields = computed(() => props.fields);
const nativeRelations = computed(() => props.nativeRelations ?? []);
const hasNoEditableFields = computed(() => props.nativeMode && props.mode !== 'seo' && !renderedFields.value.length && !nativeRelations.value.length && !showBaseInfoCard.value);

// 动态下拉选项缓存
const dynamicSelectOptions = ref<Record<string, SelectOptionItem[]>>({});
const loadingSelectFields = ref<Set<string>>(new Set());
/** note/content 类字段的敏感词比对结果，key 为字段名 */
const sensitiveHits = reactive<Record<string, SensitiveWordHit[]>>({});

// 监听 fields 变化，加载动态下拉选项
// 字段名（singleLine5、singleLine1 等）在不同栏目间复用，缓存必须按栏目隔离：
// 否则切换栏目后会复用上一个栏目同名字段的旧选项，导致下拉显示的不是当前字段所配置栏目的内容。
let optionsMenuId: number | null = null;
watch(
  () => props.fields,
  (fields) => {
    if (!props.nativeMode) return;
    const currentMenuId = props.menuId ?? null;
    if (optionsMenuId !== currentMenuId) {
      dynamicSelectOptions.value = {};
      loadingSelectFields.value = new Set();
      optionsMenuId = currentMenuId;
    }
    if (!props.visible) return;
    const selectFields = fields.filter((f) => !isExtContentReferenceField(f) && f.selectQuery && f.selectQuery.startsWith('{$'));
    selectFields.forEach((field) => {
      if (!field.selectQuery) return;
      if (field.cascadeParentField) {
        // 级联字段：未选上级时显示为空；选了上级则按其值加载
        const pv = parentIdsOf(field).join(',');
        if (pv === '') {
          dynamicSelectOptions.value[field.fieldName] = [];
          return;
        }
        if (!dynamicSelectOptions.value[field.fieldName]?.length) {
          loadDynamicSelectOptions(field.fieldName, field.selectQuery, pv);
        }
        return;
      }
      if (!dynamicSelectOptions.value[field.fieldName]) {
        loadDynamicSelectOptions(field.fieldName, field.selectQuery);
      }
    });
  },
  { immediate: true }
);

/** 取字段的上级选中值（多选以 ,a,b, 存储，拆为 id 数组；未选为空数组） */
function parentIdsOf(field: FieldOption): string[] {
  if (!field.cascadeParentField) return [];
  const raw = props.contentForm.values[field.cascadeParentField];
  if (raw === undefined || raw === null || raw === '') return [];
  return unpackMultiValue(String(raw));
}

/** 用户改动某个上级字段后：以它为上级的字段按新值重载选项并清空值 */
function syncCascadeChildren(parentFieldName: string) {
  props.fields
    .filter((f) => f.cascadeParentField === parentFieldName && f.selectQuery && f.selectQuery.startsWith('{$'))
    .forEach((f) => {
      const pv = parentIdsOf(f).join(',');
      props.contentForm.values[f.fieldName] = isMultiSelectField(f) ? '' : null;
      if (pv === '') dynamicSelectOptions.value[f.fieldName] = [];
      else loadDynamicSelectOptions(f.fieldName, f.selectQuery!, pv);
    });
}

async function loadDynamicSelectOptions(fieldName: string, query: string, parentValue?: string) {
  if (loadingSelectFields.value.has(fieldName)) return;
  const requestMenuId = props.menuId ?? null;
  loadingSelectFields.value.add(fieldName);
  try {
    const menuId = props.menuId ?? Number(localStorage.getItem('cms-menu-id') || '0');
    const options = await fetchNativeSelectOptions(menuId, query, parentValue);
    // 请求期间若已切换栏目，丢弃过期响应，避免覆盖新栏目同名字段的选项
    if (requestMenuId !== (props.menuId ?? null)) return;
    dynamicSelectOptions.value[fieldName] = options;
  } catch (error) {
    console.error(`Failed to load select options for ${fieldName}:`, error);
    if (requestMenuId === (props.menuId ?? null)) dynamicSelectOptions.value[fieldName] = [];
  } finally {
    loadingSelectFields.value.delete(fieldName);
  }
}

const initialForm = ref('');
const confirmingClose = ref(false);
const hasChanges = computed(() => props.visible && JSON.stringify(props.contentForm) !== initialForm.value);
async function requestClose(done: () => void) {
  if (props.saving || confirmingClose.value) return;
  if (hasChanges.value) {
    confirmingClose.value = true;
    try {
      await ElMessageBox.confirm('放弃当前记录尚未保存的修改？', '有未保存的修改', { confirmButtonText: '放弃修改', cancelButtonText: '继续编辑' });
    } catch { return; }
    finally { confirmingClose.value = false; }
  }
  done();
}

watch(
  () => props.visible,
  (visible) => {
    if (visible) initialForm.value = JSON.stringify(props.contentForm);
    else clearSensitiveHits();
  }
);

function onDrawerVisibleChange(value: boolean) {
  emit('update:visible', value);
}

function sensitiveFieldCheckMode(field: FieldOption): 'html' | 'text' | null {
  if (isRichTextField(field)) return 'html';
  if (isSmallTextareaField(field)) return 'text';
  return null;
}

/** 失焦时调用：比对当前字段内容与敏感词规则，结果展示在字段标题旁 */
async function checkFieldSensitiveWords(field: FieldOption): Promise<SensitiveWordHit[]> {
  const mode = sensitiveFieldCheckMode(field);
  if (!mode) return [];
  try {
    const hits = await checkSensitiveText(String(props.contentForm.values[field.fieldName] ?? ''), { html: mode === 'html' });
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
  const next = applySensitiveWordFix(String(props.contentForm.values[key] ?? ''), hit);
  props.contentForm.values[key] = next;
  await checkFieldSensitiveWords(field);
}

function clearSensitiveHits() {
  Object.keys(sensitiveHits).forEach((key) => delete sensitiveHits[key]);
}

function sensitiveHitTagType(action: string): 'danger' | 'warning' | 'primary' {
  if (action === 'reject') return 'danger';
  if (action === 'alert') return 'warning';
  return 'primary';
}

/**
 * 保存前对全部 note/content 类字段统一比对敏感词并提示结果；
 * 存在“拒绝”类命中时返回 false，不允许提交。
 */
async function validateSensitiveWordsBeforeSave(): Promise<boolean> {
  const problems: string[] = [];
  const rejected: string[] = [];
  for (const field of props.fields) {
    if (!sensitiveFieldCheckMode(field)) continue;
    const hits = await checkFieldSensitiveWords(field);
    const fieldLabel = field.title || field.fromName || field.fieldName;
    for (const hit of hits) {
      const text = `${fieldLabel}：${formatSensitiveWordHit(hit)}`;
      problems.push(text);
      if (hit.action === 'reject') rejected.push(text);
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

async function handleSave() {
  if (hasNoEditableFields.value) return;
  if (props.mode !== 'seo' && !(await validateSensitiveWordsBeforeSave())) return;
  emit('save');
}

function normalizedFieldType(field: FieldOption) {
  return (field.fieldType ?? '').replace(/[\s_-]/g, '').toLowerCase();
}

function normalizedFieldName(field: FieldOption) {
  return field.fieldName.replace(/[\s_-]/g, '').toLowerCase();
}

function normalizedFieldLabel(field: FieldOption) {
  return (field.title || field.fromName || '').replace(/[\s_-]/g, '').toLowerCase();
}

function isPrimaryTitleField(field: FieldOption) {
  const name = normalizedFieldName(field);
  const label = normalizedFieldLabel(field);
  return ['title', 'name', 'fullname', 'username', 'xingming', 'xm'].includes(name)
    || ['标题', '姓名', '名称', '名字', '人员姓名', '姓名名称'].includes(label);
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
  return ['number', 'int', 'integer', 'decimal'].includes(normalizedFieldType(field));
}

function isBooleanField(field: FieldOption) {
  return ['boolean', 'bool'].includes(normalizedFieldType(field));
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

function isEnumField(field: FieldOption) {
  // 动态下拉字段
  if (field.selectQuery && field.selectQuery.startsWith('{$')) {
    return true;
  }
  return ['enum', 'select', 'radio', 'checkbox'].includes(normalizedFieldType(field));
}

/** 是否多选下拉（仅字段配置为多选时） */
function isMultiSelectField(field: FieldOption) {
  return Boolean(field.inputMultiple);
}

/** 多选字段的原始存储值为 ,a,b,，下拉需要数组；单选原样返回 */
function enumFieldValue(field: FieldOption): unknown {
  const raw = props.contentForm.values[field.fieldName];
  if (!isMultiSelectField(field)) {
    return raw;
  }
  return unpackMultiValue(typeof raw === 'string' ? raw : raw == null ? '' : String(raw));
}

function setContentReferenceValue(field: FieldOption, value: string) {
  props.contentForm.values[field.fieldName] = value;
  syncCascadeChildren(field.fieldName);
}

/** 多选下拉选中数组打包为 ,a,b, 存储；单选原样写回 */
function setEnumFieldValue(field: FieldOption, value: unknown) {
  if (!isMultiSelectField(field)) {
    props.contentForm.values[field.fieldName] = (value ?? null) as string | number | boolean | null;
  } else {
    props.contentForm.values[field.fieldName] = packMultiValue(
      Array.isArray(value) ? (value as Array<string | number>) : []
    );
  }
  // 该字段若为其它字段的级联上级，联动刷新下级下拉
  syncCascadeChildren(field.fieldName);
}

function isFullWidthField(field: FieldOption) {
  // 富文本编辑器和多行文本域占满整行宽度
  return isRichTextField(field) || isLargeTextareaField(field) || isSmallTextareaField(field);
}

async function copyFieldName(fieldName: string) {
  try {
    await navigator.clipboard.writeText(fieldName);
    ElMessage.success(`已复制: ${fieldName}`);
  } catch {
    ElMessage.error('复制失败');
  }
}

function fieldRenderKind(field: FieldOption) {
  if (isExtContentReferenceField(field)) return 'content-reference';
  if (isLargeTextareaField(field)) {
    return 'large-textarea';
  }
  if (isRichTextField(field)) {
    return 'rich-text';
  }
  if (isSmallTextareaField(field)) {
    return 'small-textarea';
  }
  if (isDateTimeField(field)) {
    return 'datetime';
  }
  if (isNumberField(field)) {
    return 'number';
  }
  if (isImageField(field)) {
    return 'image';
  }
  if (isMultiImageField(field)) {
    return 'multi-image';
  }
  if (isFileField(field)) {
    return 'file';
  }
  if (isVideoField(field)) {
    return 'video';
  }
  if (isEnumField(field)) {
    return 'enum';
  }
  if (isBooleanField(field)) {
    return 'boolean';
  }
  return 'input';
}

function textareaRows(field: FieldOption) {
  if (isLargeTextareaField(field)) {
    return 8;
  }
  if (isSmallTextareaField(field)) {
    return 3;
  }
  return undefined;
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
  const type = normalizedFieldType(field);
  return type === 'date' ? 'date' : type === 'time' ? 'time' : 'datetime';
}

function enumOptions(field: FieldOption): EnumOption[] {
  const options = enumOptionsRaw(field);
  // 「允许不选」：栏目内容下拉前置一个空选项（值=空串）
  if (field.allowEmpty) {
    return [{ value: '', label: '（空）' }, ...options];
  }
  return options;
}

function enumOptionsRaw(field: FieldOption): EnumOption[] {
  // 动态下拉选项
  if (field.selectQuery && field.selectQuery.startsWith('{$')) {
    const cached = dynamicSelectOptions.value[field.fieldName];
    if (cached) {
      return cached.map((item) => ({ value: item.value, label: item.label, group: item.group ?? null }));
    }
    return [];
  }
  // 优先使用 enumValues 数组
  if (field.enumValues && field.enumValues.length > 0) {
    return field.enumValues.map((item) => {
      const [value, label] = item.includes(':')
        ? item.split(':', 2)
        : item.includes('=')
          ? item.split('=', 2)
          : [item, item];
      return { value: value.trim(), label: label.trim() };
    });
  }
  // 兼容旧的 bak 或 defaultValue 格式
  const source = (field.bak || field.defaultValue || '').trim();
  if (!source) {
    return [];
  }
  return source
    .split(/[\n,|]/)
    .map((item) => item.trim())
    .filter(Boolean)
    .map((item) => {
      const [value, label] = item.includes(':')
        ? item.split(':', 2)
        : item.includes('=')
          ? item.split('=', 2)
          : [item, item];
      return { value: value.trim(), label: label.trim() };
    });
}

/** 选项按来源栏目分组；无分组信息时返回 null，按平铺渲染 */
function enumGroups(field: FieldOption): Array<{ label: string; options: EnumOption[] }> | null {
  const options = enumOptions(field);
  const grouped = options.filter((option) => option.group);
  if (!grouped.length) return null;
  // 「允许不选」的空选项无来源分组，并入第一个分组，避免出现空标题分组
  const empties = options.filter((option) => !option.group && option.value === '');
  const groups = new Map<string, EnumOption[]>();
  for (const option of grouped) {
    const key = option.group || '';
    const bucket = groups.get(key);
    if (bucket) bucket.push(option);
    else groups.set(key, [option]);
  }
  const result = Array.from(groups.entries()).map(([label, opts]) => ({ label, options: opts }));
  if (empties.length && result.length) {
    result[0].options = [...empties, ...result[0].options];
  }
  return result;
}

function fieldPlaceholder(field: FieldOption) {
  if (isImageField(field)) {
    return '图片地址';
  }
  if (isFileField(field)) {
    return '文件地址';
  }
  return '';
}

function textValue(value: string | number | boolean | null) {
  return value == null ? '' : String(value);
}
</script>

<style>
.content-editor-drawer-modal {
  /* 抽屉模式：遮罩层默认样式 */
}

.content-editor-drawer-modal .content-editor-drawer.el-drawer {
  height: 100%;
  max-height: 100vh;
  overflow: hidden;
  box-shadow: -8px 0 24px rgb(15 23 42 / 0.12);
}

@media (max-width: 640px) {
  .content-editor-drawer-modal .content-editor-drawer.el-drawer {
    max-height: 100vh;
  }
}
</style>

<style scoped>
.content-editor-drawer :deep(.el-drawer__body) {
  padding: 0;
  background: #f6f8fb;
}

.content-editor {
  display: grid;
  grid-template-rows: auto minmax(0, 1fr);
  min-height: 100%;
  min-width: 0;
}

.content-editor__topbar {
  position: sticky;
  top: 0;
  z-index: 5;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  min-height: 64px;
  padding: 0 24px;
  border-bottom: 1px solid var(--admin-border-subtle);
  background: color-mix(in srgb, var(--admin-bg-surface) 96%, transparent);
  backdrop-filter: blur(10px);
}

.content-editor__topbar-copy {
  display: flex;
  align-items: baseline;
  gap: 12px;
  min-width: 0;
}

.content-editor__topbar h2 {
  margin: 0;
  color: var(--admin-text-primary);
  font-size: var(--admin-font-size-title);
  font-weight: 700;
  line-height: 1.3;
  letter-spacing: 0;
}

.content-editor__topbar p {
  margin: 0;
  color: var(--admin-text-muted);
  font-size: 13px;
  line-height: 1.4;
}

.content-editor__topbar-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex: 0 0 auto;
}

.content-editor__close {
  color: var(--admin-text-secondary);
}

.content-editor__shell {
  display: grid;
  gap: 14px;
  min-width: 0;
  padding: 16px 24px 0;
}

.content-editor__section-head h3 {
  margin: 0;
  color: var(--admin-text-primary);
  letter-spacing: 0;
}

.content-editor__section-head p {
  margin: 0;
  color: var(--admin-text-muted);
  line-height: 1.6;
}

.content-editor__form {
  grid-template-rows: 1fr auto;
  gap: 0;
}

.content-editor__layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  align-items: start;
}

.content-editor__main {
  display: grid;
  gap: 12px;
  width: min(100%, 1040px);
  margin: 0 auto;
  min-width: 0;
}

.cms-form {
  display: grid;
  gap: 12px;
}

.content-editor__card {
  display: grid;
  gap: 14px;
  min-width: 0;
  padding: 18px;
  border: var(--admin-card-border);
  border-radius: var(--admin-radius-md);
  background: var(--admin-bg-surface);
  box-shadow: 0 1px 2px rgb(15 23 42 / 0.04);
}

.content-editor__base-card {
  grid-template-columns: 140px minmax(0, 1fr);
  align-items: start;
  padding: 14px 18px;
}

.content-editor__section-label {
  display: grid;
  gap: 3px;
  min-width: 0;
  padding-top: 4px;
}

.content-editor__section-label h3 {
  margin: 0;
  color: var(--admin-text-primary);
  font-size: 15px;
  line-height: 1.35;
}

.content-editor__section-label p {
  margin: 0;
  color: var(--admin-text-muted);
  font-size: 12px;
  line-height: 1.4;
}

.content-editor__section-head {
  display: grid;
  gap: 4px;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--admin-border-subtle);
}

.content-editor__section-head h3 {
  font-size: 16px;
  line-height: 1.4;
}

.content-editor__section-head p {
  font-size: 12px;
}

.cms-form__grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
}

.content-editor__base-grid {
  align-items: start;
  gap: 14px;
}

.content-editor__field-list {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px 20px;
  align-items: start;
}

.content-editor__field-item {
  margin-bottom: 0;
}

.content-editor__field-item--rich {
  grid-column: 1 / -1;
  padding-top: 2px;
}

.content-editor__field-item :deep(.el-form-item__label) {
  margin-bottom: 6px;
  color: var(--admin-text-secondary);
  font-weight: 700;
  line-height: 1.35;
}

.content-editor__required-mark {
  color: #f56c6c;
  margin-left: 2px;
}

.content-editor__field-hint {
  color: #f56c6c;
  font-weight: 400;
  font-size: 12px;
}

.content-editor__field-name-tag {
  margin-left: 8px;
  font-family: 'Consolas', 'Monaco', monospace;
  font-size: 11px;
  color: #909399;
  border-color: #e4e7ed;
  cursor: pointer;
  user-select: none;
}

.content-editor__field-name-tag:hover {
  color: #409eff;
  border-color: #409eff;
}

.content-editor__sensitive-tag {
  margin-left: 8px;
}

.content-editor__sensitive-confirm {
  margin-left: 2px;
}

.content-editor__field-item :deep(.el-input__wrapper),
.content-editor__field-item :deep(.el-textarea__inner) {
  box-shadow: 0 0 0 1px var(--admin-border-subtle) inset;
}

.cms-form__field-control {
  width: 100%;
}

.cms-form__field-control.content-editor__field-item--rich,
.content-editor__field-item--rich .cms-form__field-control {
  width: 100%;
}

.content-editor__field-item--rich :deep(.el-textarea) {
  width: 100%;
}

.content-editor__advanced-panels {
  display: grid;
  gap: 10px;
  min-width: 0;
}

.content-editor__details {
  overflow: hidden;
  border: 1px solid var(--admin-border-subtle);
  border-radius: var(--admin-radius-md);
  background: var(--admin-bg-surface);
  box-shadow: 0 1px 2px rgb(15 23 42 / 0.04);
}

.content-editor__details > summary {
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: 42px;
  padding: 0 16px;
  color: var(--admin-text-secondary);
  font-size: 14px;
  font-weight: 700;
  cursor: pointer;
  user-select: none;
}

.content-editor__details > summary::marker {
  color: var(--admin-color-primary);
}

.content-editor__details :deep(.content-side-panel) {
  border: 0;
  border-top: 1px solid var(--admin-border-subtle);
  border-radius: 0;
  box-shadow: none;
}

.cms-form__footer {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
}

.content-editor__actions {
  position: sticky;
  bottom: 0;
  z-index: 2;
  margin: 16px -24px 0;
  padding: 12px 24px;
  border-top: 1px solid var(--admin-border-subtle);
  background: color-mix(in srgb, #f6f8fb 94%, transparent);
  backdrop-filter: blur(8px);
}

@media (max-width: 1080px) {
  .cms-form__grid,
  .content-editor__field-list,
  .content-editor__base-card {
    grid-template-columns: minmax(0, 1fr);
  }
}

@media (max-width: 640px) {
  .content-editor__topbar {
    min-height: 58px;
    padding: 0 16px;
  }

  .content-editor__topbar-copy {
    display: grid;
    gap: 2px;
  }

  .content-editor__shell {
    padding-right: 16px;
    padding-left: 16px;
  }

  .content-editor__actions {
    margin-right: -16px;
    margin-left: -16px;
  }

  .content-editor__actions :deep(.el-button) {
    flex: 1 1 0;
  }
}
</style>
