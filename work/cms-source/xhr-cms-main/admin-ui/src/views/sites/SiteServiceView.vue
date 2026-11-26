<template>
  <section class="site-service">
    <header class="site-service__header">
      <div>
        <p class="admin-eyebrow">运维管理</p>
        <h1>站点服务</h1>
      </div>
      <div class="site-service__actions">
        <el-button @click="openUiPortDialog">设置UI服务端口</el-button>
        <el-button @click="openModeDialog">设置服务模式</el-button>
        <el-button :loading="loading" @click="loadSites">刷新</el-button>
      </div>
    </header>

    <el-table element-loading-text="正在加载数据…" v-loading="loading" :data="sites" stripe class="site-service__table">
      <el-table-column prop="projectCode" label="站点编码" width="150" fixed="left" />
      <el-table-column prop="projectName" label="站点名称" min-width="150" />
      <el-table-column prop="siteDomain" label="站点域名" min-width="180">
        <template #default="{ row }">
          {{ row.siteDomain || '-' }}
        </template>
      </el-table-column>
      <el-table-column prop="defaultLanguage" label="站点语言" width="100">
        <template #default="{ row }">
          {{ row.defaultLanguage || '-' }}
        </template>
      </el-table-column>
      
      <el-table-column label="UI服务状态" width="150" align="center">
        <template #default="{ row }">
          <div class="ui-package-cell">
            <el-tag :type="row.hasUiPackage ? 'success' : 'info'" size="small">
              {{ row.hasUiPackage ? '已上传' : '未上传' }}
            </el-tag>
            <template v-if="row.hasUiPackage">
              <el-tag
                v-if="row.uiServiceStatus === 'running'"
                type="success"
                size="small"
                effect="dark"
              >
                运行中 ({{ getUiServicePort(row.uiServiceUrl) }})
              </el-tag>
              <el-tag
                v-else-if="row.uiServiceStatus === 'available'"
                type="warning"
                size="small"
              >
                服务未启动
              </el-tag>
              <el-tag
                v-else
                type="info"
                size="small"
              >
                服务离线
              </el-tag>
              <el-button
                v-if="row.uiServiceUrl"
                link
                type="primary"
                size="small"
                @click="openUrl(row.uiServiceUrl)"
              >
                访问
              </el-button>
            </template>
          </div>
        </template>
      </el-table-column>
      
      <el-table-column label="站点服务" width="150" align="center">
        <template #default="{ row }">
          <div class="site-service-cell">
            <el-tag
              v-if="row.siteServiceStatus === 'RUNNING'"
              type="success"
              size="small"
              effect="dark"
            >
              运行中 ({{ row.siteServicePort }}<template v-if="serviceMode === 'independent' && row.siteServicePid"> · PID {{ row.siteServicePid }}</template>)
            </el-tag>
            <el-tag
              v-else
              type="info"
              size="small"
            >
              {{ row.siteServicePort ? `已停止 (${row.siteServicePort})` : '未配置' }}
            </el-tag>
            <el-button
              v-if="row.siteServiceUrl"
              link
              type="primary"
              size="small"
              @click="openSiteServiceUrl(row.siteServiceUrl)"
            >
              访问
            </el-button>
            <!-- 路由操作按钮 -->
            <div v-if="row.siteServiceStatus === 'RUNNING'" class="route-actions">
              <el-button
                link
                type="primary"
                size="small"
                :loading="row.routeReloading"
                @click="handleReloadRoutes(row)"
              >
                重载路由
              </el-button>
              <el-button
                link
                type="primary"
                size="small"
                @click="openRoutesDialog(row)"
              >
                查看路由
              </el-button>
            </div>
          </div>
        </template>
      </el-table-column>
      
      <el-table-column label="模板" align="center">
        <template #default="{ row }">
          <el-tag :type="row.hasTemplates ? 'success' : 'info'" size="small">
            {{ row.hasTemplates ? '存在' : '不存在' }}
          </el-tag>
        </template>
      </el-table-column>
      
      <el-table-column label="服务操作" width="180" align="center" fixed="right">
        <template #default="{ row }">
          <div class="action-cell">
            <!-- UI 服务操作 -->
            <el-button
              v-if="row.hasUiPackage"
              :type="row.uiServiceStatus === 'running' ? 'warning' : 'success'"
              size="small"
              :loading="row.uiServiceLoading"
              @click="toggleUiService(row)"
            >
              {{ row.uiServiceStatus === 'running' ? '停止UI' : '启动UI' }}
            </el-button>
            <!-- 站点服务操作 -->
            <el-button
              v-if="row.hasUiPackage && row.hasTemplates"
              :type="row.siteServiceStatus === 'RUNNING' ? 'warning' : 'primary'"
              size="small"
              :loading="row.siteServiceLoading"
              @click="toggleSiteService(row)"
            >
              {{ row.siteServiceStatus === 'RUNNING' ? '停止站点' : '启动站点' }}
            </el-button>
            <el-button
              type="info"
              size="small"
              @click="openPortDialog(row)"
            >
              站点服务设置
            </el-button>
            <el-button
              v-if="serviceMode === 'independent' && row.hasUiPackage && row.hasTemplates"
              type="info"
              size="small"
              @click="openSiteParamsDialog(row)"
            >
              启动参数
            </el-button>
            <span v-if="!row.hasUiPackage" class="text-gray-400">未上传UI包</span>
          </div>
        </template>
      </el-table-column>
    </el-table>

    <!-- 预览选项对话框 -->
    <el-dialog
      v-model="previewDialogVisible"
      :title="previewSite ? `预览选项 - ${previewSite.projectName}` : '预览选项'"
      width="500px"
      destroy-on-close
    >
      <div v-if="previewLoading" class="preview-loading">
        <el-icon class="is-loading" :size="24"><Loading /></el-icon>
        <p>正在获取访问地址...</p>
      </div>
      <div v-else-if="previewError" class="preview-error">
        <el-icon :size="32"><WarningFilled /></el-icon>
        <p>{{ previewError }}</p>
      </div>
      <div v-else class="preview-options">
        <el-button type="primary" size="large" @click="openTemplatePreview">
          模板页面预览
        </el-button>
        <el-button type="success" size="large" @click="openUiPackagePreview">
          UI资源包预览
        </el-button>
      </div>
      <template #footer>
        <el-button @click="previewDialogVisible = false">关闭</el-button>
      </template>
    </el-dialog>

    <!-- 路由列表抽屉 -->
    <el-drawer
      v-model="routesDialogVisible"
      :title="routesSite ? `路由列表 - ${routesSite.projectName}` : '路由列表'"
      direction="rtl"
      size="75%"
      destroy-on-close
    >
      <template #header>
        <div class="drawer-header">
          <h3>{{ routesSite ? `路由列表 - ${routesSite.projectName}` : '路由列表' }}</h3>
          <div v-if="routesSite" class="routes-header">
            <span><strong>站点ID：</strong>{{ routesSite.siteId }}</span>
            <span><strong>站点编码：</strong>{{ routesSite.projectCode }}</span>
            <el-button
              type="primary"
              size="small"
              :loading="routesSite?.routeReloading"
              @click="handleReloadRoutes(routesSite!)"
            >
              重新加载路由
            </el-button>
          </div>
        </div>
      </template>
      <div v-if="routesLoading" class="preview-loading">
        <el-icon class="is-loading" :size="24"><Loading /></el-icon>
        <p>正在加载路由...</p>
      </div>
      <template v-else>
        <!-- 统计信息 -->
        <div class="routes-stats">
          <div class="stat-item">
            <span class="stat-label">路由数量</span>
            <span class="stat-value">{{ routeStats.totalRoutes }}</span>
          </div>
          <div class="stat-item">
            <span class="stat-label">模板数量</span>
            <span class="stat-value">{{ routeStats.totalTemplates }}</span>
          </div>
          <div class="stat-item" :class="{ 'has-conflict': routeStats.conflictRoutes.length > 0 }">
            <span class="stat-label">栏目共享路由</span>
            <span class="stat-value">{{ routeStats.conflictRoutes.length }}</span>
            <el-tooltip v-if="routeStats.conflictRoutes.length > 0" placement="top">
              <template #content>
                <div class="conflict-tooltip">
                  <div v-for="conflict in routeStats.conflictRoutes" :key="conflict.pattern" class="conflict-item">
                    <strong>{{ conflict.pattern }}</strong>
                    <div v-for="col in conflict.columns" :key="col.columnId" class="conflict-column">
                      → [{{ col.columnId }}] {{ col.columnName }}
                    </div>
                  </div>
                </div>
              </template>
              <el-icon class="conflict-icon"><WarningFilled /></el-icon>
            </el-tooltip>
          </div>
          <div class="stat-item">
            <span class="stat-label">栏目总数</span>
            <span class="stat-value">{{ routeStats.totalColumns }}</span>
          </div>
          <div class="stat-item">
            <span class="stat-label">未配置路由</span>
            <span class="stat-value">{{ routeStats.noRouteColumns }}</span>
          </div>
        </div>

        <!-- 栏目共享路由列表 -->
        <div v-if="routeStats.conflictRoutes.length > 0" class="conflict-routes-panel">
          <div
            class="conflict-header"
            role="button"
            tabindex="0"
            :aria-expanded="conflictPanelOpen"
            @click="conflictPanelOpen = !conflictPanelOpen"
            @keydown.enter.prevent="conflictPanelOpen = !conflictPanelOpen"
            @keydown.space.prevent="conflictPanelOpen = !conflictPanelOpen"
          >
            <el-icon color="#e6a23c"><WarningFilled /></el-icon>
            <span>栏目共享路由详情（同一路由配置到多个栏目）</span>
            <el-icon class="conflict-toggle">
              <ArrowDown v-if="conflictPanelOpen" />
              <ArrowRight v-else />
            </el-icon>
          </div>
          <el-collapse-transition>
            <div v-show="conflictPanelOpen" class="conflict-list">
              <div v-for="conflict in routeStats.conflictRoutes" :key="conflict.pattern" class="conflict-route-item">
                <code class="conflict-pattern">{{ conflict.pattern }}</code>
                <div class="conflict-columns">
                  <el-tag v-for="col in conflict.columns" :key="col.columnId" size="small" type="warning">
                    [{{ col.columnId }}] {{ col.columnName }}
                  </el-tag>
                </div>
              </div>
            </div>
          </el-collapse-transition>
        </div>

        <div class="routes-tree-container">
          <el-tree
            :data="routesTreeData"
            :props="{ children: 'children', label: 'label' }"
            node-key="id"
            default-expand-all
            :expand-on-click-node="false"
          >
            <template #default="{ node, data }">
              <div class="tree-node">
                <!-- 栏目节点 -->
                <template v-if="data.isColumn">
                  <div class="column-node">
                    <span class="column-id">[{{ data.columnId }}]</span>
                    <span class="column-name">{{ data.columnName }}</span>
                    <template v-if="data.hasNoRoute">
                      <el-tag type="info" size="small">未配置路由</el-tag>
                    </template>
                    <template v-else>
                      <span class="route-count">({{ data.routeCount }} 条路由)</span>
                    </template>
                  </div>
                </template>
                <!-- 路由节点 -->
                <template v-else>
                  <div class="route-node">
                    <el-tag :type="data.type === 'list' ? 'primary' : 'success'" size="small" class="route-type">
                      {{ data.type === 'list' ? '列表' : '详情' }}
                    </el-tag>
                    <el-tag :type="data.isDynamic ? 'warning' : 'info'" size="small">
                      {{ data.isDynamic ? '动态' : '静态' }}
                    </el-tag>
                    <el-tag v-if="data.isShared" type="warning" size="small" effect="dark">
                      共享
                    </el-tag>
                    <code class="route-pattern">{{ data.isDynamic ? (data.sampleUrl || data.originalUrl) : data.pattern }}</code>
                    <span v-if="data.isDynamic" class="original-url-hint">(正则: {{ data.pattern }})</span>
                    <span class="template-path">{{ data.templatePath || '未配置模板' }}</span>
                    <span v-if="data.description" class="route-note">{{ data.description }}</span>
                    <el-button
                      v-if="routeVisitPath(data) && routesSite?.siteServiceUrl"
                      link
                      type="primary"
                      size="small"
                      @click.stop="openRouteUrl(routeVisitPath(data)!)"
                    >
                      访问
                    </el-button>
                  </div>
                </template>
              </div>
            </template>
          </el-tree>
        </div>
      </template>
    </el-drawer>

    <!-- 站点服务设置对话框 -->
    <el-dialog
      v-model="portDialogVisible"
      :title="portSite ? `站点服务设置 - ${portSite.projectName}` : '站点服务设置'"
      width="520px"
      destroy-on-close
      @closed="accessPassword = ''"
    >
      <el-form label-width="100px">
        <el-form-item label="访问域名">
          <el-input v-model="accessDomain" maxlength="253" placeholder="域名或 IP，不含协议、端口和路径（可留空）" />
        </el-form-item>
        <el-form-item label="服务端口" required>
          <el-input-number
            v-model="portInput"
            :min="1024"
            :max="49151"
            :precision="0"
            :step="1"
            step-strictly
            controls-position="right"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label=" ">
          <span class="port-hint">端口范围 1024–49151；修改访问域名或服务端口会重启运行中的站点服务，单独修改资源域名无需重启。</span>
        </el-form-item>
        <el-form-item label="资源域名">
          <el-input v-model="resourceDomain" maxlength="255" placeholder="https://cdn.example.com，可带路径前缀" />
        </el-form-item>
        <el-form-item label=" ">
          <span class="port-hint">完整 HTTP(S) 资源根地址，不自动拼接站点编码。留空使用全局配置。</span>
        </el-form-item>
        <el-form-item label="是否认证" required>
          <el-radio-group v-model="accessEnabled">
            <el-radio :value="true">开启</el-radio>
            <el-radio :value="false">关闭</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="认证密码" :required="accessEnabled">
          <el-input v-model="accessPassword" type="password" show-password autocomplete="new-password"
            :disabled="!accessEnabled" maxlength="255" placeholder="开启认证时必填" />
        </el-form-item>
        <el-form-item label=" ">
          <span class="port-hint">仅保护本站 UI 静态服务和站点服务的 HTML 页面；图片、JS、CSS 等资源无需认证。</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="portDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="portSaving" @click="savePort">确定</el-button>
      </template>
    </el-dialog>

    <!-- 设置 UI 服务端口对话框 -->
    <el-dialog
      v-model="uiPortDialogVisible"
      title="设置UI服务端口"
      width="420px"
      destroy-on-close
    >
      <el-form v-loading="uiPortLoading" label-width="90px">
        <el-form-item label="服务端口">
          <el-input-number
            v-model="uiPortInput"
            :min="1024"
            :max="49151"
            :precision="0"
            :step="1"
            step-strictly
            controls-position="right"
            placeholder="请输入 1024–49151 范围内的整数"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label=" ">
          <span class="port-hint">端口范围 1024–49151，保存后 UI 服务将重启到新端口。</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="uiPortDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="uiPortSaving" @click="saveUiPort">确定</el-button>
      </template>
    </el-dialog>

    <!-- 设置服务模式对话框（服务类型 + 独立进程全局启动参数） -->
    <el-dialog
      v-model="modeDialogVisible"
      title="设置服务模式"
      width="640px"
      destroy-on-close
    >
      <el-form v-loading="modeDialogLoading" label-width="120px">
        <el-form-item label="服务模式">
          <el-radio-group v-model="dialogMode" :disabled="modeSaving">
            <el-radio value="unified">统一进程</el-radio>
            <el-radio value="independent">独立进程</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label=" ">
          <span class="port-hint">
            {{ dialogMode === 'independent'
              ? '独立进程：每个站点由独立子进程提供服务，仅开放站点服务端口，不开放管理端口'
              : '统一进程：所有站点服务运行在管理进程内（默认）' }}
          </span>
        </el-form-item>

        <el-divider content-position="left">独立进程启动参数（全局）</el-divider>
        <el-form-item label="java 路径">
          <el-input v-model="globalParamsForm.javaPath" :disabled="dialogMode !== 'independent'" :placeholder="paramsDefaults.javaPath || '留空使用当前 JVM 的 java'" />
        </el-form-item>
        <el-form-item label="jar 路径">
          <el-input v-model="globalParamsForm.jarPath" :disabled="dialogMode !== 'independent'" :placeholder="paramsDefaults.jarPath || '留空使用当前运行 jar'" />
        </el-form-item>
        <el-form-item label="工作目录">
          <el-input v-model="globalParamsForm.workDir" :disabled="dialogMode !== 'independent'" :placeholder="paramsDefaults.workDir || '留空使用 jar 同级目录'" />
        </el-form-item>
        <el-form-item label="JVM 参数">
          <el-input v-model="globalParamsForm.jvmArgs" :disabled="dialogMode !== 'independent'" placeholder="如 -Xms256m -Xmx512m" />
        </el-form-item>
        <el-form-item label="程序参数">
          <el-input v-model="globalParamsForm.programArgs" :disabled="dialogMode !== 'independent'" placeholder="如 --spring.profiles.active=prod" />
        </el-form-item>
        <el-form-item label=" ">
          <span class="port-hint">参数按空白分隔；仅在独立进程模式下生效。</span>
        </el-form-item>
        <el-form-item label=" ">
          <span class="mode-warning">切换服务模式会关闭当前站点服务并按新模式重新启动，站点服务会短暂中断。</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="modeDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="modeSaving" @click="saveModeDialog">确定</el-button>
      </template>
    </el-dialog>

    <!-- 站点启动参数覆盖对话框 -->
    <el-dialog
      v-model="siteParamsVisible"
      :title="siteParamsSite ? `站点启动参数 - ${siteParamsSite.projectName}` : '站点启动参数'"
      width="640px"
      destroy-on-close
    >
      <el-form v-loading="siteParamsLoading" label-width="120px">
        <el-form-item label="java 路径">
          <el-input v-model="siteParamsForm.javaPath" :placeholder="siteParamsPlaceholder('javaPath')" />
        </el-form-item>
        <el-form-item label="jar 路径">
          <el-input v-model="siteParamsForm.jarPath" :placeholder="siteParamsPlaceholder('jarPath')" />
        </el-form-item>
        <el-form-item label="工作目录">
          <el-input v-model="siteParamsForm.workDir" :placeholder="siteParamsPlaceholder('workDir')" />
        </el-form-item>
        <el-form-item label="JVM 参数">
          <el-input v-model="siteParamsForm.jvmArgs" :placeholder="siteParamsPlaceholder('jvmArgs')" />
        </el-form-item>
        <el-form-item label="程序参数">
          <el-input v-model="siteParamsForm.programArgs" :placeholder="siteParamsPlaceholder('programArgs')" />
        </el-form-item>
        <el-form-item label=" ">
          <span class="port-hint">留空表示沿用全局默认；仅独立进程模式下生效。</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="siteParamsVisible = false">取消</el-button>
        <el-button type="primary" :loading="siteParamsSaving" @click="saveSiteParams">确定</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<script setup lang="ts">
