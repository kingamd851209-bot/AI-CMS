<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import SiteExternalIntegrationsPanel from '@/components/sites/SiteExternalIntegrationsPanel.vue';
import { createTableLoader } from '@/utils/tableLoader';
import { ElMessage } from 'element-plus';
import { siteSettingsApi, type ColumnConfig, type SiteSettingRow } from '@/api/siteSettings';
import { siteCatalogApi, type SiteCatalogOption, type SiteColumnNode } from '@/api/cmsPlatform';
import { useSiteContextStore } from '@/stores/siteContext';

const siteContext = useSiteContextStore();
const siteId = computed<number | null>(() => {
  const id = siteContext.currentSite?.id;
  return id ? Number(id) : null;
});

interface MenuNode { id: number; className: string; parentId: number; children?: MenuNode[] }

const treeProps = { children: 'children', label: 'className' };
const rootMenus = ref<MenuNode[]>([]);
const loading = ref(false);

const currentCol = ref<MenuNode | null>(null);
const rows = ref<ColumnConfig[]>([]);
const listForm = ref<SiteSettingRow>({});
const detailForm = ref<SiteSettingRow>({});
const configLoading = ref(false);
const saving = ref(false);

const sites = ref<SiteCatalogOption[]>([]);
const listSiteId = ref<number | null>(null);
const listColumns = ref<SiteColumnNode[]>([]);
const listSelectedColumn = ref<SiteColumnNode | null>(null);
const listCross = ref<Array<{ siteId: number; siteName: string; columnId: number; columnName: string }>>([]);
const detailSiteId = ref<number | null>(null);
const detailColumns = ref<SiteColumnNode[]>([]);
const detailSelectedColumn = ref<SiteColumnNode | null>(null);
const detailCross = ref<Array<{ siteId: number; siteName: string; columnId: number; columnName: string }>>([]);

async function loadRoot() {
  loading.value = true;
  try {
    rootMenus.value = await httpMenus(0);
  } finally {
    loading.value = false;
  }
}

async function httpMenus(parentId: number): Promise<MenuNode[]> {
  const http = (await import('@/api/http')).http;
  return http.get<MenuNode[]>('/api/admin/v2/cms/menus', { params: { parentId } });
}

async function loadChild(node: { data: MenuNode }, resolve: (data: MenuNode[]) => void) {
  resolve(await httpMenus(node.data?.id ?? 0));
}

const configLoader = createTableLoader(configLoading);

function clearConfig() {
  rows.value = [];
  listForm.value = {};
  detailForm.value = {};
  listCross.value = [];
  detailCross.value = [];
}

watch(siteId, () => {
  configLoader.invalidate();
  clearConfig();
  currentCol.value = null;
  void loadRoot();
});

async function selectMenu(node: MenuNode) {
  const id = siteId.value;
  const request = configLoader.begin(clearConfig);
  try {
    if (!id) return;
    currentCol.value = node;
    const [children, l, d] = await Promise.all([
      siteSettingsApi.children(id, node.id),
      siteSettingsApi.config(id, node.id, 'list'),
      siteSettingsApi.config(id, node.id, 'detail')
    ]);
    if (!request.isCurrent()) return;
    rows.value = children;
    listForm.value = { ...l };
    detailForm.value = { ...d };
    listCross.value = parseCross(listForm.value.crossMappingJson);
    detailCross.value = parseCross(detailForm.value.crossMappingJson);
  } catch (error) {
    if (request.isCurrent()) ElMessage.error(error instanceof Error ? error.message : '栏目配置加载失败');
  } finally {
    request.finish();
  }
}

function parseCross(json?: string): Array<{ siteId: number; siteName: string; columnId: number; columnName: string }> {
  if (!json) return [];
  try {
    return JSON.parse(json);
  } catch {
    return [];
  }
}

async function loadColumns(siteId: number): Promise<SiteColumnNode[]> {
  const http = (await import('@/api/http')).http;
  const data = await http.get<SiteColumnNode[] | { flat?: SiteColumnNode[] }>('/api/admin/v2/cms/menus', { params: { siteId } });
  return Array.isArray(data) ? data : (data.flat ?? []);
}

async function onListSiteChange() {
  listColumns.value = [];
  listSelectedColumn.value = null;
  if (listSiteId.value) {
    listColumns.value = await loadColumns(listSiteId.value);
  }
}
async function onDetailSiteChange() {
  detailColumns.value = [];
  detailSelectedColumn.value = null;
  if (detailSiteId.value) {
    detailColumns.value = await loadColumns(detailSiteId.value);
  }
}

