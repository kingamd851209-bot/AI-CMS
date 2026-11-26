<template>
  <div class="content-reference-field" :data-testid="`reference-field-${field.fieldName}`">
    <el-select
      :model-value="selection"
      :multiple="multiple"
      :loading="loading"
      clearable filterable collapse-tags collapse-tags-tooltip
      placeholder="请选择关联内容"
      style="width: 100%"
      :aria-label="label || '关联内容'"
      :data-testid="controlTestId || `reference-control-${field.fieldName}`"
      @update:model-value="change"
    >
      <el-option v-for="option in options" :key="option.value" :value="option.value" :label="optionLabels.get(option.value)" />
    </el-select>
    <ol v-if="multiple && ids.length > 1" aria-label="引用展示顺序">
      <li v-for="(id, index) in ids" :key="id">
        {{ optionLabels.get(id) || id }}
        <el-button size="small" link :disabled="!active || index === 0" @click="move(index, -1)">上移</el-button>
        <el-button size="small" link :disabled="!active || index === ids.length - 1" @click="move(index, 1)">下移</el-button>
      </li>
    </ol>
    <p v-if="error" role="status" class="content-reference-field__hint">{{ error }}</p>
    <p v-else-if="!loading && hasUnavailable" role="status" class="content-reference-field__hint">部分当前引用不可选，可移除或重新选择；不会自动丢弃已有引用。</p>
    <el-button size="small" text :loading="loading" :disabled="loading || !active" @click="load">重新读取可选内容</el-button>
  </div>
</template>
<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { fetchNativeSelectOptions, type SelectOptionItem } from '@/api/cms';
import { useSiteContextStore } from '@/stores/siteContext';
import { referenceIds, referenceOptions, updateReferenceValue, type ContentReferenceField } from './contentReference';
const props = withDefaults(defineProps<{field: ContentReferenceField; modelValue: unknown; menuId?: number | null; active?: boolean; recordKey?: number | string | null; label?: string; controlTestId?: string}>(), {active: true});
const emit = defineEmits<{'update:modelValue': [value: string]}>();
const site = useSiteContextStore();
const loading = ref(false), error = ref('');
const available = ref<SelectOptionItem[]>([]), removed = ref<Set<string>>(new Set());
const multiple = computed(() => props.field.inputMultiple === true);
const ids = computed(() => referenceIds(props.modelValue, multiple.value));
const selection = computed(() => multiple.value ? ids.value : ids.value[0] || '');
const options = computed(() => referenceOptions(available.value, ids.value, removed.value, loading.value));
const optionLabels = computed(() => {
  const labels = new Map<string, string>();
  const counts = new Map<string, number>();
  for (const option of options.value) {
    const text = option.group ? `${option.group} · ${option.label}` : option.label;
    labels.set(option.value, text);
    counts.set(text, (counts.get(text) ?? 0) + 1);
  }
  for (const [id, text] of labels) {
    if ((counts.get(text) ?? 0) > 1) labels.set(id, `${text}（ID ${id}）`);
  }
  return labels;
});
const hasUnavailable = computed(() => options.value.some(option => option.unavailable));
const query = computed(() => props.field.selectQuery || props.field.defaultValue || '');
let sequence = 0;
async function load() {
  const current = ++sequence;
  available.value = []; error.value = ''; loading.value = false;
  if (!props.active) return;
  if (!props.menuId || !/^\{\$[1-9][0-9]*(?:,[1-9][0-9]*)*_title_id\$\}$/.test(query.value)) {
    error.value = '关联来源配置不完整，当前引用仍可移除。'; return;
  }
  loading.value = true;
  try {
    const result = await fetchNativeSelectOptions(props.menuId, query.value);
    if (current === sequence) available.value = result;
  } catch {
    if (current === sequence) error.value = '当前无法读取可选内容，已有引用仍可移除；可稍后重试。';
  } finally { if (current === sequence) loading.value = false; }
}
function change(value: unknown) {
  const next = updateReferenceValue(value, props.modelValue, available.value, multiple.value, removed.value);
  removed.value = next.removed;
  emit('update:modelValue', next.value);
}
function move(index: number, direction: -1 | 1) {
  const next = [...ids.value];
  const target = index + direction;
  if (!props.active || target < 0 || target >= next.length) return;
  [next[index], next[target]] = [next[target]!, next[index]!];
  change(next);
}
watch(() => [props.menuId, site.currentSite?.id, query.value, props.active, props.recordKey], () => { removed.value = new Set(); void load(); }, {immediate: true});
onBeforeUnmount(() => { sequence++; });
</script>
<style scoped>
.content-reference-field{width:100%;min-width:0}.content-reference-field__hint{margin:6px 0;color:var(--el-text-color-secondary);font-size:12px;line-height:1.5}
</style>
