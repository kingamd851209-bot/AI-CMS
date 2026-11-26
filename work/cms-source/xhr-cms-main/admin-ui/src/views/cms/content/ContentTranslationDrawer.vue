<template>
  <el-drawer
    :model-value="visible"
    :with-header="false"
    direction="rtl"
    class="content-translation-drawer"
    size="min(1680px, calc(100vw - var(--admin-drawer-mobile-gutter)))"
    @update:model-value="emit('update:visible', $event)"
  >
    <div class="ct-drawer">
      <!-- 顶部工具条 -->
      <header class="ct-drawer__topbar">
        <div class="ct-drawer__title">
          <h2>内容翻译</h2>
          <p>{{ recordId }}</p>
        </div>
        <div class="ct-drawer__toolbar">
          <el-tag size="small">LLM翻译服务</el-tag>
          <el-tag v-if="aiServiceStatus?.status === 'running'" type="success" size="small">运行中</el-tag>
          <el-tag v-else-if="aiServiceStatus?.status === 'starting'" type="warning" size="small">启动中...</el-tag>
          <el-tag v-else-if="aiServiceStatus?.status === 'available'" type="success" size="small">已配置</el-tag>
          <el-tag v-else-if="aiServiceStatus?.status === 'stopped'" type="info" size="small">已停止</el-tag>
          <el-tag v-else-if="aiServiceStatus?.status === 'failed'" type="danger" size="small">启动失败</el-tag>
          <el-tag v-else type="info" size="small">未知状态</el-tag>
          <el-button v-if="streamLog" size="small" @click="streamLogVisible = true">翻译记录</el-button>
          <el-button v-if="prompt" size="small" @click="promptVisible = true">prompt</el-button>
          <el-button v-if="rawResponse" size="small" @click="rawVisible = true">原始响应</el-button>
          <el-button size="small" :loading="aiServiceLoading" @click="refreshAiServiceStatus">刷新状态</el-button>
          <el-button
            v-if="aiServiceStatus?.status === 'stopped' || aiServiceStatus?.status === 'failed'"
            size="small"
            type="primary"
            :loading="aiServiceOperating"
            @click="handleStartAiService"
          >检查配置</el-button>
          <el-button size="small" type="primary" :loading="translating" :disabled="!canTranslate || !targetLanguage" @click="startTranslate">翻译预览</el-button>
        </div>
      </header>

      <!-- 流式日志 -->
      <div v-if="translating" class="ct-drawer__stream">
        <div class="ct-drawer__stream-head">
          <span>翻译记录</span>
          <div class="ct-drawer__stream-actions">
            <el-button size="small" link @click="streamLog = ''">清除</el-button>
          </div>
        </div>
        <p>关闭面板仅停止等待，不会撤销已发出的模型调用。</p>
        <pre class="ct-drawer__stream-body">{{ streamLog || '等待 AI 响应...' }}</pre>
      </div>

      <!-- 双栏 -->
      <section class="ct-drawer__panels">
        <div class="ct-drawer__panel">
          <div class="ct-drawer__panel-head">
            <span>源内容</span>
            <div class="ct-drawer__panel-head-actions">
              <el-button size="small" @click="selectAllFields">全选</el-button>
              <el-button size="small" @click="clearFields">清空</el-button>
              <el-checkbox v-model="hideUntranslatable" size="small">隐藏不可译字段</el-checkbox>
            </div>
          </div>
          <div class="ct-drawer__field-list">
            <div v-for="field in visibleFields" :key="field.fieldName" class="ct-drawer__field">
              <div class="ct-drawer__field-head">
                <el-checkbox
                  v-if="isTranslatable(field)"
                  :model-value="selectedKeys.includes(field.fieldName)"
                  @update:model-value="(v: boolean) => toggleField(field.fieldName, v)"
                >{{ fieldLabel(field) }}</el-checkbox>
                <span v-else class="ct-drawer__field-label">{{ fieldLabel(field) }}</span>
                <el-tag v-if="!isTranslatable(field)" size="small" type="info" effect="plain">不可译</el-tag>
                <code class="ct-drawer__field-key">{{ field.fieldName }}</code>
              </div>
              <div class="ct-drawer__field-value">{{ sourceValue(field.fieldName) || '（空）' }}</div>
            </div>
          </div>
        </div>

        <div class="ct-drawer__panel">
          <div class="ct-drawer__panel-head">
            <span>译文预览（可编辑）</span>
            <el-select v-model="targetLanguage" size="small" placeholder="目标语言" filterable class="ct-drawer__target-lang">
              <el-option v-for="l in languageOptions" :key="l.languageId" :label="l.languageName" :value="l.languageCode" />
            </el-select>
          </div>
          <div class="ct-drawer__field-list">
            <div v-for="field in visibleFields" :key="field.fieldName" class="ct-drawer__field">
              <div class="ct-drawer__field-head">
                <span class="ct-drawer__field-label">{{ fieldLabel(field) }}</span>
                <code class="ct-drawer__field-key">{{ field.fieldName }}</code>
              </div>
              <el-input
                v-if="isTranslatable(field)"
                v-model="translatedValues[field.fieldName]"
                :type="isMultiline(field) ? 'textarea' : 'text'"
                :rows="isMultiline(field) ? 4 : undefined"
                :disabled="!selectedKeys.includes(field.fieldName)"
              />
              <span v-else class="ct-drawer__field-value ct-drawer__field-value--disabled">—</span>
            </div>
          </div>
        </div>
      </section>

      <!-- 底部 -->
      <footer class="ct-drawer__footer">
        <span class="ct-drawer__hint">已选 {{ selectedKeys.length }} 个字段</span>
        <el-button @click="emit('update:visible', false)">{{ translating ? '停止等待并关闭' : '关闭' }}</el-button>
        <el-button type="primary" :loading="saving" :disabled="selectedKeys.length === 0" @click="handleSave">
          使用翻译后的内容保存
        </el-button>
      </footer>
    </div>
  </el-drawer>

  <el-dialog v-model="streamLogVisible" title="翻译记录" width="800px" append-to-body>
    <pre class="ct-drawer__dialog-pre">{{ streamLog || '无日志内容' }}</pre>
  </el-dialog>
  <el-dialog v-model="promptVisible" title="Prompt" width="900px" append-to-body>
    <pre class="ct-drawer__dialog-pre">{{ prompt || '无内容' }}</pre>
  </el-dialog>
  <el-dialog v-model="rawVisible" title="原始响应" width="900px" append-to-body>
    <pre class="ct-drawer__dialog-pre">{{ rawResponse || '无内容' }}</pre>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { ElMessage } from 'element-plus';
