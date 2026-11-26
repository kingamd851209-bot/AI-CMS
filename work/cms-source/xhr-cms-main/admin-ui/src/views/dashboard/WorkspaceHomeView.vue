<template>
  <section class="workspace-home">
    <header class="workspace-home__hero">
      <div class="workspace-home__hero-copy">
        <p class="admin-eyebrow">CMS 运营中心</p>
        <h1>站点运营工作台</h1>
        <p class="workspace-home__hero-text">
          查看当前站点，管理栏目内容与语言版本，继续日常更新和发布检查。
        </p>
      </div>

      <div class="workspace-home__hero-side">
        <RouterLink v-if="developerWorkbenchEnabled && isPlatformAdmin" class="workspace-home__primary" to="/admin/v2/site-build">整站建站引导</RouterLink>
        <section class="workspace-home__site-context">
          <span class="workspace-home__site-kicker">当前站点</span>
          <strong>{{ currentSiteName }}</strong>
          <div class="workspace-home__site-meta">
            <span v-for="item in currentSiteMeta" :key="item">{{ item }}</span>
          </div>
        </section>

        <RouterLink
          v-if="canManageSites && isPlatformAdmin"
          class="workspace-home__primary"
          data-testid="workspace-primary-action"
          to="/admin/v2/sites-v2?create=1"
        >
          新增站点
        </RouterLink>
      </div>
    </header>

    <div class="admin-metric-grid workspace-home__metrics">
      <MetricCard
        label="当前站点"
        :value="currentSiteName"
        :helper="siteMetricHelper"
        tone="primary"
        action-label="切换站点"
        :action-to="canManageSites ? '/admin/v2/sites-v2' : ''"
      />
      <MetricCard
        label="栏目驱动"
        value="栏目管理"
        helper="按当前站点维护栏目树、模板路径和 SEO 信息。"
        tone="success"
        action-label="进入栏目管理"
        action-to="/admin/v2/cms/menus"
      />
      <MetricCard
        label="内容管理"
        value="内容工作台"
        helper="按栏目管理内容列表，支持搜索、筛选、编辑与发布。"
        tone="success"
        action-label="进入内容管理"
        action-to="/admin/v2/cms/content-management"
      />
      <MetricCard
        label="多语言任务"
        :value="i18nMetricValue"
        :helper="i18nMetricHelper"
        tone="warning"
        action-label="进入多语言工作台"
        :action-to="canManageSites && isPlatformAdmin ? '/admin/v2/sites-v2?i18n=1' : ''"
      />
    </div>

    <div class="workspace-home__content">
      <section class="workspace-home__panel">
        <div class="workspace-home__panel-head">
          <div>
            <h2>下一步工作</h2>
            <p>从站点配置、内容更新到访问检查，继续处理日常工作。</p>
          </div>
        </div>

        <ul class="workspace-home__next-list">
          <li v-for="item in nextWorkItems" :key="item.title" class="workspace-home__next-item">
            <div>
              <strong>{{ item.title }}</strong>
              <p>{{ item.description }}</p>
            </div>
            <RouterLink :to="item.to">{{ item.actionLabel }}</RouterLink>
          </li>
        </ul>
      </section>

      <section class="workspace-home__panel">
        <div class="workspace-home__panel-head">
          <div>
            <h2>常用工作入口</h2>
            <p>快速进入栏目、内容和多语言管理。</p>
          </div>
        </div>

        <div class="workspace-home__quick-grid">
          <RouterLink v-for="item in quickLinks" :key="item.to" class="workspace-home__quick-link" :to="item.to">
            <strong>{{ item.title }}</strong>
            <span>{{ item.description }}</span>
          </RouterLink>
        </div>
      </section>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue';
import { getActivePinia } from 'pinia';
import MetricCard from '@/components/common/MetricCard.vue';
import { useSiteContextStore } from '@/stores/siteContext';

const siteContext = getActivePinia() ? useSiteContextStore() : null;

const currentSiteName = computed(() => siteContext?.currentSite?.name || '未选择站点');

