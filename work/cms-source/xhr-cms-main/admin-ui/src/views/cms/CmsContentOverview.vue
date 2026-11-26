<template>
  <section class="cms-page content-overview">
    <header class="cms-page__hero">
      <div>
        <p class="admin-eyebrow">内容管理</p>
        <h1>内容总览</h1>
        <p>查看当前站点内容统计数据、访问量排行、热门文章和日志记录。</p>
      </div>
      <div class="cms-page__actions">
        <el-button :loading="loading" @click="refreshRevision++">刷新</el-button>
      </div>
    </header>

    <el-empty v-if="!siteContext.currentSite" description="请先选择站点">
      <el-button type="primary" @click="router.push('/admin/v2')">前往工作台</el-button>
    </el-empty>

    <template v-else>
      <!-- 统计卡片 -->
      <div class="content-overview__metrics">
        <div class="content-overview__metric-card">
          <div class="content-overview__metric-icon">
            <el-icon><Document /></el-icon>
          </div>
          <div class="content-overview__metric-info">
            <span class="content-overview__metric-value">{{ statistics.articleCount }}</span>
            <span class="content-overview__metric-label">文章总数</span>
          </div>
        </div>
        <div class="content-overview__metric-card">
          <div class="content-overview__metric-icon content-overview__metric-icon--primary">
            <el-icon><Grid /></el-icon>
          </div>
          <div class="content-overview__metric-info">
            <span class="content-overview__metric-value">{{ statistics.menuCount }}</span>
            <span class="content-overview__metric-label">栏目数量</span>
          </div>
        </div>
        <div class="content-overview__metric-card">
          <div class="content-overview__metric-icon content-overview__metric-icon--warning">
            <el-icon><Clock /></el-icon>
          </div>
          <div class="content-overview__metric-info">
            <span class="content-overview__metric-value">{{ statistics.pendingReviewCount }}</span>
            <span class="content-overview__metric-label">待审核</span>
          </div>
        </div>
        <div class="content-overview__metric-card">
          <div class="content-overview__metric-icon content-overview__metric-icon--info">
            <el-icon><EditPen /></el-icon>
          </div>
          <div class="content-overview__metric-info">
            <span class="content-overview__metric-value">{{ statistics.draftCount }}</span>
            <span class="content-overview__metric-label">草稿</span>
          </div>
        </div>
      </div>

      <div v-if="loading" class="content-overview__loading">
        <el-icon class="is-loading"><Loading /></el-icon>
        <span>加载中...</span>
      </div>

      <!-- 列表区域 -->
      <div v-else class="content-overview__panels">
        <!-- 访问量 Top10 -->
        <section class="content-overview__panel">
          <div class="content-overview__panel-header">
            <h2>访问量 Top10</h2>
            <span>整合栏目和列表内容</span>
          </div>
          <div v-if="topHits.length === 0" class="content-overview__empty">暂无访问量数据</div>
          <ul v-else class="content-overview__list">
            <li v-for="item in topHits" :key="`${item.type}-${item.id}`" class="content-overview__list-item">
              <el-tag class="content-overview__hits" size="small" type="primary">{{ item.hits }}次</el-tag>
              <el-tag size="small" :type="item.type === 'content' ? 'success' : 'info'">
                {{ item.type === 'content' ? '内容' : '栏目' }}
              </el-tag>
              <a v-if="item.visitorUrl" :href="item.visitorUrl" @click.exact.prevent="openVisitorWindow(item.visitorUrl)" title="在新窗口打开" target="_blank" rel="noopener noreferrer" class="content-overview__link">{{ item.title }}</a>
              <span v-else class="content-overview__title">{{ item.title }}</span>
              <span class="content-overview__menu-name">{{ item.menuName }}</span>
            </li>
          </ul>
        </section>

        <!-- 热门文章 -->
        <section class="content-overview__panel">
          <div class="content-overview__panel-header">
            <h2>热门文章</h2>
            <span>列表内容访问量排名</span>
          </div>
          <div v-if="hotArticles.length === 0" class="content-overview__empty">暂无热门文章</div>
          <ul v-else class="content-overview__list">
            <li v-for="article in hotArticles" :key="article.id" class="content-overview__list-item">
              <el-tag class="content-overview__hits" size="small" type="warning">{{ article.hits }}次</el-tag>
              <a v-if="article.visitorUrl" :href="article.visitorUrl" @click.exact.prevent="openVisitorWindow(article.visitorUrl)" title="在新窗口打开" target="_blank" rel="noopener noreferrer" class="content-overview__link">{{ article.title }}</a>
              <span v-else class="content-overview__title">{{ article.title }}</span>
              <span class="content-overview__menu-name">{{ article.menuName }}</span>
            </li>
          </ul>
        </section>

        <!-- 系统日志 -->
        <section class="content-overview__panel">
          <div class="content-overview__panel-header">
            <h2>系统日志</h2>
            <router-link v-if="siteContext.canManageSystem" to="/admin/v2/system/logs">查看更多</router-link>
          </div>
          <div v-if="recentLogs.length === 0" class="content-overview__empty">暂无日志数据</div>
          <ul v-else class="content-overview__list">
            <li v-for="log in recentLogs" :key="log.id" class="content-overview__list-item content-overview__log-item">
              <span class="content-overview__log-user">{{ log.userName }}</span>
              <span class="content-overview__log-action">{{ log.action }}</span>
              <span class="content-overview__log-time">{{ log.createTime }}</span>
            </li>
          </ul>
        </section>

        <!-- 操作日志 -->
        <section class="content-overview__panel">
          <div class="content-overview__panel-header">
            <h2>操作日志</h2>
            <router-link v-if="siteContext.canManageSystem" to="/admin/v2/system/operation-logs">查看更多</router-link>
          </div>
          <div v-if="operationLogs.length === 0" class="content-overview__empty">暂无操作日志</div>
          <ul v-else class="content-overview__list">
            <li v-for="log in operationLogs" :key="log.id" class="content-overview__list-item content-overview__log-item content-overview__log-clickable"
                role="button" tabindex="0" :title="`查看操作日志详情：${log.action}`" @click="openOperationDetail(log.id)"
                @keydown.enter="openOperationDetail(log.id)" @keydown.space.prevent="openOperationDetail(log.id)">
              <span class="content-overview__log-user">{{ log.userName }}</span>
              <span class="content-overview__log-action">{{ log.action }}</span>
              <span class="content-overview__log-time">{{ log.createTime }}</span>
            </li>
          </ul>
        </section>

        <!-- 操作日志详情弹窗 -->
        <el-dialog v-model="operationDetailVisible" title="操作日志详情" width="min(760px, calc(100vw - 32px))">
          <el-descriptions v-if="operationDetail" :column="2" border>
            <el-descriptions-item label="动作" :span="2">{{ operationDetail.action }}</el-descriptions-item>
            <el-descriptions-item label="操作人">{{ operationDetail.userName }}</el-descriptions-item>
            <el-descriptions-item label="IP">{{ operationDetail.ip }}</el-descriptions-item>
            <el-descriptions-item label="栏目ID" :span="2">{{ operationDetail.classId }}</el-descriptions-item>
            <el-descriptions-item label="数据ID" :span="2">{{ operationDetail.infoId }}</el-descriptions-item>
            <el-descriptions-item label="时间" :span="2">{{ formatOperationTime(operationDetail.created) }}</el-descriptions-item>
            <el-descriptions-item v-if="operationDetail.requestData" label="入参数据" :span="2">
              <pre class="content-overview__detail-pre">{{ operationDetail.requestData }}</pre>
            </el-descriptions-item>
            <el-descriptions-item v-if="operationDetail.responseData" label="变更明细" :span="2">
              <div v-html="operationDetail.responseData" class="content-overview__detail-html" />
            </el-descriptions-item>
          </el-descriptions>
        </el-dialog>
      </div>

      <!-- 快捷入口 -->
      <section class="content-overview__quick-links">
        <h2>快捷入口</h2>
        <div class="content-overview__quick-grid">
          <router-link to="/admin/v2/cms/menus" class="content-overview__quick-card">
            <el-icon><Grid /></el-icon>
            <span>栏目管理</span>
          </router-link>
          <router-link to="/admin/v2/cms/content-management" class="content-overview__quick-card">
            <el-icon><Document /></el-icon>
            <span>内容管理</span>
          </router-link>
          <router-link to="/admin/v2/cms/template-management" class="content-overview__quick-card">
            <el-icon><Files /></el-icon>
            <span>模板管理</span>
          </router-link>
        </div>
      </section>
    </template>
  </section>
