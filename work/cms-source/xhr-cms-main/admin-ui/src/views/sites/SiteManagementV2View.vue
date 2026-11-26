<template>
  <section class="site-v2">
    <header class="site-v2__header">
      <div>
        <p class="admin-eyebrow">站点智能管理</p>
        <h1>站点管理</h1>
      </div>
      <div class="site-v2__actions">
        <el-button v-if="AiGenerationSettingsDialog && isPlatformAdmin" @click="aiSettingsVisible=true">系统AI配置</el-button>
        <el-button :loading="loading" @click="loadSites">刷新</el-button>
        <el-button v-if="isPlatformAdmin" type="primary" @click="openDrawer()">新增</el-button>
      </div>
    </header>

    <div class="site-v2__body">
      <main class="site-v2__list">
        <el-table element-loading-text="正在加载数据…"
          v-loading="loading"
          :data="siteTableTree"
          row-key="siteId"
          default-expand-all
          :tree-props="{ children: 'children' }"
          stripe
          class="site-v2__table"
        >
          <el-table-column prop="projectCode" label="站点编码" width="180" fixed="left" />
          <el-table-column prop="projectName" label="站点名称" min-width="150" />
          <el-table-column label="上级站点" width="140">
            <template #default="{ row }">{{ parentSiteName(row) }}</template>
          </el-table-column>
          <el-table-column prop="siteDomain" label="域名" min-width="180">
            <template #default="{ row }">
              {{ row.siteDomain || '-' }}
            </template>
          </el-table-column>
          <el-table-column prop="siteMode" label="站点类型" width="180">
            <template #default="{ row }">
              <el-tag
                v-for="mode in siteModeList(row.siteMode)"
                :key="mode"
                :type="mode === 'HEADLESS' ? 'success' : 'info'"
                size="small"
                class="site-v2__mode-tag"
              >
                {{ siteModeLabel(mode) }}
              </el-tag>
              <span v-if="!siteModeList(row.siteMode).length">-</span>
            </template>
          </el-table-column>
          <el-table-column prop="defaultLanguage" label="站点语言" width="100">
            <template #default="{ row }">
              {{ languageLabel(row.defaultLanguage) }}
            </template>
          </el-table-column>
          <el-table-column prop="siteDescription" label="描述" min-width="150">
            <template #default="{ row }">
              {{ row.siteDescription || '-' }}
            </template>
          </el-table-column>
          <el-table-column label="UIPackage" width="120" align="center">
            <template #default="{ row }">
              <el-tag v-if="row.uiPackageStatus === 'RUNNING'" type="success" size="small">运行中</el-tag>
              <el-tag v-else-if="row.uiPackageStatus === 'UPLOADED' || row.uiPackageStatus === 'STOPPED'" type="info" size="small">已上传</el-tag>
              <el-tag v-else-if="row.uiPackageStatus === 'NOT_UPLOADED'" type="warning" size="small">未上传</el-tag>
              <span v-else>-</span>
            </template>
          </el-table-column>
          <el-table-column label="创建时间" width="170" align="center">
            <template #default="{ row }">{{ formatDateTime(row.createTime) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="250" align="center" fixed="right">
            <template #default="{ row }">
              <div class="site-v2__row-primary">
                <el-button size="small" link type="primary" @click="openDrawer(row)">编辑</el-button>
                <el-button size="small" link type="primary" @click="goToMenus(row)">站点栏目</el-button>
                <el-button size="small" v-if="isPlatformAdmin" link type="success" @click="openI18nDrawer(row)">生成多语言站点</el-button>
              </div>
              <div class="site-v2__row-secondary">
                <el-button size="small" v-if="row.uiPackageStatus === 'UPLOADED' || row.uiPackageStatus === 'STOPPED'" link type="success" @click="handleQuickStart(row)">启动</el-button>
                <el-button size="small" v-if="row.uiPackageStatus === 'RUNNING'" link type="warning" @click="handleQuickStop(row)">停止</el-button>
                <el-button size="small" v-if="row.uiPackageStatus === 'RUNNING'" link type="primary" @click="openAccessUrl(row)">访问UI</el-button>
                <el-button size="small" link type="primary" @click="openUiPackageDrawer(row)">UIPackage</el-button>
                <el-button size="small" v-if="isPlatformAdmin" link type="danger" @click="handleDelete(row)">删除</el-button>
              </div>
            </template>
          </el-table-column>
        </el-table>
      </main>
    </div>

    <el-drawer
      v-model="drawerVisible"
      :title="editingSite ? '编辑站点' : '新增站点'"
      direction="rtl"
      size="480px"
    >
      <el-form
        ref="formRef"
        :model="form"
        :rules="formRules"
        label-position="top"
        class="site-v2__form"
      >
        <el-form-item label="站点名称" prop="siteName">
          <el-input v-model="form.siteName" placeholder="请输入站点名称" />
        </el-form-item>
        <el-form-item label="站点编码" prop="siteCode">
          <el-input v-model="form.siteCode" placeholder="请输入站点编码" />
        </el-form-item>
        <el-form-item label="父级节点">
          <el-select v-model="form.parentSiteId" placeholder="选择父级站点（可选）" clearable filterable style="width: 100%">
            <el-option
              v-for="s in parentSiteOptions"
              :key="s.siteId"
              :label="s.projectName"
              :value="s.siteId"
            />
          </el-select>
        </el-form-item>
        <el-form-item v-if="!editingSite" label="站点域名">
          <el-input v-model="form.siteDomain" placeholder="请输入公网域名" />
        </el-form-item>
        <el-form-item v-if="!editingSite" label="服务端口">
          <el-input-number
            v-model="form.siteServicePort"
            :min="1024"
            :max="49151"
            :precision="0"
            :controls="false"
            placeholder="请输入 1024–49151 范围内的整数"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="站点语言">
          <el-select v-model="form.defaultLanguage" placeholder="请选择站点语言" style="width: 100%">
            <el-option
              v-for="lang in i18nLanguageOptions"
              :key="lang.languageId"
              :label="lang.languageName"
              :value="lang.languageCode"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="站点类型" prop="siteMode">
          <el-checkbox-group v-model="form.siteMode">
            <el-checkbox v-for="option in SITE_MODE_OPTIONS" :key="option.value" :value="option.value">
              {{ option.label }}
            </el-checkbox>
          </el-checkbox-group>
        </el-form-item>
        <el-form-item v-if="form.siteMode.includes('HEADLESS') && editingSite" label="Headless 认证">
          <div v-loading="headlessLoading" class="site-v2__headless-auth">
            <div class="site-v2__headless-row">
              <span class="site-v2__headless-label">appKey</span>
              <el-input :model-value="headlessAuth?.appKey ?? '未生成'" readonly />
              <el-button
                :icon="CopyDocument"
                :disabled="!headlessAuth?.appKey"
                @click="copyText(headlessAuth?.appKey ?? '')"
              >
                复制
              </el-button>
            </div>
            <div class="site-v2__headless-row">
              <span class="site-v2__headless-label">appSecret</span>
              <el-input :model-value="maskedAppSecret" readonly />
              <el-button :icon="appSecretVisible ? Hide : View" @click="appSecretVisible = !appSecretVisible">
                {{ appSecretVisible ? '隐藏' : '显示' }}
              </el-button>
              <el-button
                :icon="CopyDocument"
                :disabled="!headlessAuth?.appSecret"
                @click="copyText(headlessAuth?.appSecret ?? '')"
              >
                复制
              </el-button>
            </div>
            <div class="site-v2__headless-row">
              <span class="site-v2__headless-label">启用</span>
              <el-switch
                :model-value="headlessAuth?.enabled ?? false"
                :disabled="!headlessAuth?.hasAuth"
                @update:model-value="(v: string | number | boolean) => toggleHeadlessEnabled(Boolean(v))"
              />
              <el-button type="warning" :loading="headlessRotating" @click="rotateHeadless">
                {{ headlessAuth?.hasAuth ? '轮换' : '生成' }}
              </el-button>
              <span v-if="headlessAuth?.rotateTime" class="site-v2__headless-hint">
                最近轮换 {{ headlessAuth.rotateTime }}
              </span>
            </div>
            <div class="site-v2__headless-row">
              <span class="site-v2__headless-label">备注</span>
              <el-input v-model="headlessRemark" placeholder="接入方/用途说明" />
              <el-button
                type="primary"
                :loading="headlessSaving"
                :disabled="!headlessAuth?.hasAuth"
                @click="saveHeadlessRemark"
              >
                保存
              </el-button>
            </div>
            <div class="site-v2__headless-hint">
              该站点同时支持经典模板与 Headless；appKey/appSecret 用于 Headless 接口鉴权，独立于后台登录。
            </div>
          </div>
        </el-form-item>
        <el-alert
          v-else-if="form.siteMode.includes('HEADLESS')"
          class="site-v2__mode-hint"
          type="info"
          :closable="false"
          show-icon
          title="保存后将自动生成 Headless 接入认证（appKey/appSecret），可在编辑站点时查看与轮换。"
        />
        <el-form-item label="描述">
          <el-input v-model="form.siteDescription" type="textarea" :rows="3" placeholder="请输入站点描述" />
        </el-form-item>
        <el-form-item label="资源域名" label-width="160px">
          <el-input v-model="form.siteServiceIp" maxlength="255" placeholder="完整 HTTP(S) 地址，不自动拼接站点编码，可留空" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="drawerVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="handleSave">保存</el-button>
      </template>
    </el-drawer>

    <el-drawer
      v-model="uiPackageDrawerVisible"
      title="UIPackage 管理"
      direction="rtl"
      size="480px"
    >
      <div v-if="uiPackageLoading" class="ui-package-loading">
        <el-icon class="is-loading"><Loading /></el-icon>
        <span>加载中...</span>
      </div>
      <div v-else class="ui-package-content">
        <el-button v-if="developerWorkbenchEnabled && isPlatformAdmin && uiPackageStatus?.version" type="primary"
          :disabled="uiPackageOperating" @click="continuePackageAnalysis">继续分析此站页面包</el-button>
        <!-- 未上传状态 -->
        <div v-if="uiPackageStatus?.status === 'NOT_UPLOADED'" class="ui-package-upload">
          <el-upload
            ref="uiPackageUploadRef"
            :auto-upload="false"
            :show-file-list="false"
            accept=".zip"
            :on-change="handleUiPackageSelect"
            drag
          >
            <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
            <div class="el-upload__text">拖拽 ZIP 包到此处或 <em>点击上传</em></div>
            <template #tip>
              <div class="el-upload__tip">支持上传包含 HTML/CSS/JS 的 ZIP 包</div>
            </template>
          </el-upload>
        </div>

        <!-- 已上传/已停止状态 -->
        <div v-else-if="uiPackageStatus?.status === 'UPLOADED' || uiPackageStatus?.status === 'STOPPED'" class="ui-package-info">
          <p class="ui-package-filename">{{ uiPackageStatus?.packageName }}</p>
          <div class="ui-package-actions">
            <el-button type="primary" :loading="uiPackageOperating" @click="handleStartUiPackage">启动服务</el-button>
            <el-button :loading="uiPackageOperating" @click="handleOpenPortSetting">端口设置</el-button>
            <el-button type="danger" :loading="uiPackageOperating" @click="handleDeleteUiPackage">删除</el-button>
          </div>
          <!-- 升级 UIPackage：版本号不变，同名文件覆盖 -->
          <el-divider content-position="left">升级UIPackage包</el-divider>
          <div class="ui-package-upgrade">
            <el-upload
              :auto-upload="false"
              :show-file-list="false"
              accept=".zip"
              :disabled="uiPackageOperating"
              :on-change="handleUiPackageUpgradeSelect"
            >
              <el-button :loading="uiPackageOperating">选择 ZIP 升级</el-button>
            </el-upload>
            <p class="ui-package-upgrade__tip">版本号不变，新增文件追加，同名文件覆盖，未包含的旧文件保留。</p>
          </div>
          <!-- 重新上传 UIPackage：生成新版本，旧包整体替换 -->
          <el-divider content-position="left">重新UIPackage上传包</el-divider>
          <div class="ui-package-upgrade">
            <el-upload
              :auto-upload="false"
              :show-file-list="false"
              accept=".zip"
              :disabled="uiPackageOperating"
              :on-change="handleUiPackageReuploadSelect"
            >
              <el-button :loading="uiPackageOperating">选择 ZIP 重新上传</el-button>
            </el-upload>
            <p class="ui-package-upgrade__tip">生成新版本，之前上传的包整体被替换。</p>
          </div>
        </div>

        <!-- 运行中状态 -->
        <div v-else-if="uiPackageStatus?.status === 'RUNNING'" class="ui-package-info">
          <p class="ui-package-filename">{{ uiPackageStatus?.packageName }}</p>
          <p class="ui-package-url">
            访问地址：<a :href="uiPackageStatus?.accessUrl" target="_blank">{{ uiPackageStatus?.accessUrl }}</a>
          </p>
          <div class="ui-package-actions">
            <el-button type="warning" :loading="uiPackageOperating" @click="handleStopUiPackage">停止服务</el-button>
            <el-button :loading="uiPackageOperating" @click="handleOpenPortSetting">端口设置</el-button>
          </div>
          <!-- 升级 UIPackage：版本号不变，同名文件覆盖 -->
          <el-divider content-position="left">升级UIPackage包</el-divider>
          <div class="ui-package-upgrade">
            <el-upload
              :auto-upload="false"
              :show-file-list="false"
              accept=".zip"
              :disabled="uiPackageOperating"
              :on-change="handleUiPackageUpgradeSelect"
            >
              <el-button :loading="uiPackageOperating">选择 ZIP 升级</el-button>
            </el-upload>
            <p class="ui-package-upgrade__tip">版本号不变，新增文件追加，同名文件覆盖，未包含的旧文件保留。</p>
          </div>
          <!-- 重新上传 UIPackage：生成新版本，旧包整体替换 -->
          <el-divider content-position="left">重新UIPackage上传包</el-divider>
          <div class="ui-package-upgrade">
            <el-upload
              :auto-upload="false"
              :show-file-list="false"
              accept=".zip"
              :disabled="uiPackageOperating"
              :on-change="handleUiPackageReuploadSelect"
            >
              <el-button :loading="uiPackageOperating">选择 ZIP 重新上传</el-button>
            </el-upload>
            <p class="ui-package-upgrade__tip">生成新版本，之前上传的包整体被替换。</p>
          </div>
        </div>

        <!-- 上传记录 -->
        <div v-if="uploadRecords.length" class="ui-package-records">
          <el-divider content-position="left">上传记录</el-divider>
          <el-table element-loading-text="正在加载数据…" v-loading="uploadRecordsLoading" :data="uploadRecords" size="small">
            <el-table-column prop="version" label="版本" min-width="130" show-overflow-tooltip />
            <el-table-column label="类型" width="84" align="center">
              <template #default="{ row }">
                {{ row.uploadType === 'UPGRADE' ? '升级' : row.uploadType === 'REUPLOAD' ? '重新上传' : '上传' }}
              </template>
            </el-table-column>
            <el-table-column prop="uploadUser" label="上传人" width="90" show-overflow-tooltip />
            <el-table-column prop="uploadTime" label="上传时间" width="150" />
          </el-table>
        </div>
      </div>
    </el-drawer>

    <!-- UIPackage 端口设置对话框 -->
    <el-dialog
      v-model="portDialogVisible"
      title="设置 UIPackage 端口"
      width="360px"
      append-to-body
    >
      <p class="ui-package-port-tip">服务将重启到新端口，端口范围 1024–49151。</p>
      <el-input-number
        v-model="portInput"
        :min="1024"
        :max="49151"
        :precision="0"
        :controls="false"
        placeholder="请输入 1024–49151 范围内的整数"
        style="width: 100%"
      />
      <template #footer>
        <el-button @click="portDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="portSetting" @click="handleConfirmPortSetting">确定</el-button>
      </template>
    </el-dialog>

    <!-- 生成多语言站点抽屉 -->
    <el-drawer
      v-model="i18nDrawerVisible"
      title="生成多语言站点"
      direction="rtl"
      size="960px"
      @close="handleI18nDrawerClose"
    >
      <div class="i18n-drawer">
        <el-steps :active="i18nStep" finish-status="success" simple class="i18n-drawer__steps">
          <el-step title="选择语言" />
          <el-step title="选择栏目" />
        </el-steps>

        <!-- 第一步：选择语言 -->
        <div v-show="i18nStep === 0" class="i18n-drawer__content">
          <p class="i18n-drawer__source">
            源站点：<strong>{{ i18nSourceSite?.projectName }}</strong>
            ({{ languageLabel(i18nSourceSite?.defaultLanguage) }})
          </p>
          <el-form label-position="top">
            <el-form-item label="目标语言" required>
              <el-select v-model="i18nTargetLanguage" placeholder="请选择目标语言" style="width: 100%" @change="handleI18nLanguageChange">
                <el-option
                  v-for="lang in i18nLanguageOptions"
                  :key="lang.languageId"
                  :label="lang.languageName"
                  :value="lang.languageCode"
                  :disabled="lang.languageCode === i18nSourceSite?.defaultLanguage"
                />
              </el-select>
            </el-form-item>
            <el-form-item label="站点名称" required>
              <el-input v-model="i18nForm.siteName" placeholder="请输入站点名称" />
            </el-form-item>
            <el-form-item label="站点编码" required>
              <el-input v-model="i18nForm.siteCode" placeholder="请输入站点编码" />
            </el-form-item>
            <el-form-item label="域名">
              <el-input v-model="i18nForm.siteDomain" placeholder="请输入域名" />
            </el-form-item>
            <el-form-item label="服务端口">
              <el-input-number
                v-model="i18nForm.siteServicePort"
                :min="1024"
                :max="49151"
                :precision="0"
                :controls="false"
                placeholder="请输入 1024–49151 范围内的整数"
                style="width: 100%"
              />
            </el-form-item>
            <el-form-item label="资源域名">
              <el-input v-model="i18nForm.siteServiceIp" maxlength="255" placeholder="完整 HTTP(S) 地址，不自动拼接站点编码，可留空" />
            </el-form-item>
          </el-form>
        </div>

        <!-- 第二步：选择栏目 -->
        <div v-show="i18nStep === 1" class="i18n-drawer__content">
          <!-- AI 服务状态 -->
          <div class="ai-service-status">
            <div class="ai-service-status__header">
              <div class="ai-service-status__heading">
                <el-tag size="small">LLM翻译服务</el-tag>
                <el-tag v-if="aiServiceStatus?.status === 'running'" type="success" size="small">运行中</el-tag>
                <el-tag v-else-if="aiServiceStatus?.status === 'starting'" type="warning" size="small">启动中...</el-tag>
                <el-tag v-else-if="aiServiceStatus?.status === 'stopped'" type="info" size="small">已停止</el-tag>
                <el-tag v-else-if="aiServiceStatus?.status === 'failed'" type="danger" size="small">启动失败</el-tag>
                <el-tag v-else type="info" size="small">未知状态</el-tag>
              </div>
              <div class="ai-service-status__actions">
                <el-button
                  v-if="translateStreamLog"
                  size="small"
                  @click="translateStreamLogVisible = true"
                >
                  翻译记录
                </el-button>
                <el-button
                  v-if="translatePrompt"
                  size="small"
                  @click="translatePromptVisible = true"
                >
                  prompt
                </el-button>
                <el-button
                  v-if="translateRawResponse"
                  size="small"
                  @click="translateRawResponseVisible = true"
                >
                  原始响应
                </el-button>
                <el-button size="small" :loading="aiServiceLoading" @click="refreshAiServiceStatus">
                  刷新
                </el-button>
                <el-button
                  v-if="aiServiceStatus?.status === 'stopped' || aiServiceStatus?.status === 'failed'"
                  size="small"
                  type="primary"
                  :loading="aiServiceOperating"
                  @click="handleStartAiService"
                >
                  检查配置
                </el-button>

              </div>
            </div>
            <div v-if="aiServiceStatus?.error" class="ai-service-status__error">
              {{ aiServiceStatus.error }}
            </div>
          </div>

          <!-- 翻译记录（翻译中显示） -->
          <div v-if="translatingPreview" class="ai-stream-log">
            <div class="ai-stream-log__header">
              <span>翻译记录</span>
              <div class="ai-stream-log__header-actions">
                <el-button size="small" link @click="translateStreamLog = ''">清除</el-button>
              </div>
            </div>
            <p>关闭面板仅停止等待，不会撤销已发出的模型调用。</p>
            <pre ref="translateStreamLogRef" class="ai-stream-log__content">{{ translateStreamLog || '等待 AI 响应...' }}</pre>
          </div>

          <div v-if="i18nMenuTreeLoading" class="i18n-drawer__loading">
            <el-icon class="is-loading"><Loading /></el-icon>
            <span>加载栏目树...</span>
          </div>
          <div v-else class="i18n-drawer__dual-tree">
            <!-- 左侧：源语言栏目树 -->
            <div class="i18n-drawer__tree-panel">
              <div class="i18n-drawer__tree-header">
                <span>{{ languageLabel(i18nSourceSite?.defaultLanguage) }}</span>
                <div class="i18n-drawer__tree-actions">
                  <el-button size="small" @click="handleSelectAllMenus">全选</el-button>
                  <el-button size="small" @click="handleClearMenuSelection">清空</el-button>
                </div>
              </div>
              <el-tree
                ref="menuTreeRef"
                :data="i18nMenuTree"
                :props="{ children: 'children', label: 'className' }"
                show-checkbox
                node-key="id"
                default-expand-all
                highlight-current
                class="i18n-drawer__tree"
                @check="handleMenuCheck"
              />
            </div>

            <!-- 右侧：翻译后的栏目树 -->
            <div class="i18n-drawer__tree-panel">
              <div class="i18n-drawer__tree-header">
                <span>{{ languageLabel(i18nTargetLanguage) || '目标语言' }}</span>
                <el-button
                  v-if="selectedMenuCount > 0 && (aiServiceStatus?.status === 'running' || aiServiceStatus?.status === 'available')"
                  size="small"
                  type="primary"
                  :loading="translatingPreview"
                  @click="handleTranslatePreview"
                >
                  翻译预览
                </el-button>
              </div>
              <div v-if="translatingPreview" class="i18n-drawer__tree-loading">
                <el-icon class="is-loading"><Loading /></el-icon>
                <span>翻译中...</span>
              </div>
              <el-tree
                v-else
                :data="translatedMenuTree"
                :props="{ children: 'children', label: 'translatedName' }"
                node-key="id"
                default-expand-all
                :expand-on-click-node="false"
                class="i18n-drawer__tree i18n-drawer__tree--translated"
              >
                <template #default="{ data }">
                  <span class="i18n-tree-node" @dblclick="startEditTranslatedName(data)">
                    <el-input
                      v-if="editingMenuId === data.id"
                      ref="editNameInputRef"
                      v-model="editingMenuName"
                      size="small"
                      class="i18n-tree-node__edit"
                      @click.stop
                      @keyup.enter="commitEditTranslatedName(data)"
                      @keyup.esc="cancelEditTranslatedName"
                      @blur="commitEditTranslatedName(data)"
                    />
                    <template v-else>
                      <span
                        class="i18n-tree-node__translated"
                        :title="canEditTranslatedName(data.id) ? '双击修改译文' : undefined"
                      >{{ data.translatedName || data.className }}</span>
                      <span v-if="data.originalName" class="i18n-tree-node__original">({{ data.originalName }})</span>
                    </template>
                  </span>
                </template>
              </el-tree>
            </div>
          </div>
        </div>

        <div class="i18n-drawer__footer">
          <span v-if="i18nStep === 0" class="i18n-drawer__hint">填写目标语言、站点名称和编码后，继续选择栏目。</span>
          <el-button v-if="i18nStep > 0" @click="i18nStep--">上一步</el-button>
          <el-button v-if="i18nStep < 1" type="primary" :disabled="!i18nTargetLanguage || !i18nForm.siteName || !i18nForm.siteCode" @click="handleI18nNextStep">
            下一步
          </el-button>
          <el-button v-if="i18nStep === 1" type="primary" :loading="i18nGenerating" @click="handleGenerateI18nSite">
            生成
          </el-button>
        </div>
      </div>
    </el-drawer>

    <!-- 翻译记录对话框 -->
    <el-dialog
      v-model="translateStreamLogVisible"
      title="翻译记录"
      width="800px"
      append-to-body
    >
      <pre class="stream-log-display">{{ translateStreamLog || '无日志内容' }}</pre>
    </el-dialog>

    <!-- Prompt 对话框 -->
    <el-dialog
      v-model="translatePromptVisible"
      title="Prompt"
      width="900px"
      append-to-body
    >
      <pre class="stream-log-display">{{ translatePrompt || '无内容' }}</pre>
    </el-dialog>

    <!-- 原始响应对话框 -->
    <el-dialog
      v-model="translateRawResponseVisible"
      title="原始响应"
      width="900px"
      append-to-body
    >
      <pre class="stream-log-display">{{ translateRawResponse || '无内容' }}</pre>
    </el-dialog>
    <component :is="AiGenerationSettingsDialog" v-if="aiSettingsVisible && AiGenerationSettingsDialog && isPlatformAdmin" v-model="aiSettingsVisible" />
  </section>
