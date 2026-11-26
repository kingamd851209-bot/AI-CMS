<template>
  <el-dialog
    :model-value="modelValue"
    title="选择静态图片"
    width="min(1200px, 92vw)"
    top="6vh"
    class="static-image-picker"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div v-if="!siteId" class="static-image-picker__empty">
      <el-empty description="未选择站点" />
    </div>

    <div v-else class="static-image-picker__layout">
      <!-- 左侧目录树 -->
      <aside class="static-image-picker__tree">
        <div class="static-image-picker__tree-header">
          <el-icon><FolderOpened /></el-icon>
          <span>目录</span>
        </div>
        <div v-if="loading" class="static-image-picker__tree-loading">
          <el-icon class="is-loading"><Loading /></el-icon>
        </div>
        <el-tree
          v-else
          ref="treeRef"
          :data="treeData"
          :props="treeProps"
          node-key="path"
          :default-expanded-keys="defaultExpandedKeys"
          :current-node-key="currentPath"
          highlight-current
          :expand-on-click-node="false"
          @node-click="onNodeClick"
        >
          <template #default="{ data }">
            <span class="static-image-picker__tree-node">
              <el-icon v-if="data.isFolder"><Folder /></el-icon>
              <span class="static-image-picker__tree-label">{{ data.name }}</span>
              <span v-if="data.imageCount > 0" class="static-image-picker__tree-count">{{ data.imageCount }}</span>
            </span>
          </template>
        </el-tree>
      </aside>

      <!-- 右侧图片列表 -->
      <main class="static-image-picker__main">
        <header class="static-image-picker__header">
          <el-input
            v-model="searchKeyword"
            placeholder="搜索文件名"
            clearable
            class="static-image-picker__search"
          >
            <template #prefix>
              <el-icon><Search /></el-icon>
            </template>
          </el-input>
          <template v-if="!searchKeyword">
            <nav class="static-image-picker__breadcrumb">
              <template v-for="(crumb, index) in breadcrumbs" :key="`${crumb.path}-${index}`">
                <span v-if="index > 0" class="static-image-picker__separator">/</span>
                <button
                  type="button"
                  class="static-image-picker__crumb"
                  :class="{ 'is-current': index === breadcrumbs.length - 1 }"
                  @click="navigateTo(crumb.path)"
                >
                  {{ crumb.name }}
                </button>
              </template>
            </nav>
            <span v-if="images.length" class="static-image-picker__total">{{ images.length }} 张图片</span>
          </template>
          <span v-else-if="filteredImages.length" class="static-image-picker__total">{{ filteredImages.length }} 个结果</span>
        </header>

        <div v-if="loading" class="static-image-picker__loading">
          <el-icon class="is-loading"><Loading /></el-icon>
          <span>加载中...</span>
        </div>

        <el-empty v-else-if="!displayImages.length" :description="searchKeyword ? '未找到匹配的图片' : '当前目录暂无图片'" />

        <div v-else class="static-image-picker__grid">
          <article
            v-for="(item, index) in displayImages"
            :key="item.relativePath"
            class="static-image-picker__item"
            :data-testid="`static-image-${index}`"
          >
            <button
              type="button"
              class="static-image-picker__select"
              :data-testid="`static-image-select-${index}`"
              @click="selectImage(item)"
            >
              <img :src="getPreviewUrl(item)" :alt="item.fileName" loading="lazy" />
              <span :title="item.fileName">{{ item.fileName }}</span>
            </button>
            <button
              type="button"
              class="static-image-picker__zoom"
              title="查看大图"
              aria-label="查看大图"
              @click.stop="openPreview(getPreviewUrl(item))"
            >
              <el-icon><ZoomIn /></el-icon>
            </button>
          </article>
        </div>
      </main>
    </div>

    <template #footer>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
    </template>
  </el-dialog>

  <!-- 放大预览：支持缩放/旋转/切换 -->
  <el-image-viewer
    v-if="previewUrl"
    :url-list="[previewUrl]"
    :teleported="true"
    @close="previewUrl = ''"
  />
</template>

<script setup lang="ts">
import { http } from '@/api/http';
import type { ResourceContext } from '@/api/resourceReferences';
import { computed, ref, watch } from 'vue';
import { Loading, Folder, FolderOpened, Search, ZoomIn } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import { listUiPackageFiles, getUiPackageAccessUrl, startUiPackage, type UiPackageFileInfo } from '@/api/uiPackage';
import { useSiteContextStore } from '@/stores/siteContext';

