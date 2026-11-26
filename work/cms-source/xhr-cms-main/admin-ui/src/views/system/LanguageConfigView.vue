<template>
  <section class="work-page">
    <header class="work-hero">
      <div>
        <p class="admin-eyebrow">系统管理</p>
        <h1>语言配置</h1>
        <p>维护站点/内容使用的语言（cms_language）：语言代码、名称、本地名称与启用状态。</p>
      </div>
      <div class="hero-actions">
        <el-button size="small" :loading="loading" @click="loadLanguages">刷新</el-button>
        <el-button size="small" type="primary" @click="openAdd">新增语言</el-button>
      </div>
    </header>

    <section class="work-card">
      <el-table element-loading-text="正在加载数据…" v-loading="loading" :data="languages" stripe>
        <el-table-column label="语言代码" prop="languageCode" min-width="140" />
        <el-table-column label="语言名称" prop="languageName" min-width="160" />
        <el-table-column label="本地名称" prop="nativeName" min-width="160">
          <template #default="{ row }">{{ row.nativeName || '-' }}</template>
        </el-table-column>
        <el-table-column label="locale" prop="locale" min-width="140">
          <template #default="{ row }">{{ row.locale || '-' }}</template>
        </el-table-column>
        <el-table-column label="默认" width="90" align="center">
          <template #default="{ row }">
            <el-tag v-if="isDefault(row)" size="small" type="success">默认</el-tag>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="启用" width="90" align="center">
          <template #default="{ row }">
            <el-switch
              :model-value="isEnabled(row)"
              :disabled="isDefault(row)"
              @change="(val: boolean) => toggleEnabled(row, val)"
            />
          </template>
        </el-table-column>
        <el-table-column label="排序" prop="sort" width="80" align="center" />
        <el-table-column label="操作" width="240" align="center" fixed="right">
          <template #default="{ row }">
            <el-button size="small" @click="openEdit(row)">修改</el-button>
            <el-button v-if="!isDefault(row)" size="small" type="primary" plain @click="makeDefault(row)">设为默认</el-button>
            <el-button v-if="!isDefault(row)" size="small" type="danger" plain @click="removeLanguage(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </section>

    <el-drawer v-model="drawerVisible" :title="form.id ? '修改语言' : '新增语言'" size="500px">
      <el-form label-position="top" class="work-form" @submit.prevent>
        <el-form-item label="语言代码" required>
          <el-input v-model="form.languageCode" :disabled="Boolean(form.id)" placeholder="如 zh-CN / en-US" />
        </el-form-item>
        <el-form-item label="语言名称" required>
          <el-input v-model="form.languageName" placeholder="如 中文 / English" />
        </el-form-item>
        <el-form-item label="本地名称">
          <el-input v-model="form.nativeName" placeholder="如 简体中文 / English" />
        </el-form-item>
        <el-form-item label="locale">
          <el-input v-model="form.locale" placeholder="如 zh_CN / en_US" />
        </el-form-item>
        <el-form-item label="排序号">
          <el-input-number v-model="form.sort" :min="0" :max="9999" />
        </el-form-item>
        <div class="form-row">
          <el-form-item label="启用">
            <el-switch v-model="form.enabled" :disabled="isDefault(form)" />
          </el-form-item>
          <el-form-item v-if="!form.id" label="设为默认语言">
            <el-switch v-model="form.defaultLanguage" />
          </el-form-item>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="drawerVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveLanguage">保存</el-button>
      </template>
    </el-drawer>
  </section>
</template>

<script setup lang="ts">
import { createTableLoader } from '@/utils/tableLoader';
import { ref, onMounted } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import {
  listLanguages,
  createLanguage,
  updateLanguage,
  deleteLanguage,
  setDefaultLanguage,
  enableLanguage,
  disableLanguage,
  type LanguageItem
} from '@/api/languages';

interface LanguageForm {
  id?: number;
  languageCode: string;
  languageName: string;
  nativeName: string;
  locale: string;
  sort: number;
  enabled: boolean;
  defaultLanguage: boolean;
  isDefault: number;
}

const loading = ref(false);
const saving = ref(false);
const drawerVisible = ref(false);
const languages = ref<LanguageItem[]>([]);

const form = ref<LanguageForm>(emptyForm());

