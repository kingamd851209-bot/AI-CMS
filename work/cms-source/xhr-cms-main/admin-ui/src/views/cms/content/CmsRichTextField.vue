<template>
  <div
    class="cms-rich-text"
    :data-testid="`rich-text-field-${field.fieldName}`"
    :data-editor-base="editorBaseUrl"
    :data-editor-state="editorState"
  >
    <div v-if="editorReady" class="cms-rich-text__resources">
      <el-button size="small" @click="resourcePickerOpen = true">插入资源</el-button>
    </div>
    <ResourcePickerDialog v-model="resourcePickerOpen" type="all" multiple @select="insertResources" />
    <textarea
      v-if="shouldUseFallbackEditor"
      class="cms-rich-text__source"
      :value="htmlValue"
      :data-testid="`field-control-${field.fieldName}`"
      @input="onFallbackInput"
      @blur="onFallbackBlur"
    />
    <div v-else class="cms-rich-text__frame">
      <div
        :id="editorId"
        ref="editorHostRef"
        class="cms-rich-text__ueditor"
        @pointerdown.capture="attachEditorLayer"
        @focusin.capture="attachEditorLayer"
        :data-testid="`field-control-${field.fieldName}`"
      />
      <p v-if="editorState === 'loading'" class="cms-rich-text__loading">正在加载富文本编辑器...</p>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ElMessage } from 'element-plus';
import { useResourceReferences, transformResourceHtml } from '@/api/resourceReferences';
import ResourcePickerDialog from '@/components/resources/ResourcePickerDialog.vue';
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';

interface FieldOption {
  fieldName: string;
  fromName?: string;
  fieldType?: string;
  title?: string;
}

interface UEditorInstance {
  ready(callback: () => void): void;
  setContent(value: string, isAppendTo?: boolean): void;
  getContent(): string;
  execCommand(command: string, value: string): void;
  addListener(event: string, callback: () => void): void;
  removeListener?(event: string, callback: () => void): void;
  destroy(): void;
}

interface UEditorGlobal {
  ui?: { uiUtils?: { getFixedLayer(): HTMLElement } };
  getEditor(id: string, options?: Record<string, unknown>): UEditorInstance;
  delEditor?(id: string): void;
  I18N?: Record<string, unknown>;
}

declare global {
  interface Window {
    UEDITOR_HOME_URL?: string;
    UE?: UEditorGlobal;
    UEDITOR_CONFIG?: { toolbars?: string[][] };
  }
}

const props = defineProps<{
  field: FieldOption;
  modelValue: string | number | null | undefined;
}>();

const emit = defineEmits<{
  'update:modelValue': [value: string];
  blur: [];
}>();

const editorBaseUrl = `${import.meta.env.BASE_URL}static/ecj27zyqedit/`;
let ueditorAssetPromise: Promise<void> | null = null;

const editorHostRef = ref<HTMLElement | null>(null);
const editor = ref<UEditorInstance | null>(null);
const editorReady = ref(false);
const resourcePickerOpen = ref(false);
const editorState = ref<'fallback' | 'loading' | 'ready' | 'failed'>('loading');
const hasAvailableUeditor = ref(hasUeditor());
const editorId = `cms-ueditor-${sanitizeIdPart(props.field.fieldName)}-${Math.random().toString(36).slice(2, 9)}`;
const isTestRuntime = import.meta.env.MODE === 'test';
let disposed = false;

const { context: resourceContext, refresh: refreshResources } = useResourceReferences();
const htmlValue = computed(() => props.modelValue == null ? '' : String(props.modelValue));
const shouldUseFallbackEditor = computed(() => editorState.value === 'failed' || (!hasAvailableUeditor.value && isTestRuntime));

watch(
  () => transformResourceHtml(htmlValue.value, resourceContext.value),
  (value) => {
    if (!editorReady.value || !editor.value) {
      return;
    }
    try {
      if (editor.value.getContent() !== value) {
        editor.value.setContent(value, false);
      }
    } catch {
      editorState.value = 'failed';
    }
  }
);

