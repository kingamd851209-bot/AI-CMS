<template>
  <main class="login-view">
    <section class="login-view__hero" :style="heroStyle">
      <div class="login-view__media" aria-hidden="true">
        <img class="login-view__poster" :src="backgroundPosterUrl" alt="" />
        <video
          v-if="branding.loginBackgroundType === 'video' && !backgroundVideoFailed"
          class="login-view__video"
          autoplay
          muted
          loop
          playsinline
          preload="metadata"
          :poster="backgroundPosterUrl"
          data-testid="login-background-video"
          @error="backgroundVideoFailed = true"
        >
          <source :src="branding.loginBackgroundUrl" :type="mediaSourceType(branding.loginBackgroundUrl)" />
        </video>
      </div>
      <div class="login-view__hero-content">
        <img v-if="branding.systemLogoUrl" class="login-view__logo" :src="branding.systemLogoUrl" alt="" />
        <h1>{{ branding.loginTitle }}</h1>
        <p class="login-view__eyebrow">{{ branding.loginEyebrow }}</p>
        <p>{{ branding.loginSubtitle }}</p>
        <ul class="login-view__highlights">
          <li v-for="highlight in loginHighlights" :key="highlight">{{ highlight }}</li>
        </ul>
      </div>
    </section>

    <section v-if="route.query.sessionUnavailable==='1' && workbenchReturn" class="login-card" aria-label="恢复工作台">
      <h2>暂时无法连接工作台</h2><p>恢复连接后，可以继续当前站点的任务。</p>
      <el-button type="primary" :loading="retrying" @click="retryWorkbench">重新连接工作台</el-button>
    </section>
    <section v-else class="login-card" aria-label="登录表单">
      <div class="login-card__header">
        <p class="login-card__eyebrow">账号登录</p>
        <h2>{{ branding.loginFormTitle }}</h2>
        <p>{{ workbenchReturn ? '登录后返回 AI 建站工作台，继续审核当前任务。' : branding.loginFormSubtitle }}</p>
      </div>

      <p v-if="submitError" class="login-submit-error" role="alert">{{ submitError }}</p>

      <form class="login-form" data-testid="login-form" @submit.prevent="submitLogin">
        <label class="login-field">
          <span>登录名</span>
          <input
            v-model.trim="form.username"
            data-testid="username-input" :aria-invalid="!!fieldErrors.username" :aria-describedby="fieldErrors.username ? 'username-error' : undefined"
            type="text"
            autocomplete="username"
            placeholder="请输入登录名"
          />
          <small id="username-error" v-if="fieldErrors.username" class="login-field__error">{{ fieldErrors.username }}</small>
        </label>

        <label class="login-field">
          <span>登录密码</span>
          <input
            v-model="form.password"
            data-testid="password-input" :aria-invalid="!!fieldErrors.password" :aria-describedby="fieldErrors.password ? 'password-error' : undefined"
            type="password"
            autocomplete="current-password"
            placeholder="请输入登录密码"
          />
          <small id="password-error" v-if="fieldErrors.password" class="login-field__error">{{ fieldErrors.password }}</small>
        </label>

        <div class="login-field">
          <div class="login-field__label-row">
            <label for="cms-login-captcha">验证码</label>
            <button
              class="login-link-button"
              data-testid="reload-captcha"
              type="button"
              :disabled="captchaLoading"
              @click="reloadCaptcha"
            >
              {{ captchaLoading ? '刷新中...' : '换一张' }}
            </button>
          </div>
          <div class="login-captcha-row">
            <input
              v-model.trim="form.imageCodeValue"
              id="cms-login-captcha" data-testid="captcha-input" :aria-invalid="!!fieldErrors.imageCodeValue" :aria-describedby="fieldErrors.imageCodeValue ? 'captcha-error' : undefined"
              type="text"
              inputmode="text"
              autocomplete="one-time-code"
              placeholder="请输入验证码"
            />
            <img
              v-if="captchaImage"
              :src="captchaImage"
              alt="图形验证码"
              class="login-captcha-image"
              data-testid="captcha-image"
            />
            <div v-else class="login-captcha-placeholder">验证码加载中</div>
          </div>
          <small id="captcha-error" v-if="fieldErrors.imageCodeValue" class="login-field__error">{{ fieldErrors.imageCodeValue }}</small>
          <small v-if="captchaError" class="login-field__error">{{ captchaError }}</small>
        </div>

        <label class="login-checkbox">
          <input v-model="rememberMe" data-testid="remember-me-input" type="checkbox" />
          <span>记住我</span>
        </label>

        <button class="login-submit" data-testid="submit-button" type="submit" :disabled="submitting || captchaLoading">
          {{ submitting ? '登录中...' : '登录' }}
        </button>
      </form>
    </section>
  </main>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { httpClient, resolveAdminApiBaseUrl } from '@/api/http';
