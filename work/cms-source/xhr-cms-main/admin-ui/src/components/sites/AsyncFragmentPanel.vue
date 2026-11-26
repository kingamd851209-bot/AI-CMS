<template>
  <el-card shadow="never" class="async-fragments" aria-label="包内异步片段检查">
    <template #header><strong>包内异步片段</strong></template>
    <p>检查页面中指向包内 HTML 的加载候选。候选仍需人工核对脚本中的触发与加载关系。</p>
    <el-button :loading="loading" :disabled="disabled || !siteId || !sourcePath" aria-label="检查包内异步片段" @click="inspect">检查加载候选</el-button>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <template v-if="inspection">
      <p class="async-fragments__evidence">来源：{{ inspection.sourcePath }} · 摘要 {{ inspection.digest.slice(0, 12) }}</p>
      <el-alert v-for="(warning, index) in inspection.warnings" :key="index" :title="warning" type="warning" :closable="false" show-icon />
      <el-empty v-if="!inspection.candidates.length" description="未发现可列出的包内加载候选；仍需核对页面脚本。" :image-size="56" />
      <el-table v-else :data="inspection.candidates" aria-label="异步片段加载候选">
        <el-table-column label="触发标签" min-width="150"><template #default="{ row }">{{ row.label || '未命名触发元素' }}</template></el-table-column>
        <el-table-column label="包内目标" min-width="190"><template #default="{ row }"><span class="async-fragments__path">{{ row.targetPath || row.rawTarget }}</span></template></el-table-column>
        <el-table-column label="状态" width="120"><template #default="{ row }"><el-tag :type="row.status === 'REVIEW' ? 'info' : 'warning'">{{ row.status === 'REVIEW' ? '待人工复核' : '待处理' }}</el-tag></template></el-table-column>
        <el-table-column label="依据与说明" min-width="220"><template #default="{ row }"><details><summary>{{ row.message }}</summary><p>触发位置：{{ row.triggerSelector }}</p><p>{{ row.attribute }}：{{ row.rawTarget }}</p><p v-if="row.targetDigest">目标摘要：{{ row.targetDigest.slice(0, 12) }}</p></details></template></el-table-column>
        <el-table-column label="操作" width="170"><template #default="{ row }"><el-button :disabled="disabled || loading || row.status !== 'REVIEW' || !row.targetPath" aria-label="复核后进入片段识别" @click="open(row)">复核后进入识别</el-button></template></el-table-column>
      </el-table>
    </template>
    <p class="async-fragments__help">确认候选关系后，进入片段并选择其对应栏目，继续已有的内容识别、字段计划和初始化；公开应用使用片段原 URL。父页面的触发标签仍在父页面维护。</p>
  </el-card>
</template>

<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue';
import { inspectAsyncFragments, type AsyncFragmentCandidate, type AsyncFragmentInspection } from '@/api/asyncFragments';

const props = defineProps<{ siteId: number | null; sourcePath: string | null; disabled: boolean }>();
const emit = defineEmits<{ open: [sourcePath: string] }>();
const inspection = ref<AsyncFragmentInspection | null>(null);
const loading = ref(false);
const error = ref('');
let generation = 0;
function reset() {
  generation++;
  inspection.value = null;
  error.value = '';
  loading.value = false;
}
watch(() => [props.siteId, props.sourcePath, props.disabled], reset, { flush: 'sync' });
onBeforeUnmount(reset);
defineExpose({inspect});
async function inspect() {
  if (props.disabled || !props.siteId || !props.sourcePath || loading.value) return;
  const current = ++generation;
  const siteId = props.siteId;
  const sourcePath = props.sourcePath;
  inspection.value = null;
  error.value = '';
  loading.value = true;
  try {
    const result = await inspectAsyncFragments(siteId, sourcePath);
    if (current !== generation) return;
    if (result.sourcePath !== sourcePath) throw new Error('检查结果与当前来源页面不一致，请重新检查');
    inspection.value = result;
  } catch (cause) {
    if (current === generation) error.value = cause instanceof Error ? cause.message : '加载候选检查失败，请重试';
  } finally {
    if (current === generation) loading.value = false;
  }
}
function open(candidate: AsyncFragmentCandidate) {
  if (props.disabled || loading.value || candidate.status !== 'REVIEW' || !candidate.targetPath || !inspection.value?.candidates.includes(candidate)) return;
  emit('open', candidate.targetPath);
}
</script>

<style scoped>
.async-fragments p { line-height: 1.6; }
.async-fragments .el-alert { margin-top: 12px; }
.async-fragments__evidence, .async-fragments__help { color: var(--el-text-color-secondary); font-size: var(--el-font-size-small); }
.async-fragments__path, .async-fragments details { overflow-wrap: anywhere; }
</style>
