<template>
  <section class="work-page">
    <header class="work-hero">
      <div>
        <p class="admin-eyebrow">系统管理</p>
        <h1>管理员组管理</h1>
        <p>按树形层级维护管理员组，设置栏目权限与启用状态。</p>
      </div>
      <div class="hero-actions">
        <el-button size="small" :loading="workspace.loading.value" @click="applyFilters">刷新</el-button>
        <el-button size="small" type="primary" @click="openAdd(0)">添加管理员组</el-button>
      </div>
    </header>

    <section class="work-card">
      <el-tabs v-model="activeTab" @tab-change="handleTabChange">
        <el-tab-pane label="站点组" name="site" />
        <el-tab-pane v-if="showPlatformTab" label="平台组" name="platform" />
      </el-tabs>

      <div class="filters admin-table-toolbar">
        <el-input size="small" v-model="query.keyword" placeholder="按组名称搜索" clearable @keyup.enter="applyFilters" @clear="applyFilters" />
        <el-select size="small" v-model="query.enabled" placeholder="可用状态" clearable @change="applyFilters">
          <el-option label="启用" :value="true" />
          <el-option label="停用" :value="false" />
        </el-select>
        <el-select size="small" v-model="query.parentId" placeholder="父级组" clearable filterable @change="applyFilters">
          <el-option v-for="item in workspace.dropdown.value" :key="item.id" :value="item.id" :label="item.title" />
        </el-select>
        <el-button size="small" type="primary" @click="applyFilters">查询</el-button>
        <el-button size="small" @click="resetFilters">重置</el-button>
      </div>

      <el-table element-loading-text="正在加载数据…" v-loading="workspace.loading.value" :data="treeRows" row-key="id" default-expand-all :tree-props="{ children: 'children' }" stripe>
        <el-table-column label="序号" prop="id" width="70" align="center" />
        <el-table-column label="组名称" prop="title" min-width="220" />
        <el-table-column label="父级组" min-width="160">
          <template #default="{ row }">
            {{ row.parentId ? (row.parentTitle || `#${row.parentId}`) : '-' }}
          </template>
        </el-table-column>
        <el-table-column label="所属站点" min-width="140">
          <template #default="{ row }">
            {{ siteLabelOf(row.siteId) }}
          </template>
        </el-table-column>
        <el-table-column label="组介绍" prop="note" min-width="220" show-overflow-tooltip />
        <el-table-column label="可用状态" width="100" align="center">
          <template #default="{ row }">
            <el-tooltip :content="switchTooltip(row)" placement="top">
              <el-switch
                :model-value="row.enabled !== false"
                :disabled="isProtectedGroup(row) || isPlatformGroup(row)"
                @change="(val: boolean) => toggleGroup(row, val)"
              />
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" prop="createTime" width="170" align="center" />
        <el-table-column label="操作" width="170" align="center" fixed="right">
          <template #default="{ row }">
            <el-button size="small" plain @click="openAdd(row.id)">添加子组</el-button>
            <el-button size="small" plain @click="openEdit(row)">修改</el-button>
            <br>
            <el-button size="small" type="warning" plain @click="openMenuConfig(row)">菜单配置</el-button>
            <el-button size="small" type="danger" plain :disabled="isPlatformGroup(row)" @click="removeGroup(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </section>

    <el-drawer v-model="dialogVisible" :title="form.id ? '修改管理员组' : '添加管理员组'" size="min(1120px, calc(100vw - 32px))">
      <el-alert
        v-if="isPlatformForm"
        class="site-info-desc"
        type="warning"
        :closable="false"
        show-icon
        title="平台组（全站共享）"
        description="该组属于平台，可管理所有站点；仅维护组信息，站点栏目权限不适用。"
      />
      <el-descriptions v-else-if="siteContext.currentSite" title="当前站点" :column="2" border size="small" class="site-info-desc">
        <el-descriptions-item label="站点名称">{{ siteContext.currentSite.name }}</el-descriptions-item>
        <el-descriptions-item label="站点编码">{{ siteContext.currentSite.code }}</el-descriptions-item>
        <el-descriptions-item label="站点ID">{{ siteContext.currentSite.id }}</el-descriptions-item>
        <el-descriptions-item label="站点域名">{{ siteContext.currentSite.domain || '-' }}</el-descriptions-item>
        <el-descriptions-item label="默认语言">{{ siteContext.currentSite.language || '-' }}</el-descriptions-item>
        <el-descriptions-item label="根栏目ID">{{ siteContext.currentSite.rootMenuId ?? '-' }}</el-descriptions-item>
      </el-descriptions>
      <el-form label-position="top" class="work-form" @submit.prevent>
        <div class="form-grid">
          <el-form-item label="父级">
            <el-select v-model="form.parentId" filterable>
              <el-option :value="0" label="顶级" />
              <el-option v-for="item in workspace.dropdown.value" :key="item.id" :disabled="item.id === form.id" :label="item.title" :value="item.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="组名称">
            <el-input v-model="form.title" placeholder="请输入管理员组名称" />
          </el-form-item>
        </div>
        <el-form-item label="组介绍">
          <el-input v-model="form.note" type="textarea" :rows="2" placeholder="请输入管理员组介绍" />
        </el-form-item>
        <el-form-item v-if="isPlatformForm" label="数据中台权限">
          <div class="permission-panel">
            <el-tabs v-model="activePlatformTab" class="platform-perm-tabs">
              <el-tab-pane v-for="mod in platformModules" :key="mod.type" :label="mod.label" :name="mod.type">
                <div class="perm-toolbar">
                  <span class="perm-toolbar__title">勾选该类别可执行的操作（未配置则整体放行）</span>
                  <div class="perm-toolbar__actions">
                    <el-button link type="primary" size="small" @click="checkAllPlatformOps(mod.type)">全选</el-button>
                    <el-button link size="small" @click="clearAllPlatformOps(mod.type)">置空</el-button>
                  </div>
                </div>
                <el-tree
                  :data="platformTrees[mod.type] ?? []"
                  node-key="key"
                  :props="{ label: 'label', children: 'children' }"
                  default-expand-all
                  empty-text="该模块暂无类别"
                >
                  <template #default="{ data }">
                    <span class="platform-perm-node" @click.stop>
                      <span class="perm-node__label">{{ data.label }}</span>
                      <span class="perm-node__ops">
                        <el-button
                          v-if="data.operationOptions.length > 1"
                          link
                          type="primary"
                          size="small"
                          class="perm-node__checkall"
                          @click="checkAllPlatformNodeOps(data.key, data.operationOptions)"
                        >
                          全选
                        </el-button>
                        <el-button
                          v-if="data.operationOptions.length > 1"
                          link
                          type="warning"
                          size="small"
                          class="perm-node__checkall"
                          @click="invertPlatformNodeOps(data.key, data.operationOptions)"
                        >
                          反选
                        </el-button>
                        <el-checkbox
                          v-for="op in data.operationOptions"
                          :key="op.id"
                          :model-value="isPlatformOpSelected(data.key, op.id)"
                          size="small"
                          @change="(val: boolean) => togglePlatformOp(data.key, op.id, val)"
                        >
                          {{ op.title }}
                        </el-checkbox>
                      </span>
                    </span>
                  </template>
                </el-tree>
              </el-tab-pane>
            </el-tabs>
          </div>
        </el-form-item>
        <el-form-item v-if="!isPlatformForm" label="栏目权限">
          <div class="permission-panel">
            <div class="perm-toolbar">
              <span class="perm-toolbar__title">勾选栏目并为其分配操作权限</span>
              <div class="perm-toolbar__actions">
                <el-button link type="primary" size="small" @click="checkAllMenu">全选</el-button>
                <el-button link size="small" @click="clearAllMenu">置空</el-button>
              </div>
            </div>
            <el-tree
              :ref="(instance: unknown) => { if (instance) permissionTree.treeRef.value = instance as PermissionTreeRef; }"
              :data="sitePermissionTree"
              :props="{ label: 'label', children: 'children' }"
              node-key="id"
              show-checkbox
              check-strictly
              :default-expanded-keys="defaultExpandedPermissionKeys"
              :render-after-expand="true"
              empty-text="当前站点暂无栏目，可先保存基础组信息"
              @check="handlePermCheck"
              @check-change="scheduleIndeterminateSync"
            >
              <template #default="{ data }">
                <span class="perm-node" @click.stop>
                  <span class="perm-node__label">
                    <el-tag v-if="data.id != null" size="small" class="perm-node__id">{{ data.id }}</el-tag>
                    {{ data.label }}
                  </span>
                  <span v-if="data.operationOptions?.length" class="perm-node__ops">
                    <el-button
                      v-if="data.operationOptions.length > 1"
                      link
                      type="primary"
                      size="small"
                      class="perm-node__checkall"
                      @click="checkAllOps(Number(data.id), data.operationOptions)"
                    >
                      全选
                    </el-button>
                    <el-button
                      v-if="data.operationOptions.length > 1"
                      link
                      type="warning"
                      size="small"
                      class="perm-node__checkall"
                      @click="invertOps(Number(data.id), data.operationOptions)"
                    >
                      反选
                    </el-button>
                    <el-checkbox
                      v-for="option in data.operationOptions"
                      :key="option.id"
                      :model-value="isOpSelected(Number(data.id), option.id)"
                      size="small"
                      @change="(val: boolean) => toggleOp(Number(data.id), option.id, val)"
                    >
                      {{ option.title }}
                    </el-checkbox>
                  </span>
                </span>
              </template>
            </el-tree>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="workspace.saving.value" @click="save">保存</el-button>
      </template>
    </el-drawer>

    <!-- 菜单配置抽屉 -->
    <el-drawer v-model="menuConfigVisible" title="菜单配置" size="500px">
      <div class="menu-config-panel">
        <p class="menu-config-hint">勾选该管理员组可访问的系统菜单：</p>
        <el-tree
          ref="menuConfigTreeRef"
          :data="menuTreeData"
          :props="{ label: 'title', children: 'children' }"
          node-key="id"
          show-checkbox
          default-expand-all
          :default-checked-keys="selectedMenuIds"
        >
          <template #default="{ data }">
            <span class="menu-node">
              <span class="menu-node__label">{{ data.title }}</span>
              <el-tag v-if="data.category" size="small" type="info">{{ data.category }}</el-tag>
            </span>
          </template>
        </el-tree>
      </div>
      <template #footer>
        <el-button @click="menuConfigVisible = false">取消</el-button>
        <el-button type="primary" :loading="menuConfigSaving" @click="saveMenuConfig">保存</el-button>
      </template>
    </el-drawer>
  </section>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue';
