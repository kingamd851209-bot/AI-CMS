<template>
  <section class="cms-page site-logs">
    <header class="cms-page__hero">
      <div>
        <p class="admin-eyebrow">站点服务</p>
        <h1>站点日志</h1>
        <p>查看当前站点的日志文件，支持实时滚动查看。</p>
      </div>
      <div class="site-logs__hero-tools">
        <div v-if="selectedSite" class="site-logs__site-context">
          <span>{{ selectedSite.name || '未命名站点' }}</span>
          <em>{{ selectedSite.code || '未设置编码' }}</em>
        </div>
        <div class="cms-page__actions">
          <el-button :loading="loading" @click="refreshFiles">刷新</el-button>
        </div>
      </div>
    </header>

    <div class="logs-container">
      <!-- 左侧文件列表 -->
      <aside class="logs-sidebar cms-card">
        <div class="sidebar-header">
          <h2>日志文件</h2>
          <span class="file-count">{{ logFiles.length }} 个文件</span>
        </div>

        <el-empty v-if="!selectedSiteId" description="请先选择站点" />
        <el-empty v-else-if="!loading && logFiles.length === 0" description="暂无日志文件" />

        <div v-else class="file-list">
          <div
            v-for="file in logFiles"
            :key="file.name"
            class="file-item"
            role="button"
            tabindex="0"
            :aria-pressed="selectedFile === file.name"
            :class="{ 'is-active': selectedFile === file.name }"
            @click="selectFile(file.name)"
            @keydown.enter="selectFile(file.name)"
            @keydown.space.prevent="selectFile(file.name)"
          >
            <el-icon><Document /></el-icon>
            <div class="file-info">
              <span class="file-name">{{ file.name }}</span>
              <span class="file-meta">{{ formatSize(file.size) }} · {{ formatDate(file.lastModified) }}</span>
            </div>
          </div>
        </div>
      </aside>

      <!-- 右侧内容区域 -->
      <main class="logs-content cms-card">
        <template v-if="selectedFile">
          <div class="content-header">
            <h2>{{ selectedFile }}</h2>
            <div class="content-actions">
              <el-button size="small" @click="loadNewer" :disabled="contentLoading || !hasMoreNewer">
                向上加载更多
              </el-button>
              <el-button size="small" @click="scrollToBottom">滚动到底部</el-button>
            </div>
          </div>

          <div v-if="contentLoading" class="content-loading">
            <el-icon class="is-loading"><Loading /></el-icon>
            <span>加载中...</span>
          </div>

          <div v-else class="log-editor">
            <codemirror
              v-model="logContent"
              :style="{ height: '100%', fontSize: '13px' }"
              :extensions="editorExtensions"
              :disabled="true"
            />
          </div>
        </template>

        <el-empty v-else-if="!selectedSiteId" description="请先选择站点" />
        <el-empty v-else description="请从左侧选择日志文件" />
      </main>
    </div>
  </section>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue';
import { Document, Loading } from '@element-plus/icons-vue';
import { http } from '@/api/http';
import { useSiteContextStore } from '@/stores/siteContext';
import { Codemirror } from 'vue-codemirror';
import { oneDark } from '@codemirror/theme-one-dark';
import { EditorView } from '@codemirror/view';

interface LogFileView {
  name: string;
  size: number;
  lastModified: number;
}

interface LogContentView {
  filename: string;
  fileSize: number;
  lastModified: number;
  lines: string[];
  nextOffset: number;
}

const siteContext = useSiteContextStore();
const logFiles = ref<LogFileView[]>([]);
const selectedFile = ref<string | null>(null);
const logLines = ref<string[]>([]);
const loading = ref(false);
const contentLoading = ref(false);
const currentOffset = ref(0);
const hasMoreNewer = ref(false);

const editorExtensions = [oneDark, EditorView.editable.of(false)];
const logContent = computed(() => logLines.value.join('\n'));

const selectedSiteId = computed(() => siteContext.currentSite?.id ?? null);

const selectedSite = computed(() => siteContext.currentSite ?? null);

watch(() => selectedSiteId.value, (newId, oldId) => {
  if (newId && newId !== oldId) {
    selectedFile.value = null;
    logLines.value = [];
    currentOffset.value = 0;
    hasMoreNewer.value = false;
    refreshFiles();
  }
});

onMounted(() => {
  if (selectedSiteId.value) {
    refreshFiles();
  }
});

async function refreshFiles() {
  if (!selectedSiteId.value) return;
  loading.value = true;
  try {
    const files = await http.get<LogFileView[]>(`/api/admin/v2/sites/${selectedSiteId.value}/logs`);
    logFiles.value = files;
    if (files.length > 0 && !selectedFile.value) {
      selectFile(files[0].name);
    } else if (files.length === 0) {
      selectedFile.value = null;
      logLines.value = [];
    }
  } catch (error) {
    console.error('加载日志文件列表失败', error);
    logFiles.value = [];
  } finally {
    loading.value = false;
  }
}

async function selectFile(filename: string) {
  if (selectedFile.value === filename) return;
  selectedFile.value = filename;
  currentOffset.value = 0;
  logLines.value = [];
  hasMoreNewer.value = false;
  await loadContent();
}

