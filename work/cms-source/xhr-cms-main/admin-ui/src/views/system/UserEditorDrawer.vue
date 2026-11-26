<template>
  <el-drawer v-model="visible" :title="form.id ? '编辑用户' : '新建用户'" class="user-editor-drawer" size="min(640px, calc(100vw - 24px))">
    <el-form ref="formRef" :model="form" :rules="rules" label-position="top" class="work-form admin-drawer-form" @submit.prevent>
      <el-form-item label="账号" prop="username">
        <el-input v-model="form.username" name="cms-user-account" autocomplete="off" :disabled="form.id > 0" />
      </el-form-item>
      <el-form-item label="姓名" prop="screenName">
        <el-input v-model="form.screenName" />
      </el-form-item>
      <el-form-item label="邮箱" prop="email">
        <el-input v-model="form.email" type="email" name="cms-user-email" autocomplete="off" />
      </el-form-item>
      <el-form-item label="归属类型">
        <el-radio-group v-model="scope">
          <el-radio-button value="site">站点管理员</el-radio-button>
          <el-radio-button value="platform">平台管理员</el-radio-button>
        </el-radio-group>
      </el-form-item>
      <el-form-item v-if="scope === 'site'" label="站点-管理员组" prop="siteGroups">
        <div class="site-group-editor">
          <div v-for="(row, index) in siteGroupRows" :key="row.key" class="site-group-row">
            <el-select
              v-model="row.siteId"
              placeholder="选择站点"
              filterable
              style="width: 180px"
              @change="onSiteRowChange(row)"
            >
              <el-option
                v-for="site in siteSiteGroups"
                :key="site.siteId"
                :disabled="siteGroupRows.some((item) => item !== row && item.siteId === site.siteId)"
                :label="site.siteName"
                :value="site.siteId"
              />
            </el-select>
            <el-select v-model="row.groupIds" placeholder="选择该站点管理员组" multiple filterable clearable style="flex: 1">
              <el-option v-for="group in groupsFor(row.siteId)" :key="group.id" :label="group.title" :value="group.id" />
            </el-select>
            <el-button text type="danger" :disabled="siteGroupRows.length === 1" @click="removeSiteRow(index)">移除</el-button>
          </div>
          <el-button text type="primary" :disabled="siteGroupRows.some((r) => !r.siteId) || siteGroupRows.length >= siteSiteGroups.length" @click="addSiteRow">+ 添加站点</el-button>
          <div class="site-group-hint">为每个站点指定该站点的管理员组；为空表示不管理该站点。</div>
        </div>
      </el-form-item>
      <el-form-item v-else label="平台组" prop="siteGroups">
        <div class="site-group-editor">
          <el-select v-model="platformGroupId" placeholder="选择平台组" filterable style="width: 100%">
            <el-option v-for="group in platformGroups" :key="group.id" :label="group.title" :value="group.id" />
          </el-select>
          <div class="site-group-hint">平台管理员可查看/操作所有站点；栏目权限仍由该平台组配置决定。</div>
        </div>
      </el-form-item>
      <template v-if="!form.id">
        <el-form-item label="登录密码" prop="password">
          <el-input v-model="form.password" type="password" autocomplete="new-password" show-password />
        </el-form-item>
        <el-form-item label="确认密码" prop="repass">
          <el-input v-model="form.repass" type="password" autocomplete="new-password" show-password />
        </el-form-item>
      </template>
      <template v-if="form.id">
        <el-divider />
        <el-alert
          v-if="isCurrentUser"
          title="当前登录管理员不能禁用自己"
          type="warning"
          show-icon
          :closable="false"
        />
        <el-form-item label="新密码">
          <el-input v-model="resetPassword" type="password" autocomplete="new-password" show-password />
          <div v-if="resetPasswordError" class="field-error">{{ resetPasswordError }}</div>
        </el-form-item>
        <div class="danger-actions">
          <el-button type="warning" plain :loading="saving" @click="emitResetPassword">确认重置密码</el-button>
          <el-button type="danger" plain :disabled="isCurrentUser" :loading="saving" @click="$emit('disable', form.id)">禁用账号</el-button>
          <el-button type="danger" :loading="saving" @click="$emit('delete', form)">删除账号</el-button>
        </div>
      </template>
      <div class="form-footer">
        <el-button @click="visible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="handleSave">保存用户</el-button>
      </div>
    </el-form>
  </el-drawer>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue';