onMounted(async () => {
  disposed = false;
  window.UEDITOR_HOME_URL = editorBaseUrl;

  if (shouldUseFallbackEditor.value) {
    editorState.value = 'fallback';
    return;
  }

  try {
    await loadUeditorAssets();
    hasAvailableUeditor.value = hasUeditor();
    if (disposed || !window.UE?.getEditor || !editorHostRef.value) {
      editorState.value = 'failed';
      return;
    }
    initEditor(window.UE);
  } catch {
    editorState.value = 'failed';
  }
});

onBeforeUnmount(() => {
  disposed = true;
  if (ownedLayer && layerParent !== document.body && ownedLayer.parentElement === layerParent) {
    document.body.appendChild(ownedLayer);
  }
  if (!editor.value) {
    return;
  }
  try {
    editor.value.removeListener?.('contentChange', emitEditorContent);
    editor.value.removeListener?.('blur', emitEditorBlur);
    window.UE?.delEditor?.(editorId);
  } catch {
    // UEditor 1.x can throw while tearing down already-detached DOM. Vue unmount must stay safe.
  } finally {
    editor.value = null;
    editorReady.value = false;
  }
});

// UEditor shares one floating layer between editors. Keep the active layer
// inside its drawer so raising or reopening the drawer also raises its dialogs.
let ownedLayer: HTMLElement | null = null;
let layerParent: HTMLElement | null = null;

function attachEditorLayer() {
  const host = editorHostRef.value;
  const layer = window.UE?.ui?.uiUtils?.getFixedLayer();
  if (!host || !layer) return;
  const parent = host.closest<HTMLElement>('.el-drawer, .el-dialog') ?? document.body;
  if (layer.parentElement !== parent) parent.appendChild(layer);
  ownedLayer = layer;
  layerParent = parent;
}

function initEditor(UE: UEditorGlobal) {
  const overlay = editorHostRef.value?.closest('.el-overlay');
  const overlayZIndex = overlay ? Number(getComputedStyle(overlay).zIndex) : 0;
  editor.value = UE.getEditor(editorId, {
    // UEditor uses different offsets for menus and modal dialogs above this base.
    ...(overlayZIndex > 0 ? { zIndex: overlayZIndex } : {}),
    serverUrl: '/api/admin/config',
    fileIconPath: new URL(`${import.meta.env.BASE_URL}static/upload/fileTypeImages/`, window.location.href).href,
    toolbars: window.UEDITOR_CONFIG?.toolbars?.map((toolbar) => toolbar.filter((button) => !['scrawl', 'snapscreen', 'wordimage'].includes(button))),
    catchRemoteImageEnable: false,
    initialFrameWidth: '100%',
    initialFrameHeight: 520,
    autoHeightEnabled: false,
    scaleEnabled: true
  });
  editor.value.ready(() => {
    if (disposed || !editor.value) {
      return;
    }
    attachEditorLayer();
    editor.value.setContent(transformResourceHtml(htmlValue.value, resourceContext.value), false);
    editor.value.addListener('contentChange', emitEditorContent);
    editor.value.addListener('blur', emitEditorBlur);
    editorReady.value = true;
    editorState.value = 'ready';
  });
}

