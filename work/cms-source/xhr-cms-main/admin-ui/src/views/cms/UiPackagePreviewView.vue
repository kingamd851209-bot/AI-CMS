<template>
  <section class="ui-preview" :class="{ 'is-fullscreen': fullscreenPanel }">
    <header v-if="!fullscreenPanel" class="ui-preview__header">
      <div class="ui-preview__file-selector">
        <p class="admin-eyebrow">UI Package 预览</p>
        <div class="ui-preview__select-row">
          <el-select
            v-model="selectedPath"
            filterable
            placeholder="选择文件"
            class="ui-preview__path-select"
            @change="onFileChange"
          >
            <el-option-group label="HTML/ASPX 文件">
              <el-option
                v-for="file in htmlFiles"
                :key="file.relativePath"
                :label="file.relativePath"
                :value="file.relativePath"
              >
                <div class="ui-preview__file-option">
                  <span class="ui-preview__file-icon">📄</span>
                  <span class="ui-preview__file-path">{{ file.relativePath }}</span>
                </div>
              </el-option>
            </el-option-group>
            <el-option-group label="其他资源文件">
              <el-option
                v-for="file in otherFiles"
                :key="file.relativePath"
                :label="file.relativePath"
                :value="file.relativePath"
              >
                <div class="ui-preview__file-option">
                  <span class="ui-preview__file-icon">{{ getFileIcon(file.relativePath) }}</span>
                  <span class="ui-preview__file-path">{{ file.relativePath }}</span>
                </div>
              </el-option>
            </el-option-group>
          </el-select>
        </div>
      </div>
      <div class="ui-preview__actions">
        <el-button @click="router.back()">返回</el-button>
        <el-button v-if="canOpenWorkbench" type="primary" @click="openWorkbench">打开建站引导</el-button>
        <el-tooltip :disabled="Boolean(previewUrl)" content="请从资源库选择页面并启动预览服务后再打开新窗口" placement="bottom">
          <el-button type="primary" :disabled="!previewUrl" @click="openInNewTab">新窗口打开</el-button>
        </el-tooltip>
      </div>
    </header>

    <div v-if="loading" class="ui-preview__loading">
      <el-icon class="is-loading"><Loading /></el-icon>
      <span>加载中...</span>
    </div>

    <div v-else-if="error" class="ui-preview__error">
      <el-icon><WarningFilled /></el-icon>
      <span>{{ error }}</span>
    </div>

    <div v-else class="ui-preview__main">
      <aside v-show="!fullscreenPanel || fullscreenPanel === 'code'" class="ui-preview__code" :class="{ 'is-fullscreen': fullscreenPanel === 'code' }">
        <div class="ui-preview__code-header">
          <span>源代码</span>
          <div class="ui-preview__header-actions">
            <el-button v-if="fullscreenPanel === 'code'" size="small" @click="exitFullscreen">
              <el-icon><Close /></el-icon>
              退出全屏
            </el-button>
            <el-button v-else size="small" @click="toggleFullscreen('code')">
              <el-icon><FullScreen /></el-icon>
              全屏
            </el-button>
            <el-button size="small" @click="copyCode">复制</el-button>
          </div>
        </div>
        <CodemirrorEditor
          v-model="fileContent"
          title=""
          :readonly="true"
          class="ui-preview__code-editor"
        />
      </aside>
      <main v-show="!fullscreenPanel || fullscreenPanel === 'render'" class="ui-preview__render" :class="{ 'is-fullscreen': fullscreenPanel === 'render' }">
        <div class="ui-preview__render-header">
          <span>页面效果</span>
          <div class="ui-preview__header-actions">
            <div v-if="previewUrl && !fullscreenPanel" class="ui-preview__url-info">
              <el-tag size="small" type="success">服务已启动</el-tag>
              <el-tooltip :content="previewUrl" placement="top">
                <el-button size="small" link type="primary" @click="refreshPreview">刷新</el-button>
              </el-tooltip>
            </div>
            <el-tag v-else-if="!previewUrl && serviceStatus && !fullscreenPanel" size="small" type="warning">{{ serviceStatus }}</el-tag>
            <el-button v-if="previewUrl" size="small" @click="openInNewTab">
              <el-icon><Link /></el-icon>
              新窗口
            </el-button>
            <el-button v-if="fullscreenPanel === 'render'" size="small" @click="exitFullscreen">
              <el-icon><Close /></el-icon>
              退出全屏
            </el-button>
            <el-button v-else size="small" @click="toggleFullscreen('render')">
              <el-icon><FullScreen /></el-icon>
              全屏
            </el-button>
          </div>
        </div>
        <iframe
          v-if="previewUrl"
          :key="iframeKey"
          :src="previewUrl"
          class="ui-preview__iframe"
          @load="onIframeLoad"
          @error="onIframeError"
        />
        <div v-else class="ui-preview__no-preview">
          <div class="ui-preview__no-preview-content">
            <el-icon size="48"><VideoPlay /></el-icon>
            <p>UI Package 服务未启动</p>
            <el-button type="primary" :loading="starting" @click="startService">
              {{ starting ? '启动中...' : '启动服务' }}
            </el-button>
          </div>
        </div>
      </main>
    </div>

  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { Loading, WarningFilled, VideoPlay, FullScreen, Close, Link } from '@element-plus/icons-vue';