import { notifyResourceReferencesChanged } from '@/api/resourceReferences';
import { createTableLoader } from '@/utils/tableLoader';
import { ref, onMounted, computed } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Loading, WarningFilled, ArrowDown, ArrowRight } from '@element-plus/icons-vue';
import {
  getSiteServiceList,
  startUiService,
  stopUiService,
  getUiServiceAccessUrl,
  getUiServicePort as fetchUiServicePort,
  startSiteService,
  stopSiteService,
  fetchSiteServiceSettings,
  saveSiteServiceSettings,
  reloadSiteRoutes,
  getSiteRoutes,
  getSiteServiceMode,
  setSiteServiceMode,
  saveGlobalLaunchParams,
  getSiteLaunchParams,
  saveSiteLaunchParams,
  type SiteServiceView,
  type SiteServiceMode,
  type GlobalParamsView,
  type RouteInfoView,
  type RouteConflictView
} from '@/api/templateRecognition';
import { setUiPackagePort } from '@/api/uiPackage';

type SiteServiceInfo = SiteServiceView & {
  uiServiceLoading?: boolean;
  siteServiceLoading?: boolean;
  routeReloading?: boolean;
};

const loading = ref(false);
const sites = ref<SiteServiceInfo[]>([]);

// 预览选项对话框
const previewDialogVisible = ref(false);
const previewLoading = ref(false);
const previewError = ref<string | null>(null);
const previewSite = ref<SiteServiceInfo | null>(null);
const previewBaseUrl = ref<string | null>(null);