async function insertResources(value: string | string[]) {
  if (!editor.value) return;
  await refreshResources();
  const urls = Array.isArray(value) ? value : [value];
  if (urls.some((url) => url.startsWith('ui:/') && !resourceContext.value.ui || url.startsWith('assets:/upload/') && !resourceContext.value.upload)) {
    ElMessage.warning('资源访问地址未就绪，请配置资源域名或启动本地站点/UI服务');
    return;
  }
  const escape = (text: string) => text.replace(/[&<>"']/g, (char) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[char] ?? char));
  const html = urls.filter((url) => url.startsWith('assets:/') || url.startsWith('ui:/')).map((url) => {
    const safe = escape(url);
    if (/\.(png|jpe?g|gif|webp|svg|bmp)$/i.test(url)) return `<img src="${safe}" alt="" />`;
    if (/\.(mp4|webm)$/i.test(url)) return `<video controls src="${safe}"></video>`;
    return `<a href="${safe}" target="_blank" rel="noopener">${escape(url.split('/').at(-1) ?? '附件')}</a>`;
  }).join('');
  editor.value.execCommand('inserthtml', transformResourceHtml(html, resourceContext.value));
  emitEditorContent();
  resourcePickerOpen.value = false;
}

function emitEditorContent() {
  if (!editor.value) {
    return;
  }
  emit('update:modelValue', transformResourceHtml(editor.value.getContent(), resourceContext.value, true));
}

function emitEditorBlur() {
  emitEditorContent();
  emit('blur');
}

function onFallbackInput(event: Event) {
  emit('update:modelValue', (event.target as HTMLTextAreaElement).value);
}

function onFallbackBlur() {
  emit('blur');
}

async function loadUeditorAssets() {
  window.UEDITOR_HOME_URL = editorBaseUrl;
  if (ueditorAssetPromise) {
    await ueditorAssetPromise;
    return;
  }
  if (hasUeditorRuntimeReady()) {
    return;
  }
  ueditorAssetPromise = loadScriptsSequentially([
    `${editorBaseUrl}ueditor.config.js`,
    `${editorBaseUrl}ueditor.all.js`,
    `${editorBaseUrl}lang/zh-cn/zh-cn.js`
  ]);
  await ueditorAssetPromise;
}

async function loadScriptsSequentially(urls: string[]) {
  for (const url of urls) {
    await loadScriptOnce(url);
  }
}

function loadScriptOnce(src: string) {
  const existing = document.querySelector<HTMLScriptElement>(`script[data-cms-ueditor-src="${src}"]`);
  if (existing?.dataset.cmsUeditorLoaded === 'true') {
    return Promise.resolve();
  }
  if (existing) {
    return new Promise<void>((resolve, reject) => {
      existing.addEventListener('load', () => resolve(), { once: true });
      existing.addEventListener('error', () => reject(new Error(`Failed to load ${src}`)), { once: true });
    });
  }

  return new Promise<void>((resolve, reject) => {
    const script = document.createElement('script');
    script.src = src;
    script.async = false;
    script.dataset.cmsUeditorSrc = src;
    script.onload = () => {
      script.dataset.cmsUeditorLoaded = 'true';
      resolve();
    };
    script.onerror = () => reject(new Error(`Failed to load ${src}`));
    document.head.appendChild(script);
  });
}

function hasUeditor() {
  return typeof window !== 'undefined' && typeof window.UE?.getEditor === 'function';
}

function hasUeditorRuntimeReady() {
  return hasUeditor() && (isTestRuntime || Boolean(window.UE?.I18N?.['zh-cn']));
}

function sanitizeIdPart(value: string) {
  return value.replace(/[^a-zA-Z0-9_-]/g, '_') || 'field';
}
</script>

<style scoped>
.cms-rich-text {
  display: grid;
  gap: 8px;
  min-width: 0;
}

.cms-rich-text__frame {
  position: relative;
  min-width: 0;
}

.cms-rich-text__ueditor {
  width: 100%;
  min-height: 520px;
}

.cms-rich-text__source {
  box-sizing: border-box;
  width: 100%;
  min-height: 520px;
  border: 1px solid var(--admin-border-subtle);
  border-radius: var(--admin-radius-md);
  padding: 14px 16px;
  background: var(--admin-bg-surface);
  color: var(--admin-text-primary);
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 13px;
  line-height: 1.7;
  resize: vertical;
}

.cms-rich-text__loading {
  position: absolute;
  inset: 12px auto auto 12px;
  margin: 0;
  padding: 6px 10px;
  border: 1px solid var(--admin-border-subtle);
  border-radius: var(--admin-radius-sm);
  background: var(--admin-bg-surface);
  color: var(--admin-text-muted);
  font-size: 12px;
}
</style>