</template>

<script setup lang="ts">
import { notifyResourceReferencesChanged } from '@/api/resourceReferences';
import { createTableLoader } from '@/utils/tableLoader';
import { defineAsyncComponent, computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue';
import { useRouter, useRoute } from 'vue-router';
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus';
import { UploadFilled, Loading, CopyDocument, Hide, View } from '@element-plus/icons-vue';
import {
  listSites,
  createSite,
  updateSite,
  deleteSite,
  getSiteMenuTree,
  generateI18nSite,
  translateMenusPreview,
  checkSiteServicePort,
  getHeadlessAuth,
  rotateHeadlessAuth,
  updateHeadlessAuth,
  type SiteV2Item,
  type CreateSiteRequest,
  type UpdateSiteRequest,
  type MenuTreeNode,
  type GenerateI18nSiteRequest,
  type TranslatedMenuPreview,
  type SiteHeadlessAuthView
} from '@/api/siteManagementV2';
import {
  getUiPackageStatus,
  uploadUiPackage,
  upgradeUiPackage,
  reuploadUiPackage,
  listUiPackageUploadRecords,
  deleteUiPackage,
  startUiPackage,
  stopUiPackage,
  setUiPackagePort,
  type UiPackageStatus,
  type UiPackageUploadRecord
} from '@/api/uiPackage';
import { listLanguages } from '@/api/languages';
import { useSiteContextStore } from '@/stores/siteContext';
import type { UploadFile } from 'element-plus';
import {
  getAiStatus,
  type AiModelStatus
} from '@/api/aiGenerationService';


const AiGenerationSettingsDialog=import.meta.env.VITE_DEVELOPER_WORKBENCH==='true'?defineAsyncComponent(()=>import('@/components/sites/AiGenerationSettingsDialog.vue')):null;
const aiSettingsVisible=ref(false);

const siteContext = useSiteContextStore();

/** 新增/删除站点仅平台管理员（超管或平台组）可用，与后端 requireSuperAdmin 口径一致 */
const developerWorkbenchEnabled = import.meta.env.VITE_DEVELOPER_WORKBENCH === 'true';
const isPlatformAdmin = computed(() => siteContext.isPlatformAdmin);

interface SiteWithUiPackage extends SiteV2Item {
  uiPackageStatus?: 'NOT_UPLOADED' | 'UPLOADED' | 'STOPPED' | 'RUNNING';
}

/** 后端返回 ISO 格式（2026-09-20T13:40:24），展示为 2026-09-20 13:40:24 */
function formatDateTime(value?: string | null): string {
  return value ? value.replace('T', ' ').slice(0, 19) : '-';
}

const router = useRouter();
const route = useRoute();
const loading = ref(false);
const saving = ref(false);
const sites = ref<SiteWithUiPackage[]>([]);
const pendingI18nOpen = ref(false);
const drawerVisible = ref(false);
const editingSite = ref<SiteV2Item | null>(null);
const formRef = ref<FormInstance>();

const uiPackageDrawerVisible = ref(false);
const uiPackageLoading = ref(false);
const uiPackageOperating = ref(false);
const currentUiPackageSite = ref<SiteWithUiPackage | null>(null);
const uiPackageStatus = ref<UiPackageStatus | null>(null);
const uiPackageUploadRef = ref();
const uploadRecords = ref<UiPackageUploadRecord[]>([]);
const uploadRecordsLoading = ref(false);
const uploadRecordsLoader = createTableLoader(uploadRecordsLoading);

// UIPackage 端口设置
const portDialogVisible = ref(false);
const portInput = ref<number | undefined>(undefined);
const portSetting = ref(false);

// 多语言站点生成
const i18nDrawerVisible = ref(false);
const i18nStep = ref(0);
const i18nGenerating = ref(false);
const i18nSourceSite = ref<SiteWithUiPackage | null>(null);
const i18nTargetLanguage = ref('');
const i18nLanguageOptions = ref<LanguageOption[]>([]);
const i18nForm = reactive({
  siteName: '',
  siteCode: '',
  siteDomain: '',
  siteServicePort: undefined as number | undefined,
  siteServiceIp: ''
});
const i18nMenuTree = ref<MenuTreeNode[]>([]);
const i18nMenuTreeLoading = ref(false);
const menuTreeRef = ref<InstanceType<typeof import('element-plus')['ElTree']>>();

// 翻译预览
interface TranslatedTreeNode {
  id: number;
  parentId: number;
  className: string;
  translatedName: string;
  originalName?: string;
  children: TranslatedTreeNode[];
}
const translatedMenuTree = ref<TranslatedTreeNode[]>([]);
const translatingPreview = ref(false);
// 双击编辑译文
const editingMenuId = ref<number | null>(null);
const editingMenuName = ref('');
const editNameInputRef = ref<InstanceType<typeof import('element-plus')['ElInput']>>();
const selectedMenuCount = ref(0);

// 翻译记录
const translateStreamLog = ref('');
const translateStreamLogRef = ref<HTMLElement | null>(null);
const translateStreamLogVisible = ref(false);

// 流式日志可能非常长，超出上限时截断更早内容，避免字符串/DOM 过大导致浏览器界面崩溃
const MAX_STREAM_LOG_LENGTH = 200_000;
const STREAM_LOG_TRUNCATE_PREFIX = '…（日志过长，已截断更早内容）…\n';
function capStreamLog(value: string): string {
  if (value.length <= MAX_STREAM_LOG_LENGTH) return value;
  const keep = Math.max(0, MAX_STREAM_LOG_LENGTH - STREAM_LOG_TRUNCATE_PREFIX.length);
  const tail = value.slice(-keep);
  const firstNewline = tail.indexOf('\n');
  return STREAM_LOG_TRUNCATE_PREFIX + (firstNewline >= 0 ? tail.slice(firstNewline + 1) : tail);
}
watch(translateStreamLog, (value) => {
  const capped = capStreamLog(value);
  if (capped !== value) translateStreamLog.value = capped;
});
let translatePreviewAbortController: AbortController | null = null;
let translatePreviewGeneration = 0;

// 翻译 prompt / 原始响应
const translatePrompt = ref('');
const translateRawResponse = ref('');
const translatePromptVisible = ref(false);
const translateRawResponseVisible = ref(false);
// 翻译预览结果，生成站点时复用，避免后台重复调用 AI
const translatePreviewItems = ref<TranslatedMenuPreview[]>([]);

// 自动滚动到日志底部
watch(translateStreamLog, () => {
  if (translateStreamLogRef.value) {
    translateStreamLogRef.value.scrollTop = translateStreamLogRef.value.scrollHeight;
  }
});

// AI 服务状态
const aiServiceStatus = ref<AiModelStatus | null>(null);
const aiServiceLoading = ref(false);
const aiServiceOperating = ref(false);

interface LanguageOption {
  languageId: number;
  languageCode: string;
  languageName: string;
}

const form = reactive({
  siteName: '',
  siteCode: '',
  siteDomain: '',
  siteServicePort: 8080 as number | undefined,
  defaultLanguage: 'zh-CN',
  siteMode: ['CMS_MANAGED'] as string[],
  siteDescription: '',
  siteServiceIp: '',
  parentSiteId: null as number | null
});

const formRules: FormRules = {
  siteName: [{ required: true, message: '请输入站点名称', trigger: 'blur' }],
  siteCode: [{ required: true, message: '请输入站点编码', trigger: 'blur' }],
  siteMode: [{ required: true, type: 'array', min: 1, message: '请至少选择一种站点类型', trigger: 'change' }]
};

/** 站点类型可选项（可多选：同一站点可同时支持经典模板与 Headless） */
const SITE_MODE_OPTIONS = [
  { value: 'CMS_MANAGED', label: '经典模板' },
  { value: 'HEADLESS', label: 'Headless' }
];

/** 站点类型（后端为逗号分隔多值）→ 数组 */
function siteModeList(mode?: string | null): string[] {
  if (!mode) return [];
  return mode
    .split(',')
    .map(item => item.trim().toUpperCase())
    .filter(Boolean);
}

/** 站点类型是否包含指定类型 */
function hasSiteMode(mode: string | null | undefined, target: string): boolean {
  return siteModeList(mode).includes(target);
}

function siteModeLabel(mode: string): string {
  return SITE_MODE_OPTIONS.find(option => option.value === mode)?.label ?? mode;
}

function languageLabel(code?: string): string {
  if (!code) return '中文';
  const lang = i18nLanguageOptions.value.find(l => l.languageCode === code);
  return lang?.languageName || code;
}

onMounted(() => {
  loadSites();
  loadLanguageOptions();
  // 工作台「新增站点」入口：带 ?create=1 跳转时自动弹出新增站点抽屉（仅平台管理员）
  if (route.query.create && isPlatformAdmin.value) {
    openDrawer();
  }
  // 工作台「多语言管理」入口：带 ?i18n=1 跳转时待站点列表加载后自动打开生成多语言站点抽屉（仅平台管理员）
  if (route.query.i18n && isPlatformAdmin.value) {
    pendingI18nOpen.value = true;
  }
});

async function loadLanguageOptions() {
  try {
    // 站点语言下拉取自「系统设置 - 语言配置」的数据
    const items = await listLanguages();
    i18nLanguageOptions.value = items.map((item) => ({
      languageId: item.id,
      languageCode: item.languageCode,
      languageName: item.languageName
    }));
  } catch (error) {
    i18nLanguageOptions.value = [];
    ElMessage.error(error instanceof Error ? error.message : '加载语言列表失败');
  }
}

const loadSitesLoader = createTableLoader(loading);

async function loadSites() {
  const request = loadSitesLoader.begin(() => {
    sites.value = [];
  });
  try {
    const siteList = await listSites();
    if (!request.isCurrent()) return;
    sites.value = siteList.map((site) => ({
      ...site,
      uiPackageStatus: site.uiPackageStatus as SiteWithUiPackage['uiPackageStatus']
    }));
    if (pendingI18nOpen.value) {
      pendingI18nOpen.value = false;
      openI18nForContext();
    }
  } catch (error) {
    if (!request.isCurrent()) return;
    ElMessage.error(error instanceof Error ? error.message : '站点列表加载失败');
  } finally {
    request.finish();
  }
}

function parentSiteName(row: { parentSiteId?: number }): string {
  if (row.parentSiteId == null) return '-';
  const parent = sites.value.find(s => s.siteId === Number(row.parentSiteId));
  return parent ? parent.projectName : String(row.parentSiteId);
}

interface SiteTableNode extends SiteWithUiPackage {
  children: SiteTableNode[];
}

/** 按上级站点组树，根节点和同级子节点均按创建时间升序；缺少时间的排在最后。 */
const siteTableTree = computed<SiteTableNode[]>(() => {
  const sorted = [...sites.value].sort((a, b) => {
    const aTime = a.createTime?.trim().replace(' ', 'T') || '';
    const bTime = b.createTime?.trim().replace(' ', 'T') || '';
    if (!aTime && bTime) return 1;
    if (aTime && !bTime) return -1;
    if (aTime !== bTime) return aTime < bTime ? -1 : 1;
    return a.projectCode.localeCompare(b.projectCode, 'zh-CN');
  });
  const nodes = new Map<number, SiteTableNode>(
    sorted.map(site => [Number(site.siteId), { ...site, children: [] }])
  );
  const roots: SiteTableNode[] = [];
  for (const node of nodes.values()) {
    const parent = nodes.get(Number(node.parentSiteId));
    // 上级不在可访问列表中或层级形成环时，仍作为根节点展示，避免丢失站点。
    const ancestors = new Set<number>([Number(node.siteId)]);
    let ancestor = parent;
    while (ancestor && !ancestors.has(Number(ancestor.siteId))) {
      ancestors.add(Number(ancestor.siteId));
      ancestor = nodes.get(Number(ancestor.parentSiteId));
    }
    if (parent && !ancestor) {
      parent.children.push(node);
    } else {
      roots.push(node);
    }
  }
  return roots;
});

/** 打开「生成多语言站点」抽屉，优先取当前上下文站点，否则取列表第一个站点 */
function openI18nForContext() {
  const currentSiteId = siteContext.currentSite?.id;
  const target = sites.value.find((s) => s.siteId === currentSiteId) ?? sites.value[0];
  if (target) {
    openI18nDrawer(target);
  } else {
    ElMessage.warning('当前没有可用站点，请先创建站点');
  }
}

/** 可选择为父级的站点（排除当前编辑自身） */
const parentSiteOptions = computed(() =>
  sites.value.filter(s => !editingSite.value || s.siteId !== editingSite.value.siteId)
);

/** 新增或生成站点时，默认填入端口池（8080-9000）内第一个空闲端口 */
async function suggestServicePort(): Promise<number | null> {
  try {
    const check = await checkSiteServicePort(8080);
    return check.suggestedPort ?? null;
  } catch {
    return null;
  }
}

function openDrawer(site?: SiteV2Item) {
  editingSite.value = site ?? null;
  form.parentSiteId = site?.parentSiteId ?? null;
  form.siteName = site?.projectName ?? '';
  form.siteCode = site?.projectCode ?? '';
  form.siteDomain = site?.siteDomain ?? '';
  form.siteServicePort = site?.siteServicePort ?? 8080;
  form.defaultLanguage = site?.defaultLanguage ?? 'zh-CN';
  form.siteMode = siteModeList(site?.siteMode);
  if (!form.siteMode.length) form.siteMode = ['CMS_MANAGED'];
  form.siteDescription = site?.siteDescription ?? '';
  form.siteServiceIp = site?.siteServiceIp ?? '';
  resetHeadlessAuth();
  if (site && hasSiteMode(site.siteMode, 'HEADLESS')) {
    void loadHeadlessAuth(site.siteId);
  }
  drawerVisible.value = true;
  if (!site) {
    void suggestServicePort().then((port) => {
      if (port) form.siteServicePort = port;
    });
  }
}

// ==================== Headless 接入认证（每站点一对 appKey/appSecret） ====================

const headlessAuth = ref<SiteHeadlessAuthView | null>(null);
const headlessLoading = ref(false);
const headlessRotating = ref(false);
const headlessSaving = ref(false);
const appSecretVisible = ref(false);
const headlessRemark = ref('');

const maskedAppSecret = computed(() => {
  const value = headlessAuth.value?.appSecret;
  if (!value) return '未生成';
  return appSecretVisible.value ? value : `${value.slice(0, 4)}${'*'.repeat(Math.max(0, value.length - 8))}${value.slice(-4)}`;
});

function resetHeadlessAuth() {
  headlessAuth.value = null;
  appSecretVisible.value = false;
  headlessRemark.value = '';
}

async function loadHeadlessAuth(siteId: number) {
  headlessLoading.value = true;
  try {
    const auth = await getHeadlessAuth(siteId);
    headlessAuth.value = auth;
    headlessRemark.value = auth.remark ?? '';
  } catch {
    headlessAuth.value = null;
  } finally {
    headlessLoading.value = false;
  }
}

async function rotateHeadless() {
  const siteId = editingSite.value?.siteId;
  if (!siteId) return;
  if (headlessAuth.value?.hasAuth) {
    try {
      await ElMessageBox.confirm(
        '轮换后旧 appKey/appSecret 立即失效，已接入的 Headless 系统需同步更新。是否继续？',
        '轮换 Headless 认证',
        { type: 'warning', confirmButtonText: '确认轮换', cancelButtonText: '取消' }
      );
    } catch {
      return;
    }
  }
  headlessRotating.value = true;
  try {
    headlessAuth.value = await rotateHeadlessAuth(siteId);
    appSecretVisible.value = true;
    ElMessage.success('已生成新的 appKey/appSecret');
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '轮换失败');
  } finally {
    headlessRotating.value = false;
  }
}