import { type FormInstance, type FormRules } from 'element-plus';
import type { SiteGroupOption } from './useAdminUsersWorkspace';
import { EMAIL_MESSAGE, PASSWORD_STRENGTH_MESSAGE, isEmail, isStrongPassword } from '@/utils/adminUserValidation';

export interface UserSiteGroupRow {
  key: number;
  siteId?: number;
  groupIds: number[];
}

export interface UserEditorUser {
  id: number;
  username: string;
  email: string;
  screenName: string;
  groupId?: number;
  activated?: number;
  password?: string;
  repass?: string;
  siteGroups?: Array<{ siteId?: number; groupId?: number }>;
}

export interface UserEditorGroup {
  id: number;
  title: string;
}

const props = defineProps<{
  modelValue: boolean;
  user: UserEditorUser;
  groups: UserEditorGroup[];
  siteGroups: SiteGroupOption[];
  currentUserId?: number;
  saving?: boolean;
}>();

const emit = defineEmits<{
  'update:modelValue': [value: boolean];
  save: [value: UserEditorUser];
  'reset-password': [payload: { userId: number; password: string }];
  disable: [userId: number];
  delete: [user: UserEditorUser];
}>();

let keySeq = 0;

/** 平台组伪站点（site_id=0） */
const PLATFORM_SITE_ID = 0;

type Scope = 'site' | 'platform';

const formRef = ref<FormInstance>();
const form = reactive<UserEditorUser>({
  id: 0,
  username: '',
  screenName: '',
  email: '',
  groupId: undefined,
  password: '',
  repass: ''
});
const scope = ref<Scope>('site');
const platformGroupId = ref<number>();
const resetPassword = ref('');
const resetPasswordError = ref('');

const visible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value)
});

const isCurrentUser = computed(() => Boolean(form.id && props.currentUserId && form.id === props.currentUserId));

const siteGroupRows = ref<UserSiteGroupRow[]>([]);

/** 站点管理员分支可选的站点（排除平台伪站点 siteId=0） */
const siteSiteGroups = computed(() => props.siteGroups.filter((s) => s.siteId !== PLATFORM_SITE_ID));

/** 平台管理员分支可选的平台组 */
const platformGroups = computed(() => props.siteGroups.find((s) => s.siteId === PLATFORM_SITE_ID)?.groups ?? []);

const rules = computed<FormRules>(() => {
  const base: FormRules = {
    username: [{ required: true, message: '账号不能为空', trigger: 'blur' }],
    screenName: [{ required: true, message: '姓名不能为空', trigger: 'blur' }],
    email: [
      { required: true, message: '邮箱不能为空', trigger: 'blur' },
      {
        validator: (_rule, value: string, callback) => {
          if (value && !isEmail(value)) {
            callback(new Error(EMAIL_MESSAGE));
          } else {
            callback();
          }
        },
        trigger: 'blur'
      }
    ],
    siteGroups: [{ validator: validateSiteGroups, trigger: 'change' }]
  };
  if (!form.id) {
    base.password = [
      { required: true, message: '新建账号必须设置密码', trigger: 'blur' },
      {
        validator: (_rule, value: string, callback) => {
          if (value && !isStrongPassword(value)) {
            callback(new Error(PASSWORD_STRENGTH_MESSAGE));
          } else {
            callback();
          }
        },
        trigger: 'blur'
      }
    ];
    base.repass = [
      { required: true, message: '请再次输入密码', trigger: 'blur' },
      {
        validator: (_rule, value: string, callback) => {
          if (value !== form.password) {
            callback(new Error('两次输入的密码不一致'));
          } else {
            callback();
          }
        },
        trigger: 'blur'
      }
    ];
  }
  return base;
});

