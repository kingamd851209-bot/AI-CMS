<template>
  <section :id="sharedContentOnly ? undefined : 'workbench-publish-panel'" v-loading="loading" class="publish-panel" :aria-label="sharedContentOnly ? '公共数据发布' : '栏目路由与内容发布'">
    <el-empty v-if="!menuId" description="请选择目标栏目" :image-size="48" />
    <el-alert v-else-if="error" :title="error" type="error" :closable="false" show-icon />
    <template v-if="menu">
      <div class="panel-heading">
        <h4>{{ menu.className }} · 栏目 {{ menu.id }}</h4>
        <el-button size="small" :disabled="locked" @click="reload()">刷新栏目状态</el-button>
      </div>
      <template v-if="!sharedContentOnly">
        <el-descriptions :column="1" size="small" border>
          <el-descriptions-item label="已保存前台渠道">{{ menu.hasFrontChannel ? '已开启' : '未开启，访客入口尚不可用' }}</el-descriptions-item>
          <el-descriptions-item label="已保存栏目路由">{{ menu.listPageUrl || '未配置' }}</el-descriptions-item>
          <el-descriptions-item label="栏目页模板">{{ menu.listPageTemplate || '未绑定' }}</el-descriptions-item>
          <el-descriptions-item v-if="menu.pageType === 1 || menu.detailPageTemplate" label="详情页模板">
            {{ menu.detailPageTemplate || '未绑定' }}
          </el-descriptions-item>
        </el-descriptions>
        <el-form label-position="top" class="route-form" :disabled="locked || !routeEditable">
          <el-form-item label="栏目页路由">
            <el-input v-model="routeDraft.listPageUrl" placeholder="/news/list/{pageIndex}.html" />
          </el-form-item>
          <el-form-item v-if="menu.pageType === 1 || menu.detailPageUrl" label="详情页路由">
            <el-input v-model="routeDraft.detailPageUrl" placeholder="/news/detail/{id}.html" />
          </el-form-item>
          <el-form-item label="前台渠道">
            <el-switch v-model="routeDraft.hasFrontChannel" active-text="开启" inactive-text="关闭" />
          </el-form-item>
          <el-button type="primary" plain :disabled="!hasUnsavedChanges" @click="saveRoutes">审核路由变更</el-button>
          <el-button v-if="hasUnsavedChanges" @click="discardRoutes">放弃路由修改</el-button>
        </el-form>
        <p v-if="!menu.url?.trim()" class="panel-note">该栏目无后台地址，请通过模板应用设置其公开入口。</p>
        <p v-else class="panel-note">路由保存只修改栏目配置。需要时在站点服务中重载运行路由。</p>

      </template>
      <template v-if="contentManageable">
        <h4>{{ sharedContentOnly ? '公共数据发布状态' : '内容发布状态' }}</h4>
        <p v-if="!sharedContentOnly" class="panel-note">发布前请在内容初值或私有预览中核对正文。</p>
        <p v-else class="panel-note">发布后可供已绑定的公共区域读取，栏目路由配置保持原值。</p>
        <p v-if="!sharedContentOnly && (!menu.listPageTemplate || !menu.listPageUrl || !menu.hasFrontChannel)" class="panel-note">
          发布后，内容可供正式模板读取。公开页面还需要应用模板、配置路由并开启前台渠道。
        </p>
        <p v-if="!canPublish" class="panel-note">当前账号可核对状态，发布需要本栏目发布权限。</p>
        <p v-if="hasUnsavedChanges" class="panel-note">请先保存或放弃路由修改，再发布内容。</p>
        <el-alert v-if="contentError" :title="contentError" type="error" :closable="false" show-icon />
        <template v-else-if="singleStatus">
          <el-tag :type="singleStatus === 'PUBLISHED' ? 'success' : 'info'">{{ singleStatusText }}</el-tag>
          <el-button
            v-if="canPublish && canPublishSingle(singleStatus)"
            type="primary" plain size="small" :disabled="locked || hasUnsavedChanges" @click="publish()"
          >{{ sharedContentOnly ? '发布公共数据' : '发布单页' }}</el-button>
          <span v-if="sharedContentOnly && singleStatus === 'PENDING_REVIEW'" class="panel-note">请先完成审核。</span>
        </template>
        <template v-else-if="menu.pageType === 1">
          <el-table :data="rows" row-key="id" size="small" empty-text="该栏目暂无内容">
            <el-table-column prop="title" label="内容" min-width="180" />
            <el-table-column prop="id" label="ID" width="80" />
            <el-table-column label="状态" width="100">
              <template #default="{ row }">{{ recordStatus[row.status] ?? '未知' }}</template>
            </el-table-column>
            <el-table-column v-if="canPublish" label="操作" width="100">
              <template #default="{ row }">
                <el-button
                  v-if="publishable(row)" type="primary" plain size="small" :disabled="locked || hasUnsavedChanges"
                  @click="publish(row)"
                >发布</el-button>
                <span v-else-if="row.status === 2" class="panel-note">请先审核</span>
              </template>
            </el-table-column>
          </el-table>
          <el-pagination
            v-if="total > pageSize" :current-page="page" :page-size="pageSize"
            :total="total" :disabled="locked" layout="prev, pager, next, total"
            @current-change="reload"
          />
        </template>
      </template>
    </template>
    <el-button v-else-if="error" size="small" :disabled="locked" @click="reload()">重新读取</el-button>
  </section>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { http } from '@/api/http';