import { mediaSourceType, useBrandingStore } from '@/stores/branding';

interface LegacyResult<T> {
  success: boolean;
  code: number;
  msg?: string;
  data: T | null;
}

interface CaptchaPayload {
  codeKey: string;
  base64Image: string;
}

interface LoginPayload {
  username: string;
  password: string;
  imageCodeKey: string;
  imageCodeValue: string;
  reMemberMe: string;
}

interface PublicKeyPayload {
  publicKey: string;
}

interface JSEncryptConstructor {
  new (): {
    setPublicKey(publicKey: string): void;
    encrypt(value: string): string | false;
  };
}

declare global {
  interface Window {
    __CMS_RSA_PUBLIC_KEY__?: string;
    JSEncrypt?: JSEncryptConstructor;
  }
}

const router = useRouter();
const route = useRoute();
const retrying = ref(false);
const workbenchReturn = computed(()=>{
  const next=route.query.returnTo;
  return typeof next==='string' && next.split('?')[0]==='/admin/v2/site-build' ? next : '';
});
async function retryWorkbench() {
  if (!workbenchReturn.value || retrying.value) return;
  retrying.value=true;
  try { await router.replace(workbenchReturn.value); }
  finally {retrying.value=false;}
}
const brandingStore = useBrandingStore();
const branding = brandingStore.settings;

const form = reactive({
  username: '',
  password: '',
  imageCodeKey: '',
  imageCodeValue: ''
});

const fieldErrors = reactive({
  username: '',
  password: '',
  imageCodeValue: ''
});

const rememberMe = ref(false);
const captchaImage = ref('');
const captchaLoading = ref(false);
const captchaError = ref('');
const submitError = ref('');
const submitting = ref(false);
const backgroundVideoFailed = ref(false);
let encryptionScriptPromise: Promise<void> | null = null;

onMounted(() => {
  void loadCaptcha();
  void loadBranding();
});

const backgroundPosterUrl = computed(() => {
  if (branding.loginBackgroundType === 'image') {
    return branding.loginBackgroundUrl || branding.loginBackgroundPosterUrl;
  }
  return branding.loginBackgroundPosterUrl || branding.loginBackgroundUrl;
});

const loginHighlights = computed(() => branding.loginHighlights
  .split(/\n|,/)
  .map((item) => item.trim())
  .filter(Boolean));

const heroStyle = computed(() => ({
  '--login-background-fit': branding.loginBackgroundFit || 'cover',
  '--login-background-position': branding.loginBackgroundPosition || 'center center',
  '--login-overlay-opacity': String(branding.loginOverlayOpacity ?? 0.48)
}));

async function loadBranding() {
  await brandingStore.loadBranding();
  backgroundVideoFailed.value = false;
}

function clearFieldErrors() {
  fieldErrors.username = '';
  fieldErrors.password = '';
  fieldErrors.imageCodeValue = '';
}

function validateForm(): boolean {
  clearFieldErrors();
  if (!form.username) {
    fieldErrors.username = '请输入登录名';
  }
  if (!form.password) {
    fieldErrors.password = '请输入登录密码';
  }
  if (!form.imageCodeValue) {
    fieldErrors.imageCodeValue = '请输入验证码';
  }
  if (!form.imageCodeKey) {
    captchaError.value = '请先获取验证码';
  }
  return !fieldErrors.username && !fieldErrors.password && !fieldErrors.imageCodeValue && Boolean(form.imageCodeKey);
}

function resultMessage<T>(result: LegacyResult<T> | null | undefined, fallback: string): string {
  return result?.msg || fallback;
}

async function loadCaptcha() {
  captchaLoading.value = true;
  captchaError.value = '';
  try {
    const response = await httpClient.get<LegacyResult<CaptchaPayload>>('/api/image-code/get');
    if (!response.data?.success || !response.data.data?.codeKey || !response.data.data.base64Image) {
      throw new Error(resultMessage(response.data, '验证码加载失败，请重试'));
    }
    form.imageCodeKey = response.data.data.codeKey;
    captchaImage.value = response.data.data.base64Image;
  } catch (error) {
    form.imageCodeKey = '';
    captchaImage.value = '';
    captchaError.value = error instanceof Error ? error.message : '验证码加载失败，请重试';
  } finally {
    captchaLoading.value = false;
  }
}

async function reloadCaptcha() {
  form.imageCodeValue = '';
  await loadCaptcha();
}

