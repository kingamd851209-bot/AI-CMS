<template>
  <section
    v-if="pageMode"
    class="resource-picker resource-picker--page"
    data-testid="resource-picker-page"
  >
    <section class="resource-picker__body" data-page-mode="true">
      <header class="resource-picker__toolbar">
        <el-select v-model="resourceScope" size="small" style="width: 130px" aria-label="资源目录范围">
          <el-option v-if="scope !== 'global'" label="站点素材" value="site" />
          <el-option label="全局素材" value="global" />
        </el-select>
        <nav class="resource-picker__breadcrumb" aria-label="资源目录">
          <template v-for="(crumb, index) in breadcrumbs" :key="`${crumb.path}-${index}`">
            <span v-if="index > 0" class="resource-picker__separator">{{ ' / ' }}</span>
            <button type="button" class="resource-picker__crumb" @click="loadResources(crumb.path)">
              {{ crumb.name }}
            </button>
          </template>
        </nav>
        <label class="resource-picker__upload">
          <span class="resource-picker__upload-button">本地上传</span>
          <input
            data-testid="resource-picker-upload-input"
            type="file"
            :accept="resolvedAccept"
            :multiple="multiple"
            @change="uploadFromInput"
          />
        </label>
      </header>

      <p v-if="loading" class="resource-picker__status">正在加载资源...</p>
      <p v-else-if="!directories.length && !filteredItems.length" class="resource-picker__status">当前目录暂无资源。</p>

      <div v-if="manageable && selectedResourceItems.length" class="resource-picker__bulkbar">
        <span data-testid="resource-selection-summary">已选择 {{ selectedResourceItems.length }} 个资源</span>
        <button type="button" data-testid="resource-copy-selected" @click="copySelectedPaths">复制路径</button>
        <button type="button" data-testid="resource-delete-selected" @click="deleteSelectedItems">批量删除</button>
        <button type="button" @click="clearCheckedItems">取消选择</button>
      </div>

      <div v-if="directories.length" class="resource-picker__dirs">
        <button
          v-for="(directory, index) in directories"
          :key="directory.path"
          type="button"
          class="resource-picker__dir"
          :data-testid="`resource-dir-${index}`"
          @click="loadResources(directory.path)"
        >
          <strong>{{ directory.name }}</strong>
          <small v-if="directory.modifiedAt">{{ directory.modifiedAt }}</small>
        </button>
      </div>

      <div v-if="filteredItems.length" class="resource-picker__grid">
        <article
          v-for="(item, index) in filteredItems"
          :key="`${item.url}-${index}`"
          class="resource-picker__item"
          :data-testid="`resource-card-${index}`"
          @contextmenu.prevent="openContextMenu(item, $event)"
        >
          <button
            v-if="manageable"
            type="button"
            class="resource-picker__check"
            :class="{ 'is-selected': isChecked(item) }"
            :data-testid="`resource-select-toggle-${index}`"
            @click.stop="toggleChecked(item)"
          >
            {{ isChecked(item) ? '已选' : '选择' }}
          </button>
          <button
            type="button"
            class="resource-picker__preview-open"
            :data-testid="`resource-preview-open-${index}`"
            @click.stop="openPreview(item)"
          >
            预览
          </button>
          <button
            type="button"
            class="resource-picker__select"
            :data-testid="`resource-item-${index}`"
            @click="selectItem(item)"
          >
            <ResourcePreview :url="item.adminPreviewUrl || item.url" :name="item.name" :media-type="item.mediaType" />
            <span>{{ item.name }}</span>
          </button>
        </article>
      </div>

      <footer v-if="showPagination" class="resource-picker__pagination">
        <span data-testid="resource-page-summary">第 {{ page }} / {{ totalPages }} 页，共 {{ total }} 个资源</span>
        <button type="button" data-testid="resource-prev-page" :disabled="page <= 1" @click="goToPage(page - 1)">上一页</button>
        <button type="button" data-testid="resource-next-page" :disabled="page >= totalPages" @click="goToPage(page + 1)">下一页</button>
      </footer>

      <div
        v-if="contextMenuItem"
        class="resource-picker__context"
        data-testid="resource-context-menu"
        :style="contextMenuStyle"
      >
        <strong>{{ contextMenuItem.name }}</strong>
        <button type="button" data-testid="resource-context-copy" @click="copyResourcePath(contextMenuItem)">复制路径</button>
        <button v-if="manageable" type="button" data-testid="resource-context-rename" @click="startRenameFromContext">重命名</button>
        <button v-if="manageable" type="button" data-testid="resource-context-delete" @click="deleteItemFromContext">删除</button>
        <button type="button" @click="contextMenuItem = null">关闭</button>
      </div>

      <div v-if="renamingItem" class="resource-picker__rename">
        <strong>重命名：{{ renamingItem.name }}</strong>
        <el-input
          v-model="renameName"
          data-testid="resource-rename-input"
          placeholder="请输入新文件名"
        />
        <el-button data-testid="resource-rename-confirm" type="primary" @click="confirmRename">
          确认
        </el-button>
      </div>
    </section>
  </section>

  <el-dialog
    v-else
    :model-value="modelValue"
    :title="dialogTitle"
    width="860px"
    class="resource-picker"
    @update:model-value="handleDialogValueChange"
  >
    <section class="resource-picker__body" data-page-mode="false">
      <header class="resource-picker__toolbar">
        <el-select v-model="resourceScope" size="small" style="width: 130px" aria-label="资源目录范围">
          <el-option v-if="scope !== 'global'" label="站点素材" value="site" />
          <el-option label="全局素材" value="global" />
        </el-select>
        <nav class="resource-picker__breadcrumb" aria-label="资源目录">
          <template v-for="(crumb, index) in breadcrumbs" :key="`${crumb.path}-${index}`">
            <span v-if="index > 0" class="resource-picker__separator">{{ ' / ' }}</span>
            <button type="button" class="resource-picker__crumb" @click="loadResources(crumb.path)">
              {{ crumb.name }}
            </button>
          </template>
        </nav>
        <label class="resource-picker__upload">
          <span class="resource-picker__upload-button">本地上传</span>
          <input
            data-testid="resource-picker-upload-input"
            type="file"
            :accept="resolvedAccept"
            :multiple="multiple"
            @change="uploadFromInput"
          />
        </label>
      </header>

      <p v-if="loading" class="resource-picker__status">正在加载资源...</p>
      <p v-else-if="!directories.length && !filteredItems.length" class="resource-picker__status">当前目录暂无资源。</p>

      <div v-if="manageable && selectedResourceItems.length" class="resource-picker__bulkbar">
        <span data-testid="resource-selection-summary">已选择 {{ selectedResourceItems.length }} 个资源</span>
        <button type="button" data-testid="resource-copy-selected" @click="copySelectedPaths">复制路径</button>
        <button type="button" data-testid="resource-delete-selected" @click="deleteSelectedItems">批量删除</button>
        <button type="button" @click="clearCheckedItems">取消选择</button>
      </div>

      <div v-if="directories.length" class="resource-picker__dirs">
        <button
          v-for="(directory, index) in directories"
          :key="directory.path"
          type="button"
          class="resource-picker__dir"
          :data-testid="`resource-dir-${index}`"
          @click="loadResources(directory.path)"
        >
          <strong>{{ directory.name }}</strong>
          <small v-if="directory.modifiedAt">{{ directory.modifiedAt }}</small>
        </button>
      </div>

      <div v-if="filteredItems.length" class="resource-picker__grid">
        <article
          v-for="(item, index) in filteredItems"
          :key="`${item.url}-${index}`"
          class="resource-picker__item"
          :data-testid="`resource-card-${index}`"
          @contextmenu.prevent="openContextMenu(item, $event)"
        >
          <button
            v-if="manageable"
            type="button"
            class="resource-picker__check"
            :class="{ 'is-selected': isChecked(item) }"
            :data-testid="`resource-select-toggle-${index}`"
            @click.stop="toggleChecked(item)"
          >
            {{ isChecked(item) ? '已选' : '选择' }}
          </button>
          <button
            type="button"
            class="resource-picker__preview-open"
            :data-testid="`resource-preview-open-${index}`"
            @click.stop="openPreview(item)"
          >
            预览
          </button>
          <button
            type="button"
            class="resource-picker__select"
            :data-testid="`resource-item-${index}`"
            @click="selectItem(item)"
          >
            <ResourcePreview :url="item.adminPreviewUrl || item.url" :name="item.name" :media-type="item.mediaType" />
            <span>{{ item.name }}</span>
          </button>
        </article>
      </div>

      <footer v-if="showPagination" class="resource-picker__pagination">
        <span data-testid="resource-page-summary">第 {{ page }} / {{ totalPages }} 页，共 {{ total }} 个资源</span>
        <button type="button" data-testid="resource-prev-page" :disabled="page <= 1" @click="goToPage(page - 1)">上一页</button>
        <button type="button" data-testid="resource-next-page" :disabled="page >= totalPages" @click="goToPage(page + 1)">下一页</button>
      </footer>

      <div
        v-if="contextMenuItem"
        class="resource-picker__context"
        data-testid="resource-context-menu"
        :style="contextMenuStyle"
      >
        <strong>{{ contextMenuItem.name }}</strong>
        <button type="button" data-testid="resource-context-copy" @click="copyResourcePath(contextMenuItem)">复制路径</button>
        <button v-if="manageable" type="button" data-testid="resource-context-rename" @click="startRenameFromContext">重命名</button>
        <button v-if="manageable" type="button" data-testid="resource-context-delete" @click="deleteItemFromContext">删除</button>
        <button type="button" @click="contextMenuItem = null">关闭</button>
      </div>

      <div v-if="renamingItem" class="resource-picker__rename">
        <strong>重命名：{{ renamingItem.name }}</strong>
        <el-input
          v-model="renameName"
          data-testid="resource-rename-input"
          placeholder="请输入新文件名"
        />
        <el-button data-testid="resource-rename-confirm" type="primary" @click="confirmRename">
          确认
        </el-button>
      </div>
    </section>
  </el-dialog>

  <div
    v-if="previewItem"
    class="resource-picker__preview-layer"
    data-testid="resource-preview-overlay"
    @click.self="closePreview"
  >
    <section class="resource-picker__preview-panel" role="dialog" aria-modal="true" :aria-label="`预览 ${previewItem.name}`">
      <header class="resource-picker__preview-header">
        <strong>{{ previewItem.name }}</strong>
        <button type="button" @click="closePreview">关闭</button>
      </header>
      <div class="resource-picker__preview-stage">
        <img
          v-if="previewItem.mediaType === 'image'"
          data-testid="resource-preview-image"
          :src="normalizeResourceUrl(previewItem.adminPreviewUrl || previewItem.url)"
          :alt="previewItem.name"
        />
        <video
          v-else-if="previewItem.mediaType === 'video'"
          data-testid="resource-preview-video"
          :src="normalizeResourceUrl(previewItem.adminPreviewUrl || previewItem.url)"
          controls
          playsinline
        />
        <div v-else class="resource-picker__preview-file" data-testid="resource-preview-file">
          <strong>{{ previewItem.name }}</strong>
          <span>{{ previewItem.url }}</span>
          <a v-if="previewItem.adminDownloadUrl" :href="previewItem.adminDownloadUrl">下载</a>
          <button type="button" @click="copyResourcePath(previewItem)">复制路径</button>
        </div>
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { http } from '@/api/http';
import ResourcePreview from './ResourcePreview.vue';
import {
  resourceAccept,
  isAllowedResourceExtension,
  normalizeResourceUrl,
  normalizeAllowedExtensions,
  resourceExtension,
  assetReference,
  type ResourceLibraryItem,
  type ResourceLibraryResponse,
  type ResourceReferenceItem,
  type ResourceReferenceResponse,
  type ResourceRequestType,
  type ResourceUploadResponse
} from './types';
import { listAssets, uploadAsset, type AssetDirectory, type AssetsListItem } from '@/api/assetsUpload';
import { useSiteContextStore } from '@/stores/siteContext';

