<script setup lang="ts">
import { ref, watch } from 'vue';
import { projectExperiencesApi, type ProjectExperience, type ProjectExperienceView, type ExperienceReference } from '@/api/projectExperiences';
const props = defineProps<{ siteId: number | null; sourcePath: string | null; workspaceRevision?: number; usedReferences?: ExperienceReference[] }>();
const empty = (): ProjectExperienceView => ({ history: [], references: [], total: 0, offset: 0, activeCount: 0, archives: [], experienceEnabled: false });
const view = ref<ProjectExperienceView>(empty());
const loading = ref(false); const acting = ref(false); const offset = ref(0); const error = ref(''); let revision = 0;
async function load() {
  const current = ++revision; view.value = empty(); error.value = '';
  if (!props.siteId || !props.sourcePath) { loading.value = false; return; }
  loading.value = true;
  try { const result = await projectExperiencesApi.get(props.siteId, props.sourcePath, offset.value); if (current === revision) view.value = result; }
  catch (cause) { if (current === revision) error.value = cause instanceof Error ? cause.message : '经验读取失败'; }
  finally { if (current === revision) loading.value = false; }
}
watch(() => [props.siteId, props.sourcePath, props.workspaceRevision], () => { offset.value = 0; void load(); }, { immediate: true });
async function toggle(item: ProjectExperience) {
  if (!props.siteId || acting.value) return;
  const current = revision;
  acting.value = true;
  try { await projectExperiencesApi.status(props.siteId, item, !item.enabled); if (current === revision) await load(); }
  catch (cause) { if (current === revision) error.value = cause instanceof Error ? cause.message : '经验状态更新失败'; }
  finally { acting.value = false; }
}
async function archive() {
  if (!props.siteId || !props.sourcePath || acting.value) return;
  const current = revision; acting.value = true;
  try { await projectExperiencesApi.archive(props.siteId, props.sourcePath); if (current === revision) await load(); }
  catch (cause) { if (current === revision) error.value = cause instanceof Error ? cause.message : '归档失败，当前历史未提交变更'; }
  finally { acting.value = false; }
}
function page(step: number) { offset.value = Math.max(0, offset.value + step * 10); void load(); }
const label = (units: ProjectExperience['after']) => (units || []).map(unit => `${unit.name}（${unit.fieldType}${unit.ignored ? '，忽略' : ''}）`).join('；');
</script>
<template>
  <el-card v-loading="loading" shadow="never">
    <template #header><strong>项目内人工修正经验</strong> <el-button size="small" :disabled="!siteId || !sourcePath" @click="load">重新读取经验</el-button></template>
    <p v-if="view.experienceEnabled">仅内容单元的明确人工修正进入经验；普通内容运营更新不学习。以下新页候选最多展示首批8条，须结合当前业务与区域判断，不会套用旧值。实际传入模型的范围以本次分析结果为准。</p>
    <p v-else>经验暂停记录与参考。当前页人工方案仍可保存和绑定，既有历史保留。</p>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-alert v-if="view.referenceWarning" :title="view.referenceWarning" type="warning" :closable="false" />
    <p v-if="view.experienceEnabled">本次分析实际传入经验：{{ usedReferences?.length ? usedReferences.map(item => `${item.correction.id} v${item.correction.version}${item.conflict ? '（冲突，待确认）' : ''}`).join('；') : '无' }}。这是提示上下文范围，不表示模型保证采用。</p>
    <details v-if="view.experienceEnabled && usedReferences?.length">
      <summary>查看本次实际发送的相关单元与旧来源（完整历史保持不变）</summary>
      <article v-for="reference in usedReferences" :key="reference.correction.id">
        <p>{{ reference.correction.id }} · v{{ reference.correction.version }} · 包 {{ reference.correction.packageVersion }} · {{ reference.correction.sourcePath }}</p>
        <p>{{ label(reference.correction.before) }} → {{ label(reference.correction.after) }}；当前来源：{{ reference.targetSourceIds.join('、') }}</p>
        <p v-for="source in reference.correction.sources" :key="source.id">旧来源 {{ source.selector }}：{{ source.rawValue }}（{{ source.context }}）</p>
      </article>
    </details>
    <p v-if="view.experienceEnabled && !view.references.length && !view.referenceWarning">当前页没有适用的跨页经验候选。</p>
    <article v-for="reference in view.references" :key="reference.correction.id">
      <el-tag v-if="reference.conflict" type="warning">经验冲突，待人工确认</el-tag>
      <p>候选 {{ reference.correction.id }} · v{{ reference.correction.version }} · {{ reference.correction.sourcePath }} · 包 {{ reference.correction.packageVersion }}</p>
      <p>原决定：{{ label(reference.correction.before) }} → {{ label(reference.correction.after) }}；当前匹配来源：{{ reference.targetSourceIds.join('、') }}</p>
      <details><summary>查看来源与区域证据</summary><p v-for="source in reference.correction.sources" :key="source.id">{{ source.selector }}：{{ source.rawValue }}（{{ source.context }}）</p></details>
      <el-button size="small" :disabled="acting || loading" @click="toggle(reference.correction)">停用此经验</el-button>
    </article>
    <p>当前页修正历史共 {{ view.total }} 条；活跃 {{ view.activeCount }}/50 条，已归档 {{ view.archives.length }} 批。启用记录时达到容量阈值会自动归档已有历史；显式归档仍保留全部历史；归档和经验开关不改变内容单元修订。</p>
    <el-alert v-if="view.activeCount >= 50" title="活跃历史已达50条；启用记录时下次修正将自动归档，无需额外操作。" type="warning" :closable="false" />
    <el-button size="small" :disabled="!view.activeCount || acting || loading" @click="archive">显式归档本页活跃历史</el-button>
    <details v-if="view.archives.length"><summary>最近归档批次（最多5条）</summary><p v-for="batch in view.archives.slice(-5)" :key="batch.id">{{ batch.id }} · {{ batch.count }} 条 · 操作者 {{ batch.operatorId }} · {{ batch.timestamp }}</p></details>
    <p>历史第 {{ Math.floor(offset / 10) + 1 }} 页，每页10条。</p>
    <el-button size="small" :disabled="offset === 0 || loading || acting" @click="page(-1)">上一页历史</el-button>
    <el-button size="small" :disabled="offset + view.history.length >= view.total || loading || acting" @click="page(1)">下一页历史</el-button>
    <article v-for="item in view.history" :key="item.id">
      <p>{{ item.id }} · v{{ item.version }} · {{ item.timestamp }} · 操作者 {{ item.operatorId }} · {{ item.enabled ? '启用' : '停用' }}</p>
      <p>{{ label(item.before) }} → {{ label(item.after) }}</p>
      <p>来源：{{ item.sourcePath }} · 包 {{ item.packageVersion }} · 基线 {{ item.baselineKind ?? '未知（旧存档未记录）' }} / {{ item.baselineId ?? '未知' }} · 原修订 {{ item.baseRevision ?? '未知' }}</p>
      <p v-if="item.statusOperatorId != null">最近状态更新：操作者 {{ item.statusOperatorId }} · {{ item.statusTimestamp }}</p>
      <details><summary>查看前后单元及来源证据</summary><pre>{{ JSON.stringify({before: item.before, after: item.after, sources: item.sources}, null, 2) }}</pre></details>
      <el-button size="small" :disabled="acting || loading" @click="toggle(item)">{{ item.enabled ? '停用' : '重新启用' }}</el-button>
    </article>
  </el-card>
</template>
<style scoped>
p { line-height: 1.6; overflow-wrap: anywhere; } article { border-top: 1px solid var(--el-border-color); padding: 12px 0; }
pre { white-space: pre-wrap; overflow-wrap: anywhere; }
</style>
