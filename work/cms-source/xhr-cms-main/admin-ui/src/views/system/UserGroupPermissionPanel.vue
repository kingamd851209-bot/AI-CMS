<template>
  <section class="work-card">
    <div class="work-card__header">
      <h2>管理员组</h2>
      <span>{{ groups.length }} 个</span>
    </div>
    <el-alert
      title="管理员组权限正在迁移到默认角色 + 可管理范围；下方 groupSetting / otherSetting 仅作为兼容字段继续维护。"
      type="info"
      show-icon
      :closable="false"
      class="migration-alert"
    />
    <div class="admin-table-shell">
      <el-table element-loading-text="正在加载数据…" v-loading="loading" :data="groups" stripe>
        <el-table-column prop="title" label="名称" />
        <el-table-column label="父级" min-width="110">
          <template #default="{ row }">{{ parentGroupName(row.parentId) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.enabled === false ? 'danger' : 'success'">{{ row.enabled === false ? '停用' : '启用' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="230">
          <template #default="{ row }">
            <el-button size="small" @click="openGroup(row)">编辑</el-button>
            <template v-if="isProtectedAdminGroup(row)">
              <el-tooltip content="顶级管理员组不可切换状态" placement="top">
                <span class="protected-group-action">
                  <el-button size="small" plain disabled>{{ row.enabled === false ? '启用' : '停用' }}</el-button>
                </span>
              </el-tooltip>
              <span class="protected-group-hint">顶级管理员组不可切换状态</span>
            </template>
            <el-button v-else size="small" plain @click="$emit('switch', row)">{{ row.enabled === false ? '启用' : '停用' }}</el-button>
            <el-button size="small" type="danger" plain @click="$emit('delete', row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-dialog v-model="dialogVisible" :title="groupForm.id ? '编辑管理员组' : '新建管理员组'" width="min(640px, calc(100vw - 24px))">
      <el-form label-position="top" class="work-form admin-drawer-form" @submit.prevent>
        <div class="form-grid">
          <el-form-item label="名称">
            <el-input v-model="groupForm.title" />
          </el-form-item>
          <el-form-item label="父级">
            <el-select v-model="groupForm.parentId">
              <el-option :value="0" label="根级" />
              <el-option v-for="group in groups" :key="group.id" :disabled="group.id === groupForm.id" :label="group.title" :value="group.id" />
            </el-select>
          </el-form-item>
        </div>
        <el-form-item label="说明">
          <el-input v-model="groupForm.note" />
        </el-form-item>
        <el-form-item label="栏目权限（兼容字段）">
          <div class="permission-panel">
            <div class="permission-panel__scope">
              <strong>当前站点：{{ currentSiteName || '未选择站点' }}</strong>
              <span>当前站点权限编辑会保留其他站点/树外兼容字段。</span>
            </div>
            <el-tree
              ref="permissionTreeRef"
              :data="permissionTreeData"
              :props="{ label: 'label', children: 'children' }"
              node-key="id"
              show-checkbox
              check-strictly
              default-expand-all
              empty-text="当前站点暂无栏目，可先保存基础组信息"
            />
            <el-alert
              title="按当前站点栏目维护兼容字段，切换站点后请重新确认范围。保存仍写回旧 groupSetting 字段，旧后台权限判断会继续生效；新入口以默认角色 + 可管理范围为准。"
              type="info"
              show-icon
              :closable="false"
            />
          </div>
        </el-form-item>
        <el-form-item label="菜单权限（兼容字段）">
          <el-checkbox-group v-if="menuPermissions.length" v-model="selectedMenuPermissionKeys" class="permission-checks">
            <el-checkbox v-for="permission in menuPermissions" :key="permission.key" :value="permission.key">
              {{ permission.label }}
            </el-checkbox>
          </el-checkbox-group>
          <el-alert v-else title="后端未返回菜单权限目录，当前不能新增菜单权限。" type="warning" show-icon :closable="false" />
        </el-form-item>
        <el-form-item label="全局权限（兼容字段）">
          <el-checkbox-group v-if="globalPermissions.length" v-model="selectedGlobalPermissionKeys" class="permission-checks">
            <el-checkbox v-for="permission in globalPermissions" :key="permission.key" :value="permission.key">
              {{ permission.label }}
            </el-checkbox>
          </el-checkbox-group>
          <el-alert v-else title="后端未返回全局权限目录，当前不能新增全局权限。" type="warning" show-icon :closable="false" />
        </el-form-item>
        <el-form-item v-if="unknownPermissions.length" label="未知历史权限（兼容字段）">
          <div class="unknown-permissions">
            <el-tag v-for="permission in unknownPermissions" :key="permission.key" type="warning" effect="plain">
              {{ permission.key }}<span v-if="permission.otherSetting">：{{ permission.otherSetting }}</span>
            </el-tag>
          </div>
        </el-form-item>
        <el-form-item label="站点权限（兼容 groupSetting）">
          <el-input v-model="groupForm.groupSetting" readonly />
        </el-form-item>
        <el-form-item label="按钮权限（兼容 otherSetting）">
          <el-input v-model="groupForm.otherSetting" type="textarea" :rows="3" />
        </el-form-item>
        <section v-if="permissionPreview" class="permission-preview">
          <h3>权限影响预览</h3>
          <p>新增权限：{{ permissionPreview.addedLabels.length ? permissionPreview.addedLabels.join('、') : '无' }}</p>
          <p>移除权限：{{ permissionPreview.removedLabels.length ? permissionPreview.removedLabels.join('、') : '无' }}</p>
          <p>保留未知历史权限：{{ permissionPreview.unknownKeys.length ? permissionPreview.unknownKeys.join('、') : '无' }}</p>
          <p>影响管理员：{{ permissionPreview.affectedUserCount }} 人</p>
        </section>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveCurrentGroup">保存角色</el-button>
        <el-button v-if="permissionPreview" type="primary" :loading="saving" @click="confirmSaveGroup">确认保存</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<script setup lang="ts">
