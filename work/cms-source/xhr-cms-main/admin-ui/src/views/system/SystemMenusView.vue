<template>
  <section class="work-page">
    <header class="work-hero">
      <div>
        <p class="admin-eyebrow">系统管理</p>
        <h1>菜单管理</h1>
        <p>管理系统菜单结构、排序与显示状态。</p>
      </div>
      <div class="hero-actions">
        <el-button size="small" :loading="loading" @click="refreshMenus">刷新</el-button>
        <el-button size="small" type="primary" @click="openAdd(0)">添加菜单</el-button>
      </div>
    </header>

    <section class="work-card">
      <div class="filters admin-table-toolbar">
        <el-input size="small" v-model="query.title" placeholder="菜单名称" clearable @keyup.enter="handleSearch" />
        <el-input size="small" v-model="query.menuKey" placeholder="菜单标识" clearable @keyup.enter="handleSearch" />
        <el-button size="small" type="primary" @click="handleSearch">查询</el-button>
        <el-button size="small" @click="handleReset">重置</el-button>
      </div>

      <div v-if="!isSearching" class="tree-tip">
        默认仅展示一级菜单，展开节点时按需加载直属子菜单。
      </div>

      <el-table element-loading-text="正在加载数据…"
        :key="tableRevision"
        v-loading="loading"
        :data="rows"
        row-key="id"
        :lazy="!isSearching"
        :load="loadChildren"
        :tree-props="treeProps"
        :default-expand-all="isSearching"
        stripe
      >
        <el-table-column label="菜单标题" prop="title" min-width="220" show-overflow-tooltip />
        <el-table-column label="菜单标识" prop="menuKey" min-width="160" show-overflow-tooltip />
        <el-table-column label="路径" prop="path" min-width="220" show-overflow-tooltip />
        <el-table-column label="图标" prop="icon" width="100" align="center" />
        <el-table-column label="分类" prop="category" width="120" show-overflow-tooltip />
        <el-table-column label="排序" prop="sortOrder" width="80" align="center" />
        <el-table-column label="显示" width="80" align="center">
          <template #default="{ row }">
            <el-switch :model-value="row.visible" @change="(val: boolean) => toggleVisible(row, val)" />
          </template>
        </el-table-column>
        <el-table-column label="启用" width="80" align="center">
          <template #default="{ row }">
            <el-switch :model-value="row.enabled" @change="(val: boolean) => toggleEnabled(row, val)" />
          </template>
        </el-table-column>
        <el-table-column label="系统管理员" width="110" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.requiresSystemAdmin" size="small" type="danger">是</el-tag>
            <el-tag v-else size="small" type="info">否</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="150" align="center" fixed="right">
          <template #default="{ row }">
            <el-button size="small" plain @click="openAdd(row.id)">添加子菜单</el-button>
            <br>
            <el-button size="small" plain @click="openEdit(row)">修改</el-button>
            <el-button size="small" type="danger" plain @click="removeMenu(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </section>

    <el-drawer v-model="dialogVisible" :title="form.id ? '修改菜单' : '添加菜单'" size="500px">
      <el-form label-position="top" class="work-form" @submit.prevent>
        <el-form-item label="父级菜单">
          <el-select v-model="form.parentId" filterable clearable placeholder="选择父级菜单（不选为顶级）">
            <el-option :value="0" label="顶级菜单" />
            <el-option
              v-for="item in flatMenus"
              :key="item.id"
              :disabled="invalidParentIds.has(item.id)"
              :label="item.label"
              :value="item.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="菜单标题" required>
          <el-input v-model="form.title" placeholder="请输入菜单标题" />
        </el-form-item>
        <el-form-item label="菜单标识" required>
          <el-input v-model="form.menuKey" placeholder="唯一标识，用于前端路由匹配" />
        </el-form-item>
        <el-form-item label="路由路径" required>
          <el-input v-model="form.path" placeholder="如 /admin/v2/system/menus" />
        </el-form-item>
        <el-form-item label="图标">
          <el-input v-model="form.icon" placeholder="Element Plus 图标名称" />
        </el-form-item>
        <el-form-item label="分类">
          <el-input v-model="form.category" placeholder="菜单所属分类" />
        </el-form-item>
        <el-form-item label="排序号">
          <el-input-number v-model="form.sortOrder" :min="0" :max="9999" />
        </el-form-item>
        <el-form-item label="前端组件路径">
          <el-input v-model="form.componentPath" placeholder="动态路由组件路径（可选）" />
        </el-form-item>
        <div class="form-row">
          <el-form-item label="显示"><el-switch v-model="form.visible" /></el-form-item>
          <el-form-item label="启用"><el-switch v-model="form.enabled" /></el-form-item>
          <el-form-item label="需要系统管理员权限"><el-switch v-model="form.requiresSystemAdmin" /></el-form-item>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveMenu">保存</el-button>
      </template>
    </el-drawer>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import {
  createMenu,
  deleteMenu,
  getMenuChildren,
  getMenuTree,
  searchMenus,
  updateMenu,
  type MenuEntity
} from '@/api/systemMenus';

