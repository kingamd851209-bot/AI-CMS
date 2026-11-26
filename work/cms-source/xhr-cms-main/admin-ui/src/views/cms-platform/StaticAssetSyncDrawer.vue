<template>
  <el-drawer
    :model-value="modelValue"
    title="从目标站点拉取静态资源"
    size="760px"
    @update:model-value="emit('update:modelValue', $event)"
    @closed="reset"
  >
    <div class="asset-sync">
      <!-- 迁移目标（继承资源导航当前选择，只读） -->
      <div class="asset-sync__target">
        <div class="asset-sync__target-line">
          <span class="asset-sync__label">当前站点</span>
          <el-tag type="info" effect="plain">{{ siteLabel }}</el-tag>
          <el-tag v-if="scope !== 'GLOBAL'" size="small" effect="plain">{{ rootLabel }}</el-tag>
        </div>
        <div class="asset-sync__target-line">
          <span class="asset-sync__label">目标目录</span>
          <div class="asset-sync__crumbs">
            <span class="asset-sync__crumb">根目录</span>
            <template v-for="c in targetCrumbs" :key="c">
              <span class="asset-sync__sep">/</span>
              <span class="asset-sync__crumb">{{ c }}</span>
            </template>
          </div>
        </div>
      </div>

      <!-- 源选择 -->
      <div class="asset-sync__row">
        <span class="asset-sync__label">源</span>
        <el-select v-model="sourceScopeSelect" style="width: 160px" @change="onSourceChange">
          <el-option
            v-for="opt in SOURCE_SCOPE_OPTIONS"
            :key="opt.value"
            :label="opt.label"
            :value="opt.value"
          />
        </el-select>
        <el-select
          v-if="sourceScopeSelect !== 'GLOBAL'"
          v-model="sourceSiteId"
          style="width: 200px"
          :loading="sitesLoading"
          @change="onSourceChange"
        >
          <el-option
            v-for="site in sourceSites"
            :key="site.siteId"
            :label="`${site.name}（${site.code}）`"
            :value="site.siteId"
          />
        </el-select>
      </div>

      <!-- 源目录浏览 -->
      <div class="asset-sync__panel">
        <div class="asset-sync__panel-title">
          <span>源资源（支持选择目录或文件）</span>
          <span v-if="selectedPaths.size" class="asset-sync__count">已选 {{ selectedPaths.size }} 项</span>
        </div>
        <div class="asset-sync__crumbs">
          <span class="asset-sync__crumb" @click="goSource('')">根目录</span>
          <template v-for="c in sourceCrumbs" :key="c.path">
            <span class="asset-sync__sep">/</span>
            <span class="asset-sync__crumb" @click="goSource(c.path)">{{ c.name }}</span>
          </template>
        </div>
        <div v-loading="sourceLoading" class="asset-sync__list">
          <el-empty v-if="!sourceLoading && !sourceEntries.length" description="目录为空" :image-size="60" />
          <div v-for="item in sourceEntries" :key="item.path" class="asset-sync__item">
            <el-checkbox
              :model-value="selectedPaths.has(item.path)"
              @change="(val: boolean) => toggleSelect(item, val)"
            />
            <el-icon v-if="item.dir" class="asset-sync__icon asset-sync__icon--dir"><Folder /></el-icon>
            <el-icon v-else class="asset-sync__icon"><Document /></el-icon>
            <span
              class="asset-sync__name"
              :class="{ 'asset-sync__name--link': item.dir }"
              @click="item.dir && enterSource(item)"
            >{{ item.name }}</span>
          </div>
        </div>
      </div>

      <!-- footer -->
      <div class="asset-sync__footer">
        <span class="asset-sync__muted">将选中资源复制到「{{ siteLabel }}」的「{{ rootLabel }}」目录</span>
        <div class="asset-sync__footer-actions">
          <el-button @click="emit('update:modelValue', false)">取消</el-button>
          <el-button
            type="primary"
            :loading="copying"
            :disabled="!selectedPaths.size"
            @click="confirmCopy"
          >确认复制（{{ selectedPaths.size }}）</el-button>
        </div>
      </div>
    </div>
  </el-drawer>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Folder, Document } from '@element-plus/icons-vue';
