<template>
  <el-button class="agent-run-entry" type="primary" :disabled="!runs.length && !chatScope" @click="visible=true">Agent 运行窗口<template v-if="liveCount"> · {{ liveCount }} 项进行中</template></el-button>
  <el-dialog v-model="visible" title="Agent 运行窗口" width="min(960px, 94vw)" top="6vh" append-to-body :close-on-click-modal="false">
    <section class="agent-run-status" aria-label="当前任务状态">
      <div class="agent-run-controls">
        <el-select v-if="runs.length" v-model="selectedKey" aria-label="选择 Agent 任务" @change="connect">
          <el-option value="current" label="当前页 · 独立对话" />
          <el-option v-for="run in runs" :key="key(run)" :value="key(run)" :label="runLabel(run)" />
        </el-select>
        <el-button v-if="canStartPage" type="primary" @click="startPage">启动当前页分析</el-button>
      </div>
      <p v-if="chatScope && selected?.sourcePath !== chatScope.sourcePath">当前页面 · {{ chatScope.sourcePath }}</p>
      <p v-if="!selected">当前会话未关联分析任务。</p>
      <template v-if="selected">
        <p><strong>{{ runLabel(selected) }}</strong> · {{ stateLabel }} · 已运行 {{ elapsed }}</p>
        <p>站点 {{ siteId }} · 页面包 {{ packageVersion || '未确认版本' }}</p>
        <p v-if="progress.message">{{ progress.message }}</p>
        <p role="status">连接：{{ connectionMessage }}<template v-if="updatedAt"> · 最后更新 {{ clockTime(updatedAt) }}</template></p>
        <p v-if="progress.observedPages != null">已观察 {{ progress.observedPages }} 页<template v-if="progress.totalPages != null"> / 共 {{ progress.totalPages }} 页</template><template v-if="progress.observedValues != null"> · {{ progress.observedValues }} 项字段值</template></p>
        <el-alert v-if="error" :title="error" type="error" :closable="false" />
        <el-alert v-if="terminal" :title="terminalMessage" :type="snapshot?.state === 'completed' ? 'success' : 'warning'" :closable="false" />
        <p v-if="progress.checkpointAvailable && terminal">已保存检查点，可在整站结构中核对恢复范围。</p>
        <el-button v-if="terminal" :loading="opening" @click="openReview">{{ reviewAction }}</el-button>
        <details v-if="progress.steps.length"><summary>近期活动与工具步骤</summary><ol v-if="progress.steps.length" class="agent-run-steps agent-run-recent" aria-label="最近三个运行步骤">
          <li v-for="(step,index) in progress.steps.slice(-3)" :key="`${step.round}-${step.tool}-${step.at || index}`">
            第 {{ step.round }} 轮 · {{ toolLabel(step.tool) }} · {{ statusLabel(step.status) }} · {{ step.message }}
          </li>
        </ol></details>
        <details>
          <summary>更多任务信息</summary>
          <p>开始于 {{ clockTime(selected.startedAt) }} · {{ connectionMessage }}。{{ terminal ? "本次任务已结束。" : "关闭窗口不会取消任务。" }}</p>
          <p v-if="progress.checkpointAvailable">已保存校验草案，可在任务中断后从整站结构恢复。</p>
          <ol v-if="progress.steps.length > 3" class="agent-run-steps" aria-label="此前运行步骤">
            <li v-for="(step,index) in progress.steps.slice(0, -3)" :key="`${step.round}-${step.tool}-${step.at || index}`">
              <time v-if="step.at" :datetime="step.at">{{ clockTime(Date.parse(step.at)) }}</time>
              第 {{ step.round }} 轮 · {{ toolLabel(step.tool) }} · {{ statusLabel(step.status) }}
              <p>{{ step.message }}</p>
            </li>
          </ol>
          <p>任务标识 <code>{{ selected.taskId }}</code></p>
          <el-button :disabled="reading" @click="connect">重新读取任务并连接</el-button>
        </details>
      </template>
    </section>
    <AgentDraftChat ref="chat" :site-id="siteId" :admin-id="adminId" :menu-id="menuId" :scope="chatScope" :units="chatUnits"
      :package-version="packageVersion" :task="selected" :field-types="fieldTypes" :apply-unit="applyUnit" :disabled="chatDisabled" :visible="visible" />
    <template #footer><el-button @click="visible=false">关闭窗口</el-button></template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue';