// 路由列表对话框
const routesDialogVisible = ref(false);
const routesLoading = ref(false);
const routesSite = ref<SiteServiceInfo | null>(null);
const routes = ref<RouteInfoView[]>([]);
const routeConflicts = ref<RouteConflictView[]>([]);
// 栏目共享路由详情面板：默认折叠
const conflictPanelOpen = ref(false);

// 站点服务设置对话框
const portDialogVisible = ref(false);
const portSaving = ref(false);
const portSite = ref<SiteServiceInfo | null>(null);
const portInput = ref(8080);
const accessDomain = ref('');
const resourceDomain = ref('');
const accessEnabled = ref(false);
const accessPassword = ref('');

// 设置 UI 服务端口对话框（UI 服务端口为全局配置，作用于所有站点）
const uiPortDialogVisible = ref(false);
const uiPortLoading = ref(false);
const uiPortSaving = ref(false);
const uiPortInput = ref<number | undefined>(undefined);

// 站点服务类型（全局，统一进程 / 独立进程）
const serviceMode = ref<SiteServiceMode>('unified');
const modeSaving = ref(false);
const paramsDefaults = ref<Record<string, string>>({});

type LaunchParamsForm = {
  javaPath: string;
  jarPath: string;
  workDir: string;
  jvmArgs: string;
  programArgs: string;
};

