<template>
  <section class="static-publish">
    <div class="static-publish__main">
      <aside class="static-publish__sidebar">
        <div class="static-publish__sidebar-header">
          <span>栏目选择</span>
          <div class="static-publish__sidebar-actions">
            <el-button size="small" link :loading="selectingAll" title="选择当前已加载的可发布栏目" @click="checkAll">全选</el-button>
            <el-button size="small" link @click="uncheckAll">置空</el-button>
          </div>
        </div>
        <div v-if="currentSite" class="static-publish__site-context">
          <span>{{ currentSite.siteName || '未命名站点' }}</span>
          <em>{{ currentSite.siteCode || '未设置编码' }}</em>
          <strong>站点模式：{{ siteModeLabel(currentSite.siteMode) }}</strong>
        </div>
        <div v-if="requestedMenu" class="static-publish__site-context">
          <span>{{ scopeNotice || '从已应用模板进入，本次仅选择当前栏目。' }}</span>
          <el-button size="small" link @click="$router.replace({path:$route.path})">查看全站栏目</el-button>
        </div>
        <div class="static-publish__tree-wrapper" v-loading="loading">
          <el-tree
            ref="treeRef"
            :key="String(siteContext.currentSite?.id)+'/'+requestedMenu"
            :data="menuTree"
            show-checkbox
            node-key="id"
            :props="treeProps"
            :lazy="true"
            :load="loadChildMenus"
            :default-expanded-keys="expandedKeys"
            :default-checked-keys="initialChecked"
            @check="onCheck"
          >
            <template #default="{ data }">
              <span class="static-publish__tree-node" :class="{ 'is-denied': !siteContext.hasMenuPermission(Number(data.id)) }">
                <span>{{ data.className }}</span>
                <el-tag size="small" type="info">{{ data.id }}</el-tag>
                <el-tooltip
                  v-if="treeProps.disabled(data)"
                  content="该栏目未配置列表页地址和详情页地址，无法发布"
                  placement="top"
                >
                  <el-icon class="static-publish__tree-warn"><WarningFilled /></el-icon>
                </el-tooltip>
              </span>
            </template>
          </el-tree>
          <el-empty v-if="!loading && menuTree.length === 0" description="暂无栏目数据" />
        </div>
      </aside>

      <div class="static-publish__content">
        <div class="static-publish__info">
          <h3>发布说明</h3>
          <el-alert type="warning" :closable="false" show-icon>
            <template #title>
              注：数据在前台哪个(些)栏目显示，就生成哪个(些)栏目。
            </template>
          </el-alert>
        </div>

        <section v-if="workbenchScope" class="static-publish__service" aria-label="发布所需的站点服务">
          <el-alert v-if="serviceStatus?.status === 'STOPPED'" title="站点服务未启动" type="warning" :closable="false" show-icon
            :description="serviceStatus.errorMessage || '生成静态页需要当前站点服务先运行。启动后可继续发布已选栏目。'" />
          <p v-else-if="readingService" role="status">正在读取当前站点服务状态…</p>
          <p v-else-if="serviceStatus?.status === 'RUNNING'" role="status">当前站点服务运行中，可继续发布已选栏目。</p>
          <el-alert v-if="serviceError" :title="serviceError" type="error" :closable="false" show-icon />
          <p v-if="serviceStatus?.status === 'STOPPED' && !canStartService">请由有站点服务权限的管理员启动。</p>
          <div class="static-publish__service-actions">
            <el-button v-if="serviceStatus?.status === 'STOPPED' && canStartService" type="primary" :loading="startingService"
              :disabled="readingService || publishing || !selectedTarget" @click="startCurrentSite">启动当前站点</el-button>
            <el-button v-if="selectedTarget" link :disabled="startingService || readingService || publishing" @click="readCurrentService">重新读取服务状态</el-button>
            <el-button link :disabled="startingService || publishing" @click="returnToWorkbench">返回建站工作台</el-button>
          </div>
        </section>

        <div class="static-publish__selected">
          <h3>已选栏目</h3>
          <div class="static-publish__selected-count">
            已选择 <strong>{{ checkedMenuIds.length }}</strong> 个栏目
          </div>
          <div class="static-publish__selected-list" v-if="checkedMenus.length > 0">
            <el-tag
              v-for="menu in checkedMenus"
              :key="menu.id"
            size="small"
              class="static-publish__tag"
              closable
              @close="removeChecked(menu.id)"
            >
              {{ menu.className }}
            </el-tag>
          </div>
          <el-empty v-else description="请从左侧选择栏目" :image-size="60" />
        </div>

        <div class="static-publish__actions">
          <el-button type="primary" size="large" :loading="publishing" :disabled="publicationBlocked" @click="publish">
            <el-icon><Promotion /></el-icon>
            发布静态页
          </el-button>
        </div>
      </div>
    </div>

    <!-- 发布进度弹窗 -->
    <el-dialog
      v-model="progressVisible"
      title="静态页发布"
      width="60%"
      :close-on-click-modal="false"
      :close-on-press-escape="false"
      :show-close="false"
      align-center
    >
      <iframe
        v-if="progressUrl"
        :src="progressUrl"
        title="静态生成进度" @load="readProgressResult"
        class="static-publish__progress-iframe"
        frameborder="0"
      />
      <template #footer>
        <el-button v-if="completedPageCount!==null" @click="closeProgress">关闭</el-button>
        <el-button v-else type="danger" @click="closeProgress">终止发布</el-button>
        <el-button v-if="canOpenAudit" type="primary" @click="openAudit">核验生成结果</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<script setup lang="ts">
