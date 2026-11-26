<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { staticAssetApi, assetCategoryOf, ASSET_TYPE_LABELS, type AssetEntry, type AssetScope, type SiteNavItem } from '@/api/staticAssets';
import StaticAssetSyncDrawer from './StaticAssetSyncDrawer.vue';

interface NavNode {
  id: string;
  label: string;
  scope: AssetScope;
  siteId?: number;
  children?: NavNode[];
}

const sites = ref<SiteNavItem[]>([]);
const currentScope = ref<AssetScope>('GLOBAL');
const currentSiteId = ref<number | undefined>(undefined);
const activeNodeId = ref('GLOBAL');

const currentPath = ref('');
const entries = ref<AssetEntry[]>([]);
const loading = ref(false);
const uploading = ref(false);
const uploadFiles = ref<File[]>([]);
const mkdirDialog = ref(false);
const mkdirName = ref('');
const syncDrawerVisible = ref(false);

const treeProps = { label: 'label', children: 'children' };

const treeData = computed<NavNode[]>(() => {
  const globalNode: NavNode = { id: 'GLOBAL', label: '全局资源', scope: 'GLOBAL' };
  const siteNodes: NavNode[] = sites.value.map((site) => ({
    id: `site-${site.siteId}`,
    label: site.name || site.code || `站点${site.siteId}`,
    scope: 'GLOBAL',
    siteId: site.siteId,
    children: [
      { id: `upload-${site.siteId}`, label: '站点素材', scope: 'SITE_ASSETS', siteId: site.siteId },
      { id: `template-${site.siteId}`, label: '模板资源', scope: 'SITE_TEMPLATE', siteId: site.siteId },
      { id: `uipackage-${site.siteId}`, label: 'UIPackage资源', scope: 'SITE_UIPACKAGE', siteId: site.siteId }
    ]
  }));
  return [globalNode, ...siteNodes];
});

const rootLabel = computed(() => {
  switch (currentScope.value) {
    case 'SITE_ASSETS': return '站点素材';
    case 'SITE_TEMPLATE': return '模板资源';
    case 'SITE_UIPACKAGE': return 'UIPackage资源';
    default: return '根目录';
  }
});

const currentSiteLabel = computed(() => {
  if (currentScope.value === 'GLOBAL') {
    return '全局资源';
  }
  const site = sites.value.find((s) => s.siteId === currentSiteId.value);
  return site ? (site.name || site.code || `站点${site.siteId}`) : '未选择站点';
});

const crumbs = computed(() => {
  const parts = currentPath.value ? currentPath.value.split('/') : [];
  return [{ name: rootLabel.value, path: '' }, ...parts.map((p, i) => ({ name: p, path: parts.slice(0, i + 1).join('/') }))];
});

const dirs = computed(() => entries.value.filter(e => e.dir));
const files = computed(() => entries.value.filter(e => !e.dir));

async function loadSites() {
  try {
    sites.value = await staticAssetApi.nav();
  } catch (e) {
    ElMessage.error('站点列表加载失败');
  }
}

async function loadList() {
  loading.value = true;
  try {
    entries.value = await staticAssetApi.list(currentPath.value, currentScope.value, currentSiteId.value);
  } finally {
    loading.value = false;
  }
}

function selectNode(data: NavNode, node: { isLeaf?: boolean }) {
  if (!node.isLeaf) {
    return;
  }
  currentScope.value = data.scope;
  currentSiteId.value = data.siteId;
  activeNodeId.value = data.id;
  currentPath.value = '';
  loadList();
}

function enter(item: AssetEntry) {
  currentPath.value = item.path;
  loadList();
}

function goTo(path: string) {
  currentPath.value = path;
  loadList();
}