interface MenuOption extends MenuEntity {
  label: string;
}

const treeProps = { children: 'children', hasChildren: 'hasChildren' };
const loading = ref(false);
const saving = ref(false);
const dialogVisible = ref(false);
const tableRevision = ref(0);
const rows = ref<MenuEntity[]>([]);
const query = ref({ title: '', menuKey: '' });
const appliedQuery = ref({ title: '', menuKey: '' });
const allMenus = ref<MenuEntity[]>([]);
const allMenusLoaded = ref(false);
const form = ref<Partial<MenuEntity>>(emptyForm());
let latestLoadRequest = 0;
const isSearching = computed(() => Boolean(appliedQuery.value.title || appliedQuery.value.menuKey));

const flatMenus = computed<MenuOption[]>(() => {
  const childrenByParent = new Map<number, MenuEntity[]>();
  for (const item of allMenus.value) {
    const parentId = item.parentId ?? 0;
    const children = childrenByParent.get(parentId) ?? [];
    children.push(item);
    childrenByParent.set(parentId, children);
  }
  const result: MenuOption[] = [];
  const visited = new Set<number>();
  const append = (parentId: number, depth: number) => {
    for (const item of childrenByParent.get(parentId) ?? []) {
      if (visited.has(item.id)) continue;
      visited.add(item.id);
      result.push({ ...item, label: `${'　'.repeat(depth)}${item.title}` });
      append(item.id, depth + 1);
    }
  };
  append(0, 0);
  return result;
});

const invalidParentIds = computed(() => {
  const currentMenuId = form.value.id;
  if (!currentMenuId) return new Set<number>();
  const ids = new Set<number>([currentMenuId]);
  let added = true;
  while (added) {
    added = false;
    for (const item of allMenus.value) {
      if (!ids.has(item.id) && ids.has(item.parentId)) {
        ids.add(item.id);
        added = true;
      }
    }
  }
  return ids;
});

function emptyForm(parentId = 0): Partial<MenuEntity> {
  return {
    parentId,
    menuKey: '',
    title: '',
    path: '',
    icon: '',
    category: '',
    sortOrder: 0,
    visible: true,
    enabled: true,
    requiresSystemAdmin: false,
    componentPath: ''
  };
}

function toMenuPayload(menu: Partial<MenuEntity>): Partial<MenuEntity> {
  return {
    parentId: menu.parentId ?? 0,
    menuKey: menu.menuKey,
    title: menu.title,
    path: menu.path,
    icon: menu.icon,
    category: menu.category,
    sortOrder: menu.sortOrder,
    visible: menu.visible,
    enabled: menu.enabled,
    requiresSystemAdmin: menu.requiresSystemAdmin,
    componentPath: menu.componentPath
  };
}

function buildSearchTree(items: MenuEntity[]): MenuEntity[] {
  const nodes = new Map<number, MenuEntity>();
  for (const item of items) nodes.set(item.id, { ...item, children: [] });
  const roots: MenuEntity[] = [];
  for (const item of items) {
    const node = nodes.get(item.id)!;
    const parent = nodes.get(item.parentId);
    if (parent) parent.children!.push(node);
    else roots.push(node);
  }
  for (const node of nodes.values()) node.hasChildren = Boolean(node.children?.length);
  return roots;
}

async function loadMenus() {
  const requestId = ++latestLoadRequest;
  loading.value = true;
  rows.value = [];
  tableRevision.value += 1;
  try {
    let nextRows: MenuEntity[];
    if (isSearching.value) {
      const items = await searchMenus({
        title: appliedQuery.value.title || undefined,
        menuKey: appliedQuery.value.menuKey || undefined
      });
      nextRows = buildSearchTree(items);
    } else {
      nextRows = await getMenuChildren(0);
    }
    if (requestId !== latestLoadRequest) return;
    rows.value = nextRows;
    tableRevision.value += 1;
  } catch (error: any) {
    if (requestId !== latestLoadRequest) return;
    ElMessage.error(error.message || '加载菜单失败');
  } finally {
    if (requestId === latestLoadRequest) loading.value = false;
  }
}

