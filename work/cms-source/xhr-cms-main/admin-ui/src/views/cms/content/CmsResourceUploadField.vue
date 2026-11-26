<template>
  <div
    class="cms-resource-upload"
    :data-testid="`resource-upload-${field.fieldName}`"
    :data-resource-type="resourceType"
    :data-multiple="isMultiImageResource ? 'true' : 'false'"
    :data-accept="accept"
  >
    <ResourceFieldControl
      :id="field.fieldName"
      :model-value="normalizedValue"
      :type="resourceType"
      :multiple="isMultiImageResource"
      :placeholder="placeholder"
      :accept="accept"
      manageable
      @update:model-value="emit('update:modelValue', $event)"
    >
      <template #actions><slot name="actions" /></template>
    </ResourceFieldControl>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import ResourceFieldControl from '@/components/resources/ResourceFieldControl.vue';
import type { ResourceRequestType } from '@/components/resources/types';

interface FieldOption {
  fieldName: string;
  fromName?: string;
  fieldType?: string;
  title?: string;
}

const props = defineProps<{
  field: FieldOption;
  modelValue: string | number | null | undefined;
}>();

const emit = defineEmits<{
  'update:modelValue': [value: string];
}>();

const normalizedType = computed(() => (props.field.fieldType ?? '').replace(/[\s_-]/g, '').toLowerCase());
const normalizedValue = computed(() => props.modelValue == null ? '' : String(props.modelValue));
const isImageResource = computed(() => ['image', 'photo', 'picture'].includes(normalizedType.value));
const isMultiImageResource = computed(() => ['multiimage', 'multiimages', 'images', 'gallery', 'photos', 'pictures'].includes(normalizedType.value));
const isVideoResource = computed(() => ['video', 'media'].includes(normalizedType.value));
const resourceType = computed<ResourceRequestType>(() => {
  if (isImageResource.value || isMultiImageResource.value) {
    return 'image';
  }
  if (isVideoResource.value) {
    return 'video';
  }
  return 'file';
});
const placeholder = computed(() => {
  if (isMultiImageResource.value) {
    return '上传或选择后自动追加图片地址';
  }
  if (isImageResource.value) {
    return '上传或选择后自动写入图片地址';
  }
  if (isVideoResource.value) {
    return '上传或选择后自动写入视频地址';
  }
  return '上传或选择后自动写入文件地址';
});
const accept = computed(() => {
  if (isImageResource.value || isMultiImageResource.value) {
    return 'image/*';
  }
  if (isVideoResource.value) {
    return 'video/*';
  }
  return '.doc,.docx,.xls,.xlsx,.ppt,.pptx,.pdf,.rar,.zip,.apk,.txt';
});
</script>

<style scoped>
.cms-resource-upload {
  display: grid;
  gap: 10px;
  min-width: 0;
}
</style>