async function addListCross() {
  if (!listSiteId.value) {
    ElMessage.warning('请选择站点');
    return;
  }
  const site = sites.value.find(s => s.id === Number(listSiteId.value));
  const col = listSelectedColumn.value;
  if (!col) {
    ElMessage.warning('请选择栏目');
    return;
  }
  if (listCross.value.some(m => m.siteId === Number(listSiteId.value) && m.columnId === col.id)) {
    ElMessage.warning('该映射已存在');
    return;
  }
  listCross.value.push({ siteId: Number(listSiteId.value), siteName: site?.name ?? '', columnId: col.id, columnName: col.className });
  listSiteId.value = null;
  listColumns.value = [];
  listSelectedColumn.value = null;
}

async function addDetailCross() {
  if (!detailSiteId.value) {
    ElMessage.warning('请选择站点');
    return;
  }
  const site = sites.value.find(s => s.id === Number(detailSiteId.value));
  const col = detailSelectedColumn.value;
  if (!col) {
    ElMessage.warning('请选择栏目');
    return;
  }
  if (detailCross.value.some(m => m.siteId === Number(detailSiteId.value) && m.columnId === col.id)) {
    ElMessage.warning('该映射已存在');
    return;
  }
  detailCross.value.push({ siteId: Number(detailSiteId.value), siteName: site?.name ?? '', columnId: col.id, columnName: col.className });
  detailSiteId.value = null;
  detailColumns.value = [];
  detailSelectedColumn.value = null;
}

async function saveRows() {
  if (!siteId.value || !currentCol.value) return;
  saving.value = true;
  try {
    for (const row of rows.value) {
      await siteSettingsApi.save({ siteId: siteId.value, columnId: row.columnId, columnName: row.columnName, parentId: currentCol.value.id, kind: 'list', pageUrl: row.list?.pageUrl ?? '', redirect301: row.list?.redirect301, redirect302: row.list?.redirect302, fallbackLang: row.list?.fallbackLang });
      await siteSettingsApi.save({ siteId: siteId.value, columnId: row.columnId, columnName: row.columnName, parentId: currentCol.value.id, kind: 'detail', pageUrl: row.detail?.pageUrl ?? '', redirect301: row.detail?.redirect301, redirect302: row.detail?.redirect302, fallbackLang: row.detail?.fallbackLang });
    }
    ElMessage.success('已保存');
  } finally {
    saving.value = false;
  }
}

async function saveForm() {
  if (!siteId.value || !currentCol.value) return;
  saving.value = true;
  try {
    await siteSettingsApi.save({ siteId: siteId.value, columnId: currentCol.value.id, columnName: currentCol.value.className, parentId: 0, kind: 'list', pageUrl: listForm.value.pageUrl, redirect301: listForm.value.redirect301, redirect302: listForm.value.redirect302, fallbackLang: listForm.value.fallbackLang, crossMappingJson: JSON.stringify(listCross.value) });
    await siteSettingsApi.save({ siteId: siteId.value, columnId: currentCol.value.id, columnName: currentCol.value.className, parentId: 0, kind: 'detail', pageUrl: detailForm.value.pageUrl, redirect301: detailForm.value.redirect301, redirect302: detailForm.value.redirect302, fallbackLang: detailForm.value.fallbackLang, crossMappingJson: JSON.stringify(detailCross.value) });
    ElMessage.success('已保存');
  } finally {
    saving.value = false;
  }
}

function onImport(file: { raw?: File }) {
  if (!file.raw || !siteId.value || !currentCol.value) return;
  siteSettingsApi.importExcel(file.raw, siteId.value).then(n => {
    ElMessage.success(`导入成功：${n} 行`);
    selectMenu(currentCol.value!);
  }).catch(e => ElMessage.error(`导入失败：${(e as Error).message ?? String(e)}`));
}

function crossText(m: { siteName: string; columnName: string }): string {
  return `${m.siteName}/${m.columnName}`;
}

onMounted(async () => {
  await loadRoot();
  try {
    sites.value = await siteCatalogApi.sites();
  } catch { /* 忽略 */ }
});
</script>

