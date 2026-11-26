<template>
  <div class="profile-security-panel">
    <el-drawer v-model="profileVisibleProxy" title="个人资料" class="profile-drawer" size="min(480px, calc(100vw - 24px))">
      <el-form label-position="top" class="work-form admin-drawer-form" @submit.prevent>
        <el-form-item label="账号">
          <el-input v-model="profileForm.username" disabled />
        </el-form-item>
        <el-form-item label="姓名">
          <el-input v-model="profileForm.screenName" />
        </el-form-item>
        <el-form-item label="手机号">
          <el-input v-model="profileForm.mobile" placeholder="旧用户表未配置手机号时可留空" />
        </el-form-item>
        <el-form-item label="邮箱">
          <el-input v-model="profileForm.email" />
        </el-form-item>
        <el-form-item label="登录信息">
          <div class="profile-login-meta">
            <span>登录次数：{{ profileForm.loginTimes ?? 0 }}</span>
            <span>最后登录 IP：{{ profileForm.lastLoginIp || '暂无' }}</span>
          </div>
        </el-form-item>
        <div class="form-footer">
          <el-button @click="profileVisibleProxy = false">取消</el-button>
          <el-button type="primary" :loading="saving" @click="$emit('save-profile', { ...profileForm })">保存资料</el-button>
        </div>
      </el-form>
    </el-drawer>

    <el-drawer v-model="passwordVisibleProxy" title="修改密码" class="password-drawer" size="min(480px, calc(100vw - 24px))">
      <el-form label-position="top" class="work-form admin-drawer-form" @submit.prevent>
        <el-alert
          v-if="passwordError"
          :title="passwordError"
          type="error"
          show-icon
          :closable="false"
        />
        <el-form-item label="原密码">
          <el-input v-model="passwordForm.oldPassword" type="password" show-password />
        </el-form-item>
        <el-form-item label="新密码">
          <el-input v-model="passwordForm.password" type="password" show-password />
        </el-form-item>
        <el-form-item label="确认密码">
          <el-input v-model="passwordForm.repass" type="password" show-password />
        </el-form-item>
        <div class="form-footer">
          <el-button @click="passwordVisibleProxy = false">取消</el-button>
          <el-button type="primary" :loading="saving" @click="$emit('save-password', { ...passwordForm })">修改密码</el-button>
        </div>
      </el-form>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, watch } from 'vue';

export interface ProfileForm {
  id: number;
  username: string;
  screenName: string;
  email: string;
  mobile?: string;
  groupName?: string;
  groupId?: number;
  lastLoginIp?: string;
  loginTimes?: number;
}

export interface PasswordForm {
  oldPassword: string;
  password: string;
  repass: string;
}

const props = defineProps<{
  profileVisible: boolean;
  passwordVisible: boolean;
  profile: ProfileForm;
  password: PasswordForm;
  passwordError?: string;
  saving?: boolean;
}>();

const emit = defineEmits<{
  'update:profileVisible': [value: boolean];
  'update:passwordVisible': [value: boolean];
  'save-profile': [value: ProfileForm];
  'save-password': [value: PasswordForm];
}>();

const profileForm = reactive<ProfileForm>({ id: 0, username: '', screenName: '', email: '', mobile: '', lastLoginIp: '', loginTimes: 0 });
const passwordForm = reactive<PasswordForm>({ oldPassword: '', password: '', repass: '' });

const profileVisibleProxy = computed({
  get: () => props.profileVisible,
  set: (value: boolean) => emit('update:profileVisible', value)
});

const passwordVisibleProxy = computed({
  get: () => props.passwordVisible,
  set: (value: boolean) => emit('update:passwordVisible', value)
});

watch(
  () => props.profile,
  (profile) => Object.assign(profileForm, profile),
  { immediate: true, deep: true }
);

watch(
  () => props.password,
  (password) => Object.assign(passwordForm, password),
  { immediate: true, deep: true }
);
</script>

<style scoped>
.form-footer { display: flex; align-items: center; justify-content: flex-end; gap: 12px; min-width: 0; }
.profile-login-meta { display: grid; gap: 6px; color: var(--admin-text-muted); }
</style>