import { AdminHttpError, resolveAdminApiBaseUrl } from '@/api/http';
import { domainAgentApi, parseDomainAgentProgress, type AgentChatScope, type AgentRunReference, type DomainAgentTask, type DomainUnit } from '@/api/domainAgent';
import { siteModelApi } from '@/api/siteModel';
import AgentDraftChat from './AgentDraftChat.vue';

const props = defineProps<{
  siteId: number | null;
  adminId: number | null;
  packageVersion?: string;
  menuId: number | null;
  chatScope: AgentChatScope | null;
  chatUnits: DomainUnit[];
  fieldTypes: { value: string; label: string }[];
  applyUnit: (unit: DomainUnit) => boolean;
  chatDisabled?: boolean;
  canStartPage?: boolean;
  startPage: () => void;
  openResult: (run: AgentRunReference, task: DomainAgentTask) => Promise<boolean>;
}>();
const chat = ref<InstanceType<typeof AgentDraftChat> | null>(null);
const runs = ref<AgentRunReference[]>([]);
const selectedKey = ref('current');
const visible = ref(false);
const snapshot = ref<DomainAgentTask | null>(null);
const reading = ref(false);
const updatedAt = ref(0);
const opening = ref(false);
// 事件流可省略结果正文；只有完整快照才确认结果是否为空。
const emptyResult = ref<boolean>();
const reviewAction = computed(() => {
  if(snapshot.value?.state !== 'completed' || emptyResult.value === true)return '返回对应审核阶段';
  return emptyResult.value === false ? '查看本次审核结果' : '核对本次任务结果';
});
async function openReview() {
  const run=selected.value, site=props.siteId, token=generation;
  if (!site || !run || opening.value) return;
  opening.value=true;
  try {
    const task=run.kind==='site' ? await siteModelApi.task(site,run.taskId) : await domainAgentApi.get(site,run.taskId);
    if(token!==generation || task.taskId!==run.taskId)return;
    const opened=await props.openResult(run,task);
    if(opened && token===generation && props.siteId===site && selected.value?.taskId===run.taskId)visible.value=false;
  } catch { if(token===generation) error.value='无法读取本次审核结果，可重新连接原任务。'; }
  finally { opening.value=false; }
}
const error = ref('');
const connectionMessage = ref('等待读取任务');
const now = ref(Date.now());
const selected = computed(() => runs.value.find(run => key(run) === selectedKey.value));
const terminal = computed(() => ['completed', 'failed', 'cancelled'].includes(snapshot.value?.state || ''));
const liveCount = computed(() => runs.value.filter(run => !run.state || ['pending', 'running'].includes(run.state)).length);
const progress = computed(() => parseDomainAgentProgress(snapshot.value?.progress));
const stateLabel = computed(() => ({ pending: '等待执行', running: '运行中', completed: '已完成', failed: '执行失败', cancelled: '已取消' } as Record<string, string>)[snapshot.value?.state || ''] || '正在恢复状态');
const terminalMessage = computed(() => snapshot.value?.state === 'completed'
  ? (emptyResult.value ? '本次分析已结束，未返回可读取的结果，可回到对应审核阶段。' : '本次分析已结束，可核对审核结果。')
  : snapshot.value?.state === 'cancelled' ? '任务已取消，可在原审核面板检查已保存结果。' : '任务已结束，请根据步骤和异常信息处理。');
const elapsed = computed(() => {
  const seconds = Math.max(0, Math.floor(((selected.value?.endedAt || now.value) - (selected.value?.startedAt || now.value)) / 1000));
  return `${Math.floor(seconds / 60)} 分 ${seconds % 60} 秒`;
});
const storageKey = computed(() => props.siteId && props.adminId && props.packageVersion
  ? `cms-agent-window:${props.adminId}:${props.siteId}:${encodeURIComponent(props.packageVersion)}` : '');
