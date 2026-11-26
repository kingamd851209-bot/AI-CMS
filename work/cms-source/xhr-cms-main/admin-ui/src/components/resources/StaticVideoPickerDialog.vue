<template>
  <el-dialog
    :model-value="modelValue"
    title="选择静态视频"
    width="960px"
    class="static-video-picker"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div v-if="!siteId" class="static-video-picker__empty">
      <el-empty description="未选择站点" />
    </div>

    <div v-else class="static-video-picker__layout">
      <!-- 左侧目录树 -->
      <aside class="static-video-picker__tree">
        <div class="static-video-picker__tree-header">
          <el-icon><FolderOpened /></el-icon>
          <span>目录</span>
        </div>
        <div v-if="loading" class="static-video-picker__tree-loading">
          <el-icon class="is-loading"><Loading /></el-icon>
        </div>
        <el-tree
          v-else
          ref="treeRef"
          :data="treeData"
          :props="treeProps"
          node-key="path"
          :default-expanded-keys="defaultExpandedKeys"
          :current-node-key="currentTreeNodeKey"
          highlight-current
          :expand-on-click-node="false"
          @node-click="onNodeClick"
        >
          <template #default="{ data }">
            <span class="static-video-picker__tree-node">
              <el-icon v-if="data.isFolder"><Folder /></el-icon>
              <span class="static-video-picker__tree-label">{{ data.name }}</span>
              <span v-if="data.videoCount > 0" class="static-video-picker__tree-count">{{ data.videoCount }}</span>
            </span>
          </template>
        </el-tree>
      </aside>

      <!-- 右侧视频列表 -->
      <main class="static-video-picker__main">
        <header class="static-video-picker__header">
          <el-input
            v-model="searchKeyword"
            placeholder="搜索文件名"
            clearable
            class="static-video-picker__search"
          >
            <template #prefix>
              <el-icon><Search /></el-icon>
            </template>
          </el-input>
          <template v-if="!searchKeyword">
            <nav class="static-video-picker__breadcrumb">
              <template v-for="(crumb, index) in breadcrumbs" :key="`${crumb.path}-${index}`">
                <span v-if="index > 0" class="static-video-picker__separator">/</span>
                <button
                  type="button"
                  class="static-video-picker__crumb"
                  :class="{ 'is-current': index === breadcrumbs.length - 1 }"
                  @click="navigateTo(crumb.path)"
                >
                  {{ crumb.name }}
                </button>
              </template>
            </nav>
            <span v-if="videos.length" class="static-video-picker__total">{{ videos.length }} 个视频</span>
          </template>
          <span v-else-if="filteredVideos.length" class="static-video-picker__total">{{ filteredVideos.length }} 个结果</span>
        </header>

        <div v-if="loading" class="static-video-picker__loading">
          <el-icon class="is-loading"><Loading /></el-icon>
          <span>加载中...</span>
        </div>

        <el-empty v-else-if="!displayVideos.length" :description="searchKeyword ? '未找到匹配的视频' : '当前目录暂无视频'" />

        <div v-else class="static-video-picker__grid">
          <article
            v-for="(item, index) in displayVideos"
            :key="item.relativePath"
            class="static-video-picker__item"
            :data-testid="`static-video-${index}`"
          >
            <button
              type="button"
              class="static-video-picker__select"
              :data-testid="`static-video-select-${index}`"
              @click="selectVideo(item)"
            >
              <video :src="getPreviewUrl(item)" class="static-video-picker__preview" muted />
              <span :title="item.fileName">{{ item.fileName }}</span>
            </button>
          </article>
        </div>
      </main>
    </div>

    <template #footer>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { http } from '@/api/http';
import type { ResourceContext } from '@/api/resourceReferences';
import { computed, ref, watch } from 'vue';
import { Loading, Folder, FolderOpened, Search } from '@element-plus/icons-vue';
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
const treeRef = ref<{ setCurrentKey: (key: string) => void } | null>(null);

const VIDEO_EXTENSIONS = ['mp4', 'webm', 'ogg', 'mov', 'avi', 'mkv', 'flv', 'swf'];

// 文件类型分组定义
const VIDEO_TYPE_GROUPS: Array<{ name: string; type: string; extensions: string[] }> = [
  { name: 'MP4 视频', type: 'mp4', extensions: ['mp4'] },
  { name: 'Flash 视频', type: 'flash', extensions: ['flv', 'swf'] },
  { name: '其他视频', type: 'other', extensions: ['webm', 'ogg', 'mov', 'avi', 'mkv'] }
];

interface TreeNode {
  path: string;
  name: string;
  isFolder: boolean;
  videoCount: number;
  children?: TreeNode[];
  isTypeGroup?: boolean;
}

interface VideoItem extends UiPackageFileInfo {
  previewUrl: string;
}

