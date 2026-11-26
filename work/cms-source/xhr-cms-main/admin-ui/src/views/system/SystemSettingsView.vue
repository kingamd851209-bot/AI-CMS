<template>
  <section class="work-page admin-responsive-page">
    <header class="work-hero">
      <div>
        <p class="admin-eyebrow">系统管理</p>
        <h1>系统设置</h1>
        <p>维护登录入口品牌配置（Logo、登录页文案与背景媒体）。</p>
      </div>
    </header>

    <section class="work-card branding-card">
      <div class="section-head">
        <div>
          <p class="admin-eyebrow">品牌与登录页</p>
          <h2>登录入口配置</h2>
          <p>维护系统 Logo、系统名称、登录页文字以及图片/视频背景。</p>
        </div>
        <div class="work-actions admin-toolbar">
          <el-button :loading="brandingLoading" @click="loadBranding">刷新品牌</el-button>
          <el-button type="primary" :loading="brandingSaving" @click="saveBranding">保存品牌配置</el-button>
        </div>
      </div>

      <div class="branding-grid admin-split-layout">
        <el-form label-position="top" class="branding-form admin-drawer-form" @submit.prevent>
          <div class="branding-form__grid">
            <div class="branding-resource-field span-2" data-testid="branding-logo-resource" data-resource-type="image">
              <ResourceFieldControl scope="global"
                id="system-logo"
                v-model="branding.systemLogoUrl"
                type="image"
                label="系统 Logo"
                placeholder="/assets/global/logo.png"
                accept=".jpg,.jpeg,.png,.webp,.svg"
                :allowed-extensions="logoAllowedExtensions"
              />
            </div>
            <el-form-item label="系统名称">
              <el-input v-model="branding.systemName" data-testid="branding-system-name-input" />
            </el-form-item>
            <br>
            <el-form-item label="登录页眉标识">
              <el-input v-model="branding.loginEyebrow" />
            </el-form-item>
            <el-form-item label="登录页标题">
              <el-input v-model="branding.loginTitle" data-testid="branding-login-title-input" />
            </el-form-item>
            <el-form-item label="登录页说明" class="span-2">
              <el-input v-model="branding.loginSubtitle" data-testid="branding-login-subtitle-input" type="textarea" :rows="4" />
            </el-form-item>
            <el-form-item label="登录框标题">
              <el-input v-model="branding.loginFormTitle" />
            </el-form-item>
            <el-form-item label="登录框说明">
              <el-input v-model="branding.loginFormSubtitle" />
            </el-form-item>
            <el-form-item label="背景类型">
              <el-segmented v-model="branding.loginBackgroundType" :options="backgroundTypeOptions" data-testid="branding-background-type" />
            </el-form-item>
            <el-form-item label="背景填充">
              <el-select v-model="branding.loginBackgroundFit">
                <el-option value="cover" label="铺满裁切" />
                <el-option value="contain" label="完整显示" />
                <el-option value="fill" label="拉伸填充" />
              </el-select>
            </el-form-item>
            <el-form-item label="侧边栏背景样式">
              <el-select v-model="branding.sidebarBackgroundStyle" data-testid="branding-sidebar-background-style">
                <el-option
                  v-for="option in sidebarBackgroundStyleOptions"
                  :key="option.value"
                  :value="option.value"
                  :label="option.label"
                />
              </el-select>
            </el-form-item>
            <div
              class="branding-resource-field span-2"
              data-testid="branding-background-resource"
              :data-resource-type="branding.loginBackgroundType === 'video' ? 'video' : 'image'"
            >
              <ResourceFieldControl scope="global"
                id="login-background"
                v-model="branding.loginBackgroundUrl"
                :type="branding.loginBackgroundType === 'video' ? 'video' : 'image'"
                label="登录页背景"
                :accept="branding.loginBackgroundType === 'video' ? '.mp4,.webm' : '.jpg,.jpeg,.png,.webp'"
                :allowed-extensions="backgroundAllowedExtensions"
              />
            </div>
            <div class="branding-resource-field span-2" data-testid="branding-poster-resource" data-resource-type="image">
              <ResourceFieldControl scope="global"
                id="login-background-poster"
                v-model="branding.loginBackgroundPosterUrl"
                type="image"
                label="视频封面/图片兜底"
                accept=".jpg,.jpeg,.png,.webp"
                :allowed-extensions="imageAllowedExtensions"
              />
            </div>
            <el-form-item label="背景位置">
              <el-input v-model="branding.loginBackgroundPosition" placeholder="center center" />
            </el-form-item>
            <el-form-item label="遮罩透明度">
              <el-slider v-model="branding.loginOverlayOpacity" :min="0" :max="0.85" :step="0.01" />
            </el-form-item>
            <el-form-item label="特点标签" class="span-2">
              <el-input v-model="branding.loginHighlights" type="textarea" :rows="3" placeholder="每行一个标签" />
            </el-form-item>
          </div>
        </el-form>

        <aside class="login-preview">
          <div class="login-preview__media">
            <img :src="previewPosterUrl" alt="" />
            <video v-if="branding.loginBackgroundType === 'video'" :src="previewAssetUrl(branding.loginBackgroundUrl)" muted loop playsinline />
          </div>
          <div class="login-preview__copy">
            <p>{{ branding.loginEyebrow }}</p>
            <h3>{{ branding.loginTitle }}</h3>
            <span>{{ branding.loginSubtitle }}</span>
          </div>
        </aside>
      </div>
    </section>

  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { ElMessage } from 'element-plus';
