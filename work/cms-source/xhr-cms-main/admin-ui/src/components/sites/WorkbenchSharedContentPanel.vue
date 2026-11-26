<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { ElMessageBox } from 'element-plus';
import { confirmSharedContent, confirmSharedContentSeed, previewSharedContent, previewSharedContentSeed, readSharedContent,
  type SharedContentEvidence, type SharedContentOwner, type SharedContentRequest,
  type SharedContentReview, type SharedContentSeedReview, type SharedContentSelection, type SharedContentSource } from '@/api/siteSharedContent';
import WorkbenchPublishPanel from './WorkbenchPublishPanel.vue';

const props = defineProps<{
  siteId: number; planId: string; planRevision: number; packageVersion: string;
  sourcePath: string; disabled?: boolean;
}>();
const emit = defineEmits<{ busy: [active: boolean]; structure: []; content: [menuId: number]; changed: [] }>();
const evidence = ref<SharedContentEvidence | null>(null);
const sourceId = ref('');
const ownerKey = ref('');
const sourceText = ref<HTMLTextAreaElement>();
const selections = ref<SharedContentSelection[]>([]);
const pending = ref<{ body: SharedContentRequest; review: SharedContentReview }>();
const seedPreview = ref<{ body: SharedContentRequest; review: SharedContentSeedReview }>();
const seedNotice = ref('');
const publishRefreshKey = ref(0);
const loading = ref(false);
const publishing = ref(false);
const error = ref('');
const needsRead = ref(false);
let scope = 0;

const blocked = computed(() => !!props.disabled || loading.value || publishing.value);
const source = computed(() => evidence.value?.sources.find(item => item.id === sourceId.value));
const owner = computed(() => evidence.value?.owners.find(item => item.modelKey === ownerKey.value));
const saved = computed(() => selectionsOf(evidence.value?.confirmed));
const hasUnsavedChanges = computed(() => JSON.stringify(selections.value) !== JSON.stringify(saved.value));
const incomplete = computed(() => selections.value.some(item =>
  !fieldsFor(item).some(field => field.fieldKey === item.fieldKey)));
const validation = computed(() => {
  if (incomplete.value) return '请为每项来源选择一个可用字段，或移除无法匹配的项。';
  if (selections.value.some(item => ownerOf(item)?.contentStatus !== 'PUBLISHED')) return '核对并录入初值后，请先发布公共数据，再确认模板映射。';
  return '';
});
const canSeed = computed(() => !!evidence.value && !blocked.value && !needsRead.value && !incomplete.value
  && selections.value.length > 0);
const readyToSeed = computed(() => new Set(seedPreview.value?.review.values
  .filter(item => item.status === 'READY').map(item => `${item.menuId}/${item.fieldName}`)).size);
const canPreview = computed(() => !!evidence.value && !blocked.value && !needsRead.value && !validation.value
  && (selections.value.length > 0 || saved.value.length > 0));
defineExpose({ hasUnsavedChanges });
watch(() => loading.value || publishing.value, active => emit('busy', active), { flush: 'sync' });
watch(() => [props.siteId, props.planId, props.planRevision, props.packageVersion, props.sourcePath], () => {
  scope++;
  evidence.value = null;
  sourceId.value = '';
  ownerKey.value = '';
  selections.value = [];
  clearPreviews();
  error.value = '';
  needsRead.value = false;
  loading.value = false;
  publishing.value = false;
}, { flush: 'sync' });
onBeforeUnmount(() => {
  scope++;
  if (loading.value || publishing.value) emit('busy', false);
});