function emptyParamsForm(): LaunchParamsForm {
  return { javaPath: '', jarPath: '', workDir: '', jvmArgs: '', programArgs: '' };
}

// 设置服务模式对话框（服务类型 + 独立进程全局启动参数）
const modeDialogVisible = ref(false);
const modeDialogLoading = ref(false);
const dialogMode = ref<SiteServiceMode>('unified');
const globalParamsForm = ref<LaunchParamsForm>(emptyParamsForm());

// 站点级启动参数覆盖对话框
const siteParamsVisible = ref(false);
const siteParamsLoading = ref(false);
const siteParamsSaving = ref(false);
const siteParamsSite = ref<SiteServiceInfo | null>(null);
const siteParamsForm = ref<LaunchParamsForm>(emptyParamsForm());
const siteParamsGlobal = ref<GlobalParamsView | null>(null);

// 计算树形数据
type RouteTreeNode = {
  id: string;
  label: string;
  isColumn: boolean;
  columnId?: number;
  columnName?: string;
  parentId?: number;
  depth?: number;
  sort?: number;
  routeCount?: number;
  hasNoRoute?: boolean;
  pattern?: string | null;
  originalUrl?: string | null;
  type?: 'list' | 'detail' | null;
  isDynamic?: boolean;
  isShared?: boolean;
  templatePath?: string | null;
  description?: string | null;
  sampleUrl?: string | null;
  children?: RouteTreeNode[];
};