async function submitLogin() {
  submitError.value = '';
  captchaError.value = '';
  if (!validateForm()) {
    return;
  }

  submitting.value = true;
  try {
    await ensureEncryptionReady();
    const encryptedPassword = encryptPassword(form.password);
    const payload: LoginPayload = {
      username: form.username,
      password: encryptedPassword,
      imageCodeKey: form.imageCodeKey,
      imageCodeValue: form.imageCodeValue,
      reMemberMe: rememberMe.value ? '1' : ''
    };
    const response = await httpClient.post<LegacyResult<null>>('/api/admin/login', payload);
    if (!response.data?.success) {
      throw new Error(resultMessage(response.data, '登录失败，请稍后重试'));
    }
    form.password = '';
    form.imageCodeValue = '';
    await router.replace(workbenchReturn.value || '/admin/v2');
  } catch (error) {
    submitError.value = error instanceof Error ? error.message : '登录失败，请稍后重试';
    form.imageCodeValue = '';
    await loadCaptcha();
  } finally {
    submitting.value = false;
  }
}

async function ensureEncryptionReady(): Promise<void> {
  if (!window.__CMS_RSA_PUBLIC_KEY__) {
    await loadPublicKey();
  }
  if (!window.JSEncrypt) {
    await loadEncryptionScript();
  }
}

async function loadPublicKey(): Promise<void> {
  const response = await httpClient.get<LegacyResult<PublicKeyPayload>>('/api/admin/login/public-key');
  const publicKey = response.data?.data?.publicKey;
  if (!response.data?.success || !publicKey) {
    throw new Error(resultMessage(response.data, '登录加密公钥加载失败，请刷新页面重试'));
  }
  window.__CMS_RSA_PUBLIC_KEY__ = publicKey;
}

function loadEncryptionScript(): Promise<void> {
  if (window.JSEncrypt) {
    return Promise.resolve();
  }
  if (encryptionScriptPromise) {
    return encryptionScriptPromise;
  }

  encryptionScriptPromise = new Promise((resolve, reject) => {
    document.querySelector('script[data-login-encryption-loader="true"]')?.remove();
    const script = document.createElement('script');
    script.src = `${import.meta.env.BASE_URL}static/js/jsencrypt.min.js`;
    script.async = true;
    script.dataset.loginEncryptionLoader = 'true';
    script.onload = () => {
      if (window.JSEncrypt) {
        resolve();
      } else {
        encryptionScriptPromise = null;
        reject(new Error('登录加密组件加载失败，请刷新页面重试'));
      }
    };
    script.onerror = () => {
      encryptionScriptPromise = null;
      reject(new Error('登录加密组件加载失败，请刷新页面重试'));
    };
    document.head.appendChild(script);
  });

  return encryptionScriptPromise;
}

function encryptPassword(rawPassword: string): string {
  const publicKey = window.__CMS_RSA_PUBLIC_KEY__;
  const Encryptor = window.JSEncrypt;
  if (!publicKey || !Encryptor) {
    throw new Error('登录加密组件加载失败，请刷新页面重试');
  }
  const encryptor = new Encryptor();
  encryptor.setPublicKey(publicKey);
  const encrypted = encryptor.encrypt(rawPassword);
  if (!encrypted) {
    throw new Error('登录密码加密失败，请刷新页面重试');
  }
  return encrypted;
}
</script>

