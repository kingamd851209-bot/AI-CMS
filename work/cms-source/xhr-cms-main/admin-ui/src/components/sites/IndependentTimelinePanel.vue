<script setup lang="ts">
import {computed, onBeforeUnmount, ref, watch} from 'vue';
import {ElMessageBox} from 'element-plus';
import PublicListApplicationPanel from '@/components/sites/PublicListApplicationPanel.vue';
import {listNativeContentGroups, type NativeContentGroup} from '@/api/cms';
import {compileQueryBindings, confirmQueryBindings, queryTimelineEvidence, readQueryBindings,
  type CompiledQuery, type QuerySnapshot, type QueryTimelineEvidence} from '@/api/templateQueryBinding';
import {useSiteContextStore} from '@/stores/siteContext';

const props = defineProps<{siteId:number; menuId:number; sourcePath:string; packageVersion:string; sourcePlanId?:string; hasListRoute:boolean; disabled:boolean}>();
const emit = defineEmits<{busy:[boolean]; changed:[]; fields:[]; routes:[]}>();
const siteContext = useSiteContextStore();
const evidence = ref<QueryTimelineEvidence | null>(null);
const layoutIndex = ref<number>();
const groupField = ref('');
const rowField = ref('');
const secondaryField = ref('');
const imageField = ref<string>();
const ascending = ref(false);
const selectedGroup = ref<string>();
const groups = ref<NativeContentGroup[]>([]);
const groupReadError = ref(false);
const knownId = ref('');
const snapshot = ref<QuerySnapshot | null>(null);
const compiled = ref<CompiledQuery | null>(null);
const error = ref('');
const loading = ref(false);
const publicBusy = ref(false);
const blocked = computed(() => props.disabled || loading.value || publicBusy.value);
const layout = computed(() => layoutIndex.value === undefined ? undefined : evidence.value?.layouts[layoutIndex.value]);
const groupFields = computed(() => evidence.value?.fields.filter(field => field.fieldType.toLowerCase() === 'singleline') || []);
const textFields = computed(() => evidence.value?.fields.filter(field => ['singleline','multiline'].includes(field.fieldType.toLowerCase())) || []);
const imageFields = computed(() => evidence.value?.fields.filter(field => field.fieldType.toLowerCase() === 'image') || []);
const validFields = computed(() => {
  if (!layout.value || !groupFields.value.some(field => field.fieldName === groupField.value)
      || !textFields.value.some(field => field.fieldName === rowField.value)
      || groupField.value === rowField.value) return false;
  return !layout.value.rowSecondarySelector || (
    secondaryField.value !== groupField.value && secondaryField.value !== rowField.value
    && textFields.value.some(field => field.fieldName === secondaryField.value)
  );
});
const savedTimeline = computed(() => snapshot.value?.presentation?.request.timeline);
const maintenanceField = computed(() => savedTimeline.value?.groupField || groupField.value);
const groupedCards = computed(() => Boolean(savedTimeline.value
  ? savedTimeline.value.rowImageSelector || !savedTimeline.value.groupTitleSelector
  : layout.value && (layout.value.rowImageSelector || !layout.value.groupTitleSelector)));
const groupLabel = computed(() => groupedCards.value ? '分组' : '年份');
const rowLabel = computed(() => groupedCards.value ? '记录' : '事件');
const canManage = computed(() => siteContext.hasMenuPermission(props.menuId));
const groupOptions = computed(() => {
  const counts = new Map(groups.value.map(group => [group.value, group.count]));
  return [...new Set([...counts.keys(), ...(savedTimeline.value ? [] : layout.value?.labels || [])])]
    .map(value => ({value, count:counts.get(value)}));
});
const storageKey = computed(() => `cms.workbench.timeline.v1:${JSON.stringify([
  siteContext.currentAdmin?.id, props.siteId, props.menuId, props.sourcePath, props.packageVersion,
])}`);
let active = true;

