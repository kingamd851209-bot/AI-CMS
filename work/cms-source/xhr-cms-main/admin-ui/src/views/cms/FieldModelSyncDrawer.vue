<template>
  <el-drawer
    :model-value="modelValue"
    title="从目标站点拉取数据模型"
    size="760px"
    @update:model-value="emit('update:modelValue', $event)"
    @closed="reset"
  >
    <div class="field-model-sync">
      <!-- 当前站点（只读） -->
      <div class="field-model-sync__site">
        <span class="field-model-sync__site-label">当前站点</span>
        <el-tag type="info" effect="plain">{{ currentSiteLabel }}</el-tag>
      </div>

      <!-- 目标站点选择 -->
      <div class="field-model-sync__target">
        <span class="field-model-sync__target-label">目标站点</span>
        <el-select
          v-model="selectedTargetId"
          placeholder="请选择目标站点"
          style="width: 320px"
          :loading="targetsLoading"
          @change="onTargetChange"
        >
          <el-option
            v-for="site in targets"
            :key="String(site.id)"
            :label="`${site.name}（${site.code || '未设置编码'}）`"
            :value="site.id"
          />
        </el-select>
      </div>

      <!-- 左右栏目对应 -->
      <div v-loading="previewLoading" element-loading-text="正在加载数据…" class="field-model-sync__mapping">
        <el-empty v-if="!previewLoading && !sourceMenus.length" description="当前站点暂无栏目" />
        <el-table
          v-else
          :data="sourceMenus"
          border
          size="small"
          class="field-model-sync__table"
          empty-text="暂无栏目"
        >
          <el-table-column label="当前站点栏目" min-width="220">
            <template #default="{ row }">
              <span class="field-model-sync__menu-name">{{ row.className || '未命名栏目' }}</span>
              <el-tag size="small" type="info" effect="plain">{{ row.id }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="目标站点栏目" min-width="240">
            <template #header>
              <span>目标站点栏目</span>
              <el-button
                v-if="hasMappings"
                link
                type="primary"
                size="small"
                @click="clearTargetMappings"
              >清空</el-button>
            </template>
            <template #default="{ row }">
              <el-select
                v-model="mappingBySource[row.id]"
                placeholder="选择对应栏目"
                clearable
                filterable
                :value-on-clear="null"
                style="width: 100%"
              >
                <el-option
                  v-for="target in targetMenus"
                  :key="target.id"
                  :label="`${target.className || '未命名栏目'}（${target.id}）`"
                  :value="target.id"
                />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column label="包含子栏目" width="100" align="center">
            <template #default="{ row }">
              <el-switch v-model="includeSubMenusBySource[row.id]" />
            </template>
          </el-table-column>
          <el-table-column label="状态" width="80" align="center">
            <template #default="{ row }">
              <span v-if="syncedMenuIds.has(row.id)" class="field-model-sync__check">✔</span>
            </template>
          </el-table-column>
        </el-table>
      </div>

      <!-- 底部操作 -->
      <div class="field-model-sync__footer">
        <span class="field-model-sync__hint">拉取目标站点的字段设置配置信息到当前站点对应栏目</span>
        <div class="field-model-sync__footer-actions">
          <el-button @click="emit('update:modelValue', false)">取消</el-button>
          <el-button
            type="primary"
            :loading="applying"
            :disabled="!selectedTargetId || !hasMappings"
            @click="confirmApply"
          >确认同步</el-button>
        </div>
      </div>
    </div>
  </el-drawer>
</template>

<script setup lang="ts">
import { createTableLoader } from '@/utils/tableLoader';
import { computed, reactive, ref, watch } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { http } from '@/api/http';
import { useSiteContextStore } from '@/stores/siteContext';

const props = defineProps<{
  modelValue: boolean;
}>();

const emit = defineEmits<{
  'update:modelValue': [value: boolean];
  synced: [];
}>();

interface TargetSite {
  id: number | string;
  name: string;
  code: string;
}

interface MenuSummary {
  id: number;
  className: string;
  classEnName?: string | null;
  parentId?: number | null;
  pageType?: number | null;
}

interface Mapping {
  sourceMenuId: number;
  targetMenuId: number;
  includeSubMenus?: boolean;
}

interface PreviewResult {
  currentSite: TargetSite;
  targetSite: TargetSite;
  sourceMenus: MenuSummary[];
  targetMenus: MenuSummary[];
  autoMappings: Mapping[];
}

const siteContext = useSiteContextStore();

const targets = ref<TargetSite[]>([]);
const targetsLoading = ref(false);
const selectedTargetId = ref<number | string | null>(null);
const previewLoading = ref(false);
const applying = ref(false);
const sourceMenus = ref<MenuSummary[]>([]);
const targetMenus = ref<MenuSummary[]>([]);
const mappingBySource = reactive<Record<number, number | null>>({});
const includeSubMenusBySource = reactive<Record<number, boolean>>({});
const syncedMenuIds = ref<Set<number>>(new Set());

const currentSiteLabel = computed(() => {
  return siteContext.currentSite?.name || siteContext.currentSite?.code || '未选择站点';
});

const hasMappings = computed(() => {
  return Object.values(mappingBySource).some((targetId) => targetId != null);
});

watch(() => props.modelValue, (visible) => {
  if (visible) {
    void open();
  } else {
    loadPreviewLoader.invalidate();
    sourceMenus.value = [];
    targetMenus.value = [];
    clearMappings();
  }
});

function clearMappings() {
  Object.keys(mappingBySource).forEach((key) => delete mappingBySource[Number(key)]);
  Object.keys(includeSubMenusBySource).forEach((key) => delete includeSubMenusBySource[Number(key)]);
}

/** 仅清空「目标站点栏目」列的选择（保留行） */
function clearTargetMappings() {
  Object.keys(mappingBySource).forEach((key) => {
    mappingBySource[Number(key)] = null;
  });
}

async function open() {
  syncedMenuIds.value = new Set();
  selectedTargetId.value = null;
  sourceMenus.value = [];
  targetMenus.value = [];
  clearMappings();
  await loadTargets();
}

async function loadTargets() {
  targetsLoading.value = true;
  try {
    targets.value = await http.get<TargetSite[]>('/api/admin/v2/cms/field-model-sync/targets');
    if (targets.value.length && selectedTargetId.value == null) {
      selectedTargetId.value = targets.value[0].id;
      await loadPreview();
    }
  } catch (err) {
    ElMessage.error(errorMessage(err, '目标站点加载失败'));
  } finally {
    targetsLoading.value = false;
  }
}

async function onTargetChange() {
  await loadPreview();
}

const loadPreviewLoader = createTableLoader(previewLoading);

async function loadPreview() {
  if (selectedTargetId.value == null) {
    loadPreviewLoader.invalidate();
    sourceMenus.value = [];
    targetMenus.value = [];
    clearMappings();
    return;
  }
  const request = loadPreviewLoader.begin(() => {
    sourceMenus.value = [];
    targetMenus.value = [];
    clearMappings();
  });
  try {
    const preview = await http.get<PreviewResult>(
      `/api/admin/v2/cms/field-model-sync/preview?targetSiteId=${selectedTargetId.value}`
    );
    if (!request.isCurrent()) return;
    sourceMenus.value = preview.sourceMenus ?? [];
    targetMenus.value = preview.targetMenus ?? [];
    applyAutoMappings(preview.autoMappings ?? []);
    syncedMenuIds.value = new Set();
  } catch (err) {
    if (!request.isCurrent()) return;
    ElMessage.error(errorMessage(err, '栏目对应信息加载失败'));
  } finally {
    request.finish();
  }
}

function applyAutoMappings(autoMappings: Mapping[]) {
  clearMappings();
  const autoBySource = new Map<number, number>();
  for (const mapping of autoMappings) {
    if (mapping && mapping.sourceMenuId != null && mapping.targetMenuId != null) {
      autoBySource.set(mapping.sourceMenuId, mapping.targetMenuId);
    }
  }
  for (const source of sourceMenus.value) {
    mappingBySource[source.id] = autoBySource.get(source.id) ?? null;
    includeSubMenusBySource[source.id] = false;
  }
}

function confirmApply() {
  if (selectedTargetId.value == null) {
    return;
  }
  ElMessageBox.confirm('当前站点栏目字段设置会被覆盖，请确认', '同步确认', {
    confirmButtonText: '确认覆盖',
    cancelButtonText: '取消',
    type: 'warning'
  })
    .then(() => apply())
    .catch(() => undefined);
}

async function apply() {
  const mappings: Mapping[] = sourceMenus.value
    .filter((source) => mappingBySource[source.id] != null)
    .map((source) => ({
      sourceMenuId: source.id,
      targetMenuId: mappingBySource[source.id]!,
      includeSubMenus: !!includeSubMenusBySource[source.id]
    }));

  applying.value = true;
  try {
    const result = await http.post<{ syncedMenuIds: number[]; syncedCount: number; createdSubMenuCount: number; updatedSubMenuCount: number }>(
      '/api/admin/v2/cms/field-model-sync/apply',
      { targetSiteId: selectedTargetId.value, mappings }
    );
    syncedMenuIds.value = new Set(result.syncedMenuIds ?? []);
    const createdSub = result.createdSubMenuCount ?? 0;
    const updatedSub = result.updatedSubMenuCount ?? 0;
    const subInfo = createdSub > 0 || updatedSub > 0
      ? `；子栏目新建 ${createdSub} 个、更新 ${updatedSub} 个`
      : '';
    ElMessage.success(`同步成功，共 ${result.syncedCount ?? syncedMenuIds.value.size} 个栏目${subInfo}`);
    emit('synced');
  } catch (err) {
    ElMessage.error(errorMessage(err, '同步失败'));
  } finally {
    applying.value = false;
  }
}

function reset() {
  targets.value = [];
  selectedTargetId.value = null;
  sourceMenus.value = [];
  targetMenus.value = [];
  clearMappings();
  syncedMenuIds.value = new Set();
}

function errorMessage(err: unknown, fallback: string) {
  return err instanceof Error ? err.message : fallback;
}
</script>

<style scoped>
.field-model-sync {
  display: grid;
  gap: 16px;
}

.field-model-sync__site,
.field-model-sync__target {
  display: flex;
  align-items: center;
  gap: 12px;
}

.field-model-sync__site-label,
.field-model-sync__target-label {
  color: var(--admin-text-muted);
  font-size: 13px;
  min-width: 64px;
}

.field-model-sync__mapping {
  min-height: 240px;
}

.field-model-sync__table {
  width: 100%;
}

.field-model-sync__menu-name {
  margin-right: 8px;
}

.field-model-sync__check {
  color: #67c23a;
  font-weight: 700;
  font-size: 16px;
}

.field-model-sync__footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  width: 100%;
}

.field-model-sync__hint {
  color: var(--admin-text-muted);
  font-size: 13px;
  flex: 1;
}

.field-model-sync__footer-actions {
  display: flex;
  gap: 8px;
  flex-shrink: 0;
}
</style>