function buildRoutesTree(): RouteTreeNode[] {
  if (!routes.value.length) return [];

  // 构建共享路由 pattern 集合
  const sharedPatterns = new Set<string>();
  routeConflicts.value.forEach(conflict => {
    sharedPatterns.add(conflict.pattern);
  });

  // 按栏目分组
  const columnMap = new Map<number, RouteInfoView[]>();
  routes.value.forEach(route => {
    if (!columnMap.has(route.columnId)) {
      columnMap.set(route.columnId, []);
    }
    columnMap.get(route.columnId)!.push(route);
  });

  // 构建栏目节点，记录路由顺序
  const nodeMap = new Map<number, RouteTreeNode>();
  const columnOrder: number[] = []; // 记录栏目出现的顺序

  routes.value.forEach(route => {
    if (!nodeMap.has(route.columnId)) {
      columnOrder.push(route.columnId);
      const routeList = columnMap.get(route.columnId)!;
      // 统计已配置路由的数量（pattern 不为 null）
      const configuredRoutes = routeList.filter(r => r.pattern);
      nodeMap.set(route.columnId, {
        id: `column-${route.columnId}`,
        label: route.columnName,
        isColumn: true,
        columnId: route.columnId,
        columnName: route.columnName,
        parentId: route.parentId,
        routeCount: configuredRoutes.length,
        hasNoRoute: configuredRoutes.length === 0,
        children: routeList.filter(r => r.pattern).map((r, idx) => ({
          id: `route-${route.columnId}-${idx}`,
          label: `${r.type === 'list' ? '列表' : '详情'}: ${r.pattern}`,
          isColumn: false,
          pattern: r.pattern,
          originalUrl: r.originalUrl,
          type: r.type,
          isDynamic: r.isDynamic,
          isShared: r.pattern ? sharedPatterns.has(r.pattern) : false,
          templatePath: r.templatePath,
          description: r.description,
          sampleUrl: r.sampleUrl
        }))
      });
    }
  });

  // 构建树形结构 - 根据 parentId 建立父子关系
  const rootNodes: RouteTreeNode[] = [];

  // 按照 columnOrder 顺序处理，保持原始顺序
  columnOrder.forEach(columnId => {
    const node = nodeMap.get(columnId)!;

    // 判断是否为根节点：parentId === 0 或 parentId 指向的栏目不在当前路由列表中
    if (node.parentId === 0 || !nodeMap.has(node.parentId!)) {
      rootNodes.push(node);
    } else {
      // 作为子栏目添加到父栏目下
      const parentNode = nodeMap.get(node.parentId!);
      if (parentNode) {
        if (!parentNode.children) {
          parentNode.children = [];
        }
        // 先展示路由配置，再展示子栏目（避免配置信息被子栏目隔离太远）
        parentNode.children.push(node);
      }
    }
  });

  return rootNodes;
}

const routesTreeData = computed(() => buildRoutesTree());

// 路由统计信息
type RouteStats = {
  totalRoutes: number;
  totalTemplates: number;
  totalColumns: number;
  noRouteColumns: number;
  conflictRoutes: RouteConflictView[];
};

const routeStats = computed<RouteStats>(() => {
  const configuredRoutes = routes.value.filter(r => r.pattern);

  // 统计模板数量（去重）
  const templates = new Set(configuredRoutes.filter(r => r.templatePath).map(r => r.templatePath));

  // 统计栏目
  const columnIds = new Set(routes.value.map(r => r.columnId));
  const noRouteColumns = routes.value.filter(r => !r.pattern).length;

  // 使用后端返回的冲突信息
  return {
    totalRoutes: configuredRoutes.length,
    totalTemplates: templates.size,
    totalColumns: columnIds.size,
    noRouteColumns,
    conflictRoutes: routeConflicts.value
  };
});

const loadSitesLoader = createTableLoader(loading);

async function loadSites() {
  const request = loadSitesLoader.begin(() => {
    sites.value = [];
  });
  try {
    const loadedRows = await getSiteServiceList();
    if (!request.isCurrent()) return;
    sites.value = loadedRows;
  } catch (error) {
    if (!request.isCurrent()) return;
    ElMessage.error(error instanceof Error ? error.message : '站点列表加载失败');
  } finally {
    request.finish();
  }
}