import { ref, computed, watch, onBeforeUnmount } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { Promotion, WarningFilled } from '@element-plus/icons-vue';
import { http } from '@/api/http';
import { getSiteServiceStatus, startSiteService, type SiteServiceStatus } from '@/api/templateRecognition';
import { useSiteContextStore } from '@/stores/siteContext';

interface MenuNode {
  id: number;
  parentId: number | null;
  className: string;
  hasChildren: boolean;
  listPageUrl?: string;
  detailPageUrl?: string;
  listPageTemplate?: string;
  children?: MenuNode[];
}

interface CurrentSiteSummary {
  siteId?: number | null;
  rootMenuId?: number | null;
  siteName?: string | null;
  siteCode?: string | null;
  globalMode?: boolean;
  siteMode?: string | null;
}

const siteContext = useSiteContextStore();
const route=useRoute();
const router=useRouter();
const requestedMenu=computed(()=>typeof route.query.menuId==='string' ? route.query.menuId : '');
const workbenchScope=computed(()=>import.meta.env.VITE_DEVELOPER_WORKBENCH==='true' && !!requestedMenu.value && typeof route.query.siteId==='string');
const initialChecked=ref<number[]>([]);
const scopeNotice=ref('');
let loadRevision=0;
const currentSite = ref<CurrentSiteSummary | null>(null);

const treeRef = ref();
const loading = ref(false);
const publishing = ref(false);
const selectingAll = ref(false);
const progressVisible = ref(false);
const progressUrl = ref('');
const completedPageCount=ref<number|null>(null);
const publishSiteId=ref<number|null>(null);
const canOpenAudit=computed(()=>import.meta.env.VITE_DEVELOPER_WORKBENCH==='true' &&
  completedPageCount.value!==null && publishSiteId.value!=null && publishSiteId.value===currentSite.value?.siteId);
function readProgressResult(event:Event){
  if(!progressVisible.value || !progressUrl.value)return;
  const frame=event.currentTarget as HTMLIFrameElement;
  const doc=frame.contentDocument;
  if(!doc || doc.URL!==new URL(progressUrl.value,window.location.href).href)return;
  const count=doc.querySelector('[data-cms-static-count]')?.getAttribute('data-cms-static-count');
  if(count && /^\d+$/.test(count))completedPageCount.value=Number(count);
}
function openAudit(){
  if(!canOpenAudit.value)return;
  const siteId=String(publishSiteId.value);
  closeProgress();
  void router.push({path:'/admin/v2/site-build',query:{releaseCheck:'1',siteId}});
}
const menuTree = ref<MenuNode[]>([]);
const expandedKeys = ref<number[]>([]);
const treeCheckedMenuIds = ref<number[]>([]);
const menuMap = ref<Map<number, MenuNode>>(new Map());
const serviceStatus=ref<SiteServiceStatus>();
const serviceError=ref(''), readingService=ref(false), startingService=ref(false);
let serviceRevision=0;