const props = withDefaults(defineProps<{
  modelValue: boolean;
  type: ResourceRequestType;
  scope?: 'site' | 'global';
  multiple?: boolean;
  title?: string;
  pageMode?: boolean;
  manageable?: boolean;
  allowedExtensions?: string[];
}>(), {
  scope: 'site',
  multiple: false,
  title: '',
  pageMode: false,
  manageable: false,
  allowedExtensions: () => []
});

const emit = defineEmits<{
  'update:modelValue': [value: boolean];
  select: [value: string | string[]];
  delete: [url: string];
}>();

const RESOURCE_PAGE_SIZE = 24;
const loading = ref(false);
const resourceScope = ref(props.scope);
const currentPath = ref('');
const parentPath = ref<string | null>(null);
const directories = ref<ResourceLibraryResponse['directories']>([]);
const items = ref<ResourceLibraryItem[]>([]);
const page = ref(1);
const pageSize = ref(RESOURCE_PAGE_SIZE);
const total = ref(0);
const selectedUrls = ref<string[]>([]);
const checkedUrls = ref<string[]>([]);
const renamingItem = ref<ResourceLibraryItem | null>(null);
const contextMenuItem = ref<ResourceLibraryItem | null>(null);
const contextMenuPosition = ref({ left: 0, top: 0 });
const previewItem = ref<ResourceLibraryItem | null>(null);
const renameName = ref('');

