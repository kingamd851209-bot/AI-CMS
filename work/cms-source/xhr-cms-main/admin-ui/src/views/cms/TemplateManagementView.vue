<template>
  <section class="cms-page template-management">
    <header class="cms-page__hero">
      <div>
        <p class="admin-eyebrow">站点内容</p>
        <h1>模板管理</h1>
        <p>管理当前站点的栏目模板配置，支持预览和编辑列表页、详情页模板。</p>
      </div>
      <div class="template-management__hero-tools">
        <div v-if="currentSite" class="template-management__site-context">
          <span>{{ currentSite.siteName || '未命名站点' }}</span>
          <em>{{ currentSite.siteCode || '未设置编码' }}</em>
        </div>
        <div class="cms-page__actions">
          <el-button v-if="developerWorkbenchEnabled && siteContext.isPlatformAdmin" @click="router.push('/admin/v2/site-build')">建站引导</el-button>
          <el-button :loading="loading" @click="loadMenus">刷新</el-button>
        </div>
      </div>
    </header>

    <el-empty v-if="!siteContext.currentSite" description="请先选择站点">
      <el-button type="primary" @click="router.push('/admin/v2')">前往工作台</el-button>
    </el-empty>

    <div v-else class="template-management__content">
      <div class="template-management__toolbar admin-table-toolbar">
        <el-input size="small"
          v-model="keyword"
          placeholder="按栏目名称搜索"
          clearable
          style="width: 300px"
          @keyup.enter="doSearch"
        >
          <template #prefix>
            <el-icon><Search /></el-icon>
          </template>
        </el-input>
        <el-button size="small" type="primary" @click="doSearch">查询</el-button>
        <el-button size="small" @click="resetSearch">重置</el-button>
        <div class="template-management__stats">
          <el-tag>共 {{ filteredMenus.length }} 个栏目</el-tag>
          <el-tag type="success">已配置模板 {{ configuredCount }} 个</el-tag>
          <el-tag type="warning">未配置模板 {{ unconfiguredCount }} 个</el-tag>
        </div>
      </div>

      <div v-if="loading && !flatMenus.length" class="template-management__loading">
        <el-icon class="is-loading"><Loading /></el-icon>
        <span>加载中...</span>
      </div>

      <el-table
        v-else
        :data="menuTree"
        stripe
        border
        height="100%"
        row-key="id"
        :tree-props="{ children: 'children' }"
        :expand-row-keys="expandedMenuIds"
        @expand-change="onExpandChange"
        @row-click="onRowClick"
      >
        <el-table-column prop="className" label="栏目名称" min-width="180">
          <template #default="{ row }">
            <span class="template-management__menu-name">
              {{ row.className }}
              <el-tag v-if="row.id === currentSite?.rootMenuId" size="small" type="info">根栏目</el-tag>
            </span>
          </template>
        </el-table-column>
        <el-table-column prop="id" label="栏目ID" width="100" align="center" />
        <el-table-column label="栏目类型" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="row.pageType === 0 ? 'info' : 'primary'" size="small">
              {{ row.pageType === 0 ? '单篇' : '列表' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column v-if="showParentColumn" label="父级栏目" width="160">
          <template #default="{ row }">
            <span v-if="!row.parentId || row.parentId === 0 || row.parentId === row.siteRootId">根级栏目</span>
            <el-link v-else-if="row.parentName" type="primary" @click.stop="goToMenu(row.parentId)">
              {{ row.parentName }}
            </el-link>
            <el-link v-else-if="getParentMenu(row.parentId)" type="primary" @click.stop="goToMenu(row.parentId)">
              {{ getParentMenu(row.parentId)?.className }}
            </el-link>
            <span v-else class="template-management__muted">ID: {{ row.parentId }}</span>
          </template>
        </el-table-column>
        <el-table-column label="列表页模板" min-width="200">
          <template #default="{ row }">
            <div class="template-management__template-cell" @click.stop>
              <el-input
                v-if="editingRow === row.id && editingField === 'listPageTemplate'"
                v-model="editForm.listPageTemplate"
                size="small"
                placeholder="输入模板路径"
                @keyup.enter="saveTemplate(row)"
                @keyup.esc="cancelEdit"
              />
              <template v-else>
                <span v-if="row.listPageTemplate" class="template-management__template-value" role="button" tabindex="0" @click="startEdit(row, 'listPageTemplate')" @keydown.enter="startEdit(row, 'listPageTemplate')" @keydown.space.prevent="startEdit(row, 'listPageTemplate')">
                  {{ row.listPageTemplate }}
                </span>
                <span v-else class="template-management__template-empty" role="button" tabindex="0" @click="startEdit(row, 'listPageTemplate')" @keydown.enter="startEdit(row, 'listPageTemplate')" @keydown.space.prevent="startEdit(row, 'listPageTemplate')">
                  点击设置
                </span>
              </template>
              <el-button
                v-if="row.listPageTemplate"
                size="small"
                link
                type="primary"
                @click.stop="previewTemplate(row, 'list')"
              >
                预览
              </el-button>
              <el-button
                size="small"
                link
                type="success"
                @click.stop="openUpload(row, 'list')"
              >
                <el-icon><Upload /></el-icon>
                上传模板
              </el-button>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="详情页模板" min-width="200">
          <template #default="{ row }">
            <div class="template-management__template-cell" @click.stop>
              <el-input
                v-if="editingRow === row.id && editingField === 'detailPageTemplate'"
                v-model="editForm.detailPageTemplate"
                size="small"
                placeholder="输入模板路径"
                @keyup.enter="saveTemplate(row)"
                @keyup.esc="cancelEdit"
              />
              <template v-else>
                <span v-if="row.detailPageTemplate" class="template-management__template-value" role="button" tabindex="0" @click="startEdit(row, 'detailPageTemplate')" @keydown.enter="startEdit(row, 'detailPageTemplate')" @keydown.space.prevent="startEdit(row, 'detailPageTemplate')">
                  {{ row.detailPageTemplate }}
                </span>
                <span v-else class="template-management__template-empty" role="button" tabindex="0" @click="startEdit(row, 'detailPageTemplate')" @keydown.enter="startEdit(row, 'detailPageTemplate')" @keydown.space.prevent="startEdit(row, 'detailPageTemplate')">
                  点击设置
                </span>
              </template>
              <el-button
                v-if="row.detailPageTemplate"
                size="small"
                link
                type="primary"
                @click.stop="previewTemplate(row, 'detail')"
              >
                预览
              </el-button>
              <el-button
                size="small"
                link
                type="success"
                @click.stop="openUpload(row, 'detail')"
              >
                <el-icon><Upload /></el-icon>
                上传模板
              </el-button>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="120" align="center" fixed="right">
          <template #default="{ row }">
            <el-button size="small" link type="primary" @click.stop="editRow(row)">
              编辑
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- 编辑抽屉 -->
    <el-drawer
      v-model="editDrawerVisible"
      title="编辑栏目模板"
      direction="rtl"
      size="500px"
      :close-on-click-modal="false"
    >
      <el-form v-if="currentEditMenu" label-position="top" class="cms-form">
        <el-form-item label="栏目名称">
          <el-input :value="currentEditMenu.className" disabled />
        </el-form-item>
        <el-form-item label="栏目ID">
          <el-input :value="currentEditMenu.id" disabled />
        </el-form-item>
        <el-form-item label="栏目类型">
          <el-tag :type="currentEditMenu.pageType === 0 ? 'info' : 'primary'">
            {{ currentEditMenu.pageType === 0 ? '单篇' : '列表' }}
          </el-tag>
        </el-form-item>
        <el-form-item label="列表页模板">
          <el-input v-model="editForm.listPageTemplate" placeholder="news/index.html" />
        </el-form-item>
        <el-form-item label="详情页模板">
          <el-input v-model="editForm.detailPageTemplate" placeholder="news/detail.html" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editDrawerVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveFromDrawer">保存</el-button>
      </template>
    </el-drawer>

    <!-- 预览抽屉 -->
    <el-drawer
      v-model="previewDrawerVisible"
      :title="previewTitle"
      direction="rtl"
      size="80%"
      :close-on-click-modal="false"
    >
      <ColumnTemplatePreview
        v-if="previewMenu && currentSite?.siteId"
        :site-id="currentSite.siteId"
        :column-id="previewMenu.id"
        :template-type="previewType"
      />
    </el-drawer>

    <!-- 上传模板抽屉 -->
    <el-drawer
      v-model="uploadDrawerVisible"
      :title="uploadDrawerTitle"
      direction="rtl"
      size="70%"
      :close-on-click-modal="false"
    >
      <div class="template-upload">
        <div class="template-upload__actions">
          <el-button type="primary" @click="triggerUploadPick">
            <el-icon><Upload /></el-icon>
            选择文件
          </el-button>
          <span class="template-upload__path">保存路径：{{ uploadPath }}</span>
          <input
            ref="uploadInputRef"
            type="file"
            accept=".html,.htm,.aspx,.asp"
            style="display: none"
            @change="handleUploadFile"
          />
        </div>
        <div class="template-upload__editor">
          <CodemirrorEditor
            v-if="uploadHasFile"
            v-model="uploadContent"
            :title="uploadPath"
            :show-save="true"
            @save="saveUploadedTemplate"
          />
          <el-empty v-else description="请选择要上传的模板文件" />
        </div>
      </div>
      <template #footer>
        <el-button @click="uploadDrawerVisible = false">取消</el-button>
      </template>
    </el-drawer>
  </section>
</template>

<script setup lang="ts">
import { createTableLoader } from '@/utils/tableLoader';
import { computed, onMounted, reactive, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { Loading, Search, Upload } from '@element-plus/icons-vue';
import { http } from '@/api/http';
import { saveTemplateContent, getColumnTemplateDir } from '@/api/templateRecognition';
import { useSiteContextStore } from '@/stores/siteContext';
import ColumnTemplatePreview from './ColumnTemplatePreview.vue';
import CodemirrorEditor from '@/components/common/CodemirrorEditor.vue';

interface MenuNode {
  id: number;
  parentId: number;
  className: string;
  pageType?: number;
  siteRootId?: number;
  parentName?: string;
  listPageUrl?: string;
  listPageTemplate?: string;
  detailPageUrl?: string;
  detailPageTemplate?: string;
  children?: MenuNode[];
}

interface CurrentSiteSummary {
  siteId?: number | null;
  rootMenuId?: number | null;
  siteName?: string | null;
  siteCode?: string | null;
}

const router = useRouter();
const developerWorkbenchEnabled = import.meta.env.VITE_DEVELOPER_WORKBENCH === 'true';
const siteContext = useSiteContextStore();
const loading = ref(false);
const saving = ref(false);
const keyword = ref('');
const flatMenus = ref<MenuNode[]>([]);
// Element Plus 的 row-key="id" 会将行标识转换为字符串。
const expandedMenuIds = ref<string[]>([]);
const showParentColumn = ref(false);
const currentSite = ref<CurrentSiteSummary | null>(null);
const editingRow = ref<number | null>(null);
const editingField = ref<string | null>(null);
const editDrawerVisible = ref(false);
const currentEditMenu = ref<MenuNode | null>(null);
const previewDrawerVisible = ref(false);
const previewMenu = ref<MenuNode | null>(null);
const previewType = ref<'list' | 'detail'>('list');

const uploadDrawerVisible = ref(false);
const uploadMenu = ref<MenuNode | null>(null);
const uploadType = ref<'list' | 'detail'>('list');
const uploadContent = ref('');
const uploadHasFile = ref(false);
const preparingUpload = ref(false);
const uploadInputRef = ref<HTMLInputElement | null>(null);

const uploadDrawerTitle = computed(() => {
  const typeName = uploadType.value === 'list' ? '列表页模板' : '详情页模板';
  return `上传模板 - ${uploadMenu.value?.className ?? ''}（${typeName}）`;
});

// 栏目 → 模板保存目录（按栏目树结构，由后端下发）
const columnDirByMenu = ref<Record<string, string>>({});

const uploadPath = computed(() => {
  const row = uploadMenu.value;
  if (!row) return '';
  const existing = uploadType.value === 'list' ? row.listPageTemplate : row.detailPageTemplate;
  if (existing) return existing;
  const fileName = uploadType.value === 'list' ? 'index.html' : 'details.html';
  // 根栏目目录为空：直接保存在 template/web/ 下
  const dir = columnDirByMenu.value[`${currentSite.value?.siteId}:${row.id}`];
  if (dir === undefined) return '';
  return dir ? `${dir}/${fileName}` : fileName;
});

// 获取并缓存栏目对应的模板保存目录（根栏目返回空字符串，同样需要缓存）
async function ensureColumnDir(menuId: number) {
  const siteId = currentSite.value?.siteId;
  if (!siteId) throw new Error('站点信息缺失');
  const key = `${siteId}:${menuId}`;
  if (key in columnDirByMenu.value) return;
  const dir = await getColumnTemplateDir(siteId, menuId);
  columnDirByMenu.value = { ...columnDirByMenu.value, [key]: dir };
}

const editForm = reactive({
  listPageTemplate: '',
  detailPageTemplate: ''
});

const filteredMenus = computed(() => {
  const text = keyword.value.trim().toLowerCase();
  if (!text) return flatMenus.value;
  return flatMenus.value.filter(menu =>
    menu.className.toLowerCase().includes(text)
  );
});

const menuTree = computed(() => {
  const menuById = new Map(flatMenus.value.map(menu => [menu.id, menu]));
  const visibleIds = new Set(filteredMenus.value.map(menu => menu.id));
  // 搜索时保留祖先路径，避免匹配到的深层栏目脱离原有层级。
  for (const menu of filteredMenus.value) {
    let parent = menuById.get(menu.parentId);
    while (parent && !visibleIds.has(parent.id)) {
      visibleIds.add(parent.id);
      parent = menuById.get(parent.parentId);
    }
  }
  const nodes = new Map<number, MenuNode>();
  for (const menu of flatMenus.value) {
    if (visibleIds.has(menu.id)) {
      nodes.set(menu.id, { ...menu, children: [] });
    }
  }
  const roots: MenuNode[] = [];
  for (const node of nodes.values()) {
    const parent = nodes.get(node.parentId);
    if (parent && parent !== node) {
      parent.children!.push(node);
    } else {
      roots.push(node);
    }
  }
  return roots;
});

function resetExpandedMenus() {
  const ids: string[] = [];
  const searching = !!keyword.value.trim();
  function collect(nodes: MenuNode[], depth = 1) {
    for (const node of nodes) {
      if (node.children?.length) {
        ids.push(String(node.id));
        if (searching || depth < 2) collect(node.children, depth + 1);
      }
    }
  }
  // 默认展开第一、第二级分支；搜索时展开结果的完整路径。
  collect(menuTree.value);
  expandedMenuIds.value = ids;
}

function onExpandChange(row: MenuNode, expanded: boolean) {
  const rowId = String(row.id);
  const ids = expandedMenuIds.value.filter(id => id !== rowId);
  if (expanded) ids.push(rowId);
  expandedMenuIds.value = ids;
}

watch(keyword, resetExpandedMenus);

const configuredCount = computed(() => {
  return flatMenus.value.filter(menu =>
    menu.listPageTemplate || menu.detailPageTemplate
  ).length;
});

const unconfiguredCount = computed(() => {
  return flatMenus.value.length - configuredCount.value;
});

const previewTitle = computed(() => {
  if (!previewMenu.value) return '模板预览';
  const typeName = previewType.value === 'list' ? '列表页模板' : '详情页模板';
  return `${previewMenu.value.className} - ${typeName}`;
});

watch(
  () => siteContext.currentSite?.id,
  (newSiteId, oldSiteId) => {
    if (newSiteId !== oldSiteId) {
      flatMenus.value = [];
      void loadMenus();
    }
  }
);

onMounted(() => {
  void loadMenus();
});

const loadMenusLoader = createTableLoader(loading);

async function loadMenus() {
  const selectedSite = siteContext.currentSite;
  if (!selectedSite) {
    loadMenusLoader.invalidate();
    currentSite.value = null;
    flatMenus.value = [];
    return;
  }

  const request = loadMenusLoader.begin(() => {
    flatMenus.value = [];
  });
  try {
    // 使用模板概览 API，确保返回完整的模板字段
    const data = await http.get<{ currentSite?: CurrentSiteSummary; flat: MenuNode[] }>('/api/admin/v2/cms/templates/overview');
    if (!request.isCurrent()) return;
    currentSite.value = {
      siteId: typeof selectedSite.id === 'number' ? selectedSite.id : Number(selectedSite.id),
      rootMenuId: selectedSite.rootMenuId,
      siteName: selectedSite.name,
      siteCode: selectedSite.code
    };
    flatMenus.value = data.flat || [];
    resetExpandedMenus();
  } catch (error) {
    if (!request.isCurrent()) return;
    ElMessage.error(error instanceof Error ? error.message : '栏目加载失败');
  } finally {
    request.finish();
  }
}

function doSearch() {
  // 搜索是实时的，此函数仅用于回车键触发
}

function resetSearch() {
  keyword.value = '';
}

function getParentMenu(parentId: number): MenuNode | undefined {
  return flatMenus.value.find(menu => menu.id === parentId);
}

function goToMenu(menuId: number) {
  const menu = flatMenus.value.find(m => m.id === menuId);
  if (menu) {
    keyword.value = menu.className;
  }
}

function startEdit(row: MenuNode, field: 'listPageTemplate' | 'detailPageTemplate') {
  editingRow.value = row.id;
  editingField.value = field;
  editForm.listPageTemplate = row.listPageTemplate || '';
  editForm.detailPageTemplate = row.detailPageTemplate || '';
}

function cancelEdit() {
  editingRow.value = null;
  editingField.value = null;
}

async function saveTemplate(row: MenuNode) {
  if (!editForm.listPageTemplate && !editForm.detailPageTemplate) {
    cancelEdit();
    return;
  }

  saving.value = true;
  try {
    const updated = await http.post<MenuNode>(`/api/admin/v2/cms/menus/${row.id}/template`, {
      listPageTemplate: editForm.listPageTemplate,
      detailPageTemplate: editForm.detailPageTemplate
    });
    const index = flatMenus.value.findIndex(m => m.id === row.id);
    if (index !== -1) {
      flatMenus.value[index] = { ...flatMenus.value[index], ...updated };
    }
    ElMessage.success('模板已保存');
    cancelEdit();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '保存失败');
  } finally {
    saving.value = false;
  }
}

function editRow(row: MenuNode) {
  currentEditMenu.value = row;
  editForm.listPageTemplate = row.listPageTemplate || '';
  editForm.detailPageTemplate = row.detailPageTemplate || '';
  editDrawerVisible.value = true;
}

async function saveFromDrawer() {
  if (!currentEditMenu.value) return;

  saving.value = true;
  try {
    const row = currentEditMenu.value;
    const updated = await http.post<MenuNode>(`/api/admin/v2/cms/menus/${row.id}/template`, {
      listPageTemplate: editForm.listPageTemplate,
      detailPageTemplate: editForm.detailPageTemplate
    });
    const index = flatMenus.value.findIndex(m => m.id === row.id);
    if (index !== -1) {
      flatMenus.value[index] = { ...flatMenus.value[index], ...updated };
    }
    ElMessage.success('模板已保存');
    editDrawerVisible.value = false;
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '保存失败');
  } finally {
    saving.value = false;
  }
}

function previewTemplate(row: MenuNode, type: 'list' | 'detail') {
  const templatePath = type === 'list' ? row.listPageTemplate : row.detailPageTemplate;
  if (!templatePath || !currentSite.value?.siteId) {
    ElMessage.warning('模板路径或站点信息缺失');
    return;
  }

  // 使用抽屉显示预览组件
  previewMenu.value = row;
  previewType.value = type;
  previewDrawerVisible.value = true;
}

async function openUpload(row: MenuNode, type: 'list' | 'detail') {
  if (!currentSite.value?.siteId) {
    ElMessage.warning('站点信息缺失');
    return;
  }
  if (preparingUpload.value) return;
  preparingUpload.value = true;
  const siteId = currentSite.value.siteId;
  try {
    const existing = type === 'list' ? row.listPageTemplate : row.detailPageTemplate;
    if (!existing) await ensureColumnDir(row.id);
    if (siteId !== currentSite.value?.siteId) return;
    uploadMenu.value = row;
    uploadType.value = type;
    uploadContent.value = '';
    uploadHasFile.value = false;
    uploadDrawerVisible.value = true;
  } catch (error) {
    ElMessage.error(`获取栏目模板保存目录失败：${error instanceof Error ? error.message : '请稍后重试'}`);
  } finally {
    preparingUpload.value = false;
  }
}

function triggerUploadPick() {
  uploadInputRef.value?.click();
}

async function handleUploadFile(event: Event) {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  input.value = '';
  if (!file) {
    return;
  }
  try {
    uploadContent.value = await file.text();
    uploadHasFile.value = true;
  } catch (e) {
    ElMessage.error('读取模板文件失败: ' + (e instanceof Error ? e.message : '未知错误'));
  }
}

async function saveUploadedTemplate(content?: string) {
  const row = uploadMenu.value;
  const siteId = currentSite.value?.siteId;
  if (!row || !siteId || !uploadHasFile.value) {
    return;
  }
  const path = uploadPath.value;
  try {
    await saveTemplateContent(siteId, path, content ?? uploadContent.value);
    // 菜单尚未配置该模板路径时同步写入，使其在列表中可见
    const currentField = uploadType.value === 'list' ? row.listPageTemplate : row.detailPageTemplate;
    if (!currentField) {
      const updated = await http.post<MenuNode>(`/api/admin/v2/cms/menus/${row.id}/template`, {
        listPageTemplate: uploadType.value === 'list' ? path : row.listPageTemplate,
        detailPageTemplate: uploadType.value === 'detail' ? path : row.detailPageTemplate
      });
      const index = flatMenus.value.findIndex(m => m.id === row.id);
      if (index !== -1) {
        flatMenus.value[index] = { ...flatMenus.value[index], ...updated };
      }
    }
    ElMessage.success('模板已保存');
    uploadDrawerVisible.value = false;
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败');
  }
}

function onRowClick(row: MenuNode) {
  if (!row.children?.length) return;
  onExpandChange(row, !expandedMenuIds.value.includes(String(row.id)));
}
</script>

<style scoped>
.cms-page {
  display: grid;
  grid-template-rows: auto minmax(0, 1fr);
  flex: 1;
  min-height: 0;
  gap: var(--admin-card-gap);
}

.cms-page__hero {
  display: flex;
  justify-content: space-between;
  gap: 18px;
  padding: var(--admin-card-padding-roomy);
  border: var(--admin-card-border);
  border-radius: var(--admin-card-radius);
  background: var(--admin-surface);
  box-shadow: var(--admin-shadow-card);
}

.cms-page__hero h1,
.cms-page__hero p {
  margin: 0;
}

.cms-page__hero h1 {
  font-size: var(--admin-font-size-page);
  font-weight: 600;
}

.cms-page__hero p {
  color: var(--admin-text-muted);
}

.admin-eyebrow {
  margin: 0 0 4px;
  font-size: var(--admin-font-size-small);
  font-weight: 500;
  color: var(--admin-text-muted);
  text-transform: uppercase;
  letter-spacing: 0.5px;
}

.cms-page__actions {
  display: flex;
  gap: 12px;
  flex-shrink: 0;
}

.template-management__hero-tools {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 12px;
}

.template-management__site-context {
  align-items: center;
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 8px;
}

.template-management__site-context span {
  color: var(--admin-text);
  font-weight: 700;
}

.template-management__site-context em {
  background: #f8fafc;
  border: 1px solid var(--admin-border);
  border-radius: 6px;
  color: var(--admin-text-muted);
  font-size: 13px;
  font-style: normal;
  font-weight: 700;
  padding: 4px 8px;
}

.template-management__content {
  display: flex;
  flex-direction: column;
  min-height: 0;
  padding: var(--admin-card-padding);
  border: var(--admin-card-border);
  border-radius: var(--admin-card-radius);
  background: var(--admin-surface);
  box-shadow: var(--admin-shadow-card);
}

.template-management__toolbar {
  display: flex;
  flex-shrink: 0;
  align-items: center;
  gap: 12px;
  margin-bottom: 16px;
  flex-wrap: wrap;
}

.template-management__stats {
  display: flex;
  gap: 8px;
  margin-left: auto;
}

.template-management__loading {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 60px;
  color: var(--admin-text-muted);
}

.template-management__menu-name {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}

.template-management__muted {
  color: var(--admin-text-muted);
}

.template-management__template-cell {
  display: flex;
  align-items: center;
  gap: 8px;
}

.template-management__template-value {
  flex: 1;
  cursor: pointer;
  color: var(--el-color-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.template-management__template-value:hover {
  text-decoration: underline;
}

.template-management__template-value:focus-visible,
.template-management__template-empty:focus-visible {
  outline: 2px solid var(--el-color-primary);
  outline-offset: 2px;
}

.template-management__template-empty {
  flex: 1;
  cursor: pointer;
  color: var(--admin-text-muted);
  font-style: italic;
}

.template-management__template-empty:hover {
  color: var(--el-color-primary);
}

.cms-form {
  padding: 0 4px;
}

.template-upload {
  display: flex;
  flex-direction: column;
  height: 100%;
  gap: 12px;
}

.template-upload__actions {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.template-upload__path {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  word-break: break-all;
}

.template-upload__editor {
  flex: 1;
  min-height: 0;
}

@media (max-width: 768px) {
  .template-management__toolbar {
    flex-direction: column;
    align-items: stretch;
  }

  .template-management__stats {
    margin-left: 0;
  }
}
</style>
