<template>
  <section
    class="resource-field"
    :data-testid="`resource-field-${id}`"
    :data-resource-type="type"
    :data-multiple="multiple ? 'true' : 'false'"
  >
    <label v-if="label" class="resource-field__label" :for="inputId">{{ label }}</label>

    <div v-if="previewUrls.length" class="resource-field__preview-row">
      <div class="resource-field__previews" :class="{ 'resource-field__previews--grid': multiple }">
        <ResourcePreview
          v-for="(url, index) in previewUrls"
          :key="`${url}-${index}`"
          :url="url"
          :media-type="previewMediaType"
          :name="fileName(resourceValues[index] || url)"
          :preview-test-id="`resource-preview-${id}-${index}`"
          :missing-test-id="`resource-preview-missing-${id}-${index}`"
        />
      </div>
      <el-button size="small" data-testid="resource-clear" plain @click="clearValue">清除</el-button>
    </div>

    <div class="resource-field__controls">
      <el-input
        :id="inputId"
        :model-value="textValue"
        :type="multiple ? 'textarea' : 'text'"
        :autosize="{ minRows: 2, maxRows: 6 }"
        :placeholder="placeholder || defaultPlaceholder"
        :data-testid="`resource-path-${id}`"
        @update:model-value="updateTextValue"
      />

      <label class="resource-field__upload">
        <span class="resource-field__button">本地上传</span>
        <input
          data-testid="resource-upload-input"
          type="file"
          :accept="resolvedAccept"
          :multiple="multiple"
          @change="uploadFromInput"
        />
      </label>

      <el-button size="small" data-testid="resource-picker-open" plain @click="pickerOpen = true">选择已上传</el-button>
      <el-button size="small" v-if="showStaticImagePicker" data-testid="static-image-picker-open" plain @click="staticImagePickerOpen = true">选择图片</el-button>
      <slot name="actions" />
      <el-button size="small" v-if="showStaticVideoPicker" data-testid="static-video-picker-open" plain @click="staticVideoPickerOpen = true">选择视频</el-button>
    </div>

    <ResourcePickerDialog
      v-model="pickerOpen"
      :type="type"
      :scope="scope"
      :multiple="multiple"
      :title="label ? `选择${label}` : '选择资源'"
      :allowed-extensions="allowedExtensions"
      :manageable="manageable"
      @select="selectResource"
      @delete="removeDeletedResource"
    />

    <StaticImagePickerDialog
      v-model="staticImagePickerOpen"
      :multiple="multiple"
      @select="selectResource"
    />

    <StaticVideoPickerDialog
      v-model="staticVideoPickerOpen"
      :multiple="multiple"
      @select="selectResource"
    />
  </section>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { ElMessage } from 'element-plus';
import { getUiPackageAccessUrl, startUiPackage } from '@/api/uiPackage';
import { uploadAsset } from '@/api/assetsUpload';
import { useResourceReferences } from '@/api/resourceReferences';
import { useSiteContextStore } from '@/stores/siteContext';
import ResourcePickerDialog from './ResourcePickerDialog.vue';
import ResourcePreview from './ResourcePreview.vue';
import StaticImagePickerDialog from './StaticImagePickerDialog.vue';
import StaticVideoPickerDialog from './StaticVideoPickerDialog.vue';
import { isAllowedResourceExtension, resourceAccept, type ResourceRequestType } from './types';

const props = withDefaults(defineProps<{
  id: string;
  modelValue: string | null | undefined;
  type: ResourceRequestType;
  scope?: 'site' | 'global';
  multiple?: boolean;
  label?: string;
  placeholder?: string;
  accept?: string;
  allowedExtensions?: string[];
  manageable?: boolean;
}>(), {
  scope: 'site',
  multiple: false,
  label: '',
  placeholder: '',
  accept: '',
  allowedExtensions: () => [],
  manageable: false
});

const emit = defineEmits<{
  'update:modelValue': [value: string];
}>();

const siteContext = useSiteContextStore();
const pickerOpen = ref(false);
const staticImagePickerOpen = ref(false);
const staticVideoPickerOpen = ref(false);
const inputId = computed(() => `resource-field-input-${props.id}`);
const textValue = computed(() => props.modelValue == null ? '' : String(props.modelValue));
const resolvedAccept = computed(() => props.accept || resourceAccept(props.type));
const previewMediaType = computed(() => props.type === 'all' ? undefined : props.type);
const resourceValues = computed(() => parseResourceValues(textValue.value));
const defaultPlaceholder = computed(() => props.multiple ? '每行一个资源地址' : '请输入或选择资源地址');
const showStaticImagePicker = computed(() => props.scope !== 'global' && props.type === 'image');
const showStaticVideoPicker = computed(() => props.scope !== 'global' && props.type === 'video');

