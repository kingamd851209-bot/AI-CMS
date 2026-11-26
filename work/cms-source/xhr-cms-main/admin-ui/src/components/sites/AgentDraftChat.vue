<template>
  <div v-if="(target && scope) || task" class="agent-chat">
    <p v-if="unit && target && scope"><strong>{{ unit.name || '未命名内容' }}</strong><template v-if="target.fieldName"> · {{ target.fieldName }}</template> · {{ scope.sourcePath }}</p>
    <p v-else-if="task">可询问当前任务的进度、步骤和已保存结果。</p>
    <p v-else>该内容单元已移除，请重新选择字段。</p>
    <ol v-if="messages.length" ref="transcript" class="agent-chat-messages" aria-label="Agent 对话记录" aria-live="polite">
      <li v-for="(message, index) in messages" :key="index">
        <strong>{{ message.role === 'user' ? '你' : 'Agent' }}</strong>
        <p>{{ message.content }}</p>
      </li>
    </ol>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <template v-if="proposal">
      <h3>修改预览</h3>
      <p>{{ proposal.reason }}</p>
      <el-table :data="differences" size="small">
        <el-table-column prop="label" label="项目" width="120" />
        <el-table-column prop="before" label="当前值" />
        <el-table-column prop="after" label="建议值" />
      </el-table>
      <p v-if="stale" role="status">此单元已修改或移除。请根据当前草稿重新向 Agent 提出调整。</p>
      <el-button type="primary" :disabled="disabled || loading || stale || !differences.length" @click="apply">应用到页面草稿</el-button>
    </template>
    <p v-if="applied" role="status">已应用到本机页面草稿。请返回页面保存人工修正。</p>
    <form class="agent-chat-input" @submit.prevent="send">
      <el-input v-model="input" type="textarea" :rows="3" :maxlength="2000" show-word-limit
        aria-label="给 Agent 的消息" :placeholder="unit ? '例如：把这项的业务名称改为产品介绍' : '例如：任务进行到哪一步了？'" :disabled="loading || !canSend" />
      <el-button native-type="submit" type="primary" :loading="loading" :disabled="!canSend || !input.trim()">发送</el-button>
    </form>
  </div>
  <p v-else>请先在页面草稿中选择一个内容单元，再点击「与 Agent 讨论所选字段」。</p>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue';
import { domainAgentApi, type AgentChatMessage, type AgentChatProposal, type AgentChatScope, type AgentRunReference, type DomainUnit } from '@/api/domainAgent';

const props = defineProps<{
  siteId: number | null;
  adminId: number | null;
  menuId: number | null;
  scope: AgentChatScope | null;
  packageVersion?: string;
  task?: AgentRunReference;
  units: DomainUnit[];
  fieldTypes: { value: string; label: string }[];
  disabled?: boolean;
  visible: boolean;
  applyUnit: (unit: DomainUnit) => boolean;
}>();
const target = ref<{ id: string; fieldName?: string } | null>(null);
const taskMismatch = computed(() => props.task?.kind === 'page' && props.task.sourcePath !== props.scope?.sourcePath);
const unit = computed(() => taskMismatch.value ? undefined : props.units.find(item => item.id === target.value?.id));
const canSend = computed(() => !!(props.siteId && props.adminId &&
  (unit.value ? props.scope && !props.disabled : props.task && props.packageVersion)));
const input = ref('');
const messages = ref<(AgentChatMessage & { suggestion?: string })[]>([]);
const proposal = ref<AgentChatProposal | null>(null);
const error = ref('');
const loading = ref(false);
const applied = ref(false);
const transcript = ref<HTMLOListElement>();
let pending: AbortController | null = null;
const scopeKey = computed(() => JSON.stringify([props.adminId, props.siteId, props.menuId, props.packageVersion, props.scope]));
const taskKey = computed(() => props.task ? `${props.task.kind}:${props.task.taskId}` : '');
const stale = computed(() => !!proposal.value && (!unit.value || unitSnapshot(unit.value) !== unitSnapshot(proposal.value.before)));
const labels = { name: '业务名称', fieldType: '字段类型', ignored: '暂不绑定', doubts: '人工说明' };
const editableFields = ['name', 'fieldType', 'ignored', 'doubts'] as const;
const differences = computed(() => {
  const change = proposal.value;
  if (!change) return [];
  return editableFields.filter(field => change.before[field] !== change.after[field]).map(field => ({
    label: labels[field],
    before: labelValue(field, change.before),
    after: labelValue(field, change.after),
  }));
});