async function toggleUiService(site: SiteServiceInfo) {
  site.uiServiceLoading = true;
  try {
    if (site.uiServiceStatus === 'running') {
      await stopUiService(site.siteId);
      site.uiServiceStatus = 'available';
      ElMessage.success('UI 服务已停止');
    } else {
      const result = await startUiService(site.siteId);
      site.uiServiceStatus = result.status as SiteServiceInfo['uiServiceStatus'];
      if (result.status === 'running') {
        ElMessage.success('UI 服务已启动');
      } else {
        ElMessage.warning(result.message || '启动失败');
      }
    }
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '操作失败');
  } finally {
    site.uiServiceLoading = false;
  }
}

async function toggleSiteService(site: SiteServiceInfo) {
  site.siteServiceLoading = true;
  try {
    if (site.siteServiceStatus === 'RUNNING') {
      const result = await stopSiteService(site.siteId);
      site.siteServiceStatus = 'STOPPED';
      site.siteServiceUrl = null;
      ElMessage.success('站点服务已停止');
    } else {
      const result = await startSiteService(site.siteId);
      if (result.status === 'RUNNING') {
        site.siteServiceStatus = 'RUNNING';
        site.siteServicePort = result.port;
        site.siteServiceUrl = result.accessUrl;
        ElMessage.success('站点服务已启动');
      } else {
        ElMessage.warning(result.errorMessage || '启动失败');
      }
    }
    // 刷新站点列表获取最新状态
    await loadSites();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '操作失败');
  } finally {
    site.siteServiceLoading = false;
  }
}

function openSiteServiceUrl(url: string) {
  window.open(url, '_blank');
}

async function openPortDialog(site: SiteServiceInfo) {
  try {
    const settings = await fetchSiteServiceSettings(site.siteId);
    portSite.value = site;
    portInput.value = settings.siteServicePort ?? 8080;
    accessDomain.value = settings.siteDomain ?? '';
    resourceDomain.value = settings.siteServiceIp ?? '';
    accessEnabled.value = settings.previewAccessEnabled;
    accessPassword.value = settings.password ?? '';
    portDialogVisible.value = true;
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '读取站点服务设置失败');
  }
}

async function savePort() {
  if (!portSite.value) return;
  const port = portInput.value;
  if (!Number.isInteger(port) || port < 1024 || port > 49151) {
    ElMessage.warning('端口必须在 1024–49151 范围内');
    return;
  }
  if (accessEnabled.value && (!accessPassword.value.trim() || accessPassword.value.length > 255)) {
    ElMessage.warning('开启认证时密码必填，且不能超过 255 个字符');
    return;
  }
  portSaving.value = true;
  try {
    const result = await saveSiteServiceSettings(portSite.value.siteId, {
      siteDomain: accessDomain.value.trim(),
      siteServiceIp: resourceDomain.value.trim(),
      siteServicePort: port,
      previewAccessEnabled: accessEnabled.value,
      password: accessPassword.value
    });
    notifyResourceReferencesChanged();
    if (result.errorMessage) {
      ElMessage.warning(result.errorMessage);
      await loadSites();
      return;
    }
    ElMessage.success('站点服务设置已保存');
    accessPassword.value = '';
    portDialogVisible.value = false;
    await loadSites();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '保存站点服务设置失败');
  } finally {
    portSaving.value = false;
  }
}

async function openUiPortDialog() {
  uiPortDialogVisible.value = true;
  uiPortLoading.value = true;
  try {
    uiPortInput.value = await fetchUiServicePort();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '获取UI服务端口失败');
  } finally {
    uiPortLoading.value = false;
  }
}

async function saveUiPort() {
  const port = uiPortInput.value;
  if (port == null || !Number.isInteger(port) || port < 1024 || port > 49151) {
    ElMessage.warning('端口必须在 1024–49151 范围内');
    return;
  }
  const site = sites.value[0];
  if (!site) {
    ElMessage.warning('暂无站点，无法设置 UI 服务端口');
    return;
  }
  const wasRunning = sites.value.some((s) => s.uiServiceStatus === 'running');
  uiPortSaving.value = true;
  try {
    await setUiPackagePort(site.siteId, port);
    uiPortDialogVisible.value = false;
    ElMessage.success(wasRunning ? 'UI 服务端口已更新并重启' : 'UI 服务端口已更新');
    await loadSites();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '站点服务设置失败');
  } finally {
    uiPortSaving.value = false;
  }
}

function openUrl(url: string) {
  window.open(url, '_blank');
}

function getUiServicePort(url: string | null): string {
  if (!url) return '-';
  try {
    const match = url.match(/:(\d+)\//);
    return match ? match[1] : '-';
  } catch {
    return '-';
  }
}

async function openPreviewDialog(site: SiteServiceInfo) {
  previewSite.value = site;
  previewDialogVisible.value = true;
  previewLoading.value = true;
  previewError.value = null;
  previewBaseUrl.value = null;

  try {
    const result = await getUiServiceAccessUrl(site.siteId);
    console.log('getUiServiceAccessUrl result:', result);

    if (result.errorMessage) {
      previewError.value = result.errorMessage;
    } else {
      previewBaseUrl.value = result.baseUrl;
      console.log('previewBaseUrl:', previewBaseUrl.value);
    }
  } catch (error) {
    console.error('getUiServiceAccessUrl error:', error);
    previewError.value = error instanceof Error ? error.message : '获取访问地址失败';
  } finally {
    previewLoading.value = false;
  }
}

function openTemplatePreview() {
  if (!previewSite.value) return;
  const templateUrl = `/api/admin/v2/site-service/${previewSite.value.siteId}/preview`;
  window.open(templateUrl, '_blank');
}

function openUiPackagePreview() {
  if (!previewBaseUrl.value) {
    ElMessage.warning('无法获取访问地址，请确保服务已启动');
    return;
  }
  window.open(previewBaseUrl.value, '_blank');
}

async function handleReloadRoutes(site: SiteServiceInfo) {
  site.routeReloading = true;
  try {
    const success = await reloadSiteRoutes(site.siteId);
    if (success) {
      ElMessage.success('路由已重新加载');
      // 如果路由列表对话框已打开，刷新路由列表
      if (routesDialogVisible.value && routesSite.value?.siteId === site.siteId) {
        await refreshRoutesList(site.siteId);
      }
    } else {
      ElMessage.warning('重新加载失败，服务可能未运行');
    }
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '重新加载失败');
  } finally {
    site.routeReloading = false;
  }
}