import { getUiPackageFileContent, getUiPackageAccessUrl, startUiPackage, listUiPackageFiles, type UiPackageFileInfo } from '@/api/uiPackage';
import CodemirrorEditor from '@/components/common/CodemirrorEditor.vue';
import { useSiteContextStore } from '@/stores/siteContext';

const route = useRoute();
const router = useRouter();
const siteContext = useSiteContextStore();
const canOpenWorkbench = computed(() => import.meta.env.VITE_DEVELOPER_WORKBENCH === 'true' && siteContext.isPlatformAdmin);

const siteId = Number(route.query.siteId) || 0;
const initialFilePath = String(route.query.path || '');
const loading = ref(true);
const error = ref('');
const fileContent = ref('');
const previewUrl = ref('');
const serviceStatus = ref('');
const starting = ref(false);
const iframeKey = ref(0);
const fullscreenPanel = ref<'code' | 'render' | null>(null);
const selectedPath = ref(initialFilePath);
const files = ref<UiPackageFileInfo[]>([]);
const baseUrl = ref('');

const htmlFiles = computed(() => {
  return files.value.filter(f => {
    const ext = f.relativePath.toLowerCase();
    return ext.endsWith('.html') || ext.endsWith('.htm') || ext.endsWith('.aspx') || ext.endsWith('.asp');
  });
});

const otherFiles = computed(() => {
  return files.value.filter(f => {
    const ext = f.relativePath.toLowerCase();
    return !ext.endsWith('.html') && !ext.endsWith('.htm') && !ext.endsWith('.aspx') && !ext.endsWith('.asp');
  });
});

function getFileIcon(path: string): string {
  const ext = path.toLowerCase().split('.').pop() || '';
  const icons: Record<string, string> = {
    css: '🎨',
    js: '📜',
    json: '📋',
    png: '🖼️',
    jpg: '🖼️',
    jpeg: '🖼️',
    gif: '🖼️',
    svg: '🖼️',
    ico: '🖼️'
  };
  return icons[ext] || '📄';
}

onMounted(async () => {
  if (!siteId || !initialFilePath) {
    error.value = '缺少站点或页面参数，请从资源库选择要预览的页面。';
    loading.value = false;
    return;
  }

  try {
    const [fileList, content, accessUrl] = await Promise.all([
      listUiPackageFiles(siteId),
      getUiPackageFileContent(siteId, initialFilePath),
      getUiPackageAccessUrl(siteId)
    ]);

    files.value = fileList;
    fileContent.value = content;

    if (accessUrl) {
      baseUrl.value = accessUrl;
      previewUrl.value = accessUrl + initialFilePath;
      serviceStatus.value = 'RUNNING';
    } else {
      serviceStatus.value = 'STOPPED';
    }
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载失败';
  } finally {
    loading.value = false;
  }
});

async function onFileChange(newPath: string) {
  if (!siteId || !newPath) return;

  loading.value = true;
  try {
    const content = await getUiPackageFileContent(siteId, newPath);
    fileContent.value = content;

    if (baseUrl.value) {
      previewUrl.value = baseUrl.value + newPath;
      iframeKey.value++;
    }

    // 更新 URL 但不触发导航
    router.replace({
      query: { siteId: String(siteId), path: newPath }
    });
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载文件失败');
  } finally {
    loading.value = false;
  }
}

async function startService() {
  starting.value = true;
  try {
    const status = await startUiPackage(siteId);
    if (status.accessUrl) {
      baseUrl.value = status.accessUrl;
      previewUrl.value = status.accessUrl + selectedPath.value;
      serviceStatus.value = 'RUNNING';
      iframeKey.value++;
      ElMessage.success('服务已启动');
    } else {
      ElMessage.warning('服务启动成功，但无法获取访问地址');
    }
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '启动服务失败');
  } finally {
    starting.value = false;
  }
}