import { staticAssetApi, type AssetEntry, type AssetScope, type SiteNavItem } from '@/api/staticAssets';

const props = defineProps<{
  modelValue: boolean;
  scope: AssetScope;
  siteId?: number;
  siteLabel: string;
  path: string;
}>();

const emit = defineEmits<{
  'update:modelValue': [value: boolean];
  synced: [];
}>();

const sourceSites = ref<SiteNavItem[]>([]);
const sitesLoading = ref(false);
const sourceScopeSelect = ref<AssetScope>('GLOBAL');
const sourceSiteId = ref<number | undefined>(undefined);

const sourcePath = ref('');
const sourceEntries = ref<AssetEntry[]>([]);
const sourceLoading = ref(false);
const selectedPaths = ref<Set<string>>(new Set());
const copying = ref(false);

const SOURCE_SCOPE_OPTIONS: Array<{ value: AssetScope; label: string }> = [
  { value: 'GLOBAL', label: '全局资源' },
  { value: 'SITE_ASSETS', label: '站点素材' },
  { value: 'SITE_TEMPLATE', label: '模板资源' },
  { value: 'SITE_UIPACKAGE', label: 'UIPackage资源' }
];

const rootLabel = computed(() => {
  switch (props.scope) {
    case 'SITE_ASSETS': return '站点素材';
    case 'SITE_TEMPLATE': return '模板资源';
    case 'SITE_UIPACKAGE': return 'UIPackage资源';
    default: return '全局资源';
  }
});

const sourceCrumbs = computed(() => {
  const parts = sourcePath.value ? sourcePath.value.split('/') : [];
  return parts.map((p, i) => ({ name: p, path: parts.slice(0, i + 1).join('/') }));
});

const targetCrumbs = computed(() => {
  return props.path ? props.path.split('/') : [];
});

watch(() => props.modelValue, (visible) => {
  if (visible) {
    void open();
  }
});

async function open() {
  selectedPaths.value = new Set();
  sourcePath.value = props.path;
  sourceEntries.value = [];
  sourceScopeSelect.value = props.scope;
  sourceSiteId.value = props.siteId;
  await Promise.all([loadSites(), loadSource()]);
}

async function loadSites() {
  sitesLoading.value = true;
  try {
    sourceSites.value = await staticAssetApi.nav();
  } catch {
    ElMessage.error('站点列表加载失败');
  } finally {
    sitesLoading.value = false;
  }
}

async function loadSource() {
  sourceLoading.value = true;
  try {
    sourceEntries.value = await staticAssetApi.list(sourcePath.value, sourceScopeSelect.value, sourceSiteId.value);
  } catch {
    ElMessage.error('源资源加载失败');
  } finally {
    sourceLoading.value = false;
  }
}

function enterSource(item: AssetEntry) {
  sourcePath.value = item.path;
  void loadSource();
}

function goSource(path: string) {
  sourcePath.value = path;
  void loadSource();
}

function toggleSelect(item: AssetEntry, checked: boolean) {
  const next = new Set(selectedPaths.value);
  if (checked) {
    next.add(item.path);
  } else {
    next.delete(item.path);
  }
  selectedPaths.value = next;
}

function onSourceChange() {
  if (sourceScopeSelect.value !== 'GLOBAL' && sourceSiteId.value == null && sourceSites.value.length) {
    sourceSiteId.value = sourceSites.value[0].siteId;
  }
  selectedPaths.value = new Set();
  sourcePath.value = '';
  void loadSource();
}