const { resolve: resolveResource, refresh: refreshResources } = useResourceReferences(() => props.scope);
const previewUrls = computed(() => resourceValues.value.map(resolveResource));

function updateTextValue(value: string) {
  emit('update:modelValue', value);
}

async function uploadFromInput(event: Event) {
  const input = event.target as HTMLInputElement;
  const files = Array.from(input.files ?? []);
  if (!files.length) {
    return;
  }
  const disallowedFiles = files.filter((file) => !isAllowedResourceExtension(file.name, props.allowedExtensions));
  if (disallowedFiles.length) {
    ElMessage.warning(`当前字段不允许上传这些文件格式：${disallowedFiles.map((file) => file.name).join('、')}`);
    input.value = '';
    return;
  }
  try {
    const uploadedUrls: string[] = [];
    for (const file of files) {
      const response = await uploadAsset(file, uploadRequestTypeForFile(file.name), (props.scope === 'global' || !siteContext.currentSite?.id) ? 'global' : 'site');
      const url = response.reference;
      if (url && isAllowedUrl(url)) {
        uploadedUrls.push(url);
      }
    }
    if (!uploadedUrls.length) {
      throw new Error('上传成功但未返回资源地址');
    }
    emitSelected(uploadedUrls);
    ElMessage.success('资源已上传');
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '资源上传失败');
  } finally {
    input.value = '';
  }
}

function selectResource(value: string | string[]) {
  void refreshResources();
  emitSelected(Array.isArray(value) ? value : [value]);
  if (!props.multiple) {
    pickerOpen.value = false;
  }
}

function emitSelected(urls: string[]) {
  const allowedUrls = urls.filter(isAllowedUrl);
  if (!allowedUrls.length) {
    ElMessage.warning('该资源格式不允许用于当前字段');
    return;
  }
  if (!props.multiple) {
    emit('update:modelValue', allowedUrls[0] ?? '');
    return;
  }
  const nextValues = uniqueValues([...resourceValues.value, ...allowedUrls]);
  emit('update:modelValue', nextValues.join('\n'));
}

function clearValue() {
  emit('update:modelValue', '');
}

function removeDeletedResource(url: string) {
  const nextValues = resourceValues.value.filter((value) => value !== url);
  if (nextValues.length === resourceValues.value.length) {
    return;
  }
  emit('update:modelValue', props.multiple ? nextValues.join('\n') : '');
}

function parseResourceValues(value: string) {
  const trimmed = value.trim();
  if (!trimmed) {
    return [];
  }
  if (trimmed.startsWith('[')) {
    try {
      const parsed = JSON.parse(trimmed);
      if (Array.isArray(parsed)) {
        return parsed.map((item) => String(item).trim()).filter(Boolean);
      }
    } catch {
      return [trimmed];
    }
  }
  return trimmed.split(/[\n,;]+/).map((item) => item.trim()).filter(Boolean);
}

function uniqueValues(values: string[]) {
  return Array.from(new Set(values.map((value) => value.trim()).filter(Boolean)));
}

function isAllowedUrl(url: string) {
  return isAllowedResourceExtension(url, props.allowedExtensions);
}

function fileName(url: string) {
  return url.split('/').filter(Boolean).at(-1) ?? url;
}

function uploadRequestTypeForFile(_fileName: string): ResourceRequestType {
  return props.type;
}
</script>

<style scoped>
.resource-field {
  display: grid;
  gap: 10px;
  min-width: 0;
}

.resource-field__label {
  font-weight: 600;
  color: var(--admin-text);
}

.resource-field__preview-row {
  display: flex;
  align-items: flex-end;
  gap: 10px;
  min-width: 0;
}

.resource-field__preview-row > .el-button {
  flex-shrink: 0;
  margin-left: 0;
}

.resource-field__previews {
  display: flex;
  flex-wrap: wrap;
  min-width: 0;
  gap: 10px;
}

.resource-field__previews--grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(min(140px, 100%), 1fr));
  flex: 1;
}

.resource-field__controls {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto auto auto;
  gap: 6px;
  align-items: center;
}

.resource-field__upload {
  position: relative;
  display: inline-flex;
}

.resource-field__upload input {
  position: absolute;
  inset: 0;
  opacity: 0;
  cursor: pointer;
}

.resource-field__button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  height: 24px;
  min-height: 24px;
  padding: 0 8px;
  border: 1px solid var(--admin-border-subtle);
  border-radius: var(--admin-radius-sm);
  background: #fff;
  color: var(--admin-text);
  cursor: pointer;
}

.resource-field__button,
.resource-field__controls > .el-button,
.resource-field__preview-row > .el-button {
  height: 24px;
  min-height: 24px;
  padding: 0 8px;
  font-family: inherit;
  font-size: 12px;
  font-weight: 500;
  line-height: 1;
  color: var(--admin-text);
}

@media (max-width: 640px) {
  .resource-field__controls {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