let resourceRequestSeq = 0;
const siteContext = useSiteContextStore();

interface ResourceDeletePlan {
  item: ResourceLibraryItem;
  relativePath: string;
  references: ResourceReferenceItem[];
}

const dialogTitle = computed(() => props.title || '选择资源');
const allowedExtensions = computed(() => normalizeAllowedExtensions(props.allowedExtensions));
const resolvedAccept = computed(() => allowedExtensions.value.length
  ? allowedExtensions.value.map((extension) => `.${extension}`).join(',')
  : resourceAccept(props.type));
const filteredItems = computed(() => items.value.filter((item) => isAllowedItem(item)));
const totalPages = computed(() => Math.max(1, Math.ceil(total.value / pageSize.value)));
const showPagination = computed(() => total.value > pageSize.value || page.value > 1);
const selectedResourceItems = computed(() => filteredItems.value.filter((item) => checkedUrls.value.includes(item.url)));
const contextMenuStyle = computed(() => ({
  left: `${contextMenuPosition.value.left}px`,
  top: `${contextMenuPosition.value.top}px`
}));
const breadcrumbs = computed(() => {
  const segments = currentPath.value.split('/').filter(Boolean);
  const crumbs = [{ name: '全部资源', path: '' }];
  let path = '';
  for (const segment of segments) {
    path = path ? `${path}/${segment}` : segment;
    crumbs.push({ name: segment, path });
  }
  return crumbs;
});

