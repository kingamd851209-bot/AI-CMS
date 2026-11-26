<template>
  <section class="home-banner-panel" aria-labelledby="home-banner-heading" v-loading="reading">
    <h4 id="home-banner-heading">首页轮播</h4>
    <p>将已审核的轮播区域接入首页。CMS 只展示来源栏目已发布且在有效期内的记录，首页其他区域继续使用原页面内容。</p>
    <p v-if="plan">当前计划 {{ plan.id }} · 修订 {{ plan.revision }}</p>
    <p v-if="sourcePath">来源页面 {{ sourcePath }}</p>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-alert v-if="notice" :title="notice" type="info" :closable="false" />
    <p v-if="!plan || plan.status !== 'CONFIRMED'" role="status">请在整站结构打开并确认包含首页轮播的计划。</p>
    <p v-else-if="!sourcePath" role="status">请在导航审核确认首页来源。</p>
    <p v-else-if="!reading && !error && !regions.length" role="status">
      {{ bannerEvidenceFound ? '当前计划没有审核此轮播区域。可在整站结构按 ID 打开包含此区域的计划。' : '原首页尚未识别到轮播区域。请在整站结构核对页面证据。' }}
    </p>
    <div class="home-banner-controls">
      <label v-if="regions.length > 1">轮播区域
        <el-select v-model="regionId" :disabled="locked" aria-label="首页轮播区域" @change="read">
          <el-option v-for="(region, index) in regions" :key="region.id" :value="region.id" :label="`${region.label} · 区域 ${index + 1}`" />
        </el-select>
      </label>
      <span v-else-if="regions[0]">{{ regions[0].label }}</span>
      <el-button v-if="plan && sourcePath" size="small" :disabled="locked" @click="read">刷新轮播状态</el-button>
      <el-button v-if="!state || !state.ready" size="small" :disabled="locked" @click="emit('structure')">打开整站结构</el-button>
      <el-button v-if="sourceMenuId" size="small" :disabled="locked" @click="emit('content', sourceMenuId)">轮播内容管理</el-button>
    </div>
    <template v-if="state">
      <el-tag :type="state.applied || state.ready ? 'success' : 'warning'">{{ statusLabels[state.state] }}</el-tag>
      <p role="status">{{ state.message }}</p>
      <p v-if="state.publishedRecords === 0" role="status">没有有效的已发布轮播记录。接入的轮播将在发布有效记录后展示条目，可前往轮播内容管理处理。</p>
      <p v-else-if="state.publishedRecords != null">
        当前有 {{ state.publishedRecords }} 条有效发布记录。<template v-if="state.publishedRecords > state.maxVisibleRecords">首页按原生排序展示前 {{ state.maxVisibleRecords }} 条。</template>
      </p>
      <p v-if="state.activePlanId && !state.applied">当前首页应用计划 {{ state.activePlanId }}。请在原计划撤回该应用后继续。</p>
      <p v-if="state.ready || state.applied">首页入口 /<template v-if="aliases.length">、{{ aliases.join('、') }}</template></p>
      <div class="home-banner-controls">
        <el-button v-if="state.ready && !state.applied && !state.activePlanId" type="primary" :disabled="locked" :loading="writing" @click="write(false)">审核并接入首页轮播</el-button>
        <el-button v-if="state.applied" :disabled="locked" :loading="writing" @click="write(true)">撤回本轮播模板</el-button>
      </div>
    </template>
    <p v-if="state?.applied || result?.applied">
      生成静态首页前，请在下方「栏目路由与内容发布」确认根首页单页已发布。该单页发布后，再进入「静态发布」生成首页文件。
    </p>
    <template v-if="result?.applied">
      <p>请在实际站点地址核对以下公开入口。</p>
      <ul><li v-for="url in result.publicUrls" :key="url"><code>{{ url }}</code></li></ul>
    </template>
  </section>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { ElMessageBox } from 'element-plus';
import { siteModelApi, type HomeBannerOptions, type HomeBannerState, type SiteModelPlan } from '@/api/siteModel';
import { reloadSiteRoutes } from '@/api/templateRecognition';
import type { RootApplicationResult } from '@/api/templateRootApplication';

const props = defineProps<{ siteId: number; plan: SiteModelPlan | null; sourcePath: string | null; disabled?: boolean }>();
const emit = defineEmits<{ busy: [active: boolean]; changed: []; structure: []; content: [menuId: number] }>();
const regions = ref<{ id: string; label: string; menuId: number | null }[]>([]);
const bannerEvidenceFound = ref(false);
const regionId = ref('');
const reading = ref(false);
const writing = ref(false);
const error = ref('');
const notice = ref('');
const state = ref<HomeBannerOptions | null>(null);
const result = ref<RootApplicationResult | null>(null);
const statusLabels: Record<HomeBannerState, string> = {
  READY: '可接入首页', APPLIED: '已接入首页', OTHER_ACTIVE: '已有其他首页应用',
  REVIEW_REQUIRED: '区域或字段待确认', SOURCE_CHANGED: '来源已变化', NOT_INITIALIZED: '轮播记录未初始化',
  ROOT_NOT_PUBLIC: '首页路由待开放前台', TARGET_NOT_PUBLIC: '轮播栏目待开放前台', BLOCKED: '轮播待处理',
};
const locked = computed(() => props.disabled || reading.value || writing.value);
const sourceMenuId = computed(() => regions.value.find(region => region.id === regionId.value)?.menuId);
const aliases = computed(() => state.value?.applied ? state.value.activeAliases : state.value?.availableAliases || []);
let generation = 0;

