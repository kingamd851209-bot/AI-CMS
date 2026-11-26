import { createTableLoader } from '@/utils/tableLoader';
import { ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { http } from '@/api/http';
import type { PermissionTreeNode } from './usePermissionTreeEditor';

export interface AdminGroupView {
  id: number;
  title: string;
  rawTitle?: string;
  parentId: number;
  parentTitle?: string;
  note?: string;
  enabled?: boolean;
  groupSetting?: string;
  otherSetting?: string;
  menuSetting?: string;
  /** 数据中台类别权限（逗号分隔键 类型:类别id，如 product:12,news:3） */
  platformSetting?: string;
  /** 数据中台操作权限（分号分段，与 platformSetting 逐位对齐；段内逗号分隔操作id：1新增/2编辑/3删除/4审核/5查看） */
  platformOtherSetting?: string;
  createTime?: string;
  userCount?: number;
  siteId?: number;
}

export interface GroupDropdownItem {
  id: number;
  title: string;
}

/** 数据中台类别节点（扁平，前端按 parentId 建树） */
export interface PlatformCategoryNode {
  id: number;
  parentId: number;
  label: string;
  operationOptions: Array<{ id: number; title: string }>;
}

/** 数据中台模块（类型 + 标题 + 该模块类别节点） */
export interface PlatformModuleCategories {
  type: string;
  label: string;
  categories: PlatformCategoryNode[];
}

export interface GroupPermissionWorkspace {
  sitePermissions: PermissionTreeNode[];
  menuPermissions: PermissionTreeNode[];
  globalPermissions: PermissionTreeNode[];
  platformModules: PlatformModuleCategories[];
}

/** 解析平台组数据中台权限：类别键（类型:类别id）→ 已勾选操作 id 列表 */
export function parsePlatformSetting(platformSetting?: string, platformOtherSetting?: string): Map<string, number[]> {
  const result = new Map<string, number[]>();
  const keys = String(platformSetting || '')
    .split(',')
    .map((key) => key.trim())
    .filter((key) => key && key !== '0');
  const segments = String(platformOtherSetting || '').split(';');
  keys.forEach((key, index) => {
    const segment = segments[index] ?? '';
    const ops = segment
      .split(',')
      .map((value) => Number(value.trim()))
      .filter((value) => Number.isInteger(value) && value > 0);
    result.set(key, ops);
  });
  return result;
}

/** 序列化数据中台权限：仅含操作勾选的类别键；全空返回空串（视为未配置） */
export function buildPlatformSettings(selection: Record<string, number[]>): {
  platformSetting: string;
  platformOtherSetting: string;
} {
  const keys = Object.keys(selection)
    .filter((key) => (selection[key] ?? []).length > 0)
    .sort();
  return {
    platformSetting: keys.join(','),
    platformOtherSetting: keys.map((key) => (selection[key] ?? []).join(',')).join(';')
  };
}

export interface AdminGroupQuery {
  keyword?: string;
  enabled?: boolean;
  parentId?: number;
  /** 组归属范围：site=当前站点组（默认），platform=平台组（site_id=0） */
  scope?: AdminGroupScope;
}

export type AdminGroupScope = 'site' | 'platform';

export function useAdminGroupsWorkspace() {
  const loading = ref(false);
  const saving = ref(false);
  const groups = ref<AdminGroupView[]>([]);
  const dropdown = ref<GroupDropdownItem[]>([]);
  const permissionWorkspace = ref<GroupPermissionWorkspace>({ sitePermissions: [], menuPermissions: [], globalPermissions: [], platformModules: [] });

  /** 最近一次查询条件：保存/启停/删除后的自动刷新需沿用，避免筛选（含组归属）被重置 */
  let lastQuery: AdminGroupQuery = {};

  const loadGroupsLoader = createTableLoader(loading);

  async function loadGroups(query: AdminGroupQuery = lastQuery) {
    lastQuery = query;
    const request = loadGroupsLoader.begin(() => {
      groups.value = [];
    });
    try {
      const params: Record<string, string> = {};
      if (query.keyword?.trim()) params.keyword = query.keyword.trim();
      if (query.enabled != null) params.enabled = String(query.enabled);
      if (query.parentId != null) params.parentId = String(query.parentId);
      if (query.scope === 'platform') params.scope = 'platform';
      const loadedRows = await http.get<AdminGroupView[]>('/api/admin/v2/system/admin-groups', { params });
      if (!request.isCurrent()) return;
      groups.value = loadedRows;
    } catch (error) {
      if (!request.isCurrent()) return;
      ElMessage.error(errorMessage(error, '管理员组加载失败'));
    } finally {
      request.finish();
    }
  }

  async function loadDropdown(scope?: AdminGroupScope) {
    try {
      const params: Record<string, string> = {};
      if (scope === 'platform') params.scope = 'platform';
      dropdown.value = await http.get<GroupDropdownItem[]>('/api/admin/v2/system/admin-groups/dropdown', { params });
    } catch (error) {
      ElMessage.error(errorMessage(error, '管理员组下拉加载失败'));
    }
  }

  async function loadPermissions() {
    try {
      const data = await http.get<GroupPermissionWorkspace>('/api/admin/v2/system/admin-groups/permissions');
      permissionWorkspace.value = {
        sitePermissions: data.sitePermissions ?? [],
        menuPermissions: data.menuPermissions ?? [],
        globalPermissions: data.globalPermissions ?? [],
        platformModules: data.platformModules ?? []
      };
    } catch (error) {
      ElMessage.error(errorMessage(error, '权限树加载失败'));
    }
  }

  async function saveGroup(payload: AdminGroupView) {
    saving.value = true;
    try {
      await http.post<AdminGroupView>('/api/admin/v2/system/admin-groups', payload);
      ElMessage.success('管理员组已保存');
      await loadGroups();
      return true;
    } catch (error) {
      ElMessage.error(errorMessage(error, '管理员组保存失败'));
      return false;
    } finally {
      saving.value = false;
    }
  }

  async function switchGroup(row: AdminGroupView) {
    if (row.id === 1 || row.id === 2) {
      ElMessage.warning('顶级管理员组不可切换状态');
      return false;
    }
    saving.value = true;
    try {
      await http.post<AdminGroupView>(`/api/admin/v2/system/admin-groups/${row.id}/switch`, { fieldName: 'Enabled' });
      ElMessage.success(row.enabled === false ? '管理员组已启用' : '管理员组已停用');
      await loadGroups();
      return true;
    } catch (error) {
      ElMessage.error(errorMessage(error, '管理员组状态切换失败'));
      return false;
    } finally {
      saving.value = false;
    }
  }

  async function deleteGroup(row: AdminGroupView) {
    await ElMessageBox.confirm(`确认删除管理员组"${row.title}"？`, '删除管理员组', { type: 'warning' });
    saving.value = true;
    try {
      await http.delete<void>(`/api/admin/v2/system/admin-groups/${row.id}`);
      ElMessage.success('管理员组已删除');
      await loadGroups();
      return true;
    } catch (error) {
      ElMessage.error(errorMessage(error, '管理员组删除失败'));
      return false;
    } finally {
      saving.value = false;
    }
  }

  return {
    loading,
    saving,
    groups,
    dropdown,
    permissionWorkspace,
    loadGroups,
    loadDropdown,
    loadPermissions,
    saveGroup,
    switchGroup,
    deleteGroup
  };
}

function errorMessage(error: unknown, fallback: string) {
  return error instanceof Error ? error.message : fallback;
}
