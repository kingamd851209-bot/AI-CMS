<template>
  <el-card shadow="never" aria-labelledby="page-list-supplement-heading">
    <template #header><strong id="page-list-supplement-heading">增补单页列表</strong></template>
    <p>{{ sourcePath }} · {{ ownerLabel }} #{{ ownerMenuId }}</p>
    <el-button :loading="loadingEvidence" :disabled="blocked" @click="readEvidence">
      {{ page ? '重新读取重复区域' : '读取原页重复区域' }}
    </el-button>
    <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" />
    <p v-if="page && !regions.length" role="status">此页没有可增补的未审核重复区域。</p>
    <el-form v-else-if="page" label-position="top" class="page-list-form">
      <el-form-item label="原页重复区域">
        <el-select v-model="regionId" aria-label="原页重复区域" filterable :disabled="blocked">
          <el-option v-for="(region, index) in regions" :key="region.id" :value="region.id"
            :label="`候选 ${index + 1} · ${region.records.length} 条 · ${regionExample(region)}`" />
        </el-select>
      </el-form-item>
      <template v-if="selected">
        <p class="page-list-selector">{{ selected.selector }}</p>
        <ul v-if="selected.warnings.length"><li v-for="warning in selected.warnings" :key="warning">{{ warning }}</li></ul>
        <el-form-item label="列表内容类型名称">
          <el-input v-model="modelName" aria-label="列表内容类型名称" :disabled="blocked" maxlength="80" />
          <p v-if="duplicateModelName" role="status">该名称已用于其他内容模型，请输入不同名称。</p>
        </el-form-item>
        <el-form-item label="维护栏目">
          <el-select v-model="destination" aria-label="维护栏目" placeholder="选择直属列表子栏目" :disabled="blocked" filterable>
            <el-option v-for="menu in menus" :key="menu.id" :value="Number(menu.id)"
              :label="`${menu.className || menu.name} #${menu.id}`" />
          </el-select>
        </el-form-item>
        <el-button size="small" link :disabled="blocked" @click="emit('refresh-menus')">重新读取可用栏目</el-button>
        <p v-if="!menus.length">此单页尚无可用的直属列表子栏目。创建后重新读取栏目即可选择。</p>
        <template v-if="!existingOwner">
          <p v-if="ownerConflict" role="status">现有主体模型与此页证据不一致，请在整站结构核对原归属。</p>
          <details v-else open>
            <summary>补充主体单页的原文对应</summary>
            <p>至少为一个已启用的字段核对同页原文。已有单页值若只对应一处原文，会预填该位置。</p>
            <el-form-item v-for="field in ownerFields" :key="field.fieldName" :label="field.title || field.fromName || field.fieldName">
              <el-select :model-value="ownerBindings[field.fieldName]" :aria-label="`主体字段 ${field.title || field.fromName || field.fieldName}`"
                filterable clearable :disabled="blocked" @update:model-value="setOwnerBinding(field.fieldName, $event)">
                <el-option v-for="value in ownerValues(field)" :key="value.id" :value="value.id"
                  :label="`${value.rawValue.slice(0, 85)} · ${value.selector}`"
                  :disabled="Object.entries(ownerBindings).some(([key, id]) => key !== field.fieldName && id === value.id)" />
              </el-select>
              <code v-if="ownerLocation(field)" class="page-list-selector">{{ ownerLocation(field) }}</code>
            </el-form-item>
          </details>
        </template>
        <div class="page-list-fields">
          <el-form-item v-for="field in fields" :key="field.key" :label="field.key === 'title' ? '原生标题字段' : '自定义字段'">
            <el-input v-model="field.name" :aria-label="`字段名称 ${field.key}`" maxlength="80" :disabled="blocked" />
            <el-select v-if="field.key !== 'title'" :model-value="field.type" aria-label="自定义字段类型"
              :disabled="blocked" @update:model-value="changeType(field, $event)">
              <el-option v-for="(label, type) in typeLabels" :key="type" :label="label" :value="type" />
            </el-select>
            <el-button v-if="field.key !== 'title'" link :disabled="blocked" @click="removeField(field.key)">移除字段</el-button>
          </el-form-item>
        </div>
        <el-button size="small" :disabled="blocked || fields.length >= 8" @click="addField">添加字段</el-button>
        <el-button v-if="alignedTitles" size="small" :disabled="blocked" @click="useAlignedTitles">按第一条的位置映射全部标题</el-button>
        <p>记录按原页顺序核对。每条都需要选择标题来源；其他字段可留待逐条补充。</p>
        <div class="page-list-records">
          <details v-for="(row, index) in selected.records" :key="row.id" :open="index === 0">
            <summary>原页第 {{ row.order }} 条 {{ recordTitle(row) }}</summary>
            <p class="page-list-selector">{{ row.selector }}</p>
            <p v-for="warning in row.warnings" :key="warning">{{ warning }}</p>
            <el-form-item v-for="field in fields" :key="field.key" :label="field.name || '未命名字段'">
              <el-select :model-value="bindings[row.id]?.[field.key]"
                :aria-label="`第 ${row.order} 条 · ${field.name || '未命名字段'}`"
                :disabled="blocked" clearable filterable
                @update:model-value="setBinding(row.id, field.key, $event)">
                <el-option v-for="value in field.key === 'title' ? titles(row) : choices(row, field.type)" :key="value.id" :value="value.id"
                  :label="value.rawValue" :disabled="mappedToOtherField(row.id, field.key, value.id)" />
              </el-select>
            </el-form-item>
          </details>
        </div>
        <el-button type="primary" :disabled="blocked || !selection" @click="previewChange">
          预览模型增补
        </el-button>
        <section v-if="review" aria-label="模型增补预览" class="page-list-preview" tabindex="-1" ref="previewSection">
          <h3>模型增补预览</h3>
          <p>新增「{{ review.model.name }}」至维护栏目 #{{ review.model.menuId }}；
            {{ review.region.records.length }} 条原页记录按原生排序字段展示。</p>
          <template v-if="review.owner">
            <h4>主体栏目 #{{ ownerMenuId }} 的字段与原文对应</h4>
            <el-table :data="review.owner.rows" size="small" max-height="260">
              <el-table-column label="字段" prop="label" min-width="120" />
              <el-table-column label="原文" prop="text" min-width="180" />
              <el-table-column label="位置" prop="selector" min-width="180" />
            </el-table>
          </template>
          <el-table :data="review.rows" size="small" max-height="380" empty-text="暂无记录">
            <el-table-column label="顺序" prop="order" width="80" />
            <el-table-column v-for="field in review.model.fields.filter(field => field.type !== 'Sort')"
              :key="field.key" :label="field.name" min-width="140">
              <template #default="{ row }">{{ row.values[field.key] || '待维护' }}</template>
            </el-table-column>
          </el-table>
          <el-button type="primary" :loading="saving" :disabled="blocked" @click="saveReview">确认保存结构增补</el-button>
        </section>
      </template>
    </el-form>
  </el-card>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue';