import {
  translateContentPreview
} from '@/api/cms';
import {
  getAiStatus,
  type AiModelStatus
} from '@/api/aiGenerationService';

interface FieldOption {
  fieldName: string;
  fromName?: string;
  fieldType?: string;
  title?: string;
  selectQuery?: string;
}

const props = defineProps<{
  visible: boolean;
  saving: boolean;
  menuId: number;
  recordId: number;
  sourceLanguageId: number;
  languageOptions: Array<{ languageId: number; languageCode: string; languageName: string }>;
  fields: FieldOption[];
  sourceTitle: string;
  sourceValues: Record<string, unknown>;
}>();

const emit = defineEmits<{
  'update:visible': [value: boolean];
  save: [payload: { fields: Array<{ fieldKey: string; translatedText: string }> }];
}>();

const TITLE_FIELD: FieldOption = { fieldName: 'title', title: '标题', fieldType: 'text' };
// title 通常是模型字段（模型字段池中 always），仅在缺失时才补充合成标题，避免重复。
const allFields = computed<FieldOption[]>(() =>
  props.fields.some((f) => f.fieldName === 'title') ? props.fields : [TITLE_FIELD, ...props.fields]
);
const hideUntranslatable = ref(false);
const visibleFields = computed<FieldOption[]>(() =>
  hideUntranslatable.value ? allFields.value.filter(isTranslatable) : allFields.value
);

const aiServiceStatus = ref<AiModelStatus | null>(null);
const aiServiceLoading = ref(false);
const aiServiceOperating = ref(false);

const selectedKeys = ref<string[]>([]);
const translatedValues = ref<Record<string, string>>({});
const targetLanguage = ref('');
const translating = ref(false);

const streamLog = ref('');
const streamLogVisible = ref(false);