import { computed, nextTick, reactive, ref, watch } from 'vue';

export interface GroupView {
  id: number;
  title: string;
  parentId?: number;
  note?: string;
  enabled?: boolean;
  groupSetting?: string;
  otherSetting?: string;
  userCount?: number;
  permissionItems?: PermissionItem[];
  unknownPermissionItems?: PermissionItem[];
}

export interface PermissionNode {
  id: number;
  label: string;
  children: PermissionNode[];
}

interface PermissionItem {
  key: string;
  label?: string;
  scope?: string;
  otherSetting?: string;
  known?: boolean;
}

interface PermissionOption {
  key: string;
  label: string;
}

interface PermissionPreview {
  addedLabels: string[];
  removedLabels: string[];
  unknownKeys: string[];
  affectedUserCount: number;
}

const props = defineProps<{
  groups: GroupView[];
  loading?: boolean;
  saving?: boolean;
  permissionTreeData: PermissionNode[];
  menuPermissionOptions?: PermissionOption[];
  globalPermissionOptions?: PermissionOption[];
  currentSiteName?: string;
}>();

const emit = defineEmits<{
  save: [group: GroupView];
  switch: [group: GroupView];
  delete: [group: GroupView];
}>();

defineExpose({ openGroup });

const dialogVisible = ref(false);
const permissionTreeSynced = ref(false);
const selectedMenuPermissionKeys = ref<string[]>([]);
const selectedGlobalPermissionKeys = ref<string[]>([]);
const pendingGroup = ref<GroupView | null>(null);
const permissionPreview = ref<PermissionPreview | null>(null);
const permissionTreeRef = ref<{
  getCheckedKeys: () => Array<string | number>;
  setCheckedKeys: (keys: Array<string | number>) => void;
} | null>(null);
const emptyGroupForm = () => ({ id: 0, title: '', parentId: 0, note: '', enabled: true, groupSetting: '0', otherSetting: '', userCount: 0, permissionItems: [], unknownPermissionItems: [] });
const groupForm = reactive<GroupView>(emptyGroupForm());
const menuPermissions = computed(() => props.menuPermissionOptions ?? []);
const globalPermissions = computed(() => props.globalPermissionOptions ?? []);
const knownNonSitePermissionKeys = computed(() => new Set([...menuPermissions.value, ...globalPermissions.value].map((permission) => permission.key)));
const unknownPermissions = computed(() => {
  const byKey = otherSettingByKey(groupForm.groupSetting, groupForm.otherSetting);
  const backendUnknown = groupForm.unknownPermissionItems ?? [];
  const keys = new Set([
    ...stringSettingKeys(groupForm.groupSetting).filter((key) => isUnknownPermissionKey(key)),
    ...backendUnknown.map((item) => item.key)
  ]);

  return Array.from(keys).map((key) => ({
    key,
    otherSetting: byKey.get(key) ?? backendUnknown.find((item) => item.key === key)?.otherSetting ?? ''
  }));
});