async function toggleHeadlessEnabled(value: boolean) {
  const siteId = editingSite.value?.siteId;
  if (!siteId) return;
  try {
    headlessAuth.value = await updateHeadlessAuth(siteId, { enabled: value });
    ElMessage.success(value ? '已启用' : '已停用');
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '更新失败');
    await loadHeadlessAuth(siteId);
  }
}

async function saveHeadlessRemark() {
  const siteId = editingSite.value?.siteId;
  if (!siteId) return;
  headlessSaving.value = true;
  try {
    headlessAuth.value = await updateHeadlessAuth(siteId, { remark: headlessRemark.value });
    ElMessage.success('备注已保存');
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '保存备注失败');
  } finally {
    headlessSaving.value = false;
  }
}

async function copyText(text: string) {
  if (!text) return;
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text);
    } else {
      const textarea = document.createElement('textarea');
      textarea.value = text;
      document.body.appendChild(textarea);
      textarea.select();
      document.execCommand('copy');
      document.body.removeChild(textarea);
    }
    ElMessage.success('已复制');
  } catch {
    ElMessage.warning('复制失败，请手动选择复制');
  }
}

async function handleSave() {
  const valid = await formRef.value?.validate().catch(() => false);
  if (!valid) return;

  saving.value = true;
  try {
    // 保存前校验服务端口是否已被其它站点占用
    if (!editingSite.value && form.siteServicePort) {
      const check = await checkSiteServicePort(form.siteServicePort);
      if (!check.available) {
        const hint = check.suggestedPort ? `，建议改用 ${check.suggestedPort}` : '';
        ElMessage.error(`${check.message || `端口 ${form.siteServicePort} 已被占用`}${hint}`);
        return;
      }
    }
    if (editingSite.value) {
      const request: UpdateSiteRequest = {
        siteName: form.siteName,
        siteCode: form.siteCode,
        siteMode: form.siteMode.join(','),
        siteDescription: form.siteDescription || undefined,
        defaultLanguage: form.defaultLanguage,
        siteServiceIp: form.siteServiceIp.trim(),
        parentSiteId: form.parentSiteId ?? undefined
      };
      await updateSite(editingSite.value.siteId, request);
      notifyResourceReferencesChanged();
      ElMessage.success('站点已更新');
    } else {
      const request: CreateSiteRequest = {
        siteName: form.siteName,
        siteCode: form.siteCode,
        siteDomain: form.siteDomain || undefined,
        siteMode: form.siteMode.join(','),
        siteDescription: form.siteDescription || undefined,
        defaultLanguage: form.defaultLanguage,
        siteServiceIp: form.siteServiceIp.trim(),
        parentSiteId: form.parentSiteId ?? undefined,
        siteServicePort: form.siteServicePort ?? undefined
      };
      const created = await createSite(request);
      if (developerWorkbenchEnabled && route.query.workbench === '1') {
        drawerVisible.value = false;
        await router.replace({path:route.path});
        try {
          await siteContext.switchSite(created.siteId);
          await openUiPackageDrawer({...created,uiPackageStatus:'NOT_UPLOADED'});
        } catch { ElMessage.warning('站点已创建。可从该站行打开 UIPackage 上传页面包。'); }
      }
      ElMessage.success('站点已创建');
    }
    drawerVisible.value = false;
    await loadSites();
    // 刷新全局站点上下文，更新顶部站点选择器
    await siteContext.loadSites();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '保存失败');
  } finally {
    saving.value = false;
  }
}