import { useSiteContextStore } from '@/stores/siteContext';

const props = defineProps<{
  siteId: number;
  menuId: number | null;
  refreshKey?: string | number;
  disabled?: boolean;
  sharedContentOnly?: boolean;
}>();
const emit = defineEmits<{ changed: []; busy: [active: boolean] }>();
const siteContext = useSiteContextStore();
const cms = '/api/admin/v2/cms';
const approvalOptionId = 4;
const publishOptionType = 25;
const pageSize = 10;

interface Routes {
  listPageUrl: string;
  detailPageUrl: string;
  hasFrontChannel: boolean;
}

interface Menu {
  id: number;
  siteProjectId: number;
  siteRootId: number;
  className: string;
  parentId: number;
  pageType: number;
  hasManageChannel: boolean;
  hasFrontChannel: boolean;
  url?: string | null;
  listPageUrl?: string | null;
  detailPageUrl?: string | null;
  listPageTemplate?: string | null;
  detailPageTemplate?: string | null;
}
interface MenuSummary extends Menu {
  contentManageable: boolean;
  isCanEdit: boolean;
}
interface RecordRow {
  id: number;
  classid: number;
  title: string;
  status: number;
}
interface SingleResponse {
  menu: Menu;
  status: string;
}
interface Scope {
  siteId: number;
  menuId: number;
  revision: number;
}

const menu = ref<Menu>();
const editable = ref(false);
const contentManageable = ref(false);
const singleStatus = ref('');
const rows = ref<RecordRow[]>([]);
const page = ref(1);
const total = ref(0);
const routeDraft = reactive<Routes>(routesOf());
const routeBaseline = ref<Menu>();
const error = ref('');
const contentError = ref('');
const loading = ref(false);
const writing = ref(false);
let revision = 0;
let disposed = false;

const locked = computed(() => !!props.disabled || loading.value || writing.value || siteContext.switching);
const routeEditable = computed(() => !!menu.value?.url?.trim() && editable.value);
const hasUnsavedChanges = computed(() => !!routeBaseline.value
  && JSON.stringify(routesOf(routeBaseline.value)) !== JSON.stringify(routeDraft));
defineExpose({ hasUnsavedChanges });
const canPublish = computed(() => !!menu.value && siteContext.canOperate(menu.value.id, approvalOptionId));
const recordStatus: Record<number, string> = { 0: '草稿', 1: '已发布', 2: '待审', 3: '已驳回', 4: '作废' };
const singleStatusText = computed(() => ({
  DRAFT: '草稿', APPROVED: '已批准', PENDING_REVIEW: '待审', PUBLISHED: '已发布', DISABLED: props.sharedContentOnly ? '草稿' : '前台已关闭',
})[singleStatus.value] ?? '未知');