import { ElMessageBox } from 'element-plus';
import { siteModelApi, type SiteContentModel, type SiteEvidenceRegion, type SiteEvidenceValue, type SiteModelPlan, type SiteModelRegion, type SitePageEvidence } from '@/api/siteModel';
import { http } from '@/api/http';
import { getMenuFieldConfig, type FieldOption, type MenuNode } from '@/api/uiPackage';

const props = defineProps<{
  siteId: number;
  sourcePath: string;
  plan: SiteModelPlan;
  ownerMenuId: number;
  ownerLabel: string;
  menus: MenuNode[];
  disabled: boolean;
}>();
const emit = defineEmits<{ busy: [boolean]; saved: [SiteModelPlan, string, number]; 'refresh-menus': [] }>();
const typeLabels = { SingleLine: '单行文本', MultiLine: '多行文本', Image: '图片' };
const typeHints = { SingleLine: ['TEXT', 'LINK'], MultiLine: ['TEXT'], Image: ['IMAGE'] };
type FieldType = keyof typeof typeLabels;
type Field = { key: string; name: string; type: FieldType };
type EvidenceRecord = SiteEvidenceRegion['records'][number];

const page = ref<SitePageEvidence | null>(null);
const error = ref('');
const loadingEvidence = ref(false);
const saving = ref(false);
const blocked = computed(() => props.disabled || loadingEvidence.value || saving.value);
watch(saving, value => emit('busy', value));
let present = true;
onBeforeUnmount(() => {
  present = false;
  if (saving.value) emit('busy', false);
});
const regionId = ref('');
const modelName = ref('');
const destination = ref<number>();
let suggestedModelName = '';
watch(() => props.menus, menus => {
  if (menus.some(menu => Number(menu.id) === destination.value)) return;
  destination.value = menus.length === 1 ? menus[0].id : undefined;
}, { immediate: true });
watch(destination, id => {
  const menu = props.menus.find(item => Number(item.id) === id);
  const name = (menu?.className || menu?.name || '').trim();
  if (!modelName.value.trim() || modelName.value === suggestedModelName)
    modelName.value = name.length <= 80 && !usesModelName(name) ? name : '';
  suggestedModelName = name;
}, { immediate: true });
function usesModelName(name: string) {
  return props.plan.proposal.models.some(model => model.name.toLowerCase() === name.toLowerCase());
}
const duplicateModelName = computed(() => usesModelName(modelName.value.trim()));
const fields = ref<Field[]>([{ key: 'title', name: '标题', type: 'SingleLine' }]);
let fieldIndex = 0;
const bindings = ref<Record<string, Record<string, string>>>({});
const ownerFields = ref<(FieldOption & { fieldType: FieldType })[]>([]);
const ownerBindings = ref<Record<string, string>>({});
const ownerCurrentValues = ref<Record<string, unknown>>({});
const previewSection = ref<HTMLElement>();
const ownerRegion = computed(() => page.value?.regions.find(region => region.kind === 'PAGE_SOURCE' && region.records.length === 1));
const ownerValues = (field: FieldOption & { fieldType: FieldType }) => {
  const record = ownerRegion.value?.records[0];
  return record ? choices(record, field.fieldType).filter(value => !selected.value?.records.some(row =>
    value.selector === row.selector || value.selector.startsWith(row.selector + ' > '))) : [];
};
const ownerLocation = (field: FieldOption & { fieldType: FieldType }) =>
  ownerValues(field).find(value => value.id === ownerBindings.value[field.fieldName])?.selector;