async function handleDelete(site: SiteV2Item) {
  try {
    await ElMessageBox.confirm(`确认删除站点"${site.projectName}"？`, '删除站点', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消'
    });
    await deleteSite(site.siteId);
    ElMessage.success('站点已删除');
    await loadSites();
    // 刷新全局站点上下文，更新顶部站点选择器
    await siteContext.loadSites();
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') {
      ElMessage.error(error instanceof Error ? error.message : '删除失败');
    }
  }
}

async function goToMenus(site: SiteV2Item) {
  try {
    await siteContext.switchSite(site.siteId);
    router.push('/admin/v2/cms/menus');
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '切换站点失败');
  }
}

async function continuePackageAnalysis() {
  const site = currentUiPackageSite.value;
  const version = uiPackageStatus.value?.version;
  if (!site || !version || uiPackageOperating.value) return;
  uiPackageOperating.value = true;
  try {
    await siteContext.switchSite(site.siteId);
    if (String(siteContext.currentSite?.id) !== String(site.siteId)) throw new Error('站点已切换，请重新选择页面包');
    await router.push({name: 'site-build-workbench', query: {siteId: String(site.siteId), uploadedVersion: version}});
  } catch (cause) {
    ElMessage.error(cause instanceof Error ? cause.message : '无法进入本站工作台');
  } finally {
    uiPackageOperating.value = false;
  }
}

