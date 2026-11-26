<template>
  <section class="column-template-preview">
    <!-- 加载中状态 -->
    <div v-if="checking" class="column-template-preview__loading">
      <el-icon class="is-loading" size="32"><Loading /></el-icon>
      <span>正在加载模板...</span>
    </div>

    <!-- 无模板提示 -->
    <div v-else-if="!templateStatus?.hasTemplate" class="column-template-preview__empty">
      <el-empty :description="emptyMessage">
        <template #description>
          <p>{{ emptyMessage }}</p>
          <p class="column-template-preview__hint">请先在栏目管理中配置模板路径</p>
        </template>
      </el-empty>
    </div>

    <!-- 有模板时显示 -->
    <div v-else class="column-template-preview__main">
      <header class="column-template-preview__header">
        <div class="column-template-preview__info">
          <el-tag type="success">模板路径: {{ templateStatus?.templatePath }}</el-tag>
          <el-tag type="info">{{ templateTypeName }}</el-tag>
        </div>
        <div class="column-template-preview__actions">
          <el-button size="small" @click="refresh">
            <el-icon><RefreshRight /></el-icon>
            刷新
          </el-button>
          <el-button v-if="previewUrl" size="small" type="primary" link @click="openInNewTab">
            <el-icon><Link /></el-icon>
            新窗口
          </el-button>
        </div>
      </header>

      <div class="column-template-preview__content">
        <!-- 子Tab切换 -->
        <el-tabs v-model="activeTab" class="column-template-preview__tabs">
          <el-tab-pane label="模板内容" name="source">
            <CodemirrorEditor
              v-model="sourceCode"
              :title="templateStatus?.templatePath || ''"
              :show-save="true"
              @save="handleSave"
            />
          </el-tab-pane>
          <el-tab-pane label="预览模板界面" name="render">
            <div class="column-template-preview__render">
              <div class="column-template-preview__render-header">
                <span>页面预览</span>
                <div class="column-template-preview__render-actions">
                  <el-tag v-if="renderedHtml" type="success" size="small">渲染成功</el-tag>
                  <el-tag v-else-if="renderError" type="danger" size="small">渲染失败</el-tag>
                  <el-button v-if="renderedHtml" size="small" type="primary" link @click="openInNewTab">
                    <el-icon><Link /></el-icon>
                    新窗口打开
                  </el-button>
                  <el-button v-if="renderedHtml" size="small" @click="refreshRender">
                    刷新预览
                  </el-button>
                </div>
              </div>
              <div class="column-template-preview__render-body">
                <iframe
                  v-if="renderedHtml"
                  :key="renderKey"
                  :srcdoc="renderedHtml"
                  class="column-template-preview__iframe"
                />
                <el-empty v-else-if="renderError" :description="renderError" />
                <div v-else class="column-template-preview__render-loading">
                  <el-icon class="is-loading"><Loading /></el-icon>
                  <span>正在渲染...</span>
                </div>
              </div>
            </div>
          </el-tab-pane>
        </el-tabs>
      </div>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { ElMessage } from 'element-plus';
import { Loading, RefreshRight, Link } from '@element-plus/icons-vue';
import CodemirrorEditor from '@/components/common/CodemirrorEditor.vue';
import {
  checkColumnTemplateStatus,
  previewTemplate,
  getTemplateContent,
  saveTemplateContent,
  type ColumnTemplateStatus
} from '@/api/templateRecognition';

const props = defineProps<{
  siteId: number;
  columnId: number;
  templateType: 'list' | 'detail';
}>();

const checking = ref(true);
const templateStatus = ref<ColumnTemplateStatus | null>(null);
const sourceCode = ref('');
const renderedHtml = ref('');
const renderError = ref('');
const renderKey = ref(0);
const activeTab = ref('source');
const previewUrl = ref('');

const templateTypeName = computed(() => {
  return props.templateType === 'list' ? '列表页模板' : '详情页模板';
});

const emptyMessage = computed(() => {
  return `该栏目暂未配置${templateTypeName.value}`;
});

watch(
  () => [props.siteId, props.columnId, props.templateType],
  () => {
    loadTemplate();
  }
);

onMounted(() => {
  loadTemplate();
});