watch(() => [props.modelValue, props.pageMode] as const, ([open, pageMode]) => {
  if (open || pageMode) {
    resetTransientState();
    loadResources('', 1);
  } else {
    resetTransientState();
  }
}, { immediate: true });

watch(() => [props.type, siteContext.currentSite?.id, resourceScope.value] as const, () => {
  if (props.modelValue || props.pageMode) {
    resetTransientState();
    loadResources('', 1);
  }
});

async function loadResources(path: string, nextPage = 1) {
  const requestSeq = ++resourceRequestSeq;
  loading.value = true;
  try {
    const response = await listAssets(props.type, path, nextPage, RESOURCE_PAGE_SIZE, allowedExtensions.value, !siteContext.currentSite?.id ? 'global' : resourceScope.value);
    if (requestSeq !== resourceRequestSeq) {
      return;
    }
    currentPath.value = response.currentPath ?? path;
    parentPath.value = response.parentPath ?? null;
    directories.value = (response.directories ?? []).map((d: AssetDirectory) => ({
      name: d.name,
      path: d.path,
      modifiedAt: ''
    }));
    items.value = (response.items ?? []).map((it: AssetsListItem) => mapAssetItem(it));
    checkedUrls.value = checkedUrls.value.filter((url) => (response.items ?? []).some((item) => item.reference === url));
    contextMenuItem.value = null;
    previewItem.value = null;
    page.value = response.page;
    pageSize.value = response.pageSize;
    total.value = response.total;
  } catch (error) {
    if (requestSeq !== resourceRequestSeq) {
      return;
    }
    ElMessage.error(error instanceof Error ? error.message : '资源加载失败');
  } finally {
    if (requestSeq === resourceRequestSeq) {
      loading.value = false;
    }
  }
}