function formatSize(size: number): string {
  if (!size) return '-';
  if (size < 1024) return `${size} B`;
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`;
  return `${(size / (1024 * 1024)).toFixed(1)} MB`;
}

function acceptOf(category: string): string {
  switch (category) {
    case 'images': return 'image/*';
    case 'videos': return 'video/*';
    case 'audios': return 'audio/*';
    case 'pdf': return '.pdf';
    case 'ppt': return '.ppt,.pptx';
    case 'excel': return '.xls,.xlsx,.csv';
    case 'word': return '.doc,.docx';
    default: return '';
  }
}

async function doUploadFiles() {
  if (uploadFiles.value.length === 0) {
    ElMessage.warning('请先选择文件');
    return;
  }
  uploading.value = true;
  try {
    let ok = 0;
    let landedDir = '';
    for (const f of uploadFiles.value) {
      try {
        const rel = await staticAssetApi.upload(f, currentPath.value, currentScope.value, currentSiteId.value);
        ok++;
        if (!landedDir && rel.includes('/')) {
          landedDir = rel.split('/')[0];
        }
      } catch (e) {
        ElMessage.error(`上传 ${f.name} 失败`);
      }
    }
    ElMessage.success(`上传完成：${ok}/${uploadFiles.value.length}`);
    uploadFiles.value = [];
    // 从根目录上传且文件被归入分类子目录时，自动进入该目录让用户立即看到
    if (currentScope.value === 'GLOBAL' && currentPath.value === '' && landedDir) {
      currentPath.value = landedDir;
    }
    await loadList();
  } finally {
    uploading.value = false;
  }
}

function onFileChange(file: { raw?: File }) {
  if (file.raw) {
    uploadFiles.value.push(file.raw);
  }
}

function openMkdir() {
  mkdirName.value = '';
  mkdirDialog.value = true;
}

async function confirmMkdir() {
  const name = mkdirName.value.trim();
  if (!name) {
    ElMessage.warning('请输入文件夹名');
    return;
  }
  await staticAssetApi.mkdir(currentPath.value, name, currentScope.value, currentSiteId.value);
  mkdirDialog.value = false;
  await loadList();
}

async function confirmDelete(entry: AssetEntry) {
  await ElMessageBox.confirm(`确认删除「${entry.name}」？`, '删除确认', { type: 'warning' });
  await staticAssetApi.remove(entry.path, currentScope.value, currentSiteId.value);
  ElMessage.success('删除成功');
  await loadList();
}

function downloadOf(item: AssetEntry): string {
  return staticAssetApi.downloadUrl(item.path, currentScope.value, currentSiteId.value);
}

function isImage(item: AssetEntry): boolean {
  return assetCategoryOf(item.name) === 'images';
}

function categoryLabel(item: AssetEntry): string {
  return ASSET_TYPE_LABELS[assetCategoryOf(item.name)] ?? '文件';
}

function onSynced() {
  loadList();
}

onMounted(() => {
  loadSites();
  loadList();
});
</script>

<template>
  <section class="static-asset">
    <header class="static-asset__header">
      <div>
        <h1>静态资源</h1>
        <p>全局资源库与各站点的 upload/global/模板/UIPackage 资源浏览与上传。</p>
      </div>
      <div class="static-asset__header-actions">
        <el-button type="primary" plain @click="syncDrawerVisible = true">资源同步</el-button>
        <el-button :loading="loading" @click="loadList">刷新</el-button>
      </div>
    </header>

    <div class="static-asset__layout">
      <aside class="static-asset__tree">
        <div class="static-asset__tree-header">
          <span>资源导航</span>
          <span>{{ sites.length }} 站点</span>
        </div>
        <div class="static-asset__tree-body">
          <el-tree
            :data="treeData"
            :props="treeProps"
            node-key="id"
            default-expand-all
            highlight-current
            :current-node-key="activeNodeId"
            @node-click="selectNode"
          >
            <template #default="{ data }">
              <span>{{ data.label }}</span>
            </template>
          </el-tree>
        </div>
      </aside>

      <main class="static-asset__main">
        <div class="static-asset__toolbar">
          <div class="static-asset__crumbs">
            <span v-for="(c, i) in crumbs" :key="c.path">
              <template v-if="i > 0">/</template>
              <span role="button" tabindex="0" @click="goTo(c.path)" @keydown.enter="goTo(c.path)" @keydown.space.prevent="goTo(c.path)">{{ c.name }}</span>
            </span>
          </div>
          <el-upload
            :auto-upload="false"
            :show-file-list="false"
            multiple
            accept=".jpg,.jpeg,.png,.gif,.webp,.svg,.bmp,.mp4,.webm,.mov,.mp3,.wav,.ogg,.pdf,.ppt,.pptx,.xls,.xlsx,.csv,.doc,.docx"
            :on-change="onFileChange"
          >
            <el-button :disabled="uploading">选择文件</el-button>
          </el-upload>
          <el-button type="primary" :loading="uploading" @click="doUploadFiles">
            上传{{ uploadFiles.length > 0 ? `(${uploadFiles.length})` : '' }}
          </el-button>
          <el-button @click="openMkdir">新建文件夹</el-button>
        </div>

        <div class="static-asset__grid" v-loading="loading">
          <el-empty v-if="!loading && entries.length === 0" description="目录为空" />
          <template v-else>
            <template v-if="dirs.length">
              <div class="static-asset__section-title">文件夹</div>
              <div class="static-asset__items">
                <div v-for="item in dirs" :key="item.path" class="static-asset-item" role="button" tabindex="0" @click="enter(item)" @keydown.enter="enter(item)" @keydown.space.prevent="enter(item)">
                  <div class="static-asset__thumb">
                    <i class="el-icon"><svg viewBox="0 0 1024 1024" style="width: 100%; height: 100%"><path d="M938.667 298.667H512L426.667 213.333H85.333a42.667 42.667 0 0 0-42.666 42.667v554.667a42.667 42.667 0 0 0 42.666 42.666h853.334a42.667 42.667 0 0 0 42.666-42.666V341.333a42.667 42.667 0 0 0-42.666-42.666z" fill="#FFC53D"/></svg></i>
                  </div>
                  <el-icon class="static-asset-item__delete" @click.stop="confirmDelete(item)"><el-icon><span style="color: var(--el-color-danger)" class="el-icon-Delete">×</span></el-icon></el-icon>
                  <div class="static-asset-item__name">{{ item.name }}</div>
                </div>
              </div>
            </template>

            <template v-if="files.length">
              <div class="static-asset__section-title">文件（{{ files.length }}）</div>
              <div class="static-asset__items">
                <div v-for="item in files" :key="item.path" class="static-asset-item">
                  <el-icon class="static-asset-item__delete" @click.stop="confirmDelete(item)"><span style="color: var(--el-color-danger)">×</span></el-icon>
                  <div class="static-asset__thumb">
                    <el-image
                      v-if="isImage(item)"
                      :src="downloadOf(item)"
                      :preview-src-list="[downloadOf(item)]"
                      :preview-teleported="true"
                      fit="cover"
                      style="width: 100%; height: 100%"
                    />
                    <span v-else>{{ categoryLabel(item) }}</span>
                  </div>
                  <a class="static-asset-item__name" :href="downloadOf(item)" :download="item.name" :title="item.name">
                    {{ item.name }}
                  </a>
                  <div class="static-asset-item__size">{{ formatSize(item.size) }}</div>
                </div>
              </div>
            </template>
          </template>
        </div>
      </main>
    </div>

    <el-dialog v-model="mkdirDialog" title="新建文件夹" width="400px">
      <el-input v-model="mkdirName" placeholder="请输入文件夹名" @keyup.enter="confirmMkdir" />
      <template #footer>
        <el-button @click="mkdirDialog = false">取消</el-button>
        <el-button type="primary" @click="confirmMkdir">确定</el-button>
      </template>
    </el-dialog>

    <StaticAssetSyncDrawer
      v-model="syncDrawerVisible"
      :scope="currentScope"
      :site-id="currentSiteId"
      :site-label="currentSiteLabel"
      :path="currentPath"
      @synced="onSynced"
    />
  </section>
</template>

<style>
.static-asset {
  display: flex;
  flex-direction: column;
  height: 100%;
  padding: 0;
  gap: var(--admin-card-gap);
}

.static-asset__header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.static-asset__header h1 { margin: 0; font-size: var(--admin-font-size-page); }
.static-asset__header p { margin: 4px 0 0; color: var(--el-text-color-secondary); font-size: 13px; }
.static-asset__header-actions { display: flex; flex-wrap: wrap; gap: 8px; }

.static-asset__layout {
  display: flex;
  flex: 1;
  gap: var(--admin-card-gap);
  min-height: 0;
}

.static-asset__tree {
  width: 260px;
  flex-shrink: 0;
  border: 1px solid var(--el-border-color-light);
  border-radius: var(--admin-card-radius);
  background: var(--el-bg-color);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.static-asset__tree-header {
  padding: 10px 12px;
  border-bottom: 1px solid var(--el-border-color-light);
  font-weight: 600;
  display: flex;
  justify-content: space-between;
}
.static-asset__tree-body {
  flex: 1;
  overflow: auto;
  padding: 8px;
}

.static-asset__main {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: var(--admin-card-gap);
  min-width: 0;
}

.static-asset__toolbar {
  display: flex;
  gap: 8px;
  align-items: center;
  flex-wrap: wrap;
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  padding: 10px 12px;
}

.static-asset__crumbs {
  font-size: 13px;
  color: var(--el-color-primary);
  cursor: pointer;
  display: flex;
  gap: 4px;
  align-items: center;
  flex: 1;
  min-width: 0;
  overflow: hidden;
  white-space: nowrap;
}

.static-asset__grid {
  flex: 1;
  overflow: auto;
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  background: var(--el-bg-color);
  padding: 12px;
}

.static-asset__section-title {
  font-weight: 600;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  margin: 8px 0;
}

.static-asset__items {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(120px, 1fr));
  gap: 10px;
}

.static-asset-item {
  cursor: pointer;
  border-radius: 8px;
  padding: 10px 6px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  border: 1px solid transparent;
  position: relative;
}
.static-asset-item:hover {
  background: var(--el-fill-color-light);
  border-color: var(--el-border-color);
}
.static-asset-item:focus-visible { outline: 2px solid var(--el-color-primary); outline-offset: 2px; }

.static-asset__thumb {
  width: 56px;
  height: 56px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  background: var(--el-fill-color-lighter);
  border-radius: 8px;
  overflow: hidden;
}
.static-asset__thumb .el-icon { font-size: 26px; }
/* 预览图统一尺寸：不按原图大小渲染，点击后由 el-image 预览查看原图 */
.static-asset__thumb .el-image,
.static-asset__thumb img {
  width: 100%;
  height: 100%;
  display: block;
  object-fit: cover;
}

.static-asset-item__name {
  font-size: 12px;
  text-align: center;
  word-break: break-all;
  max-width: 100%;
  line-height: 1.3;
  color: var(--el-text-color-primary);
}

.static-asset-item__size {
  font-size: 11px;
  color: var(--el-text-color-secondary);
}

.static-asset-item__delete {
  position: absolute;
  top: 4px;
  right: 4px;
  visibility: hidden;
}
.static-asset-item:hover .static-asset-item__delete {
  visibility: visible;
}
.static-asset-item:focus-within .static-asset-item__delete { visibility: visible; }

@media (max-width: 760px) {
  .static-asset { height: auto; min-height: 100%; padding: 0; }
  .static-asset__header { align-items: flex-start; gap: 12px; flex-wrap: wrap; }
  .static-asset__layout { flex-direction: column; }
  .static-asset__tree { width: auto; min-height: 180px; max-height: 300px; }
  .static-asset__grid { min-height: 360px; }
  .static-asset__toolbar { align-items: flex-start; }
  .static-asset__crumbs { flex-basis: 100%; }
}
</style>