const props = defineProps<{
  modelValue: boolean;
  multiple?: boolean;
}>();

const emit = defineEmits<{
  'update:modelValue': [value: boolean];
  select: [value: string | string[]];
}>();

const siteContext = useSiteContextStore();
const siteId = computed(() => {
  const id = siteContext.currentSite?.id;
  return id ? Number(id) : null;
});

const loading = ref(false);
const baseUrl = ref('');
const allFiles = ref<UiPackageFileInfo[]>([]);
const currentPath = ref('');
const searchKeyword = ref('');
const previewUrl = ref('');
const treeRef = ref<{ setCurrentKey: (key: string) => void } | null>(null);

const IMAGE_EXTENSIONS = ['png', 'jpg', 'jpeg', 'gif', 'webp', 'svg', 'ico', 'bmp'];

interface TreeNode {
  path: string;
  name: string;
  isFolder: boolean;
  imageCount: number;
  children?: TreeNode[];
}

interface ImageItem extends UiPackageFileInfo {
  previewUrl: string;
}

// 构建目录树数据
const treeData = computed<TreeNode[]>(() => {
  // 收集所有目录路径及其父目录
  // 注意：根目录不加入 dirMap，用特殊标记 '__root__' 作为其 parentPath
  const dirMap = new Map<string, { name: string; parentPath: string; imageCount: number }>();

  // 第一遍遍历：收集所有目录
  for (const file of allFiles.value) {
    const parts = file.relativePath.split('/').filter(Boolean);

    // 为每一级目录创建条目
    for (let i = 0; i < parts.length - 1; i++) {
      const dirPath = parts.slice(0, i + 1).join('/');
      const dirName = parts[i];
      // 一级目录的 parentPath 用特殊标记，不使用空字符串避免与根目录混淆
      const parentPath = i > 0 ? parts.slice(0, i).join('/') : '__root__';

      if (!dirMap.has(dirPath)) {
        dirMap.set(dirPath, { name: dirName, parentPath, imageCount: 0 });
      }
    }
  }

  // 第二遍遍历：统计每个目录的直接图片数量
  for (const file of allFiles.value) {
    const ext = file.relativePath.toLowerCase().split('.').pop() || '';
    if (!IMAGE_EXTENSIONS.includes(ext)) continue;

    const parts = file.relativePath.split('/').filter(Boolean);
    const dirPath = parts.length > 1 ? parts.slice(0, -1).join('/') : '';
    if (dirPath === '') {
      // 根目录下的图片，不计入任何目录
      continue;
    }
    const dir = dirMap.get(dirPath);
    if (dir) {
      dir.imageCount++;
    }
  }

  // 递归构建树节点
  function buildNode(nodePath: string, nodeName: string, nodeImageCount: number): TreeNode {
    // 对于根节点，查找 parentPath 为 '__root__' 的子目录
    // 对于其他节点，查找 parentPath 为当前路径的子目录
    const childParentKey = nodePath === '' ? '__root__' : nodePath;

    const childDirs = Array.from(dirMap.entries())
      .filter(([p, info]) => info.parentPath === childParentKey)
      .map(([p, info]) => buildNode(p, info.name, info.imageCount))
      .sort((a, b) => a.name.localeCompare(b.name, 'zh-CN'));

    // 计算递归图片总数
    const totalCount = nodeImageCount + childDirs.reduce((sum, child) => sum + child.imageCount, 0);

    return {
      path: nodePath,
      name: nodeName,
      isFolder: true,
      imageCount: totalCount,
      children: childDirs.length > 0 ? childDirs : undefined
    };
  }

  // 统计根目录下的图片
  const rootImageCount = allFiles.value.filter(f => {
    const ext = f.relativePath.toLowerCase().split('.').pop() || '';
    return IMAGE_EXTENSIONS.includes(ext) && !f.relativePath.includes('/');
  }).length;

  // 构建根节点，一级目录的 parentPath 是 '__root__'
  const rootNode = buildNode('', '静态资源', rootImageCount);
  return [rootNode];
});

const defaultExpandedKeys = computed(() => {
  // 展开根节点 + 当前路径的各层，便于定位上次选择的文件夹
  const keys = [''];
  let path = '';
  for (const segment of currentPath.value.split('/').filter(Boolean)) {
    path = path ? `${path}/${segment}` : segment;
    keys.push(path);
  }
  return keys;
});

const treeProps = {
  label: 'name',
  children: 'children'
};