import { ElMessage } from 'element-plus';
import {
  flattenPermissionIds,
  isUnknownPermissionKey,
  otherSettingByKey,
  otherSettingOptions,
  stringSettingKeys,
  uniqueKeys,
  usePermissionTreeEditor,
  type OperationOption,
  type PermissionTreeNode,
  type PermissionTreeRef
} from './usePermissionTreeEditor';
import { useAdminGroupsWorkspace, buildPlatformSettings, parsePlatformSetting, type AdminGroupScope, type AdminGroupView, type PlatformModuleCategories } from './useAdminGroupsWorkspace';
import { useSiteContextStore } from '@/stores/siteContext';
import { getMenuTree, type MenuEntity } from '@/api/systemMenus';

interface TreeGroup extends AdminGroupView {
  children?: TreeGroup[];
}

const workspace = useAdminGroupsWorkspace();
const siteContext = useSiteContextStore();

/** 当前标签页归属：站点组 / 平台组 */
const activeTab = ref<AdminGroupScope>('site');
/** 平台管理（超管或平台组成员）才显示「平台组」标签页 */
const showPlatformTab = computed(() => siteContext.isPlatformAdmin);

const siteNameById = computed(() => {
  const map = new Map<string, string>();
  for (const site of siteContext.sites) {
    map.set(String(site.id), site.name);
  }
  return map;
});