async function openUiPackageDrawer(site: SiteWithUiPackage) {
  currentUiPackageSite.value = site;
  uiPackageDrawerVisible.value = true;
  uiPackageLoading.value = true;
  uiPackageStatus.value = null;
  uploadRecords.value = [];
  try {
    const status = await getUiPackageStatus(site.siteId);
    if (currentUiPackageSite.value?.siteId === site.siteId) uiPackageStatus.value = status;
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '获取 UIPackage 状态失败');
  } finally {
    if (currentUiPackageSite.value?.siteId === site.siteId) uiPackageLoading.value = false;
  }
  await loadUploadRecords(site.siteId);
}

async function loadUploadRecords(siteId: number) {
  if (siteId !== currentUiPackageSite.value?.siteId) return;
  const request = uploadRecordsLoader.begin(() => {
    uploadRecords.value = [];
  });
  try {
    const data = await listUiPackageUploadRecords(siteId);
    if (!request.isCurrent() || siteId !== currentUiPackageSite.value?.siteId) return;
    uploadRecords.value = data;
  } catch {
    if (request.isCurrent()) uploadRecords.value = [];
  } finally {
    request.finish();
  }
}

async function handleUiPackageSelect(file: UploadFile) {
  if (!file.raw || !currentUiPackageSite.value) return;
  uiPackageOperating.value = true;
  try {
    const siteId = currentUiPackageSite.value.siteId;
    const status = await uploadUiPackage(siteId, file.raw);
    if (currentUiPackageSite.value?.siteId !== siteId) return;
    uiPackageStatus.value = status;
    ElMessage.success('UIPackage 上传成功');
    await loadUploadRecords(currentUiPackageSite.value.siteId);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '上传失败');
  } finally {
    uiPackageOperating.value = false;
  }
}

