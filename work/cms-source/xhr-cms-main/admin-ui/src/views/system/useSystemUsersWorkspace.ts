import { createTableLoader } from '@/utils/tableLoader';
import { reactive, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { http } from '@/api/http';
import { useSiteContextStore } from '@/stores/siteContext';
import type { GroupView } from './UserGroupPermissionPanel.vue';
import type { PasswordForm, ProfileForm } from './ProfileSecurityPanel.vue';
import type { UserEditorUser } from './UserEditorDrawer.vue';

export interface UserView {
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
  permissionSummary?: string;
  mobile?: string;
}

interface UserWorkspace {
  users: UserView[];
  groups: GroupView[];
  permissionWorkspace?: PermissionWorkspace;
}

export interface PermissionWorkspace {
  sitePermissions?: PermissionWorkspaceNode[];
  menuPermissions?: PermissionWorkspaceNode[];
  globalPermissions?: PermissionWorkspaceNode[];
}

export interface PermissionWorkspaceNode {
  id?: number;
  parentId?: number;
  key?: string;
  label?: string;
  className?: string;
  scope?: string;
  children?: PermissionWorkspaceNode[];
}

export function useSystemUsersWorkspace(siteContext: ReturnType<typeof useSiteContextStore>) {
  void siteContext;

  const loading = ref(false);
  const saving = ref(false);
  const users = ref<UserView[]>([]);
  const groups = ref<GroupView[]>([]);
  const permissionWorkspace = ref<PermissionWorkspace>({});
  const currentUserId = ref<number | undefined>();
  const profileForm = reactive<ProfileForm>({
    id: 0,
    username: '',
    screenName: '',
    email: '',
    mobile: '',
    groupName: '',
    groupId: undefined,
    lastLoginIp: '',
    loginTimes: 0
  });
  const passwordForm = reactive<PasswordForm>({ oldPassword: '', password: '', repass: '' });
  const passwordError = ref('');

  const loadWorkspaceLoader = createTableLoader(loading);

  async function loadWorkspace() {
    const request = loadWorkspaceLoader.begin(() => {
      users.value = [];
      groups.value = [];
    });
    try {
      const data = await http.get<UserWorkspace>('/api/admin/v2/system/users');
      await loadCurrentProfile();
      if (!request.isCurrent()) return;
      users.value = data.users ?? [];
      groups.value = data.groups ?? [];
      permissionWorkspace.value = data.permissionWorkspace ?? {};
    } catch (error) {
      if (!request.isCurrent()) return;
      ElMessage.error(errorMessage(error, '用户权限加载失败'));
    } finally {
      request.finish();
    }
  }

  async function loadCurrentProfile() {
    const data = await http.get<UserView>('/api/admin/v2/system/profile');
    currentUserId.value = data.id;
    Object.assign(profileForm, {
      id: data.id,
      username: data.username,
      screenName: data.screenName,
      email: data.email,
      mobile: data.mobile ?? '',
      groupName: data.groupName ?? '',
      groupId: data.groupId,
      lastLoginIp: data.lastLoginIp ?? '',
      loginTimes: data.loginTimes ?? 0
    });
  }

  async function saveProfile(nextProfile: ProfileForm) {
    if (!nextProfile.screenName.trim() || !nextProfile.email.trim()) {
      ElMessage.error('姓名和邮箱不能为空');
      return false;
    }
    saving.value = true;
    try {
      const data = await http.post<UserView>('/api/admin/v2/system/profile', {
        screenName: nextProfile.screenName,
        mobile: nextProfile.mobile ?? '',
        email: nextProfile.email
      });
      Object.assign(profileForm, data);
      ElMessage.success('个人资料已保存');
      return true;
    } catch (error) {
      ElMessage.error(errorMessage(error, '个人资料保存失败'));
      return false;
    } finally {
      saving.value = false;
    }
  }

  async function savePassword(nextPassword: PasswordForm) {
    passwordError.value = validatePasswordForm(nextPassword);
    if (passwordError.value) {
      return false;
    }
    saving.value = true;
    try {
      await http.post<void>('/api/admin/v2/system/profile/password', { ...nextPassword });
      ElMessage.success('密码已修改');
      return true;
    } catch (error) {
      passwordError.value = errorMessage(error, '密码修改失败');
      return false;
    } finally {
      saving.value = false;
    }
  }

  async function saveUser(payload: UserEditorUser) {
    saving.value = true;
    try {
      await http.post<UserView>('/api/admin/v2/system/users', payload);
      ElMessage.success('用户已保存');
      await loadWorkspace();
      return true;
    } catch (error) {
      ElMessage.error(errorMessage(error, '用户保存失败'));
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

  async function disableUser(userId: number) {
    if (userId === currentUserId.value) {
      ElMessage.warning('当前登录管理员不能禁用自己');
      return false;
    }
    saving.value = true;
    try {
      await http.post<UserView>(`/api/admin/v2/system/users/${userId}/disable`, {});
      ElMessage.success('账号已禁用');
      await loadWorkspace();
      return true;
    } catch (error) {
      ElMessage.error(errorMessage(error, '账号禁用失败'));
      return false;
    } finally {
      saving.value = false;
    }
  }

  async function deleteUser(row: UserView | UserEditorUser) {
    if (isSuperAdmin(row)) {
      ElMessage.warning('超级管理员不可删除');
      return false;
    }
    await ElMessageBox.confirm(`确认删除用户“${row.username}”？`, '删除用户', { type: 'warning' });
    await http.delete<void>(`/api/admin/v2/system/users/${row.id}`);
    ElMessage.success('用户已删除');
    await loadWorkspace();
    return true;
  }

  async function saveGroup(group: GroupView) {
    saving.value = true;
    try {
      await http.post<GroupView>('/api/admin/v2/system/groups', group);
      ElMessage.success('管理员组已保存');
      await loadWorkspace();
      return true;
    } catch (error) {
      ElMessage.error(errorMessage(error, '管理员组保存失败'));
      return false;
    } finally {
      saving.value = false;
    }
  }

  async function switchGroup(row: GroupView) {
    if (isProtectedAdminGroup(row)) {
      ElMessage.warning('顶级管理员组不可切换状态');
      return false;
    }
    saving.value = true;
    try {
      await http.post<GroupView>(`/api/admin/v2/system/groups/${row.id}/switch`, { fieldName: 'Enabled' });
      ElMessage.success(row.enabled === false ? '管理员组已启用' : '管理员组已停用');
      await loadWorkspace();
      return true;
    } catch (error) {
      ElMessage.error(errorMessage(error, '管理员组状态切换失败'));
      return false;
    } finally {
      saving.value = false;
    }
  }

  async function deleteGroup(row: GroupView) {
    await ElMessageBox.confirm(`确认删除管理员组“${row.title}”？`, '删除管理员组', { type: 'warning' });
    await http.delete<void>(`/api/admin/v2/system/groups/${row.id}`);
    ElMessage.success('管理员组已删除');
    await loadWorkspace();
    return true;
  }

  return {
    loading,
    saving,
    users,
    groups,
    permissionWorkspace,
    currentUserId,
    profileForm,
    passwordForm,
    passwordError,
    loadWorkspace,
    loadCurrentProfile,
    saveUser,
    resetUserPassword,
    disableUser,
    deleteUser,
    saveGroup,
    switchGroup,
    deleteGroup,
    saveProfile,
    savePassword
  };
}

function validatePasswordForm(nextPassword: PasswordForm) {
  if (!nextPassword.oldPassword.trim()) {
    return '请填写原密码';
  }
  if (!nextPassword.password.trim()) {
    return '请填写新密码';
  }
  if (!nextPassword.repass.trim()) {
    return '请再次确认新密码';
  }
  if (nextPassword.password !== nextPassword.repass) {
    return '两次输入的新密码不一致';
  }
  if (nextPassword.password === nextPassword.oldPassword) {
    return '新密码不能与旧密码相同';
  }
  return '';
}

function errorMessage(error: unknown, fallback: string) {
  return error instanceof Error ? error.message : fallback;
}

function isSuperAdmin(row: Pick<UserView, 'id' | 'username'>) {
  return row.id === 1 || row.id === 13 || row.username === 'supera';
}

function isProtectedAdminGroup(row: GroupView) {
  return row.id === 1 || row.id === 2;
}