// 流式日志可能非常长，超出上限时截断更早内容，避免字符串/DOM 过大导致浏览器界面崩溃
const MAX_STREAM_LOG_LENGTH = 200_000;
const STREAM_LOG_TRUNCATE_PREFIX = '…（日志过长，已截断更早内容）…\n';
function capStreamLog(value: string): string {
  if (value.length <= MAX_STREAM_LOG_LENGTH) return value;
  const keep = Math.max(0, MAX_STREAM_LOG_LENGTH - STREAM_LOG_TRUNCATE_PREFIX.length);
  const tail = value.slice(-keep);
  const firstNewline = tail.indexOf('\n');
  return STREAM_LOG_TRUNCATE_PREFIX + (firstNewline >= 0 ? tail.slice(firstNewline + 1) : tail);
}
watch(streamLog, (value) => {
  const capped = capStreamLog(value);
  if (capped !== value) streamLog.value = capped;
});
const prompt = ref('');
const promptVisible = ref(false);
const rawResponse = ref('');
const rawVisible = ref(false);

let abortController: AbortController | null = null;
let translateGeneration = 0;

const canTranslate = computed(
  () => selectedKeys.value.length > 0 && (aiServiceStatus.value?.status === 'running' || aiServiceStatus.value?.status === 'available')
);

function normalizedType(field: FieldOption): string {
  return (field.fieldType ?? '').replace(/[\s_-]/g, '').toLowerCase();
}

function isTranslatable(field: FieldOption): boolean {
  const t = normalizedType(field);
  const nonTranslatable = [
    'image', 'photo', 'picture', 'multiimage', 'multiimages', 'images', 'gallery', 'photos', 'pictures',
    'file', 'attachment', 'download', 'video', 'media',
    'number', 'int', 'integer', 'decimal', 'date', 'datetime', 'time', 'boolean', 'bool'
  ];
  if (nonTranslatable.includes(t)) return false;
  if (['enum', 'select', 'radio', 'checkbox'].includes(t) && field.selectQuery?.startsWith('{$')) return false;
  return true;
}

function isMultiline(field: FieldOption): boolean {
  return ['textarea', 'longtext', 'note', 'multiline', 'content', 'editor', 'html', 'richtext'].includes(normalizedType(field));
}

function fieldLabel(field: FieldOption): string {
  return field.title || field.fromName || field.fieldName;
}

function sourceValue(fieldName: string): string {
  if (fieldName === 'title') return props.sourceTitle ?? '';
  const raw = props.sourceValues[fieldName];
  return raw == null ? '' : String(raw);
}

function translatableKeys(): string[] {
  return allFields.value.filter(isTranslatable).map((f) => f.fieldName);
}

function resetState() {
  selectedKeys.value = translatableKeys();
  translatedValues.value = {};
  targetLanguage.value = '';
  streamLog.value = '';
  prompt.value = '';
  rawResponse.value = '';
  translating.value = false;
  void refreshAiServiceStatus();
}

watch(() => props.visible, (v) => {
  if (v) {
    resetState();
  } else {
    translateGeneration++;
    abortController?.abort();
  }
}, { immediate: true });

function selectAllFields() { selectedKeys.value = translatableKeys(); }
function clearFields() { selectedKeys.value = []; }
function toggleField(fieldName: string, checked: boolean) {
  const set = new Set(selectedKeys.value);
  if (checked) set.add(fieldName); else set.delete(fieldName);
  selectedKeys.value = Array.from(set);
}

async function refreshAiServiceStatus() {
  aiServiceLoading.value = true;
  try {
    aiServiceStatus.value = await getAiStatus('CONTENT_TRANSLATION');
  } catch {
    aiServiceStatus.value = { status: 'failed', url: '', message: '', error: '获取状态失败' };
  } finally {
    aiServiceLoading.value = false;
  }
}


async function handleStartAiService() {
  aiServiceOperating.value = true;
  try {
    aiServiceStatus.value = await getAiStatus('CONTENT_TRANSLATION');
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '启动失败');
  } finally {
    aiServiceOperating.value = false;
  }
}