import { http } from '@/api/http';
import { useResourceReferences } from '@/api/resourceReferences';
import ResourceFieldControl from '@/components/resources/ResourceFieldControl.vue';
import { assignBrandingSettings, defaultBrandingSettings, normalizeBrandingAssetUrl, type BrandingSettings } from '@/stores/branding';

interface BrandingResponse { settings: BrandingSettings; urls?: Record<string, string> }

const brandingLoading = ref(false);
const brandingSaving = ref(false);
const branding = reactive<BrandingSettings>({ ...defaultBrandingSettings });

const backgroundTypeOptions = [
  { label: '视频', value: 'video' },
  { label: '图片', value: 'image' }
];
const imageAllowedExtensions = ['jpg', 'jpeg', 'png', 'webp'];
const logoAllowedExtensions = [...imageAllowedExtensions, 'svg'];
const videoAllowedExtensions = ['mp4', 'webm'];
const sidebarBackgroundStyleOptions = [
  { label: '二进制数字', value: 'binary' },
  { label: '星空蓝紫', value: 'stars' },
  { label: '深色渐变', value: 'solid' },
  { label: '纯净深色', value: 'none' }
];

const { resolve: resolveGlobalResource } = useResourceReferences(() => 'global');
const previewAssetUrl = (value: string) => normalizeBrandingAssetUrl(resolveGlobalResource(value));
const previewPosterUrl = computed(() => previewAssetUrl(branding.loginBackgroundType === 'image'
  ? branding.loginBackgroundUrl
  : branding.loginBackgroundPosterUrl));
const backgroundAllowedExtensions = computed(() => branding.loginBackgroundType === 'video'
  ? videoAllowedExtensions
  : imageAllowedExtensions);

onMounted(() => {
  void loadBranding();
});

async function loadBranding() {
  brandingLoading.value = true;
  try {
    const data = await http.get<BrandingResponse>('/api/admin/v2/system/branding');
    Object.assign(branding, data.settings);
    assignBrandingSettings(data.settings, data.urls);
  } catch (error) {
    ElMessage.error(errorMessage(error, '品牌配置加载失败'));
  } finally {
    brandingLoading.value = false;
  }
}

async function saveBranding() {
  brandingSaving.value = true;
  try {
    const validationError = validateBrandingMedia();
    if (validationError) {
      ElMessage.error(validationError);
      return;
    }
    await persistBranding();
    ElMessage.success('品牌配置已保存');
  } catch (error) {
    ElMessage.error(errorMessage(error, '品牌配置保存失败'));
  } finally {
    brandingSaving.value = false;
  }
}

function validateBrandingMedia() {
  return validateBrandingMediaPath(branding.systemLogoUrl, '系统 Logo', logoAllowedExtensions)
    ?? validateBrandingMediaPath(
      branding.loginBackgroundUrl,
      '登录页背景',
      branding.loginBackgroundType === 'video' ? videoAllowedExtensions : imageAllowedExtensions
    )
    ?? validateBrandingMediaPath(branding.loginBackgroundPosterUrl, '视频封面/图片兜底', imageAllowedExtensions);
}

function validateBrandingMediaPath(value: string | undefined, label: string, allowedExtensions: string[]) {
  const text = value?.trim() ?? '';
  if (!text) {
    return null;
  }
  if (!text.startsWith('assets:/global/') && hasSiteAssetPathRisk(text)) {
    return `${label}必须使用 /assets/global/、/media/ 或 /static/ 下的安全站内路径`;
  }
  if (!hasAllowedOrNoExtension(text, allowedExtensions)) {
    return `${label}仅支持 ${allowedExtensions.join('、')} 格式`;
  }
  return null;
}

async function persistBranding() {
  const data = await http.post<BrandingResponse>('/api/admin/v2/system/branding', { ...branding });
  Object.assign(branding, data.settings);
  assignBrandingSettings(data.settings, data.urls);
}