/** 站点管理/站点服务入口受 requiresSystemAdmin 管控；菜单配置授权对应路径时同样放行，避免点开后被守卫拦截 */
function canOpenSystemPath(path: string): boolean {
  return siteContext ? siteContext.canManageSystem || siteContext.canAccessSystemMenuPath(path) : false;
}

const canManageSites = computed(() => canOpenSystemPath('/admin/v2/sites-v2'));
const canOpenSiteService = computed(() => canOpenSystemPath('/admin/v2/site-service'));
/** 新增站点仅平台管理员（超管或平台组）可用，与站点管理页/后端 requireSuperAdmin 口径一致 */
const isPlatformAdmin = computed(() => (siteContext ? siteContext.isPlatformAdmin : false));
const developerWorkbenchEnabled = import.meta.env.VITE_DEVELOPER_WORKBENCH === 'true';
const currentSiteMeta = computed(() => {
  const site = siteContext?.currentSite;

  if (!site) {
    return ['请先在站点管理中选择当前站点'];
  }

  const meta = [site.code];

  if (site.domain) {
    meta.push(site.domain);
  }

  meta.push(site.language || '默认语言待确认');

  return meta;
});

const siteMetricHelper = computed(() => {
  const site = siteContext?.currentSite;

  if (!site) {
    return '站点上下文尚未锁定，先选择站点再发起 ZIP 上传和多语言任务。';
  }

  return `站点编码 ${site.code}${site.domain ? ` · ${site.domain}` : ''}`;
});

const i18nMetricValue = computed(() => siteContext?.currentSite?.language || '模型化翻译');
const i18nMetricHelper = computed(() => siteContext?.currentSite
  ? '基于当前站点语言和内容模型继续发起多语言任务。'
  : '先选择站点并确认内容模型版本，再进入多语言工作台。');

const nextWorkItems = computed(() => [
  ...(canOpenSiteService.value
    ? [
        {
          title: '站点服务',
          description: '监控站点服务运行状态，管理服务启停与访问地址。',
          actionLabel: '查看服务',
          to: '/admin/v2/site-service'
        }
      ]
    : []),
  ...(canManageSites.value
    ? [
        {
          title: '站点管理',
          description: '切换当前站点，确认站点上下文、域名和模板包承接状态。',
          actionLabel: '打开站点',
          to: '/admin/v2/sites-v2'
        }
      ]
    : []),
  {
    title: '内容总览',
    description: '按当前站点栏目维护内容、字段、模板和预览验证。',
    actionLabel: '进入内容',
    to: '/admin/v2/cms/content'
  }
]);

const quickLinks = computed(() => [
  {
    title: '栏目管理',
    description: '维护栏目树、模板路径和 SEO。',
    to: '/admin/v2/cms/menus'
  },
  {
    title: '内容管理',
    description: '按栏目维护内容和推荐关系。',
    to: '/admin/v2/cms/content'
  },
  {
    title: '模板查看',
    description: '查看模板包、栏目与模板绑定状态。',
    to: '/admin/v2/cms/template-management'
  },
  ...(canManageSites.value && isPlatformAdmin.value
    ? [
        {
          title: '多语言管理',
          description: '基于内容模型生成多语言站点。',
          to: '/admin/v2/sites-v2?i18n=1'
        }
      ]
    : [])
]);
</script>

<style scoped>
.workspace-home {
  display: grid;
  gap: var(--admin-card-gap);
}

.workspace-home__hero,
.workspace-home__panel {
  display: grid;
  gap: var(--admin-space-5);
  padding: var(--admin-space-6);
  border: 1px solid var(--admin-border-subtle);
  border-radius: var(--admin-radius-lg);
  background: var(--admin-bg-surface);
  box-shadow: var(--admin-shadow-sm);
}

.workspace-home__hero {
  grid-template-columns: minmax(0, 1.45fr) minmax(280px, 0.55fr);
  align-items: start;
}

.workspace-home__hero-copy h1,
.workspace-home__panel-head h2 {
  margin: 0;
}

.workspace-home__hero-text,
.workspace-home__panel-head p,
.workspace-home__next-item p,
.workspace-home__table td p {
  margin: 0;
  color: var(--admin-text-secondary);
  line-height: 1.7;
}