function labelValue(field: typeof editableFields[number], value: DomainUnit) {
  if (field === 'ignored') return value.ignored ? '是' : '否';
  if (field === 'fieldType') return props.fieldTypes.find(type => type.value === value.fieldType)?.label || value.fieldType;
  return value[field] || '空';
}
function sourceSnapshot(value: DomainUnit) {
  return value.sources.map(({ sourceId, start, end }) => [sourceId, start, end]);
}
function unitSnapshot(value: DomainUnit) {
  return JSON.stringify([value.id, sourceSnapshot(value), ...editableFields.map(field => value[field])]);
}
function summarizeProposal(change: AgentChatProposal) {
  return editableFields.filter(field => change.before[field] !== change.after[field]).map(field =>
    `${labels[field]}：${labelValue(field, change.before).slice(0, 200)} → ${labelValue(field, change.after).slice(0, 200)}`).join('\n');
}
function clearConversation() {
  pending?.abort();
  pending = null;
  messages.value = [];
  proposal.value = null;
  input.value = '';
  error.value = '';
  loading.value = false;
  applied.value = false;
}
function clear() {
  target.value = null;
  clearConversation();
}
function open(id: string, fieldName?: string) {
  if (target.value?.id === id) {
    target.value.fieldName = fieldName;
    return;
  }
  clear();
  target.value = { id, fieldName };
}
async function send() {
  const { siteId, scope, task, packageVersion } = props;
  const current = unit.value;
  const content = input.value.trim();
  if (!siteId || !content || !canSend.value || loading.value) return;
  const requestUnit = current ? { ...current, sources: current.sources.map(source => ({ ...source })) } : null;
  const requestScope = requestUnit && scope ? scope : {
    sourcePath: task?.kind === 'page' ? task.sourcePath || null : null,
    packageVersion: packageVersion || null,
    digest: null,
    revision: null,
  };
  const taskRef = task ? { kind: task.kind, id: task.taskId } : null;
  const turns = messages.value.slice(-10);
  const history = turns.map(({ role, content: previous, suggestion }) => {
    if (!suggestion) return { role, content: previous };
    const summary = `\n上一轮建议的差异\n${suggestion}`;
    return { role, content: previous.slice(0, 2000 - summary.length) + summary };
  });
  while (history.reduce((length, message) => length + message.content.length, content.length) > 12000) history.splice(0, 2);
  const requestMessages: AgentChatMessage[] = [...history, { role: 'user', content }];
  const controller = new AbortController();
  pending = controller;
  loading.value = true;
  proposal.value = null;
  applied.value = false;
  error.value = '';
  try {
    const response = await domainAgentApi.chat(siteId, requestScope, requestUnit, taskRef, requestMessages, controller.signal);
    if (pending !== controller) return;
    const suggestion = response.proposal;
    if (suggestion && (!requestUnit || unitSnapshot(suggestion.before) !== unitSnapshot(requestUnit)
      || suggestion.after.id !== requestUnit.id
      || JSON.stringify(sourceSnapshot(suggestion.after)) !== JSON.stringify(sourceSnapshot(requestUnit))
      || (suggestion.after.name !== requestUnit.name && (typeof suggestion.after.name !== 'string' || !suggestion.after.name.trim() || suggestion.after.name.length > 100))
      || (suggestion.after.fieldType !== requestUnit.fieldType && !props.fieldTypes.some(type => type.value === suggestion.after.fieldType))
      || typeof suggestion.after.ignored !== 'boolean'
      || (suggestion.after.doubts !== requestUnit.doubts && (typeof suggestion.after.doubts !== 'string' || suggestion.after.doubts.length > 2000)))) {
      throw new Error('Agent 建议与请求范围不一致，请重新提出调整。');
    }
    messages.value = [...turns.slice(turns.length - history.length),
      { role: 'user', content }, { role: 'assistant', content: response.reply, ...(suggestion ? { suggestion: summarizeProposal(suggestion) } : {}) }];
    proposal.value = suggestion;
    input.value = '';
    await nextTick();
    if (transcript.value) transcript.value.scrollTop = transcript.value.scrollHeight;
  } catch (cause) {
    if (pending === controller) error.value = cause instanceof Error ? cause.message : '暂时无法获取 Agent 回复，请重试。';
  } finally {
    if (pending === controller) {
      pending = null;
      loading.value = false;
    }
  }
}
function apply() {
  if (!proposal.value || stale.value || props.disabled || loading.value || !differences.value.length) return;
  if (!props.applyUnit(proposal.value.after)) {
    error.value = '页面审核中有待应用的人工修正，或当前草稿不可编辑。请回到页面核对后再应用。';
    return;
  }
  proposal.value = null;
  error.value = '';
  applied.value = true;
}
watch(scopeKey, clear, { flush: 'sync' });
watch(taskKey, clearConversation, { flush: 'sync' });
watch(() => props.visible, visible => {
  if (visible) return;
  pending?.abort();
  pending = null;
  loading.value = false;
});
onBeforeUnmount(clear);
defineExpose({ open });
</script>

<style scoped>
.agent-chat { display: grid; gap: 12px; }
.agent-chat p, .agent-chat h3 { margin: 0; overflow-wrap: anywhere; }
.agent-chat-messages { max-height: 30vh; overflow-y: auto; margin: 0; padding: 0; list-style: none; }
.agent-chat-messages li { padding: 10px; background: var(--admin-bg-page); white-space: pre-wrap; }
.agent-chat-messages li + li { margin-top: 6px; }
.agent-chat-input { display: grid; gap: 8px; justify-items: end; }
.agent-chat-input .el-textarea { width: 100%; }
.agent-chat > .el-button { justify-self: start; }
</style>