const selectedTarget=computed(()=>{
  const siteId=Number(siteContext.currentSite?.id), target=menuTree.value[0];
  if(!workbenchScope.value || !Number.isSafeInteger(siteId) || siteId<=0 || String(siteId)!==route.query.siteId ||
    menuTree.value.length!==1 || !target || String(target.id)!==requestedMenu.value || !siteContext.hasMenuPermission(Number(target.id)))return null;
  return {siteId,menuId:Number(target.id)};
});
const serviceScope=computed(()=>selectedTarget.value ? `${selectedTarget.value.siteId}:${selectedTarget.value.menuId}` : '');
const canStartService=computed(()=>siteContext.canManageSystem || siteContext.canAccessSystemMenuPath('/admin/v2/site-service'));
const publicationBlocked=computed(()=>!checkedMenuIds.value.length || (workbenchScope.value && (!selectedTarget.value ||
  checkedMenuIds.value.length!==1 || checkedMenuIds.value[0]!==selectedTarget.value.menuId || serviceStatus.value?.status!=='RUNNING' ||
  readingService.value || startingService.value)));

const treeProps = {
  label: 'className',
  children: 'children',
  isLeaf: (data: MenuNode) => !data.hasChildren,
  // 未配置列表页/详情页地址的栏目不可勾选（无法生成静态页）
  disabled: (data: MenuNode) => !data.listPageUrl && !data.detailPageUrl
    && !(data.id === currentSite.value?.rootMenuId && data.listPageTemplate)
};

function canPublishMenu(menu: MenuNode | undefined): menu is MenuNode {
  return !!menu && !treeProps.disabled(menu) && siteContext.hasMenuPermission(Number(menu.id));
}

const checkedMenuIds = computed(() =>
  treeCheckedMenuIds.value.filter(id => canPublishMenu(menuMap.value.get(id)))
);

const checkedMenus = computed(() => {
  return checkedMenuIds.value
    .map(id => menuMap.value.get(id))
    .filter((m): m is MenuNode => !!m);
});

/** 站点类型（逗号分隔多值）→ 展示文案 */
function siteModeLabel(siteMode?: string | null) {
  if (!siteMode || !siteMode.trim()) return '经典模板';
  const labels = siteMode
    .split(',')
    .map(item => item.trim().toUpperCase())
    .filter(Boolean)
    .map(mode => (mode === 'HEADLESS' ? 'Headless' : '经典模板'));
  return [...new Set(labels)].join(' / ') || '经典模板';
}

// 监听站点切换，重新加载数据
watch([()=>siteContext.currentSite?.id,requestedMenu,()=>route.query.siteId],()=>{
  treeCheckedMenuIds.value=[];
  initialChecked.value=[];
  scopeNotice.value='';
  menuTree.value=[];
  expandedKeys.value=[];
  menuMap.value.clear();
  void loadRootMenus();
},{immediate:true});

watch(serviceScope,scope=>{
  ++serviceRevision;
  serviceStatus.value=undefined;
  serviceError.value='';
  readingService.value=false;
  startingService.value=false;
  if(scope)void readCurrentService();
},{immediate:true});
onBeforeUnmount(()=>{++serviceRevision;});

async function readCurrentService(){
  const target=selectedTarget.value;
  if(!target || readingService.value)return;
  const revision=++serviceRevision, scope=serviceScope.value;
  const current=()=>revision===serviceRevision && scope===serviceScope.value;
  readingService.value=true;serviceError.value='';
  try {
    const status=await getSiteServiceStatus(target.siteId);
    if(current()){serviceStatus.value=status;return status;}
  } catch(error){
    if(current()){
      serviceStatus.value=undefined;
      serviceError.value=error instanceof Error ? error.message : '无法读取站点服务状态，请重试。';
    }
  } finally {if(current())readingService.value=false;}
}