function siteNameOf(siteId: number | string) {
  return siteNameById.value.get(String(siteId)) ?? `#${siteId}`;
}

const permissionTree = usePermissionTreeEditor(() => workspace.permissionWorkspace.value.sitePermissions, true);
let indeterminateSyncPending = false;
function scheduleIndeterminateSync() {
  if (indeterminateSyncPending) return;
  indeterminateSyncPending = true;
  void nextTick(() => {
    indeterminateSyncPending = false;
    permissionTree.syncIndeterminate();
  });
}

const dialogVisible = ref(false);
/** 当前表单归属：新增时决定写入站点组还是平台组 */
const formScope = ref<AdminGroupScope>('site');
/** 平台组表单：仅维护父级/组名称/组介绍，不展示站点信息与栏目权限 */
const isPlatformForm = computed(() => formScope.value === 'platform');
const form = reactive({
  id: 0,
  title: '',
  parentId: 0,
  note: '',
  enabled: true,
  groupSetting: '0',
  otherSetting: ''
});
const selectedMenuKeys = ref<string[]>([]);
const selectedGlobalKeys = ref<string[]>([]);
const opSelection = reactive<Record<number, number[]>>({});
/** 平台组数据中台权限：类别键（类型:类别id）→ 已勾选操作id 列表 */
const platformOpSelection = reactive<Record<string, number[]>>({});
let pendingTreeSetting = '';