async function handleUiPackageUpgradeSelect(file: UploadFile) {
  if (!file.raw || !currentUiPackageSite.value) return;
  uiPackageOperating.value = true;
  try {
    uiPackageStatus.value = await upgradeUiPackage(currentUiPackageSite.value.siteId, file.raw);
    ElMessage.success('UIPackage 升级成功');
    await loadUploadRecords(currentUiPackageSite.value.siteId);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '升级失败');
  } finally {
    uiPackageOperating.value = false;
  }
}

async function handleUiPackageReuploadSelect(file: UploadFile) {
  if (!file.raw || !currentUiPackageSite.value) return;
  uiPackageOperating.value = true;
  try {
    uiPackageStatus.value = await reuploadUiPackage(currentUiPackageSite.value.siteId, file.raw);
    ElMessage.success('UIPackage 重新上传成功');
    await loadUploadRecords(currentUiPackageSite.value.siteId);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '重新上传失败');
  } finally {
    uiPackageOperating.value = false;
  }
}

async function handleStartUiPackage() {
  if (!currentUiPackageSite.value) return;
  uiPackageOperating.value = true;
  try {
    uiPackageStatus.value = await startUiPackage(currentUiPackageSite.value.siteId);
    ElMessage.success('服务已启动');
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '启动失败');
  } finally {
    uiPackageOperating.value = false;
  }
}

async function handleStopUiPackage() {
  if (!currentUiPackageSite.value) return;
  uiPackageOperating.value = true;
  try {
    uiPackageStatus.value = await stopUiPackage(currentUiPackageSite.value.siteId);
    ElMessage.success('服务已停止');
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '停止失败');
  } finally {
    uiPackageOperating.value = false;
  }
}

function handleOpenPortSetting() {
  const url = uiPackageStatus.value?.accessUrl;
  let currentPort: number | undefined;
  if (url) {
    try {
      currentPort = Number(new URL(url).port) || undefined;
    } catch {
      currentPort = undefined;
    }
  }
  portInput.value = currentPort;
  portDialogVisible.value = true;
}

async function handleConfirmPortSetting() {
  if (!currentUiPackageSite.value) return;
  const port = portInput.value;
  if (port == null || !Number.isInteger(port) || port < 1024 || port > 49151) {
    ElMessage.warning('请输入 1024–49151 范围内的端口号');
    return;
  }
  portSetting.value = true;
  try {
    const status = await setUiPackagePort(currentUiPackageSite.value.siteId, port);
    uiPackageStatus.value = status;
    ElMessage.success('端口已设置');
    portDialogVisible.value = false;
    const siteId = currentUiPackageSite.value.siteId;
    sites.value = sites.value.map((s) =>
      s.siteId === siteId ? { ...s, uiPackageStatus: status.status } : s
    );
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '端口设置失败');
  } finally {
    portSetting.value = false;
  }
}

async function handleDeleteUiPackage() {
  if (!currentUiPackageSite.value) return;
  try {
    await ElMessageBox.confirm('确认删除 UIPackage？', '删除确认', { type: 'warning' });
  } catch {
    return;
  }
  uiPackageOperating.value = true;
  try {
    await deleteUiPackage(currentUiPackageSite.value.siteId);
    uiPackageStatus.value = { status: 'NOT_UPLOADED' };
    ElMessage.success('UIPackage 已删除');
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '删除失败');
  } finally {
    uiPackageOperating.value = false;
  }
}

async function handleQuickStart(site: SiteWithUiPackage) {
  try {
    const status = await startUiPackage(site.siteId);
    sites.value = sites.value.map((s) =>
      s.siteId === site.siteId ? { ...s, uiPackageStatus: status.status } : s
    );
    ElMessage.success('服务已启动');
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '启动失败');
  }
}

async function handleQuickStop(site: SiteWithUiPackage) {
  try {
    const status = await stopUiPackage(site.siteId);
    sites.value = sites.value.map((s) =>
      s.siteId === site.siteId ? { ...s, uiPackageStatus: status.status } : s
    );
    ElMessage.success('服务已停止');
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '停止失败');
  }
}

async function openAccessUrl(site: SiteWithUiPackage) {
  try {
    const status = await getUiPackageStatus(site.siteId);
    if (status.accessUrl) {
      window.open(status.accessUrl, '_blank');
    } else {
      ElMessage.warning('服务未激活，请先停止再重新启动');
    }
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '获取访问地址失败');
  }
}

async function openI18nDrawer(site: SiteWithUiPackage) {
  i18nSourceSite.value = site;
  i18nTargetLanguage.value = '';
  i18nForm.siteName = '';
  i18nForm.siteCode = '';
  i18nForm.siteDomain = site.siteDomain || '';
  i18nForm.siteServicePort = undefined;
  i18nForm.siteServiceIp = '';
  i18nStep.value = 0;
  i18nMenuTree.value = [];
  translatedMenuTree.value = [];
  translatePreviewItems.value = [];
  selectedMenuCount.value = 0;
  i18nDrawerVisible.value = true;
  const suggestedPort = await suggestServicePort();
  if (suggestedPort) i18nForm.siteServicePort = suggestedPort;
}