let generation = 0;
let source: EventSource | null = null;
let retry: ReturnType<typeof setTimeout> | undefined;
let lastSequence = -1;
const ticker = setInterval(() => { now.value = Date.now(); }, 1000);
function key(run: AgentRunReference) { return `${run.kind}:${run.taskId}`; }
function runLabel(run: AgentRunReference) { return `${run.kind === 'site' ? '整站分析' : '单页分析'}${run.sourcePath ? ` · ${run.sourcePath}` : ''}`; }
function clockTime(value: number) { return new Date(value).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' }); }
function toolLabel(value: string) {
  return ({ model: '等待模型决策', model_decision: '等待模型决策', read_catalog: '读取页面清单', read_fragments: '读取公共片段',
    read_model: '读取栏目与字段', read_page: '读取页面证据', read_pages: '读取页面证据', read_region: '读取区域原文',
    read_navigation: '核对导航', read_coverage: '核对覆盖范围', read_shared: '读取公共信息', read_interactions: '读取交互',
    read_capabilities: '识别页面关系', read_page_review: '核对原包跨页关系', read_form_actions: '核对表单入口',
    propose: '校验方案', validate_proposal: '校验方案', finish: '保存待审方案', context: '准备分析上下文', stopped: '任务停止' } as Record<string, string>)[value] || '工具步骤';
}
function statusLabel(value: string) {
  return ({ waiting: '等待', running: '执行中', pending: '等待执行', ok: '已完成', completed: '已完成', error: '待处理', failed: '失败', cancelled: '已取消' } as Record<string, string>)[value] || '等待更新';
}
function persist() {
  if (!storageKey.value) return;
  try { localStorage.setItem(storageKey.value, JSON.stringify({ runs: runs.value, selectedKey: selectedKey.value, visible: visible.value })); }
  catch { /* 当前窗口仍保留任务连接。 */ }
}
function disconnect() {
  ++generation;
  source?.close();
  source = null;
  clearTimeout(retry);
  reading.value = false;
}
function accept(value: DomainAgentTask, sequence?: number) {
  if (!selected.value || value.taskId !== selected.value.taskId) return;
  if (terminal.value && !['completed', 'failed', 'cancelled'].includes(value.state)) return;
  const number = sequence ?? value.sequence;
  if (number != null && (!Number.isSafeInteger(number) || number <= lastSequence)) return;
  if (number != null) lastSequence = number;
  snapshot.value = { taskId: value.taskId, state: value.state, timeoutMs: value.timeoutMs,
    error: typeof value.error === 'string' ? value.error.slice(0, 500) : '', progress: value.progress || '', text: '' };
  updatedAt.value=Date.now();
  selected.value.state = value.state;
  if (value.startedAt && Number.isFinite(Date.parse(value.startedAt))) selected.value.startedAt = Date.parse(value.startedAt);
  if (snapshot.value.error) error.value = snapshot.value.error;
  persist();
  if (terminal.value) {
    selected.value.endedAt ||= Date.now();
    persist();
    source?.close();
    source = null;
    clearTimeout(retry);
    connectionMessage.value = '已收到任务终态';
  }
}
async function connect() {
  disconnect();
  const run = selected.value;
  const siteId = props.siteId;
  persist();
  snapshot.value = null;
  emptyResult.value = undefined;
  updatedAt.value=0;
  error.value = '';
  if (!run || !siteId) return;
  const token = generation;
  lastSequence = -1;
  reading.value = true;
  connectionMessage.value = '正在恢复任务快照';
  try {
    const current = run.kind === 'site' ? await siteModelApi.task(siteId, run.taskId) : await domainAgentApi.get(siteId, run.taskId);
    if (token !== generation) return;
    emptyResult.value = current.state === 'completed' ? !current.text?.trim() : undefined;
    accept(current);
    if (terminal.value) return;
    const base = resolveAdminApiBaseUrl().replace(/\/$/, '');
    const path = run.kind === 'site' ? 'site-model/tasks' : 'domain-agent-tasks';
    const stream = new EventSource(`${base}/api/admin/v2/sites/${siteId}/template-seed/${path}/${encodeURIComponent(run.taskId)}/events`, { withCredentials: true });
    source = stream;
    stream.onopen = () => { if (token === generation) connectionMessage.value = '实时连接已建立'; };
    const receive = (event: MessageEvent<string>) => {
      if (token !== generation) return;
      try {
        const data = JSON.parse(event.data) as DomainAgentTask;
        accept(data, event.lastEventId ? Number(event.lastEventId) : undefined);
      } catch { connectionMessage.value = '实时消息暂不可读，将补查任务'; stream.close(); schedule(token); }
    };
    for (const event of ['snapshot', 'progress', 'done', 'failed', 'cancelled', 'message']) stream.addEventListener(event, receive as EventListener);
    stream.onerror = () => {
      if (token !== generation || terminal.value) return;
      stream.close();
      connectionMessage.value = '实时连接中断，正在补查任务并重连';
      schedule(token);
    };
  } catch (cause) {
    if (token !== generation) return;
    error.value = cause instanceof Error ? cause.message : '任务快照读取失败';
    if (cause instanceof AdminHttpError && [401, 403, 404, 410].includes(cause.status || 0)) {
      run.state = 'unavailable';
      persist();
      connectionMessage.value = '原任务暂不可恢复，请在对应审核面板核对任务或已保存草案';
    } else schedule(token);
  } finally { if (token === generation) reading.value = false; }
}
function schedule(token: number) {
  clearTimeout(retry);
  retry = setTimeout(() => { if (token === generation) void connect(); }, 2000);
}
function follow(run: AgentRunReference) {
  const existing = runs.value.find(item => key(item) === key(run));
  if (existing && (!['completed', 'failed', 'cancelled'].includes(existing.state || '') ||
    !['pending', 'running'].includes(run.state || ''))) {
    existing.state = run.state;
    persist();
  }
  if (!existing) {
    runs.value = [...runs.value, run].slice(-20);
    selectedKey.value = key(run);
    visible.value = true;
    persist();
    void connect();
  }
}
async function discuss(id: string, fieldName?: string) {
  const { siteId, adminId, menuId, chatScope } = props;
  if (selected.value?.kind === 'page' && selected.value.sourcePath !== chatScope?.sourcePath) {
    const samePage = runs.value.filter(run => run.kind === 'page' && run.sourcePath === chatScope?.sourcePath).at(-1);
    selectedKey.value = samePage ? key(samePage) : 'current';
    void connect();
  }
  visible.value = true;
  await nextTick();
  if (props.siteId === siteId && props.adminId === adminId && props.menuId === menuId && props.chatScope === chatScope) {
    chat.value?.open(id, fieldName);
  }
}
watch(visible, persist);
watch(storageKey, () => {
  disconnect();
  runs.value = [];
  selectedKey.value = 'current';
  snapshot.value = null;
  emptyResult.value = undefined;
  visible.value = false;
  if (!storageKey.value) return;
  try {
    const saved = JSON.parse(localStorage.getItem(storageKey.value) || 'null');
    if (!saved || !Array.isArray(saved.runs)) return;
    runs.value = saved.runs.filter((run: AgentRunReference) => run && (run.kind === 'site' || run.kind === 'page') &&
      typeof run.taskId === 'string' && /^[a-f0-9-]{36}$/i.test(run.taskId) && Number.isFinite(run.startedAt)).slice(-20);
    if (!runs.value.length) return;
    selectedKey.value = saved.selectedKey === 'current' || runs.value.some(run => key(run) === saved.selectedKey)
      ? saved.selectedKey : key(runs.value.at(-1)!);
    visible.value = saved.visible === true;
    void connect();
  } catch { /* 忽略无效本机任务引用。 */ }
}, { immediate: true });
onBeforeUnmount(() => { disconnect(); clearInterval(ticker); });
defineExpose({ follow, discuss });
</script>

<style scoped>
.agent-run-entry { position: fixed; right: 24px; bottom: 24px; z-index: 2000; }
.agent-run-status { padding-bottom: 14px; margin-bottom: 14px; border-bottom: 1px solid var(--admin-border-subtle); }
.agent-run-status p { margin-block: 6px; overflow-wrap: anywhere; }
.agent-run-controls { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; }
.agent-run-controls .el-select { width: min(300px, 100%); }
.agent-run-steps { max-height: 48vh; overflow-y: auto; padding-left: 24px; }
.agent-run-recent { max-height: 10rem; margin-block: 8px; }
.agent-run-recent li { margin-block: 4px; }
.agent-run-steps li { margin-block: 12px; overflow-wrap: anywhere; }
.agent-run-steps li p { margin: 4px 0; }
.agent-run-steps time { margin-right: 8px; color: var(--admin-text-secondary); }
@media (max-width: 768px) {
  .agent-run-entry { right: 12px; bottom: max(12px, env(safe-area-inset-bottom)); min-height: 44px; }
  .agent-run-status summary, .agent-run-status :deep(.el-button) { min-height: 44px; align-content: center; }
}
</style>