<style scoped>
.login-view {
  min-height: 100vh;
  display: grid;
  grid-template-columns: minmax(0, 1.1fr) minmax(360px, 420px);
  gap: 32px;
  padding: 32px;
  background:
    radial-gradient(circle at top left, rgb(239 68 68 / 0.18), transparent 32%),
    linear-gradient(135deg, #fff5f5 0%, #f8fafc 55%, #fff 100%);
}

.login-view__media {
  position: absolute;
  inset: 0;
  z-index: 0;
  overflow: hidden;
  background: #111827;
}

.login-view__video,
.login-view__poster {
  width: 100%;
  height: 100%;
  display: block;
  object-fit: var(--login-background-fit, cover);
  object-position: var(--login-background-position, center center);
}

.login-view__video {
  position: absolute;
  inset: 0;
}

.login-view__poster {
  filter: saturate(0.96) contrast(1.02);
}

.login-view__hero,
.login-card {
  min-width: 0;
  border: 1px solid rgb(226 232 240 / 0.85);
  border-radius: 8px;
  box-shadow: 0 18px 40px rgb(15 23 42 / 0.08);
}

.login-view__hero {
  position: relative;
  display: grid;
  align-content: end;
  gap: 16px;
  padding: 40px;
  overflow: hidden;
  background: #111827;
  color: #fff;
}

.login-view__hero::after {
  content: "";
  position: absolute;
  inset: 0;
  z-index: 1;
  pointer-events: none;
  background:
    linear-gradient(180deg, rgb(17 24 39 / 0.14) 0%, rgb(17 24 39 / var(--login-overlay-opacity, 0.48)) 48%, rgb(127 29 29 / 0.82) 100%),
    linear-gradient(90deg, rgb(17 24 39 / var(--login-overlay-opacity, 0.48)), transparent 55%);
}

.login-view__hero-content {
  position: relative;
  z-index: 2;
  display: grid;
  gap: 16px;
}

.login-view__hero h1,
.login-card__header h2 {
  margin: 0;
}

.login-view__hero h1 {
  font-size: 42px;
  line-height: 1.08;
}

.login-view__logo {
  max-width: 160px;
  max-height: 52px;
  object-fit: contain;
}

.login-view__hero p {
  margin: 0;
  max-width: 540px;
  color: rgb(255 255 255 / 0.82);
  line-height: 1.7;
}

.login-view__eyebrow,
.login-card__eyebrow {
  margin: 0;
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0;
  text-transform: uppercase;
}

.login-view__eyebrow {
  color: rgb(255 255 255 / 0.88);
  font-size: 42px;
  font-weight: 700;
  line-height: 1.08;
}

.login-view__highlights {
  display: grid;
  gap: 10px;
  margin: 8px 0 0;
  padding: 0;
  list-style: none;
}

.login-view__highlights li {
  width: fit-content;
  min-height: 34px;
  display: inline-flex;
  align-items: center;
  padding: 0 14px;
  border: 1px solid rgb(255 255 255 / 0.18);
  border-radius: 999px;
  background: rgb(255 255 255 / 0.08);
  color: #fff5f5;
  font-size: 13px;
}

.login-card {
  display: grid;
  align-content: start;
  gap: 24px;
  padding: 32px;
  background: #fff;
}

.login-card__header {
  display: grid;
  gap: 8px;
}

.login-card__eyebrow {
  color: #b91c1c;
}

.login-card__header p {
  margin: 0;
  color: #64748b;
  line-height: 1.6;
}

.login-form {
  display: grid;
  gap: 18px;
}

.login-field {
  display: grid;
  gap: 8px;
}

.login-field > span,
.login-field__label-row > span,
.login-checkbox {
  color: #334155;
  font-size: 13px;
  font-weight: 600;
}

.login-field input {
  width: 100%;
  min-height: 44px;
  border: 1px solid #cbd5e1;
  border-radius: 6px;
  padding: 0 14px;
  color: #0f172a;
  font: inherit;
  background: #fff;
}

.login-field input:focus {
  border-color: #dc2626;
  outline: 2px solid rgb(248 113 113 / 0.15);
  outline-offset: 0;
}

.login-field__label-row,
.login-captcha-row,
.login-checkbox {
  display: flex;
  align-items: center;
}

.login-field__label-row {
  justify-content: space-between;
  gap: 12px;
}

.login-captcha-row {
  gap: 12px;
}

.login-captcha-row input {
  flex: 1 1 auto;
}

.login-captcha-image,
.login-captcha-placeholder {
  width: 140px;
  height: 44px;
  border-radius: 6px;
  border: 1px solid #e2e8f0;
  background: #fff5f5;
}

.login-captcha-image {
  display: block;
  object-fit: cover;
}

.login-captcha-placeholder {
  display: grid;
  place-items: center;
  color: #94a3b8;
  font-size: 12px;
}

.login-link-button {
  min-height: 28px;
  border: 0;
  padding: 0;
  background: transparent;
  color: #b91c1c;
  font: inherit;
  cursor: pointer;
}

.login-link-button:disabled,
.login-submit:disabled {
  cursor: not-allowed;
  opacity: 0.65;
}

.login-checkbox {
  gap: 10px;
  font-weight: 500;
}

.login-checkbox input {
  width: 16px;
  height: 16px;
  margin: 0;
}

.login-field__error,
.login-submit-error {
  margin: 0;
  color: #b91c1c;
  font-size: 12px;
  line-height: 1.5;
}

.login-submit {
  min-height: 46px;
  border: 1px solid #b91c1c;
  border-radius: 6px;
  background: #cf1034;
  color: #fff;
  font: inherit;
  font-weight: 700;
  cursor: pointer;
}

@media (max-width: 960px) {
  .login-view {
    grid-template-columns: 1fr;
    padding: 20px;
  }

  .login-view__hero,
  .login-card {
    padding: 24px;
  }

  .login-view__hero h1 {
    font-size: 34px;
  }

  .login-view__eyebrow {
    font-size: 34px;
  }
}

@media (max-width: 640px) {
  .login-view {
    gap: 20px;
    padding: 12px;
  }

  .login-captcha-row {
    align-items: stretch;
    flex-direction: column;
  }

  .login-captcha-image,
  .login-captcha-placeholder {
    width: 100%;
  }
}
</style>