// 当前目录的图片
const images = computed<ImageItem[]>(() => {
  return allFiles.value
    .filter((file) => {
      const ext = file.relativePath.toLowerCase().split('.').pop() || '';
      if (!IMAGE_EXTENSIONS.includes(ext)) return false;
      const dirPath = file.relativePath.split('/').slice(0, -1).join('/');
      return dirPath === currentPath.value;
    })
    .map((file) => ({
      ...file,
      previewUrl: getPreviewUrl(file)
    }))
    .sort((a, b) => a.fileName.localeCompare(b.fileName, 'zh-CN'));
});

// 根据搜索关键字过滤的所有图片
const filteredImages = computed<ImageItem[]>(() => {
  const keyword = searchKeyword.value.trim().toLowerCase();
  if (!keyword) return [];

  return allFiles.value
    .filter((file) => {
      const ext = file.relativePath.toLowerCase().split('.').pop() || '';
      if (!IMAGE_EXTENSIONS.includes(ext)) return false;
      return file.fileName.toLowerCase().includes(keyword);
    })
    .map((file) => ({
      ...file,
      previewUrl: getPreviewUrl(file)
    }))
    .sort((a, b) => a.fileName.localeCompare(b.fileName, 'zh-CN'));
});

// 显示的图片列表（搜索时显示搜索结果，否则显示当前目录）
const displayImages = computed<ImageItem[]>(() => {
  return searchKeyword.value.trim() ? filteredImages.value : images.value;
});

// 面包屑导航
const breadcrumbs = computed(() => {
  const segments = currentPath.value.split('/').filter(Boolean);
  const crumbs = [{ name: '静态资源', path: '' }];
  let path = '';
  for (const segment of segments) {
    path = path ? `${path}/${segment}` : segment;
    crumbs.push({ name: segment, path });
  }
  return crumbs;
});

// 上次选择的文件夹（按站点记忆）
const LAST_PATH_STORAGE_PREFIX = 'staticImagePicker:lastPath:';

function lastPathStorageKey(): string {
  return `${LAST_PATH_STORAGE_PREFIX}${siteId.value ?? ''}`;
}

function readLastPath(): string {
  try {
    return localStorage.getItem(lastPathStorageKey()) || '';
  } catch {
    return '';
  }
}

function rememberPath(): void {
  try {
    localStorage.setItem(lastPathStorageKey(), currentPath.value);
  } catch {
    /* 忽略存储失败（如隐私模式） */
  }
}

function treeHasPath(nodes: TreeNode[], path: string): boolean {
  return nodes.some(
    (node) => node.path === path || (node.children ? treeHasPath(node.children, path) : false)
  );
}

watch(
  () => props.modelValue,
  async (open) => {
    if (open && siteId.value) {
      currentPath.value = readLastPath();
      await loadFiles();
      // 上次的文件夹若已不存在，回退到根目录
      if (currentPath.value && !treeHasPath(treeData.value, currentPath.value)) {
        currentPath.value = '';
      }
    }
  }
);

async function loadFiles() {
  if (!siteId.value) return;

  loading.value = true;
  try {
    const context = await http.get<ResourceContext>('/api/admin/v2/resource-references/context', { params: { siteId: siteId.value } });
    let accessUrl = context.ui || await getUiPackageAccessUrl(siteId.value);
    if (!accessUrl) {
      const status = await startUiPackage(siteId.value);
      accessUrl = status.accessUrl || '';
    }
    const fileList = await listUiPackageFiles(siteId.value);

    allFiles.value = fileList;
    baseUrl.value = accessUrl;
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '静态图片加载失败');
  } finally {
    loading.value = false;
  }
}

function getPreviewUrl(file: UiPackageFileInfo): string {
  return baseUrl.value + file.relativePath;
}

function onNodeClick(data: TreeNode) {
  currentPath.value = data.path;
  rememberPath();
}

function navigateTo(path: string) {
  currentPath.value = path;
  treeRef.value?.setCurrentKey(path);
  rememberPath();
}

function selectImage(item: ImageItem) {
  // 使用 ui:/ 前缀标识 UIPackage 相对路径，访问时再拼接 UIPackage 访问地址
  emit('select', 'ui:/' + item.relativePath);
  emit('update:modelValue', false);
}

function openPreview(url: string) {
  previewUrl.value = url;
}
</script>

<style scoped>
.static-image-picker__empty {
  padding: 40px;
}