watch(
  () => props.permissionTreeData,
  async (items) => {
    if (!dialogVisible.value || permissionTreeSynced.value || !items.length) {
      return;
    }
    await nextTick();
    permissionTreeRef.value?.setCheckedKeys(settingToKeys(groupForm.groupSetting));
    permissionTreeSynced.value = true;
  },
  { deep: true }
);

async function openGroup(row?: GroupView) {
  Object.assign(groupForm, row ? { ...emptyGroupForm(), ...row } : emptyGroupForm());
  const keys = new Set(stringSettingKeys(groupForm.groupSetting));
  selectedMenuPermissionKeys.value = menuPermissions.value.map((permission) => permission.key).filter((key) => keys.has(key));
  selectedGlobalPermissionKeys.value = globalPermissions.value.map((permission) => permission.key).filter((key) => keys.has(key));
  pendingGroup.value = null;
  permissionPreview.value = null;
  permissionTreeSynced.value = false;
  dialogVisible.value = true;
  await nextTick();
  if (props.permissionTreeData.length) {
    permissionTreeRef.value?.setCheckedKeys(settingToKeys(groupForm.groupSetting));
    permissionTreeSynced.value = true;
  }
}

function saveCurrentGroup() {
  const nextGroup: GroupView = { ...groupForm, groupSetting: checkedPermissionKeys() };
  pendingGroup.value = nextGroup;
  permissionPreview.value = buildPermissionPreview(nextGroup);
}

function confirmSaveGroup() {
  if (!pendingGroup.value) {
    saveCurrentGroup();
    return;
  }
  groupForm.groupSetting = pendingGroup.value.groupSetting;
  emit('save', { ...pendingGroup.value });
}

function parentGroupName(parentId?: number) {
  if (!parentId) {
    return '根级';
  }
  return props.groups.find((group) => group.id === parentId)?.title ?? `#${parentId}`;
}

function isProtectedAdminGroup(row: GroupView) {
  return row.id === 1 || row.id === 2;
}

function settingToKeys(value?: string) {
  return stringSettingKeys(value)
    .map((item) => Number(item))
    .filter((item) => Number.isFinite(item) && item > 0);
}

function checkedPermissionKeys() {
  const existingKeys = stringSettingKeys(groupForm.groupSetting);
  if (!props.permissionTreeData.length || !permissionTreeRef.value) {
    return uniqueKeys([...existingKeys, ...selectedMenuPermissionKeys.value, ...selectedGlobalPermissionKeys.value]).join(',') || '0';
  }
  const currentTreeKeys = new Set(flattenPermissionIds(props.permissionTreeData).map(String));
  const keys = permissionTreeRef.value.getCheckedKeys() ?? [];
  const normalized = keys
    .map((item) => String(item))
    .filter((item) => currentTreeKeys.has(item));
  const selectedCurrentTreeKeys = new Set(normalized);
  const existingSelectedKeys = new Set(existingKeys);
  const merged = [
    ...existingKeys.filter((item) => !currentTreeKeys.has(item) || selectedCurrentTreeKeys.has(item)),
    ...Array.from(selectedCurrentTreeKeys).filter((item) => !existingSelectedKeys.has(item)),
    ...selectedMenuPermissionKeys.value,
    ...selectedGlobalPermissionKeys.value
  ];
  return uniqueKeys(merged).join(',') || '0';
}