function handleI18nLanguageChange(langCode: string) {
  if (!i18nSourceSite.value || !langCode) return;
  const sourceSite = i18nSourceSite.value;
  const langOption = i18nLanguageOptions.value.find(l => l.languageCode === langCode);
  const langSuffix = langCode.split('-')[0].toLowerCase();

  // 自动生成站点名称和编码
  i18nForm.siteName = `${sourceSite.projectName} (${langOption?.languageName || langCode})`;
  i18nForm.siteCode = `${sourceSite.projectCode}-${langSuffix}`;

  // 域名添加语言前缀
  if (sourceSite.siteDomain) {
    // 移除已有的语言前缀（如 en.fada.localhost -> fada.localhost）
    let baseDomain = sourceSite.siteDomain;
    const knownPrefixes = ['zh.', 'en.', 'zh-cn.', 'en-us.'];
    for (const prefix of knownPrefixes) {
      if (baseDomain.toLowerCase().startsWith(prefix)) {
        baseDomain = baseDomain.substring(prefix.length);
        break;
      }
    }
    // 添加新的语言前缀
    i18nForm.siteDomain = `${langSuffix}.${baseDomain}`;
  }
}

async function handleI18nNextStep() {
  if (!i18nSourceSite.value) return;

  // 进入下一步前校验服务端口是否已被其它站点占用
  if (i18nForm.siteServicePort) {
    const check = await checkSiteServicePort(i18nForm.siteServicePort);
    if (!check.available) {
      const hint = check.suggestedPort ? `，建议改用 ${check.suggestedPort}` : '';
      ElMessage.error(`${check.message || `端口 ${i18nForm.siteServicePort} 已被占用`}${hint}`);
      return;
    }
  }

  i18nMenuTreeLoading.value = true;
  i18nStep.value = 1;

  // 并行加载栏目树和 AI 服务状态
  try {
    const [menuTree] = await Promise.all([
      getSiteMenuTree(i18nSourceSite.value.siteId),
      refreshAiServiceStatus()
    ]);
    i18nMenuTree.value = menuTree;
    // 初始化右侧树，根节点显示为目标站点编码
    if (menuTree.length > 0 && i18nForm.siteCode) {
      const rootNode = menuTree[0];
      translatedMenuTree.value = [{
        id: rootNode.id,
        parentId: rootNode.parentId,
        className: rootNode.className,
        translatedName: i18nForm.siteCode,
        children: []
      }];
    }
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '加载栏目树失败');
    i18nStep.value = 0;
  } finally {
    i18nMenuTreeLoading.value = false;
  }
}


async function refreshAiServiceStatus() {
  aiServiceLoading.value = true;
  try {
    aiServiceStatus.value = await getAiStatus('MENU_TRANSLATION');
  } catch (error) {
    aiServiceStatus.value = { status: 'failed', url: '', message: '', error: '获取状态失败' };
  } finally {
    aiServiceLoading.value = false;
  }
}

async function handleStartAiService() {
  aiServiceOperating.value = true;
  try {
    aiServiceStatus.value = await getAiStatus('MENU_TRANSLATION');
    if (aiServiceStatus.value.status === 'running' || aiServiceStatus.value.status === 'available') {
      ElMessage.success('AI 模型已配置');
    }
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '检查 AI 模型配置失败');
  } finally {
    aiServiceOperating.value = false;
  }
}




function getAllMenuIds(nodes: MenuTreeNode[]): number[] {
  const ids: number[] = [];
  for (const node of nodes) {
    ids.push(node.id);
    if (node.children?.length) {
      ids.push(...getAllMenuIds(node.children));
    }
  }
  return ids;
}

function handleSelectAllMenus() {
  const allIds = getAllMenuIds(i18nMenuTree.value);
  menuTreeRef.value?.setCheckedKeys(allIds);
  updateSelectedCount();
}

function handleClearMenuSelection() {
  menuTreeRef.value?.setCheckedKeys([]);
  updateSelectedCount();
  translatedMenuTree.value = [];
  translatePreviewItems.value = [];
}

function handleMenuCheck() {
  updateSelectedCount();
}

function updateSelectedCount() {
  const keys = menuTreeRef.value?.getCheckedKeys(false) || [];
  selectedMenuCount.value = keys.length;
}

async function handleTranslatePreview() {
  if (!i18nSourceSite.value || !i18nTargetLanguage.value) return;
  const selectedMenuIds = menuTreeRef.value?.getCheckedKeys(false) as number[] || [];
  if (selectedMenuIds.length === 0) {
    ElMessage.warning('请先选择要翻译的栏目');
    return;
  }
  translatePreviewAbortController?.abort();
  const abortController = new AbortController();
  translatePreviewAbortController = abortController;
  const generation = ++translatePreviewGeneration;
  const isCurrent = () => generation === translatePreviewGeneration && !abortController.signal.aborted;
  translatingPreview.value = true;
  translateStreamLog.value = '[正在调用翻译服务...]\n';


  translatePrompt.value = '';
  translateRawResponse.value = '';
  translatePreviewItems.value = [];
  try {
    const result = await translateMenusPreview(i18nSourceSite.value.siteId, {
      targetLanguage: i18nTargetLanguage.value,
      menuIds: selectedMenuIds,
      engine: 'SPRING_AI'
    });
    if (!isCurrent()) return;
    translatePrompt.value = result.prompt || '';
    translateRawResponse.value = result.rawResponse || '';
    translateStreamLog.value = `=== Prompt ===\n${result.prompt || ''}\n=== AI 响应内容 ===\n${result.rawResponse || ''}\n`;
    const items = result.items || [];
    translatePreviewItems.value = items;
    const translationMap = new Map(items.map(item => [item.menuId, item]));
    translatedMenuTree.value = buildTranslatedTree(i18nMenuTree.value, translationMap, selectedMenuIds, i18nForm.siteCode);
    translateStreamLog.value += '[翻译完成]\n';
  } catch (error) {
    if (!isCurrent()) return;
    translateStreamLog.value += `\n[错误] ${error instanceof Error ? error.message : '翻译失败'}\n`;
    ElMessage.error(error instanceof Error ? error.message : '翻译预览失败');
  } finally {
    if (isCurrent()) {
      translatePreviewAbortController = null;
      translatingPreview.value = false;
    }
  }
}

function handleI18nDrawerClose() {
  translatePreviewGeneration++;
  translatePreviewAbortController?.abort();
  translatePreviewAbortController = null;
  translatingPreview.value = false;
}

onBeforeUnmount(handleI18nDrawerClose);



function buildTranslatedTree(
  nodes: MenuTreeNode[],
  translationMap: Map<number, TranslatedMenuPreview>,
  selectedIds: number[],
  siteCode?: string
): TranslatedTreeNode[] {
  const result: TranslatedTreeNode[] = [];
  for (const node of nodes) {
    if (selectedIds.includes(node.id)) {
      const translation = translationMap.get(node.id);
      // 根节点（parentId 为 0）使用目标站点编码作为名称
      const isRoot = node.parentId === 0;
      const translatedName = isRoot && siteCode
        ? siteCode
        : (translation?.translatedName || node.className);
      const translatedNode: TranslatedTreeNode = {
        id: node.id,
        parentId: node.parentId,
        className: node.className,
        translatedName,
        originalName: isRoot ? undefined : (translation ? node.className : undefined),
        children: buildTranslatedTree(node.children || [], translationMap, selectedIds, siteCode)
      };
      result.push(translatedNode);
    } else if (node.children?.length) {
      const childResults = buildTranslatedTree(node.children || [], translationMap, selectedIds, siteCode);
      if (childResults.length > 0) {
        result.push({
          id: node.id,
          parentId: node.parentId,
          className: node.className,
          translatedName: node.className,
          children: childResults
        });
      }
    }
  }
  return result;
}

function canEditTranslatedName(menuId: number): boolean {
  return translatePreviewItems.value.some(item => item.menuId === menuId);
}

function startEditTranslatedName(data: TranslatedTreeNode) {
  if (!canEditTranslatedName(data.id)) return;
  editingMenuId.value = data.id;
  editingMenuName.value = data.translatedName || data.className;
}

function commitEditTranslatedName(data: TranslatedTreeNode) {
  if (editingMenuId.value !== data.id) return;
  const name = editingMenuName.value.trim();
  if (name) {
    data.translatedName = name;
    const item = translatePreviewItems.value.find(i => i.menuId === data.id);
    if (item) item.translatedName = name;
  }
  editingMenuId.value = null;
}

function cancelEditTranslatedName() {
  editingMenuId.value = null;
}

watch(editingMenuId, async (id) => {
  if (id == null) return;
  await nextTick();
  editNameInputRef.value?.focus();
});

