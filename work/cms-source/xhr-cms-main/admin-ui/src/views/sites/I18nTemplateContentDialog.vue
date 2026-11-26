<script setup lang="ts">
import { computed, ref } from 'vue';
import { getTemplateContent } from '@/api/templateRecognition';
import CodemirrorEditor from '@/components/common/CodemirrorEditor.vue';

const props = defineProps<{
  modelValue: boolean;
  title?: string;
  sourceLabel?: string;
  sourceSiteId?: number;
  sourcePath?: string | null;
  targetLabel?: string;
  targetSiteId?: number;
  targetPath?: string | null;
  showTarget?: boolean;
}>();

const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void;
}>();

interface Panel {
  key: string;
  label: string;
  path: string;
  content: string;
  error: string;
  loading: boolean;
}

const sourceContent = ref('');
const sourceError = ref('');
const sourceLoading = ref(false);
const targetContent = ref('');
const targetError = ref('');
const targetLoading = ref(false);

const panels = computed<Panel[]>(() => {
  const list: Panel[] = [{
    key: 'source',
    label: props.sourceLabel ?? '源栏目',
    path: props.sourcePath ?? '',
    content: sourceContent.value,
    error: sourceError.value,
    loading: sourceLoading.value
  }];
  if (props.showTarget) {
    list.push({
      key: 'target',
      label: props.targetLabel ?? '目标栏目',
      path: props.targetPath ?? '',
      content: targetContent.value,
      error: targetError.value,
      loading: targetLoading.value
    });
  }
  return list;
});

const activeTab = ref('source');

async function loadPanel(siteId: number | undefined, path: string | null | undefined,
                         contentRef: typeof sourceContent, errorRef: typeof sourceError, loadingRef: typeof sourceLoading) {
  contentRef.value = '';
  errorRef.value = '';
  if (!siteId || !path) {
    errorRef.value = '未配置模版文件路径';
    return;
  }
  loadingRef.value = true;
  try {
    const content = await getTemplateContent(siteId, path);
    if (content === null || content === undefined || content === '') {
      errorRef.value = '模版文件不存在或内容为空';
    } else {
      contentRef.value = content;
    }
  } catch (e) {
    errorRef.value = e instanceof Error ? e.message : '模版内容读取失败';
  } finally {
    loadingRef.value = false;
  }
}

async function onOpen() {
  activeTab.value = 'source';
  await loadPanel(props.sourceSiteId, props.sourcePath, sourceContent, sourceError, sourceLoading);
  if (props.showTarget) {
    await loadPanel(props.targetSiteId, props.targetPath, targetContent, targetError, targetLoading);
  }
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    :title="title || '栏目模版内容'"
    width="1100px"
    top="6vh"
    @update:model-value="(v: boolean) => emit('update:modelValue', v)"
    @open="onOpen"
  >
    <el-tabs v-if="showTarget" v-model="activeTab">
      <el-tab-pane v-for="panel in panels" :key="panel.key" :name="panel.key">
        <template #label>
          <span>{{ panel.label }}</span>
        </template>
        <div class="tpl-content__path">{{ panel.path || '-' }}</div>
        <el-alert v-if="panel.error" :title="panel.error" type="warning" :closable="false" show-icon />
        <div v-else v-loading="panel.loading" class="tpl-content__editor">
          <CodemirrorEditor :model-value="panel.content" :title="panel.path" />
        </div>
      </el-tab-pane>
    </el-tabs>

    <template v-else>
      <div class="tpl-content__path">{{ panels[0].path || '-' }}</div>
      <el-alert v-if="panels[0].error" :title="panels[0].error" type="warning" :closable="false" show-icon />
      <div v-else v-loading="panels[0].loading" class="tpl-content__editor">
        <CodemirrorEditor :model-value="panels[0].content" :title="panels[0].path" />
      </div>
    </template>
  </el-dialog>
</template>

<style scoped>
.tpl-content__path {
  margin-bottom: 8px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  word-break: break-all;
}
.tpl-content__editor {
  height: 620px;
  max-height: 70vh;
}
</style>