function mapAssetItem(it: AssetsListItem): ResourceLibraryItem {
  const extension = resourceExtension(it.name);
  let mediaType: 'image' | 'video' | 'file' = 'file';
  if (['jpg', 'jpeg', 'png', 'gif', 'webp', 'svg', 'bmp'].includes(extension)) {
    mediaType = 'image';
  } else if (['mp4', 'webm', 'ogg', 'mov'].includes(extension)) {
    mediaType = 'video';
  }
  return {
    url: it.reference,
    adminPreviewUrl: it.url || undefined,
    adminDownloadUrl: it.url || undefined,
    name: it.name,
    mediaType,
    extension: extension || undefined,
    size: it.fileSize || undefined,
    modifiedAt: it.modifiedAt ? new Date(it.modifiedAt).toISOString() : ''
  };
}

function handleDialogValueChange(value: boolean) {
  if (!value) {
    resetTransientState();
  }
  emit('update:modelValue', value);
}

function resetTransientState() {
  selectedUrls.value = [];
  checkedUrls.value = [];
  renamingItem.value = null;
  contextMenuItem.value = null;
  previewItem.value = null;
  contextMenuPosition.value = { left: 0, top: 0 };
  renameName.value = '';
}

function selectItem(item: ResourceLibraryItem) {
  if (!isAllowedItem(item)) {
    ElMessage.warning('该资源格式不允许用于当前字段');
    return;
  }
  if (!props.multiple) {
    emit('select', item.url);
    emit('update:modelValue', false);
    return;
  }
  if (!selectedUrls.value.includes(item.url)) {
    selectedUrls.value = [...selectedUrls.value, item.url];
  }
  emit('select', [...selectedUrls.value]);
}

async function uploadFromInput(event: Event) {
  const input = event.target as HTMLInputElement;
  const files = Array.from(input.files ?? []);
  if (!files.length) {
    return;
  }
  const disallowedFiles = files.filter((file) => !isAllowedResourceExtension(file.name, props.allowedExtensions));
  if (disallowedFiles.length) {
    ElMessage.warning(`当前字段不允许上传这些文件格式：${disallowedFiles.map((file) => file.name).join('、')}`);
    input.value = '';
    return;
  }
  try {
    const uploadedUrls: string[] = [];
    for (const file of files) {
      const response = await uploadAsset(file, uploadRequestTypeForFile(file.name), !siteContext.currentSite?.id ? 'global' : resourceScope.value);
      const url = response.reference;
      if (url && isAllowedResourceExtension(url, props.allowedExtensions)) {
        uploadedUrls.push(url);
      }
    }
    if (!uploadedUrls.length) {
      throw new Error('上传成功但未返回资源地址');
    }
    await loadResources(currentPath.value);
    if (props.multiple) {
      selectedUrls.value = uniqueUrls([...selectedUrls.value, ...uploadedUrls]);
      emit('select', [...selectedUrls.value]);
    } else {
      emit('select', uploadedUrls[0]);
      emit('update:modelValue', false);
    }
    ElMessage.success('资源已上传');
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '资源上传失败');
  } finally {
    input.value = '';
  }
}

function startRename(item: ResourceLibraryItem) {
  if (!canManage(item)) {
    ElMessage.warning('该资源路径不可在前端执行管理操作');
    return;
  }
  renamingItem.value = item;
  renameName.value = item.name;
}

async function confirmRename() {
  if (!renamingItem.value) {
    return;
  }
  const relativePath = assetReference(renamingItem.value.url);
  const newName = renameName.value.trim();
  if (!relativePath || !newName) {
    ElMessage.warning('请输入安全的新文件名');
    return;
  }
  try {
    const renamed = await http.patch<{ reference: string; url: string | null; name: string }>('/api/admin/v2/cms/public-resources', { path: relativePath, newName });
    selectedUrls.value = selectedUrls.value.map((url) => url === renamingItem.value?.url ? renamed.reference : url);
    ElMessage.success('资源已重命名');
    renamingItem.value = null;
    renameName.value = '';
    await loadResources(currentPath.value);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '资源重命名失败');
  }
}