async function startTranslate() {
  if (!targetLanguage.value || selectedKeys.value.length === 0) return;
  abortController?.abort();
  const controller = new AbortController();
  abortController = controller;
  const generation = ++translateGeneration;
  const isCurrent = () => generation === translateGeneration && !controller.signal.aborted;
  translating.value = true;

  streamLog.value = '[开始翻译...]\n';
  translatedValues.value = {};
  prompt.value = '';
  rawResponse.value = '';
  try {
    const result = await translateContentPreview({
      menuId: props.menuId,
      recordId: props.recordId,
      languageId: props.sourceLanguageId,
      targetLanguage: targetLanguage.value,
      engine: 'SPRING_AI',
      fieldKeys: selectedKeys.value
    });
    if (!isCurrent()) return;
    prompt.value = result.prompt || '';
    rawResponse.value = result.rawResponse || '';
    streamLog.value = `=== Prompt ===\n${result.prompt || ''}\n=== AI 响应内容 ===\n${result.rawResponse || ''}\n`;
    for (const item of result.items) translatedValues.value[item.fieldKey] = item.translatedText || '';
    streamLog.value += '[翻译完成]\n';
  } catch (error) {
    if (!isCurrent()) return;
    streamLog.value += `\n[错误] ${error instanceof Error ? error.message : '翻译失败'}\n`;
    ElMessage.error(error instanceof Error ? error.message : '翻译失败');
  } finally {
    if (isCurrent()) translating.value = false;
  }
}

function handleSave() {
  const fields = selectedKeys.value.map((key) => ({ fieldKey: key, translatedText: translatedValues.value[key] ?? '' }));
  emit('save', { fields });
}
</script>

<style scoped>
.ct-drawer {
  display: grid;
  grid-template-rows: auto auto minmax(0, 1fr) auto;
  height: 100%;
}
.ct-drawer__topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  padding: 12px 20px;
  border-bottom: 1px solid var(--admin-border-subtle);
}
.ct-drawer__title h2 { margin: 0; font-size: var(--admin-font-size-title); }
.ct-drawer__title p { margin: 2px 0 0; color: var(--admin-text-muted); font-size: 12px; }
.ct-drawer__toolbar { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.ct-drawer__engine { width: 170px; }
.ct-drawer__stream {
  margin: 12px 20px 0;
  border: 1px solid var(--el-border-color);
  border-radius: 8px;
  overflow: hidden;
}
.ct-drawer__stream-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px 12px;
  background: var(--el-fill-color-light);
  border-bottom: 1px solid var(--el-border-color);
  font-size: 13px;
  font-weight: 500;
}
.ct-drawer__stream-actions { display: flex; align-items: center; gap: 8px; }
.ct-drawer__stream-body {
  background: #1e1e1e;
  color: #4ade80;
  padding: 12px;
  font-family: monospace;
  font-size: 12px;
  height: 200px;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-all;
  margin: 0;
  line-height: 1.5;
}
.ct-drawer__panels {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
  min-height: 0;
  padding: 12px 20px;
}
.ct-drawer__panel { display: grid; grid-template-rows: auto minmax(0, 1fr); min-height: 0; border: 1px solid var(--admin-border-subtle); border-radius: 8px; }
.ct-drawer__panel-head { display: flex; align-items: center; justify-content: space-between; padding: 8px 12px; border-bottom: 1px solid var(--admin-border-subtle); font-weight: 600; }
.ct-drawer__panel-head-actions { display: flex; align-items: center; gap: 8px; }
.ct-drawer__target-lang { width: 200px; }
.ct-drawer__field-list { overflow: auto; padding: 8px 12px; display: grid; gap: 10px; align-content: start; }
.ct-drawer__field { display: grid; gap: 4px; }
.ct-drawer__field-head { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
.ct-drawer__field-key {
  font-family: 'Consolas', 'Monaco', monospace;
  font-size: 11px;
  color: var(--admin-text-muted);
}
.ct-drawer__field-label { font-size: 13px; font-weight: 600; color: var(--admin-text-secondary); }
.ct-drawer__field-value { font-size: 13px; color: var(--admin-text-muted); word-break: break-all; white-space: pre-wrap; }
.ct-drawer__field-value--disabled { color: var(--admin-text-muted); }
.ct-drawer__footer { display: flex; align-items: center; justify-content: flex-end; gap: 12px; padding: 12px 20px; border-top: 1px solid var(--admin-border-subtle); }
.ct-drawer__hint { margin-right: auto; color: var(--admin-text-muted); font-size: 13px; }
.ct-drawer__dialog-pre { max-height: 60vh; overflow: auto; white-space: pre-wrap; }
</style>
