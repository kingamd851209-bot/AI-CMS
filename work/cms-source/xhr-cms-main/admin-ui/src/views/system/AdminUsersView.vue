<template>
  <section class="work-page">
    <header class="work-hero">
      <div>
        <p class="admin-eyebrow">系统管理</p>
        <h1>管理员管理</h1>
        <p>维护后台登录管理员账号及其所属管理员组。</p>
      </div>
      <div class="hero-actions">
        <el-button size="small" :loading="workspace.loading.value" @click="workspace.loadUsers()">刷新</el-button>
        <el-button size="small" type="primary" @click="openAdd">添加用户</el-button>
      </div>
    </header>

    <section class="work-card">
      <div class="filters admin-table-toolbar">
        <el-input size="small" v-model="query.username" placeholder="按账户搜索" clearable />
        <el-input size="small" v-model="query.screenName" placeholder="按名称搜索" clearable />
        <el-input size="small" v-model="query.email" placeholder="按邮箱搜索" clearable />
        <el-select size="small" v-model="query.siteId" placeholder="站点" clearable filterable>
          <el-option v-for="site in siteOptions" :key="site.siteId" :value="site.siteId" :label="site.siteName" />
        </el-select>
        <el-select size="small" v-model="query.groupId" placeholder="管理员组" clearable filterable>
          <el-option v-for="g in groupOptions" :key="g.groupId" :value="g.groupId" :label="g.groupName" />
        </el-select>
        <el-button size="small" type="primary" @click="applyFilters">查询</el-button>
        <el-button size="small" @click="resetFilters">重置</el-button>
      </div>

      <el-table element-loading-text="正在加载数据…" v-loading="workspace.loading.value" :data="filteredUsers" stripe>
        <el-table-column type="expand">
          <template #default="{ row }">
            <div class="site-group-expand">
              <div v-if="!row.siteGroups?.length" class="empty-tip">该用户暂未分配到站点管理员组</div>
              <template v-else>
                <div class="expand-header">
                  <span class="col-site">站点名称</span>
                  <span class="col-group">管理员组</span>
                </div>
                <div v-for="site in groupedBySite(row.siteGroups)" :key="site.siteId" class="site-group-block">
                  <span class="col-site site-name">{{ site.siteName }}</span>
                  <span class="col-group">
                    <el-tag v-for="g in site.groups" :key="g.groupId" size="small" class="group-tag">{{ g.groupName || `#${g.groupId}` }}</el-tag>
                  </span>
                </div>
              </template>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="username" label="账号" min-width="140" />
        <el-table-column prop="screenName" label="姓名" min-width="140" />
        <el-table-column prop="email" label="邮箱" min-width="200" show-overflow-tooltip />
        <el-table-column label="状态" width="90" align="center">
          <template #default="{ row }">
            <el-tag :type="isUserEnabled(row) ? 'success' : 'info'" size="small">
              {{ isUserEnabled(row) ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="lastLoginTime" label="最后登录时间" width="180" align="center" />
        <el-table-column prop="lastLoginIp" label="最后登录IP" width="140" align="center" />
        <el-table-column prop="loginTimes" label="累计登录次数" width="120" align="center" />
        <el-table-column label="操作" width="200" align="left" fixed="right">
          <template #default="{ row }">
            <el-button
              size="small"
              :type="isUserEnabled(row) ? 'warning' : 'success'"
              plain
              :disabled="isSuperAdmin(row) || row.id === workspace.currentUserId.value"
              @click="toggleStatus(row)"
            >
              {{ isUserEnabled(row) ? '停用' : '启用' }}
            </el-button>
            <el-button size="small" @click="openEdit(row)">修改</el-button>
            <el-button size="small" type="danger" plain @click="removeUser(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </section>

    <UserEditorDrawer
      v-model="drawerVisible"
      :user="editingUser"
      :groups="workspace.groups.value"
      :site-groups="workspace.siteGroups.value"
      :current-user-id="workspace.currentUserId.value"
      :saving="workspace.saving.value"
      @save="handleSave"
      @reset-password="handleResetPassword"
      @disable="handleDisable"
      @delete="handleDelete"
    />
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { ElMessageBox } from 'element-plus';
import UserEditorDrawer, { type UserEditorUser } from './UserEditorDrawer.vue';
import { isSuperAdmin, isUserEnabled, useAdminUsersWorkspace, type AdminUserView, type AdminUserSiteGroup } from './useAdminUsersWorkspace';

const workspace = useAdminUsersWorkspace();

const drawerVisible = ref(false);
const editingUser = ref<UserEditorUser>(emptyUser());

const query = reactive({ username: '', screenName: '', email: '', siteId: undefined as number | undefined, groupId: undefined as number | undefined });

const siteOptions = computed(() => {
  const seen = new Map<number, string>();
  for (const site of workspace.siteGroups.value) {
    if (site.siteId != null) seen.set(site.siteId, site.siteName);
  }
  return Array.from(seen, ([siteId, siteName]) => ({ siteId, siteName }));
});

const groupOptions = computed(() => {
  const seen = new Map<number, string>();
  for (const site of workspace.siteGroups.value) {
    for (const g of site.groups) {
      if (g.id != null) seen.set(g.id, g.title);
    }
  }
  return Array.from(seen, ([groupId, groupName]) => ({ groupId, groupName }));
});

const filteredUsers = computed(() => {
  const kw = (v?: string) => (v ?? '').trim().toLowerCase();
  const username = kw(query.username);
  const screenName = kw(query.screenName);
  const email = kw(query.email);
  return workspace.users.value.filter((u) => {
    if (username && !(u.username ?? '').toLowerCase().includes(username)) return false;
    if (screenName && !(u.screenName ?? '').toLowerCase().includes(screenName)) return false;
    if (email && !(u.email ?? '').toLowerCase().includes(email)) return false;
    if (query.siteId != null) {
      const sites = (u.siteGroups ?? []).map((g) => g.siteId);
      if (!sites.includes(query.siteId)) return false;
    }
    if (query.groupId != null) {
      const groups = (u.siteGroups ?? []).map((g) => g.groupId);
      if (!groups.includes(query.groupId)) return false;
    }
    return true;
  });
});

function applyFilters() {
  // computed 已实时响应 query；此处重新加载数据以确保基于最新记录筛选
  void workspace.loadUsers();
}

function resetFilters() {
  query.username = '';
  query.screenName = '';
  query.email = '';
  query.siteId = undefined;
  query.groupId = undefined;
}

function emptyUser(): UserEditorUser {
  return { id: 0, username: '', screenName: '', email: '', groupId: undefined, password: '', repass: '', siteGroups: [] };
}

interface SiteGroupBlock {
  siteId?: number;
  siteName?: string;
  groups: AdminUserSiteGroup[];
}

function groupedBySite(siteGroups?: AdminUserSiteGroup[]): SiteGroupBlock[] {
  if (!siteGroups?.length) return [];
  const map = new Map<number, SiteGroupBlock>();
  for (const g of siteGroups) {
    const key = g.siteId ?? 0;
    if (!map.has(key)) {
      map.set(key, { siteId: g.siteId, siteName: g.siteName, groups: [] });
    }
    map.get(key)!.groups.push(g);
  }
  return Array.from(map.values());
}

onMounted(() => {
  void workspace.loadUsers();
  void workspace.loadGroups();
  void workspace.loadSiteGroups();
});

function openAdd() {
  editingUser.value = emptyUser();
  drawerVisible.value = true;
}

function openEdit(row: AdminUserView) {
  editingUser.value = {
    id: row.id,
    username: row.username,
    screenName: row.screenName,
    email: row.email,
    groupId: row.groupId,
    activated: row.activated,
    password: '',
    repass: '',
    siteGroups: (row.siteGroups ?? []).map((g) => ({ siteId: g.siteId, groupId: g.groupId }))
  };
  drawerVisible.value = true;
}

async function handleSave(payload: UserEditorUser) {
  const ok = await workspace.saveUser(payload);
  if (ok) {
    drawerVisible.value = false;
  }
}

async function handleResetPassword(payload: { userId: number; password: string }) {
  const ok = await workspace.resetUserPassword(payload);
  if (ok) {
    drawerVisible.value = false;
  }
}

function handleDisable(userId: number) {
  const row = workspace.users.value.find((u) => u.id === userId);
  if (!row) {
    return;
  }
  void toggleStatus(row, true);
}

async function toggleStatus(row: AdminUserView, closeDrawer = false) {
  const enabled = !isUserEnabled(row);
  const action = enabled ? '启用' : '停用';
  try {
    await ElMessageBox.confirm(`确认${action}管理员"${row.username}"？`, `${action}管理员`, { type: 'warning' });
  } catch {
    return;
  }
  const ok = await workspace.toggleUserStatus(row, enabled);
  if (ok && closeDrawer) {
    drawerVisible.value = false;
  }
}

async function handleDelete(user: UserEditorUser) {
  const ok = await workspace.deleteUser(user);
  if (ok) {
    drawerVisible.value = false;
  }
}

async function removeUser(row: AdminUserView) {
  await workspace.deleteUser(row);
}
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
.filters .el-input { width: 180px; }
.filters .el-select { width: 200px; }
.site-group-expand { display: flex; flex-direction: column; gap: 10px; padding: 8px 16px; }
.expand-header { display: flex; gap: 12px; font-weight: 600; color: var(--admin-text-muted); padding-bottom: 6px; border-bottom: 1px solid var(--admin-border-subtle); }
.site-group-block { display: flex; align-items: center; gap: 12px; }
.col-site { flex: 0 0 160px; }
.col-group { flex: 1; display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.site-name { font-weight: 600; color: var(--admin-text); }
.group-tag { margin-right: 4px; }
.empty-tip { color: var(--admin-text-muted); }
</style>