async function startCurrentSite(){
  const target=selectedTarget.value;
  if(!target || !canStartService.value || startingService.value || readingService.value || publishing.value || serviceStatus.value?.status!=='STOPPED')return;
  const scope=serviceScope.value;
  const revision=++serviceRevision;
  const current=()=>revision===serviceRevision && scope===serviceScope.value;
  startingService.value=true;serviceError.value='';
  let refresh=false;
  try {
    const status=await startSiteService(target.siteId);
    if(!current())return;
    serviceStatus.value=status;
    if(status.status==='RUNNING')refresh=true;
    else if(!status.errorMessage)serviceError.value='站点服务启动失败，可以重试。';
  } catch(error){if(current())serviceError.value=error instanceof Error ? error.message : '站点服务启动失败，可以重试。';}
  finally {
    if(current()){
      startingService.value=false;
      if(refresh)await readCurrentService();
    }
  }
}

function returnToWorkbench(){
  const previous=router.options.history.state.back;
  const resolved=typeof previous==='string' ? router.resolve(previous) : null;
  if(resolved?.name==='site-build-workbench' && resolved.query.packageReview!=='1'){
    router.back();return;
  }
  // 直接复用工作台已保存的栏目和步骤，避免审核深链再次选中整包面板。
  void router.replace({name:'site-build-workbench'});
}

async function loadRootMenus() {
  const revision=++loadRevision;
  loading.value = true;
  try {
    const selectedSite = siteContext.currentSite;
    if (!selectedSite) {
      currentSite.value = null;
      menuTree.value = [];
      return;
    }

    // 获取站点详细信息（包含 siteMode）
    const siteData = await http.get<{ currentSite?: CurrentSiteSummary;flat?:MenuNode[] }>('/api/admin/v2/cms/menus');
    if(revision!==loadRevision)return;
    currentSite.value = {
      siteId: typeof selectedSite.id === 'number' ? selectedSite.id : Number(selectedSite.id),
      rootMenuId: selectedSite.rootMenuId,
      siteName: selectedSite.name,
      siteCode: selectedSite.code,
      globalMode: siteData.currentSite?.globalMode,
      siteMode: siteData.currentSite?.siteMode
    };

    const rootId = selectedSite.rootMenuId;
    if(requestedMenu.value) {
      const target=String(selectedSite.id)===route.query.siteId
        ? siteData.flat?.find(menu=>String(menu.id)===requestedMenu.value) : undefined;
      if(!canPublishMenu(target)){
        scopeNotice.value='当前站点未找到有权限且配置了发布路由的目标栏目。';
        return;
      }
      const item={...target,children:[],hasChildren:false};
      menuMap.value.set(item.id,item);
      menuTree.value=[item];
      initialChecked.value=[item.id];
      treeCheckedMenuIds.value=[item.id];
      return;
    }

    if (rootId) {
      // 有 rootMenuId：加载根节点及其一级子节点
      const rootNode = await http.get<MenuNode>(`/api/admin/v2/cms/menus/${rootId}`);
      const rootChildren = await http.get<MenuNode[]>('/api/admin/v2/cms/menus', {
        params: { parentId: rootId, contentOnly: true }
      });
      if(revision!==loadRevision)return;
      menuTree.value = [{ ...rootNode, children: rootChildren, hasChildren: rootChildren.length > 0 }];
      expandedKeys.value = [rootId];
      // 记录菜单映射
      menuMap.value.set(rootNode.id, rootNode);
      rootChildren.forEach(child => menuMap.value.set(child.id, child));
    } else {
      // 无 rootMenuId：加载顶级节点
      const topNodes = await http.get<MenuNode[]>('/api/admin/v2/cms/menus', {
        params: { parentId: 0, contentOnly: true }
      });
      menuTree.value = topNodes;
      expandedKeys.value = topNodes.map(n => n.id);
      // 记录菜单映射
      topNodes.forEach(node => menuMap.value.set(node.id, node));
    }
  } catch (e) {
    ElMessage.error('加载栏目树失败');
  } finally {
    if(revision===loadRevision)loading.value = false;
  }
}