</template>

<script setup lang="ts">
import { reactive, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { Document, Grid, Clock, EditPen, Loading, Files } from '@element-plus/icons-vue';
import { http } from '@/api/http';
import { getSiteRoutes, getSiteServiceStatus } from '@/api/templateRecognition';
import { getExistingColumns } from '@/api/uiPackage';
import { useSiteContextStore } from '@/stores/siteContext';
import { contentOverviewLinks } from '@/utils/contentOverviewLinks';

interface DashboardStatistics {
  articleCount: number;
  menuCount: number;
  pendingReviewCount: number;
  draftCount: number;
}

interface TopHitItem {
  id: number;
  title: string;
  hits: number;
  menuId: number;
  type: string; // "menu" 或 "content"
  menuName: string;
  detailUrl: string | null;
  visitorUrl?: string;
}

interface HotArticleItem {
  id: number;
  title: string;
  hits: number;
  menuName: string;
  detailUrl: string | null;
  visitorUrl?: string;
}

interface LogItem {
  id: number;
  userName: string;
  action: string;
  createTime: string;
}

interface OperationLogDetail {
  id: number;
  action: string;
  requestData?: string;
  responseData?: string;
  userName?: string;
  ip?: string;
  created?: string;
  classId?: number;
  infoId?: number;
}

interface DashboardResponse {
  statistics: DashboardStatistics;
  topHits: TopHitItem[];
  hotArticles: HotArticleItem[];
  recentLogs: LogItem[];
  operationLogs: LogItem[];
}

const router = useRouter();
const siteContext = useSiteContextStore();
const loading = ref(false);

const statistics = reactive<DashboardStatistics>({
  articleCount: 0,
  menuCount: 0,
  pendingReviewCount: 0,
  draftCount: 0
});

const topHits = ref<TopHitItem[]>([]);
const hotArticles = ref<HotArticleItem[]>([]);
const recentLogs = ref<LogItem[]>([]);
const operationLogs = ref<LogItem[]>([]);
const operationDetail = ref<OperationLogDetail | null>(null);
const operationDetailVisible = ref(false);

const refreshRevision = ref(0);
watch([() => siteContext.currentSite?.id, () => siteContext.switching, refreshRevision], ([id, switching], _, onCleanup) => {
  topHits.value = [];
  hotArticles.value = [];
  recentLogs.value = [];
  operationLogs.value = [];
  operationDetail.value = null;
  operationDetailVisible.value = false;
  Object.assign(statistics, { articleCount: 0, menuCount: 0, pendingReviewCount: 0, draftCount: 0 });
  loading.value = Boolean(id);
  if (!id || switching) return;
  const request = new AbortController();
  onCleanup(() => request.abort());
  void loadDashboard(Number(id), request.signal);
}, { immediate: true, flush: 'sync' });

async function loadDashboard(siteId: number, signal: AbortSignal) {
  loading.value = true;
  try {
    const [data, links] = await Promise.all([
      http.get<DashboardResponse>('/api/admin/v2/cms/dashboard', { signal }),
      Promise.all([getSiteServiceStatus(siteId), getSiteRoutes(siteId), getExistingColumns(siteId)])
        .then(([status, routes, columns]) => contentOverviewLinks(status, routes, columns.flat))
        .catch(() => () => ''),
    ]);
    if (signal.aborted) return;
    Object.assign(statistics, data.statistics);
    topHits.value = (data.topHits || []).map(row => ({ ...row, visitorUrl: links(row) }));
    hotArticles.value = (data.hotArticles || []).map(row => ({ ...row, visitorUrl: links(row) }));
    recentLogs.value = data.recentLogs || [];
    operationLogs.value = data.operationLogs || [];
  } catch (error) {
    if (!signal.aborted) ElMessage.error(error instanceof Error ? error.message : '仪表盘数据加载失败');
  } finally {
    if (!signal.aborted) loading.value = false;
  }
}

async function openOperationDetail(id: number) {
  try {
    operationDetail.value = await http.get<OperationLogDetail>(`/api/admin/v2/system/operation-logs/${id}`);
    operationDetailVisible.value = true;
  } catch (error) {
    operationDetail.value = null;
    operationDetailVisible.value = false;
    ElMessage.error(error instanceof Error ? error.message : '操作日志详情加载失败');
  }
}

function openVisitorWindow(url: string) {
  window.open(url, '_blank', 'popup=yes,noopener,noreferrer,width=1200,height=800');
}

function formatOperationTime(value?: string) {
  if (!value) return '';
  return value.replace('T', ' ').slice(0, 19);
}
</script>

<style scoped>
.cms-page {
  display: grid;
  gap: var(--admin-card-gap);
}

.cms-page__hero {
  display: flex;
  justify-content: space-between;
  gap: 18px;
  padding: var(--admin-card-padding-roomy);
  border: var(--admin-card-border);
  border-radius: var(--admin-card-radius);
  background: var(--admin-surface);
  box-shadow: var(--admin-shadow-card);
}

.cms-page__hero h1,
.cms-page__hero p {
  margin: 0;
}

.cms-page__hero h1 {
  font-size: var(--admin-font-size-page);
  font-weight: 600;
}

.cms-page__hero p {
  color: var(--admin-text-muted);
}

.admin-eyebrow {
  margin: 0 0 4px;
  font-size: var(--admin-font-size-small);
  font-weight: 500;
  color: var(--admin-text-muted);
  text-transform: uppercase;
  letter-spacing: 0.5px;
}

.cms-page__actions {
  display: flex;
  gap: 12px;
  flex-shrink: 0;
}

.content-overview__metrics {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: var(--admin-card-gap);
}

.content-overview__metric-card {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: var(--admin-card-padding);
  border: var(--admin-card-border);
  border-radius: var(--admin-card-radius);
  background: var(--admin-surface);
  box-shadow: var(--admin-shadow-card);
}

.content-overview__metric-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 48px;
  height: 48px;
  border-radius: 8px;
  background: #ecfdf5;
  color: #10b981;
  font-size: 24px;
}