function canPublishSingle(status: string) {
  return (props.sharedContentOnly ? ['DRAFT', 'DISABLED', 'APPROVED'] : ['DRAFT', 'APPROVED', 'PENDING_REVIEW']).includes(status);
}
function verifySharedOwner(item: Menu) {
  if (props.sharedContentOnly && (item.pageType !== 0 || !item.hasManageChannel))
    throw new Error('公共数据目标必须是开启后台管理的单页栏目。');
}

function routesOf(item?: Menu): Routes {
  return {
    listPageUrl: item?.listPageUrl ?? '',
    detailPageUrl: item?.detailPageUrl ?? '',
    hasFrontChannel: item?.hasFrontChannel === true,
  };
}
function resetRoutes(item?: Menu) {
  routeBaseline.value = item;
  Object.assign(routeDraft, routesOf(item));
}
function discardRoutes() {
  resetRoutes(menu.value);
}
function publishable(row: RecordRow) {
  return row.classid === menu.value?.id && [0, 3].includes(row.status);
}
function isCurrent(scope: Scope) {
  return scope.revision === revision && scope.siteId === props.siteId && scope.menuId === props.menuId
    && !siteContext.switching && Number(siteContext.currentSite?.id) === scope.siteId;
}
function requireCurrent(scope: Scope) {
  if (!isCurrent(scope)) throw new Error('站点或栏目已切换，请重新读取。');
}
function verifyMenu(item: Menu, scope: Scope) {
  if (item.id !== scope.menuId || item.siteProjectId !== scope.siteId) {
    throw new Error('栏目与当前站点或目标不一致。');
  }
}
async function verifySite(scope: Scope) {
  requireCurrent(scope);
  const current = await http.get<{ siteId: number | string; globalMode?: boolean }>('/api/admin/site-context/current');
  requireCurrent(scope);
  if (current.globalMode || Number(current.siteId) !== scope.siteId) {
    throw new Error('当前会话站点已变化，请重新选择该站点。');
  }
}
async function readMenu(scope: Scope) {
  const item = await http.get<Menu>(`${cms}/menus/${scope.menuId}`);
  requireCurrent(scope);
  verifyMenu(item, scope);
  return item;
}

async function reload(nextPage = 1) {
  revision++;
  if (routeBaseline.value?.id !== props.menuId || routeBaseline.value?.siteProjectId !== props.siteId) {
    resetRoutes();
  }
  menu.value = undefined;
  editable.value = false;
  contentManageable.value = false;
  singleStatus.value = '';
  rows.value = [];
  total.value = 0;
  error.value = '';
  contentError.value = '';
  loading.value = false;
  if (!props.menuId || siteContext.switching) return;
  const scope = { siteId: props.siteId, menuId: props.menuId, revision };
  if (!isCurrent(scope)) {
    error.value = '请选择该栏目的所属站点。';
    return;
  }
  loading.value = true;
  page.value = nextPage;
  try {
    await verifySite(scope);
    const workspace = await http.get<{ currentSite: { siteId: number }; flat: MenuSummary[] }>(`${cms}/menus`, {
      params: { siteId: scope.siteId },
    });
    requireCurrent(scope);
    const summary = workspace.flat.find((item) => item.id === scope.menuId);
    if (workspace.currentSite.siteId !== scope.siteId || !summary) {
      throw new Error('所选栏目不在当前站点。');
    }
    verifyMenu(summary, scope);
    const item = await readMenu(scope);
    verifySharedOwner(item);
    menu.value = item;
    if (!hasUnsavedChanges.value) resetRoutes(item);
    editable.value = summary.isCanEdit && siteContext.hasMenuPermission(scope.menuId);
    contentManageable.value = summary.contentManageable;
    if (!contentManageable.value) return;
    try {
      if (item.pageType === 0) {
        const single = await http.get<SingleResponse>(`${cms}/single/${item.id}`);
        requireCurrent(scope);
        verifyMenu(single.menu, scope);
        singleStatus.value = single.status;
      } else {
        const content = await http.get<{ menu: Menu; rows: RecordRow[]; total: number }>(`${cms}/content`, {
          params: { classId: item.id, page: nextPage, limit: pageSize },
        });
        requireCurrent(scope);
        verifyMenu(content.menu, scope);
        if (content.rows.some((row) => row.classid !== scope.menuId)) {
          throw new Error('返回内容与目标栏目不一致。');
        }
        rows.value = content.rows;
        total.value = content.total;
      }
    } catch (e) {
      if (isCurrent(scope)) contentError.value = message(e, '内容状态读取失败');
    }
  } catch (e) {
    if (isCurrent(scope)) error.value = message(e, '栏目状态读取失败');
  } finally {
    if (isCurrent(scope)) loading.value = false;
  }
}