async function loadChildMenus(node: { data: MenuNode }, resolve: (data: MenuNode[]) => void) {
  try {
    const parentId = node.data?.id;
    if (!parentId) {
      resolve([]);
      return;
    }
    const children = await http.get<MenuNode[]>('/api/admin/v2/cms/menus', {
      params: { parentId, contentOnly: true }
    });
    // 确保 children 是数组
    const childList = Array.isArray(children) ? children : [];
    // 记录菜单映射
    childList.forEach(child => menuMap.value.set(child.id, child));
    resolve(childList);
  } catch (error) {
    const errMsg = error instanceof Error ? error.message : '未知错误';
    ElMessage.error(`子栏目加载失败: ${errMsg}`);
    resolve([]);
  }
}

function onCheck(node: any) {
  if (!treeRef.value) return;

  // 记录当前节点及其父节点链
  const nodeData = node;
  const checkedKeys = treeRef.value.getCheckedKeys(false) || [];

  // 如果节点被取消勾选，且其父节点之前是被勾选的，则重新勾选父节点
  if (nodeData && !checkedKeys.includes(nodeData.id)) {
    // 找到父节点
    const parentNode = findParentNode(menuTree.value, nodeData.id);
    if (parentNode && canPublishMenu(menuMap.value.get(parentNode.id)) && !checkedKeys.includes(parentNode.id)) {
      treeRef.value.setChecked(parentNode.id, true, false);
    }
  }

  // 剔除无权限栏目（置灰但不可勾选）
  const rawCheckedKeys: Array<string | number> = treeRef.value.getCheckedKeys(false) || [];
  const deniedIds = rawCheckedKeys.filter(id => !siteContext.hasMenuPermission(Number(id)));
  deniedIds.forEach(id => treeRef.value.setChecked(id, false, false));

  // 树组件可能随子节点勾选而标记禁用父节点，实际发布只使用可操作的栏目。
  const updatedCheckedKeys: Array<string | number> = treeRef.value.getCheckedKeys(false) || [];
  treeCheckedMenuIds.value = updatedCheckedKeys.map(Number);
}

function findParentNode(nodes: MenuNode[], childId: number): MenuNode | null {
  for (const node of nodes) {
    if (node.children) {
      if (node.children.some(child => child.id === childId)) {
        return node;
      }
      const found = findParentNode(node.children, childId);
      if (found) return found;
    }
  }
  return null;
}

async function checkAll() {
  if (!treeRef.value || selectingAll.value) return;
  const siteId = siteContext.currentSite?.id;
  selectingAll.value = true;
  try {
    // 栏目列表不包含发布地址，必须读取详情后判断，不能把所有子栏目误判为空。
    const nodes = await Promise.all([...menuMap.value.values()].map(async node => {
      if (node.listPageUrl !== undefined || node.detailPageUrl !== undefined) return node;
      const detail = await http.get<MenuNode>(`/api/admin/v2/cms/menus/${node.id}`);
      return { ...node, listPageUrl: detail.listPageUrl, detailPageUrl: detail.detailPageUrl };
    }));
    if (siteContext.currentSite?.id !== siteId) return;
    for (const node of nodes) {
      menuMap.value.set(node.id, node);
      if (canPublishMenu(node)) {
        treeRef.value.setChecked(node.id, true, false);
      }
    }
    onCheck({});
    if (!checkedMenuIds.value.length) ElMessage.info('当前已加载栏目中没有配置发布地址且可操作的栏目');
  } catch {
    ElMessage.error('读取栏目发布地址失败，请重试');
  } finally {
    selectingAll.value = false;
  }
}

function uncheckAll() {
  if (treeRef.value) {
    treeRef.value.setCheckedKeys([], false);
    treeCheckedMenuIds.value = [];
  }
}

function removeChecked(menuId: number) {
  if (treeRef.value) {
    treeRef.value.setChecked(menuId, false, false);
    onCheck({});
  }
}