async function deleteItem(item: ResourceLibraryItem) {
  const relativePath = assetReference(item.url);
  if (!relativePath) {
    ElMessage.warning('该资源路径不可在前端执行管理操作');
    return;
  }
  try {
    const references = await checkResourceReferences(relativePath);
    if (references.referenced) {
      await ElMessageBox.confirm(
        `资源“${item.name}”正在被以下位置引用：\n${referenceSourceSummary(references.references)}\n确认继续删除？删除后引用位置可能无法显示该资源。`,
        '资源被引用',
        { type: 'warning' }
      );
    } else {
      await ElMessageBox.confirm(`确认删除资源“${item.name}”？`, '删除资源', { type: 'warning' });
    }
    await http.delete('/api/admin/v2/cms/public-resources', {
      params: references.referenced ? { path: relativePath, force: true } : { path: relativePath }
    });
    ElMessage.success('资源已删除');
    emit('delete', item.url);
    checkedUrls.value = checkedUrls.value.filter((url) => url !== item.url);
    const nextPage = items.value.length <= 1 && page.value > 1 ? page.value - 1 : page.value;
    await loadResources(currentPath.value, nextPage);
  } catch (error) {
    if (error instanceof Error && error.message) {
      ElMessage.error(error.message);
    }
  }
}

async function checkResourceReferences(relativePath: string) {
  const response = await http.get<ResourceReferenceResponse>('/api/admin/v2/cms/public-resources/references', {
    params: { path: relativePath }
  });
  return {
    path: response.path || relativePath,
    referenced: Boolean(response.referenced),
    references: response.references ?? []
  };
}

function referenceSourceSummary(references: ResourceReferenceItem[]) {
  const sourceNames = Array.from(new Set(references
    .map((reference) => reference.sourceName || reference.sourceType)
    .filter(Boolean)));
  return sourceNames.length ? sourceNames.join('、') : '未知来源';
}

function canManage(item: ResourceLibraryItem) {
  return props.manageable && Boolean(assetReference(item.url));
}

function toggleChecked(item: ResourceLibraryItem) {
  if (!canManage(item)) {
    ElMessage.warning('该资源路径不可在前端执行管理操作');
    return;
  }
  checkedUrls.value = checkedUrls.value.includes(item.url)
    ? checkedUrls.value.filter((url) => url !== item.url)
    : [...checkedUrls.value, item.url];
}

function isChecked(item: ResourceLibraryItem) {
  return checkedUrls.value.includes(item.url);
}

function clearCheckedItems() {
  checkedUrls.value = [];
}

async function copySelectedPaths() {
  await copyText(selectedResourceItems.value.map((item) => item.url).join('\n'));
}

async function copyResourcePath(item: ResourceLibraryItem) {
  await copyText(item.url);
  contextMenuItem.value = null;
}

async function copyText(value: string) {
  if (!value) {
    return;
  }
  try {
    await navigator.clipboard?.writeText(value);
    ElMessage.success('资源路径已复制');
  } catch {
    ElMessage.error('资源路径复制失败');
  }
}

async function deleteSelectedItems() {
  if (!selectedResourceItems.value.length) {
    return;
  }
  try {
    const deletingItems = [...selectedResourceItems.value];
    const deletePlans: ResourceDeletePlan[] = [];
    for (const item of deletingItems) {
      const relativePath = assetReference(item.url);
      if (!relativePath) {
        continue;
      }
      const references = await checkResourceReferences(relativePath);
      deletePlans.push({ item, relativePath, references: references.references });
    }
    const referencedPlans = deletePlans.filter((plan) => plan.references.length);
    if (referencedPlans.length) {
      await ElMessageBox.confirm(
        `选中的资源存在引用：\n${referencedPlans.map((plan) => `${plan.item.name}：${referenceSourceSummary(plan.references)}`).join('\n')}\n确认继续批量删除？删除后引用位置可能无法显示相关资源。`,
        '批量删除资源存在引用',
        { type: 'warning' }
      );
    } else {
      await ElMessageBox.confirm(`确认删除选中的 ${selectedResourceItems.value.length} 个资源？`, '批量删除资源', { type: 'warning' });
    }
    for (const plan of deletePlans) {
      await http.delete('/api/admin/v2/cms/public-resources', {
        params: plan.references.length ? { path: plan.relativePath, force: true } : { path: plan.relativePath }
      });
      emit('delete', plan.item.url);
    }
    ElMessage.success('资源已删除');
    clearCheckedItems();
    const nextPage = items.value.length <= deletingItems.length && page.value > 1 ? page.value - 1 : page.value;
    await loadResources(currentPath.value, nextPage);
  } catch (error) {
    if (error instanceof Error && error.message) {
      ElMessage.error(error.message);
    }
  }
}

