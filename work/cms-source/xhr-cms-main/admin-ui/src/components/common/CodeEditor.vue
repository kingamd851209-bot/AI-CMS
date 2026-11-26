<template>
  <Teleport to="body" :disabled="!isFullscreen">
    <div class="code-editor" :class="{ 'is-fullscreen': isFullscreen }">
      <div class="code-editor__header">
        <span class="code-editor__title">{{ title }}</span>
        <div class="code-editor__actions">
          <el-button size="small" link @click="copyCode">
            <el-icon><CopyDocument /></el-icon>
            复制
          </el-button>
          <el-button size="small" link @click="toggleFullscreen">
            <el-icon v-if="isFullscreen"><Close /></el-icon>
            <el-icon v-else><FullScreen /></el-icon>
            {{ isFullscreen ? '退出全屏' : '全屏' }}
          </el-button>
        </div>
      </div>
      <div class="code-editor__body">
        <div class="code-editor__lines" ref="linesRef">
          <div v-for="(line, index) in lines" :key="index" class="code-editor__line">
            {{ index + 1 }}
          </div>
        </div>
        <div class="code-editor__code-wrapper">
          <textarea
            ref="textareaRef"
            v-model="localValue"
            class="code-editor__textarea"
            :placeholder="placeholder"
            :readonly="readonly"
            @input="onInput"
            @scroll="syncScroll"
          />
          <pre class="code-editor__highlight" ref="highlightRef"><code v-html="highlightedCode"></code></pre>
        </div>
      </div>
      <div v-if="showPreview" class="code-editor__footer">
        <el-button type="primary" @click="previewTemplate">
          <el-icon><View /></el-icon>
          预览模板效果
        </el-button>
      </div>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
import { ref, computed, watch, nextTick, onMounted, onUnmounted } from 'vue';
import { ElMessage } from 'element-plus';
import { CopyDocument, FullScreen, Close, View } from '@element-plus/icons-vue';

const props = defineProps<{
  modelValue: string;
  title?: string;
  placeholder?: string;
  readonly?: boolean;
  showPreview?: boolean;
}>();

const emit = defineEmits<{
  (e: 'update:modelValue', value: string): void;
  (e: 'preview'): void;
}>();

const textareaRef = ref<HTMLTextAreaElement | null>(null);
const highlightRef = ref<HTMLPreElement | null>(null);
const linesRef = ref<HTMLDivElement | null>(null);
const isFullscreen = ref(false);
const localValue = ref(props.modelValue);

watch(() => props.modelValue, (val) => {
  localValue.value = val;
});

const lines = computed(() => {
  return (localValue.value || '').split('\n');
});

function onInput() {
  emit('update:modelValue', localValue.value);
}

function syncScroll() {
  if (textareaRef.value && highlightRef.value && linesRef.value) {
    const { scrollTop, scrollLeft } = textareaRef.value;
    highlightRef.value.scrollTop = scrollTop;
    highlightRef.value.scrollLeft = scrollLeft;
    linesRef.value.scrollTop = scrollTop;
  }
}

// HTML 语法高亮
function highlightHtml(code: string): string {
  if (!code) return '';

  // 转义 HTML 特殊字符
  let escaped = code
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;');

  // 高亮 HTML 注释
  escaped = escaped.replace(
    /(&lt;!--[\s\S]*?--&gt;)/g,
    '<span class="hl-comment">$1</span>'
  );

  // 高亮 DOCTYPE
  escaped = escaped.replace(
    /(&lt;!DOCTYPE[^&]*&gt;)/gi,
    '<span class="hl-doctype">$1</span>'
  );

  // 高亮标签名（不在 span 标签内）
  escaped = escaped.replace(
    /&lt;(\/?)([\w-]+)/g,
    '&lt;$1<span class="hl-tag">$2</span>'
  );

  // 高亮 th: 属性（优先处理，避免被普通属性匹配）
  escaped = escaped.replace(
    /\s(th:[\w-]+)(=)/g,
    ' <span class="hl-th-attr">$1</span>$2'
  );

  // 高亮普通属性名（排除已处理的 th: 属性和 span 标签属性）
  escaped = escaped.replace(
    /\s([\w-]+)(=)(?=")/g,
    ' <span class="hl-attr">$1</span>$2'
  );

  // 高亮属性值（字符串）
  escaped = escaped.replace(
    /="([^"]*)"/g,
    '="<span class="hl-string">$1</span>"'
  );

  // 高亮 Thymeleaf 表达式
  escaped = escaped.replace(
    /(\$\{[^}]+\})/g,
    '<span class="hl-thymeleaf">$1</span>'
  );
  escaped = escaped.replace(
    /(@\{[^}]+\})/g,
    '<span class="hl-thymeleaf">$1</span>'
  );

  return escaped;
}