function message(reason: unknown, fallback: string) {
  return reason instanceof Error ? reason.message : fallback;
}
function menuSnapshot(item: Menu) {
  const { id, siteProjectId, siteRootId, className, parentId, pageType, hasManageChannel, url } = item;
  return {
    id, siteProjectId, siteRootId, className, parentId, pageType, hasManageChannel, url,
    ...routesOf(item),
    listPageTemplate: item.listPageTemplate ?? '',
    detailPageTemplate: item.detailPageTemplate ?? '',
  };
}

async function confirmMutation(title: string, description: string, mutate: (scope: Scope) => Promise<void>) {
  const item = menu.value;
  if (!item || locked.value) return;
  const scope = { siteId: props.siteId, menuId: item.id, revision };
  if (!isCurrent(scope)) return;
  writing.value = true;
  emit('busy', true);
  try {
    await ElMessageBox.confirm(description, title, { type: 'warning', confirmButtonText: title });
    if (!isCurrent(scope)) return;
    await verifySite(scope);
    await mutate(scope);
    if (!isCurrent(scope)) return;
    ElMessage.success(`${title}成功`);
    emit('changed');
    await reload(page.value);
  } catch (e) {
    if (isCurrent(scope) && e !== 'cancel' && e !== 'close') ElMessage.error(message(e, `${title}失败`));
  } finally {
    writing.value = false;
    if (!disposed) emit('busy', false);
  }
}

