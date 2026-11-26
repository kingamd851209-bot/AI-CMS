<script setup lang="ts">
import { computed } from 'vue';
import type { SiteBuildStageId, SiteBuildStageStatus, SiteBuildAnalysisCounts } from '@/utils/siteBuildProgress';

const props = defineProps<{
  stages: ReadonlyArray<{ id: SiteBuildStageId; label: string; status: SiteBuildStageStatus; detail: string }>;
  viewing: SiteBuildStageId;
  next: SiteBuildStageId | null;
  actionLabel: string;
  nextTask?: string;
  analysis: SiteBuildAnalysisCounts | null;
  canNavigate: boolean;
  busy: boolean;
}>();
defineEmits<{ navigate: [id: SiteBuildStageId]; proceed: [] }>();
const labels: Record<SiteBuildStageStatus, string> = {
  'unknown': '待核实', 'waiting': '待开始', 'in-progress': '进行中', 'attention': '待处理', 'complete': '已完成',
};
const completed = computed(() => props.stages.filter(stage => stage.status === 'complete').length);
const suggested = computed(() => props.stages.find(stage => stage.id === props.next));
</script>

<template>
  <section class="workflow admin-panel" aria-labelledby="workflow-title">
    <div class="workflow-heading">
      <h2 id="workflow-title">站点开发流程</h2>
      <span aria-live="polite">已完成 <b>{{ completed }}</b> / {{ stages.length }} 个阶段</span>
    </div>
    <nav aria-label="开发阶段">
      <ol class="workflow-steps">
        <li v-for="(stage, index) in stages" :key="stage.id">
          <button type="button" class="workflow-step" :data-status="stage.status"
            :aria-pressed="viewing === stage.id" :aria-label="stage.label + '，' + labels[stage.status]"
            :disabled="busy || (!canNavigate && stage.id !== 'package')" @click="$emit('navigate', stage.id)">
            <span class="step-status"><span class="step-number">{{ index + 1 }}</span>{{ labels[stage.status] }}</span>
            <strong>{{ stage.label }}</strong>
            <span class="step-detail">{{ stage.detail }}</span>
            <span v-if="stage.id === 'structure' && analysis?.total" class="step-count">
              原页分析 {{ analysis.analyzed }}/{{ analysis.total }} · 已审核 {{ analysis.confirmed }}
            </span>
          </button>
        </li>
      </ol>
    </nav>
    <div class="workflow-next">
      <div><span class="next-caption">{{ suggested ? '建议下一步' : '验收结果' }}</span>
        <strong>{{ nextTask || suggested?.label || '查看整站验收记录' }}</strong></div>
      <el-button type="primary" :disabled="busy" @click="$emit('proceed')">{{ actionLabel }}</el-button>
    </div>
  </section>
</template>

<style scoped>
.workflow { border:var(--admin-card-border); border-radius:var(--admin-card-radius); padding:var(--admin-card-padding); background:var(--el-bg-color); }
.workflow-heading,.workflow-next,.workflow-next>div,.step-status { display:flex;align-items:center;gap:10px; }
.workflow-heading,.workflow-next { justify-content:space-between;flex-wrap:wrap; }
.workflow-heading h2 { margin:0;font-size:var(--admin-font-size-section); }
.workflow-heading>span,.next-caption { color:var(--el-text-color-secondary);font-size:13px; }
.workflow-heading b { color:var(--el-text-color-primary);font-variant-numeric:tabular-nums; }
.workflow-steps { list-style:none;display:grid;grid-template-columns:repeat(5,minmax(0,1fr));gap:8px;margin:16px 0;padding:0; }
.workflow-step { box-sizing:border-box;display:flex;flex-direction:column;align-items:flex-start;gap:6px;width:100%;height:100%;padding:12px;text-align:left;font:inherit;color:var(--el-text-color-primary);background:var(--el-fill-color-blank);border:1px solid var(--el-border-color);border-radius:6px;cursor:pointer; }
.workflow-step:hover:not(:disabled),.workflow-step[aria-pressed=true] { border-color:var(--el-color-primary); }
.workflow-step[aria-pressed=true] { background:var(--el-color-primary-light-9); }
.workflow-step:focus-visible { outline:2px solid var(--el-color-primary);outline-offset:2px; }
.workflow-step:disabled { cursor:default; }
.step-status { gap:6px;font-size:12px;color:var(--el-text-color-secondary); }
.step-number { display:grid;place-items:center;width:21px;height:21px;font-size:12px;font-weight:650;border-radius:50%;background:var(--el-fill-color);color:var(--el-text-color-regular); }
[data-status=complete] .step-number { color:var(--el-color-success-dark-2);background:var(--el-color-success-light-9); }
[data-status=attention] .step-number { color:var(--el-color-warning-dark-2);background:var(--el-color-warning-light-9); }
[aria-pressed=true] .step-number { background:var(--el-color-primary);color:white; }
.workflow-step strong { font-size:14px;line-height:1.5; }
.step-count {font-size:12px;color:var(--el-text-color-secondary);line-height:1.5;}
.step-detail { color:var(--el-text-color-regular);font-size:12px;line-height:1.5;overflow-wrap:anywhere; }
.workflow-next { border-top:1px solid var(--el-border-color-lighter);padding-top:12px; }
.workflow-next strong { font-size:14px; }
@media(max-width:1100px) { .workflow-steps {grid-template-columns:repeat(auto-fit,minmax(145px,1fr));} }
@media(max-width:640px) {
  .workflow-steps {display:flex;flex-direction:column;gap:4px;margin:12px 0;}
  .workflow-step {display:grid;grid-template-columns:68px 1fr;gap:3px 8px;padding:7px 10px;}
  .step-detail,.step-count {grid-column:2;}
  .workflow-next .el-button {min-height:40px;}
}
</style>