watch(() => loading.value || publicBusy.value, value => emit('busy', value), {flush:'sync'});
watch(layoutIndex, () => { imageField.value = undefined; secondaryField.value = ''; });
watch([layoutIndex, groupField, rowField, secondaryField, imageField, ascending], () => {
  snapshot.value = null;
  compiled.value = null;
});
watch([maintenanceField, canManage, groupedCards], async ([field, allowed], _old, onCleanup) => {
  let current = true;
  onCleanup(() => { current = false; });
  selectedGroup.value = undefined;
  groups.value = [];
  groupReadError.value = false;
  if (!field || !allowed) return;
  try {
    const values = await listNativeContentGroups(props.menuId, field);
    if (current) groups.value = values;
  } catch { if (current) groupReadError.value = true; }
});
onBeforeUnmount(() => { active = false; if (loading.value || publicBusy.value) emit('busy', false); });
try { knownId.value = localStorage.getItem(storageKey.value) || ''; } catch { /* 仍可按已知计划读取。 */ }

function remember(plan:QuerySnapshot) {
  snapshot.value = plan;
  knownId.value = plan.id;
  try { localStorage.setItem(storageKey.value, plan.id); } catch { /* 服务器已保存，可按计划编号读取。 */ }
}
async function run(action:() => Promise<void>) {
  if (blocked.value || !active) return;
  loading.value = true;
  error.value = '';
  try { await action(); }
  catch (cause) { if (active) error.value = cause instanceof Error ? cause.message : String(cause); }
  finally { if (active) loading.value = false; }
}
function resetEvidence() {
  snapshot.value = null;
  compiled.value = null;
  evidence.value = null;
  layoutIndex.value = undefined;
  groupField.value = rowField.value = secondaryField.value = '';
  imageField.value = undefined;
}
async function loadEvidence() {
  const sourcePlanId = props.sourcePlanId;
  if (!sourcePlanId) return;
  await run(async () => {
    resetEvidence();
    const value = await queryTimelineEvidence(props.siteId, props.menuId, props.sourcePath, sourcePlanId);
    if (!active) return;
    evidence.value = value;
    if (value.layouts.length === 1) layoutIndex.value = 0;
  });
}
async function save() {
  if (blocked.value || !props.sourcePlanId || !props.hasListRoute || !layout.value || !evidence.value || !validFields.value) return;
  const {labels, sourceRows, ...selectors} = layout.value;
  const {sourcePath, digest, containerSelector, fieldConfigDigest, routeDigest} = evidence.value.candidates;
  const request = {sourcePlanId: props.sourcePlanId, sourcePath, digest, containerSelector, fieldConfigDigest, routeDigest,
    menuId: props.menuId, mappings: [], confirmed:true,
    presentation:{replaceGroupConfirmed:true, timeline:{...selectors, groupField:groupField.value, rowField:rowField.value,
      secondaryField:selectors.rowSecondarySelector ? secondaryField.value : undefined,
      imageField:selectors.rowImageSelector ? imageField.value || null : undefined, ascending:ascending.value}}};
  try {
    await ElMessageBox.confirm(`已核对原页 ${labels.length} 组、${sourceRows} 个${rowLabel.value}位置。公开展示以栏目已发布记录和所选字段分组。`,
      '确认分组列表方案', {type:'warning', confirmButtonText:'确认保存', cancelButtonText:'取消'});
  } catch { return; }
  await run(async () => {
    const saved = await confirmQueryBindings(props.siteId, request);
    if (active) { compiled.value = null; remember(saved); }
  });
}
async function reopen() {
  await run(async () => {
    const saved = await readQueryBindings(props.siteId, knownId.value.trim());
    if (saved.siteId !== props.siteId || saved.menuId !== props.menuId || saved.sourcePath !== props.sourcePath ||
        saved.packageVersion !== props.packageVersion || !saved.presentation?.request.timeline)
      throw new Error('请选择当前栏目、原页和整包版本的分组列表计划。');
    if (active) { compiled.value = null; remember(saved); }
  });
}
async function compile() {
  const plan = snapshot.value;
  if (!plan) return;
  await run(async () => {
    compiled.value = null;
    const result = await compileQueryBindings(props.siteId, plan.id);
    if (active) compiled.value = result;
  });
}
</script>