.workspace-home__hero-side {
  display: grid;
  gap: var(--admin-card-gap);
  align-content: start;
  justify-items: stretch;
}

.workspace-home__site-context {
  display: grid;
  gap: var(--admin-space-3);
  padding: var(--admin-space-5);
  border: 1px solid rgb(217 45 32 / 0.12);
  border-radius: var(--admin-radius-lg);
  background: linear-gradient(180deg, rgb(217 45 32 / 0.06) 0%, rgb(255 255 255 / 0.95) 100%);
}

.workspace-home__site-kicker {
  color: var(--admin-color-primary);
  font-size: 12px;
  font-weight: 700;
}

.workspace-home__site-context strong {
  font-size: 22px;
  line-height: 1.3;
}

.workspace-home__site-meta {
  display: flex;
  flex-wrap: wrap;
  gap: var(--admin-space-2);
}

.workspace-home__site-meta span {
  display: inline-flex;
  align-items: center;
  min-height: 28px;
  padding: 0 10px;
  border-radius: var(--admin-radius-pill);
  background: var(--admin-bg-surface);
  color: var(--admin-text-secondary);
  font-size: 12px;
  font-weight: 600;
}

.workspace-home__primary,
.workspace-home__next-item a,
.workspace-home__quick-link {
  text-decoration: none;
}

.workspace-home__primary {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-height: 44px;
  padding: 0 var(--admin-space-5);
  border-radius: var(--admin-radius-lg);
  color: var(--admin-text-inverse);
  background: var(--admin-color-primary);
  font-weight: 700;
  box-shadow: 0 12px 30px rgb(217 45 32 / 0.16);
}

.workspace-home__metrics {
  grid-template-columns: repeat(4, minmax(0, 1fr));
}

.workspace-home__content {
  display: grid;
  gap: var(--admin-card-gap);
  grid-template-columns: minmax(320px, 0.9fr) minmax(0, 1.1fr);
}

.workspace-home__panel-head {
  display: flex;
  align-items: start;
  justify-content: space-between;
  gap: var(--admin-space-4);
}

.workspace-home__next-list {
  display: grid;
  gap: var(--admin-space-3);
  margin: 0;
  padding: 0;
  list-style: none;
}

.workspace-home__next-item {
  display: grid;
  gap: var(--admin-space-3);
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: center;
  padding: var(--admin-space-4);
  border: 1px solid var(--admin-border-subtle);
  border-radius: var(--admin-radius-lg);
  background: var(--admin-bg-page);
}

.workspace-home__next-item.is-danger {
  border-left: 4px solid var(--el-color-danger);
}

.workspace-home__next-item.is-warning {
  border-left: 4px solid var(--el-color-warning);
}

.workspace-home__next-item.is-info {
  border-left: 4px solid var(--el-color-primary);
}

.workspace-home__next-item strong,
.workspace-home__quick-link strong {
  display: block;
  margin-bottom: 6px;
}

.workspace-home__next-item a,
.workspace-home__quick-link {
  color: var(--admin-color-primary);
  font-weight: 700;
}

.workspace-home__quick-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--admin-card-gap);
}

.workspace-home__quick-link {
  display: grid;
  gap: 6px;
  min-height: 96px;
  padding: var(--admin-space-4);
  border: 1px solid var(--admin-border-subtle);
  border-radius: var(--admin-radius-lg);
  background: var(--admin-bg-page);
}

.workspace-home__quick-link span {
  color: var(--admin-text-secondary);
  font-size: 13px;
  font-weight: 500;
  line-height: 1.6;
}

@media (max-width: 1180px) {
  .workspace-home__metrics,
  .workspace-home__content {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 960px) {
  .workspace-home__hero,
	  .workspace-home__content,
	  .workspace-home__metrics,
	  .workspace-home__next-item,
	  .workspace-home__quick-grid {
	    grid-template-columns: 1fr;
	  }

  .workspace-home__panel-head {
    flex-direction: column;
  }
}
</style>