async function refreshRoutesList(siteId: number) {
  routesLoading.value = true;
  try {
    const result = await getSiteRoutes(siteId);
    routes.value = result.routes;
    routeConflicts.value = result.conflicts;
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '刷新路由列表失败');
  } finally {
    routesLoading.value = false;
  }
}

async function openRoutesDialog(site: SiteServiceInfo) {
  routesSite.value = site;
  routesDialogVisible.value = true;
  routes.value = [];
  routeConflicts.value = [];
  await refreshRoutesList(site.siteId);
}

/** 可访问地址：动态路由用后端解析好的 sampleUrl，静态路由用 pattern。 */
function routeVisitPath(node?: RouteTreeNode | null): string | null {
  if (!node) return null;
  if (node.isDynamic) return node.sampleUrl || null;
  return node.pattern || null;
}

function openRouteUrl(pattern: string) {
  const base = routesSite.value?.siteServiceUrl;
  if (!base) {
    ElMessage.warning('站点服务未运行');
    return;
  }
  // 处理根路由
  if (pattern === '/') {
    window.open(base, '_blank');
    return;
  }
  // 静态路由传入的是正则，去掉 ^ $ 并还原转义；动态路由传入的是已解析的真实地址。
  const path = pattern
    .replace(/^\^/, '')
    .replace(/\$$/, '')
    .replace(/\\\./g, '.')
    .replace(/\\\//g, '/');

  const url = base.replace(/\/$/, '') + (path.startsWith('/') ? path : '/' + path);
  window.open(url, '_blank');
}

async function loadMode() {
  try {
    const result = await getSiteServiceMode();
    serviceMode.value = result.mode;
    paramsDefaults.value = result.defaults || {};
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '获取站点服务类型失败');
  }
}

async function openModeDialog() {
  modeDialogVisible.value = true;
  modeDialogLoading.value = true;
  try {
    const result = await getSiteServiceMode();
    serviceMode.value = result.mode;
    dialogMode.value = result.mode;
    paramsDefaults.value = result.defaults || {};
    globalParamsForm.value = {
      javaPath: result.globalParams.javaPath || '',
      jarPath: result.globalParams.jarPath || '',
      workDir: result.globalParams.workDir || '',
      jvmArgs: result.globalParams.jvmArgs || '',
      programArgs: result.globalParams.programArgs || ''
    };
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '获取服务模式配置失败');
  } finally {
    modeDialogLoading.value = false;
  }
}

async function saveModeDialog() {
  const modeChanged = dialogMode.value !== serviceMode.value;
  if (modeChanged) {
    try {
      await ElMessageBox.confirm(
        '会关闭当前站点服务，重新按新的服务模式启动。站点服务会短暂中断。',
        '变更服务模式',
        { type: 'warning', confirmButtonText: '确定变更', cancelButtonText: '取消' }
      );
    } catch {
      return;
    }
  }

  modeSaving.value = true;
  try {
    let restarted: number | null = null;
    let failed: number | null = null;
    if (modeChanged) {
      const modeResult = await setSiteServiceMode(dialogMode.value);
      serviceMode.value = modeResult.mode;
      restarted = modeResult.restarted;
      failed = modeResult.failed;
    }

    const paramsResult = await saveGlobalLaunchParams(globalParamsForm.value);
    paramsDefaults.value = paramsResult.defaults || {};
    modeDialogVisible.value = false;

    if (!modeChanged) {
      ElMessage.success('启动参数已保存');
    } else if (failed && failed > 0) {
      ElMessage.warning(`服务模式已切换；已按新模式重启 ${restarted ?? 0} 个站点服务，${failed} 个启动失败，请查看列表状态`);
    } else {
      ElMessage.success(`服务模式已切换，已按新模式重启 ${restarted ?? 0} 个站点服务`);
    }
    await loadSites();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '保存失败');
  } finally {
    modeSaving.value = false;
  }
}

async function openSiteParamsDialog(site: SiteServiceInfo) {
  siteParamsSite.value = site;
  siteParamsVisible.value = true;
  siteParamsLoading.value = true;
  try {
    const result = await getSiteLaunchParams(site.siteId);
    siteParamsGlobal.value = result.globalParams;
    paramsDefaults.value = result.defaults || {};
    siteParamsForm.value = {
      javaPath: result.override.javaPath || '',
      jarPath: result.override.jarPath || '',
      workDir: result.override.workDir || '',
      jvmArgs: result.override.jvmArgs || '',
      programArgs: result.override.programArgs || ''
    };
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '获取站点启动参数失败');
  } finally {
    siteParamsLoading.value = false;
  }
}