function hasSiteAssetPathRisk(value: string) {
  const trimmed = value.trim();
  if (!trimmed || /[\u0000-\u001f\u007f\\]/.test(trimmed) || !trimmed.startsWith('/') || trimmed.startsWith('//')) {
    return true;
  }
  const path = trimmed.split(/[?#]/)[0] ?? '';
  const decodedPath = decodePathRepeatedly(path);
  if (!decodedPath || /[\u0000-\u001f\u007f\\]/.test(decodedPath)) {
    return true;
  }
  const segments = decodedPath.split('/');
  if (segments.includes('..')) {
    return true;
  }
  const normalizedSegments: string[] = [];
  for (const segment of segments) {
    if (!segment || segment === '.') {
      continue;
    }
    normalizedSegments.push(segment);
  }
  const normalizedPath = `/${normalizedSegments.join('/')}`;
  return !['/assets/global/', '/media/', '/static/'].some((prefix) => normalizedPath.startsWith(prefix));
}

function decodePathRepeatedly(value: string) {
  let decoded = value;
  try {
    for (let index = 0; index < 3 && decoded.includes('%'); index += 1) {
      const next = decodeURIComponent(decoded);
      if (next === decoded) {
        return decoded;
      }
      decoded = next;
    }
    return decoded;
  } catch {
    return '';
  }
}

function isAllowedBackgroundExtension(value: string) {
  const extension = decodedResourceExtension(value);
  return !extension || ['jpg', 'jpeg', 'png', 'webp', 'mp4', 'webm'].includes(extension);
}

function isAllowedLogoExtension(value: string) {
  const extension = decodedResourceExtension(value);
  return !extension || ['jpg', 'jpeg', 'png', 'webp', 'svg'].includes(extension);
}

function hasAllowedOrNoExtension(value: string, allowedExtensions: string[]) {
  const extension = decodedResourceExtension(value);
  return !extension || allowedExtensions.includes(extension);
}

function decodedResourceExtension(value: string) {
  const path = decodePathRepeatedly(value.split(/[?#]/)[0]?.toLowerCase() ?? '');
  const fileName = path.split('/').filter(Boolean).at(-1) ?? path;
  return fileName.includes('.') ? fileName.slice(fileName.lastIndexOf('.') + 1) : '';
}

function errorMessage(error: unknown, fallback: string) {
  return error instanceof Error ? error.message : fallback;
}
</script>

<style scoped>
.work-page { display: grid; gap: var(--admin-card-gap); min-width: 0; }
.work-hero, .work-card { border: var(--admin-card-border); border-radius: var(--admin-card-radius); background: var(--admin-bg-surface); box-shadow: var(--admin-shadow-md); min-width: 0; }
.work-hero { display: flex; justify-content: space-between; gap: 20px; min-width: 0; padding: var(--admin-card-padding-roomy); }
.work-hero h1, .work-hero p { margin: 0; }
.work-hero p { color: var(--admin-text-muted); }
.work-actions { display: flex; align-items: center; gap: 12px; min-width: 0; }
.work-card { padding: var(--admin-card-padding); }
.section-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 18px; min-width: 0; margin-bottom: 18px; }
.section-head h2, .section-head p { margin: 0; }
.section-head p { color: var(--admin-text-muted); }
.branding-grid { display: grid; grid-template-columns: minmax(0, 1fr) 360px; gap: var(--admin-card-gap); align-items: start; }
.branding-form__grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px; }
.branding-resource-field { min-width: 0; }
.span-2 { grid-column: span 2; }
.login-preview { position: relative; min-height: 420px; overflow: hidden; border-radius: 8px; background: #111827; color: #fff; }
.login-preview__media { position: absolute; inset: 0; }
.login-preview__media img, .login-preview__media video { width: 100%; height: 100%; object-fit: cover; object-position: center; display: block; }
.login-preview__media video { position: absolute; inset: 0; }
.login-preview__copy { position: absolute; inset: auto 0 0; padding: 24px; background: linear-gradient(180deg, transparent, rgb(127 29 29 / 0.82)); }
.login-preview__copy p, .login-preview__copy h3, .login-preview__copy span { margin: 0; }
.login-preview__copy span { display: block; color: rgb(255 255 255 / 0.78); line-height: 1.6; margin-top: 10px; }
@media (max-width: 1180px) { .branding-grid, .branding-form__grid { grid-template-columns: 1fr; } .span-2 { grid-column: auto; } }

[data-testid="branding-logo-resource"] :deep(.resource-preview__image img) { object-fit: contain !important; }
</style>
