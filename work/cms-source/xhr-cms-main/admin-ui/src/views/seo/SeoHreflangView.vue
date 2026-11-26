<template>
  <section class="seo-page">
    <header class="seo-page__header">
      <h1>Hreflang管理</h1>
      <el-button size="small" type="primary" @click="openEdit()">新增</el-button>
    </header>
    <el-table element-loading-text="正在加载数据…" :data="list" stripe border v-loading="loading">
      <el-table-column prop="hreflang" label="语言代码" width="120" />
      <el-table-column prop="href" label="链接地址" min-width="200" show-overflow-tooltip />
      <el-table-column prop="siteName" label="应用站点" width="150" />
      <el-table-column prop="menuName" label="应用栏目" width="150" />
      <el-table-column prop="contentTitle" label="应用内容" width="150" />
      <el-table-column label="状态" width="100" align="center">
        <template #default="{ row }">
          <el-tag :type="row.status === 1 ? 'success' : 'danger'">
            {{ row.status === 1 ? '启用' : '禁用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="createTime" label="创建时间" width="180" />
      <el-table-column label="操作" width="150" fixed="right">
        <template #default="{ row }">
          <el-button size="small" link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button size="small" link type="danger" @click="doDelete(row.id)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-drawer v-model="editVisible" :title="editId ? '编辑' : '新增'" direction="rtl" size="600px" :close-on-click-modal="false">
      <el-form label-width="100px" class="seo-form">
        <el-form-item label="应用站点">
          <el-select v-model="editForm.siteId" placeholder="请选择站点" style="width: 100%">
            <el-option v-for="site in sites" :key="site.id" :label="site.name" :value="site.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-radio-group v-model="editForm.status">
            <el-radio :value="1">启用</el-radio>
            <el-radio :value="0">禁用</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="语言代码">
          <el-input v-model="editForm.hreflang" placeholder="如 zh-CN, en-US" />
        </el-form-item>
        <el-form-item label="链接地址">
          <el-input v-model="editForm.href" />
        </el-form-item>
        <el-form-item label="应用栏目">
          <el-select v-model="editForm.menuId" placeholder="请选择栏目" style="width: 100%" @change="onMenuChange">
            <el-option v-for="menu in menus" :key="menu.id" :label="menu.name" :value="menu.id" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="showContentSelect" label="应用内容">
          <el-select v-model="editForm.contentId" placeholder="请选择内容" style="width: 100%" clearable>
            <el-option v-for="content in contents" :key="content.id" :label="content.title" :value="content.id" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" @click="doSave">保存</el-button>
      </template>
    </el-drawer>
  </section>
</template>
<script setup lang="ts">
import { createTableLoader } from '@/utils/tableLoader';
import { ref, onMounted, computed, watch } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { http } from '@/api/http';
import { useSiteContextStore } from '@/stores/siteContext';

interface SiteItem { id: number | string; name: string; }
interface MenuOption { id: number; name: string; pageType: number; }
interface ContentOption { id: number; title: string; }

const siteContext = useSiteContextStore();
const loading = ref(false);
const list = ref<any[]>([]);
const editVisible = ref(false);
const editId = ref<number | null>(null);
const menus = ref<MenuOption[]>([]);
const contents = ref<ContentOption[]>([]);
const editForm = ref({
  hreflang: '',
  href: '',
  menuId: null as number | null,
  contentId: null as number | null,
  siteId: null as number | null,
  status: 1
});

const sites = computed(() => siteContext.sites);
const showContentSelect = computed(() => {
  const menu = menus.value.find(m => m.id === editForm.value.menuId);
  return menu && menu.pageType === 1; // pageType=1 表示列表栏目
});

onMounted(() => loadList());

const loadListLoader = createTableLoader(loading);

async function loadList() {
  const request = loadListLoader.begin(() => {
    list.value = [];
  });
  try {
    const loadedRows = await http.get<typeof list.value>('/api/admin/v2/seo/hreflang');
    if (!request.isCurrent()) return;
    list.value = loadedRows;
  } catch (error) {
    if (request.isCurrent()) throw error;
  } finally {
    request.finish();
  }
}

async function loadMenus() {
  try {
    menus.value = await http.get<MenuOption[]>('/api/admin/v2/seo/menus');
  } catch (e) {
    menus.value = [];
  }
}

async function loadContents(menuId: number) {
  try {
    contents.value = await http.get<ContentOption[]>(`/api/admin/v2/seo/contents?menuId=${menuId}`);
  } catch (e) {
    contents.value = [];
  }
}

function onMenuChange(menuId: number | null) {
  editForm.value.contentId = null;
  contents.value = [];
  if (menuId) {
    const menu = menus.value.find(m => m.id === menuId);
    if (menu && menu.pageType === 1) {
      loadContents(menuId);
    }
  }
}

async function openEdit(row?: any) {
  editId.value = row?.id ?? null;
  editForm.value = {
    hreflang: row?.hreflang ?? '',
    href: row?.href ?? '',
    menuId: row?.menuId ?? null,
    contentId: row?.contentId ?? null,
    siteId: row?.siteId ?? siteContext.currentSite?.id ?? null,
    status: row?.status ?? 1
  };
  editVisible.value = true;
  await loadMenus();
  if (editForm.value.menuId) {
    const menu = menus.value.find(m => m.id === editForm.value.menuId);
    if (menu && menu.pageType === 1 && editForm.value.contentId) {
      await loadContents(editForm.value.menuId);
    }
  }
}

async function doSave() {
  if (!editForm.value.siteId) {
    ElMessage.warning('请选择应用站点');
    return;
  }
  try {
    if (editId.value) {
      await http.put(`/api/admin/v2/seo/hreflang/${editId.value}`, editForm.value);
    } else {
      await http.post('/api/admin/v2/seo/hreflang', editForm.value);
    }
    ElMessage.success('保存成功');
    editVisible.value = false;
    loadList();
  } catch (e: any) {
    ElMessage.error(e.message);
  }
}

async function doDelete(id: number) {
  await ElMessageBox.confirm('确定删除？', '提示', { type: 'warning' });
  await http.delete(`/api/admin/v2/seo/hreflang/${id}`);
  ElMessage.success('删除成功');
  loadList();
}
</script>
<style scoped>
.seo-page { padding: 0; }
.seo-page__header { display: flex; justify-content: space-between; align-items: center; margin-bottom: var(--admin-card-gap); }
.seo-page__header h1 { margin: 0; font-size: 20px; }
.seo-form { padding: 0 10px; }
</style>