async function handleGenerateI18nSite() {
  if (!i18nSourceSite.value || !i18nTargetLanguage.value || !i18nForm.siteName || !i18nForm.siteCode) return;

  const selectedMenuIds = menuTreeRef.value?.getCheckedKeys(false) as number[] || [];
  if (selectedMenuIds.length === 0) {
    ElMessage.warning('请至少选择一个栏目');
    return;
  }

  const sourceSite = i18nSourceSite.value;

  i18nGenerating.value = true;
  try {
    // 复用翻译预览结果：已预览的栏目直接带译文，后台不再重复调用 AI
    const previewMap = new Map(translatePreviewItems.value.map(item => [item.menuId, item.translatedName]));
    const menuTranslations = selectedMenuIds
      .filter(id => previewMap.has(id))
      .map(id => ({ menuId: id, translatedName: previewMap.get(id) as string }));

    const request: GenerateI18nSiteRequest = {
      targetLanguage: i18nTargetLanguage.value,
      engine: 'SPRING_AI',
      siteName: i18nForm.siteName,
      siteCode: i18nForm.siteCode,
      siteDomain: i18nForm.siteDomain || undefined,
      siteServicePort: i18nForm.siteServicePort ?? undefined,
      siteServiceIp: i18nForm.siteServiceIp.trim(),
      selectedMenuIds,
      menuTranslations
    };
    const result = await generateI18nSite(sourceSite.siteId, request);
    i18nDrawerVisible.value = false;
    await loadSites();
    // 刷新全局站点上下文，更新顶部站点选择器
    await siteContext.loadSites();

    if (result.taskId) {
      // 已生成多语言站点任务：提示后跳转到该任务的明细
      try {
        await ElMessageBox.alert(
          '多语言站点任务初始化完成，查看生成任务明细。',
          '生成完成',
          { confirmButtonText: '确认', type: 'success', showClose: false }
        );
        await router.push({ path: '/admin/v2/sites-v2/i18n-tasks', query: { taskId: String(result.taskId) } });
      } catch {
        // 忽略关闭
      }
    } else {
      ElMessage.success(`已创建多语言站点: ${result.siteName}，翻译了 ${result.translatedMenuCount} 个栏目`);
    }
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '创建站点失败');
  } finally {
    i18nGenerating.value = false;
  }
}
</script>

<style scoped>
.site-v2 {
  padding: 0;
}

.site-v2__header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: var(--admin-card-gap);
}

.site-v2__header h1 {
  margin: 0;
  font-size: 20px;
}

.site-v2__actions {
  display: flex;
  gap: 12px;
}

.site-v2__table {
  background: var(--admin-bg-surface);
  border-radius: 8px;
  border: 1px solid var(--admin-border);
}

.site-v2__body {
  display: flex;
  align-items: flex-start;
  gap: var(--admin-card-gap);
}

.site-v2__list {
  flex: 1;
  min-width: 0;
}

.site-v2__form {
  padding: 0 20px;
}

.ui-package-loading {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 40px;
  color: var(--el-text-color-secondary);
}

.ui-package-content {
  padding: 0 20px;
}

.ui-package-upload {
  padding: 20px 0;
}

.ui-package-info {
  padding: 20px 0;
}

.ui-package-filename {
  font-size: 14px;
  margin-bottom: 16px;
}

.ui-package-url {
  font-size: 14px;
  margin-bottom: 16px;
}

.ui-package-url a {
  color: var(--el-color-primary);
}

.ui-package-actions {
  display: flex;
  gap: 12px;
}

.ui-package-port-tip {
  margin: 0 0 12px;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

.ui-package-upgrade__tip {
  margin: 8px 0 0;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.ui-package-records {
  margin-top: 8px;
}

/* 多语言站点抽屉样式 */
.i18n-drawer {
  display: flex;
  flex-direction: column;
  height: 100%;
}

.i18n-drawer__steps {
  margin-bottom: 24px;
}

.i18n-drawer__content {
  flex: 1;
  min-height: 0;
  overflow: auto;
}

.i18n-drawer__source {
  margin: 0 0 20px;
  padding: 12px 16px;
  border: 1px solid var(--admin-border);
  border-radius: var(--admin-radius-md);
  background: var(--admin-bg-subtle);
  font-size: var(--admin-font-size-base);
  color: var(--admin-text-secondary);
}

.i18n-drawer__source strong {
  color: var(--el-text-color-primary);
}

.i18n-drawer__loading {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 40px;
  color: var(--el-text-color-secondary);
}

.i18n-drawer__dual-tree {
  display: flex;
  gap: 16px;
  min-height: 400px;
}

.i18n-drawer__tree-panel {
  flex: 1;
  border: 1px solid var(--el-border-color-light);
  border-radius: 6px;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

.i18n-drawer__tree-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 16px;
  background: var(--el-fill-color-light);
  border-bottom: 1px solid var(--el-border-color-light);
  font-size: 14px;
  font-weight: 500;
}

.i18n-drawer__tree-actions {
  display: flex;
  gap: 8px;
}

.i18n-drawer__tree {
  flex: 1;
  overflow: auto;
  padding: 12px;
  min-height: 300px;
}

.i18n-drawer__tree-loading {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 40px;
  color: var(--el-text-color-secondary);
}

.i18n-tree-node {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}

.i18n-tree-node__translated {
  color: var(--el-color-primary);
  font-weight: 500;
}

.i18n-tree-node__edit {
  width: 160px;
}

.i18n-tree-node__original {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.i18n-drawer__footer {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  flex-shrink: 0;
  justify-content: flex-end;
  gap: 12px;
  padding-top: 20px;
  border-top: 1px solid var(--el-border-color-light);
  margin-top: 20px;
}

/* AI 服务状态样式 */
.ai-service-status {
  margin-bottom: 20px;
  padding: 16px;
  background: var(--el-fill-color-light);
  border-radius: 8px;
}

.ai-service-status__header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}

.ai-service-status__heading {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}

.ai-service-status__title {
  font-size: 14px;
  font-weight: 500;
  color: var(--el-text-color-primary);
}

.ai-service-status__engine {
  width: 140px;
}

/* 引擎下拉：默认呈现为文本，悬停/聚焦时显示下拉样式与箭头 */
.ai-service-status__engine :deep(.el-select__wrapper) {
  background: transparent;
  box-shadow: none;
  padding: 0 4px;
}

.ai-service-status__engine :deep(.el-select__caret) {
  opacity: 0;
  transition: opacity 0.15s ease;
}

.ai-service-status__engine:hover :deep(.el-select__wrapper),
.ai-service-status__engine:focus-within :deep(.el-select__wrapper) {
  background: var(--el-fill-color-blank);
  box-shadow: 0 0 0 1px var(--el-border-color) inset;
}

.ai-service-status__engine:hover :deep(.el-select__caret),
.ai-service-status__engine:focus-within :deep(.el-select__caret) {
  opacity: 1;
}

.ai-service-status__actions {
  display: flex;
  gap: 8px;
  flex-shrink: 0;
}

.ai-service-status__url {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  font-family: monospace;
}

.ai-service-status__error {
  margin-top: 8px;
  font-size: 12px;
  color: var(--el-color-danger);
  word-break: break-all;
}

/* 翻译记录 */
.ai-stream-log {
  border: 1px solid var(--el-border-color);
  border-radius: 8px;
  overflow: hidden;
  margin-top: 16px;
}

.ai-stream-log__header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px 12px;
  background: var(--el-fill-color-light);
  border-bottom: 1px solid var(--el-border-color);
  font-size: 13px;
  font-weight: 500;
}

.ai-stream-log__header-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.ai-stream-log__content {
  background: #1e1e1e;
  color: #4ade80;
  padding: 12px;
  font-family: monospace;
  font-size: 12px;
  height: 200px;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-all;
  margin: 0;
  line-height: 1.5;
}

.stream-log-display {
  background: #1e1e1e;
  color: #4ade80;
  padding: 16px;
  border-radius: 8px;
  font-family: monospace;
  font-size: 13px;
  max-height: 500px;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-all;
  margin: 0;
}

.site-v2__row-primary,
.site-v2__row-secondary {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px 12px;
}
.site-v2__row-secondary {
  margin-top: 10px;
  padding-top: 8px;
  border-top: 1px solid var(--admin-border-subtle);
}
.site-v2__row-primary :deep(.el-button),
.site-v2__row-secondary :deep(.el-button) { margin-left: 0; }
.i18n-drawer__hint {
  flex: 1 1 240px;
  color: var(--admin-text-muted);
  font-size: var(--admin-font-size-small);
}
.site-v2__mode-tag + .site-v2__mode-tag {
  margin-left: 6px;
}
.site-v2__mode-hint {
  flex: 1 1 100%;
  color: var(--admin-text-muted);
  font-size: var(--admin-font-size-small);
}
.site-v2__headless-auth {
  width: 100%;
}
.site-v2__headless-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}
.site-v2__headless-row:last-child {
  margin-bottom: 0;
}
.site-v2__headless-label {
  flex: 0 0 76px;
  color: var(--admin-text-muted);
  font-size: var(--admin-font-size-small);
}
.site-v2__headless-hint {
  margin-left: 4px;
  color: var(--admin-text-muted);
  font-size: var(--admin-font-size-small);
}
@media (max-width: 960px) {
  .site-v2__body { flex-direction: column; }
  .site-v2__list { width: 100%; }
  .i18n-drawer__dual-tree { grid-template-columns: minmax(0, 1fr); }
}
</style>

