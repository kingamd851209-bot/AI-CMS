<template>
  <div class="reviewed-import">
    <el-button :disabled="disabled || !siteId || !packageVersion" @click="visible = true">导入已审列表记录</el-button>
    <el-dialog v-model="visible" title="导入已审列表记录" width="min(900px, 92vw)"
      :close-on-click-modal="!busy" :close-on-press-escape="!busy" :show-close="!busy">
      <div class="reviewed-import__tools">
        <label for="reviewed-list-file">已审导入 JSON</label>
        <input id="reviewed-list-file" type="file" accept=".json,application/json" :disabled="busy" @change="readFile">
        <el-checkbox v-if="input?.sections.some(section => section.sectionFields)"
          v-model="narrow" :disabled="busy" @change="resetPreview">
          同时收敛分区的启用字段，保留原字段值
        </el-checkbox>
        <el-button :disabled="!input || busy" @click="preview">读取栏目并预览</el-button>
      </div>
      <p v-if="input">站点 #{{ input.siteId }} · {{ input.packageVersion }} · {{ input.sections.length }} 个列表栏目</p>
      <el-alert v-if="error" :title="error" :closable="false" type="error" show-icon />
      <p v-if="progress" role="status">{{ progress }}</p>
      <div v-if="plans.length" class="reviewed-import__results">
        <details v-for="plan in plans" :key="plan.section.listMenuId">
          <summary>
            {{ plan.parent.menu.className }} #{{ plan.section.sectionMenuId }} →
            {{ plan.fields.menu.className }} #{{ plan.section.listMenuId }}
            · 新增 {{ count(plan, 'create') }} · 待审核 {{ count(plan, 'resume') }}
            · 沿用 {{ count(plan, 'keep') }} · 冲突 {{ count(plan, 'conflict') }}
          </summary>
          <p v-if="plan.listPlan.enabled.length">列表启用字段 {{ plan.listPlan.enabled.join('、') }}</p>
          <p v-if="plan.queries?.repaired">核对后台展示字段和搜索字段，缺省使用原生标题。</p>
          <p v-if="plan.sectionPlan?.enabled.length">分区启用字段 {{ plan.sectionPlan.enabled.join('、') }}</p>
          <p v-if="plan.sectionPlan?.disabled.length">分区停用字段 {{ plan.sectionPlan.disabled.join('、') }}</p>
          <p v-for="problem in [...plan.listPlan.conflicts, ...(plan.sectionPlan?.conflicts ?? [])]" :key="problem">
            字段冲突，该栏目暂停导入。{{ problem }}
          </p>
          <ul>
            <li v-for="(entry, index) in plan.recordPlan" :key="index">
              <details>
                <summary>{{ entry.record.values.title }} · 排序 {{ entry.record.sort }} · {{ entry.detail }}</summary>
                <dl v-for="(value, field) in entry.record.values" :key="field">
                  <dt>{{ field }}</dt>
                  <dd>{{ value }}</dd>
                </dl>
              </details>
            </li>
          </ul>
        </details>
      </div>
      <details v-if="results.length" open class="reviewed-import__results">
        <summary>原生执行结果 · 完成 {{ results.filter(r => !r.failed).length }} 项 · 待处理 {{ results.filter(r => r.failed).length }} 项</summary>
        <ul>
          <li v-for="(result, index) in results" :key="index">
            #{{ result.menuId }} · {{ result.failed ? '待处理 · ' : '' }}{{ result.message }}
          </li>
        </ul>
      </details>
      <template #footer>
        <el-button :disabled="busy" @click="visible = false">关闭</el-button>
        <el-button type="primary" :disabled="busy || !canApply" :loading="busy && !!progress" @click="confirm">
          确认导入并审核发布
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { ElMessageBox } from 'element-plus';
import { applyReviewedListImport, prepareReviewedListImport, type SectionImportPlan } from '@/api/reviewedListImport';
import { parseReviewedListImport, type ReviewedListImport } from '@/utils/reviewedListImport';