// 菜单配置相关状态
const menuConfigVisible = ref(false);
const menuConfigSaving = ref(false);
const menuConfigTreeRef = ref<InstanceType<typeof import('element-plus')['ElTree']> | null>(null);
const menuTreeData = ref<MenuEntity[]>([]);
const selectedMenuIds = ref<number[]>([]);
const currentMenuConfigGroup = ref<AdminGroupView | null>(null);

const sitePermissionTree = computed<PermissionTreeNode[]>(() => workspace.permissionWorkspace.value.sitePermissions ?? []);
const defaultExpandedPermissionKeys = computed(() =>
  sitePermissionTree.value.flatMap((node) => [node.id, ...(node.children ?? []).map((child) => child.id)])
    .filter((id): id is number => id != null)
);
const menuPermissionOptions = computed(() => workspace.permissionWorkspace.value.menuPermissions ?? []);
const globalPermissionOptions = computed(() => workspace.permissionWorkspace.value.globalPermissions ?? []);
const platformModules = computed<PlatformModuleCategories[]>(() => workspace.permissionWorkspace.value.platformModules ?? []);

/** 当前数据中台模块标签页 */
const activePlatformTab = ref<string>('');

interface PlatformTreeNode {
  key: string;
  label: string;
  operationOptions: Array<{ id: number; title: string }>;
  children?: PlatformTreeNode[];
}

/** 各模块类别树（扁平节点按 parentId 建树，节点 key = 类型:类别id） */
const platformTrees = computed<Record<string, PlatformTreeNode[]>>(() => {
  const result: Record<string, PlatformTreeNode[]> = {};
  for (const mod of platformModules.value) {
    result[mod.type] = buildCategoryTree(mod);
  }
  return result;
});

function buildCategoryTree(mod: PlatformModuleCategories): PlatformTreeNode[] {
  const map = new Map<number, PlatformTreeNode>();
  mod.categories.forEach((category) => {
    map.set(category.id, {
      key: `${mod.type}:${category.id}`,
      label: category.label,
      operationOptions: category.operationOptions,
      children: []
    });
  });
  const roots: PlatformTreeNode[] = [];
  mod.categories.forEach((category) => {
    const node = map.get(category.id)!;
    const parent = category.parentId ? map.get(category.parentId) : undefined;
    if (parent) {
      parent.children = parent.children ?? [];
      parent.children.push(node);
    } else {
      roots.push(node);
    }
  });
  return roots;
}

watch(
  platformModules,
  (modules) => {
    if (modules.length && !modules.some((mod) => mod.type === activePlatformTab.value)) {
      activePlatformTab.value = modules[0].type;
    }
  },
  { immediate: true }
);
const knownPermissionKeys = computed(() => new Set<string>([
  ...menuPermissionOptions.value.map((permission) => String(permission.key)),
  ...globalPermissionOptions.value.map((permission) => String(permission.key))
]));
const unknownPermissions = computed(() => {
  const byKey = otherSettingByKey(form.groupSetting, form.otherSetting);
  return stringSettingKeys(form.groupSetting)
    .filter((key) => isUnknownPermissionKey(key, knownPermissionKeys.value))
    .map((key) => ({ key, otherSetting: byKey.get(key) ?? '' }));
});

const treeRows = computed<TreeGroup[]>(() => buildTree(workspace.groups.value));

const query = reactive<{ keyword: string; enabled?: boolean; parentId?: number }>({
  keyword: '',
  enabled: undefined,
  parentId: undefined
});

