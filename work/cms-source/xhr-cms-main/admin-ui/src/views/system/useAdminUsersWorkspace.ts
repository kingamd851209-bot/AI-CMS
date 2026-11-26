import { createTableLoader } from '@/utils/tableLoader';
import { computed, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { http } from '@/api/http';
import { useSiteContextStore } from '@/stores/siteContext';
import type { UserEditorUser } from './UserEditorDrawer.vue';

export interface AdminUserView {
  id: number;
  username: string;
  email: string;
  screenName: string;
  groupName?: string;
  groupId?: number;
  activated?: number;
  status?: string;
  lastLoginTime?: string;
  lastLoginIp?: string;
  loginTimes?: number;
  siteGroups?: AdminUserSiteGroup[];
}

export interface AdminUserSiteGroup {
  siteId?: number;
  siteName?: string;
  groupId?: number;
  groupName?: string;
}

export interface GroupOption {
  id: number;
  title: string;
}

export interface SiteGroupOption {
  siteId: number;
  siteName: string;
  groups: GroupOption[];
}

export function useAdminUsersWorkspace() {
  const siteContext = useSiteContextStore();
  const currentUserId = computed(() => siteContext.currentAdmin?.id);
  const loading = ref(false);
  const saving = ref(false);
  const users = ref<AdminUserView[]>([]);
  const groups = ref<GroupOption[]>([]);
  const siteGroups = ref<SiteGroupOption[]>([]);

  const loadUsersLoader = createTableLoader(loading);

  async function loadUsers() {
    const request = loadUsersLoader.begin(() => {
      users.value = [];
    });
    try {
      const loadedRows = await http.get<AdminUserView[]>('/api/admin/v2/system/admin-users');
      if (!request.isCurrent()) return;
      users.value = loadedRows;
    } catch (error) {
      if (!request.isCurrent()) return;
      ElMessage.error(errorMessage(error, '管理员加载失败'));
    } finally {
      request.finish();
    }
  }

  async function loadGroups() {
    try {
      groups.value = await http.get<GroupOption[]>('/api/admin/v2/system/admin-users/groups');
    } catch (error) {
      ElMessage.error(errorMessage(error, '管理员组下拉加载失败'));
    }
  }

  async function loadSiteGroups() {
    try {
      siteGroups.value = await http.get<SiteGroupOption[]>('/api/admin/v2/system/admin-users/site-groups');
    } catch (error) {
      ElMessage.error(errorMessage(error, '站点-组加载失败'));
    }
  }

  async function saveUser(payload: UserEditorUser) {
    saving.value = true;
    try {
      await http.post<AdminUserView>('/api/admin/v2/system/admin-users', payload);
      ElMessage.success('管理员已保存');
      await loadUsers();
      return true;
    } catch (error) {
      ElMessage.error(errorMessage(error, '管理员保存失败'));
      return false;
    } finally {
      saving.value = false;
    }
  }

  async function resetUserPassword(payload: { userId: number; password: string }) {
    if (!payload.password.trim()) {
      ElMessage.error('请填写新密码');
      return false;
    }
    saving.value = true;
    try {
      await http.post<void>(`/api/admin/v2/system/users/${payload.userId}/reset-password`, { password: payload.password });
      ElMessage.success('密码已重置');
      return true;
    } catch (error) {
      ElMessage.error(errorMessage(error, '密码重置失败'));
      return false;
    } finally {
      saving.value = false;
    }
  }

  async function deleteUser(row: AdminUserView | UserEditorUser) {
    if (isSuperAdmin(row)) {
      ElMessage.warning('超级管理员不可删除');
      return false;
    }
    await ElMessageBox.confirm(`确认删除管理员"${row.username}"？`, '删除管理员', { type: 'warning' });
    saving.value = true;
    try {
      await http.delete<void>(`/api/admin/v2/system/admin-users/${row.id}`);
      ElMessage.success('管理员已删除');
      await loadUsers();
      return true;
    } catch (error) {
      ElMessage.error(errorMessage(error, '管理员删除失败'));
      return false;
    } finally {
      saving.value = false;
    }
  }

  async function toggleUserStatus(row: AdminUserView, enabled: boolean) {
    saving.value = true;
    try {
      await http.post<AdminUserView>(`/api/admin/v2/system/admin-users/${row.id}/status`, { enabled });
      ElMessage.success(enabled ? '账号已启用' : '账号已停用');
      await loadUsers();
      return true;
    } catch (error) {
      ElMessage.error(errorMessage(error, enabled ? '启用账号失败' : '停用账号失败'));
      return false;
    } finally {
      saving.value = false;
    }
  }

  return {
    loading,
    saving,
    users,
    groups,
    siteGroups,
    currentUserId,
    loadUsers,
    loadGroups,
    loadSiteGroups,
    saveUser,
    resetUserPassword,
    deleteUser,
    toggleUserStatus
  };
}

export function isUserEnabled(row: Pick<AdminUserView, 'activated'>) {
  return row.activated !== 1;
}

export function isSuperAdmin(row: Pick<AdminUserView, 'id' | 'username'>) {
  return row.id === 1 || row.id === 13 || row.username === 'supera';
}

function errorMessage(error: unknown, fallback: string) {
  return error instanceof Error ? error.message : fallback;
}