.content-overview__metric-icon--primary {
  background: #eff6ff;
  color: #3b82f6;
}

.content-overview__metric-icon--warning {
  background: #fffbeb;
  color: #f59e0b;
}

.content-overview__metric-icon--info {
  background: #f3f4f6;
  color: #6b7280;
}

.content-overview__metric-info {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.content-overview__metric-value {
  font-size: 28px;
  font-weight: 700;
  color: var(--admin-text);
}

.content-overview__metric-label {
  font-size: var(--admin-font-size-body);
  color: var(--admin-text-muted);
}

.content-overview__loading {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 60px;
  color: var(--admin-text-muted);
}

.content-overview__panels {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  grid-auto-rows: 1fr;
  gap: var(--admin-card-gap);
}

.content-overview__panel {
  display: flex;
  flex-direction: column;
  min-height: 0;
  padding: var(--admin-card-padding);
  border: var(--admin-card-border);
  border-radius: var(--admin-card-radius);
  background: var(--admin-surface);
  box-shadow: var(--admin-shadow-card);
}

.content-overview__panel-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--admin-border);
  flex-shrink: 0;
}

.content-overview__panel-header h2 {
  margin: 0;
  font-size: var(--admin-font-size-section);
  font-weight: 600;
}

.content-overview__panel-header span,
.content-overview__panel-header a {
  font-size: var(--admin-font-size-small);
  color: var(--admin-text-muted);
}

