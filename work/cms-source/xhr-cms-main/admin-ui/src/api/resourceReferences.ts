import { computed, onMounted, onUnmounted, ref, watch } from 'vue';
import { http } from './http';
import { useSiteContextStore } from '@/stores/siteContext';

export interface ResourceContext {
  siteCode: string | null;
  ui: string | null;
  upload: string | null;
  global: string;
}
const pending = new Map<string, Promise<ResourceContext>>();
function loadContext(siteId?: string | number | null): Promise<ResourceContext> {
  const key = String(siteId ?? 'global');
  const existing = pending.get(key);
  if (existing) return existing;
  const request = http.get<ResourceContext>('/api/admin/v2/resource-references/context', { params: siteId ? { siteId } : { siteCode: '' } });
  pending.set(key, request);
  void request.finally(() => { if (pending.get(key) === request) pending.delete(key); }).catch(() => {});
  return request;
}
const empty: ResourceContext = { siteCode: null, ui: null, upload: null, global: '/assets/global/' };
export function notifyResourceReferencesChanged() {
  pending.clear();
  window.dispatchEvent(new Event('cms-resource-references-changed'));
}
export function useResourceReferences(scope: () => 'site' | 'global' = () => 'site') {
  const site = useSiteContextStore();
  const context = ref<ResourceContext>({ ...empty });
  let sequence = 0;
  async function refresh() {
    const siteId = scope() === 'global' ? null : site.currentSite?.id;
    const request = ++sequence;
    try {
      const result = await loadContext(siteId);
      if (request === sequence) context.value = result;
    } catch { /* show unavailable resources without starting services */ }
  }
  watch(() => [scope(), scope() === 'global' ? null : site.currentSite?.id], () => { context.value = { ...empty }; void refresh(); }, { immediate: true });
  onMounted(() => window.addEventListener('cms-resource-references-changed', refresh));
  onUnmounted(() => window.removeEventListener('cms-resource-references-changed', refresh));
  const resolve = (value: string) => resolveResource(value, context.value);
  return { context, resolve, refresh, siteCode: computed(() => context.value.siteCode) };
}
export function resolveResource(value: string, context: ResourceContext): string {
  if (!value) return '';
  for (const [prefix, base] of [['assets:/global/', context.global], ['assets:/upload/', context.upload], ['ui:/', context.ui]] as const) {
    if (value.startsWith(prefix)) {
      const relative = value.slice(prefix.length);
      const index = relative.search(/[?#]/);
      const path = index < 0 ? relative : relative.slice(0, index);
      const suffix = index < 0 ? '' : relative.slice(index);
      if (path.includes('\\') || path.includes('%') || path.split('/').includes('..')) return '';
      return base ? base + path.split('/').map(encodeURIComponent).join('/') + suffix : '';
    }
  }
  return value;
}
export function canonicalResource(value: string, context: ResourceContext): string {
  if (!value) return value;
  if (context.global.startsWith('/') && typeof window !== 'undefined' && /^https?:\/\//i.test(value)) {
    try {
      const url = new URL(value);
      if (url.origin === window.location.origin && url.pathname.startsWith(context.global)) value = url.pathname + url.search + url.hash;
    } catch { return value; }
  }
  for (const [prefix, base] of [['assets:/global/', context.global], ['assets:/upload/', context.upload], ['ui:/', context.ui]] as const) {
    if (base && value.startsWith(base)) {
      if (prefix === 'ui:/' && !/\.(png|jpe?g|gif|webp|svg|bmp|mp4|webm|mp3|pdf|docx?|xlsx?|pptx?|zip|txt)(?:[?#]|$)/i.test(value)) return value;
      try { return prefix + decodeURIComponent(value.slice(base.length)); } catch { return value; }
    }
  }
  return value;
}
export function transformResourceHtml(html: string, context: ResourceContext, canonical = false): string {
  if (!html || typeof DOMParser === 'undefined') return html;
  const doc = new DOMParser().parseFromString(html, 'text/html');
  const transform = canonical ? canonicalResource : resolveResource;
  for (const element of doc.body.querySelectorAll('[src],[href],[poster],[srcset],[style]')) {
    for (const attribute of ['src', 'href', 'poster']) {
      const value = element.getAttribute(attribute);
      if (value) element.setAttribute(attribute, transform(value, context));
    }
    const style = element.getAttribute('style');
    if (style) element.setAttribute('style', style.replace(/url\(\s*(?:"([^"]*)"|'([^']*)'|([^\s)]*))\s*\)/gi,
      (_match, double: string | undefined, single: string | undefined, bare: string | undefined) => `url("${transform(double ?? single ?? bare ?? '', context)}")`));
    const srcset = element.getAttribute('srcset');
    if (srcset && !srcset.includes('data:') && (canonical || /(?:ui:|assets:)/.test(srcset))) element.setAttribute('srcset', srcset.split(',').map((part) => {
      const [url = '', ...descriptor] = part.trim().split(/\s+/); return [transform(url, context), ...descriptor].join(' ');
    }).join(', '));
  }
  return doc.body.innerHTML;
}