const highlightedCode = computed(() => {
  return highlightHtml(localValue.value || '');
});

async function copyCode() {
  try {
    await navigator.clipboard.writeText(localValue.value);
    ElMessage.success('已复制到剪贴板');
  } catch {
    ElMessage.error('复制失败');
  }
}

function toggleFullscreen() {
  isFullscreen.value = !isFullscreen.value;
}

function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Escape' && isFullscreen.value) {
    isFullscreen.value = false;
  }
}

onMounted(() => {
  document.addEventListener('keydown', onKeydown);
});

onUnmounted(() => {
  document.removeEventListener('keydown', onKeydown);
});

function previewTemplate() {
  emit('preview');
}
</script>

<style scoped>
.code-editor {
  display: flex;
  flex-direction: column;
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  background: #1e1e1e;
  overflow: hidden;
}

.code-editor.is-fullscreen {
  position: fixed;
  inset: 0;
  z-index: 3000;
  width: 100vw;
  height: 100vh;
  border-radius: 0;
}

.code-editor__header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px 16px;
  background: #252526;
  border-bottom: 1px solid #3c3c3c;
  color: #cccccc;
  font-size: 14px;
  font-weight: 500;
}

.code-editor__title {
  flex: 1;
}

.code-editor__actions {
  display: flex;
  gap: 8px;
}

.code-editor__actions .el-button {
  color: #cccccc;
}

.code-editor__actions .el-button:hover {
  color: #fff;
}

.code-editor__body {
  display: flex;
  flex: 1;
  min-height: 200px;
  overflow: hidden;
}

.code-editor.is-fullscreen .code-editor__body {
  height: calc(100vh - 100px);
}

.code-editor__lines {
  padding: 12px 8px;
  background: #1e1e1e;
  color: #858585;
  font-family: 'Consolas', 'Monaco', 'Courier New', monospace;
  font-size: 13px;
  line-height: 1.5;
  text-align: right;
  user-select: none;
  border-right: 1px solid #3c3c3c;
  overflow: hidden;
}

.code-editor__line {
  padding: 0 4px;
  min-width: 24px;
}

.code-editor__code-wrapper {
  position: relative;
  flex: 1;
  overflow: hidden;
}

.code-editor__textarea {
  position: absolute;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
  padding: 12px;
  background: transparent;
  color: transparent;
  caret-color: #d4d4d4;
  font-family: 'Consolas', 'Monaco', 'Courier New', monospace;
  font-size: 13px;
  line-height: 1.5;
  border: none;
  outline: none;
  resize: none;
  white-space: pre;
  tab-size: 2;
  z-index: 2;
}

.code-editor__textarea::selection {
  background: rgba(255, 255, 255, 0.2);
}

.code-editor__highlight {
  position: absolute;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
  padding: 12px;
  margin: 0;
  background: #1e1e1e;
  color: #d4d4d4;
  font-family: 'Consolas', 'Monaco', 'Courier New', monospace;
  font-size: 13px;
  line-height: 1.5;
  white-space: pre;
  tab-size: 2;
  overflow: auto;
  pointer-events: none;
  z-index: 1;
}

.code-editor__highlight code {
  background: transparent;
  color: inherit;
}

.code-editor__footer {
  padding: 12px 16px;
  background: #252526;
  border-top: 1px solid #3c3c3c;
  display: flex;
  justify-content: flex-end;
}

/* 语法高亮颜色 */
:deep(.hl-comment) {
  color: #6a9955;
}

:deep(.hl-doctype) {
  color: #569cd6;
}

:deep(.hl-tag) {
  color: #569cd6;
}

:deep(.hl-attr) {
  color: #9cdcfe;
}

:deep(.hl-string) {
  color: #ce9178;
}

:deep(.hl-thymeleaf) {
  color: #dcdcaa;
}

:deep(.hl-th-attr) {
  color: #4ec9b0;
}
</style>