function setOwnerBinding(key: string, value?: string) {
  if (value) ownerBindings.value[key] = value;
  else delete ownerBindings.value[key];
}
const existingOwner = computed(() => {
  const regions = props.plan.proposal.regions.filter(region => region.sourcePath === props.sourcePath
    && region.mode === 'SINGLE' && region.records.length === 1);
  if (regions.length !== 1) return undefined;
  return props.plan.proposal.models.find(model => model.key === regions[0].modelKey
    && model.kind === 'SINGLE' && model.menuId === props.ownerMenuId);
});
const ownerConflict = computed(() => !existingOwner.value && props.plan.proposal.models.some(model => model.menuId === props.ownerMenuId));
const choices = (row: EvidenceRecord, type: FieldType) =>
  row.values.filter(value => value.rawValue.trim() && typeHints[type].includes(value.typeHint));
const titles = (row: EvidenceRecord) => choices(row, 'SingleLine').filter(value => value.typeHint === 'TEXT');
const descriptiveTitles = (row: EvidenceRecord) => titles(row).filter(value => !/^\d+[.、]?$/.test(value.rawValue.trim()));
function regionExample(region: SiteEvidenceRegion) {
  return descriptiveTitles(region.records[0])[0]?.rawValue.slice(0, 50)
    || region.selector;
}
const usedRegions = computed(() => new Set(props.plan.proposal.regions
  .filter(region => region.sourcePath === props.sourcePath).map(region => region.regionId)));
