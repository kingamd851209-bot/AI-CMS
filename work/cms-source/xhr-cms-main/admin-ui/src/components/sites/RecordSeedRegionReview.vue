<template>
  <div class="seed-region-review">
    <p v-if="!groups.length" role="status">此页面没有与栏目字段相容的未审核重复区域。</p>
    <template v-else>
      <label>重复区域
        <el-select v-model="regionId" aria-label="待审核重复区域" placeholder="选择一处原页位置" filterable :disabled="disabled">
          <el-option-group v-for="(group, index) in groups" :key="group[0].id"
            :label="`候选 ${index + 1} · ${group[0].records.length} 条${group.length > 1 ? ` · ${group.length} 处原值相同` : ''}`">
            <el-option v-for="(region, position) in group" :key="region.id" :value="region.id"
              :label="`候选 ${index + 1} · 位置 ${position + 1}：${region.selector}`" />
          </el-option-group>
        </el-select>
      </label>
      <template v-if="selected">
        <p v-if="selectedGroup.length > 1" role="status">
          这 {{ selectedGroup.length }} 处区域的原始记录与顺序完全相同。请在原页核对后，只选一处作为初值来源。
        </p>
        <p>选定来源 <code>{{ page.sourcePath }} · {{ selected.selector }}</code></p>
        <ul v-if="selected.warnings.length">
          <li v-for="warning in selected.warnings" :key="warning">{{ warning }}</li>
        </ul>
        <el-table :data="selected.records" size="small" max-height="480">
          <el-table-column label="来源记录" min-width="320">
            <template #default="{ row }">
              <details>
                <summary>原页第 {{ row.order }} 条 · 来源位置</summary>
                <code>{{ row.selector }}</code>
              </details>
              <div v-for="value in row.values" :key="value.id" :title="`${value.selector} · ${value.attribute}`">
                <RecordSeedPhoto v-if="value.typeHint === 'IMAGE'" :site-id="siteId" :source-path="page.sourcePath" :raw="value.rawValue" label="原页图片" />
                <span>{{ value.rawValue }}</span>
              </div>
              <p v-for="warning in row.warnings" :key="warning">{{ warning }}</p>
            </template>
          </el-table-column>
          <el-table-column label="栏目字段映射" min-width="340">
            <template #default="{ row }">
              <label v-for="field in fields" :key="field.key">
                {{ field.name }}
                <el-select :model-value="bindings[row.id]?.[field.key]" :aria-label="`第 ${row.order} 条 · ${field.name}`"
                  clearable :disabled="disabled" placeholder="留空，待逐条维护"
                  @update:model-value="setBinding(row.id, field.key, $event)">
                  <el-option v-for="value in choices(row, field)" :key="value.id" :value="value.id"
                    :label="value.rawValue" :disabled="Object.entries(bindings[row.id] || {}).some(([key, id]) => key !== field.key && id === value.id)" />
                </el-select>
              </label>
            </template>
          </el-table-column>
        </el-table>
        <el-button type="primary" :disabled="disabled || !regionDraft" @click="regionDraft && emit('review', regionDraft)">
          审核并确认这处重复区域
        </el-button>
      </template>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import type { SiteContentModel, SiteEvidenceRegion, SiteModelField, SiteModelRegion, SitePageEvidence } from '@/api/siteModel';
import RecordSeedPhoto from './RecordSeedPhoto.vue';

const props = defineProps<{ siteId:number; page: SitePageEvidence; model: SiteContentModel; usedRegionIds: string[]; disabled: boolean }>();
const emit = defineEmits<{ review: [SiteModelRegion] }>();
type EvidenceRecord = SiteEvidenceRegion['records'][number];
const supported: Record<string, string[]> = { SingleLine: ['TEXT', 'LINK'], MultiLine: ['TEXT'], Image: ['IMAGE'], File: ['FILE'], DateTime: ['DATE', 'DATETIME'] };
const fields = computed(() => props.model.fields.filter(field => field.fieldName && supported[field.type]));
const choices = (row: EvidenceRecord, field: SiteModelField) =>
  (row.values || []).filter(value => value.rawValue.trim() && supported[field.type].includes(value.typeHint));

const groups = computed(() => {
  const groupsByValues = new Map<string, SiteEvidenceRegion[]>();

  for (const region of props.page.regions) {
    if (props.usedRegionIds.includes(region.id) || region.kind !== 'REPEATED' || region.records.length < 2 ||
        !region.records.every(row => fields.value.some(field => choices(row, field).length))) continue;
    // A navigation with year labels alone is not an illustrated record region.
    if (region.records.every(row => row.values.length === 1 && row.values[0]?.typeHint === 'TEXT' && /^\d{4}年?$/.test(row.values[0].rawValue.trim()))) continue;
    const key = JSON.stringify(region.records.map(row => row.values.map(value => [value.attribute, value.typeHint, value.rawValue])));
    const group = groupsByValues.get(key);
    if (group) group.push(region);
    else groupsByValues.set(key, [region]);
  }
  return [...groupsByValues.values()];
});
const regionId = ref('');
const selectedGroup = computed(() => groups.value.find(group => group.some(region => region.id === regionId.value)) || []);
const selected = computed(() => selectedGroup.value.find(region => region.id === regionId.value));
const bindings = ref<Record<string, Record<string, string>>>({});
watch(selected, region => {
  bindings.value = {};
  for (const row of region?.records || []) {
    const proposals = fields.value
      .filter(field => !/年份|年度/.test(field.name))
      .map(field => ({ field, values: choices(row, field) }))
      .filter(item => item.values.length === 1);
    const rowBindings: Record<string, string> = {};
    for (const { field, values: [value] } of proposals) {
      const competing = proposals.filter(item => item.values[0].id === value.id);
      if (competing.length === 1 || (value.typeHint === 'TEXT' && field.fieldName === 'title')) rowBindings[field.key] = value.id;
    }
    bindings.value[row.id] = rowBindings;
  }
});
function setBinding(rowId: string, fieldKey: string, value: string) {
  if (value) bindings.value[rowId][fieldKey] = value;
  else delete bindings.value[rowId][fieldKey];
}
const regionDraft = computed<SiteModelRegion | null>(() => {
  const records = selected.value?.records.map(row => ({ recordId: row.id, bindings: { ...bindings.value[row.id] } }));
  if (!selected.value || !records?.every(row => Object.keys(row.bindings).length)) return null;
  return { sourcePath: props.page.sourcePath, regionId: selected.value.id, modelKey: props.model.key, mode: 'LIST', records };
});

</script>

<style scoped>
.seed-region-review > label { display: block; max-width: 680px; }
.seed-region-review label .el-select { width: 100%; margin-bottom: var(--admin-space-2); }
.seed-region-review code, .seed-region-review span { overflow-wrap: anywhere; }
.seed-region-review > .el-button { margin-top: var(--admin-space-3); }
</style>