<template>
  <section class="site-settings">
    <aside class="site-settings__tree">
      <div class="site-settings__tree-header">
        <span>栏目导航</span>
        <el-tag v-if="siteId" size="small">{{ siteContext.currentSite?.name ?? siteId }}</el-tag>
      </div>
      <div class="site-settings__tree-body" v-loading="loading">
        <el-tree
          :data="rootMenus"
          :props="treeProps"
          node-key="id"
          lazy
          :load="loadChild"
          :expand-on-click-node="false"
          highlight-current
          @node-click="selectMenu"
        >
          <template #default="{ data }">
            <span>{{ data.className }}<el-tag size="small" type="info" style="margin-left: 6px">{{ data.id }}</el-tag></span>
          </template>
        </el-tree>
      </div>
    </aside>

    <main class="site-settings__main" v-loading="configLoading" element-loading-text="正在加载数据…">
      <SiteExternalIntegrationsPanel :site-id="siteId" />
      <el-empty v-if="!currentCol" description="请选择左侧栏目" />
      <template v-else>
        <!-- 上：当前栏目配置 -->
        <div class="site-settings__section">
          <h3 class="site-settings__section-title">{{ currentCol.className }} 当前栏目配置</h3>
          <div class="site-settings__form-grid">
            <div class="site-settings__block">
              <h3>列表页地址配置</h3>
              <el-form label-width="110px">
                <el-form-item label="主页地址"><el-input v-model="listForm.pageUrl" placeholder="列表页地址" /></el-form-item>
                <el-form-item label="301配置页面"><el-input v-model="listForm.redirect301" /></el-form-item>
                <el-form-item label="302配置页"><el-input v-model="listForm.redirect302" /></el-form-item>
                <el-form-item label="多语言回退页"><el-input v-model="listForm.fallbackLang" /></el-form-item>
                <el-form-item label="跨站点栏目映射">
                  <div class="site-settings__cross">
                    <el-select v-model="listSiteId" size="small" filterable placeholder="选择站点" style="width: 160px" @change="onListSiteChange">
                      <el-option v-for="s in sites" :key="s.id" :label="s.name" :value="Number(s.id)" />
                    </el-select>
                    <el-select v-model="listSelectedColumn" size="small" filterable placeholder="选择栏目" style="width: 180px" value-key="id">
                      <el-option v-for="c in listColumns" :key="c.id" :label="c.className" :value="c" />
                    </el-select>
                    <el-button size="small" @click="addListCross">添加</el-button>
                    <el-tag v-for="(m, i) in listCross" :key="i" closable @close="listCross.splice(i, 1)">{{ crossText(m) }}</el-tag>
                  </div>
                </el-form-item>
              </el-form>
            </div>

            <div class="site-settings__block">
              <h3>详情页地址配置</h3>
              <el-form label-width="110px">
                <el-form-item label="详情页地址"><el-input v-model="detailForm.pageUrl" placeholder="详情页地址" /></el-form-item>
                <el-form-item label="301配置页面"><el-input v-model="detailForm.redirect301" /></el-form-item>
                <el-form-item label="302配置页"><el-input v-model="detailForm.redirect302" /></el-form-item>
                <el-form-item label="多语言回退页"><el-input v-model="detailForm.fallbackLang" /></el-form-item>
                <el-form-item label="跨站点栏目映射">
                  <div class="site-settings__cross">
                    <el-select v-model="detailSiteId" size="small" filterable placeholder="选择站点" style="width: 160px" @change="onDetailSiteChange">
                      <el-option v-for="s in sites" :key="s.id" :label="s.name" :value="Number(s.id)" />
                    </el-select>
                    <el-select v-model="detailSelectedColumn" size="small" filterable placeholder="选择栏目" style="width: 180px" value-key="id">
                      <el-option v-for="c in detailColumns" :key="c.id" :label="c.className" :value="c" />
                    </el-select>
                    <el-button size="small" @click="addDetailCross">添加</el-button>
                    <el-tag v-for="(m, i) in detailCross" :key="i" closable @close="detailCross.splice(i, 1)">{{ crossText(m) }}</el-tag>
                  </div>
                </el-form-item>
              </el-form>
            </div>
          </div>
          <el-button type="primary" :loading="saving" @click="saveForm">保存</el-button>
        </div>

        <!-- 下：子栏目列表配置 -->
        <div class="site-settings__section">
          <div class="site-settings__list-head">
            <span class="site-settings__title">子栏目列表配置</span>
            <div class="site-settings__list-actions">
              <el-upload :auto-upload="false" :show-file-list="false" accept=".xlsx" :on-change="onImport">
                <el-button size="small">导入</el-button>
              </el-upload>
              <a v-if="siteId" class="site-settings__link" :href="siteSettingsApi.exportUrl(siteId, currentCol.id)" download><el-button size="small">导出</el-button></a>
              <a v-if="siteId" class="site-settings__link" :href="siteSettingsApi.exportTemplateUrl(siteId, currentCol.id)" download><el-button size="small">导出模版</el-button></a>
            </div>
          </div>

          <h3>列表页（主页）配置</h3>
          <el-table :data="rows" size="small" stripe empty-text="当前栏目下暂无子栏目">
            <el-table-column prop="columnId" label="栏目id" width="80" />
            <el-table-column prop="columnName" label="栏目名称" min-width="120" />
            <el-table-column label="主页地址" min-width="160">
              <template #default="{ row }"><el-input v-model="row.list.pageUrl" size="small" placeholder="主页地址" /></template>
            </el-table-column>
            <el-table-column label="301配置页面" min-width="150">
              <template #default="{ row }"><el-input v-model="row.list.redirect301" size="small" placeholder="301页" /></template>
            </el-table-column>
            <el-table-column label="302配置页" min-width="150">
              <template #default="{ row }"><el-input v-model="row.list.redirect302" size="small" placeholder="302页" /></template>
            </el-table-column>
            <el-table-column label="多语言回退页" min-width="150">
              <template #default="{ row }"><el-input v-model="row.list.fallbackLang" size="small" placeholder="回退页" /></template>
            </el-table-column>
          </el-table>

          <h3>详情页配置</h3>
          <el-table :data="rows" size="small" stripe empty-text="当前栏目下暂无子栏目">
            <el-table-column prop="columnId" label="栏目id" width="80" />
            <el-table-column prop="columnName" label="栏目名称" min-width="120" />
            <el-table-column label="详情页地址" min-width="160">
              <template #default="{ row }"><el-input v-model="row.detail.pageUrl" size="small" placeholder="详情页地址" /></template>
            </el-table-column>
            <el-table-column label="301配置页面" min-width="150">
              <template #default="{ row }"><el-input v-model="row.detail.redirect301" size="small" placeholder="301页" /></template>
            </el-table-column>
            <el-table-column label="302配置页" min-width="150">
              <template #default="{ row }"><el-input v-model="row.detail.redirect302" size="small" placeholder="302页" /></template>
            </el-table-column>
            <el-table-column label="多语言回退页" min-width="150">
              <template #default="{ row }"><el-input v-model="row.detail.fallbackLang" size="small" placeholder="回退页" /></template>
            </el-table-column>
          </el-table>
          <el-button type="primary" :loading="saving" @click="saveRows">保存</el-button>
        </div>
      </template>
    </main>
  </section>