function validateSiteGroups(_rule: unknown, _value: unknown, callback: (error?: Error) => void) {
  if (scope.value === 'platform') {
    if (platformGroupId.value == null) {
      callback(new Error('请选择平台组'));
      return;
    }
    callback();
    return;
  }
  const usable = siteGroupRows.value.filter((row) => row.siteId != null);
  if (!usable.length) {
    callback(new Error('请至少选择一个站点及其管理员组'));
    return;
  }
  if (usable.some((row) => !row.groupIds.length)) {
    callback(new Error('请为所选站点指定管理员组'));
    return;
  }
  callback();
}

function groupsFor(siteId?: number): UserEditorGroup[] {
  if (siteId == null) {
    return [];
  }
  return props.siteGroups.find((s) => s.siteId === siteId)?.groups ?? [];
}

function addSiteRow() {
  const used = new Set((siteGroupRows.value as UserSiteGroupRow[]).map((r) => r.siteId));
  const firstUnused = siteSiteGroups.value.find((s) => !used.has(s.siteId));
  if (!firstUnused) {
    return;
  }
  (siteGroupRows.value as UserSiteGroupRow[]).push({ key: ++keySeq, siteId: firstUnused.siteId, groupIds: [] });
}

function removeSiteRow(index: number) {
  (siteGroupRows.value as UserSiteGroupRow[]).splice(index, 1);
}

function onSiteRowChange(row: UserSiteGroupRow) {
  row.groupIds = [];
}

function toSiteGroupRows(siteGroups?: Array<{ siteId?: number; groupId?: number }>): UserSiteGroupRow[] {
  const rows = new Map<number, UserSiteGroupRow>();
  for (const ref of siteGroups ?? []) {
    if (ref.siteId == null || !siteSiteGroups.value.some((site) => site.siteId === ref.siteId)) continue;
    let row = rows.get(ref.siteId);
    if (!row) {
      row = { key: ++keySeq, siteId: ref.siteId, groupIds: [] };
      rows.set(ref.siteId, row);
    }
    if (ref.groupId != null && !row.groupIds.includes(ref.groupId)) row.groupIds.push(ref.groupId);
  }
  return [...rows.values()];
}

watch(
  () => [props.user, props.siteGroups] as const,
  ([user]) => {
    Object.assign(form, user);
    form.repass = '';
    const incoming = user.siteGroups ?? [];
    const platformRow = incoming.find((r) => r.siteId === PLATFORM_SITE_ID);
    scope.value = platformRow ? 'platform' : 'site';
    platformGroupId.value = platformRow?.groupId;
    siteGroupRows.value = toSiteGroupRows(incoming);
    resetPassword.value = '';
    resetPasswordError.value = '';
    formRef.value?.clearValidate();
  },
  { immediate: true, deep: true }
);

async function handleSave() {
  const formEl = formRef.value;
  if (!formEl) {
    return;
  }
  try {
    await formEl.validate();
  } catch {
    return;
  }
  const payload = { ...form } as UserEditorUser;
  payload.siteGroups = scope.value === 'platform'
    ? [{ siteId: PLATFORM_SITE_ID, groupId: platformGroupId.value }]
    : (siteGroupRows.value as UserSiteGroupRow[])
        .filter((r) => siteSiteGroups.value.some((site) => site.siteId === r.siteId))
        .flatMap((r) => r.groupIds.map((groupId) => ({ siteId: r.siteId, groupId })));
  emit('save', payload);
}

function emitResetPassword() {
  const value = resetPassword.value;
  if (!value) {
    resetPasswordError.value = '请填写新密码';
    return;
  }
  if (!isStrongPassword(value)) {
    resetPasswordError.value = PASSWORD_STRENGTH_MESSAGE;
    return;
  }
  resetPasswordError.value = '';
  emit('reset-password', { userId: form.id, password: value });
}
</script>

<style scoped>
.form-footer, .danger-actions { display: flex; align-items: center; justify-content: flex-end; gap: 12px; min-width: 0; }
.danger-actions { flex-wrap: wrap; margin-bottom: 18px; }
.site-group-editor { display: grid; gap: 8px; width: 100%; }
.site-group-row { display: flex; align-items: center; gap: 8px; width: 100%; }
.site-group-hint { font-size: 12px; color: var(--admin-text-muted); line-height: 1.4; }
.field-error { width: 100%; font-size: 12px; color: var(--el-color-danger); line-height: 1.4; }
</style>