function openContextMenu(item: ResourceLibraryItem, event: MouseEvent) {
  contextMenuItem.value = item;
  contextMenuPosition.value = clampContextMenuPosition(event.clientX, event.clientY);
}

function openPreview(item: ResourceLibraryItem) {
  previewItem.value = item;
  contextMenuItem.value = null;
}

function closePreview() {
  previewItem.value = null;
}

function startRenameFromContext() {
  if (!contextMenuItem.value) {
    return;
  }
  startRename(contextMenuItem.value);
  contextMenuItem.value = null;
}

async function deleteItemFromContext() {
  if (!contextMenuItem.value) {
    return;
  }
  const item = contextMenuItem.value;
  contextMenuItem.value = null;
  await deleteItem(item);
}

function goToPage(nextPage: number) {
  if (nextPage < 1 || nextPage > totalPages.value || loading.value) {
    return;
  }
  loadResources(currentPath.value, nextPage);
}

function isAllowedItem(item: ResourceLibraryItem) {
  return isAllowedResourceExtension(item.name || item.url, props.allowedExtensions);
}

function uniqueUrls(urls: string[]) {
  return Array.from(new Set(urls.filter(Boolean)));
}

function uploadRequestTypeForFile(_fileName: string): ResourceRequestType {
  return props.type;
}

function clampContextMenuPosition(left: number, top: number) {
  const menuWidth = 188;
  const menuHeight = 176;
  const margin = 12;
  const viewportWidth = window.innerWidth || left + menuWidth + margin;
  const viewportHeight = window.innerHeight || top + menuHeight + margin;
  return {
    left: Math.max(margin, Math.min(left, viewportWidth - menuWidth - margin)),
    top: Math.max(margin, Math.min(top, viewportHeight - menuHeight - margin))
  };
}
</script>

<style scoped>
.resource-picker__body {
  display: grid;
  gap: 14px;
}

.resource-picker__toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.resource-picker__breadcrumb {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px;
}

.resource-picker__crumb {
  border: 0;
  background: transparent;
  color: var(--el-color-primary);
  cursor: pointer;
  padding: 0;
}

.resource-picker__separator {
  color: var(--admin-text-muted);
  margin: 0 2px;
}

.resource-picker__upload {
  position: relative;
  display: inline-flex;
}

.resource-picker__upload input {
  position: absolute;
  inset: 0;
  opacity: 0;
  cursor: pointer;
}

.resource-picker__upload-button,
.resource-picker__bulkbar button,
.resource-picker__pagination button,
.resource-picker__context button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-height: 32px;
  padding: 0 12px;
  border: 1px solid var(--admin-border-subtle);
  border-radius: var(--admin-radius-sm);
  background: #fff;
  color: var(--admin-text);
  cursor: pointer;
}

.resource-picker__upload-button {
  height: 24px;
  min-height: 24px;
  padding: 0 8px;
  font-family: inherit;
  font-size: 12px;
  font-weight: 500;
  line-height: 1;
}

.resource-picker__status {
  margin: 0;
  color: var(--admin-text-muted);
}

.resource-picker__pagination {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  padding: 10px 12px;
  border: 1px solid var(--admin-border-subtle);
  border-radius: var(--admin-radius-sm);
  background: #f8fafc;
  color: var(--admin-text);
}

.resource-picker__bulkbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  border: 1px solid rgba(220, 38, 38, 0.18);
  border-radius: var(--admin-radius-sm);
  background: rgba(254, 242, 242, 0.72);
  color: var(--admin-text);
}

.resource-picker__dirs,
.resource-picker__grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(150px, 1fr));
  gap: 10px;
}

.resource-picker__dir,
.resource-picker__select {
  display: grid;
  gap: 6px;
  min-width: 0;
  width: 100%;
  padding: 10px;
  border: 1px solid var(--admin-border-subtle);
  border-radius: var(--admin-radius-sm);
  background: #fff;
  color: inherit;
  cursor: pointer;
  text-align: left;
}

.resource-picker__dir:hover,
.resource-picker__select:hover {
  border-color: var(--el-color-primary);
}

