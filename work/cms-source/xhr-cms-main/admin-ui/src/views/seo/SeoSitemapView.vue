<template>
  <section class="seo-page">
    <header class="seo-page__header">
      <h1>SiteMap管理</h1>
      <el-button size="small" type="primary" @click="openEdit()">新增</el-button>
    </header>
    <el-table element-loading-text="正在加载数据…" :data="list" stripe border v-loading="loading">
      <el-table-column prop="content" label="SiteMap内容" min-width="300" show-overflow-tooltip />
      <el-table-column prop="siteName" label="应用站点" width="150" />
      <el-table-column prop="sitemapType" label="类型" width="100" />
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
        <el-form-item label="类型">
          <el-select v-model="editForm.sitemapType">
            <el-option label="XML" value="XML" />
            <el-option label="TXT" value="TXT" />
            <el-option label="HTML" value="HTML" />
          </el-select>
        </el-form-item>
        <el-form-item label="SiteMap内容">
          <div class="content-with-btn">
            <el-input v-model="editForm.content" type="textarea" :rows="12" placeholder="输入 SiteMap 内容" />
            <el-tooltip content="AI 生成功能暂未接入">
              <span class="ai-btn">
                <el-button type="primary" plain disabled>
                  <el-icon><MagicStick /></el-icon>
                  AI生成
                </el-button>
              </span>
            </el-tooltip>
          </div>
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
import { MagicStick } from '@element-plus/icons-vue';
import { http } from '@/api/http';
import { useSiteContextStore } from '@/stores/siteContext';

const siteContext = useSiteContextStore();
const loading = ref(false);
const list = ref<any[]>([]);
const editVisible = ref(false);
const editId = ref<number | null>(null);
const editForm = ref({ content: '', sitemapType: 'XML', siteId: null as number | null, status: 1 });

const sites = computed(() => siteContext.sites);

onMounted(() => loadList());

const loadListLoader = createTableLoader(loading);

async function loadList() {
  const request = loadListLoader.begin(() => {
    list.value = [];
  });
  try {
    const loadedRows = await http.get<typeof list.value>('/api/admin/v2/seo/sitemap');
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
    content: row?.content ?? '',
    sitemapType: row?.sitemapType ?? 'XML',
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
      await http.put(`/api/admin/v2/seo/sitemap/${editId.value}`, editForm.value);
    } else {
      await http.post('/api/admin/v2/seo/sitemap', editForm.value);
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
  await http.delete(`/api/admin/v2/seo/sitemap/${id}`);
  ElMessage.success('删除成功');
  loadList();
}
</script>
<style scoped>
.seo-page { padding: 0; }
.seo-page__header { display: flex; justify-content: space-between; align-items: center; margin-bottom: var(--admin-card-gap); }
.seo-page__header h1 { margin: 0; font-size: 20px; }
.seo-form { padding: 0 10px; }
.content-with-btn { width: 100%; }
.content-with-btn .ai-btn { display: inline-block; margin-top: 10px; }
</style>