function selectionsOf(review?: SharedContentReview | null) {
  return (review?.bindings ?? []).map(({ sourceId, start, end, modelKey, fieldKey }) =>
    ({ sourceId, start, end, modelKey, fieldKey }));
}
function sourceOf(item: SharedContentSelection) {
  return evidence.value?.sources.find(value => value.id === item.sourceId);
}
function ownerOf(item: SharedContentSelection) {
  return evidence.value?.owners.find(value => value.modelKey === item.modelKey);
}
function fieldOf(item: SharedContentSelection) {
  return ownerOf(item)?.fields.find(value => value.fieldKey === item.fieldKey);
}
function fieldsFor(item: SharedContentSelection) {
  const kind = sourceOf(item)?.kind;
  return ownerOf(item)?.fields.filter(field =>
    kind === 'TEXT' && field.fieldType === 'SingleLine' || kind === 'IMAGE' && field.fieldType === 'Image') ?? [];
}
function sourceLabel(item: SharedContentSource) {
  return `${item.publicRegion === 'HEADER' ? '页头' : '页脚'} · ${item.rawValue.trim().slice(0, 90)}`;
}
const statuses: Record<SharedContentOwner['contentStatus'], string> = {
  DRAFT: '草稿', PENDING_REVIEW: '待审核', APPROVED: '已审核', PUBLISHED: '已发布',
};
function clearPreviews() {
  pending.value = undefined;
  seedPreview.value = undefined;
  seedNotice.value = '';
}
const seedStatuses: Record<string, string> = { READY: '可写入', PRESERVED: '保留现有数据', PENDING: '待处理' };

function failure(cause: unknown, fallback: string) {
  return cause instanceof Error ? cause.message : fallback;
}

async function refreshEvidence(preserveSelections: boolean, token: number) {
  const result = await readSharedContent(props.siteId, props.planId, props.packageVersion, props.sourcePath);
  if (token !== scope) return;
  if (result.sourcePath !== props.sourcePath || result.revision !== props.planRevision)
    throw new Error('当前页面或结构方案已变化，请刷新工作台。');
  if (preserveSelections && (result.sourceDigest !== evidence.value?.sourceDigest
    || result.confirmed?.digest !== evidence.value?.confirmed?.digest)) {
    throw new Error('来源或已确认映射已变化，请重新读取公共信息。');
  }
  evidence.value = result;
  if (!preserveSelections) selections.value = selectionsOf(result.confirmed);
  if (!result.sources.some(value => value.id === sourceId.value)) sourceId.value = result.sources[0]?.id ?? '';
  if (!result.owners.some(value => value.modelKey === ownerKey.value)) ownerKey.value = result.owners[0]?.modelKey ?? '';
  needsRead.value = false;
}

function publishChanged() {
  emit('changed');
  void read(true);
}
async function read(refreshOwner = false) {
  if (loading.value || (!refreshOwner && blocked.value)) return;
  const token = scope;
  loading.value = true;
  try {
    if (!refreshOwner && hasUnsavedChanges.value) {
      try {
        await ElMessageBox.confirm('重新读取会丢弃当前未确认的公共信息映射。', '重新读取公共信息', { type: 'warning' });
      } catch { return; }
    }
    if (token !== scope) return;
    error.value = '';
    clearPreviews();
    await refreshEvidence(refreshOwner, token);
  } catch (cause) {
    if (token === scope) {
      needsRead.value = true;
      error.value = failure(cause, '无法读取公共信息证据');
    }
  } finally {
    if (token === scope) loading.value = false;
  }
}