</template>

<style>
.site-settings { display: flex; height: 100%; padding: 0; gap: var(--admin-card-gap); }
.site-settings__tree {
  width: 300px; flex-shrink: 0; border: 1px solid var(--el-border-color-light);
  border-radius: var(--admin-card-radius); background: var(--el-bg-color); display: flex; flex-direction: column; overflow: hidden;
}
.site-settings__tree-header {
  padding: 10px 12px; border-bottom: 1px solid var(--el-border-color-light);
  display: flex; justify-content: space-between; align-items: center; font-weight: 600;
}
.site-settings__tree-body { flex: 1; overflow: auto; padding: 8px; }
.site-settings__main {
  flex: 1; border: 1px solid var(--el-border-color-light); border-radius: var(--admin-card-radius);
  background: var(--el-bg-color); padding: var(--admin-card-padding-compact); overflow: auto; min-width: 0;
}
.site-settings__section { margin-bottom: 20px; }
.site-settings__section-title { margin: 0 0 10px; font-size: 16px; }
.site-settings__list-head { display: flex; justify-content: space-between; align-items: center; gap: 8px; margin-bottom: 12px; }
.site-settings__title { font-weight: 600; font-size: 15px; }
.site-settings__list-actions { display: flex; align-items: center; gap: 8px; }
.site-settings__link { text-decoration: none; }
.site-settings__form-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; margin-bottom: 12px; }
.site-settings__block { min-width: 0; border: 1px solid var(--el-border-color-lighter); border-radius: 8px; padding: 12px; }
.site-settings__block h3 { margin: 0 0 10px; font-size: 15px; }
.site-settings__cross { display: flex; gap: 8px; flex-wrap: wrap; align-items: center; }

@media (max-width: 1100px) {
  .site-settings { height: auto; min-height: 100%; flex-direction: column; padding: 0; }
  .site-settings__tree { width: auto; max-height: 300px; min-height: 180px; }
  .site-settings__form-grid { grid-template-columns: 1fr; }
  .site-settings__list-head { align-items: flex-start; flex-wrap: wrap; }
}
</style>