function saveRoutes() {
  const initial = routeBaseline.value;
  if (!initial || !routeEditable.value || !hasUnsavedChanges.value) return;
  const before = routesOf(initial);
  const after = {
    listPageUrl: routeDraft.listPageUrl === before.listPageUrl ? before.listPageUrl : routeDraft.listPageUrl.trim(),
    detailPageUrl: routeDraft.detailPageUrl === before.detailPageUrl ? before.detailPageUrl : routeDraft.detailPageUrl.trim(),
    hasFrontChannel: routeDraft.hasFrontChannel,
  };
  const paths = [
    before.listPageUrl !== after.listPageUrl ? after.listPageUrl : '',
    before.detailPageUrl !== after.detailPageUrl ? after.detailPageUrl : '',
  ];
  if (paths.some((path) => path && (!/^\/(?!\/)/.test(path) || /[\s?#\\]/.test(path)))) {
    ElMessage.warning('请输入以单个 / 开头的站内路径，可留空。');
    return;
  }
  const description = [
    before.listPageUrl !== after.listPageUrl ? `栏目页：${before.listPageUrl || '空'} → ${after.listPageUrl || '空'}` : '',
    before.detailPageUrl !== after.detailPageUrl ? `详情页：${before.detailPageUrl || '空'} → ${after.detailPageUrl || '空'}` : '',
    before.hasFrontChannel !== after.hasFrontChannel ? `前台渠道：${after.hasFrontChannel ? '开启' : '关闭'}` : '',
  ].filter(Boolean).join('，');
  if (!description) {
    resetRoutes(initial);
    return;
  }
  void confirmMutation('保存路由', `确认修改「${initial.className}」（${initial.id}）？${description}。`, async (scope) => {
    const latest = await readMenu(scope);
    if (!siteContext.hasMenuPermission(scope.menuId)) throw new Error('当前用户无该栏目权限。');
    if (JSON.stringify(menuSnapshot(initial)) !== JSON.stringify(menuSnapshot(latest))) {
      throw new Error('栏目配置已变化，请刷新后重新审核。');
    }
    const { id, siteProjectId, siteRootId, className, parentId, pageType, hasManageChannel, url } = latest;
    const result = await http.post<Menu>(`${cms}/menus`, {
      id, siteProjectId, siteRootId, className, parentId, pageType, hasManageChannel, url,
      hasFrontChannel: after.hasFrontChannel,
      ...(before.listPageUrl !== after.listPageUrl ? { listPageUrl: after.listPageUrl } : {}),
      ...(before.detailPageUrl !== after.detailPageUrl ? { detailPageUrl: after.detailPageUrl } : {}),
    });
    verifyMenu(result, scope);
    if (isCurrent(scope)) resetRoutes(result);
  });
}

function publish(row?: RecordRow) {
  const item = menu.value;
  if (!item || !canPublish.value || !contentManageable.value || hasUnsavedChanges.value) return;
  if (row ? !publishable(row) : item.pageType !== 0 || !canPublishSingle(singleStatus.value)) return;
  const initialSingleStatus = singleStatus.value;
  const title = row ? `「${row.title}」（内容 ${row.id}）` : `「${item.className}」（栏目 ${item.id}）`;
  const status = row ? recordStatus[row.status] : singleStatusText.value;
  const effect = props.sharedContentOnly ? '已绑定的公共区域将可读取此数据。' : '发布后内容可供正式模板读取。';
  const description = `确认发布${title}？当前状态为${status}。${effect}`;
  void confirmMutation('发布内容', description, async (scope) => {
    if (!siteContext.canOperate(scope.menuId, approvalOptionId)) throw new Error('当前用户无发布权限。');
    if (row) {
      const latest = await http.get<{ menu: Menu; content: RecordRow }>(`${cms}/content/${scope.menuId}/${row.id}`);
      requireCurrent(scope);
      verifyMenu(latest.menu, scope);
      if (latest.content.id !== row.id || latest.content.classid !== scope.menuId
        || latest.content.status !== row.status || latest.content.title !== row.title) {
        throw new Error('内容或发布状态已变化，请刷新后重试。');
      }
      await http.post(`${cms}/content/${scope.menuId}/option`, { ids: [row.id], optionType: publishOptionType });
    } else {
      const latest = await http.get<SingleResponse>(`${cms}/single/${scope.menuId}`);
      requireCurrent(scope);
      verifyMenu(latest.menu, scope);
      verifySharedOwner(latest.menu);
      if (latest.status !== initialSingleStatus || latest.menu.className !== item.className) {
        throw new Error('单页栏目或发布状态已变化，请刷新后重试。');
      }
      const result = await http.post<SingleResponse>(`${cms}/single/${scope.menuId}/publish`);
      verifyMenu(result.menu, scope);
    }
  });
}

watch(
  [() => props.siteId, () => props.menuId, () => props.refreshKey, () => siteContext.currentSite?.id, () => siteContext.switching],
  () => { void reload(); },
  { immediate: true },
);
onBeforeUnmount(() => {
  disposed = true;
  revision++;
  if (writing.value) emit('busy', false);
});
</script>

<style scoped>
.publish-panel { display: grid; gap: 12px; }
.panel-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.publish-panel h4 { margin: 0; }
.publish-panel :deep(.el-descriptions__content) { overflow-wrap: anywhere; }
.panel-note { margin: 0; color: var(--el-text-color-secondary); font-size: 13px; }
</style>