function applyFilters() {
  void workspace.loadGroups({
    keyword: query.keyword || undefined,
    enabled: query.enabled,
    parentId: query.parentId,
    scope: activeTab.value
  });
}

function resetFilters() {
  query.keyword = '';
  query.enabled = undefined;
  query.parentId = undefined;
  applyFilters();
}

function handleTabChange() {
  // 父级下拉按归属重新加载，原父级筛选值不再适用
  query.parentId = undefined;
  void workspace.loadDropdown(activeTab.value);
  applyFilters();
}

onMounted(() => {
  applyFilters();
  void workspace.loadDropdown(activeTab.value);
  void workspace.loadPermissions();
});

// 切换当前站点后，栏目权限树、组列表与父级下拉均按新站点重新加载
watch(
  () => siteContext.currentSite?.id,
  () => {
    void workspace.loadPermissions();
    applyFilters();
    void workspace.loadDropdown(activeTab.value);
  }
);

watch(
  () => workspace.permissionWorkspace.value.sitePermissions,
  async () => {
    if (dialogVisible.value && pendingTreeSetting !== '') {
      await permissionTree.syncCheckedKeys(pendingTreeSetting);
      pendingTreeSetting = '';
    }
  },
  { deep: true }
);

function isProtectedGroup(row: AdminGroupView) {
  return row.id === 1 || row.id === 2;
}

/** 平台组：site_id=0，跨站点共享，仅允许修改与菜单配置 */
function isPlatformGroup(row: Pick<AdminGroupView, 'siteId'>) {
  return row.siteId === 0;
}

function siteLabelOf(siteId?: number) {
  if (siteId === 0) return '平台';
  if (siteId == null) return '-';
  return siteNameOf(siteId);
}

function switchTooltip(row: AdminGroupView) {
  if (isPlatformGroup(row)) return '平台组不可切换状态';
  if (isProtectedGroup(row)) return '顶级管理员组不可切换状态';
  return row.enabled !== false ? '点击停用' : '点击启用';
}

function buildTree(flat: AdminGroupView[]): TreeGroup[] {
  const map = new Map<number, TreeGroup>();
  flat.forEach((item) => map.set(item.id, { ...item }));
  const roots: TreeGroup[] = [];
  map.forEach((node) => {
    const parent = node.parentId ? map.get(node.parentId) : undefined;
    if (parent) {
      parent.children = parent.children ?? [];
      parent.children.push(node);
    } else {
      roots.push(node);
    }
  });
  return roots;
}

function clearOpSelection() {
  Object.keys(opSelection).forEach((key) => delete opSelection[Number(key)]);
}

function clearPlatformOpSelection() {
  Object.keys(platformOpSelection).forEach((key) => delete platformOpSelection[key]);
}

function resolvePlatformOpSelection(platformSetting?: string, platformOtherSetting?: string) {
  clearPlatformOpSelection();
  parsePlatformSetting(platformSetting, platformOtherSetting).forEach((ops, key) => {
    platformOpSelection[key] = ops;
  });
}

function togglePlatformOp(key: string, optionId: number, checked: boolean) {
  const current = platformOpSelection[key] ?? [];
  if (checked) {
    platformOpSelection[key] = current.includes(optionId) ? current : [...current, optionId];
  } else {
    platformOpSelection[key] = current.filter((id) => id !== optionId);
  }
}

function isPlatformOpSelected(key: string, optionId: number) {
  return (platformOpSelection[key] ?? []).includes(optionId);
}

function checkAllPlatformOps(type: string) {
  const mod = platformModules.value.find((item) => item.type === type);
  if (!mod) {
    return;
  }
  mod.categories.forEach((category) => {
    platformOpSelection[`${type}:${category.id}`] = category.operationOptions.map((op) => op.id);
  });
}

function clearAllPlatformOps(type: string) {
  const prefix = `${type}:`;
  Object.keys(platformOpSelection).forEach((key) => {
    if (key.startsWith(prefix)) {
      delete platformOpSelection[key];
    }
  });
}

function checkAllPlatformNodeOps(key: string, options: Array<{ id: number; title: string }>) {
  platformOpSelection[key] = options.map((op) => op.id);
}

