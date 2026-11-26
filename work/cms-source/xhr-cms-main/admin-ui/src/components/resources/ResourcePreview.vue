<template>
  <figure
    class="resource-preview"
    :data-media-type="resolvedMediaType"
    :data-testid="previewTestId || undefined"
  >
    <el-image
      v-if="resolvedMediaType === 'image' && normalizedUrl && !failed"
      :src="normalizedUrl"
      :preview-src-list="[normalizedUrl]"
      :alt="name || fileName"
      fit="cover"
      class="resource-preview__image"
      @error="failed = true"
    />
    <video
      v-else-if="resolvedMediaType === 'video' && normalizedUrl && !failed"
      :src="normalizedUrl"
      controls
      muted
      playsinline
      @error="failed = true"
    />
    <span
      v-else-if="failed && url"
      class="resource-preview__missing"
      :data-testid="missingTestId || undefined"
      :title="normalizedUrl || url"
    >
      <strong>资源缺失</strong>
      <small>{{ normalizedUrl || url }}</small>
    </span>
    <div v-else class="resource-preview__file">
      <strong>{{ name || fileName }}</strong>
      <small>{{ url }}</small>
      <a v-if="downloadUrl" :href="downloadUrl" target="_blank" rel="noopener noreferrer">下载附件</a>
      <small v-if="fileStatus === 'checking'">正在检查本地附件…</small>
      <small v-else-if="fileStatus === 'unavailable'">无法验证本地附件，请检查站点权限或资源配置</small>
      <small v-else-if="fileStatus === 'external'">外部链接，未验证本地文件</small>
    </div>
  </figure>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { canonicalResource, useResourceReferences } from '@/api/resourceReferences';
import { staticAssetApi, type AssetScope } from '@/api/staticAssets';
import { useSiteContextStore } from '@/stores/siteContext';
import { normalizeResourceUrl, type ResourceMediaType } from './types';

const props = defineProps<{
  url: string;
  mediaType?: ResourceMediaType;
  name?: string;
  previewTestId?: string;
  missingTestId?: string;
}>();

const failed = ref(false);
const { resolve, context } = useResourceReferences();
const site = useSiteContextStore();
const fileStatus = ref<'checking' | 'available' | 'unavailable' | 'external' | ''>('');
const localDownload = ref('');
const normalizedUrl = computed(() => normalizeResourceUrl(resolve(props.url)));
const fileName = computed(() => props.url.split('/').filter(Boolean).at(-1) ?? '资源文件');
const resolvedMediaType = computed<ResourceMediaType>(() => {
  if (props.mediaType) {
    return props.mediaType;
  }
  const lowerUrl = props.url.toLowerCase();
  if (/\.(jpg|jpeg|png|gif|webp|svg)(\?|#|$)/.test(lowerUrl)) {
    return 'image';
  }
  if (/\.(mp4|webm|ogg|mov)(\?|#|$)/.test(lowerUrl)) {
    return 'video';
  }
  return 'file';
});

const localFile = computed(() => {
  const value = canonicalResource(props.url.trim(), context.value);
  const scopes: [string, AssetScope][] = [
    ['assets:/global/', 'GLOBAL'], ['assets:/upload/', 'SITE_ASSETS'], ['ui:/', 'SITE_UIPACKAGE']
  ];
  for (const [prefix, scope] of scopes) {
    if (!value.startsWith(prefix)) continue;
    const path = value.slice(prefix.length).split(/[?#]/)[0] ?? '';
    const siteId = scope === 'GLOBAL' ? undefined : Number(site.currentSite?.id);
    return { path, scope, siteId };
  }
  return null;
});
const downloadUrl = computed(() => {
  if (resolvedMediaType.value !== 'file') return '';
  if (fileStatus.value === 'available') return localDownload.value;
  return fileStatus.value === 'external' ? normalizedUrl.value : '';
});

watch(() => [props.url, resolvedMediaType.value, localFile.value] as const, async (_, __, onCleanup) => {
  let active = true;
  onCleanup(() => { active = false; });
  failed.value = false;
  localDownload.value = '';
  fileStatus.value = '';
  if (resolvedMediaType.value !== 'file' || !props.url.trim()) return;
  const local = localFile.value;
  if (!local) {
    fileStatus.value = /^(assets:|ui:)/.test(props.url) ? 'unavailable' : 'external';
    return;
  }
  if (!local.path || (local.scope !== 'GLOBAL' && !Number.isFinite(local.siteId))) {
    fileStatus.value = 'unavailable';
    return;
  }
  fileStatus.value = 'checking';
  try {
    const result = await staticAssetApi.metadata(local.path, local.scope, local.siteId);
    if (!active) return;
    failed.value = !result.exists;
    fileStatus.value = result.exists ? 'available' : '';
    if (result.exists) localDownload.value = staticAssetApi.downloadUrl(local.path, local.scope, local.siteId);
  } catch {
    if (active) fileStatus.value = 'unavailable';
  }
}, { immediate: true });
</script>

<style scoped>
.resource-preview {
  display: grid;
  gap: 6px;
  min-width: 0;
  margin: 0;
}

.resource-preview__image {
  display: block;
  width: 100px;
  height: 100px;
  border: 1px solid var(--admin-border-subtle);
  border-radius: var(--admin-radius-sm);
  background: var(--admin-bg-subtle);
  cursor: pointer;
}

.resource-preview__image :deep(img) {
  width: 100%;
  height: 100%;
  object-fit: cover;
  border-radius: var(--admin-radius-sm);
}

.resource-preview video {
  display: block;
  width: 160px;
  max-height: 120px;
  object-fit: contain;
  border: 1px solid var(--admin-border-subtle);
  border-radius: var(--admin-radius-sm);
  background: var(--admin-bg-subtle);
}

.resource-preview__file,
.resource-preview__missing {
  display: grid;
  gap: 4px;
  min-width: 0;
  padding: 10px;
  border: 1px solid var(--admin-border-subtle);
  border-radius: var(--admin-radius-sm);
  background: var(--admin-bg-subtle);
}

.resource-preview__missing {
  color: var(--el-color-danger);
}

.resource-preview__file strong,
.resource-preview__file small,
.resource-preview__missing strong,
.resource-preview__missing small {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.resource-preview__file small,
.resource-preview__missing small {
  color: var(--admin-text-muted);
}
</style>