.static-image-picker__layout {
  display: grid;
  grid-template-columns: 240px minmax(0, 1fr);
  gap: 16px;
  min-height: 560px;
}

/* 左侧目录树 */
.static-image-picker__tree {
  display: flex;
  flex-direction: column;
  border: 1px solid var(--admin-border-subtle);
  border-radius: var(--admin-radius-sm);
  background: #fafbfc;
  overflow: hidden;
}

.static-image-picker__tree-header {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 10px 12px;
  border-bottom: 1px solid var(--admin-border-subtle);
  font-weight: 600;
  font-size: 13px;
  color: var(--admin-text-secondary);
}

.static-image-picker__tree-loading {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 40px;
  color: var(--admin-text-muted);
}

.static-image-picker__tree :deep(.el-tree) {
  background: transparent;
  padding: 8px 4px;
  flex: 1;
  overflow: auto;
}

.static-image-picker__tree :deep(.el-tree-node__content) {
  height: 32px;
  border-radius: 4px;
}

.static-image-picker__tree :deep(.el-tree-node__content:hover) {
  background: var(--el-fill-color-light);
}

.static-image-picker__tree :deep(.el-tree-node.is-current > .el-tree-node__content) {
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
}

.static-image-picker__tree-node {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
  flex: 1;
}

.static-image-picker__tree-node .el-icon {
  font-size: 16px;
  color: var(--el-color-warning);
}

.static-image-picker__tree-label {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.static-image-picker__tree-count {
  flex-shrink: 0;
  min-width: 20px;
  padding: 0 6px;
  border-radius: 10px;
  background: var(--el-fill-color);
  color: var(--admin-text-muted);
  font-size: 11px;
  text-align: center;
}

/* 右侧主区域 */
.static-image-picker__main {
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
}

.static-image-picker__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--admin-border-subtle);
}

.static-image-picker__search {
  flex-shrink: 0;
  width: 200px;
}

.static-image-picker__breadcrumb {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px;
  min-width: 0;
}

.static-image-picker__crumb {
  border: 0;
  background: transparent;
  color: var(--el-color-primary);
  cursor: pointer;
  padding: 0;
  font-size: 13px;
}

.static-image-picker__crumb.is-current {
  color: var(--admin-text);
  font-weight: 600;
}

.static-image-picker__crumb:hover:not(.is-current) {
  text-decoration: underline;
}

.static-image-picker__separator {
  color: var(--admin-text-muted);
  margin: 0 2px;
}

.static-image-picker__total {
  flex-shrink: 0;
  color: var(--admin-text-muted);
  font-size: 12px;
}

.static-image-picker__loading {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  flex: 1;
  color: var(--admin-text-muted);
}

.static-image-picker__grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(120px, 1fr));
  gap: 10px;
  flex: 1;
  overflow-y: auto;
  padding: 4px;
  align-content: start;
}

.static-image-picker__item {
  position: relative;
  display: grid;
  gap: 4px;
  min-width: 0;
}

/* 缩略图右上角的“查看大图”按钮，悬停显示 */
.static-image-picker__zoom {
  position: absolute;
  top: 10px;
  right: 10px;
  z-index: 1;
  display: none;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  padding: 0;
  border: 0;
  border-radius: 6px;
  background: rgba(0, 0, 0, 0.55);
  color: #fff;
  cursor: pointer;
}

.static-image-picker__item:hover .static-image-picker__zoom {
  display: flex;
}

.static-image-picker__zoom:hover {
  background: rgba(0, 0, 0, 0.78);
}

.static-image-picker__select {
  display: grid;
  gap: 4px;
  min-width: 0;
  width: 100%;
  padding: 6px;
  border: 1px solid var(--admin-border-subtle);
  border-radius: var(--admin-radius-sm);
  background: #fff;
  color: inherit;
  cursor: pointer;
  text-align: left;
  transition: border-color 0.2s, box-shadow 0.2s;
}

.static-image-picker__select:hover {
  border-color: var(--el-color-primary);
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.08);
}

.static-image-picker__select img {
  width: 100%;
  height: 88px;
  object-fit: cover;
  border-radius: 3px;
  background: #f5f5f5;
}

.static-image-picker__select span {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 11px;
  color: var(--admin-text-muted);
  line-height: 1.3;
}

@media (max-width: 768px) {
  .static-image-picker__layout {
    grid-template-columns: 1fr;
  }

  .static-image-picker__tree {
    max-height: 200px;
  }
}
</style>