function invertPlatformNodeOps(key: string, options: Array<{ id: number; title: string }>) {
  const current = new Set(platformOpSelection[key] ?? []);
  platformOpSelection[key] = options.map((op) => op.id).filter((id) => !current.has(id));
}

function resetForm(parentId: number) {
  Object.assign(form, { id: 0, title: '', parentId, note: '', enabled: true, groupSetting: '0', otherSetting: '' });
  formScope.value = 'site';
  selectedMenuKeys.value = [];
  selectedGlobalKeys.value = [];
  clearOpSelection();
}

function resolveSelectedKeys() {
  const keys = new Set(stringSettingKeys(form.groupSetting));
  selectedMenuKeys.value = menuPermissionOptions.value.map((permission) => String(permission.key)).filter((key) => keys.has(key));
  selectedGlobalKeys.value = globalPermissionOptions.value.map((permission) => String(permission.key)).filter((key) => keys.has(key));
}

function resolveOpSelection() {
  clearOpSelection();
  otherSettingOptions(form.groupSetting, form.otherSetting).forEach((ops, menuId) => {
    opSelection[menuId] = ops;
  });
}

function toggleOp(menuId: number, optionId: number, checked: boolean) {
  const current = opSelection[menuId] ?? [];
  if (checked) {
    if (!current.includes(optionId)) {
      opSelection[menuId] = [...current, optionId];
    }
    // 勾选操作权限时自动授权对应栏目，保证栏目 id 进入 groupSetting、操作进入 otherSetting
    const selected = new Set<number>((permissionTree.treeRef.value?.getCheckedKeys() ?? []).map((key) => Number(key)));
    selected.add(menuId);
    permissionTree.treeRef.value?.setCheckedKeys(Array.from(selected));
  } else {
    opSelection[menuId] = current.filter((id) => id !== optionId);
  }
}

function isOpSelected(menuId: number, optionId: number) {
  return (opSelection[menuId] ?? []).includes(optionId);
}

function checkAllOps(menuId: number, options: OperationOption[]) {
  opSelection[menuId] = options.map((option) => option.id);
  // 全选操作时自动授权对应栏目（若未勾选），保证栏目 id 进入 groupSetting、操作进入 otherSetting
  const selected = new Set<number>((permissionTree.treeRef.value?.getCheckedKeys() ?? []).map((key) => Number(key)));
  selected.add(menuId);
  permissionTree.treeRef.value?.setCheckedKeys(Array.from(selected));
}

function invertOps(menuId: number, options: OperationOption[]) {
  const ids = new Set(options.map((option) => option.id));
  const current = new Set(opSelection[menuId] ?? []);
  opSelection[menuId] = options.map((option) => option.id).filter((id) => !current.has(id) && ids.has(id));
  if (opSelection[menuId].length > 0) {
    const selected = new Set<number>((permissionTree.treeRef.value?.getCheckedKeys() ?? []).map((key) => Number(key)));
    selected.add(menuId);
    permissionTree.treeRef.value?.setCheckedKeys(Array.from(selected));
  }
}

function checkAllMenu() {
  permissionTree.treeRef.value?.setCheckedKeys(flattenPermissionIds(sitePermissionTree.value));
}

function handlePermCheck(data: PermissionTreeNode, info: { checkedKeys: Array<string | number> }) {
  const checked = info.checkedKeys.includes(data.id as string | number);
  checkChildrenRecursively(data, checked);
}

function checkChildrenRecursively(node: PermissionTreeNode, checked: boolean) {
  if (node.id != null) {
    opSelection[Number(node.id)] = checked ? (node.operationOptions ?? []).map((option) => option.id) : [];
  }
  (node.children ?? []).forEach((child) => {
    if (child.id != null && permissionTree.treeRef.value) {
      permissionTree.treeRef.value.setChecked(child.id, checked, false);
    }
    checkChildrenRecursively(child, checked);
  });
}

function clearAllMenu() {
  permissionTree.treeRef.value?.setCheckedKeys([]);
}

/** 按 groupSetting 的 key 顺序，将每个栏目的操作权限组装为分号分隔的 otherSetting */
function buildOtherSetting(groupSetting: string): string {
  return stringSettingKeys(groupSetting)
    .map((key) => (opSelection[Number(key)] ?? []).join(','))
    .join(';');
}