function refreshPreview() {
  iframeKey.value++;
}

function toggleFullscreen(panel: 'code' | 'render') {
  fullscreenPanel.value = panel;
}

function exitFullscreen() {
  fullscreenPanel.value = null;
}

function onIframeLoad() {
  // iframe 加载成功
}

function onIframeError() {
  ElMessage.error('页面加载失败，请检查服务是否正常运行');
}

function openInNewTab() {
  if (previewUrl.value) {
    window.open(previewUrl.value, '_blank');
  }
}

async function copyCode() {
  try {
    await navigator.clipboard.writeText(fileContent.value);
    ElMessage.success('已复制到剪贴板');
  } catch {
    ElMessage.error('复制失败');
  }
}

async function openWorkbench() {
  if (import.meta.env.VITE_DEVELOPER_WORKBENCH !== 'true' || !siteContext.isPlatformAdmin) return;
  try {
    if (String(siteContext.currentSite?.id) !== String(siteId)) await siteContext.switchSite(siteId);
    await router.push({ name: 'site-build-workbench' });
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '打开建站引导失败');
  }
}
</script>

<style scoped>
.ui-preview {
  display: flex;
  flex-direction: column;
  gap: var(--admin-card-gap);
  height: calc(100vh - 80px);
  padding: 0;
}

.ui-preview.is-fullscreen {
  height: calc(100vh - 48px);
  padding: 12px 24px;
}

.ui-preview__header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 16px;
  border: 1px solid var(--admin-border);
  border-radius: 8px;
  background: var(--admin-surface);
  box-shadow: var(--admin-shadow-card);
  flex-shrink: 0;
}

.ui-preview__file-selector {
  flex: 1;
  min-width: 0;
}

.ui-preview__select-row {
  margin-top: 2px;
}

.ui-preview__path-select {
  width: 100%;
  max-width: 500px;
}

.ui-preview__file-option {
  display: flex;
  align-items: center;
  gap: 8px;
}

.ui-preview__file-icon {
  flex-shrink: 0;
}

.ui-preview__file-path {
  overflow: hidden;
  text-overflow: ellipsis;
}

.ui-preview__header h1,
.ui-preview__header p {
  margin: 0;
}

.ui-preview__header p {
  color: var(--admin-text-muted);
  font-size: 12px;
}

.ui-preview__actions {
  display: flex;
  gap: 8px;
  flex-shrink: 0;
}

.ui-preview__loading,
.ui-preview__error {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 100px;
  color: var(--el-text-color-secondary);
  font-size: 16px;
}

.ui-preview__error {
  color: var(--el-color-danger);
}

.ui-preview__main {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
  min-height: 0;
  flex: 1;
  overflow: hidden;
}

.ui-preview.is-fullscreen .ui-preview__main {
  grid-template-columns: 1fr;
}

.ui-preview__code,
.ui-preview__render {
  display: flex;
  flex-direction: column;
  border: 1px solid var(--admin-border);
  border-radius: 8px;
  background: var(--admin-surface);
  overflow: hidden;
  min-height: 0;
}

.ui-preview__code.is-fullscreen,
.ui-preview__render.is-fullscreen {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  max-height: none;
  min-height: 0;
  z-index: 1000;
  border-radius: 0;
}

.ui-preview__code-header,
.ui-preview__render-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 6px 12px;
  background: var(--el-fill-color-light);
  border-bottom: 1px solid var(--admin-border);
  font-weight: 600;
  font-size: 13px;
  flex-shrink: 0;
}

.ui-preview__code-editor {
  flex: 1;
  border: none;
  border-radius: 0;
}

.ui-preview__code-editor :deep(.codemirror-editor__header) {
  display: none;
}

.ui-preview__header-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.ui-preview__iframe {
  flex: 1;
  width: 100%;
  height: 100%;
  border: none;
  background: #fff;
}

.ui-preview__no-preview {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
}

.ui-preview__no-preview-content {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 16px;
  color: var(--el-text-color-secondary);
}

.ui-preview__no-preview-content p {
  margin: 0;
  font-size: 16px;
}

.ui-preview__url-info {
  display: flex;
  align-items: center;
  gap: 12px;
}

@media (max-width: 1200px) {
  .ui-preview__main {
    grid-template-columns: 1fr;
  }

  .ui-preview__header {
    flex-direction: column;
    align-items: flex-start;
    gap: 10px;
  }

  .ui-preview__actions {
    width: 100%;
    justify-content: flex-end;
  }
}
</style>