async function publish() {
  if (checkedMenuIds.value.length === 0) {
    ElMessage.warning('请至少选择一个栏目');
    return;
  }
  if(publicationBlocked.value || publishing.value)return;

  publishing.value = true;
  const menus=[...checkedMenuIds.value], scope=serviceScope.value;
  const siteAtSubmit=currentSite.value?.siteId ?? null;
  try {
    if(workbenchScope.value){
      const status=await readCurrentService();
      if(status?.status!=='RUNNING' || scope!==serviceScope.value || publicationBlocked.value ||
        checkedMenuIds.value.length!==1 || checkedMenuIds.value[0]!==menus[0])return;
    }
    const result = await http.post<{ data?: string; success?: boolean; msg?: string }>(
      '/api/admin/v2/static-publish/create-task',
      { menuIds: menus }
    );

    const taskId = result.data;
    if (taskId) {
      completedPageCount.value=null;
      publishSiteId.value=siteAtSubmit;
      progressUrl.value = `/api/admin/v2/static-publish/publish?taskId=${taskId}`;
      progressVisible.value = true;
    } else {
      ElMessage.error(result.msg || '发布任务创建失败');
    }
  } catch (e: any) {
    ElMessage.error(e.message || '发布失败');
  } finally {
    publishing.value = false;
  }
}

function closeProgress() {
  progressVisible.value = false;
  progressUrl.value = '';
}
</script>

<style scoped>
.static-publish {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.static-publish__main {
  display: flex;
  gap: var(--admin-card-gap);
  flex: 1;
  min-height: 0;
}

.static-publish__sidebar {
  width: 350px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  border: 1px solid var(--el-border-color-light);
  border-radius: var(--admin-card-radius);
  background: var(--el-bg-color);
  overflow: hidden;
}

.static-publish__sidebar-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 16px;
  background: var(--el-fill-color-light);
  border-bottom: 1px solid var(--el-border-color-light);
  font-weight: 600;
}

.static-publish__sidebar-actions {
  display: flex;
  gap: 8px;
}

.static-publish__tree-wrapper {
  flex: 1;
  overflow-y: auto;
  padding: 12px;
}

.static-publish__content {
  flex: 1;
  display: flex;
  flex-direction: column;
  border: 1px solid var(--el-border-color-light);
  border-radius: var(--admin-card-radius);
  background: var(--el-bg-color);
  padding: var(--admin-card-padding);
}

.static-publish__info h3,
.static-publish__selected h3 {
  margin: 0 0 12px;
  font-size: 16px;
}
.static-publish__service { display:grid; gap:8px; margin-top:12px; }
.static-publish__service p { margin:0; }
.static-publish__service-actions { display:flex; flex-wrap:wrap; gap:8px; align-items:center; }
.static-publish__service-actions :deep(.el-button) { margin:0; }

.static-publish__selected-count {
  margin-bottom: 12px;
  color: var(--el-text-color-secondary);
}

.static-publish__selected-count strong {
  color: var(--el-color-primary);
  font-size: 18px;
}

.static-publish__selected {
  flex: 1;
  margin-top: 20px;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.static-publish__selected-list {
  flex: 1;
  overflow-y: auto;
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-content: flex-start;
}

.static-publish__tag {
  max-width: 200px;
  overflow: hidden;
  text-overflow: ellipsis;
}

.static-publish__actions {
  margin-top: 20px;
  padding-top: 20px;
  border-top: 1px solid var(--el-border-color-light);
  text-align: center;
}

.static-publish__progress-iframe {
  width: 100%;
  height: 400px;
  border: none;
}

.static-publish__site-context {
  align-items: center;
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  padding: 12px 16px;
  border-bottom: 1px solid var(--el-border-color-light);
  background: var(--el-fill-color-lighter);
}

.static-publish__site-context span {
  color: var(--el-text-color-primary);
  font-weight: 700;
}

.static-publish__site-context em,
.static-publish__site-context strong {
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color-light);
  border-radius: 6px;
  color: var(--el-text-color-secondary);
  font-size: 13px;
  font-style: normal;
  font-weight: 700;
  padding: 4px 8px;
}

.static-publish__tree-node {
  display: flex;
  align-items: center;
  gap: 6px;
}

.static-publish__tree-node.is-denied {
  opacity: 0.45;
}

.static-publish__tree-warn {
  color: var(--el-color-warning);
}
</style>