async function loadTemplate() {
  if (!props.siteId || !props.columnId) {
    checking.value = false;
    return;
  }

  checking.value = true;
  templateStatus.value = null;
  sourceCode.value = '';
  renderedHtml.value = '';
  renderError.value = '';

  try {
    const status = await checkColumnTemplateStatus(
      props.siteId,
      props.columnId,
      props.templateType
    );
    templateStatus.value = status;

    if (status.hasTemplate && status.templatePath) {
      await Promise.all([
        loadSourceCode(status.templatePath),
        loadPreview(status.templatePath)
      ]);
    }
  } catch (e) {
    console.error('加载模板失败:', e);
    ElMessage.error('加载模板失败');
  } finally {
    checking.value = false;
  }
}

async function loadSourceCode(templatePath: string) {
  try {
    const content = await getTemplateContent(props.siteId, templatePath);
    sourceCode.value = content || '';
  } catch (e) {
    console.error('加载模板源码失败:', e);
  }
}

async function loadPreview(templatePath: string) {
  try {
    const result = await previewTemplate(
      props.siteId,
      templatePath,
      props.columnId
    );

    renderedHtml.value = result.renderedHtml || '';
    previewUrl.value = result.staticPreviewUrl || '';
    renderError.value = '';
    renderKey.value++;
  } catch (e) {
    console.error('加载模板预览失败:', e);
    renderError.value = e instanceof Error ? e.message : '预览加载失败';
    renderedHtml.value = '';
  }
}

async function refresh() {
  await loadTemplate();
  ElMessage.success('已刷新');
}

async function refreshRender() {
  if (templateStatus.value?.templatePath) {
    await loadPreview(templateStatus.value.templatePath);
  }
}

async function handleSave() {
  if (!templateStatus.value?.templatePath) {
    ElMessage.warning('模板路径不存在');
    return;
  }

  try {
    await saveTemplateContent(props.siteId, templateStatus.value.templatePath, sourceCode.value);
    ElMessage.success('保存成功');
    // 保存后刷新预览
    await loadPreview(templateStatus.value.templatePath);
  } catch (e) {
    console.error('保存失败:', e);
    ElMessage.error(e instanceof Error ? e.message : '保存失败');
  }
}

function openInNewTab() {
  if (!renderedHtml.value) {
    ElMessage.warning('暂无预览内容');
    return;
  }

  // 使用 Blob URL 在新窗口中打开渲染的 HTML
  const blob = new Blob([renderedHtml.value], { type: 'text/html' });
  const url = URL.createObjectURL(blob);
  const newWindow = window.open(url, '_blank');

  // 延迟释放 URL，确保新窗口加载完成
  if (newWindow) {
    setTimeout(() => {
      URL.revokeObjectURL(url);
    }, 1000);
  }
}
</script>

<style scoped>
.column-template-preview {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 400px;
}

.column-template-preview__loading,
.column-template-preview__empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  padding: 60px;
  color: var(--el-text-color-secondary);
}

.column-template-preview__hint {
  margin-top: 8px;
  font-size: 12px;
  color: var(--el-text-color-placeholder);
}

.column-template-preview__main {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 0;
}

.column-template-preview__header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 16px;
  background: var(--el-fill-color-light);
  border-bottom: 1px solid var(--el-border-color-light);
  border-radius: 8px 8px 0 0;
}

.column-template-preview__info {
  display: flex;
  align-items: center;
  gap: 8px;
}

.column-template-preview__actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.column-template-preview__content {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.column-template-preview__tabs {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-height: 0;
}

.column-template-preview__tabs :deep(.el-tabs__content) {
  flex: 1;
  min-height: 0;
}

.column-template-preview__tabs :deep(.el-tab-pane) {
  height: 100%;
}

.column-template-preview__render {
  display: flex;
  flex-direction: column;
  height: 100%;
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  overflow: hidden;
}

.column-template-preview__render-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 16px;
  background: var(--el-fill-color-lighter);
  border-bottom: 1px solid var(--el-border-color-light);
  font-weight: 500;
}

.column-template-preview__render-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.column-template-preview__render-body {
  flex: 1;
  min-height: 0;
  position: relative;
}

.column-template-preview__iframe {
  width: 100%;
  height: 100%;
  border: none;
  background: #fff;
}

.column-template-preview__render-loading {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  height: 100%;
  color: var(--el-text-color-secondary);
}
</style>