const regions = computed(() => page.value?.regions.filter(region =>
  region.kind === 'REPEATED' && !usedRegions.value.has(region.id) && region.records.length >= 2
  && region.records.every(row => titles(row).length)) || []);
const selected = computed(() => regions.value.find(region => region.id === regionId.value));
function relativePosition(row: EvidenceRecord, value: SiteEvidenceValue) {
  if (value.selector === row.selector || value.selector.startsWith(row.selector + ' > '))
    return value.selector.slice(row.selector.length);
  return null;
}
const alignedTitles = computed(() => {
  const first = selected.value?.records[0];
  const title = first && titles(first).find(value => value.id === bindings.value[first.id]?.title);
  if (!selected.value || !first || !title) return null;
  const position = relativePosition(first, title);
  if (position === null) return null;
  const entries = selected.value.records.map(row => [row.id, titles(row).filter(value =>
    value.selector === row.selector + position && value.attribute === title.attribute)] as const);
  return entries.every(([, values]) => values.length === 1) ? entries : null;
});
function useAlignedTitles() {
  for (const [rowId, values] of alignedTitles.value || []) {
    for (const [key, value] of Object.entries(bindings.value[rowId] || {}))
      if (key !== 'title' && value === values[0].id) setBinding(rowId, key);
    setBinding(rowId, 'title', values[0].id);
  }
}
watch(selected, region => {
  const records = region?.records || [];
  const repeatedTitles = records.map(descriptiveTitles);
  const firstTitle = repeatedTitles[0]?.[0];
  const position = firstTitle ? relativePosition(records[0], firstTitle) : null;
  const useRepeatedTitles = firstTitle && position !== null && repeatedTitles.every((values, index) =>
    values.length === 1 && values[0].attribute === firstTitle.attribute && relativePosition(records[index], values[0]) === position);
  bindings.value = Object.fromEntries(records.map((row, index) => {
    const rowTitles = titles(row);
    const mapped: Record<string, string> = {};
    if (rowTitles.length === 1) mapped.title = rowTitles[0].id;
    else if (useRepeatedTitles) mapped.title = repeatedTitles[index][0].id;
    return [row.id, mapped];
  }));
});
watch([ownerFields, ownerCurrentValues], () => {
  const candidates = ownerFields.value.flatMap(field => {
    const current = ownerCurrentValues.value[field.fieldName];
    if (typeof current !== 'string' || !current.trim()) return [];
    const values = ownerValues(field).filter(value => value.rawValue.trim() === current.trim());
    return values.length === 1 ? [[field.fieldName, values[0].id] as const] : [];
  });
  ownerBindings.value = Object.fromEntries(candidates.filter(([, id]) =>
    candidates.filter(([, other]) => other === id).length === 1));
});
watch(selected, () => {
  for (const field of ownerFields.value) {
    const id = ownerBindings.value[field.fieldName];
    if (id && !ownerValues(field).some(value => value.id === id)) setOwnerBinding(field.fieldName);
  }
});
function recordTitle(row: EvidenceRecord) {
  return row.values.find(value => value.id === bindings.value[row.id]?.title)?.rawValue.slice(0, 50) || '· 待选标题';
}
function mappedToOtherField(rowId: string, key: string, valueId: string) {
  return Object.entries(bindings.value[rowId] || {}).some(([field, id]) => key !== field && id === valueId);
}
function setBinding(rowId: string, key: string, valueId?: string) {
  const row = bindings.value[rowId];
  if (valueId) row[key] = valueId;
  else delete row[key];
}
function addField() {
  fields.value.push({ key: `field-${++fieldIndex}`, name: '', type: 'SingleLine' });
}
function removeField(key: string) {
  fields.value = fields.value.filter(field => field.key !== key);
  for (const rowId of Object.keys(bindings.value)) setBinding(rowId, key);
}
function changeType(field: Field, type: FieldType) {
  field.type = type;
  for (const rowId of Object.keys(bindings.value)) setBinding(rowId, field.key);
}
const selection = computed(() => {
  const region = selected.value;
  const name = modelName.value.trim();
  const chosen = fields.value.map(field => ({ ...field, name: field.name.trim() }));
  if (!region || !name || chosen.some(field => !field.name) || !region.records.every(row => bindings.value[row.id]?.title)
      || new Set(chosen.map(field => field.name)).size !== chosen.length) return null;
  if (!destination.value || !props.menus.some(menu => Number(menu.id) === destination.value)) return null;
  const ownerMappedFields = ownerFields.value.filter(field => ownerValues(field).some(value => value.id === ownerBindings.value[field.fieldName]));
  if (!existingOwner.value && (!ownerMappedFields.length || !ownerRegion.value || ownerConflict.value)) return null;
  if (duplicateModelName.value) return null;
  return { region, name, fields: chosen, menuId: destination.value, ownerMappedFields };
});
type Review = {
  model: SiteContentModel;
  region: SiteModelRegion;
  owner?: { model: SiteContentModel; region: SiteModelRegion; rows: { label: string; text: string; selector: string }[] };
  rows: { order: number; values: Record<string, string> }[];
};
const review = ref<Review | null>(null);
watch([selection, bindings, ownerBindings], () => { review.value = null; }, { deep: true });
async function readEvidence() {
  if (blocked.value) return;
  loadingEvidence.value = true;
  error.value = '';
  try {
    const source = await siteModelApi.evidence(props.siteId, props.plan.packageVersion, props.sourcePath);
    if (source.sourcePath !== props.sourcePath) throw new Error('返回证据与当前页面不一致。');
    ownerFields.value = [];
    ownerCurrentValues.value = {};
    page.value = source;
    regionId.value = regions.value.length === 1 ? regions.value[0].id : '';
    ownerBindings.value = {};
    if (!existingOwner.value) {
      const [config, current] = await Promise.all([
        getMenuFieldConfig(props.ownerMenuId, 1),
        http.get<{ content?: { menuId: number; values: Record<string, unknown> } }>(`/api/admin/v2/cms/single/${props.ownerMenuId}`)
          .catch(() => undefined),
      ]);
      if (config.menu.id !== props.ownerMenuId || config.menu.pageType !== 0) throw new Error('栏目已变化，请重新读取单页。');
      ownerFields.value = config.fieldOptions.filter((field): field is FieldOption & {fieldType: FieldType} =>
        Object.hasOwn(typeHints, field.fieldType) && ['on', 'true', '1'].includes(String(field.isOpen).toLowerCase()));
      if (current?.content?.menuId === props.ownerMenuId) ownerCurrentValues.value = current.content.values;
    }
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '读取原页重复区域失败';
  } finally {
    loadingEvidence.value = false;
  }
}
async function previewChange() {
  const choice = selection.value;
  if (blocked.value || !choice) return;
  const key = `page-list-${choice.menuId}`;
  const model: SiteContentModel = {
    key, name: choice.name, kind: 'LIST', menuId: choice.menuId,
    fields: [...choice.fields.map(field => ({ ...field, fieldName: field.key === 'title' ? 'title' : null })),
      { key: 'sort', name: '排序', type: 'Sort', fieldName: 'sort' }],
  };
  const singleKey = existingOwner.value?.key || `page-owner-${props.ownerMenuId}`;
  const region: SiteModelRegion = {
    sourcePath: props.sourcePath, regionId: choice.region.id, modelKey: key, mode: 'LIST',
    records: choice.region.records.map(row => ({ recordId: row.id, bindings: { ...bindings.value[row.id] } })),
    maintenance: { mode: 'PAGE_LIST', ownerModelKey: singleKey, fieldKey: null, reason: '原单页同一区域的条目随专用列表子栏目分别维护' },
  };
  const source = ownerRegion.value;
  const mappedOwnerFields = choice.ownerMappedFields.map((field, index) => ({
    key: `existing-field-${index + 1}`, name: field.title || field.fromName || field.fieldName,
    type: field.fieldType, fieldName: field.fieldName,
  }));
  const owner = !existingOwner.value && source ? {
    model: {
      key: singleKey, name: `${props.ownerLabel}（单页主体）`, kind: 'SINGLE', menuId: props.ownerMenuId,
      fields: mappedOwnerFields,
    },
    region: {
      sourcePath: props.sourcePath, regionId: source.id, modelKey: singleKey, mode: 'SINGLE',
      records: [{ recordId: source.records[0].id, bindings: Object.fromEntries(
        mappedOwnerFields.map(field => [field.key, ownerBindings.value[field.fieldName]])) }],
    },
    rows: mappedOwnerFields.flatMap(field => {
      const value = source.records[0].values.find(item => item.id === ownerBindings.value[field.fieldName]);
      return value ? [{ label: field.name, text: value.rawValue, selector: value.selector }] : [];
    }),
  } : undefined;
  review.value = { model, region, owner, rows: choice.region.records.map(row => ({
    order: row.order, values: Object.fromEntries(row.values.filter(value =>
      Object.values(bindings.value[row.id]).includes(value.id)).map(value => [
      Object.keys(bindings.value[row.id]).find(field => bindings.value[row.id][field] === value.id)!, value.rawValue,
    ])),
  })) };
  await nextTick();
  previewSection.value?.focus();
}
async function saveReview() {
  const candidate = review.value;
  if (!candidate || blocked.value) return;
  try {
    await ElMessageBox.confirm('确定将预览所示模型和原文记录对应增补到已确认的站点结构？', '确认增补单页列表',
      { type: 'warning', confirmButtonText: '确认增补', cancelButtonText: '取消' });
  } catch { return; }
  if (!present || blocked.value || candidate !== review.value) return;
  saving.value = true;
  error.value = '';
  try {
    const base = props.plan;
    const proposal = {
      ...base.proposal,
      models: [...base.proposal.models, ...(candidate.owner ? [candidate.owner.model] : []), candidate.model],
      regions: [...base.proposal.regions, ...(candidate.owner ? [candidate.owner.region] : []), candidate.region],
    };
    const result = await siteModelApi.review(props.siteId, base.id, {
      packageVersion: base.packageVersion, revision: base.revision, confirm: true, proposal,
    });
    if (present) emit('saved', result, candidate.model.name, candidate.model.menuId!);
  } catch (cause) {
    if (present) error.value = cause instanceof Error ? cause.message : '保存结构增补失败，请重新读取计划。';
  } finally {
    saving.value = false;
  }
}
</script>

<style scoped>
.page-list-form { margin-top: var(--admin-space-3); }
.page-list-form .el-select { width: 100%; max-width: 48rem; }
.page-list-fields { display: grid; gap: var(--admin-space-2); grid-template-columns: repeat(auto-fit, minmax(min(100%, 15rem), 1fr)); }
.page-list-selector { overflow-wrap: anywhere; }
.page-list-records { max-height: 32rem; overflow: auto; margin-bottom: var(--admin-space-3); }
.page-list-records details { padding: var(--admin-space-2); border-bottom: var(--admin-card-border); }
.page-list-records summary { cursor: pointer; padding-block: var(--admin-space-2); overflow-wrap: anywhere; }
.page-list-preview { margin-top: var(--admin-space-3); }
.page-list-preview .el-button { margin-top: var(--admin-space-3); }
</style>