function openAdd(parentId: number) {
  resetForm(parentId);
  formScope.value = activeTab.value;
  clearPlatformOpSelection();
  dialogVisible.value = true;
  pendingTreeSetting = form.groupSetting;
  void nextTick(() => permissionTree.syncCheckedKeys(form.groupSetting));
}

function openEdit(row: AdminGroupView) {
  Object.assign(form, {
    id: row.id,
    title: row.rawTitle ?? row.title,
    parentId: row.parentId,
    note: row.note ?? '',
    enabled: row.enabled !== false,
    groupSetting: row.groupSetting ?? '0',
    otherSetting: row.otherSetting ?? ''
  });
  formScope.value = isPlatformGroup(row) ? 'platform' : 'site';
  resolveSelectedKeys();
  resolveOpSelection();
  resolvePlatformOpSelection(row.platformSetting, row.platformOtherSetting);
  dialogVisible.value = true;
  pendingTreeSetting = form.groupSetting;
  void nextTick(() => permissionTree.syncCheckedKeys(form.groupSetting));
}

async function toggleGroup(row: AdminGroupView, _value: boolean) {
  await workspace.switchGroup(row);
}

async function removeGroup(row: AdminGroupView) {
  await workspace.deleteGroup(row);
}

async function save() {
  if (!form.title.trim()) {
    ElMessage.error('请输入管理员组名称');
    return;
  }
  // 平台组表单不含站点栏目权限：直接沿用原 groupSetting，不并入（未挂载的）栏目树
  const treeSetting = isPlatformForm.value
    ? form.groupSetting
    : permissionTree.checkedGroupSetting(form.groupSetting, [], []);
  // 菜单权限 + 全局权限（系统管理入口、内容置顶/热门/推荐）由栏目树自动顺带授予
  const groupSetting = uniqueKeys([
    ...stringSettingKeys(treeSetting),
    ...menuPermissionOptions.value.map((p) => String(p.key)),
    ...globalPermissionOptions.value.map((p) => String(p.key))
  ]).join(',') || '0';
  // 平台组表单：序列化数据中台权限；站点组表单不传，保持原值
  const platformSettings = isPlatformForm.value ? buildPlatformSettings(platformOpSelection) : undefined;
  const ok = await workspace.saveGroup({
    id: form.id,
    title: form.title.trim(),
    parentId: form.parentId,
    note: form.note,
    enabled: form.enabled,
    groupSetting,
    otherSetting: buildOtherSetting(groupSetting),
    platformSetting: platformSettings?.platformSetting,
    platformOtherSetting: platformSettings?.platformOtherSetting,
    // 平台标签页新增时归属平台（site_id=0），编辑时不改写归属
    siteId: form.id === 0 && formScope.value === 'platform' ? 0 : undefined
  });
  if (ok) {
    dialogVisible.value = false;
  }
}

// 菜单配置相关方法
async function openMenuConfig(row: AdminGroupView) {
  currentMenuConfigGroup.value = row;

  // 加载菜单树
  try {
    const menus = await getMenuTree();
    menuTreeData.value = buildMenuTree(menus);
  } catch (e) {
    ElMessage.error('加载菜单失败');
    return;
  }

  // 解析已选中的菜单
  selectedMenuIds.value = [];
  if (row.menuSetting) {
    try {
      const parsed = JSON.parse(row.menuSetting);
      if (Array.isArray(parsed)) {
        selectedMenuIds.value = parsed.map(id => Number(id)).filter(id => !isNaN(id));
      }
    } catch {
      // JSON 解析失败，忽略
    }
  }

  menuConfigVisible.value = true;
}

/**
 * 将扁平菜单列表构建为树形结构
 */
function buildMenuTree(menus: MenuEntity[]): MenuEntity[] {
  const menuMap = new Map<number, MenuEntity>();
  menus.forEach(m => menuMap.set(Number(m.id), { ...m, children: [] }));

  const roots: MenuEntity[] = [];
  menus.forEach(m => {
    const menu = menuMap.get(Number(m.id))!;
    if (Number(m.parentId) === 0) {
      roots.push(menu);
    } else {
      const parent = menuMap.get(Number(m.parentId));
      if (parent) {
        parent.children = parent.children || [];
        parent.children.push(menu);
      }
    }
  });

  return roots;
}