// DOM textarea selection indices use LF even when evidence preserves CRLF.
function rawTextOffset(raw: string, offset: number) {
  if (!raw.includes('\r\n')) return offset;
  let index = 0;
  for (let displayed = 0; displayed < offset && index < raw.length; displayed++) {
    index += raw.startsWith('\r\n', index) ? 2 : 1;
  }
  return index;
}
function add(selectionOnly = false) {
  const value = source.value;
  if (!value || blocked.value || needsRead.value) return;
  if (selections.value.length >= 50) { error.value = '每页最多可审核 50 项映射。'; return; }
  const start = selectionOnly ? rawTextOffset(value.rawValue, sourceText.value?.selectionStart ?? 0) : 0;
  const end = selectionOnly ? rawTextOffset(value.rawValue, sourceText.value?.selectionEnd ?? 0) : value.rawValue.length;
  if (!value.rawValue.slice(start, end).trim()) { error.value = '请在原文框选出要维护的文字。'; return; }
  const splitsPair = (offset: number) => offset > 0 && offset < value.rawValue.length
    && /[\uD800-\uDBFF]/.test(value.rawValue.charAt(offset - 1))
    && /[\uDC00-\uDFFF]/.test(value.rawValue.charAt(offset));
  if (splitsPair(start) || splitsPair(end)) {
    error.value = '选段边缘截断了一个字符，请重新选择。';
    return;
  }
  if (selections.value.some(item => item.sourceId === value.id && start < item.end && end > item.start)) {
    error.value = '当前选段与已有映射重叠，请调整选段或移除原映射。';
    return;
  }
  selections.value.push({ sourceId: value.id, start, end, modelKey: ownerKey.value, fieldKey: '' });
  clearPreviews();
  error.value = '';
}
function changeOwner(item: SharedContentSelection, key: string) {
  item.modelKey = key;
  item.fieldKey = '';
  clearPreviews();
}
function remove(index: number) {
  selections.value.splice(index, 1);
  clearPreviews();
  error.value = '';
}
function requestFor(value: SharedContentEvidence): SharedContentRequest {
  return {
    packageVersion: props.packageVersion, revision: value.revision, sourcePath: value.sourcePath,
    sourceDigest: value.sourceDigest, previousDigest: value.confirmed?.digest,
    selections: selections.value.map(item => ({ ...item })),
  };
}
async function checkSeed() {
  const value = evidence.value;
  if (!value || !canSeed.value) return;
  const token = scope;
  const body = requestFor(value);
  loading.value = true;
  clearPreviews();
  error.value = '';
  try {
    const review = await previewSharedContentSeed(props.siteId, props.planId, body);
    if (token === scope) seedPreview.value = { body, review };
  } catch (cause) {
    if (token === scope) error.value = failure(cause, '无法预览公共信息初值');
  } finally {
    if (token === scope) loading.value = false;
  }
}
async function applySeed() {
  const preview = seedPreview.value;
  const count = readyToSeed.value;
  if (!preview || !canSeed.value || !count) return;
  const token = scope;
  loading.value = true;
  error.value = '';
  try {
    try {
      await ElMessageBox.confirm(
        `从 ${preview.body.sourcePath} 向所选公共数据栏目的 ${count} 个空字段写入初值？已有值和发布状态保持原样。`,
        '确认公共信息初值', { type: 'warning', confirmButtonText: '写入初值', cancelButtonText: '取消' },
      );
    } catch { return; }
    if (token !== scope || preview !== seedPreview.value) return;
    const result = await confirmSharedContentSeed(props.siteId, props.planId, preview.body, preview.review.digest);
    if (token !== scope) return;
    emit('changed');
    clearPreviews();
    seedNotice.value = '初值确认已返回，正在核对目标字段状态。';
    await refreshEvidence(true, token);
    if (token !== scope) return;
    publishRefreshKey.value++;
    const unresolved = result.values.filter(item => item.status === 'PENDING');
    const reasons = [...new Set(unresolved.map(item => item.reason).filter(Boolean))].join('；');
    seedNotice.value = unresolved.length
      ? `字段状态已回读，仍有 ${unresolved.length} 项待处理${reasons ? `（${reasons}）` : ''}。`
      : '字段状态已回读。核对录入值后，可发布公共数据并确认模板映射。';
  } catch (cause) {
    if (token === scope) {
      seedPreview.value = undefined;
      needsRead.value = true;
      if (seedNotice.value) seedNotice.value = '初值确认已返回，目标字段状态待重新读取。';
      error.value = failure(cause, '无法确认公共信息初值');
    }
  } finally {
    if (token === scope) loading.value = false;
  }
}
async function check() {
  const value = evidence.value;
  if (!value || !canPreview.value) return;
  const token = scope;
  const body = requestFor(value);
  loading.value = true;
  clearPreviews();
  error.value = '';
  try {
    const review = await previewSharedContent(props.siteId, props.planId, body);
    if (token === scope) pending.value = { body, review };
  } catch (cause) {
    if (token === scope) error.value = failure(cause, '无法预览公共信息映射');
  } finally {
    if (token === scope) loading.value = false;
  }
}
async function confirm() {
  const value = evidence.value;
  const preview = pending.value;
  if (!value || !preview || blocked.value || !canPreview.value) return;
  const token = scope;
  loading.value = true;
  error.value = '';
  try {
    const count = preview.review.bindings.length;
    try {
      await ElMessageBox.confirm(
        `确认保存 ${value.sourcePath} 的公共信息映射（${count} 项）？已应用的模板需要撤回后重新应用。`,
        '确认公共信息映射', { type: 'warning', confirmButtonText: '保存映射', cancelButtonText: '取消' },
      );
    } catch { return; }
    if (token !== scope || preview !== pending.value || evidence.value !== value) return;
    const result = await confirmSharedContent(props.siteId, props.planId, preview.body, preview.review.digest);
    if (token !== scope) return;
    evidence.value = { ...value, confirmed: result };
    selections.value = selectionsOf(result);
    pending.value = undefined;
    emit('changed');
  } catch (cause) {
    if (token === scope) {
      pending.value = undefined;
      needsRead.value = true;
      error.value = failure(cause, '无法确认公共信息映射');
    }
  } finally {
    if (token === scope) loading.value = false;
  }
}
</script>