function emptyForm(): LanguageForm {
  return {
    languageCode: '',
    languageName: '',
    nativeName: '',
    locale: '',
    sort: 0,
    enabled: true,
    defaultLanguage: false,
    isDefault: 0
  };
}

function isDefault(row: { isDefault?: number | null }): boolean {
  return row.isDefault === 1;
}

function isEnabled(row: { enabled?: number | null }): boolean {
  return row.enabled === 1;
}

const loadLanguagesLoader = createTableLoader(loading);

async function loadLanguages() {
  const request = loadLanguagesLoader.begin(() => {
    languages.value = [];
  });
  try {
    const loadedRows = await listLanguages();
    if (!request.isCurrent()) return;
    languages.value = loadedRows;
  } catch (e) {
    if (!request.isCurrent()) return;
    ElMessage.error(e instanceof Error ? e.message : '加载语言失败');
  } finally {
    request.finish();
  }
}

function openAdd() {
  form.value = emptyForm();
  drawerVisible.value = true;
}

function openEdit(row: LanguageItem) {
  form.value = {
    id: row.id,
    languageCode: row.languageCode,
    languageName: row.languageName,
    nativeName: row.nativeName ?? '',
    locale: row.locale ?? '',
    sort: row.sort ?? 0,
    enabled: isEnabled(row),
    defaultLanguage: isDefault(row),
    isDefault: row.isDefault
  };
  drawerVisible.value = true;
}

async function saveLanguage() {
  if (!form.value.languageCode || !form.value.languageName) {
    ElMessage.warning('请填写语言代码与语言名称');
    return;
  }

  saving.value = true;
  try {
    if (form.value.id) {
      await updateLanguage(form.value.id, {
        languageName: form.value.languageName,
        nativeName: form.value.nativeName,
        locale: form.value.locale,
        sort: form.value.sort,
        enabled: form.value.enabled
      });
      ElMessage.success('修改成功');
    } else {
      await createLanguage({
        languageCode: form.value.languageCode,
        languageName: form.value.languageName,
        nativeName: form.value.nativeName,
        locale: form.value.locale,
        sort: form.value.sort,
        enabled: form.value.enabled,
        defaultLanguage: form.value.defaultLanguage
      });
      ElMessage.success('新增成功');
    }
    drawerVisible.value = false;
    await loadLanguages();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败');
  } finally {
    saving.value = false;
  }
}

async function removeLanguage(row: LanguageItem) {
  try {
    await ElMessageBox.confirm(`确定删除语言「${row.languageName}」吗？`, '删除确认', { type: 'warning' });
    await deleteLanguage(row.id);
    ElMessage.success('删除成功');
    await loadLanguages();
  } catch (e) {
    if (e !== 'cancel') {
      ElMessage.error(e instanceof Error ? e.message : '删除失败');
    }
  }
}

async function makeDefault(row: LanguageItem) {
  try {
    await setDefaultLanguage(row.id);
    ElMessage.success(`已将「${row.languageName}」设为默认语言`);
    await loadLanguages();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '操作失败');
  }
}

async function toggleEnabled(row: LanguageItem, enabled: boolean) {
  try {
    if (enabled) {
      await enableLanguage(row.id);
    } else {
      await disableLanguage(row.id);
    }
    row.enabled = enabled ? 1 : 0;
    ElMessage.success(enabled ? '已启用' : '已停用');
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '操作失败');
  }
}

onMounted(() => {
  loadLanguages();
});
</script>

<style scoped>
.work-page {
  padding: 0;
}

.work-hero {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: var(--admin-card-gap);
}

.hero-actions {
  display: flex;
  gap: 8px;
}

.admin-eyebrow {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-bottom: 4px;
}

.work-hero h1 {
  font-size: 24px;
  font-weight: 600;
  margin: 0 0 8px;
}

.work-hero p {
  font-size: 14px;
  color: var(--el-text-color-secondary);
  margin: 0;
}

.work-card {
  background: var(--el-bg-color);
  border-radius: var(--admin-card-radius);
  border: 1px solid var(--el-border-color-light);
  padding: var(--admin-card-padding-compact);
}

.work-form {
  padding: 16px 0;
}

.form-row {
  display: flex;
  gap: 24px;
}

.form-row .el-form-item {
  flex: 1;
}
</style>
