<template>
  <Teleport to="body" :disabled="!isFullscreen">
    <div class="codemirror-editor" :class="{ 'is-fullscreen': isFullscreen }">
      <div class="codemirror-editor__header">
        <span class="codemirror-editor__title">{{ title }}</span>
        <div class="codemirror-editor__actions">
          <el-button v-if="showPreview" size="small" link type="success" @click="previewCode" :loading="previewing">
            <el-icon><View /></el-icon>
            预览
          </el-button>
          <el-button size="small" link @click="copyCode">
            <el-icon><CopyDocument /></el-icon>
            复制
          </el-button>
          <el-button size="small" link @click="downloadCode">
            <el-icon><Download /></el-icon>
            下载
          </el-button>
          <el-button v-if="showSave" size="small" link type="primary" @click="saveCode" :loading="saving">
            <el-icon><FolderChecked /></el-icon>
            保存
          </el-button>
          <el-button size="small" link @click="toggleFullscreen">
            <el-icon v-if="isFullscreen"><Close /></el-icon>
            <el-icon v-else><FullScreen /></el-icon>
            {{ isFullscreen ? '退出' : '全屏' }}
          </el-button>
        </div>
      </div>
      <div class="codemirror-editor__body">
        <codemirror
          v-model="localValue"
          :style="editorStyle"
          :extensions="extensions"
          @change="onChange"
        />
      </div>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
import { ref, computed, watch, onMounted, onUnmounted } from 'vue';
import { Codemirror } from 'vue-codemirror';
import { html } from '@codemirror/lang-html';
import { oneDark } from '@codemirror/theme-one-dark';
import { ElMessage } from 'element-plus';
import { CopyDocument, FullScreen, Close, FolderChecked, View, Download } from '@element-plus/icons-vue';

const props = defineProps<{
  modelValue: string;
  title?: string;
  showSave?: boolean;
  showPreview?: boolean;
  readonly?: boolean;
}>();

const emit = defineEmits<{
  (e: 'update:modelValue', value: string): void;
  (e: 'save', value: string): void;
  (e: 'preview', value: string): void;
}>();

const localValue = ref(props.modelValue);
const isFullscreen = ref(false);
const saving = ref(false);
const previewing = ref(false);

watch(() => props.modelValue, (val) => {
  localValue.value = val;
});

const extensions = [html(), oneDark];

const editorStyle = computed(() => ({
  height: '100%',
  fontSize: '13px',
}));

function onChange(value: string) {
  emit('update:modelValue', value);
}

async function copyCode() {
  try {
    await navigator.clipboard.writeText(localValue.value);
    ElMessage.success('已复制到剪贴板');
  } catch {
    ElMessage.error('复制失败');
  }
}

function downloadCode() {
  const name = (props.title || '').split(/[\\/]/).pop()?.trim() || 'template.html';
  const blob = new Blob([localValue.value ?? ''], { type: 'text/plain;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = name;
  document.body.appendChild(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(url);
}

async function saveCode() {
  saving.value = true;
  try {
    emit('save', localValue.value);
  } finally {
    saving.value = false;
  }
}

async function previewCode() {
  previewing.value = true;
  try {
    emit('preview', localValue.value);
  } finally {
    previewing.value = false;
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
</script>

<style scoped>
.codemirror-editor {
  display: flex;
  flex-direction: column;
  height: 100%;
  width: 100%;
  max-width: 100%;
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  overflow: hidden;
  background: #1e1e1e;
}

.codemirror-editor.is-fullscreen {
  position: fixed;
  inset: 0;
  z-index: 3000;
  width: 100vw;
  height: 100vh;
  border: none;
  border-radius: 0;
}

.codemirror-editor__header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px 16px;
  background: #252526;
  border-bottom: 1px solid #3c3c3c;
  color: #cccccc;
  font-size: 14px;
  font-weight: 500;
  flex-shrink: 0;
}

.codemirror-editor__title {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.codemirror-editor__actions {
  display: flex;
  gap: 8px;
  flex-shrink: 0;
}

.codemirror-editor__actions .el-button {
  color: #cccccc;
}

.codemirror-editor__actions .el-button:hover {
  color: #fff;
}

.codemirror-editor__body {
  flex: 1;
  overflow: hidden;
  width: 100%;
  position: relative;
}

.codemirror-editor__body :deep(.cm-editor) {
  height: 100%;
  width: 100% !important;
  max-width: 100% !important;
  position: relative;
  overflow: hidden;
}

.codemirror-editor__body :deep(.cm-scroller) {
  font-family: 'Consolas', 'Monaco', 'Courier New', monospace;
  overflow: auto !important;
  overflow-x: auto !important;
  overflow-y: auto !important;
  width: 100% !important;
  max-width: 100% !important;
  box-sizing: border-box;
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
}

.codemirror-editor__body :deep(.cm-content) {
  min-width: max-content;
  box-sizing: border-box;
}

.codemirror-editor__body :deep(.cm-line) {
  white-space: pre;
}
</style>