<template>
  <el-card shadow="never" class="shared-review">
    <template #header><strong>公共信息审核</strong></template>
    <p><strong>使用公共信息的页面</strong> {{ sourcePath }}。在该页的页头或页脚选择文字、图片，再指定负责维护它们的单记录栏目。</p>
    <div class="shared-actions">
      <el-button :loading="loading" :disabled="blocked" @click="read()">读取当前页公共信息</el-button>
      <el-button :disabled="blocked" @click="emit('structure')">查看整站结构与字段</el-button>
    </div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <template v-if="evidence">
      <el-alert v-for="warning in evidence.warnings" :key="warning" :title="warning" type="warning" :closable="false" />
      <p v-if="needsRead" role="status">请重新读取证据与目标状态后再继续。</p>
      <p v-if="!evidence.owners.length">请先在整站结构中为公共数据应用单记录模型并配置字段。</p>
      <template v-else>
        <label>负责维护公共数据的栏目</label>
        <el-select v-model="ownerKey" :disabled="blocked" aria-label="查看公共信息栏目状态" placeholder="选择公共数据栏目">
          <el-option v-for="item in evidence.owners" :key="item.modelKey" :value="item.modelKey" :label="`${item.label}（${statuses[item.contentStatus]}）`" />
        </el-select>
        <template v-if="owner">
          <p>核对初值与录入状态。也可在原生单页维护中编辑字段值。</p>
          <div><el-button :disabled="blocked || needsRead" @click="emit('content', owner.menuId)">维护单页数据</el-button></div>
          <WorkbenchPublishPanel :site-id="siteId" :menu-id="owner.menuId" :refresh-key="publishRefreshKey" :disabled="disabled || loading || needsRead"
            shared-content-only @busy="publishing = $event" @changed="publishChanged" />
        </template>
      </template>
      <p v-if="!evidence.sources.length">当前页未读取到可审核的页头、页脚文字或图片。</p>
      <template v-else>
        <el-select v-model="sourceId" filterable :disabled="blocked || needsRead" aria-label="公共信息来源" placeholder="选择公共信息来源">
          <el-option v-for="item in evidence.sources" :key="item.id" :value="item.id" :label="sourceLabel(item)" />
        </el-select>
        <div v-if="source" class="shared-source">
          <small>{{ source.selector }} · {{ source.attribute }}</small>
          <textarea :key="source.id" ref="sourceText" :value="source.rawValue" readonly rows="2" aria-label="公共信息原文" />
          <div>
            <el-button :disabled="blocked || needsRead || !evidence.owners.length" @click="add()">添加整个原值</el-button>
            <el-button v-if="source.kind === 'TEXT'" :disabled="blocked || needsRead || !evidence.owners.length" @click="add(true)">添加选中的文字</el-button>
          </div>
        </div>
      </template>
      <el-table :data="selections" size="small" empty-text="当前页暂无映射" max-height="460">
        <el-table-column label="原值" min-width="180">
          <template #default="{ row }">{{ sourceOf(row)?.rawValue.slice(row.start, row.end) ?? row.sourceId }}</template>
        </el-table-column>
        <el-table-column label="公共数据栏目" min-width="170">
          <template #default="{ row }">
            <el-select :model-value="row.modelKey" aria-label="目标公共数据栏目" :disabled="blocked || needsRead"
              @change="changeOwner(row, $event)">
              <el-option v-for="item in evidence.owners" :key="item.modelKey" :value="item.modelKey"
                :label="`${item.label}（${statuses[item.contentStatus]}）`" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="字段" min-width="200">
          <template #default="{ row }">
            <el-select v-model="row.fieldKey" aria-label="目标字段" :disabled="blocked || needsRead || !fieldsFor(row).length"
              @change="clearPreviews">
              <el-option v-for="field in fieldsFor(row)" :key="field.fieldKey" :value="field.fieldKey"
                :label="`${field.label} · ${field.fieldType}${field.hasValue === undefined ? '' : field.hasValue ? ' · 已录入' : ' · 待录入'}`" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="80">
          <template #default="{ $index }"><el-button link type="danger" :disabled="blocked || needsRead" @click="remove($index)">移除</el-button></template>
        </el-table-column>
      </el-table>
      <p v-if="evidence.confirmed">已保存 {{ saved.length }} 项映射。整站结构修订后可重新预览核对映射。更新映射后，请重新应用并实际预览受影响的页面模板。</p>
      <p v-if="hasUnsavedChanges" role="status">{{ validation || '有未确认的公共信息映射。' }}</p>
      <p>初值来自整站方案已审核的原始来源。使用公共信息的其他页面可直接配置模板映射。</p>
      <el-button :disabled="!canSeed" @click="checkSeed">预览初值</el-button>
      <p v-if="seedNotice" role="status">{{ seedNotice }}</p>
      <template v-if="seedPreview">
        <el-table :data="seedPreview.review.values" size="small" max-height="320" empty-text="本次没有可核对的初值">
          <el-table-column prop="rawValue" label="原页初值" min-width="160" />
          <el-table-column label="写入目标" min-width="200">
            <template #default="{ row }">{{ ownerOf(row)?.label }} · {{ fieldOf(row)?.label }}（{{ row.fieldName }}）</template>
          </el-table-column>
          <el-table-column label="初值状态" min-width="160">
            <template #default="{ row }">{{ seedStatuses[row.status] }} <span v-if="row.reason">· {{ row.reason }}</span></template>
          </el-table-column>
        </el-table>
        <el-button type="primary" :disabled="!canSeed || !readyToSeed" @click="applySeed">写入 {{ readyToSeed }} 个空字段</el-button>
      </template>
      <el-button :disabled="!canPreview" @click="check">预览映射</el-button>
      <template v-if="pending">
        <p v-if="!pending.review.bindings.length">本次将清除当前页的已保存公共信息映射。</p>
        <el-table v-else :data="pending.review.bindings" size="small" max-height="320">
          <el-table-column label="原值" min-width="160">
            <template #default="{ row }">{{ sourceOf(row)?.rawValue.slice(row.start, row.end) }}</template>
          </el-table-column>
          <el-table-column label="绑定目标" min-width="200">
            <template #default="{ row }">{{ ownerOf(row)?.label }} · {{ fieldOf(row)?.label }}（{{ row.fieldName }}）</template>
          </el-table-column>
        </el-table>
        <el-button type="primary" :disabled="blocked || !canPreview" @click="confirm">确认公共信息映射</el-button>
      </template>
    </template>
  </el-card>
</template>

<style scoped>
.shared-review :deep(.el-card__body) { display: grid; gap: 12px; }
.shared-review :deep(.el-card__body > .el-button) { justify-self: start; margin-left: 0; }
.shared-actions { display: flex; flex-wrap: wrap; gap: 8px; }
.shared-actions .el-button + .el-button { margin-left: 0; }
.shared-review p { margin: 0; }
.shared-source { display: grid; gap: 8px; overflow-wrap: anywhere; }
.shared-source textarea { width: 100%; resize: vertical; padding: 8px; font: inherit; color: inherit; }
</style>