const props = defineProps<{ siteId: number | null; packageVersion?: string; disabled?: boolean }>();
const emit = defineEmits<{ applied: [] }>();
const visible = ref(false);
const busy = ref(false);
const input = ref<ReviewedListImport>();
const narrow = ref(false);
const plans = ref<SectionImportPlan[]>([]);
const results = ref<Awaited<ReturnType<typeof applyReviewedListImport>>>([]);
const error = ref('');
const progress = ref('');

const canApply = computed(() => plans.value.some(plan =>
  !plan.listPlan.conflicts.length && !plan.sectionPlan?.conflicts.length && (
    plan.listPlan.enabled.length || plan.queries?.repaired || plan.recordPlan.some(item => item.action === 'create' || item.action === 'resume') ||
    (!plan.recordPlan.some(item => item.action === 'conflict') && (plan.sectionPlan?.enabled.length || plan.sectionPlan?.disabled.length))
  )));

function count(plan: SectionImportPlan, action: string) {
  return plan.recordPlan.filter(item => item.action === action).length;
}
function resetPreview() {
  plans.value = [];
  results.value = [];
  error.value = '';
}
watch(() => [props.siteId, props.packageVersion], resetPreview);

function guard(current: ReviewedListImport) {
  if (current.siteId !== props.siteId || current.packageVersion !== props.packageVersion)
    throw new Error('JSON 的站点或整包版本与当前工作台不一致，请切换到对应项目');
}
function showError(reason: unknown) {
  error.value = reason instanceof Error ? reason.message : String(reason);
}
async function readFile(event: Event) {
  const file = (event.target as HTMLInputElement).files?.[0];
  input.value = undefined;
  narrow.value = false;
  resetPreview();
  if (!file) return;
  try {
    const source = parseReviewedListImport(JSON.parse((await file.text()).replace(/^\uFEFF/, '')));
    guard(source);
    input.value = source;
  } catch (reason) {
    showError(reason);
  }
}
async function preview() {
  const current = input.value;
  if (!current || busy.value) return;
  resetPreview();
  busy.value = true;
  try {
    plans.value = await prepareReviewedListImport(current, narrow.value, () => guard(current));
  } catch (reason) {
    showError(reason);
  } finally {
    busy.value = false;
  }
}
async function confirm() {
  const current = input.value;
  if (!current || busy.value || !canApply.value) return;
  try {
    await ElMessageBox.confirm(
      '将通过 CMS 保存草稿、设置排序并审核发布。已有不同值的记录保持原样，失败记录可重新预览。',
      '确认导入已审记录', { type: 'warning', confirmButtonText: '开始导入', cancelButtonText: '返回预览' }
    );
  } catch {
    return;
  }
  if (busy.value || input.value !== current || !canApply.value) return;
  busy.value = true;
  error.value = '';
  progress.value = '正在复核正式栏目与已有记录';
  let changed = false;
  try {
    const apply = () => applyReviewedListImport(current, narrow.value, plans.value, () => guard(current),
      message => { progress.value = message; }, () => { changed = true; });
    results.value = navigator.locks
      ? await navigator.locks.request('cms-reviewed-list-import-' + current.siteId, apply)
      : await apply();
    plans.value = [];
  } catch (reason) {
    showError(reason);
  } finally {
    progress.value = '';
    busy.value = false;
    if (changed && props.siteId === current.siteId && props.packageVersion === current.packageVersion) emit('applied');
  }
}
</script>

<style scoped>
.reviewed-import { margin: 0 0 12px; }
.reviewed-import__tools { display: flex; align-items: center; flex-wrap: wrap; gap: 8px 16px; }
.reviewed-import__results { margin-top: 12px; max-height: 45vh; overflow: auto; }
.reviewed-import__results summary { cursor: pointer; padding: 6px 0; }
.reviewed-import__results dl { display: grid; grid-template-columns: minmax(75px, 1fr) 5fr; }
.reviewed-import__results dd { margin: 0; white-space: pre-wrap; overflow-wrap: anywhere; }
</style>
