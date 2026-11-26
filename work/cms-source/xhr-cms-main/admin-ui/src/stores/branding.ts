import { reactive, ref } from 'vue';
import { http, resolveAdminApiBaseUrl } from '@/api/http';
import { resolveResource } from '@/api/resourceReferences';

export type LoginBackgroundType = 'image' | 'video';
export type SidebarBackgroundStyle = 'binary' | 'stars' | 'solid' | 'none';

export interface BrandingSettings {
  systemLogoUrl: string;
  systemName: string;
  loginEyebrow: string;
  loginTitle: string;
  loginSubtitle: string;
  loginFormTitle: string;
  loginFormSubtitle: string;
  loginBackgroundType: LoginBackgroundType;
  loginBackgroundUrl: string;
  loginBackgroundPosterUrl: string;
  loginBackgroundFit: string;
  loginBackgroundPosition: string;
  loginOverlayOpacity: number;
  loginHighlights: string;
  sidebarBackgroundStyle: SidebarBackgroundStyle;
}

interface BrandingResponse {
  settings: BrandingSettings;
  urls?: Record<string, string>;
}

export const defaultBrandingSettings: BrandingSettings = {
  systemLogoUrl: '',
  systemName: '鸿儒 CMS 3.0',
  loginEyebrow: '智能内容管理平台',
  loginTitle: '鸿儒 CMS 3.0',
  loginSubtitle: '现代化多站点内容管理平台，融合 AI 模板识别、可控字段绑定和多语言内容生成能力。',
  loginFormTitle: '欢迎回来',
  loginFormSubtitle: '请输入管理员账号、密码和验证码。',
  loginBackgroundType: 'video',
  loginBackgroundUrl: `${import.meta.env.BASE_URL}media/login-background.mp4`,
  loginBackgroundPosterUrl: `${import.meta.env.BASE_URL}media/login-background-poster.jpg`,
  loginBackgroundFit: 'cover',
  loginBackgroundPosition: 'center center',
  loginOverlayOpacity: 0.48,
  loginHighlights: '多站点统一管理\nAI 辅助模板绑定\n内容模型驱动多语言',
  sidebarBackgroundStyle: 'binary'
};

const settings = reactive<BrandingSettings>({ ...defaultBrandingSettings });
const loading = ref(false);

function syncDocumentTitle(systemName: string): void {
  if (typeof document !== 'undefined') {
    document.title = systemName;
  }
}

export function normalizeBrandingAssetUrl(value: string): string {
  value = resolveResource(value, { siteCode: null, ui: null, upload: null, global: '/assets/global/' });
  if (value.startsWith('/assets/global/')) {
    const base = resolveAdminApiBaseUrl();
    if (/^https?:\/\//i.test(base)) return `${base}${value}`;
  }
  if (!value) {
    return value;
  }
  if (value.startsWith('/admin-ui/media/')) {
    return `${import.meta.env.BASE_URL}media/${value.slice('/admin-ui/media/'.length)}`;
  }
  if (value.startsWith('/media/')) {
    return `${import.meta.env.BASE_URL}media/${value.slice('/media/'.length)}`;
  }
  if (value.startsWith('media/')) {
    return `${import.meta.env.BASE_URL}${value}`;
  }
  return value;
}

export function mediaSourceType(url: string): string {
  return url.toLowerCase().endsWith('.webm') ? 'video/webm' : 'video/mp4';
}

export function assignBrandingSettings(nextSettings?: Partial<BrandingSettings> | null, urls?: Record<string, string>): void {
  Object.assign(settings, {
    ...defaultBrandingSettings,
    ...nextSettings
  });
  for (const key of ['systemLogoUrl', 'loginBackgroundUrl', 'loginBackgroundPosterUrl'] as const) {
    if (urls?.[key] !== undefined) settings[key] = urls[key] || '';
  }
  settings.systemLogoUrl = normalizeBrandingAssetUrl(settings.systemLogoUrl);
  settings.loginBackgroundUrl = normalizeBrandingAssetUrl(settings.loginBackgroundUrl);
  settings.loginBackgroundPosterUrl = normalizeBrandingAssetUrl(settings.loginBackgroundPosterUrl);
  syncDocumentTitle(settings.systemName);
}

export function useBrandingStore() {
  async function loadBranding(): Promise<void> {
    loading.value = true;
    try {
      const data = await http.get<BrandingResponse>('/api/admin/v2/system/branding');
      assignBrandingSettings(data.settings, data.urls);
    } catch {
      assignBrandingSettings(defaultBrandingSettings);
    } finally {
      loading.value = false;
    }
  }

  return {
    settings,
    loading,
    loadBranding
  };
}
