<template>
  <section class="cms-page cms-single admin-responsive-page">
    <header class="cms-page__hero">
      <div class="cms-page__hero-copy">
        <p class="admin-eyebrow">内容管理</p>
        <h1>单页维护</h1>
        <p>{{ heroDescription }}</p>
      </div>
      <div class="cms-page__actions admin-toolbar">
        <el-button :loading="loading" :disabled="saving || !selectedMenuId" @click="refreshSinglePage">刷新</el-button>
        <el-button type="primary" :loading="saving" :disabled="!selectedMenuId || invalidRouteMenu || fields.length === 0" data-testid="single-page-save" @click="saveSinglePage">
          保存
        </el-button>
      </div>
    </header>

    <main class="cms-card cms-single__workspace">
      <div class="cms-card__header cms-single__header">
        <div>
          <h2>{{ workspaceTitle }}</h2>
          <p>{{ workspaceSubtitle }}</p>
          <p v-if="fields.length" role="status">{{ dirty ? '修改尚未保存' : '当前显示已保存的内容' }}</p>
        </div>
      </div>

      <section v-if="loadingRouteMenuContext" class="cms-single__state admin-empty-state" data-testid="single-loading-context-state">
        <h3>正在加载栏目上下文</h3>
        <p>正在读取当前站点的栏目导航，加载完成后会自动判断该栏目是否为单页。</p>
      </section>

      <section v-else-if="invalidRouteMenu" class="cms-single__state cms-single__state--error admin-empty-state" data-testid="single-invalid-state">
        <h3>栏目不可作为单页维护</h3>
        <p>该栏目不属于当前站点，或不是单页栏目。</p>
      </section>

      <el-form v-else-if="fields.length" :inert="saving || loading" label-position="top" class="cms-form cms-single__form" data-testid="single-page-form" @submit.prevent>
        <el-form-item
          v-for="field in fields"
          :key="field.fieldName"
        >
          <template #label>
            <span>{{ field.title || field.fromName || field.fieldName }}</span>
            <span v-if="field.bak" class="cms-single__field-hint"> - {{ field.bak }}</span>
          </template>
          <el-input
            v-if="isLargeTextareaField(field)"
            v-model="formValues[field.fieldName]"
            type="textarea"
            :rows="10"
            :data-testid="`single-field-control-${field.fieldName}`"
          />
          <CmsRichTextField
            v-else-if="isRichTextField(field)"
            v-model="formValues[field.fieldName]"
            :field="field"
          />
          <el-input
            v-else-if="isSmallTextareaField(field)"
            v-model="formValues[field.fieldName]"
            type="textarea"
            :rows="4"
            :data-testid="`single-field-control-${field.fieldName}`"
          />
          <el-date-picker
            v-else-if="isDateTimeField(field)"
            v-model="formValues[field.fieldName]"
            :type="datePickerType(field)"
            :editable="!isDayDateField(field)"
            :disabled-date="isDayDateField(field) ? dayDateDisabled : undefined"
            :value-format="datePickerFormat(field)"
            :format="datePickerFormat(field)"
            style="width: 100%"
            :data-testid="`single-field-control-${field.fieldName}`"
          />
          <el-input-number
            v-else-if="isNumberField(field)"
            v-model="formValues[field.fieldName]"
            :min="-999999999"
            :max="999999999"
            style="width: 100%"
            :data-testid="`single-field-control-${field.fieldName}`"
          />
          <CmsResourceUploadField
            v-else-if="isResourceField(field)"
            v-model="formValues[field.fieldName]"
            :field="field"
          />
          <CmsContentReferenceField
            v-else-if="isExtContentReferenceField(field)"
            v-model="formValues[field.fieldName]"
            :field="field" :menu-id="selectedMenuId"
            :label="field.title || field.fromName || field.fieldName"
            :control-test-id="`single-field-control-${field.fieldName}`"
          />
          <el-input
            v-else
            v-model="formValues[field.fieldName]"
            :placeholder="fieldPlaceholder(field)"
            :data-testid="`single-field-control-${field.fieldName}`"
          />
        </el-form-item>
      </el-form>

      <section v-else class="cms-single__state admin-empty-state" data-testid="single-empty-fields-state">
        <h3>该栏目尚未配置内容字段</h3>
        <p>请先在栏目管理的字段设置中启用并配置字段，再返回维护单页内容。</p>
      </section>
    </main>
  </section>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue';
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import { flattenContentMenus, useSiteContextStore } from '@/stores/siteContext';
import { http } from '@/api/http';
import CmsResourceUploadField from './content/CmsResourceUploadField.vue';
import CmsRichTextField from './content/CmsRichTextField.vue';
import CmsContentReferenceField from './content/CmsContentReferenceField.vue';
import { isExtContentReferenceField } from './content/contentReference';