async function confirmCopy() {
  if (!selectedPaths.value.size) {
    return;
  }
  const ok = await ElMessageBox.confirm(
    `确认将选中的 ${selectedPaths.value.size} 项复制到「${props.siteLabel}」？`,
    '复制确认',
    { type: 'warning' }
  ).then(() => true).catch(() => false);
  if (!ok) {
    return;
  }
  copying.value = true;
  try {
    const result = await staticAssetApi.syncCopy({
      sourceScope: sourceScopeSelect.value,
      sourceSiteId: sourceSiteId.value,
      sourcePaths: [...selectedPaths.value],
      targetScope: props.scope,
      targetSiteId: props.siteId,
      targetPath: props.path
    });
    const failedCount = result.failed?.length ?? 0;
    if (failedCount > 0) {
      const reasons = result.failed.slice(0, 5).map((f) => `${f.path}：${f.reason}`).join('；');
      ElMessage.warning(`复制完成 ${result.copiedCount} 项，失败 ${failedCount} 项${reasons ? `：${reasons}` : ''}`);
    } else {
      ElMessage.success(`复制成功，共 ${result.copiedCount} 项`);
    }
    selectedPaths.value = new Set();
    emit('synced');
  } catch (err) {
    ElMessage.error(errorMessage(err, '复制失败'));
  } finally {
    copying.value = false;
  }
}

function reset() {
  sourceSites.value = [];
  sourceScopeSelect.value = 'GLOBAL';
  sourceSiteId.value = undefined;
  sourcePath.value = '';
  sourceEntries.value = [];
  selectedPaths.value = new Set();
}

function errorMessage(err: unknown, fallback: string) {
  return err instanceof Error ? err.message : fallback;
}
</script>

<style scoped>
.asset-sync {
  display: flex;
  flex-direction: column;
  gap: 16px;
  height: 100%;
}

.asset-sync__target {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 12px;
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  background: var(--el-fill-color-lighter);
}

.asset-sync__target-line {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.asset-sync__row {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.asset-sync__label {
  color: var(--el-text-color-secondary);
  font-size: 13px;
  min-width: 56px;
}

.asset-sync__muted {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.asset-sync__panel {
  border: 1px solid var(--el-border-color-light);
  border-radius: var(--admin-card-radius);
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

.asset-sync__panel-title {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px 12px;
  font-weight: 600;
  font-size: 13px;
  border-bottom: 1px solid var(--el-border-color-light);
  background: var(--el-fill-color-lighter);
}

.asset-sync__count {
  color: var(--el-color-primary);
  font-size: 12px;
  font-weight: 500;
}

.asset-sync__crumbs {
  display: flex;
  gap: 4px;
  align-items: center;
  padding: 8px 12px;
  font-size: 13px;
  flex-wrap: wrap;
}

.asset-sync__crumb {
  color: var(--el-text-color-primary);
}
.asset-sync__panel .asset-sync__crumb {
  cursor: pointer;
  color: var(--el-color-primary);
}
.asset-sync__sep {
  color: var(--el-text-color-secondary);
}

.asset-sync__list {
  max-height: 280px;
  min-height: 140px;
  overflow: auto;
  padding: 4px 12px;
}

.asset-sync__item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 0;
  border-bottom: 1px solid var(--el-border-color-lighter);
}
.asset-sync__item:last-child {
  border-bottom: none;
}

.asset-sync__icon {
  font-size: 16px;
  color: var(--el-text-color-secondary);
  flex-shrink: 0;
}
.asset-sync__icon--dir {
  color: #ffc53d;
}

.asset-sync__name {
  font-size: 13px;
  word-break: break-all;
}
.asset-sync__name--link {
  cursor: pointer;
  color: var(--el-text-color-primary);
}
.asset-sync__name--link:hover {
  color: var(--el-color-primary);
}

.asset-sync__footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-top: auto;
  padding-top: 8px;
}

.asset-sync__footer-actions {
  display: flex;
  gap: 8px;
  flex-shrink: 0;
}
</style>