async function read() {
  const { siteId, plan, sourcePath } = props;
  if (!plan || plan.status !== 'CONFIRMED' || !sourcePath || writing.value) return;
  const token = ++generation;
  reading.value = true;
  error.value = '';
  state.value = null;
  result.value = null;
  try {
    const page = await siteModelApi.evidence(siteId, plan.packageVersion, sourcePath);
    if (token !== generation) return;
    if (page.sourcePath !== sourcePath) throw new Error('轮播证据与所选首页来源不一致。');
    const bannerIds = new Set(page.regions.filter(region => region.kind === 'BANNER').map(region => region.id));
    bannerEvidenceFound.value = bannerIds.size > 0;
    regions.value = plan.proposal.regions.filter(region => region.sourcePath === sourcePath &&
      region.mode === 'LIST' && !region.maintenance && bannerIds.has(region.regionId)).map(region => {
      const model = plan.proposal.models.find(item => item.key === region.modelKey);
      return { id: region.regionId, label: model?.name || '已审核轮播', menuId: model?.menuId ?? null };
    });
    if (!regions.value.some(region => region.id === regionId.value)) {
      regionId.value = regions.value.length === 1 ? regions.value[0]!.id : '';
    }
    if (!regionId.value) return;
    const options = await siteModelApi.homeBanner(siteId, plan, { sourcePath, regionId: regionId.value });
    if (token === generation) state.value = options;
  } catch (cause) {
    if (token === generation) error.value = cause instanceof Error ? cause.message : '无法读取首页轮播状态';
  } finally {
    if (token === generation) reading.value = false;
  }
}

async function write(withdraw: boolean) {
  const { siteId, plan, sourcePath } = props;
  const current = state.value;
  if (locked.value || !plan || !sourcePath || !regionId.value || !current ||
    (withdraw ? !current.applied : !current.ready || current.applied || !!current.activePlanId)) return;
  const selection = { sourcePath, regionId: regionId.value };
  const token = ++generation;
  writing.value = true;
  emit('busy', true);
  error.value = '';
  notice.value = '';
  try {
    await ElMessageBox.confirm(
      withdraw
        ? '恢复本次轮播应用前的根模板指针。来源栏目及其内容仍可在 CMS 管理。'
        : `将此轮播接入根路径 / 及已核对首页入口 ${current.availableAliases.join('、') || '（没有其他 index 入口）'}。请确认后查看实际首页。`,
      withdraw ? '确认撤回首页轮播' : '确认接入首页轮播', { type: 'warning' });
    if (token !== generation) return;
    const applied = withdraw
      ? await siteModelApi.withdrawHomeBanner(siteId, plan, selection)
      : await siteModelApi.applyHomeBanner(siteId, plan, selection, current.availableAliases);
    if (token !== generation) return;
    result.value = applied;
    state.value = null;
    const [reload, options] = await Promise.allSettled([
      applied.requiresRouteReload ? reloadSiteRoutes(siteId) : Promise.resolve(true),
      siteModelApi.homeBanner(siteId, plan, selection),
    ]);
    if (token !== generation) return;
    notice.value = reload.status === 'fulfilled' && reload.value
      ? applied.message
      : '模板状态已保存，路由未重载。请检查现有站点服务再核对实际首页。';
    if (options.status === 'fulfilled') state.value = options.value;
    else error.value = '模板状态已保存，请重新读取首页轮播状态。';
  } catch (cause) {
    if (token === generation && cause !== 'cancel' && cause !== 'close') {
      error.value = cause instanceof Error ? cause.message : '首页轮播操作未完成，请刷新状态';
    }
  } finally {
    if (token === generation) {
      writing.value = false;
      emit('busy', false);
      if (result.value) emit('changed');
    }
  }
}

watch(() => [props.siteId, props.plan?.id, props.plan?.revision, props.sourcePath], () => {
  ++generation;
  if (writing.value) emit('busy', false);
  writing.value = false;
  reading.value = false;
  regions.value = [];
  bannerEvidenceFound.value = false;
  regionId.value = '';
  state.value = null;
  result.value = null;
  error.value = '';
  notice.value = '';
  void read();
}, { immediate: true });
onBeforeUnmount(() => { ++generation; if (writing.value) emit('busy', false); });
</script>

<style scoped>
.home-banner-panel { padding: var(--admin-space-4); border: 1px solid var(--admin-border-subtle); }
.home-banner-controls { display: flex; flex-wrap: wrap; align-items: center; gap: var(--admin-space-2); }
.home-banner-controls label { display: flex; align-items: center; gap: var(--admin-space-2); }
</style>