// 构建目录树数据
const treeData = computed<TreeNode[]>(() => {
  const dirMap = new Map<string, { name: string; parentPath: string; videoCount: number }>();

  // 第一遍遍历：收集所有目录（基于所有文件，不只是视频）
  for (const file of allFiles.value) {
    const parts = file.relativePath.split('/').filter(Boolean);

    for (let i = 0; i < parts.length - 1; i++) {
      const dirPath = parts.slice(0, i + 1).join('/');
      const dirName = parts[i];
      const parentPath = i > 0 ? parts.slice(0, i).join('/') : '__root__';

      if (!dirMap.has(dirPath)) {
        dirMap.set(dirPath, { name: dirName, parentPath, videoCount: 0 });
      }
    }
  }

  // 第二遍遍历：统计每个目录的视频数量
  for (const file of allFiles.value) {
    const ext = file.relativePath.toLowerCase().split('.').pop() || '';
    if (!VIDEO_EXTENSIONS.includes(ext)) continue;

    const parts = file.relativePath.split('/').filter(Boolean);
    const dirPath = parts.length > 1 ? parts.slice(0, -1).join('/') : '';
    if (dirPath === '') continue;
    const dir = dirMap.get(dirPath);
    if (dir) {
      dir.videoCount++;
    }
  }

  // 递归构建树节点
  function buildNode(nodePath: string, nodeName: string, nodeVideoCount: number): TreeNode {
    const childParentKey = nodePath === '' ? '__root__' : nodePath;

    const childDirs = Array.from(dirMap.entries())
      .filter(([p, info]) => info.parentPath === childParentKey)
      .map(([p, info]) => buildNode(p, info.name, info.videoCount))
      .sort((a, b) => a.name.localeCompare(b.name, 'zh-CN'));

    const totalCount = nodeVideoCount + childDirs.reduce((sum, child) => sum + child.videoCount, 0);

    return {
      path: nodePath,
      name: nodeName,
      isFolder: true,
      videoCount: totalCount,
      children: childDirs.length > 0 ? childDirs : undefined
    };
  }

  // 统计根目录下的视频
  const rootVideoCount = allFiles.value.filter(f => {
    const ext = f.relativePath.toLowerCase().split('.').pop() || '';
    return VIDEO_EXTENSIONS.includes(ext) && !f.relativePath.includes('/');
  }).length;

  // 构建物理目录树
  const physicalRootNode = buildNode('', '静态资源', rootVideoCount);

  // 构建类型分组虚拟文件夹
  const typeGroupNodes: TreeNode[] = VIDEO_TYPE_GROUPS.map((group) => {
    const count = allFiles.value.filter(f => {
      const ext = f.relativePath.toLowerCase().split('.').pop() || '';
      return group.extensions.includes(ext);
    }).length;

    return {
      path: `__type__${group.type}`,
      name: group.name,
      isFolder: true,
      isTypeGroup: true,
      videoCount: count,
      children: undefined
    };
  });

  // 合并物理目录和类型分组，使用 __root__ 作为根节点 key
  const physicalChildren = physicalRootNode.children || [];
  const allChildren = [...typeGroupNodes, ...physicalChildren];

  return [{
    path: '__root__',
    name: '静态资源',
    isFolder: true,
    videoCount: physicalRootNode.videoCount,
    children: allChildren.length > 0 ? allChildren : undefined
  }];
});

const defaultExpandedKeys = computed(() => ['__root__']);

// 映射 currentPath 到 tree node key
const currentTreeNodeKey = computed(() => {
  return currentPath.value === '' ? '__root__' : currentPath.value;
});

const treeProps = {
  label: 'name',
  children: 'children'
};

// 当前目录的视频
const videos = computed<VideoItem[]>(() => {
  // 检查是否是类型分组路径
  const typeGroup = VIDEO_TYPE_GROUPS.find(g => currentPath.value === `__type__${g.type}`);

  return allFiles.value
    .filter((file) => {
      const ext = file.relativePath.toLowerCase().split('.').pop() || '';
      if (!VIDEO_EXTENSIONS.includes(ext)) return false;

      // 如果是类型分组路径，按扩展名过滤
      if (typeGroup) {
        return typeGroup.extensions.includes(ext);
      }

      // 否则按目录路径过滤
      const dirPath = file.relativePath.split('/').slice(0, -1).join('/');
      return dirPath === currentPath.value;
    })
    .map((file) => ({
      ...file,
      previewUrl: getPreviewUrl(file)
    }))
    .sort((a, b) => a.fileName.localeCompare(b.fileName, 'zh-CN'));
});

// 根据搜索关键字过滤的所有视频
const filteredVideos = computed<VideoItem[]>(() => {
  const keyword = searchKeyword.value.trim().toLowerCase();
  if (!keyword) return [];

  return allFiles.value
    .filter((file) => {
      const ext = file.relativePath.toLowerCase().split('.').pop() || '';
      if (!VIDEO_EXTENSIONS.includes(ext)) return false;
      return file.fileName.toLowerCase().includes(keyword);
    })
    .map((file) => ({
      ...file,
      previewUrl: getPreviewUrl(file)
    }))
    .sort((a, b) => a.fileName.localeCompare(b.fileName, 'zh-CN'));
});

// 显示的视频列表（搜索时显示搜索结果，否则显示当前目录）
const displayVideos = computed<VideoItem[]>(() => {
  return searchKeyword.value.trim() ? filteredVideos.value : videos.value;
});