.content-overview__panel-header a {
  color: var(--el-color-primary);
  text-decoration: none;
}

.content-overview__empty {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 340px;
  padding: 0 12px;
  text-align: center;
  color: var(--admin-text-muted);
}

.content-overview__list {
  height: 340px;
  margin: 0;
  padding: 0;
  list-style: none;
  overflow-y: auto;
}

.content-overview__list-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 0;
  border-bottom: 1px solid var(--admin-border-subtle);
}

.content-overview__list-item:last-child {
  border-bottom: none;
}

.content-overview__hits {
  flex-shrink: 0;
}

.content-overview__link {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--el-color-primary);
  text-decoration: none;
}

.content-overview__link:hover {
  text-decoration: underline;
}

.content-overview__title {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--admin-text);
}

.content-overview__menu-name {
  flex-shrink: 0;
  font-size: 12px;
  color: var(--admin-text-muted);
}

.content-overview__log-item {
  flex-wrap: wrap;
}

.content-overview__log-clickable {
  cursor: pointer;
}

.content-overview__log-clickable:hover {
  background: var(--admin-fill-hover, rgba(0, 0, 0, 0.04));
}

.content-overview__log-clickable:focus-visible {
  outline: 2px solid var(--el-color-primary);
  outline-offset: -2px;
}