async function loadChildren(row: MenuEntity, _treeNode: unknown, resolve: (children: MenuEntity[]) => void) {
  try {
    resolve(await getMenuChildren(row.id));
  } catch (error: any) {
    resolve([]);
    ElMessage.error(error.message || `加载「${row.title}」的子菜单失败`);
  }
}

async function loadAllMenus(force = false) {
  if (allMenusLoaded.value && !force) return;
  try {
    allMenus.value = await getMenuTree();
    allMenusLoaded.value = true;
  } catch (error: any) {
    ElMessage.error(error.message || '加载父级菜单选项失败');
  }
}

async function refreshMenus() {
  await loadMenus();
  if (allMenusLoaded.value) await loadAllMenus(true);
}

function handleSearch() {
  appliedQuery.value = {
    title: query.value.title.trim(),
    menuKey: query.value.menuKey.trim()
  };
  void loadMenus();
}

function handleReset() {
  query.value = { title: '', menuKey: '' };
  appliedQuery.value = { title: '', menuKey: '' };
  void loadMenus();
}

async function openAdd(parentId: number) {
  form.value = emptyForm(parentId);
  await loadAllMenus();
  dialogVisible.value = true;
}

async function openEdit(menu: MenuEntity) {
  form.value = { ...toMenuPayload(menu), id: menu.id };
  await loadAllMenus();
  dialogVisible.value = true;
}

async function saveMenu() {
  if (!form.value.title || !form.value.menuKey || !form.value.path) {
    ElMessage.warning('请填写必填项');
    return;
  }
  saving.value = true;
  try {
    const payload = toMenuPayload(form.value);
    if (form.value.id) {
      await updateMenu(form.value.id, payload);
      ElMessage.success('修改成功');
    } else {
      await createMenu(payload);
      ElMessage.success('创建成功');
    }
    dialogVisible.value = false;
    await Promise.all([loadMenus(), loadAllMenus(true)]);
  } catch (error: any) {
    ElMessage.error(error.message || '保存失败');
  } finally {
    saving.value = false;
  }
}

async function removeMenu(menu: MenuEntity) {
  try {
    await ElMessageBox.confirm(`确定删除菜单「${menu.title}」吗？`, '删除确认', { type: 'warning' });
    await deleteMenu(menu.id);
    ElMessage.success('删除成功');
    await Promise.all([loadMenus(), loadAllMenus(true)]);
  } catch (error: any) {
    if (error !== 'cancel') ElMessage.error(error.message || '删除失败');
  }
}

async function toggleVisible(menu: MenuEntity, visible: boolean) {
  try {
    await updateMenu(menu.id, { visible });
    menu.visible = visible;
    ElMessage.success(visible ? '已显示' : '已隐藏');
  } catch (error: any) {
    ElMessage.error(error.message || '操作失败');
  }
}

async function toggleEnabled(menu: MenuEntity, enabled: boolean) {
  try {
    await updateMenu(menu.id, { enabled });
    menu.enabled = enabled;
    ElMessage.success(enabled ? '已启用' : '已禁用');
  } catch (error: any) {
    ElMessage.error(error.message || '操作失败');
  }
}

onMounted(() => {
  void loadMenus();
});
</script>

<style scoped>
.work-page { display: grid; gap: var(--admin-card-gap); }
.work-hero, .work-card { border: var(--admin-card-border); border-radius: var(--admin-card-radius); background: var(--admin-bg-surface); box-shadow: var(--admin-shadow-md); }
.work-hero { display: flex; justify-content: space-between; gap: 20px; padding: var(--admin-card-padding-roomy); }
.hero-actions { display: flex; gap: 8px; align-items: center; }
.work-hero h1, .work-hero p { margin: 0; }
.work-hero p { color: var(--admin-text-muted); }
.work-card { padding: var(--admin-card-padding); }
.filters { display: flex; flex-wrap: wrap; gap: 12px; margin-bottom: 16px; }
.filters .el-input { width: 200px; }
.tree-tip { margin: -4px 0 14px; color: var(--admin-text-muted); font-size: 13px; }
.work-form { padding: 16px 0; }
.form-row { display: flex; gap: 24px; }
.form-row .el-form-item { flex: 1; }
</style>