interface FieldOption {
  datePrecision?: 'DAY' | 'SECOND' | null;
  fieldName: string;
  fromName?: string;
  fieldType?: string;
  title?: string;
  bak?: string;
  defaultValue?: string;
  selectQuery?: string;
  inputType?: string;
  inputMultiple?: boolean;
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
}

const route = useRoute();
const siteContext = useSiteContextStore();
const loading = ref(false);
const saving = ref(false);
const selectedMenuId = ref<number | null>(null);
const invalidRouteMenu = ref(false);
const fields = ref<FieldOption[]>([]);
const formValues = reactive<Record<string, string | number | null>>({});
const savedValues = ref('{}');
const dirty = computed(() => fields.value.length > 0 && JSON.stringify(formValues) !== savedValues.value);
async function confirmLeave() {
  if (saving.value) { ElMessage.info('正在保存，请稍候'); return false; }
  if (!dirty.value) return true;
  try {
    await ElMessageBox.confirm('放弃当前单页尚未保存的修改？', '有未保存的修改', { confirmButtonText: '放弃修改', cancelButtonText: '继续编辑' });
    return true;
  } catch { return false; }
}
async function refreshSinglePage() { if (await confirmLeave()) await loadSinglePage(); }
onBeforeRouteLeave(confirmLeave);
onBeforeRouteUpdate((to, from) => to.params.menuId === from.params.menuId || confirmLeave());
siteContext.$onAction(({ name, args }) => {
  if (name === 'switchSite' && String(args[0]) !== String(siteContext.currentSite?.id) && (dirty.value || saving.value)) {
    const message = '请先保存单页修改，或刷新单页并确认放弃修改后再切换站点。';
    ElMessage.warning(message);
    throw new Error(message);
  }
});
let requestSeq = 0;

const allMenus = computed(() => flattenContentMenus(siteContext.contentMenus));
const currentMenu = computed(() => allMenus.value.find((menu) => menu.id === selectedMenuId.value) ?? null);
const loadingRouteMenuContext = computed(() => (
  hasRouteMenuParam.value
  && allMenus.value.length === 0
  && (siteContext.loading || siteContext.switching)
));
const hasRouteMenuParam = computed(() => routeMenuParam() !== null);
const workspaceTitle = computed(() => {
  if (invalidRouteMenu.value) {
    return '栏目不可维护';
  }
  if (loadingRouteMenuContext.value) {
    return '栏目上下文加载中';
  }
  return currentMenu.value?.className ?? '单页维护';
});
const workspaceSubtitle = computed(() => {
  if (invalidRouteMenu.value) {
    return '只有当前站点下的单页栏目才会加载单页编辑表单。';
  }
  return '字段由当前栏目的字段设置决定，保存后写入该栏目的单页内容。';
});
const heroDescription = computed(() => {
  const siteName = siteContext.currentSite?.name ?? '未选择站点';
  const menuName = currentMenu.value?.className;
  return menuName ? `${siteName} / ${menuName}` : `${siteName} / 单页维护`;
});

watch(
  () => [route.params.menuId, siteContext.contentMenus, siteContext.currentSite?.id] as const,
  () => {
    syncSelectedMenuWithRoute();
    void loadSinglePage();
  },
  { immediate: true }
);