async function loadContent() {
  if (!selectedSiteId.value || !selectedFile.value) return;
  contentLoading.value = true;
  try {
    const data = await http.get<LogContentView>(
      `/api/admin/v2/sites/${selectedSiteId.value}/logs/${encodeURIComponent(selectedFile.value)}`,
      { params: { lines: 100, offset: currentOffset.value } }
    );
    logLines.value = data.lines;
    currentOffset.value = data.nextOffset;
    hasMoreNewer.value = currentOffset.value > 0;

    setTimeout(() => scrollToBottom(), 100);
  } catch (error) {
    console.error('加载日志内容失败', error);
  } finally {
    contentLoading.value = false;
  }
}

async function loadNewer() {
  if (!selectedSiteId.value || !selectedFile.value || currentOffset.value <= 0) return;
  contentLoading.value = true;
  try {
    const data = await http.get<LogContentView>(
      `/api/admin/v2/sites/${selectedSiteId.value}/logs/${encodeURIComponent(selectedFile.value)}`,
      { params: { lines: 100, offset: currentOffset.value } }
    );
    logLines.value = [...data.lines, ...logLines.value];
    currentOffset.value = data.nextOffset;
    hasMoreNewer.value = currentOffset.value > 0;
  } catch (error) {
    console.error('加载更多日志失败', error);
  } finally {
    contentLoading.value = false;
  }
}

function scrollToBottom() {
  // CodeMirror 编辑器自动滚动到底部
  const cmDom = document.querySelector('.log-editor .cm-editor') as HTMLElement | null;
  if (cmDom) {
    const scroller = cmDom.querySelector('.cm-scroller') as HTMLElement | null;
    if (scroller) {
      scroller.scrollTop = scroller.scrollHeight;
    }
  }
}

function formatSize(bytes: number): string {
  if (bytes < 1024) return bytes + ' B';
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
  return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
}

function formatDate(timestamp: number): string {
  const date = new Date(timestamp);
  return date.toLocaleString('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit'
  });
}
</script>

<style scoped>
.site-logs {
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: var(--admin-card-gap);
  height: 100%;
}

.cms-page__hero {
  border: var(--admin-card-border);
  border-radius: var(--admin-card-radius);
  background: var(--admin-surface);
  box-shadow: var(--admin-shadow-card);
  display: flex;
  justify-content: space-between;
  gap: 18px;
  padding: var(--admin-card-padding-roomy);
}

.cms-page__hero h1 {
  margin: 0;
  font-size: 22px;
  font-weight: 600;
}

.cms-page__hero p {
  margin: 0;
  color: var(--admin-text-muted);
}

.admin-eyebrow {
  margin: 0 0 4px;
  font-size: 12px;
  font-weight: 500;
  color: var(--admin-text-muted);
  text-transform: uppercase;
  letter-spacing: 0.5px;
}

.cms-page__actions {
  display: flex;
  align-items: center;
  gap: 12px;
}

.site-logs__hero-tools {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 12px;
}

.site-logs__site-context {
  align-items: center;
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 8px;
}

.site-logs__site-context span {
  color: var(--admin-text);
  font-weight: 700;
}

.site-logs__site-context em {
  background: #f8fafc;
  border: 1px solid var(--admin-border);
  border-radius: 6px;
  color: var(--admin-text-muted);
  font-size: 13px;
  font-style: normal;
  font-weight: 700;
  padding: 4px 8px;
}

.logs-container {
  display: flex;
  gap: 20px;
  flex: 1;
  min-height: 0;
}

.logs-sidebar {
  width: 300px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
}

.sidebar-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--admin-border);
  margin-bottom: 12px;
}

.sidebar-header h2 {
  margin: 0;
  font-size: 16px;
}

.file-count {
  font-size: 12px;
  color: var(--admin-text-muted);
}

.file-list {
  flex: 1;
  overflow-y: auto;
}

.file-item {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 10px;
  border-radius: 6px;
  cursor: pointer;
  transition: background-color 0.2s;
}

.file-item:hover {
  background-color: var(--admin-bg-hover);
}

.file-item.is-active {
  background-color: var(--el-color-primary-light-9);
}

.file-info {
  display: flex;
  flex-direction: column;
  gap: 2px;
  flex: 1;
  min-width: 0;
}

.file-name {
  font-size: 14px;
  word-break: break-all;
}

.file-meta {
  font-size: 12px;
  color: var(--admin-text-muted);
}

.logs-content {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.content-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--admin-border);
  margin-bottom: 12px;
}

.content-header h2 {
  margin: 0;
  font-size: 16px;
}

.content-actions {
  display: flex;
  gap: 8px;
}

.content-loading {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 10px;
  color: var(--admin-text-muted);
}

.log-content {
  flex: 1;
  overflow: auto;
  background: #1e1e1e;
  border-radius: 6px;
  padding: 16px;
}

.log-editor {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.log-editor :deep(.cm-editor) {
  height: 100%;
  flex: 1;
}

.log-editor :deep(.cm-scroller) {
  overflow: auto;
}

.cms-card {
  border: var(--admin-card-border);
  border-radius: var(--admin-card-radius);
  background: var(--admin-surface);
  padding: var(--admin-card-padding-compact);
}

.file-item:focus-visible { outline: 2px solid var(--admin-accent); outline-offset: -2px; }
.content-header { flex-wrap: wrap; gap: 12px; }
.content-header h2 { overflow-wrap: anywhere; min-width: 0; }
.content-actions { flex-wrap: wrap; }
@media (max-width: 960px) {
  .logs-container { flex-direction: column; }
  .logs-sidebar { width: 100%; max-height: 260px; }
  .logs-content { min-height: 360px; }
}
</style>