function flattenPermissionIds(items: PermissionNode[]): number[] {
  return items.flatMap((item) => [item.id, ...flattenPermissionIds(item.children ?? [])]);
}

function stringSettingKeys(value?: string) {
  return String(value || '')
    .split(/[\n,]/)
    .map((item) => item.trim())
    .filter((item) => item && item !== '0');
}

function uniqueKeys(keys: string[]) {
  return Array.from(new Set(keys.filter(Boolean)));
}

function isUnknownPermissionKey(key: string) {
  return !knownNonSitePermissionKeys.value.has(key) && !positiveInteger(key);
}

function positiveInteger(key: string) {
  return Number.isInteger(Number(key)) && Number(key) > 0;
}

function otherSettingByKey(groupSetting?: string, otherSetting?: string) {
  const keys = stringSettingKeys(groupSetting);
  const segments = String(otherSetting || '').split(';');
  return new Map(keys.map((key, index) => [key, segments[index] ?? '']));
}

function buildPermissionPreview(nextGroup: GroupView): PermissionPreview {
  const previousKeys = stringSettingKeys(groupForm.groupSetting);
  const nextKeys = stringSettingKeys(nextGroup.groupSetting);
  const previousSet = new Set(previousKeys);
  const nextSet = new Set(nextKeys);

  return {
    addedLabels: nextKeys.filter((key) => !previousSet.has(key) && !isUnknownPermissionKey(key)).map(permissionLabel),
    removedLabels: previousKeys.filter((key) => !nextSet.has(key) && !isUnknownPermissionKey(key)).map(permissionLabel),
    unknownKeys: nextKeys.filter(isUnknownPermissionKey),
    affectedUserCount: groupForm.userCount ?? 0
  };
}

function permissionLabel(key: string) {
  const option = [...menuPermissions.value, ...globalPermissions.value].find((item) => item.key === key);
  if (option) {
    return option.label;
  }
  return findPermissionNodeLabel(props.permissionTreeData, Number(key)) ?? `栏目 #${key}`;
}

function findPermissionNodeLabel(items: PermissionNode[], id: number): string | undefined {
  for (const item of items) {
    if (item.id === id) {
      return item.label;
    }
    const childLabel = findPermissionNodeLabel(item.children ?? [], id);
    if (childLabel) {
      return childLabel;
    }
  }
  return undefined;
}
</script>

<style scoped>
.work-card { padding: var(--admin-card-padding); border: var(--admin-card-border); border-radius: var(--admin-card-radius); background: var(--admin-bg-surface); box-shadow: var(--admin-shadow-md); min-width: 0; }
.work-card__header { display: flex; align-items: center; justify-content: space-between; gap: 12px; min-width: 0; margin-bottom: 18px; }
.work-card__header h2 { margin: 0; }
.migration-alert { margin-bottom: 16px; }
.form-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px; }
.permission-panel { display: grid; gap: 12px; width: 100%; padding: 14px; border: var(--admin-card-border); border-radius: var(--admin-card-radius); background: #fafafa; }
.permission-panel__scope { display: grid; gap: 4px; color: var(--admin-text-muted); }
.permission-panel__scope strong { color: var(--admin-text-strong); }
.permission-checks, .unknown-permissions { display: flex; flex-wrap: wrap; gap: 8px 14px; }
.permission-preview { display: grid; gap: 6px; padding: 12px; border: 1px solid var(--admin-border-subtle); border-radius: 8px; background: #f8fafc; }
.permission-preview h3, .permission-preview p { margin: 0; }
@media (max-width: 1024px) { .form-grid { grid-template-columns: 1fr; } }
</style>