async function saveMenuConfig() {
  if (!currentMenuConfigGroup.value) return;

  const checkedKeys = menuConfigTreeRef.value?.getCheckedKeys() as number[] | undefined;
  const menuIds = checkedKeys ?? [];

  menuConfigSaving.value = true;
  try {
    const ok = await workspace.saveGroup({
      id: currentMenuConfigGroup.value.id,
      title: currentMenuConfigGroup.value.title,
      parentId: currentMenuConfigGroup.value.parentId,
      note: currentMenuConfigGroup.value.note,
      enabled: currentMenuConfigGroup.value.enabled,
      groupSetting: currentMenuConfigGroup.value.groupSetting,
      otherSetting: currentMenuConfigGroup.value.otherSetting,
      menuSetting: JSON.stringify(menuIds)
    });

    if (ok) {
      ElMessage.success('菜单配置已保存');
      menuConfigVisible.value = false;
      // 刷新列表
      await applyFilters();
    }
  } finally {
    menuConfigSaving.value = false;
  }
}

defineExpose({ openEdit });
</script>

<style scoped>
.work-page { display: grid; gap: var(--admin-card-gap); }
.work-hero, .work-card { border: var(--admin-card-border); border-radius: var(--admin-card-radius); background: var(--admin-bg-surface); box-shadow: var(--admin-shadow-md); }
.work-hero { display: flex; justify-content: space-between; gap: 20px; padding: var(--admin-card-padding-roomy); }
.work-hero h1, .work-hero p { margin: 0; }
.work-hero p { color: var(--admin-text-muted); }
.work-card { padding: var(--admin-card-padding); }
.hero-actions { display: flex; gap: 8px; align-items: center; }
.filters { display: flex; flex-wrap: wrap; gap: 12px; margin-bottom: 16px; }
.filters .el-input { width: 220px; }
.filters .el-select { width: 160px; }
.form-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px; }
.site-info-desc { margin-bottom: 16px; }
.permission-panel { width: 100%; padding: 14px; border: var(--admin-card-border); border-radius: var(--admin-card-radius); background: #fafafa; max-height: 60vh; overflow: auto; }
.perm-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 10px; color: var(--admin-text-muted); font-size: 13px; }
.perm-toolbar__actions { display: flex; align-items: center; gap: 4px; }
.perm-node { display: flex; flex-wrap: wrap; align-items: flex-start; gap: 2px 12px; min-width: 0; }
.platform-perm-tabs :deep(.el-tabs__header) { margin-bottom: 8px; }
.platform-perm-tabs :deep(.el-tabs__content) { overflow: visible; }
.platform-perm-node { display: inline-flex; flex-wrap: wrap; align-items: center; gap: 4px 12px; min-width: 0; }
.perm-node__label { white-space: nowrap; line-height: 22px; display: inline-flex; align-items: center; gap: 6px; }
.perm-node__id { font-family: var(--admin-mono, monospace); font-size: 11px; }
.perm-node__ops { display: flex; flex-wrap: wrap; align-items: center; row-gap: 2px; column-gap: 10px; color: var(--admin-text-muted); }
.perm-node__ops :deep(.el-checkbox) { margin-right: 0; white-space: nowrap; height: 22px; }
.perm-node__ops :deep(.el-checkbox__label) { white-space: nowrap; padding-left: 4px; font-size: 12px; }
.perm-node__ops :deep(.el-checkbox__inner) { width: 13px; height: 13px; }
.perm-node__ops :deep(.el-checkbox__inner::after) { left: 3px; top: 1px; width: 4px; height: 7px; }
.permission-panel :deep(.el-tree-node__content) { height: auto; min-height: 28px; padding-top: 3px; padding-bottom: 3px; align-items: flex-start; }
.permission-checks { display: flex; flex-wrap: wrap; gap: 8px 14px; }
.menu-config-panel { padding: var(--admin-card-padding-compact); }
.menu-config-hint { margin: 0 0 16px; color: var(--admin-text-muted); font-size: 14px; }
.menu-node { display: flex; align-items: center; gap: 8px; }
.menu-node__label { font-size: 14px; }
@media (max-width: 1024px) { .form-grid { grid-template-columns: 1fr; } }
</style>
