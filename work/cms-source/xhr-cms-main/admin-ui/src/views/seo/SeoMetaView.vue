<template>
  <section class="seo-page">
    <header class="seo-page__header">
      <h1>SEO管理</h1>
      <el-button size="small" type="primary" @click="openEdit()">新增</el-button>
    </header>
    <el-table element-loading-text="正在加载数据…" :data="list" stripe border v-loading="loading">
      <el-table-column prop="seoTitle" label="SEO标题" min-width="150" show-overflow-tooltip />
      <el-table-column prop="seoKeywords" label="关键词" min-width="150" show-overflow-tooltip />
      <el-table-column prop="seoDescription" label="描述" min-width="200" show-overflow-tooltip />
      <el-table-column prop="siteName" label="应用站点" width="150" />
      <el-table-column prop="menuId" label="应用栏目ID" width="110" />
      <el-table-column prop="contentId" label="应用内容ID" width="110" />
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

    <el-drawer v-model="editVisible" :title="editId ? '编辑' : '新增'" direction="rtl" size="700px" :close-on-click-modal="false">
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
        <el-form-item label="SEO标题">
          <el-input v-model="editForm.seoTitle" />
        </el-form-item>
        <el-form-item label="SEO关键词">
          <el-input v-model="editForm.seoKeywords" />
        </el-form-item>
        <el-form-item label="SEO描述">
          <el-input v-model="editForm.seoDescription" type="textarea" :rows="3" />
        </el-form-item>
        <el-form-item label="规范链接">
          <el-input v-model="editForm.canonicalUrl" />
        </el-form-item>
        <el-form-item label="OG标题">
          <el-input v-model="editForm.ogTitle" />
        </el-form-item>
        <el-form-item label="OG描述">
          <el-input v-model="editForm.ogDescription" />
        </el-form-item>
        <el-form-item label="OG图片">
          <el-input v-model="editForm.ogImage" />
        </el-form-item>
        <el-form-item label="应用栏目ID">
          <el-input v-model.number="editForm.menuId" type="number" />
        </el-form-item>
        <el-form-item label="应用内容ID">
          <el-input v-model.number="editForm.contentId" type="number" />
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
import { ref, onMounted, computed } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { http } from '@/api/http';
import { useSiteContextStore } from '@/stores/siteContext';

const siteContext = useSiteContextStore();
const loading = ref(false);
const list = ref<any[]>([]);
const editVisible = ref(false);
const editId = ref<number | null>(null);
const editForm = ref({
  seoTitle: '', seoKeywords: '', seoDescription: '', canonicalUrl: '',
  ogTitle: '', ogDescription: '', ogImage: '',
  menuId: null as any, contentId: null as any,
  siteId: null as number | null, status: 1
});

const sites = computed(() => siteContext.sites);

onMounted(() => loadList());

const loadListLoader = createTableLoader(loading);

async function loadList() {
  const request = loadListLoader.begin(() => {
    list.value = [];
  });
  try {
    const loadedRows = await http.get<typeof list.value>('/api/admin/v2/seo/meta');
    if (!request.isCurrent()) return;
    list.value = loadedRows;
  } catch (error) {
    if (request.isCurrent()) throw error;
  } finally {
    request.finish();
  }
}

function openEdit(row?: any) {
  editId.value = row?.id ?? null;
  editForm.value = {
    seoTitle: row?.seoTitle ?? '',
    seoKeywords: row?.seoKeywords ?? '',
    seoDescription: row?.seoDescription ?? '',
    canonicalUrl: row?.canonicalUrl ?? '',
    ogTitle: row?.ogTitle ?? '',
    ogDescription: row?.ogDescription ?? '',
    ogImage: row?.ogImage ?? '',
    menuId: row?.menuId ?? null,
    contentId: row?.contentId ?? null,
    siteId: row?.siteId ?? siteContext.currentSite?.id ?? null,
    status: row?.status ?? 1
  };
  editVisible.value = true;
}

async function doSave() {
  if (!editForm.value.siteId) {
    ElMessage.warning('请选择应用站点');
    return;
  }
  try {
    if (editId.value) {
      await http.put(`/api/admin/v2/seo/meta/${editId.value}`, editForm.value);
    } else {
      await http.post('/api/admin/v2/seo/meta', editForm.value);
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
  await http.delete(`/api/admin/v2/seo/meta/${id}`);
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