async function loadSinglePage() {
  const currentRequest = ++requestSeq;
  if (!selectedMenuId.value || invalidRouteMenu.value) {
    clearSinglePageState();
    loading.value = false;
    return;
  }
  loading.value = true;
  try {
    const data = await http.get<SinglePageResponse>(`/api/admin/v2/cms/single/${selectedMenuId.value}`);
    if (currentRequest !== requestSeq) {
      return;
    }
    fields.value = data.fields ?? [];
    replaceFormValues(data.content?.values ?? {});
  } catch (error) {
    if (currentRequest === requestSeq) {
      clearSinglePageState();
      ElMessage.error(errorMessage(error, '单页内容加载失败'));
    }
  } finally {
    if (currentRequest === requestSeq) {
      loading.value = false;
    }
  }
}

async function saveSinglePage() {
  if (!selectedMenuId.value || invalidRouteMenu.value || fields.value.length === 0) {
    return;
  }
  const token = requestSeq;
  saving.value = true;
  try {
    const data = await http.post<SinglePageResponse>(`/api/admin/v2/cms/single/${selectedMenuId.value}`, {
      values: { ...formValues }
    });
    if (token !== requestSeq) return;
    fields.value = data.fields ?? fields.value;
    replaceFormValues(data.content?.values ?? { ...formValues });
    ElMessage.success('单页内容已保存');
  } catch (error) {
    ElMessage.error(errorMessage(error, '单页内容保存失败'));
  } finally {
    saving.value = false;
  }
}

function syncSelectedMenuWithRoute() {
  const rawMenuId = routeMenuParam();
  invalidRouteMenu.value = false;
  if (rawMenuId === null || !/^[1-9]\d*$/.test(rawMenuId)) {
    selectedMenuId.value = null;
    invalidRouteMenu.value = rawMenuId !== null;
    clearSinglePageState();
    return;
  }

  const menuId = Number(rawMenuId);
  if (allMenus.value.length === 0 && (siteContext.loading || siteContext.switching)) {
    selectedMenuId.value = null;
    clearSinglePageState();
    return;
  }

  const menu = allMenus.value.find((item) => item.id === menuId);
  if (!menu || !isSinglePageMenu(menu)) {
    selectedMenuId.value = null;
    invalidRouteMenu.value = true;
    clearSinglePageState();
    return;
  }

  selectedMenuId.value = menuId;
}

function isSinglePageMenu(menu: { pageType?: number | null; manageReason?: string | null }) {
  return menu.pageType === 0 || menu.manageReason === 'single-page-non-content';
}

function replaceFormValues(values: Record<string, string | number | null>) {
  Object.keys(formValues).forEach((key) => {
    delete formValues[key];
  });
  fields.value.forEach((field) => {
    formValues[field.fieldName] = values[field.fieldName] ?? '';
  });
  savedValues.value = JSON.stringify(formValues);
}

function clearSinglePageState() {
  fields.value = [];
  Object.keys(formValues).forEach((key) => {
    delete formValues[key];
  });
}

function routeMenuParam() {
  const raw = route.params.menuId;
  const value = Array.isArray(raw) ? raw[0] : raw;
  return value || null;
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
  if (['image', 'photo', 'picture'].includes(type)) {
    return '图片地址';
  }
  if (['file', 'attachment', 'download'].includes(type)) {
    return '文件地址';
  }
  return '';
}

function errorMessage(error: unknown, fallback: string) {
  return error instanceof Error ? error.message : fallback;
}
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
  gap: 18px;
  padding: var(--admin-card-padding);
}

.cms-page__hero-copy {
  min-width: 0;
}

.cms-page__hero h1,
.cms-page__hero p {
  margin: 0;
}

.cms-page__actions {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.cms-single__workspace {
  display: grid;
  gap: 16px;
  padding: 18px;
}

.cms-card__header {
  display: flex;
  justify-content: space-between;
  gap: 14px;
}

.cms-card__header h2,
.cms-card__header p {
  margin: 0;
}

.cms-single__form {
  display: grid;
  gap: 12px;
  max-width: 960px;
}

.cms-single__field-hint {
  color: #f56c6c;
  font-weight: 400;
}

.cms-single__state {
  min-height: 260px;
  align-content: center;
}

.cms-single__state--error {
  border-color: var(--el-color-warning-light-5);
}

@media (max-width: 760px) {
  .cms-page__hero,
  .cms-card__header {
    display: grid;
  }
}
</style>