// 面包屑导航
const breadcrumbs = computed(() => {
  const crumbs = [{ name: '静态资源', path: '' }];

  // 检查是否是类型分组路径
  const typeGroup = VIDEO_TYPE_GROUPS.find(g => currentPath.value === `__type__${g.type}`);
  if (typeGroup) {
    crumbs.push({ name: typeGroup.name, path: currentPath.value });
    return crumbs;
  }

  // 物理目录路径
  const segments = currentPath.value.split('/').filter(Boolean);
  let path = '';
  for (const segment of segments) {
    path = path ? `${path}/${segment}` : segment;
    crumbs.push({ name: segment, path });
  }
  return crumbs;
});

watch(
  () => props.modelValue,
  async (open) => {
    if (open && siteId.value) {
      currentPath.value = '';
      await loadFiles();
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
    ElMessage.error(error instanceof Error ? error.message : '静态视频加载失败');
  } finally {
    loading.value = false;
  }
}

function getPreviewUrl(file: UiPackageFileInfo): string {
  return baseUrl.value + file.relativePath;
}

function onNodeClick(data: TreeNode) {
  // 根节点 __root__ 对应物理根目录 ''
  currentPath.value = data.path === '__root__' ? '' : data.path;
}

function navigateTo(path: string) {
  currentPath.value = path;
  // 映射物理根目录 '' 到 tree node key '__root__'
  treeRef.value?.setCurrentKey(path === '' ? '__root__' : path);
}

function selectVideo(item: VideoItem) {
  // 使用 ui:/ 前缀标识 UIPackage 相对路径，访问时再拼接 UIPackage 访问地址
  emit('select', 'ui:/' + item.relativePath);
  emit('update:modelValue', false);
}
</script>

<style scoped>
.static-video-picker__empty {
  padding: 40px;
}

.static-video-picker__layout {
  display: grid;
  grid-template-columns: 220px minmax(0, 1fr);
  gap: 16px;
  min-height: 420px;
}

/* 左侧目录树 */
.static-video-picker__tree {
  display: flex;
  flex-direction: column;
  border: 1px solid var(--admin-border-subtle);
  border-radius: var(--admin-radius-sm);
  background: #fafbfc;
  overflow: hidden;
}

.static-video-picker__tree-header {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 10px 12px;
  border-bottom: 1px solid var(--admin-border-subtle);
  font-weight: 600;
  font-size: 13px;
  color: var(--admin-text-secondary);
}

.static-video-picker__tree-loading {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 40px;
  color: var(--admin-text-muted);
}

.static-video-picker__tree :deep(.el-tree) {
  background: transparent;
  padding: 8px 4px;
  flex: 1;
  overflow: auto;
}

.static-video-picker__tree :deep(.el-tree-node__content) {
  height: 32px;
  border-radius: 4px;
}

.static-video-picker__tree :deep(.el-tree-node__content:hover) {
  background: var(--el-fill-color-light);
}

.static-video-picker__tree :deep(.el-tree-node.is-current > .el-tree-node__content) {
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
}

.static-video-picker__tree-node {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
  flex: 1;
}

.static-video-picker__tree-node .el-icon {
  font-size: 16px;
  color: var(--el-color-warning);
}

.static-video-picker__tree-label {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.static-video-picker__tree-count {
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
.static-video-picker__main {
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
}

.static-video-picker__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--admin-border-subtle);
}

.static-video-picker__search {
  flex-shrink: 0;
  width: 200px;
}

.static-video-picker__breadcrumb {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px;
  min-width: 0;
}

.static-video-picker__crumb {
  border: 0;
  background: transparent;
  color: var(--el-color-primary);
  cursor: pointer;
  padding: 0;
  font-size: 13px;
}

.static-video-picker__crumb.is-current {
  color: var(--admin-text);
  font-weight: 600;
}

.static-video-picker__crumb:hover:not(.is-current) {
  text-decoration: underline;
}

.static-video-picker__separator {
  color: var(--admin-text-muted);
  margin: 0 2px;
}

.static-video-picker__total {
  flex-shrink: 0;
  color: var(--admin-text-muted);
  font-size: 12px;
}

.static-video-picker__loading {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  flex: 1;
  color: var(--admin-text-muted);
}

.static-video-picker__grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(120px, 1fr));
  gap: 10px;
  flex: 1;
  overflow-y: auto;
  padding: 4px;
  align-content: start;
}

.static-video-picker__item {
  display: grid;
  gap: 4px;
  min-width: 0;
}

.static-video-picker__select {
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

.static-video-picker__select:hover {
  border-color: var(--el-color-primary);
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.08);
}

.static-video-picker__preview {
  width: 100%;
  height: 72px;
  object-fit: cover;
  border-radius: 3px;
  background: #f5f5f5;
}

.static-video-picker__select span {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 11px;
  color: var(--admin-text-muted);
  line-height: 1.3;
}

@media (max-width: 768px) {
  .static-video-picker__layout {
    grid-template-columns: 1fr;
  }

  .static-video-picker__tree {
    max-height: 200px;
  }
}
</style>