.resource-picker__item {
  display: grid;
  gap: 8px;
  min-width: 0;
  position: relative;
}

.resource-picker__check {
  position: absolute;
  top: 8px;
  left: 8px;
  z-index: 1;
  min-height: 26px;
  padding: 0 8px;
  border: 1px solid rgba(15, 23, 42, 0.18);
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.92);
  color: var(--admin-text-muted);
  cursor: pointer;
}

.resource-picker__check.is-selected {
  border-color: var(--el-color-primary);
  background: var(--el-color-primary);
  color: #fff;
}

.resource-picker__preview-open {
  position: absolute;
  top: 8px;
  right: 8px;
  z-index: 1;
  min-height: 26px;
  padding: 0 8px;
  border: 1px solid rgba(15, 23, 42, 0.18);
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.94);
  color: var(--admin-text-muted);
  cursor: pointer;
}

.resource-picker__preview-open:hover {
  border-color: var(--el-color-primary);
  color: var(--el-color-primary);
}

.resource-picker__select span {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.resource-picker__context {
  position: fixed;
  z-index: 3000;
  display: grid;
  gap: 4px;
  width: 188px;
  max-width: calc(100vw - 24px);
  padding: 8px;
  border: 1px solid var(--admin-border-subtle);
  border-radius: var(--admin-radius-sm);
  background: #fff;
  box-shadow: 0 18px 44px rgba(15, 23, 42, 0.18);
}

.resource-picker__context strong {
  min-width: 0;
  padding: 4px 6px 6px;
  overflow: hidden;
  color: var(--admin-text);
  font-size: 13px;
  line-height: 1.35;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.resource-picker__context button {
  justify-content: flex-start;
  width: 100%;
  border-color: transparent;
  background: transparent;
}

.resource-picker__context button:hover {
  background: var(--admin-bg-subtle);
}

.resource-picker__rename {
  display: grid;
  grid-template-columns: minmax(140px, auto) minmax(0, 1fr) auto;
  gap: 8px;
  align-items: center;
  padding: 10px;
  border: 1px solid var(--admin-border-subtle);
  border-radius: var(--admin-radius-sm);
  background: var(--admin-bg-subtle);
}

.resource-picker__preview-layer {
  position: fixed;
  inset: 0;
  z-index: 3200;
  display: grid;
  place-items: center;
  padding: 24px;
  background: rgba(15, 23, 42, 0.48);
}

.resource-picker__preview-panel {
  display: grid;
  grid-template-rows: auto minmax(0, 1fr);
  width: min(1040px, 100%);
  max-height: min(860px, calc(100vh - 48px));
  border-radius: var(--admin-radius-lg);
  background: #fff;
  box-shadow: 0 24px 72px rgba(15, 23, 42, 0.28);
  overflow: hidden;
}

.resource-picker__preview-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 12px 14px;
  border-bottom: 1px solid var(--admin-border-subtle);
}

.resource-picker__preview-header strong {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.resource-picker__preview-header button,
.resource-picker__preview-file button {
  min-height: 32px;
  padding: 0 12px;
  border: 1px solid var(--admin-border-subtle);
  border-radius: var(--admin-radius-sm);
  background: #fff;
  color: var(--admin-text);
  cursor: pointer;
}

.resource-picker__preview-stage {
  min-width: 0;
  min-height: 0;
  padding: 16px;
  overflow: auto;
  background: #f8fafc;
}

.resource-picker__preview-stage img {
  display: block;
  width: auto;
  height: auto;
  max-width: none;
  max-height: none;
  margin: 0 auto;
  background: #fff;
}

.resource-picker__preview-stage video {
  display: block;
  width: 100%;
  max-height: calc(100vh - 170px);
  background: #0f172a;
}

.resource-picker__preview-file {
  display: grid;
  gap: 10px;
  max-width: 680px;
  margin: 0 auto;
  padding: 18px;
  border: 1px solid var(--admin-border-subtle);
  border-radius: var(--admin-radius-sm);
  background: #fff;
}

.resource-picker__preview-file span {
  min-width: 0;
  overflow-wrap: anywhere;
  color: var(--admin-text-muted);
}

@media (max-width: 640px) {
  .resource-picker__rename {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