function siteParamsPlaceholder(field: keyof LaunchParamsForm): string {
  const global = siteParamsGlobal.value;
  const defaults = paramsDefaults.value;
  if (field === 'javaPath') return global?.javaPath || defaults.javaPath || '留空使用当前 JVM 的 java';
  if (field === 'jarPath') return global?.jarPath || defaults.jarPath || '留空使用当前运行 jar';
  if (field === 'workDir') return global?.workDir || defaults.workDir || '留空使用 jar 同级目录';
  if (field === 'jvmArgs') return global?.jvmArgs || '留空沿用全局';
  return global?.programArgs || '留空沿用全局';
}

async function saveSiteParams() {
  if (!siteParamsSite.value) return;
  siteParamsSaving.value = true;
  try {
    await saveSiteLaunchParams(siteParamsSite.value.siteId, siteParamsForm.value);
    siteParamsVisible.value = false;
    ElMessage.success('站点启动参数已保存');
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '保存站点启动参数失败');
  } finally {
    siteParamsSaving.value = false;
  }
}

onMounted(() => {
  loadMode();
  loadSites();
});
</script>

<style scoped>
.site-service {
  min-width: 0;
  padding: 0;
}

.site-service__header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: var(--admin-card-gap);
}

.site-service__header h1 {
  margin: 0;
  font-size: 22px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.site-service__header .admin-eyebrow {
  margin: 0 0 4px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.site-service__actions {
  display: flex;
  gap: 10px;
}

.site-service__table {
  width: 100%;
  background: #fff;
  border-radius: 8px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.05);
}

.ui-package-cell {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
}

.site-service-cell {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
}

.route-actions {
  display: flex;
  gap: 4px;
  margin-top: 4px;
}

.route-pattern {
  font-size: 12px;
  padding: 2px 6px;
  background: #f5f5f5;
  border-radius: 4px;
}

.action-cell {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 12px;
  align-items: center;
  justify-content: flex-start;
}

.action-cell :deep(.el-button) {
  flex-shrink: 0;
  margin-left: 0;
}

.port-hint {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.mode-warning {
  color: var(--el-color-danger);
  font-size: 13px;
  line-height: 1.5;
}

/* 预览选项样式 */
.preview-loading,
.preview-error {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 40px 0;
  color: var(--el-text-color-secondary);
}

.preview-error {
  color: var(--el-color-danger);
}

.preview-options {
  display: flex;
  gap: 20px;
  padding: 20px 0;
}

.preview-options .el-button {
  flex: 1;
}

.drawer-header {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.drawer-header h3 {
  margin: 0;
  font-size: 18px;
}

.routes-header {
  display: flex;
  gap: 20px;
  align-items: center;
  font-size: 14px;
  color: var(--el-text-color-regular);
}

.routes-header span {
  white-space: nowrap;
}

.routes-tree-container {
  padding: 10px 0;
}

.tree-node {
  flex: 1;
  display: flex;
  align-items: center;
  min-width: 0;
}

.column-node {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 0;
}

.column-id {
  color: #909399;
  font-size: 12px;
}

.column-name {
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.route-count {
  color: #909399;
  font-size: 12px;
  margin-left: 8px;
}

.route-node {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 6px 12px;
  background: #fafafa;
  border-radius: 6px;
  border: 1px solid #eee;
  margin: 4px 0;
}

.route-type {
  min-width: 40px;
  text-align: center;
}

.route-pattern {
  font-size: 12px;
  padding: 2px 8px;
  background: #f0f2f5;
  border-radius: 4px;
  color: #409eff;
  font-family: 'Monaco', 'Menlo', monospace;
}

.original-url-hint {
  color: #909399;
  font-size: 12px;
  font-style: italic;
}

.template-path {
  color: #606266;
  font-size: 13px;
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.route-note {
  color: #909399;
  font-size: 12px;
  font-style: italic;
}

.text-gray-400 {
  color: #9ca3af;
}

.text-xs {
  font-size: 12px;
}

/* 路由统计样式 */
.routes-stats {
  display: flex;
  gap: 24px;
  padding: 16px 20px;
  background: #f5f7fa;
  border-radius: 8px;
  margin-bottom: 16px;
}

.stat-item {
  display: flex;
  align-items: center;
  gap: 8px;
}

.stat-label {
  color: var(--el-text-color-secondary);
  font-size: 14px;
}

.stat-value {
  font-size: 18px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.stat-item.has-conflict .stat-value {
  color: var(--el-color-warning);
}

.conflict-icon {
  color: var(--el-color-warning);
  cursor: pointer;
}

.conflict-tooltip {
  max-width: 400px;
}

.conflict-item {
  margin-bottom: 12px;
  padding-bottom: 12px;
  border-bottom: 1px solid #eee;
}

.conflict-item:last-child {
  margin-bottom: 0;
  padding-bottom: 0;
  border-bottom: none;
}

.conflict-column {
  margin-left: 12px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

/* 冲突路由面板 */
.conflict-routes-panel {
  background: #fdf6ec;
  border: 1px solid #faecd8;
  border-radius: var(--admin-card-radius);
  padding: var(--admin-card-padding-compact);
  margin-bottom: 16px;
}

.conflict-header {
  display: flex;
  align-items: center;
  gap: 8px;
  color: #e6a23c;
  font-weight: 500;
  cursor: pointer;
  user-select: none;
}

.conflict-header .conflict-toggle {
  margin-left: auto;
  color: #e6a23c;
}

.conflict-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin-top: 12px;
}

.conflict-route-item {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 12px;
  background: #fff;
  border-radius: 6px;
}

.conflict-pattern {
  font-family: 'Monaco', 'Menlo', monospace;
  font-size: 13px;
  color: #e6a23c;
  background: #fef0e6;
  padding: 4px 8px;
  border-radius: 4px;
}

.conflict-columns {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
</style>
