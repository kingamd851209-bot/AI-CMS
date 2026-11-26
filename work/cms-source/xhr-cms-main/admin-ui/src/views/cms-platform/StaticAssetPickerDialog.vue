<script setup lang="ts">
import { computed, watch, ref } from 'vue';
import { Folder } from '@element-plus/icons-vue';
import { staticAssetApi, assetCategoryOf, type AssetEntry } from '@/api/staticAssets';

const props = withDefaults(defineProps<{
  modelValue: boolean;
  title?: string;
  /** 允许选择的分类，如 ['images','videos']；为空表示全部文件 */
  accept?: string[];
}>(), {
  title: '从静态资源库选择',
  accept: () => []
});

const emit = defineEmits<{
  'update:modelValue': [value: boolean];
  select: [path: string];
}>();

const currentPath = ref('');
const entries = ref<AssetEntry[]>([]);
const loading = ref(false);

const crumbs = computed(() => {
  const parts = currentPath.value ? currentPath.value.split('/') : [];
  return [
    { name: '根目录', path: '' },
    ...parts.map((p, i) => ({ name: p, path: parts.slice(0, i + 1).join('/') }))
  ];
});

const dirs = computed(() => entries.value.filter(e => e.dir));
const files = computed(() => entries.value
  .filter(e => !e.dir)
  .filter(e => props.accept.length === 0 || props.accept.includes(assetCategoryOf(e.name))));

async function loadList() {
  loading.value = true;
  try {
    entries.value = await staticAssetApi.list(currentPath.value);
  } finally {
    loading.value = false;
  }
}

function enterDir(item: AssetEntry) {
  currentPath.value = item.path;
  loadList();
}

function goTo(path: string) {
  currentPath.value = path;
  loadList();
}

function selectFile(item: AssetEntry) {
  emit('select', item.path);
  emit('update:modelValue', false);
}

function previewOf(item: AssetEntry): string {
  return staticAssetApi.downloadUrl(item.path);
}

watch(
  () => props.modelValue,
  (open) => {
    if (open) {
      currentPath.value = '';
      loadList();
    }
  }
);
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    :title="title"
    width="880px"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div class="sa-picker">
      <div class="sa-picker__toolbar">
        <div class="sa-picker__crumbs">
          <template v-for="(c, i) in crumbs" :key="c.path">
            <span v-if="i > 0">/</span>
            <span class="sa-picker__crumb" @click="goTo(c.path)">{{ c.name }}</span>
          </template>
        </div>
      </div>

      <div class="sa-picker__body" v-loading="loading">
        <el-empty v-if="!loading && dirs.length === 0 && files.length === 0" description="暂无可用文件" />
        <template v-else>
          <template v-if="dirs.length">
            <div class="sa-picker__section">文件夹</div>
            <div class="sa-picker__grid">
              <div v-for="item in dirs" :key="item.path" class="sa-picker__item" title="点击进入" @click="enterDir(item)">
                <div class="sa-picker__thumb sa-picker__thumb--dir">
                  <el-icon><Folder /></el-icon>
                </div>
                <span class="sa-picker__name">{{ item.name }}</span>
              </div>
            </div>
          </template>

          <template v-if="files.length">
            <div class="sa-picker__section">文件（{{ files.length }}）</div>
            <div class="sa-picker__grid">
              <div v-for="item in files" :key="item.path" class="sa-picker__item" @click="selectFile(item)">
                <div class="sa-picker__thumb">
                  <el-image
                    v-if="assetCategoryOf(item.name) === 'images'"
                    :src="previewOf(item)"
                    fit="cover"
                    style="width: 100%; height: 100%"
                  />
                  <span v-else class="sa-picker__ext">{{ item.ext?.toUpperCase() || assetCategoryOf(item.name) }}</span>
                </div>
                <span class="sa-picker__name" :title="item.name">{{ item.name }}</span>
              </div>
            </div>
          </template>
        </template>
      </div>
    </div>

    <template #footer>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.sa-picker__toolbar {
  display: flex;
  align-items: center;
  padding-bottom: 10px;
  border-bottom: 1px solid var(--el-border-color-lighter);
}
.sa-picker__crumbs {
  display: flex;
  gap: 4px;
  align-items: center;
  flex-wrap: wrap;
  font-size: 13px;
}
.sa-picker__crumb {
  color: var(--el-color-primary);
  cursor: pointer;
}
.sa-picker__body {
  min-height: 360px;
  max-height: 480px;
  overflow: auto;
  padding: 8px 0;
}
.sa-picker__section {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin: 8px 0;
}
.sa-picker__grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(100px, 1fr));
  gap: 10px;
}
.sa-picker__item {
  cursor: pointer;
  border-radius: 8px;
  padding: 6px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  border: 1px solid transparent;
  text-align: center;
  min-width: 0;
}
.sa-picker__item:hover {
  background: var(--el-fill-color-light);
  border-color: var(--el-border-color);
}
.sa-picker__thumb {
  width: 64px;
  height: 64px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 8px;
  background: var(--el-fill-color-lighter);
  overflow: hidden;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.sa-picker__thumb--dir .el-icon {
  font-size: 36px;
  color: var(--el-color-warning);
}
.sa-picker__ext {
  font-weight: 600;
  color: var(--el-color-primary);
}
.sa-picker__name {
  font-size: 12px;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>