<template>
  <el-card shadow="never" class="timeline-panel">
    <template #header><strong>独立分组列表</strong></template>
    <p>核对原页的分组和条目样式，选择本栏目用于分组和展示的字段。每条记录按其 CMS 字段值归组。</p>
    <div class="timeline-controls">
      <el-button v-if="sourcePlanId" type="primary" :disabled="blocked" @click="loadEvidence">检查原页分组与可用字段</el-button>
      <el-button v-else link type="primary" :disabled="blocked" @click="emit('fields')">核对整站结构中的列表来源</el-button>
    </div>
    <p v-if="!sourcePlanId" role="status">请先在整站结构中确认此栏目及原页的重复区域。</p>
    <el-alert v-if="error" type="error" :title="error" :closable="false" />
    <p v-if="evidence && !evidence.layouts.length" role="status">此原页尚未识别到可审核的导航、分组和条目布局。</p>
    <template v-if="evidence?.layouts.length">
      <el-select v-model="layoutIndex" aria-label="原页分组版式" placeholder="选择原页分组范围" :disabled="blocked">
        <el-option v-for="(item, index) in evidence.layouts" :key="index" :value="index" :label="`${item.labels.slice(0, 2).join('、')} · ${item.labels.length} 组 · ${item.sourceRows} 个${item.rowImageSelector ? '图文' : '文本'}位置`" />
      </el-select>
      <template v-if="layout">
        <p>原页分组入口 {{layout.labels.join('、')}}，共 {{layout.sourceRows}} 个{{layout.rowImageSelector ? '图文' : '文本'}}位置。公开页根据已发布记录生成分组。</p>
        <details v-if="evidence.layouts.length > 1"><summary>核对原页位置</summary>
          <p>导航 <code>{{layout.navigationSelector}}</code> · 分组 <code>{{layout.groupSelector}}</code></p>
        </details>
        <div class="timeline-controls">
          <el-select v-model="groupField" placeholder="选择分组字段" aria-label="分组字段" :disabled="blocked">
            <el-option v-for="field in groupFields" :key="field.fieldName" :value="field.fieldName" :label="`${field.name} · ${field.fieldName}`" :disabled="rowField === field.fieldName || secondaryField === field.fieldName" />
          </el-select>
          <el-select v-model="rowField" :placeholder="`选择${rowLabel}文字字段`" :aria-label="`${rowLabel}文字字段`" :disabled="blocked">
            <el-option v-for="field in textFields" :key="field.fieldName" :value="field.fieldName" :label="`${field.name} · ${field.fieldName}`" :disabled="groupField === field.fieldName || secondaryField === field.fieldName" />
          </el-select>
          <el-select v-if="layout.rowSecondarySelector" v-model="secondaryField"
            placeholder="选择附加文字字段" aria-label="附加文字字段" :disabled="blocked">
            <el-option v-for="field in textFields" :key="field.fieldName" :value="field.fieldName" :label="`${field.name} · ${field.fieldName}`" :disabled="groupField === field.fieldName || rowField === field.fieldName" />
          </el-select>
          <el-select v-if="layout.rowImageSelector" v-model="imageField" clearable
            placeholder="选择图片字段（可选）" aria-label="图片字段（可选）" :disabled="blocked || !imageFields.length">
            <el-option v-for="field in imageFields" :key="field.fieldName" :value="field.fieldName" :label="`${field.name} · ${field.fieldName}`" />
          </el-select>
          <el-switch v-model="ascending" active-text="升序" inactive-text="降序" aria-label="分组顺序" :disabled="blocked" />
        </div>
        <p v-if="layout.rowImageSelector">公开模板沿用原页的分组、条目样式和图片位置。图片字段未选或记录无有效图片时隐藏图片。</p>
        <p v-if="!hasListRoute" role="status">请配置固定的列表公开路径，再保存分组方案。</p>
        <p v-if="!groupFields.length || textFields.length < (layout.rowSecondarySelector ? 3 : 2) || layout.rowImageSelector && !imageFields.length" role="status">
          {{ !groupFields.length || textFields.length < (layout.rowSecondarySelector ? 3 : 2) ? '需要单行分组字段及版式要求的各个独立文字字段。' : '当前栏目没有可选的单图字段。' }}
          <el-button link type="primary" :disabled="blocked" @click="$router.push('/admin/v2/cms/menus')">打开栏目管理</el-button>
        </p>
        <el-button type="primary" :disabled="blocked || !hasListRoute || !validFields" @click="save">确认并保存分组列表</el-button>
        <el-button link type="primary" :disabled="blocked" @click="emit('routes')">核对公开路由与已发布内容</el-button>
      </template>
    </template>
    <div class="timeline-controls">
      <el-input v-model="knownId" placeholder="已保存的分组列表计划编号" aria-label="分组列表计划编号" :disabled="blocked" />
      <el-button :disabled="blocked || !knownId.trim()" @click="reopen">重读分组列表计划</el-button>
    </div>
    <template v-if="snapshot">
      <p>已保存方案 {{snapshot.id}} · 公开路径 {{snapshot.firstUrl || '待配置'}}</p>
      <p v-if="savedTimeline">分组字段 {{savedTimeline.groupField}} · 文字字段 {{savedTimeline.rowField}}<template v-if="savedTimeline.secondaryField"> · 附加文字字段 {{savedTimeline.secondaryField}}</template><template v-if="savedTimeline.rowImageSelector"> · {{savedTimeline.imageField ? `图片字段 ${savedTimeline.imageField}` : '隐藏图片'}}</template></p>
      <el-button :disabled="blocked" @click="compile">检查分组列表编译结果</el-button>
      <p v-if="compiled?.completeList" role="status">分组列表编译完成，可以进入公开应用。</p>
      <p v-for="warning in compiled?.warnings || []" :key="warning">{{warning}}</p>
      <details v-if="compiled"><summary>查看编译结果</summary><el-input type="textarea" :rows="8" :model-value="compiled.html" readonly aria-label="分组列表编译结果" /></details>
      <PublicListApplicationPanel :site-id="siteId" :query-id="snapshot.id" :package-version="snapshot.packageVersion"
        :complete="!!compiled?.completeList" :disabled="blocked" :has-form-actions="false" :grouped-menu-id="menuId"
        @busy="publicBusy=$event" @changed="emit('changed')" />
    </template>
    <section v-if="canManage && maintenanceField" class="timeline-maintenance" :aria-label="`按${groupLabel}维护${rowLabel}`">
      <h4>按{{groupLabel}}维护{{rowLabel}}</h4>
      <p>选择或输入{{groupLabel}}后打开内容列表。每条{{rowLabel}}独立保存，可在组内排序并按权限发布。</p>
      <p v-if="groupReadError" role="status">已保存的{{groupLabel}}暂时无法读取，可输入{{groupLabel}}打开内容列表。</p>
      <div class="timeline-controls">
        <el-select v-model="selectedGroup" filterable allow-create default-first-option :empty-values="[null,undefined]" :aria-label="`维护${groupLabel}`"
          :placeholder="`选择或输入${groupLabel}`" :disabled="blocked">
          <el-option v-for="group in groupOptions" :key="group.value" :label="group.count === undefined ? `${group.value} · 原页入口` : `${group.value || '未分组'} · ${group.count} 条${rowLabel}`" :value="group.value" />
        </el-select>
        <el-button :disabled="blocked || selectedGroup === undefined || selectedGroup.trim().length > 512" @click="selectedGroup !== undefined && $router.push({path:'/admin/v2/cms/content-management',
          query:{siteId:String(siteId),menuId:String(menuId),groupField:maintenanceField,groupValue:selectedGroup.trim(),
            ...(groupedCards ? {groupDisplay:'records'} : {})}})">打开此{{groupLabel}}的{{rowLabel}}列表</el-button>
      </div>
    </section>
  </el-card>
</template>

<style scoped>
.timeline-panel { margin-top: 16px; }
.timeline-controls { display:flex; flex-wrap:wrap; align-items:center; gap:12px; margin:12px 0; }
.el-select, .timeline-controls .el-input { width:min(300px,100%); }
p { overflow-wrap:anywhere; }
</style>