.content-overview__detail-pre {
  max-height: 240px;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-word;
}

.content-overview__detail-html :deep(table) {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}

.content-overview__detail-html :deep(th),
.content-overview__detail-html :deep(td) {
  border: 1px solid var(--admin-border-subtle);
  padding: 8px;
  text-align: left;
}

.content-overview__detail-html :deep(th) {
  background: var(--admin-bg-page);
  font-weight: 600;
}

.content-overview__log-user {
  font-weight: 600;
  color: var(--admin-text);
}

.content-overview__log-action {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--admin-text-muted);
}

.content-overview__log-time {
  font-size: 12px;
  color: var(--admin-text-muted);
}

.content-overview__quick-links {
  padding: 20px;
  border: 1px solid var(--admin-border);
  border-radius: 8px;
  background: var(--admin-surface);
  box-shadow: var(--admin-shadow-card);
}

.content-overview__quick-links h2 {
  margin: 0 0 16px;
  font-size: var(--admin-font-size-section);
  font-weight: 600;
}

.content-overview__quick-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: var(--admin-card-gap);
}

.content-overview__quick-card {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: var(--admin-card-padding);
  border: var(--admin-card-border);
  border-radius: var(--admin-card-radius);
  background: var(--admin-bg-page);
  color: var(--admin-text);
  text-decoration: none;
  transition: all 0.2s;
}

.content-overview__quick-card:hover {
  border-color: var(--el-color-primary);
  color: var(--el-color-primary);
}

.content-overview__quick-card .el-icon {
  font-size: 24px;
}

.content-overview__quick-card span {
  font-size: var(--admin-font-size-body);
  font-weight: 500;
}

@media (max-width: 1024px) {
  .content-overview__metrics {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .content-overview__panels {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .content-overview__quick-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 768px) {
  .content-overview__panels {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 640px) {
  .cms-page__hero {
    flex-direction: column;
    padding: var(--admin-card-padding-compact);
  }

  .cms-page__actions {
    flex-wrap: wrap;
  }

  .content-overview__metrics {
    grid-template-columns: 1fr;
  }

  .content-overview__quick-grid {
    grid-template-columns: 1fr;
  }
}
</style>
