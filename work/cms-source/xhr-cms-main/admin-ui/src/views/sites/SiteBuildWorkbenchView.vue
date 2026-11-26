<template>
  <section class="site-build admin-responsive-page">
    <header class="build-header cms-page__hero admin-panel">
      <div><h1>AI建站工作台</h1><p v-if="siteContext.currentSite">{{ siteContext.currentSite.name }}</p></div>
      <el-button :loading="loading" :disabled="busy || !siteContext.currentSite" @click="refreshCurrent">刷新阶段状态</el-button>
    </header>
    <WorkbenchStageProgress :stages="siteProgress.stages" :viewing="viewingStage" :next="suggestedStage"
      :action-label="workflowActionLabel" :next-task="workflowTaskLabel" :analysis="siteProgress.analysisCounts" :can-navigate="hasPackage && !packageReadProblem" :busy="busy"
      @navigate="openWorkflowStage" @proceed="continueWorkflow" />
    <AgentRunWindow ref="agentRunWindow" :site-id="siteContext.currentSite?.id ? Number(siteContext.currentSite.id) : null"
      :admin-id="siteContext.currentAdmin?.id ? Number(siteContext.currentAdmin.id) : null" :package-version="packageStatus?.version"
      :menu-id="planTargetMenuId ?? null" :chat-scope="chatScope" :chat-units="unitsWorkspace?.units || []" :field-types="unitTypes"
      :chat-disabled="chatDisabled" :apply-unit="applyChatUnit" :can-start-page="domainAgentPanel?.canStart"
      :start-page="() => domainAgentPanel?.start()" :open-result="openRunResult" />
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <el-alert v-if="columnsRefreshError" title="刷新栏目列表失败" type="warning" :closable="false" show-icon>
      <el-button link type="primary" @click="refreshColumns">重读栏目列表</el-button>
    </el-alert>
    <el-card v-if="siteContext.currentSite" shadow="never" class="build-resume">
      <div class="build-card-title"><strong ref="workspaceHeading" tabindex="-1">当前操作 · {{ currentStepLabel }}</strong><el-tag :type="hasUnsavedEdits?'warning':'info'">{{ hasUnsavedEdits ? (localDraftStored?'本机已暂存 · 未保存到CMS':'未保存到CMS · 本机暂存不可用') : '服务端方案只读恢复' }}</el-tag></div>
      <p class="build-next" aria-label="当前工作范围">页面包 {{ packageStatus?.version || (packageReadProblem ? "状态未核实" : packageStatus ? "尚未上传" : "等待读取") }} · {{ [1,2,3,4].includes(activeStep) ? (sourcePath || "尚未选择页面") : activeStep===6 ? "本站待办" : "整包" }}</p>
      <div v-if="hasPackage && (activeStep===0 || activeStep===7 || packageAnalysisPanel?.running)" class="build-controls">
        <el-button :type="activeStep===7 && packageNext && !['primary','adopt'].includes(packageNext.kind) ? 'default' : 'primary'" :disabled="packageAnalysisPanel?.actionDisabled ?? true" @click="openPackageAnalysis">{{ packageAnalysisPanel?.actionLabel || '读取整包分析状态' }}</el-button>
      </div>
      <div v-if="activeStep===7 && hasPackage && packageNext && packageNext.kind!=='primary'" class="build-package-next" role="region" aria-label="整包审核下一步">
        <div><strong>{{ packageNext.label }}</strong><p>{{ packageNext.text }}</p></div>
        <el-button v-if="activeStep!==7 || packageNext.kind!=='adopt'" type="primary" :disabled="packageAnalysisPanel?.actionDisabled" @click="openPackageNext">{{ packageNext.button }}</el-button>
      </div>
      <div class="build-workspace-nav">
        <el-radio-group v-if="stageTools.length > 1" :key="stepNavRevision" :model-value="activeStep"
          :disabled="busy" aria-label="本阶段操作" @change="changeStep(Number($event))">
          <el-radio-button v-for="[step, name] in stageTools" :key="step" :value="step" :aria-label="`前往${name}`">{{ name }}</el-radio-button>
        </el-radio-group>
        <el-button v-if="hasPackage && !packageReadProblem && activeStep!==6" link :disabled="busy" @click="openTaskList">打开站点待办</el-button>
        <template v-if="activeStep===6">
          <el-button link :disabled="busy" @click="openTaskList">查看站点待办</el-button>
          <el-button link :disabled="busy" @click="router.push('/admin/v2/cms/static-publish')">进入静态页发布</el-button>
          <el-button link :disabled="busy" @click="showReleaseReview">查看整站签收</el-button>
        </template>
      </div>
      <el-button v-if="linkReviewOrigin" plain :disabled="busy" @click="returnToLinkReview">返回 {{ linkReviewOrigin.path }} 的{{ linkReviewOrigin.list ? '列表与详情审核' : linkReviewOrigin.landingCard ? '概览卡片' : '公共导航审核' }}</el-button>
      <div v-if="[1,2,3,4].includes(activeStep)" class="build-controls"><el-select :model-value="sourcePath" @change="changeSourcePath" aria-label="结构分析来源页面" placeholder="选择当前页面" filterable :disabled="busy||!hasPackage"><el-option v-for="page in seedPages" :key="page.sourcePath" :label="page.sourcePath" :value="page.sourcePath"/></el-select><el-select :model-value="planTargetMenuId" @change="changePlanMenu" aria-label="字段计划目标栏目" placeholder="选择对应栏目" filterable :disabled="busy"><el-option v-if="rootMenuId" :value="rootMenuId" label="首页（站点根栏目）"/><el-option v-for="menu in columns.filter(item=>item.pageType===0||item.pageType===1)" :key="menu.id" :value="Number(menu.id)" :label="`${menu.className||menu.name}（${menu.pageType===0?'单页':'列表'}）`"/></el-select></div>
      <section v-if="checklistTarget && activeStep!==6 && (!checklistTarget.sourcePath || checklistTarget.sourcePath===sourcePath || activeStep===5)" aria-label="当前待办">
        <strong>{{ activeStep===2 && overlaps.length && !planStale ? `处理 ${overlaps.length} 处原文冲突` : checklistTarget.label }}</strong> · {{ checklistTarget.reason }}
        <el-button link @click="changeStep(6)">返回待办清单</el-button>
        <div v-if="activeStep===1 && checklistTarget.action==='recognize' && !searchEvidence && unitsWorkspace?.sourcePath===sourcePath" class="build-controls">
          <template v-if="unitsWorkspace.units.length">
            <el-button :disabled="busy" @click="openDraftReview()">逐项核对原页与草稿</el-button>
            <span>核对内容归属、重复样例及真实链接后，在整页审核中保存修正。</span>
          </template>
          <template v-else>
            <el-button :disabled="busy || !domainAgentPanel?.canStart" @click="domainAgentPanel?.start()">让 Agent 分析当前原页</el-button>
            <span>生成建议后，核对哪些内容可以维护、哪些还需补资料。</span>
          </template>
        </div>
        <p v-if="activeStep===1 && searchEvidence">下一步：到本页“检索结果审核”核对公开检索范围。<el-button link @click="router.push('/admin/v2/cms/menus')">核对公开检索字段</el-button></p>
        <div v-if="activeStep===2" class="build-controls">
          <el-button v-if="fieldPlan && !planStale && (textBinding?.initialized || completedDetail)" :disabled="busy" @click="textBindingAction('preview')">查看已初始化的私有草稿</el-button>
          <el-button v-else-if="fieldPlan && canCheckTextBinding" :disabled="busy" @click="textBindingAction('inspect')">检查当前绑定状态</el-button>
          <el-button v-if="fieldPlan && !planPreviewUnavailable" :disabled="busy" @click="planBindingPreview?.open()">打开整页字段审核</el-button>
          <el-button v-else-if="unitsWorkspace" :disabled="busy" @click="openDraftReview()">核对原页内容单元</el-button>
        </div>
      </section>
      <div v-if="activeStep===2 && sourcePath" class="build-controls">
        <strong>{{ currentMenuLabel }} · {{ sourcePath }}</strong>
        <el-button v-if="overlaps.length && !planStale" type="primary" @click="focusOverlapReview">处理 {{ overlaps.length }} 处原文冲突</el-button>
        <el-button v-else-if="textBinding?.initialized && fieldPlan" :disabled="busy" @click="openTextBindingEditor">维护已有内容</el-button>
      </div>
      <p v-if="[1,2,3,4].includes(activeStep)" class="build-next"><strong>{{ currentWorkStatus }}</strong><span>{{ nextWorkAction }}</span></p>
      <div v-if="activeStep===2 && completedDetail" class="build-controls"><el-button type="primary" :disabled="busy" @click="continueDetailList">继续配置此栏目列表</el-button></div>
      <WorkbenchHandoffPanel v-if="activeStep===6 && siteContext.currentAdmin" ref="handoffPanel" :admin-id="String(siteContext.currentAdmin.id)" :key="String(siteContext.currentAdmin.id) + ':' + siteContext.currentSite.id" :site-id="Number(siteContext.currentSite.id)" :disabled="busy" @resume="resumeHandoff" />
      <details class="build-resume-help"><summary>继续工作说明</summary><p class="build-resume-scope">记忆当前页面、栏目和步骤；自动读取已保存内容与字段计划。列表配对、查询及公开应用请在对应面板读取原计划，未提交的独立面板选择不会自动恢复。不会自动创建计划、初始化或发布。</p></details>
      <el-alert v-if="resumeNotice" :title="resumeNotice" :type="unrestoredDraft?'warning':'info'" :closable="false"/>
      <details v-if="unrestoredDraft"><summary>查看未自动恢复的本机修正（只读）</summary><p>本机修订 {{ unrestoredDraft.revision }} · 服务端修订 {{ unitsWorkspace?.revision ?? '待读取' }}</p>
        <ul><li v-for="row in recoveryRows" :key="row.id"><strong>{{ row.name }}</strong> · 本机：{{ row.local }} · 当前：{{ row.current }}</li></ul>
        <details><summary>查看字段来源和技术明细</summary><pre class="build-analysis">{{ JSON.stringify(unrestoredDraft.units,null,2) }}</pre></details><el-button @click="clearUnrestoredDraft">清除旧本机修正</el-button></details>

    </el-card>
    <el-card id="workbench-project-site" tabindex="-1" v-if="!siteContext.currentSite||activeStep===0" shadow="never">
      <template #header><strong>目标站点</strong></template>
      <div class="build-controls">
        <el-select aria-label="目标站点" placeholder="请选择站点" :model-value="siteContext.currentSite?.id" :disabled="busy" @change="selectSite">
          <el-option v-for="site in siteContext.sites" :key="site.id" :label="`${site.name}（${site.code}）`" :value="site.id" />
        </el-select>
        <el-button v-if="siteContext.isPlatformAdmin" :disabled="busy" @click="router.push('/admin/v2/sites-v2?create=1&workbench=1')">创建新站点</el-button>
      </div>
      <p>操作会作用于当前选中的站点。在站点管理上传 ZIP 后，点击“继续分析此站页面包”进入新站。</p>
    </el-card>
    <template v-if="siteContext.currentSite">
      <PackageAnalysisPanel v-if="packageStatus?.version && hasPackage" v-show="activeStep===7" ref="packageAnalysisPanel"
        :key="`${siteContext.currentAdmin?.id}:${siteContext.currentSite.id}:${packageStatus.version}`"
        :site-id="Number(siteContext.currentSite.id)" :package-version="packageStatus.version" :columns="packageColumns"
        :plan="siteModelPlan" :applied-navigation="appliedPackageNavigation" :active="activeStep===7" :open-review="route.query.packageReview==='1'" :disabled="busy"
        :saved-review="reviewReturn" @continue-review="continueConfirmedReview"
        @structure="openPackageStructure" @correct="openPackageSource" @adopt="adoptPackagePage" />
      <el-card id="workbench-project-package" tabindex="-1" v-if="activeStep===0" v-loading="loading" shadow="never">
        <template #header><div class="build-card-title"><strong>页面包</strong><el-tag>{{ packageStatus ? (hasPackage ? '已上传' : '待上传') : '待检查' }}</el-tag></div></template>
        <el-button v-if="packageReadProblem==='transient'" :loading="loading" @click="refreshCurrent">重新读取页面包状态</el-button>
        <p v-if="packageStatus">上传包含静态 HTML/HTM/ASPX/ASP、CSS、JS 的 ZIP；包内 file/virtual 包含仅作受控本地展开。已有包的升级、替换与服务管理请在站点管理中操作。</p>
        <p v-if="hasPackage">{{ packageStatus?.packageName || 'UI 包已存在' }} <span v-if="packageStatus?.version">· {{ packageStatus.version }}</span></p>
        <el-button v-if="hasPackage" type="primary" :disabled="packageAnalysisPanel?.actionDisabled ?? true" @click="openPackageAnalysis">{{ packageAnalysisPanel?.actionLabel || '读取整包分析状态' }}</el-button>
        <el-upload v-if="packageStatus && !hasPackage" :aria-label="uploading ? '正在上传页面包' : '选择 ZIP 并上传'" accept=".zip" :auto-upload="false" :show-file-list="false" :disabled="busy" :on-change="upload">
          <span class="el-button el-button--primary">{{ uploading ? '正在上传…' : '选择 ZIP 并上传' }}</span>
        </el-upload>
        <el-button v-else-if="hasPackage" :disabled="busy" @click="router.push('/admin/v2/sites-v2')">管理已有 UI 包</el-button>
        <template v-if="packageInspection">
          <p>版本：{{ packageInspection.version }} · {{ packageInspection.files.length }} 个文件</p>
          <details><summary>包检查说明</summary><el-alert v-for="warning in packageInspection.warnings" :key="warning" :title="warning" type="info" :closable="false" /></details>
          <details><summary>查看页面包文件清单</summary><el-table :data="packageInspection.files" size="small" max-height="240"><el-table-column prop="path" label="包内文件" min-width="240" /><el-table-column label="原文件类型" width="110"><template #default="{ row }">{{ row.path.split('.').pop()?.toUpperCase() || '文件' }}</template></el-table-column><el-table-column prop="size" label="大小（字节）" width="140" /></el-table></details>
          <div class="build-controls"><el-select v-model="previewPath" aria-label="隔离预览页面" placeholder="选择静态页面" :disabled="busy || isolatedLoading"><el-option v-for="page in seedPages" :key="page.sourcePath" :value="page.sourcePath" :label="page.sourcePath" /></el-select><el-button :loading="isolatedLoading" :disabled="busy || !previewPath" @click="previewIsolated">隔离静态预览</el-button></div>
          <el-alert v-for="warning in isolatedWarnings" :key="warning" :title="previewWarning(warning)" type="warning" :closable="false" />
          <SourceVideoPreview v-if="isolatedHtml && previewPath && packageStatus?.version" :site-id="Number(siteContext.currentSite.id)" :source-path="previewPath" :package-version="packageStatus.version" />
          <iframe v-if="isolatedHtml" title="隔离静态页面预览" sandbox="" referrerpolicy="no-referrer" :srcdoc="isolatedHtml" style="width: 100%; height: 560px; border: 1px solid var(--admin-border-subtle); margin-top: 12px" />
        </template>
      </el-card>
      <WorkbenchTemplateChecklist :position="checklistPosition" @remember="checklistPosition=$event" @manage-content="openExistingMenuContent" :package-plan-id="packageAnalysisPanel?.structurePlanId" v-if="hasPackage && !loading && (activeStep===6 || visitedStages.includes(6) || !!reviewedNavigation)"
        v-show="activeStep===6" :key="`tasks:${siteContext.currentSite.id}:${packageStatus?.version}`" ref="checklistPanel" :site-id="Number(siteContext.currentSite.id)" :package-version="packageStatus?.version" :plan="siteModelPlan" :navigation="navigationSnapshot" :columns="checklistColumns" :root-menu-id="rootMenuId" :pages="packagePagePaths" :disabled="busy" @open="openWorkbenchTarget" @page="openChecklistPage" @structure="openChecklistStructure" @prerequisite="openChecklistPrerequisite" @package-review="changeStep(hasPackage ? 7 : 0)"
        :navigation-evidence="releaseNavigationEvidence" @clear-navigation-evidence="invalidateReleaseReview" />
      <WorkbenchReleaseReview ref="releasePanel" v-if="visitedStages.includes(6) || restoreReleaseProgress" v-show="activeStep===6"
        :admin-id="String(siteContext.currentAdmin?.id || '')"
        :site-id="Number(siteContext.currentSite.id)" :package-version="packageStatus?.version" :disabled="busy"
        :scope="releaseReviewScope"
        :resolve-action="resolveReleaseAction"
        :confirm-leave="confirmReleaseLeave"
        @navigation-evidence="releaseNavigationEvidence=$event"
        @open-form-center="router.push('/admin/v2/cms/form-center')"
        @publish="router.push('/admin/v2/cms/static-publish')" />
      <WorkbenchStaticAudit v-if="visitedStages.includes(6)" v-show="activeStep===6"
        :key="`${siteContext.currentSite.id}:${packageStatus?.version}`" :site-id="Number(siteContext.currentSite.id)"
        :disabled="busy" :can-review-source="canReviewStaticSource" @review-source="reviewStaticSource"
        @publish="router.push('/admin/v2/cms/static-publish')" @templates="router.push('/admin/v2/cms/template-management')"
        @site-service="router.push('/admin/v2/site-service')" />
      <el-alert v-if="activeStep===5 && packageStructureLinkError" :title="packageStructureLinkError" type="error" :closable="false" show-icon />
      <SiteModelPanel :package-running="packageAnalysisPanel?.running" :package-linking="packageStructureLinkBusy" @show-package-run="changeStep(7)" :focus-target="structureFocus" :display-panel="visitedStages.includes(5)" v-if="packageStatus?.version" v-show="activeStep===5" ref="siteModelPanel"
        :key="`${siteContext.currentAdmin?.id}:${siteContext.currentSite.id}:${packageStatus.version}`"
        :site-id="Number(siteContext.currentSite.id)" :admin-id="String(siteContext.currentAdmin?.id || '')" :package-version="packageStatus.version" :columns="columns" :root-menu-id="rootMenuId" :package-analysis-plan-id="packageAnalysisPanel?.structurePlanId" :package-analysis-plan-revision="packageAnalysisPanel?.structurePlanRevision"
        :columns-loaded="columnsLoaded" :pages="packagePagePaths" :disabled="busy || packageStructureLinkBusy"
        @plan="siteModelPlan = $event" @agent-task="followAgent('site', $event)" @open-page="openModelPage" @open-package-page="openPackagePageForReview" @use-package-plan="usePackageStructure" @navigation-appended="continueAppendedNavigation" @structure-applied="refreshColumns" @application="openWorkbenchApplication"
        @navigation-continued="continueAppliedNavigation" @manage-columns="router.push('/admin/v2/cms/menus')" @manage-content="openModelContent"
        @open-fields="openModelFields" @navigation="receiveModelNavigation" @continue-node="openNavigationNode"
        @navigation-kind-corrected="continueCorrectedNavigation" @continue-list="openNavigationList" />
      <el-alert v-else-if="activeStep===5" title="请先检查站点的页面包，再审核整站内容结构。" type="info" :closable="false" />
      <el-card v-if="targetColumn" v-show="[1,2,3,4].includes(activeStep)" shadow="never">
        <template #header><strong>栏目工作状态 · {{ currentMenuLabel }}（{{ targetColumn.id }}）</strong></template>
        <ul class="build-target-stages">
          <li><strong>结构</strong> {{ navigationFact }}</li>
          <li><strong>字段</strong> {{ fieldFact }}</li>
          <li><strong>初值</strong> {{ initializationFact }}</li>
          <li><strong>模板</strong> {{ templateFact }}</li>
          <li><strong>公开</strong> {{ publicationFact }}</li>
        </ul>
        <p v-if="targetReadErrors.length" role="status">{{ targetReadErrors.join('；') }}</p>
        <p v-if="targetNavigation?.kind==='DIRECTORY' && !targetHome">此入口当前按目录处理。如有独立落地内容，请核对原页和栏目类型。已应用导航的类型需要在原生栏目管理修正并重新核对导航。</p>
        <p v-if="targetHome">首页使用站点根栏目。可在“预览与公开”接入已审核的首页轮播，或按单页字段计划接入首页内容。</p>
        <div class="build-controls">
          <el-button size="small" :disabled="busy" :loading="targetReading" @click="readTargetStatus">读取栏目运行状态</el-button>
          <el-button v-if="activeStep!==2" size="small" :disabled="busy || !targetBindingSource" @click="targetBindingSource && openModelFields(targetBindingSource, Number(targetColumn.id))">字段与初值</el-button>
          <el-button v-if="activeStep!==3 && targetColumn.pageType===1 && targetListSource" size="small" :disabled="busy" @click="goToList">列表模板</el-button>
          <el-button v-if="activeStep!==4" size="small" :disabled="busy" @click="listReviewTarget ? openWorkbenchTarget(sourcePath, Number(targetColumn.id), 4) : openTargetPublication()">预览与公开</el-button>
          <el-button v-if="targetNavigation?.kind==='DIRECTORY' || targetNavigation?.drift" size="small" :disabled="busy" @click="changeStep(5)">返回导航审核</el-button>
        </div>
      </el-card>
      <AsyncFragmentPanel v-if="visitedStages.includes(1)" v-show="activeStep===1" ref="asyncFragmentPanel" id="workbench-fragment-evidence" tabindex="-1" :site-id="siteContext.currentSite?.id ? Number(siteContext.currentSite.id) : null" :source-path="sourcePath || null" :disabled="busy" @open="changeSourcePath" />
      <el-card v-show="activeStep===1" shadow="never">
        <template #header><div class="build-card-title"><strong>页面区域证据与修正</strong><el-tag :type="columns.length ? 'success' : 'info'">{{ columnsLoaded ? `已有 ${columns.length} 个栏目` : '待检查' }}</el-tag></div></template>
        <p>核对整站结构在当前页面使用的区域与来源。已有单页草稿可继续修正，保存只记录证据判断。</p><details><summary>识别与保存规则</summary><p>先读取静态原文与区域定位，再按需调用后端 AI 提出内容单元。可人工拆分、合并、忽略和改名；保存只记录工作台判断，不创建字段、栏目或业务内容。</p></details>
        <div class="build-controls">
          <el-button type="primary" :loading="analyzing" :disabled="busy || !hasPackage || !sourcePath || !unitsWorkspace" @click="analyzePage">AI自动分批识别（生成草稿）</el-button>
          <el-button v-if="activeAnalysisTask" :disabled="analysisCancelling" @click="cancelPageAnalysis">取消分析任务</el-button>
          <el-button v-if="activeAnalysisTask && !analyzing" @click="forgetPageAnalysis">清除任务引用</el-button>
          <el-button :disabled="busy" @click="router.push('/admin/v2/cms/menus')">手动维护栏目</el-button>
        </div>
        <DomainAgentPanel ref="domainAgentPanel" :site-id="Number(siteContext.currentSite.id)" :source-path="sourcePath" :menu-id="planTargetMenuId" :sources="unitsWorkspace?.sources" :workspace-package-version="packageStatus?.version" :workspace-digest="unitsWorkspace?.digest" :workspace-revision="unitsWorkspace?.revision" :preview-active="activeStep===1" :disabled="busy || !hasPackage || !unitsWorkspace" :adopt="adoptDomainAgent" @inspect-relation="openAgentRelation" @review="openDraftReview" @agent-task="followAgent('page', $event)" />
        <el-alert v-if="analysisError" :title="analysisError" type="error" :closable="false" show-icon />
        <p v-if="analysisProgress">{{ analysisProgress }}</p>
        <p v-if="analysisResult">本次 AI 仅分析 {{ analysisResult.analyzedSources }} / {{ analysisResult.totalSources }} 项静态来源。请求编号：{{ analysisResult.requestId }}。AI草稿已由服务端保存，仍待人工确认。</p>
        <p v-if="analysisResult?.coverage">静态提取快照覆盖：{{ analysisResult.coverage.sourceCoverageComplete ? '可用来源已全部分析' : '仍有未处理来源或本次未重新分析' }}；明确排除 {{ analysisResult.coverage.excluded.length }} 项，未处理 {{ analysisResult.coverage.unprocessed.length }} 项。不是动态JS或整页语义准确性保证。</p>
        <details v-if="analysisResult?.coverage && (analysisResult.coverage.excluded.length || analysisResult.coverage.unprocessed.length)"><summary>查看排除与未处理来源</summary><p v-for="item in analysisResult.coverage.excluded" :key="`excluded-${item.sourceId}`">排除 {{ item.sourceId }}：{{ item.reason }}</p><p v-for="item in analysisResult.coverage.unprocessed" :key="`pending-${item.sourceId}`">未处理 {{ item.sourceId }}：{{ item.reason }}</p></details>
        <details><summary>识别范围与待处理说明</summary><el-alert v-for="warning in analysisResult?.warnings || []" :key="warning" :title="warning" type="warning" :closable="false" /></details>
        <el-alert v-if="unitsError" :title="unitsError" type="error" :closable="false" />
        <template v-if="unitsWorkspace">
          <VisualBindingSuggestions v-if="activeStep===1 && unitsWorkspace.sourcePath===sourcePath" ref="draftBindingPreview"
            :title="unitsDirty ? '原页面审核 · 本地未保存修正' : '原页面审核 · 内容单元草稿'"
            :site-id="Number(siteContext.currentSite.id)" :source-path="sourcePath" :package-version="packageStatus?.version"
            :digest="unitsWorkspace.digest" :revision="unitsWorkspace.revision" :units="unitsWorkspace.units" :sources="unitsWorkspace.sources"
            :mappings="currentBindingMappings" :mapping-notice="draftMappingNotice" :disabled="busy || !hasPackage"
            :edit-units="applyReviewUnits" :draft-dirty="unitsDirty" :save-units="saveReviewUnits" :return-to-sources="returnToUnitSources"
            :add-coverage-source="addCoverageSource" :add-coverage-sources="addCoverageSources" :restore-coverage-sources="restoreCoverageSources"
            :action-error="planError" :next-step-notice="listReviewTarget ? '内容单元保存后可核对列表卡片。' : canOpenSavedPlan ? '可读取已保存字段映射。' : newBindingNotice || '可继续审核字段映射。'"
            :footer-notice="unitsDirty ? '有本地修正未保存。整页保存只记录内容单元。' : listReviewTarget ? '可继续核对列表卡片、记录归属和分页。' : canOpenSavedPlan ? '可读取已保存字段映射。' : newBindingNotice || '内容单元保存后可继续审核字段映射。'">
            <template #selected-actions="{ unit }">
              <el-button size="small" :disabled="chatDisabled" @click="discussUnit(unit.id)">与 Agent 讨论所选字段</el-button>
            </template>
            <template #actions="{ dirty }">
              <el-button v-if="listReviewTarget" :disabled="busy || dirty || unitsDirty || !unitsWorkspace.revision" @click="goToList">核对列表卡片</el-button>
              <el-button v-else-if="planTargetMenuId" :disabled="busy || dirty || unitsDirty || !unitsWorkspace.revision || (!!newBindingNotice && !canOpenSavedPlan)"
                :loading="planLoading" @click="openPlanReview">继续审核字段映射</el-button>
            </template>
          </VisualBindingSuggestions>
          <details><summary>来源摘要与修订证据</summary><p>原文证据：{{ unitsWorkspace.sourcePath }} · 页面摘要 {{ unitsWorkspace.digest.slice(0, 12) }} · 已保存版本 {{ unitsWorkspace.revision }} · {{ unitsDirty ? '有未保存修正' : '已读取' }}</p></details>
          <details><summary>原文提取说明</summary><el-alert v-for="warning in unitsWorkspace.warnings" :key="warning" :title="warning" type="info" :closable="false" /></details>
          <p v-if="sourceCorrectionNotice" role="status">{{ sourceCorrectionNotice }}</p>
          <div class="build-controls"><el-select ref="manualSourcePicker" v-model="manualSourceId" filterable aria-label="待处理原文来源" placeholder="选择主体内容来源，手动新增单元" :disabled="busy"><el-option v-for="source in pageContentSources" :key="source.id" :value="source.id" :label="`${source.kind.startsWith('HTML_REGION') ? (source.kind === 'HTML_REGION_PENDING' ? '布局/样式待处理 · ' : '完整正文HTML候选 · ') : ''}${source.rawValue.slice(0, 60)} · ${source.selector}`" /><el-option v-if="excludedManualSource" :value="excludedManualSource.id" :label="`已有公共区或交互来源（仅核对） · ${excludedManualSource.rawValue.slice(0,60)}`" disabled /></el-select><el-button :disabled="busy || !manualSourceId || !!excludedManualSource" @click="addManualUnit">新增来源单元</el-button><el-button :disabled="busy || selectedUnitIds.length < 2" @click="mergeUnits">合并所选单元</el-button><el-button type="primary" :loading="unitsSaving" :disabled="busy || !unitsDirty" @click="saveUnits">保存人工修正</el-button><el-button :disabled="busy" @click="reloadUnits">重新读取已保存判断</el-button></div>
          <section v-if="unitsWorkspace.sourcePath===sourcePath" class="review-continuation" aria-label="本页审核下一步">
            <div><strong>本页审核下一步</strong><p aria-live="polite">{{ unitsDirty ? '当前修改尚未保存。' : '已保存修订 ' + unitsWorkspace.revision + '。' }}{{ reviewNext.help }}</p></div>
            <el-button type="primary" :disabled="busy || unitsSaving || planLoading || !unitsWorkspace.units.length"
              @click="continueUnitReview">{{ unitsDirty ? '保存并继续 · ' : '' }}{{ reviewNext.label }}</el-button>
            <p v-if="planError" role="alert">{{ planError }}</p>
          </section>
          <p>多行文本用于纯文本和换行；富文本用于正文排版和图文维护。新闻正文即使当前只有文字，也按富文本维护。正文内视频及复杂布局暂需人工处理，不降为多行文本。</p>
          <el-button :disabled="chatDisabled || selectedUnitIds.length !== 1" @click="discussUnit(selectedUnitIds[0])">与 Agent 讨论所选字段</el-button>
          <div v-if="activeStep===1" class="build-controls">
            <el-input v-model="unitSearch" clearable aria-label="搜索本页字段" placeholder="搜索业务名称或原文" />
            <el-select v-model="unitFilter" aria-label="筛选字段"><el-option label="全部字段" value="all"/><el-option label="待核对" value="review"/><el-option label="已忽略" value="ignored"/></el-select>
            <el-pagination v-model:current-page="unitPage" :total="filteredUnits.length" :page-size="20" layout="total, prev, pager, next" />
          </div>
          <el-table v-if="activeStep===1" :key="sourcePath" :data="visibleUnits" size="small" row-key="id" max-height="520" @selection-change="selectUnits">
            <el-table-column type="selection" reserve-selection width="45" />
            <el-table-column type="expand"><template #default="{ row }"><div v-for="(reference, index) in row.sources" :key="index" class="build-source"><p>来源 {{ reference.sourceId }} · 区间 {{ reference.start }}—{{ reference.end }} · {{ sourceFor(reference)?.selector }}</p><pre>完整原值：{{ sourceFor(reference)?.rawValue }}</pre><pre>区域上下文：{{ sourceFor(reference)?.context }}</pre><p>重复区域：{{ sourceFor(reference)?.repeatGroup || '未识别' }}</p></div></template></el-table-column>
            <el-table-column label="业务名称" min-width="160"><template #default="{ row }"><el-input v-model="row.name" aria-label="内容单元业务名称" maxlength="100" :disabled="busy" @input="markUnitsDirty" /></template></el-table-column>
            <el-table-column label="建议类型" width="155"><template #default="{ row }"><el-select v-model="row.fieldType" aria-label="内容单元建议类型" :disabled="busy" @change="markUnitsDirty"><el-option v-for="type in unitTypes" :key="type.value" :value="type.value" :label="type.label" /></el-select></template></el-table-column>
            <el-table-column label="原始值（只读）" min-width="200"><template #default="{ row }"><pre class="build-unit-value">{{ unitOriginal(row) }}</pre></template></el-table-column>
            <el-table-column label="区域定位" min-width="180"><template #default="{ row }">{{ row.sources.map((reference: UnitReference) => sourceFor(reference)?.selector).join('；') }}</template></el-table-column>
            <el-table-column label="疑点／人工说明" min-width="180"><template #default="{ row }"><el-input v-model="row.doubts" aria-label="内容单元疑点" type="textarea" :rows="2" maxlength="2000" :disabled="busy" @input="markUnitsDirty" /></template></el-table-column>
            <el-table-column label="忽略" width="70"><template #default="{ row }"><el-checkbox v-model="row.ignored" aria-label="忽略内容单元" :disabled="busy" @input="markUnitsDirty" /></template></el-table-column>
            <el-table-column label="操作" width="185"><template #default="{ row }"><el-button size="small" :disabled="busy || !draftBindingPreview" @click="draftBindingPreview?.open(row.id)">定位</el-button><el-button size="small" :disabled="busy" @click="splitUnit(row)">拆分</el-button><el-button v-if="counterRange(row)" size="small" :disabled="busy" @click="confirmCounterRange(row)">确认统计数值</el-button></template></el-table-column>
          </el-table>
        </template>
      </el-card>
      <el-alert v-if="activeStep===1 && pageListNotice?.sourcePath===sourcePath && pageListNotice?.siteId===Number(siteContext.currentSite.id)"
        :title="pageListNotice.message" type="success" show-icon :closable="false" />
      <PageListSupplementPanel v-if="activeStep===1 && pageListScope"
        :key="`${pageListScope.plan.id}:${pageListScope.plan.revision}:${sourcePath}:${planTargetMenuId}`"
        :site-id="pageListScope.plan.siteId" :source-path="sourcePath" :plan="pageListScope.plan"
        :owner-menu-id="pageListScope.ownerMenuId" :owner-label="currentMenuLabel" :menus="pageListScope.menus"
        :disabled="busy || unitsDirty || !!siteModelPanel?.hasUnsavedChanges()"
        @refresh-menus="refreshColumns" @busy="pageListBusy=$event" @saved="receivePageListSupplement" />
      <el-card v-if="activeStep===1 && searchEvidence" shadow="never" aria-label="检索结果审核">
        <template #header><strong>检索结果审核</strong></template>
        <p>原页标题「{{ searchEvidence.heading }}」，样例计数「{{ searchEvidence.counter }}」。先选择允许访客检索的栏目，核对其字段设置里的「公开检索」。</p>
        <el-button @click="router.push('/admin/v2/cms/menus')">打开栏目管理核对公开检索字段</el-button>
        <p>接入结果模板后，用真实关键词验证结果链接、无结果提示和分页。</p>
      </el-card>
      <el-card v-if="siteContext.currentSite && sourcePath && formTargetMenu?.id" v-show="activeStep===1" shadow="never">
        <NativeFormImportPanel :site-id="Number(siteContext.currentSite.id)" :menu-id="formTargetMenu.id" :source-path="sourcePath" :menu-label="formTargetMenu.className || formTargetMenu.name" :disabled="busy" standalone :can-review-navigation="!!navigationLinkScope" @review-navigation="reviewFormNavigation" @busy="nativeFormBusy=$event" @changed="refreshChangedBusinessEvidence" />
        <el-button size="small" :disabled="busy" @click="changeStep(4)">在工作台配置此表单页路由</el-button>
      </el-card>
      <ReviewedListImportPanel v-if="activeStep===3" :site-id="siteContext.currentSite?.id ? Number(siteContext.currentSite.id) : null" :package-version="packageStatus?.version" :disabled="busy" @applied="refreshChangedBusinessEvidence" />
      <ListPairingPanel :reviewed-plan="reviewedNavigation?.plan || siteModelPlan" :reviewed-nodes="reviewedNavigation?.navigation.nodes" @open-source="(path,menuId)=>openWorkbenchTarget(path,menuId,1)" :package-version="packageStatus?.version" @open-list-seed="openChecklistSeeds" @manage-content="openModelContent" v-if="visitedStages.includes(3)" v-show="activeStep===3" :site-id="siteContext.currentSite?.id ? Number(siteContext.currentSite.id) : null" :source-path="sourcePath || null" :source-paths="packagePagePaths" :columns="columns" :preferred-menu-id="planTargetMenuId" :detail-plan="listDetailPlan" :navigation-plan-id="reviewedNavigation?.plan.id" :disabled="busy" @review-detail="openSharedDetailReview" @configure-routes="menuId=>openWorkbenchTarget(sourcePath,menuId,4)" @public-list-changed="refreshTargetAfterChange" />
      <el-card v-if="activeStep===2 && checklistTarget?.fieldPlanId && textDraftHtml" shadow="never">
        <template #header><strong>审核当前栏目私有草稿 · {{ currentMenuLabel }}</strong></template>
        <p>核对已保存内容及页面样式。需要调整页面字段时，打开整页字段审核。</p>
        <iframe title="当前待办私有草稿预览" sandbox="" referrerpolicy="no-referrer" :srcdoc="textDraftHtml" style="width:100%;height:55vh;border:0"/>
      </el-card>
      <el-card v-if="visitedStages.includes(2)" v-show="activeStep===2" shadow="never">
        <template #header><strong id="workbench-field-plan-heading" tabindex="-1">字段映射计划</strong></template>
        <p v-if="sharedDetailTemplate">从已确认并初始化的共享详情生成本栏目模板的标题和正文映射。</p>
        <p v-else-if="usePackageFieldPlan">根据 {{ sourcePath }} 的已保存修订 {{ unitsWorkspace?.revision }}（{{ unitsWorkspace?.units.length }} 项）生成字段计划，再核对复用、新增及待处理项。</p>
        <p v-else>按整站结构中已审核的记录和字段生成计划。已保存的字段计划可继续审核和初始化。</p>
        <div class="build-controls"><span>当前目标：{{ currentMenuLabel }}</span><el-button type="primary" :disabled="busy || !!newBindingNotice || !unitsWorkspace?.revision || unitsDirty" :loading="planLoading" @click="createPlan">{{ sharedDetailTemplate ? '按已审核共享详情生成模板字段计划' : usePackageFieldPlan ? (planChoicesDirty ? '按审核选择重新生成计划' : '按整包审核生成字段计划') : '按已确认结构生成计划' }}</el-button><el-button :disabled="busy || !(manualPlanId.trim() || savedPlanId || fieldPlan)" @click="reloadPlan">读取已保存计划</el-button></div>
        <p v-if="unitsDirty || !unitsWorkspace?.revision">请先保存本页内容单元，再生成字段计划。<el-button link @click="changeStep(1)">核对内容单元</el-button></p>
        <el-alert v-if="newBindingNotice" :title="newBindingNotice" type="info" :closable="false" />
        <el-alert v-if="planError" :title="planError" type="error" :closable="false" />
        <div class="build-controls">
          <el-select v-model="savedPlanId" aria-label="服务端已保存字段计划" placeholder="选择当前页面与栏目的已有计划" :disabled="busy || !savedPlans.length" @change="manualPlanId = ''"><el-option v-for="plan in savedPlans" :key="plan.id" :value="plan.id" :label="`${plan.status === 'DRAFT' ? '待确认' : '已确认'} · ${plan.id} · 修订${plan.unitsRevision}`" /></el-select>
          <el-input v-model="manualPlanId" aria-label="已知字段计划ID" placeholder="或输入已知计划ID读取" maxlength="36" :disabled="busy" style="max-width:380px" />
        </div>
        <el-alert v-for="warning in planHistoryWarnings" :key="warning" :title="warning" type="info" :closable="false" />
        <template v-if="fieldPlan">
          <p>计划 {{ fieldPlan.id }} · {{ {DRAFT: '待确认', CONFIRMED: '字段计划已确认', CONFIRMED_WITH_PENDING: '已确认部分，仍有待处理'}[fieldPlan.status] }} · 包 {{ fieldPlan.packageVersion }} · 来源修订 {{ fieldPlan.unitsRevision }} · {{ fieldPlan.pageType === 0 ? '单页 menuField / Oneshot' : '列表 modelField / Content' }}</p>
          <el-alert v-if="planStale" title="来源或人工修正已变化，请重新生成字段计划。" type="warning" :closable="false" />
          <el-alert v-if="planChoicesDirty" :title="usePackageFieldPlan ? '本机保留了还未生成计划的字段选择。' : '本机仍保留未提交的旧映射选择。可重新读取已保存方案，或按整站结构重新生成。'" type="info" :closable="false" />
          <section v-if="repeatedFieldUnits.size && fieldPlan.status==='DRAFT' && !planStale"
            aria-label="重复区域维护审核" class="review-continuation">
            <div><strong>{{ repeatedFieldUnits.size }} 项来源位于重复条目</strong>
              <p>请按原页分组核对轮播和列表的维护方式。需要单独维护的固定内容，可在字段对应中手动选择「提出新增字段」。</p>
              <el-button :disabled="busy || planLoading" @click="openChecklistSeeds(fieldPlan.sourcePath,fieldPlan.menuId)">查看重复区域与列表审核</el-button>
            </div>
          </section>
          <section v-if="overlaps.length && !planStale" id="binding-overlap-review" tabindex="-1" aria-label="原文绑定冲突" class="review-continuation">
            <div><strong>原文绑定冲突（{{ overlaps.length }} 处）</strong>
              <p>同一段原文有多个维护来源。完全重复时可选择要保留的字段；部分重叠时请回到原页调整区间。</p>
              <div v-for="group in overlaps" :key="group.key" class="build-controls">
                <strong>{{ group.text }}</strong>
                <el-select v-if="group.exact" v-model="keepBinding[group.key]" :aria-label="group.text+'保留字段'" placeholder="选择要保留的字段" :disabled="busy || unitsSaving || planLoading">
                  <el-option v-for="row in group.rows" :key="row.unitId" :value="row.unitId" :label="'保留：'+row.name"/>
                </el-select>
                <el-button v-for="row in group.rows" :key="row.unitId" link @click="openDraftReview(row.unitId)">定位「{{ row.name }}」</el-button>
              </div>
              <el-button type="primary" :disabled="busy || unitsSaving || planLoading || !overlaps.every(group=>group.exact && keepBinding[group.key])"
                @click="repairOverlapPlan">保存修正并重新生成计划</el-button>
            </div>
          </section>
          <p v-if="planPreviewUnavailable">{{ planPreviewUnavailable }}</p>
          <VisualBindingSuggestions v-else-if="activeStep===2" ref="planBindingPreview" title="整页审核 · 字段映射计划"
            :site-id="Number(siteContext.currentSite.id)" :source-path="fieldPlan.sourcePath" :package-version="fieldPlan.packageVersion"
            :digest="fieldPlan.digest" :revision="fieldPlan.unitsRevision" :units="planPreviewUnits" :sources="planPreviewSources"
            :mappings="planPreviewMappings"
            :action-error="planError" :footer-notice="pageDraftSummary || (planChoicesDirty ? '旧映射选择已保留在本地。可重新读取已保存方案，或按整站结构重新生成。' : fieldPlan.status === 'DRAFT' ? '核对复用字段的用途。' : completedDetail ? '详情初值已保存，可关闭审核后读取私有预览或继续配置列表。' : '字段计划已确认。可关闭审核后检查当前内容状态。')"
            :disabled="busy || !hasPackage">
            <template #selected-actions="{ unit }">
              <el-button v-if="unit.ignored" size="small" :disabled="busy || planStale" @click="reviseIgnoredUnit(unit.id)">在内容单元审核中修正</el-button>
              <el-button size="small" :disabled="chatDisabled || planStale" @click="discussUnit(unit.id)">与 Agent 讨论所选字段</el-button>
            </template>
            <template #mapping="{ unit }">
              <template v-for="row in [planBindingsById.get(unit.id)]" :key="unit.id">
                <div v-if="row" class="build-review-mapping">
                  <strong>字段映射</strong>
                  <FieldSourceChoice v-if="editableFieldSource(row)" v-model="fieldChoices[row.unitId]" :row="row"
                :menus="childNameOptions(row)" :fields="compatiblePackageFields(row)" :disabled="busy || planLoading" />
                  <p v-else-if="row.action==='MENU_NAME'">{{ menuReferenceLabel(row) }}</p>
                  <p v-else-if="row.fieldName">{{ row.fieldName }}</p>
                  <p v-if="repeatedFieldUnits.has(row.unitId)">来源位于重复条目，请核对分组后决定是否使用独立字段。</p>
                  <p v-if="row.action==='CONFLICT'">{{ usePackageFieldPlan ? '选择数据来源后，回到字段映射计划，按审核选择重新生成计划。' : '请在整站结构核对字段对应后重新生成计划。' }}</p>
                  <template v-if="row.action==='PENDING_TYPE'">
                    <p>该类型或来源尚不能绑定。可修正内容单元，或在确认计划时保持待处理。</p>
                    <el-button size="small" :disabled="busy" @click="openDraftReview(row.unitId)">回整页内容单元修正</el-button>
                  </template>
                  <p v-if="row.action==='IGNORED'">暂不绑定，原页面内容保留。</p>
                  <p v-if="row.fieldName && row.currentValuePresent">现有内容：{{ fieldPlan.fields.find(field => field.fieldName === row.fieldName)?.currentValuePreview }}（此计划不覆盖）</p>
                </div>
              </template>
            </template>
            <template #actions>
              <el-button v-if="usePackageFieldPlan && fieldPlan?.status==='DRAFT' && !planStale"
                :loading="planLoading" :disabled="busy || planLoading || (!Object.keys(pageSuggestions).length && !planChoicesDirty)"
                @click="draftWholePage">{{ Object.keys(pageSuggestions).length ? '生成整页映射草稿' : '更新整页映射草稿' }}</el-button>
              <el-button v-if="fieldPlan.status==='DRAFT'" type="primary" :loading="planApplying"
                :disabled="busy || !fieldPlan.canConfirm || !!overlaps.length || planChoicesDirty || planStale" @click="applyPlan">确认整页字段计划</el-button>
            </template>
          </VisualBindingSuggestions>
          <el-button v-if="usePackageFieldPlan && fieldPlan?.status==='DRAFT' && !planStale"
                :loading="planLoading" :disabled="busy || planLoading || (!Object.keys(pageSuggestions).length && !planChoicesDirty)"
                @click="draftWholePage">{{ Object.keys(pageSuggestions).length ? '生成整页映射草稿' : '更新整页映射草稿' }}</el-button>
          <p v-if="pageDraftSummary" role="status">{{ pageDraftSummary }}</p>
          <details><summary>字段计划说明</summary><el-alert v-for="warning in fieldPlan.warnings" :key="warning" :title="warning" type="info" :closable="false" /></details>
          <div class="build-controls"><span>{{ fieldPlan.bindings.length - ignoredPlanBindings }} 项参与绑定，{{ ignoredPlanBindings }} 项已忽略</span><el-switch v-if="ignoredPlanBindings" v-model="showIgnoredPlanBindings" active-text="显示已忽略项" /></div>
          <el-table :data="visiblePlanBindings" size="small" max-height="460">
            <el-table-column prop="name" label="业务名称" min-width="140" />
            <el-table-column prop="fieldType" label="类型" width="110" />
            <el-table-column prop="valuePreview" label="来源原值" min-width="160" show-overflow-tooltip />
            <el-table-column label="原DOM定位／字符区间" min-width="200"><template #default="{ row }">{{ row.sources.map((reference: FieldPlanReference) => `${fieldPlan?.sources.find(source => source.id === reference.sourceId)?.selector} [${reference.start}, ${reference.end})`).join('；') }}</template></el-table-column>
            <el-table-column label="字段对应" min-width="240"><template #default="{ row }">
              <FieldSourceChoice v-if="editableFieldSource(row)" v-model="fieldChoices[row.unitId]" :row="row"
                :menus="childNameOptions(row)" :fields="compatiblePackageFields(row)" :disabled="busy || planLoading" />
              <span v-else>{{ row.action==='MENU_NAME' ? menuReferenceLabel(row) : row.fieldName || (row.action === 'IGNORED' ? '已忽略' : '待处理，不绑定') }}<template v-if="row.locked"> · 已审核复用</template></span>
            </template></el-table-column>
            <el-table-column label="状态／影响" min-width="240"><template #default="{ row }"><template v-if="fieldChoices[row.unitId]?.startsWith('MENU_NAME:') && fieldChoices[row.unitId]!=='MENU_NAME:'+row.menuId"><el-tag>已选栏目名称</el-tag><p>点击「按审核选择重新生成计划」保存此项对应。</p></template><template v-else><el-tag :type="row.action === 'CONFLICT' ? 'danger' : row.action === 'PENDING_TYPE' ? 'warning' : 'info'">{{ {MENU_NAME: '引用栏目名称', REUSE: '复用', CREATE: '新增', IGNORED: '已忽略', PENDING_TYPE: '待处理', CONFLICT: '需修正'}[row.action as FieldPlanBinding['action']] }}</el-tag><p>{{ row.message }}</p></template><p v-if="row.fieldName && row.currentValuePresent">单页已有值：{{ fieldPlan.fields.find(field => field.fieldName === row.fieldName)?.currentValuePreview }}（此计划不覆盖）</p></template></el-table-column>
          </el-table>
          <el-button type="primary" :loading="planApplying" :disabled="busy || !fieldPlan.canConfirm || !!overlaps.length || planChoicesDirty || planStale || fieldPlan.status !== 'DRAFT'" @click="applyPlan">确认计划与所需字段新增</el-button>
          <p v-if="fieldPlan.status !== 'DRAFT'">{{ completedDetail ? '详情已关联到草稿。' : '字段计划已保存。下一步检查文本绑定和当前内容状态。' }}</p>
        </template>
      </el-card>
      <el-card v-if="visitedStages.includes(2)" v-show="activeStep===2" shadow="never">
        <template #header><strong id="workbench-initial-content" tabindex="-1">内容初始化与私有草稿</strong></template>
        <p>{{ completedDetail ? '详情已关联到草稿，可读取当前 CMS 值的私有预览。' : '先检查绑定和草稿状态，再按需确认初值。' }}</p><details><summary>可绑定范围与初始化限制</summary><p>单页初始化只填空字段。补齐详情草稿时，差异表列出的已绑定字段可在确认后写入计划初值。多行限完整纯文本/br容器或统一内联包装，源码格式换行可能可视，请先核对预览。Editor仅支持人工确认的独立完整HTML区域；正文清洗与资源映射影响在下方展示，固定样式、布局依赖保留待处理。图片、原生视频及封面支持独立字段绑定；响应式多资源、脚本背景、混合样式、多来源、枚举及关联仍需核对处理。</p></details>
        <el-radio-group v-if="fieldPlan?.pageType === 1 && !completedDetail && !fieldPlan.sharedDetailSeedPlanId" v-model="detailMode" :disabled="busy || !canCheckTextBinding" aria-label="详情草稿初始化方式">
          <el-radio-button value="existing">补齐已有草稿</el-radio-button>
          <el-radio-button value="preserve">保留当前内容</el-radio-button>
          <el-radio-button value="new">新建独立草稿</el-radio-button>
        </el-radio-group>
        <p v-if="fieldPlan?.pageType === 1 && !completedDetail && detailMode !== 'preserve'">共用模型的其他栏目可选择“保留当前内容”，使用本栏目已有的草稿。</p>
        <div class="build-controls">
          <el-button v-if="overlaps.length && !planStale" type="primary" @click="focusOverlapReview">处理原文绑定冲突（{{ overlaps.length }} 处）</el-button><el-button v-else :disabled="busy || !canCheckTextBinding" @click="textBindingAction('inspect')">检查文本绑定范围</el-button>
          <el-button v-if="!isExistingDetail && !completedDetail" type="primary" :disabled="busy || !textBinding || textBinding.initialized || !textBinding.bound.length || (onlyMenuNames && fieldPlan?.pageType===1) || !canCheckTextBinding" @click="textBindingAction('initialize')">{{ fieldPlan?.pageType === 1 ? '确认新建独立草稿' : onlyMenuNames ? '生成栏目引用草稿' : '确认原文初始化' }}</el-button>
          <el-button :disabled="busy || !(textBinding?.initialized || completedDetail) || planStale" @click="textBindingAction('preview')">读取真实私有草稿</el-button>
          <el-button :disabled="busy || !textBinding?.initialized || !fieldPlan" @click="openTextBindingEditor">打开原生内容编辑器</el-button>
          <el-button v-if="fieldPlan && !planStale && (textBinding?.initialized || completedDetail)" type="primary" :disabled="busy"
            @click="openWorkbenchTarget(fieldPlan.sourcePath,fieldPlan.menuId,4)">继续模板与发布</el-button>
        </div>
        <template v-if="isExistingDetail && !completedDetail">
          <p v-if="preserveDetailCurrent">使用所选草稿生成详情预览，草稿字段保留当前值。</p>
          <div class="build-controls">
            <el-button :disabled="busy || !canCheckTextBinding" @click="loadDetailCandidates(1)">读取本栏目草稿</el-button>
            <el-select v-if="detailCandidates.length" v-model="selectedDetailId" placeholder="按标题与 ID 选择草稿" aria-label="详情草稿记录" :disabled="busy || !canCheckTextBinding">
              <el-option v-for="row in detailCandidates" :key="row.recordId" :value="row.recordId" :label="`${row.title || '无标题'}（ID ${row.recordId}）`" />
            </el-select>
            <el-button :disabled="busy || !canCheckTextBinding || !selectedDetailId" @click="inspectSelectedDetail">{{ preserveDetailCurrent ? '核对当前字段' : '查看字段差异' }}</el-button>
          </div>
          <el-pagination v-if="detailCandidateTotal > detailPageSize" :current-page="detailCandidatePage" :page-size="detailPageSize" :total="detailCandidateTotal" :disabled="busy" layout="prev, pager, next" @current-change="loadDetailCandidates" />
          <p v-if="detailCandidatesLoaded && !detailCandidates.length">本页没有可选草稿。</p>
          <template v-if="existingDetailPreview">
            <p>{{ existingDetailPreview.title || '无标题' }}（ID {{ existingDetailPreview.recordId }}）</p>
            <el-table :data="existingDetailPreview.changes" size="small">
              <el-table-column prop="name" label="字段" />
              <el-table-column prop="field" label="字段标识" />
              <el-table-column prop="currentPreview" label="当前值" />
              <el-table-column v-if="!preserveDetailCurrent" prop="initialPreview" label="计划初值" />
              <el-table-column label="处理"><template #default="{ row }"><el-tag :type="row.action === 'REPLACE' ? 'warning' : 'info'">{{ detailChangeLabels[row.action as keyof typeof detailChangeLabels] }}</el-tag></template></el-table-column>
            </el-table>
            <el-alert v-if="!existingDetailPreview.applicable" :title="preserveDetailCurrent ? '当前草稿无法关联，请重新检查记录状态。' : '当前草稿无法补齐，请重新检查记录状态与字段差异。'" type="warning" :closable="false" show-icon />
            <el-button type="primary" :disabled="busy || !canConfirmExistingDetail" @click="textBindingAction('initialize')">{{ preserveDetailCurrent ? '确认关联并保留内容' : '确认补齐所选草稿' }}</el-button>
          </template>
        </template>
        <el-alert v-if="textBindingError" :title="textBindingError" type="error" :closable="false" show-icon />
        <el-button :disabled="busy || !fieldPlan || fieldPlan.status === 'DRAFT' || planStale" @click="checkBindingRecovery">检查持久应用状态 / 恢复</el-button>
        <div v-if="recoveryState" class="build-source">
          <p>持久阶段：{{ recoveryState.phase }} · 详情ID：{{ recoveryState.recordId ?? '未分配' }}</p><p>{{ recoveryState.message }}</p>
          <el-table :data="recoveryState.differences" size="small"><el-table-column prop="name" label="允许字段" /><el-table-column prop="initialPreview" label="计划初始值（长值展示截断）" /><el-table-column prop="currentPreview" label="当前值（只读）" /></el-table>
          <el-button v-for="action in recoveryState.actions" :key="action" :disabled="busy || planStale" @click="applyBindingRecovery(action)">{{ recoveryLabels[action] }}</el-button>
        </div>
        <template v-if="textBinding">
          <p>{{ textBinding.initialized ? '已完成初始化；预览读取当前CMS值，重复确认不会覆盖人工编辑。' : '尚未初始化；请核对可绑定项和待处理项。' }}</p>
          <p v-if="textBinding.recordId">详情草稿记录 {{ textBinding.recordId }}，仅DRAFT状态可预览；编辑后可重新读取真实预览，其他工作流状态请通过CMS正式流程处理。</p>
          <el-alert v-for="warning in textBinding.warnings" :key="warning" :title="warning" type="info" :closable="false" />
          <div v-for="(prepared, unitId) in textBinding.preparedContents || {}" :key="unitId" class="build-source">
            <p>正文初值审计：{{ fieldPlan?.bindings.find(binding => binding.unitId === unitId)?.name || unitId }}；确认初始化将使用清洗与资源映射后的值，原HTML证据保留。</p>
            <el-alert v-for="change in prepared.changes" :key="change" :title="change" type="warning" :closable="false" />
            <details><summary>服务端完整原HTML（只读证据）</summary><pre>{{ prepared.originalHtml }}</pre></details>
            <details><summary>将写入CMS的清洗后HTML</summary><pre>{{ prepared.valueHtml }}</pre></details>
            <el-table :data="prepared.resourceMappings" size="small"><el-table-column prop="attribute" label="属性" /><el-table-column prop="original" label="原资源地址" /><el-table-column prop="resolved" label="当前包逻辑路径" /></el-table>
          </div>
          <div v-if="Object.keys(textBinding.mediaMappings || {}).length"><p>媒体仅绑定人工确认的src/source/poster；下列引用按当前站点原生资源规则校验。视频隔离预览只显示封面，不播放；背景、多资源及脚本播放器仍待处理。</p><p v-for="(reference, unitId) in textBinding.mediaMappings" :key="unitId">{{ fieldPlan?.bindings.find(binding => binding.unitId === unitId)?.name || unitId }}：{{ reference }}</p></div>
          <details v-if="textBinding.domChanges?.length"><summary>统计限定符 DOM 差异（须人工核对）</summary><section v-for="change in textBinding.domChanges" :key="change.unitId"><p>{{ change.reason }}</p><p>调整前</p><pre class="build-source">{{ change.before }}</pre><p>调整后</p><pre class="build-source">{{ change.after }}</pre></section></details>
          <details v-if="textBinding.compiledHtml"><summary>实际私有草稿模板（只读，不发布）</summary><pre class="build-source">{{ textBinding.compiledHtml }}</pre></details>
          <p v-if="onlyMenuNames && fieldPlan?.pageType===1">这些标题使用栏目名称。请选择已有详情草稿并关联模板。</p>
          <el-table :data="textBinding.bound" size="small"><el-table-column prop="name" label="可绑定业务项" /><el-table-column label="数据来源"><template #default="{row}">{{ row.action==='MENU_NAME' ? menuReferenceLabel(row) : row.fieldName }}</template></el-table-column><el-table-column prop="valuePreview" label="来源原文" /></el-table>
          <el-table v-if="textBinding.pending.length" :data="textBinding.pending" size="small"><el-table-column label="待处理单元"><template #default="{ row }">{{ fieldPlan?.bindings.find(binding => binding.unitId === row.unitId)?.name || row.unitId }}</template></el-table-column><el-table-column prop="reason" label="原因（不初始化、不绑定）" /></el-table>
        </template>
        <el-alert v-for="warning in textDraftWarnings" :key="warning" :title="previewWarning(warning)" type="warning" :closable="false" />
        <iframe v-if="textDraftHtml && !checklistTarget?.fieldPlanId" title="真实私有草稿预览" sandbox="" referrerpolicy="no-referrer" :srcdoc="textDraftHtml" style="width:100%;height:560px;border:1px solid var(--admin-border-subtle);margin-top:12px" />
      </el-card>
      <el-card v-if="visitedStages.includes(4)" v-show="activeStep===4" shadow="never">
        <template #header><strong>预览与公开 · {{ currentMenuLabel }}</strong></template>
        <p>配置当前栏目路由并发布审核通过的内容，再应用对应模板。从站点服务打开实际地址，核对列表、详情或单页。</p>
        <div class="build-controls">
          <el-button v-if="targetBindingSource && targetBindingSource!==sourcePath" :disabled="busy" @click="openTargetPublication">读取单页或详情应用</el-button>
          <el-button v-if="targetListSource && targetColumn?.pageType===1" :disabled="busy" @click="goToList">核对列表模板应用</el-button>
          <el-button v-if="formTargetMenu && sourcePath" :disabled="busy" @click="changeStep(1)">返回原页表单审核</el-button>
          <el-button v-if="navigationLinkScope && sourcePath" :disabled="busy" @click="focusNavigationLinks">审核原页公共链接</el-button>
          <el-button :disabled="busy" @click="router.push('/admin/v2/site-service')">站点服务</el-button>
          <el-button :disabled="busy" @click="focusReleaseCheck">查看静态产物核验</el-button>
        </div>
        <details><summary>原生管理入口</summary><div class="build-controls">
          <el-button :disabled="busy || !targetColumn" @click="router.push('/admin/v2/cms/menus')">栏目管理</el-button>
          <el-button :disabled="busy || !hasPackage" @click="router.push('/admin/v2/cms/template-management')">模板管理</el-button>
          <el-button :disabled="busy || !planTargetMenuId" @click="planTargetMenuId && openModelContent(planTargetMenuId)">内容管理</el-button>
        </div></details>
        <WorkbenchHomeBannerPanel v-if="activeStep===4 && targetHome"
          :site-id="Number(siteContext.currentSite.id)" :plan="siteModelPlan" :source-path="targetNavigation?.sourcePath || sourcePath || null"
          :disabled="otherPublishBusy || workbenchPublishBusy || navigationLandingBusy || navigationLinksBusy || sharedContentBusy"
          @busy="homeBannerBusy=$event" @changed="homeBannerChanged" @structure="changeStep(5)" @content="openModelContent" />
        <WorkbenchNavigationLandingPanel @open-source="(path,menuId)=>openWorkbenchTarget(path,menuId,1)" @review-structure="reviewLandingEntry" v-if="activeStep===4 && targetOverview && reviewedNavigation"
          :site-id="Number(siteContext.currentSite.id)" :plan="reviewedNavigation.plan" :node="targetOverview" :nodes="reviewedNavigation.navigation.nodes"
          :origin-plan-id="reviewedNavigation.navigation.originPlanId"
          :focus-card="checklistTarget?.action==='navigation-landing' ? checklistTarget.nodeId ?? undefined : undefined" @prerequisite="openLinkPrerequisite"
          :navigation-revision="reviewedNavigation.navigation.revision"
          :disabled="otherPublishBusy || workbenchPublishBusy || homeBannerBusy || navigationLinksBusy || sharedContentBusy"
          @busy="navigationLandingBusy=$event" @changed="refreshTargetAfterChange" />
      </el-card>
      <WorkbenchPublishPanel v-if="activeStep===4 && (targetNavigation?.kind!=='DIRECTORY' || targetHome) && !targetNavigation?.drift" ref="workbenchPublishPanel" :site-id="Number(siteContext.currentSite.id)" :menu-id="planTargetMenuId ?? null" :refresh-key="publishRefreshKey" :disabled="otherPublishBusy || homeBannerBusy || navigationLandingBusy || navigationLinksBusy || sharedContentBusy" @busy="workbenchPublishBusy=$event" @changed="refreshTargetAfterChange" />

      <WorkbenchNavigationLinksPanel v-if="visitedStages.includes(4) && navigationLinkScope && sourcePath" id="workbench-navigation-links" tabindex="-1"
        v-show="activeStep===4" ref="navigationLinksPanel" :site-id="Number(siteContext.currentSite.id)" :plan-id="navigationLinkScope.plan.id"
        :plan-revision="navigationLinkScope.plan.revision" :navigation-revision="navigationLinkScope.navigation.revision" :package-version="navigationLinkScope.plan.packageVersion"
        :source-path="sourcePath" :source-paths="packagePagePaths"
        :nodes="navigationLinkScope.navigation.status==='APPLIED' ? navigationLinkScope.navigation.nodes : []" :disabled="busy"
        :home="targetHome" :root-menu-id="rootMenuId" :list-source="listReviewTarget" :can-open-template="!!fieldPlan && fieldPlan.sourcePath===sourcePath && fieldPlan.menuId===planTargetMenuId"
        :focus-href="checklistTarget?.sourcePath===sourcePath ? checklistTarget?.auditHref : undefined"
        @prerequisite="openLinkPrerequisite" @busy="navigationLinksBusy=$event" @template="focusPublicBinding" @list="goToList" @changed="refreshChangedBusinessEvidence"
        @manage-templates="router.push('/admin/v2/cms/template-management')" />
      <WorkbenchSharedContentPanel v-if="visitedStages.includes(4) && siteModelPlan?.status==='CONFIRMED' && sourcePath" v-show="activeStep===4"
        ref="sharedContentPanel" :site-id="Number(siteContext.currentSite.id)" :plan-id="siteModelPlan.id"
        :plan-revision="siteModelPlan.revision" :package-version="siteModelPlan.packageVersion" :source-path="sourcePath"
        :disabled="otherPublishBusy || workbenchPublishBusy || homeBannerBusy || navigationLandingBusy || navigationLinksBusy" @busy="sharedContentBusy=$event"
        @structure="changeStep(5)" @content="openModelContent" @changed="refreshChangedBusinessEvidence" />
    </template>
    <PublicBindingApplicationPanel v-if="fieldPlan && fieldPlan.sourcePath===sourcePath && fieldPlan.menuId===planTargetMenuId && (activeStep===4 || fieldPlan.pageType===0) && siteContext.currentSite" v-show="activeStep===4" id="workbench-public-binding" tabindex="-1" ref="publicBindingPanel" :site-id="Number(siteContext.currentSite.id)" :root-menu-id="rootMenuId" :plan="fieldPlan" :configured-route="targetColumn?.listPageUrl" :stale="planStale" :disabled="busy" @busy="publicBindingBusy=$event" @changed="publicBindingChanged" @review="changeStep(2)" />
    <details v-if="siteContext.currentSite" class="build-advanced" @toggle="advancedToolsOpen=($event.target as HTMLDetailsElement).open"><summary>高级工具与原始证据</summary><p>经验功能保持当前暂停策略；以下入口不会自动确认或导入内容。</p><template v-if="advancedToolsOpen">
      <ProjectExperiencePanel :site-id="siteContext.currentSite?.id ? Number(siteContext.currentSite.id) : null" :source-path="sourcePath || null" :workspace-revision="unitsWorkspace?.revision" :used-references="analysisResult?.references || []" />
      <el-card shadow="never">
        <template #header><strong>原始提取与旧版独立导入</strong></template>
        <p>读取 UI 页面中的文字、图片和链接，核对来源后显式映射字段。每次确认仅导入一条草稿，不自动发布。</p>
        <div class="build-controls"><el-select :model-value="sourcePath" @change="changeSourcePath" aria-label="提取来源页面" placeholder="选择页面" filterable :disabled="busy || seedLoading" style="width: 360px"><el-option v-for="page in seedPages" :key="page.sourcePath" :label="page.sourcePath" :value="page.sourcePath" /></el-select><el-button :loading="seedLoading" :disabled="busy || !sourcePath" @click="previewSeed">提取并预览</el-button></div>
        <el-alert v-if="seedError" :title="seedError" type="error" :closable="false" />
        <template v-if="seedPreview">
          <p>{{ seedPreview.title || seedPreview.sourcePath }} · DOM 规则提取 · {{ seedPreview.items.length }} 个候选</p>
          <el-alert v-for="warning in seedPreview.warnings" :key="warning" :title="warning" type="warning" :closable="false" />
          <el-table :data="seedPreview.items" size="small" max-height="400">
            <el-table-column prop="kind" label="类型" width="90" />
            <el-table-column prop="text" label="文字" min-width="180" show-overflow-tooltip />
            <el-table-column prop="value" label="值／资源路径" min-width="200" show-overflow-tooltip />
            <el-table-column prop="selector" label="来源定位" min-width="160" show-overflow-tooltip />
            <el-table-column prop="repeatGroup" label="重复分组" min-width="100" show-overflow-tooltip />
          </el-table>
          <h3>映射到目标栏目</h3>
          <el-select v-model="targetMenuId" aria-label="目标栏目" placeholder="选择栏目以加载可写字段" :disabled="importing" @change="loadSeedFields"><el-option v-for="menu in columns" :key="menu.id" :value="menu.id!" :label="menu.className || menu.name || String(menu.id)" /></el-select>
          <el-form label-width="120px">
            <el-form-item v-for="field in seedFields" :key="field.fieldName" :label="field.title || field.fieldName">
              <el-select v-model="mapping[field.fieldName]" clearable filterable :disabled="importing" placeholder="选择已提取候选（留空不导入）"><el-option v-for="item in seedPreview.items" :key="item.id" :value="item.id" :label="`${item.kind} · ${item.text || item.value} · ${item.selector}`" /></el-select>
            </el-form-item>
          </el-form>
          <el-button type="primary" :disabled="busy || seedLoading || !mapping.title || !targetMenuId" :loading="importing" @click="importSeed">确认映射并导入一条草稿</el-button>
          <p v-if="importResult">{{ importResult }}</p>
        </template>
      </el-card>
    </template></details>
    <WorkbenchApplicationDialog v-if="applicationContext" :plan="applicationContext.plan" :navigation="applicationContext.navigation"
      @busy="applicationBusy=$event" @close="closeWorkbenchApplication()" @applied="receiveWorkbenchApplication" @fields="openAppliedFields" />
  </section>
</template>

<script setup lang="ts">
import { computed, ref, shallowRef, watch, nextTick, onBeforeUnmount, toRaw } from 'vue';
import PublicBindingApplicationPanel from '@/components/template-recognition/PublicBindingApplicationPanel.vue';
import { pageAnalysisTasksApi, type PageAnalysisTask } from '@/api/pageAnalysisTasks';
import { useRoute, useRouter, onBeforeRouteLeave } from 'vue-router';
import { ElMessage, ElMessageBox, type UploadFile } from 'element-plus';
import { SiteSwitchBlockedError, useSiteContextStore } from '@/stores/siteContext';
import { AdminHttpError, http } from '@/api/http';
import DomainAgentPanel from '@/components/sites/DomainAgentPanel.vue';
import type { RecognitionSuggestion } from '@/api/domainAgent';
import AgentRunWindow from '@/components/sites/AgentRunWindow.vue';
import SiteModelPanel from '@/components/sites/SiteModelPanel.vue';
import WorkbenchHomeBannerPanel from '@/components/sites/WorkbenchHomeBannerPanel.vue';
import WorkbenchHandoffPanel from '@/components/sites/WorkbenchHandoffPanel.vue';
import WorkbenchNavigationLandingPanel from '@/components/sites/WorkbenchNavigationLandingPanel.vue';
import NativeFormImportPanel from '@/components/sites/NativeFormImportPanel.vue';
import PageListSupplementPanel from '@/components/sites/PageListSupplementPanel.vue';
import { inspectNativeForms } from '@/api/nativeFormImport';
import WorkbenchPublishPanel from '@/components/sites/WorkbenchPublishPanel.vue';
import WorkbenchTemplateChecklist, { type ChecklistPosition } from '@/components/sites/WorkbenchTemplateChecklist.vue';
import WorkbenchReleaseReview, { hasRememberedReleaseReads, type ReleaseAction, type ReleaseNavigationEvidence, type ReleasePage, type ReleaseScope } from '@/components/sites/WorkbenchReleaseReview.vue';
import WorkbenchStaticAudit from '@/components/sites/WorkbenchStaticAudit.vue';
import WorkbenchNavigationLinksPanel from '@/components/sites/WorkbenchNavigationLinksPanel.vue';
import WorkbenchSharedContentPanel from '@/components/sites/WorkbenchSharedContentPanel.vue';
import { siteModelApi, usesPageFieldReview, workbenchApplyLabel, workbenchApplyResumeLabel, type SiteModelPlan, type SiteNavigation, type SiteNavigationNode, type WorkbenchApplyResult } from '@/api/siteModel';
import VisualBindingSuggestions from '@/components/sites/VisualBindingSuggestions.vue';
import SourceVideoPreview from '@/components/sites/SourceVideoPreview.vue';
import PackageAnalysisPanel from '@/components/sites/PackageAnalysisPanel.vue';
import WorkbenchApplicationDialog from '@/components/sites/WorkbenchApplicationDialog.vue';
import type { AgentRunReference, AgentChatScope, DomainAgentResult, DomainAgentTask, DomainUnit } from '@/api/domainAgent';
import ProjectExperiencePanel from '@/components/sites/ProjectExperiencePanel.vue';
import AsyncFragmentPanel from '@/components/sites/AsyncFragmentPanel.vue';
import ReviewedListImportPanel from '@/components/sites/ReviewedListImportPanel.vue';
import ListPairingPanel from '@/components/sites/ListPairingPanel.vue';
import { createSharedDetailTemplatePlan, type SharedDetailTemplateScope } from '@/api/templateListPairing';
import type { ExperienceReference } from '@/api/projectExperiences';
import { inspectTextBinding, initializeTextBinding, previewTextBindingDraft, inspectExistingDetail, initializeExistingDetail, getBindingRecovery, recoverTextBinding, inspectPublicBinding, type BindingRecovery, type ExistingDetailPreview, type RecoveryAction, type TextBindingResult } from '@/api/templateTextBinding';
import { getSiteRoutes, getSiteServiceStatus } from '@/api/templateRecognition';
import { listNativeContent, type NativeContentRow } from '@/api/cms';
import FieldSourceChoice from '@/components/sites/FieldSourceChoice.vue';
import { readFieldPlan, recentFieldPlans, confirmFieldPlan, type FieldPlanSummary, type FieldPlan, type FieldPlanBinding, type FieldPlanReference } from '@/api/templateFieldPlan';
import { packageAnalysisApi } from '@/api/packageAnalysis';
import { getExistingColumns, getUiPackageStatus, uploadUiPackage, type MenuNode, type UiPackageStatus } from '@/api/uiPackage';

import WorkbenchStageProgress from '@/components/sites/WorkbenchStageProgress.vue';
import { getSiteBuildProgress, type SiteBuildStageId, type SiteBuildProgressInput } from '@/utils/siteBuildProgress';

import { readWorkbenchLocation, saveWorkbenchLocation, readWorkbenchDraft, saveWorkbenchDraft, draftKey, analysisKey, type WorkbenchScope, type WorkbenchDraft } from './workbenchResume';

const router = useRouter();
const route = useRoute();
const siteContext = useSiteContextStore();
const workspaceHeading = ref<HTMLElement>();
const releasePanel = ref<InstanceType<typeof WorkbenchReleaseReview> | null>(null);
const activeStep=ref(7);
const handoffPanel=ref<InstanceType<typeof WorkbenchHandoffPanel> | null>(null);
const packageAnalysisPanel=ref<InstanceType<typeof PackageAnalysisPanel>|null>(null);
const packageStructureLinkBusy=ref(false);
const packageStructureLinkError=ref('');
const applicationContext=ref<{plan:SiteModelPlan;navigation:SiteNavigation}|null>(null);
const applicationBusy=ref(false);
let applicationTrigger:HTMLElement|null=null, applicationRefresh:Promise<void>|undefined;
const visitedStages = ref<number[]>([]);
watch(activeStep, step => {
  if (!visitedStages.value.includes(step)) visitedStages.value.push(step);
}, { immediate:true, flush:'sync' });

const stepNavRevision=ref(0);
const resumeNotice=ref('');
const localDraftStored=ref(false);
const resumeScope=ref<WorkbenchScope|null>(null);
const unrestoredDraft=ref<WorkbenchDraft<ContentUnit>|null>(null);
let restoringSession=false;
let approvedSiteSwitch=false;
let loadedUnitsContext: {scope:WorkbenchScope;path:string}|null=null;
let installingUnits=false;
let unitsBaseline='';
let choicesBaseline='';
let wantedPlanId='';
let pendingLocalDraft:WorkbenchDraft<ContentUnit>|null=null;
const advancedToolsOpen=ref(false);
// 旧步骤编号保留，避免改变浏览器已记住的位置和草稿。
const workSteps=[[0,'项目与页面包'],[7,'整包分析与审核'],[5,'整站结构'],[1,'区域证据与修正'],[2,'字段与初始化'],[3,'列表与查询'],[4,'预览与公开'],[6,'待办清单']] as const;
const siteModelPanel=ref<InstanceType<typeof SiteModelPanel> | null>(null);
const checklistPosition=ref<ChecklistPosition>();
const releaseNavigationEvidence=ref<ReleaseNavigationEvidence>();
const checklistPanel=ref<InstanceType<typeof WorkbenchTemplateChecklist>|null>(null);
const agentRunWindow=ref<InstanceType<typeof AgentRunWindow> | null>(null);
const domainAgentPanel = ref<InstanceType<typeof DomainAgentPanel> | null>(null);
function followAgent(kind: 'site' | 'page', snapshot: DomainAgentTask) {
  agentRunWindow.value?.follow({ kind, taskId: snapshot.taskId, state: snapshot.state,
    startedAt: Date.now(), ...(kind === 'page' ? { sourcePath: sourcePath.value } : {}) });
}
const siteModelPlan=ref<SiteModelPlan | null>(null);
const navigationSnapshot=ref<SiteNavigation|null>(null);
const navigationPlanId = ref<string>();
function receiveModelNavigation(snapshot: SiteNavigation | null, planId?: string) {
  navigationSnapshot.value = snapshot;
  navigationPlanId.value = planId;
}
const loading = ref(false);
const uploading = ref(false);
const error = ref('');
const packageReadProblem = ref<'forbidden'|'transient'|null>(null);
const packageStatus = ref<UiPackageStatus | null>(null);
const columns = ref<MenuNode[]>([]);
const columnsLoaded = ref(false);
const columnsRefreshError = ref(false);
const nativeFormBusy = ref(false);
const publicBindingBusy = ref(false);
const publicBindingPanel=ref<InstanceType<typeof PublicBindingApplicationPanel>|null>(null);
const workbenchPublishBusy = ref(false);
const homeBannerBusy = ref(false);
const navigationLandingBusy = ref(false);
const workbenchPublishPanel=ref<{hasUnsavedChanges?:boolean}|null>(null);
const navigationLinksPanel=ref<InstanceType<typeof WorkbenchNavigationLinksPanel>|null>(null);
const asyncFragmentPanel=ref<InstanceType<typeof AsyncFragmentPanel>|null>(null);
const navigationLinksBusy=ref(false);
const sharedContentPanel=ref<InstanceType<typeof WorkbenchSharedContentPanel>|null>(null);
const sharedContentBusy=ref(false);
const pageListBusy=ref(false);
const pageListNotice=ref<{siteId:number;sourcePath:string;message:string}>();
const publishRefreshKey = ref(0);
const otherPublishBusy = computed(() => loading.value || uploading.value || importing.value || analyzing.value || unitsLoading.value || unitsSaving.value || planLoading.value || planApplying.value || planHistoryLoading.value || textBindingBusy.value || nativeFormBusy.value || publicBindingBusy.value || siteContext.switching || !!siteModelPanel.value?.isWriting());
const busy = computed(() => applicationBusy.value || otherPublishBusy.value || workbenchPublishBusy.value || homeBannerBusy.value || navigationLandingBusy.value || navigationLinksBusy.value || sharedContentBusy.value || pageListBusy.value);
const hasPackage = computed(() => !!packageStatus.value && packageStatus.value.status !== 'NOT_UPLOADED');
let requestVersion = 0, columnsRequestVersion = 0;
interface SeedPage { sourcePath: string; fileName: string }
interface SeedPreview {
  sourcePath: string; title: string; method: string; digest: string;
  items: { id: string; kind: string; text: string; value: string; selector: string; sourcePath: string; repeatGroup: string | null; scriptInput?: {reader: string; input: string; reason: string} | null }[];
  warnings: string[];
}
const seedPages = ref<SeedPage[]>([]);
const sourcePath = ref('');
const seedPreview = ref<SeedPreview | null>(null);
const seedLoading = ref(false);
const seedError = ref('');
let seedVersion = 0;
const targetMenuId = ref<number>();
const seedFields = ref<{fieldName: string; title: string; fieldType: string}[]>([]);
const mapping = ref<Record<string, string>>({});
const importing = ref(false);
const importResult = ref('');
const analyzing = ref(false);
const analysisError = ref('');
interface UnitReference { sourceId: string; start: number; end: number }
interface UnitSource { publicRegion?: 'HEADER'|'FOOTER'|null; interactionPending?: boolean|null; publicNavigation?: boolean|null; backgroundBindable?: boolean|null; id: string; kind: string; rawValue: string; selector: string; context: string; repeatGroup: string | null; scriptInput?: {reader: string; input: string; reason: string} | null }
interface ContentUnit { id: string; name: string; fieldType: string; sources: UnitReference[]; ignored: boolean; doubts: string }
interface UnitsWorkspace { sourcePath: string; digest: string; revision: number; sources: UnitSource[]; units: ContentUnit[]; warnings: string[] }
interface AnalysisCoverage { sourceCoverageComplete: boolean; excluded: {sourceId: string; reason: string}[]; unprocessed: {sourceId: string; reason: string}[] }
interface UnitAnalysis { coverage?: AnalysisCoverage; revision: number; references: ExperienceReference[]; requestId: string; units: ContentUnit[]; digest: string; analyzedSources: number; totalSources: number; warnings: string[] }
const analysisResult = ref<UnitAnalysis | null>(null);
const unitsWorkspace = ref<UnitsWorkspace | null>(null);
const recoveryRows=computed(()=>{
  const describe=(unit:ContentUnit)=>[unit.name,unit.fieldType,unit.ignored?'忽略':'维护',unit.doubts].filter(Boolean).join(' · ');
  return (unrestoredDraft.value?.units || []).map(unit=>{
    const current=unitsWorkspace.value?.units.find(item=>item.id===unit.id);
    return {id:unit.id,name:unit.name,local:describe(unit),current:current?describe(current):'无对应单元'};
  });
});
const draftBindingPreview = ref<InstanceType<typeof VisualBindingSuggestions> | null>(null);
const planBindingPreview = ref<InstanceType<typeof VisualBindingSuggestions> | null>(null);
const manualSourcePicker = ref<{focus: () => void}>();
const sourceCorrectionNotice = ref('');
const unitsLoading = ref(false);
const unitsSaving = ref(false);
const unitsDirty = ref(false);
const unitsError = ref('');
const selectedUnitIds = ref<string[]>([]);
const unitSearch = ref(''), unitFilter = ref('all'), unitPage = ref(1);
const unitSourceText = computed(() => new Map(unitsWorkspace.value?.sources.map(s => [s.id,s.rawValue.toLowerCase()])));
const filteredUnits = computed(() => {
  const term = unitSearch.value.trim().toLowerCase();
  return (unitsWorkspace.value?.units || []).filter(unit =>
    (unitFilter.value !== 'review' || (!unit.ignored && !!unit.doubts?.trim())) &&
    (unitFilter.value !== 'ignored' || unit.ignored) &&
    (!term || unit.name.toLowerCase().includes(term) || unit.sources.some(ref => unitSourceText.value.get(ref.sourceId)?.includes(term))));
});
const visibleUnits = computed(() => filteredUnits.value.slice((unitPage.value-1)*20,unitPage.value*20));
watch([unitSearch,unitFilter,sourcePath], () => { unitPage.value=1; });

const manualSourceId = ref('');
function isPageContentSource(source: UnitSource) {
  return !source.publicRegion && !source.publicNavigation && !source.interactionPending &&
    (source.kind!=='BACKGROUND_IMAGE' || source.backgroundBindable===true);
}
const pageContentSources = computed(() => unitsWorkspace.value?.sources.filter(isPageContentSource) || []);
const excludedManualSource = computed(() => unitsWorkspace.value?.sources.find(source => source.id === manualSourceId.value && !isPageContentSource(source)));
let unitsVersion = 0;
const unitTypes = [{value: 'SingleLine', label: '单行文本'}, {value: 'MultiLine', label: '多行文本'}, {value: 'Image', label: '图片'}, {value: 'File', label: '文件'}, {value: 'DateTime', label: '日期时间'}, {value: 'Editor', label: '富文本（正文）'}, {value: 'Video', label: '原生视频（私有预览仅封面）'}];
const chatScope = computed<AgentChatScope | null>(() => {
  const workspace = unitsWorkspace.value;
  const packageVersion = packageStatus.value?.version;
  if (!workspace || workspace.sourcePath !== sourcePath.value || !packageVersion) return null;
  const { sourcePath: path, digest, revision } = workspace;
  return { sourcePath: path, packageVersion, digest, revision };
});
const chatDisabled = computed(() => busy.value || !chatScope.value || !siteContext.currentAdmin?.id || !!unrestoredDraft.value);
function discussUnit(id: string) {
  if (chatDisabled.value || !unitsWorkspace.value?.units.some(unit => unit.id === id)) return;
  if (hasUnappliedReview()) {
    ElMessage.warning('请先应用审核窗口内的人工修正，再与 Agent 讨论。');
    return;
  }
  void agentRunWindow.value?.discuss(id, currentBindingMappings.value?.find(mapping => mapping.unitId === id)?.fieldName || undefined);
}
function applyChatUnit(unit: DomainUnit) {
  return !chatDisabled.value && !hasUnappliedReview() && applyReviewUnits([unit]);
}
function sourceFor(reference: UnitReference) { return unitsWorkspace.value?.sources.find(source => source.id === reference.sourceId); }
function unitOriginal(unit: ContentUnit) {
  return unit.sources.map((reference, index) => {
    const previous = unit.sources[index - 1];
    const boundary = index && !(previous.sourceId === reference.sourceId && previous.end === reference.start) ? '\n' : '';
    return boundary + (sourceFor(reference)?.rawValue.slice(reference.start, reference.end) || '');
  }).join('');
}
function createSourceUnit(workspace:UnitsWorkspace,sourceId:string,ranges?:{start:number;end:number}[],ignored=false):ContentUnit|null {
  const source=workspace.sources.find(item=>item.id===sourceId);
  if(!source || !isPageContentSource(source))return null;
  const ordered=[...(ranges ?? [{start:0,end:source.rawValue.length}])].sort((left,right)=>left.start-right.start);
  const splitsCharacter=(index:number)=>{
    const before=source.rawValue.charCodeAt(index-1), after=source.rawValue.charCodeAt(index);
    return before>=0xD800 && before<=0xDBFF && after>=0xDC00 && after<=0xDFFF;
  };
  if(ranges && (!ordered.length || ordered.length>20 || ordered.some((range,index)=>!Number.isInteger(range.start)||!Number.isInteger(range.end)
    || range.start<0 || range.end>source.rawValue.length || range.end<=range.start
    || splitsCharacter(range.start) || splitsCharacter(range.end)
    || source.kind!=='TEXT' && (range.start!==0 || range.end!==source.rawValue.length)
    || index>0 && ordered[index-1]!.end>range.start
    || workspace.units.some(unit=>unit.sources.some(ref=>ref.sourceId===sourceId && ref.start<range.end && ref.end>range.start)))))return null;
  const id=`unit-${crypto.randomUUID()}`;
  const fieldType=source.kind.startsWith('HTML_REGION') ? 'Editor'
    : ['IMAGE','VIDEO_POSTER','BACKGROUND_IMAGE'].includes(source.kind) ? 'Image'
    : ['VIDEO_SRC','VIDEO_SOURCE'].includes(source.kind) ? 'Video' : 'SingleLine';
  return {id,name:ignored ? '人工暂不绑定来源' : '待命名内容',fieldType,
    sources:ordered.map(range=>({sourceId,...range})),ignored,
    doubts:ignored ? '整页核对中明确选择暂不绑定，保留原文证据。' : '人工新增来源单元，请核对区域原文'};
}
function appendSourceUnit(sourceId:string,ranges?:{start:number;end:number}[],ignored=false):string|null {
  const workspace=unitsWorkspace.value;
  if(!workspace || workspace.sourcePath!==sourcePath.value || busy.value)return null;
  if(workspace.units.length>=200){ElMessage.warning('内容单元最多200项');return null;}
  const unit=createSourceUnit(workspace,sourceId,ranges,ignored);
  if(!unit) {
    if(!ranges)ElMessage.warning('无法新增此来源，请先核对原文和适用范围。');
    return null;
  }
  workspace.units.push(unit);
  markUnitsDirty();
  return unit.id;
}
function addManualUnit() {
  if(appendSourceUnit(manualSourceId.value))manualSourceId.value='';
}
function addCoverageSource(id:string,ranges:{start:number;end:number}[],ignored:boolean) {
  return appendSourceUnit(id,ranges,ignored);
}
function addCoverageSources(sources:{sourceId:string;ranges:{start:number;end:number}[]}[]) {
  const workspace=unitsWorkspace.value;
  if(!workspace || workspace.sourcePath!==sourcePath.value || busy.value)return [];
  const capacity=200-workspace.units.length;
  const unique=new Map(sources.map(item=>[item.sourceId,item]));
  const added:ContentUnit[]=[];
  for(const item of unique.values()) {
    if(added.length>=capacity)break;
    const unit=createSourceUnit(workspace,item.sourceId,item.ranges);
    if(unit)added.push(unit);
  }
  if(added.length) {
    workspace.units=[...workspace.units,...added];
    markUnitsDirty();
  }
  return added.map(unit=>unit.id);
}
function restoreCoverageSources(unitIds:string[]) {
  const workspace=unitsWorkspace.value;
  if(!workspace || workspace.sourcePath!==sourcePath.value || busy.value)return [];
  const eligible=new Set(unitIds);
  const restored=workspace.units.filter(unit=>unit.ignored && eligible.has(unit.id)).map(unit=>unit.id);
  if(restored.length) {
    const ids=new Set(restored);
    workspace.units=workspace.units.map(unit=>ids.has(unit.id) ? {...unit,ignored:false} : unit);
    markUnitsDirty();
  }
  return restored;
}
function markUnitsDirty() { unitsDirty.value = true; stashLocalDraft(); }
function applyReviewUnits(edits: ContentUnit[]) {
  const workspace = unitsWorkspace.value;
  if (!workspace || busy.value || workspace.sourcePath !== sourcePath.value) return false;
  const changes = new Map(edits.map(unit => [unit.id, unit]));
  if (edits.some(unit => !workspace.units.some(current => current.id === unit.id))) return false;
  workspace.units = workspace.units.map(unit => {
    const change = changes.get(unit.id);
    return change ? {...unit, name: change.name, fieldType: change.fieldType, ignored: change.ignored, doubts: change.doubts} : unit;
  });
  markUnitsDirty();
  return true;
}
async function saveReviewUnits() {
  return await saveUnits() ? null : unitsError.value || '当前工作区已变化，整页内容单元尚未保存。';
}
async function reviseIgnoredUnit(id:string){
  if(planStale.value || busy.value)return;
  await openDraftReview(id);
}
async function openDraftReview(unitId?: string) {
  if(!await changeStep(1))return;
  await nextTick();
  void draftBindingPreview.value?.open(unitId);
}
async function returnToUnitSources(unitId: string) {
  const unit = unitsWorkspace.value?.units.find(item => item.id === unitId);
  if (!unit) return;
  manualSourceId.value = unit.sources[0]?.sourceId || '';
  sourceCorrectionNotice.value = `正在修正「${unit.name || '未命名内容'}」的来源：展开表格行查看完整原文，可拆分错误区间、合并同一内容或选择正确来源新增单元。替换后将旧单元设为忽略，再回到整页审核。`;
  await nextTick();
  manualSourcePicker.value?.focus();
}
async function adoptDomainAgent(result: DomainAgentResult) {
  const workspace = unitsWorkspace.value;
  const siteId = siteContext.currentSite?.id;
  const menuId = planTargetMenuId.value ?? null;
  const version = unitsVersion;
  const matches = () => !!workspace && unitsWorkspace.value === workspace && siteContext.currentSite?.id === siteId
    && unitsVersion === version && result.packageVersion === packageStatus.value?.version
    && result.sourcePath === sourcePath.value && result.sourcePath === workspace.sourcePath
    && result.menuId === (planTargetMenuId.value ?? null) && result.menuId === menuId
    && result.digest === workspace.digest && result.revision === workspace.revision;
  if (busy.value || !matches()) { ElMessage.warning('方案已失效，请按当前页面、栏目和修订重新分析'); return false; }
  const valid = result.units.every(unit => typeof unit.id === 'string' && typeof unit.name === 'string' && unit.name.length <= 100
    && typeof unit.ignored === 'boolean' && typeof unit.doubts === 'string'
    && unitTypes.some(type => type.value === unit.fieldType) && Array.isArray(unit.sources)
    && unit.sources.every(reference => { const source = workspace!.sources.find(item => item.id === reference.sourceId); return !!source && Number.isInteger(reference.start) && Number.isInteger(reference.end) && reference.start >= 0 && reference.end > reference.start && reference.end <= source.rawValue.length; }));
  if (!result.units.length) { ElMessage.warning('方案没有可采纳的内容单元。请保留当前草稿，在原文来源中手动修正。'); return false; }
  if (!valid || new Set(result.units.map(unit => unit.id)).size !== result.units.length) { ElMessage.warning('方案内容或来源无效，未采纳'); return false; }
  const initialUnits = JSON.stringify(workspace!.units);
  const initialDirty = unitsDirty.value;
  try { await ElMessageBox.confirm(`${unitsDirty.value ? '已有未保存的内容修正，采纳将替换这些修正。' : ''}仅将建议内容单元放入本地草稿。核对后可在整页审核中保存，再确认字段映射。`, '采纳待审核方案', {type: 'warning', confirmButtonText: '采纳并审核', cancelButtonText: '取消'}); } catch { return false; }
  if (busy.value || !matches() || JSON.stringify(workspace!.units) !== initialUnits || unitsDirty.value !== initialDirty) { ElMessage.warning('审核期间工作区已变化，请重新分析'); return false; }
  try {
    const [current, currentPackage] = await Promise.all([
      http.get<UnitsWorkspace>(`/api/admin/v2/sites/${siteId}/template-seed/content-units`, {params: {sourcePath: result.sourcePath}}),
      getUiPackageStatus(Number(siteId)),
    ]);
    if (busy.value || !matches() || JSON.stringify(workspace!.units) !== initialUnits || unitsDirty.value !== initialDirty) {
      ElMessage.warning('校验期间工作区已变化，请重新审核'); return false;
    }
    if (!result.packageVersion || currentPackage.version !== result.packageVersion || current.sourcePath !== result.sourcePath || current.digest !== result.digest || current.revision !== result.revision) {
      ElMessage.warning('服务端页面或修订已变化，请重新读取后分析'); return false;
    }
  } catch { ElMessage.warning('无法核对服务端当前修订，未采纳方案'); return false; }
  workspace!.units = structuredClone(toRaw(result.units));
  adoptedMappingSource.value=result;
  selectedUnitIds.value = [];
  markUnitsDirty();
  ElMessage.success('已采纳到本地草稿，请核对并保存整页确认');
  return true;
}
function selectUnits(rows: ContentUnit[]) { selectedUnitIds.value = rows.map(row => row.id); }
async function loadUnits(restoreLocal=true) {
  sourceCorrectionNotice.value = '';
  const siteId = siteContext.currentSite?.id;
  const path = sourcePath.value;
  const version = ++unitsVersion;
  // 异步读取新页面前先撤下上一页的恢复候选，磁盘缓存仍留在其原作用域。
  unrestoredDraft.value=null;
  pendingLocalDraft=null;
  loadedUnitsContext=null;
  localDraftStored.value=false;
  unitsWorkspace.value = null;
  analysisResult.value = null;
  unitsDirty.value = false;
  unitsError.value = '';
  selectedUnitIds.value = [];
  manualSourceId.value = '';
  if (!siteId || !path) { unitsLoading.value = false; return; }
  unitsLoading.value = true;
  try {
    const workspace = await http.get<UnitsWorkspace>(`/api/admin/v2/sites/${siteId}/template-seed/content-units`, {params: {sourcePath: path}});
    if (version === unitsVersion) { installWorkspace(workspace,restoreLocal); void restoreAnalysisReference(workspace,version); }
  } catch (cause) { if (version === unitsVersion) unitsError.value = cause instanceof Error ? cause.message : '读取内容单元失败'; }
  finally { if (version === unitsVersion) unitsLoading.value = false; }
}
let currentUnitsRead:Promise<void>|undefined;
async function reloadUnits() {
  if (unitsDirty.value) { try { await ElMessageBox.confirm('重新读取会放弃当前未保存修正。', '确认读取', {type: 'warning'}); } catch { return; } }
  if(resumeScope.value&&sourcePath.value) { try{localStorage.removeItem(draftKey(resumeScope.value,sourcePath.value));}catch{resumeNotice.value='本机缓存无法清理；本次仍重新读取服务端方案，不自动套用旧修正。';}unrestoredDraft.value=null; pendingLocalDraft=null; }
  await loadUnits(false);
}
async function saveUnits() {
  const siteId = siteContext.currentSite?.id;
  const workspace = unitsWorkspace.value;
  if (!siteId || !workspace || busy.value) return;
  const expectedPackageVersion = loadedUnitsContext?.scope.version;
  if (!expectedPackageVersion) { unitsError.value = '无法确认草稿的页面包版本，请重新读取后再保存。'; return; }
  const version = unitsVersion;
  unitsSaving.value = true;
  unitsError.value = '';
  try {
    const saved = await http.post<UnitsWorkspace>(`/api/admin/v2/sites/${siteId}/template-seed/content-units`, {sourcePath: workspace.sourcePath, digest: workspace.digest, revision: workspace.revision, units: workspace.units, expectedPackageVersion});
    if (version === unitsVersion) { installingUnits=true;unitsBaseline=JSON.stringify(saved.units);unitsWorkspace.value = saved; unitsDirty.value = false;installingUnits=false;unrestoredDraft.value=null;pendingLocalDraft=null;stashLocalDraft(); selectedUnitIds.value = []; ElMessage.success('人工修正已保存，未写入业务内容'); return true; }
  } catch (cause) { if (version === unitsVersion) unitsError.value = cause instanceof Error ? cause.message : '保存内容单元失败'; }
  finally { unitsSaving.value = false; }
}
function counterRange(unit: ContentUnit) {
  if (unit.sources.length !== 1 || unit.fieldType !== 'SingleLine') return null;
  const reference = unit.sources[0]; const source = sourceFor(reference);
  if (source?.kind !== 'SCRIPT_PENDING' || source.scriptInput?.reader !== 'jquery.countUp/text-v1' || source.scriptInput.input !== 'text-affix') return null;
  const matches = [...source.rawValue.matchAll(/[0-9][0-9,.]*/g)];
  if (matches.length !== 1) return null;
  const match = matches[0]; const start = match.index; const end = start + match[0].length;
  if (match[0].length > 64 || start > 20 || source.rawValue.length - end > 20 || (start === 0 && end === source.rawValue.length) || (reference.start === start && reference.end === end)) return null;
  return {source, reference, start, end, value: match[0]};
}
async function confirmCounterRange(unit: ContentUnit) {
  const proposal = counterRange(unit); if (!proposal || busy.value) return;
  const version = unitsVersion;
  try { await ElMessageBox.confirm(`仅将“${proposal.value}”作为统计数值；固定前后缀“${proposal.source.rawValue.slice(0, proposal.start)}”/“${proposal.source.rawValue.slice(proposal.end)}”保留为相邻文本。将显式使用counterNumber字段约束。复杂样式可能仍待处理；编译后须核对DOM差异和原JS效果。`, '确认脚本数值入口', {type: 'warning', confirmButtonText: '确认数值与限定符', cancelButtonText: '取消'}); } catch { return; }
  if (version !== unitsVersion) return;
  unit.sources = [{...proposal.reference, start: proposal.start, end: proposal.end}]; markUnitsDirty();
}
async function splitUnit(unit: ContentUnit) {
  const workspace = unitsWorkspace.value;
  if (!workspace || busy.value) return;
  const version = unitsVersion;
  let references: UnitReference[][];
  if (unit.sources.length > 1) references = unit.sources.map(reference => [reference]);
  else {
    const reference = unit.sources[0];
    const source = sourceFor(reference);
    if (!source || source.kind !== 'TEXT') { ElMessage.warning('仅文本来源可以按原文拆分'); return; }
    let suffix: string;
    try { const answer = await ElMessageBox.prompt('输入第二个单元从哪段原文开始（保留原文，不能新增值）。', '拆分原文', {inputPlaceholder: unitOriginal(unit)}); suffix = answer.value; } catch { return; }
    if (version !== unitsVersion) return;
    const offset = source.rawValue.indexOf(suffix, reference.start);
    if (!suffix || offset <= reference.start || offset >= reference.end || offset + suffix.length > reference.end) { ElMessage.warning('请输入当前原文内部的有效后半段'); return; }
    references = [[{...reference, end: offset}], [{...reference, start: offset}]];
  }
  if (workspace.units.length + references.length - 1 > 200) { ElMessage.warning('内容单元最多200项'); return; }
  const index = workspace.units.findIndex(item => item.id === unit.id);
  workspace.units.splice(index, 1, ...references.map((sources, i) => ({...unit, id: `unit-${crypto.randomUUID()}`, name: `${unit.name.slice(0, 90)}·${i + 1}`, sources})));
  markUnitsDirty();
}
async function mergeUnits() {
  const workspace = unitsWorkspace.value;
  if (!workspace || busy.value) return;
  const selected = workspace.units.filter(unit => selectedUnitIds.value.includes(unit.id));
  if (selected.length < 2) return;
  const references = Array.from(new Map(selected.flatMap(unit => unit.sources).map(reference => [`${reference.sourceId}:${reference.start}:${reference.end}`, reference])).values());
  for (let i = 1; i < references.length;) {
    if (references[i - 1].sourceId === references[i].sourceId && references[i - 1].end === references[i].start) {
      references[i - 1] = {...references[i - 1], end: references[i].end};
      references.splice(i, 1);
    } else i++;
  }
  if (references.length > 20) { ElMessage.warning('合并单元最多保留20个来源'); return; }
  const first = workspace.units.findIndex(unit => unit.id === selected[0].id);
  workspace.units = workspace.units.filter(unit => !selectedUnitIds.value.includes(unit.id));
  workspace.units.splice(first, 0, {id: `unit-${crypto.randomUUID()}`, name: selected[0].name, fieldType: selected.every(unit => unit.fieldType === selected[0].fieldType) ? selected[0].fieldType : 'MultiLine', sources: references, ignored: false, doubts: selected.map(unit => unit.doubts).filter(Boolean).join('；').slice(0, 2000)});
  selectedUnitIds.value = [];
  markUnitsDirty();
}

const fieldPlan = ref<FieldPlan | null>(null);
const showIgnoredPlanBindings = ref(false);
const ignoredPlanBindings = computed(() => fieldPlan.value?.bindings.filter(row => row.action === 'IGNORED').length || 0);
const visiblePlanBindings = computed(() => (fieldPlan.value?.bindings || []).filter(row => showIgnoredPlanBindings.value || row.action !== 'IGNORED'));
watch(() => fieldPlan.value?.id, () => { showIgnoredPlanBindings.value = false; });
const savedPlans = ref<FieldPlanSummary[]>([]);
const savedPlanId = ref('');
const manualPlanId = ref('');
const planHistoryWarnings = ref<string[]>([]);
const planHistoryLoading = ref(false);

const planTargetMenuId = ref<number>();
const fieldChoices = ref<Record<string, string>>({});
const planChoicesDirty = ref(false);
const planLoading = ref(false);
const planApplying = ref(false);
const planError = ref('');
let planVersion = 0;
const planStale = computed(() => !!fieldPlan.value && (!unitsWorkspace.value || unitsDirty.value || fieldPlan.value.sourcePath !== sourcePath.value || fieldPlan.value.menuId !== planTargetMenuId.value || fieldPlan.value.digest !== unitsWorkspace.value.digest || fieldPlan.value.unitsRevision !== unitsWorkspace.value.revision));
const planPreviewUnavailable = computed(() => {
  const plan = fieldPlan.value;
  if (!plan) return '';
  if (plan.origins?.length) return '此计划使用多个页面，请在对应页面分别核对来源。';
  if (planStale.value || plan.packageVersion !== packageStatus.value?.version) return '页面包或来源修订已变化，请重新生成计划后审核位置。';
  return '';
});
const planPreviewSources=computed(()=>planStale.value ? fieldPlan.value?.sources : [...new Map([...(unitsWorkspace.value?.sources||[]),...(fieldPlan.value?.sources||[])].map(source=>[source.id,source])).values()]);
const planPreviewUnits = computed(() => (fieldPlan.value?.bindings || []).map(({ unitId, name, fieldType, action, sources }) => ({
  id: unitId, name, fieldType, ignored: action === 'IGNORED', sources: sources?.length ? sources : !planStale.value ? unitsWorkspace.value?.units.find(unit=>unit.id===unitId)?.sources || [] : [], doubts: unitsWorkspace.value?.units.find(unit => unit.id === unitId)?.doubts || '',
})));
const planPreviewMappings = computed(() => (fieldPlan.value?.bindings || [])
  .map(({ unitId, fieldName, action, message }) => ({ unitId, fieldName, action, reason: message })));
const planBindingsById = computed(() => new Map(fieldPlan.value?.bindings.map(row => [row.unitId, row])));
const currentBindingMappings = computed(() => fieldPlan.value && !planPreviewUnavailable.value && !planChoicesDirty.value ? planPreviewMappings.value : undefined);
const canOpenSavedPlan = computed(() => !!fieldPlan.value && !planStale.value && !planPreviewUnavailable.value);
const modelBinding = computed(() => {
  const plan = siteModelPlan.value?.status==='CONFIRMED' && siteModelPlan.value.packageVersion===packageStatus.value?.version
    ? siteModelPlan.value : packageStructure.value?.plan;
  if (plan?.status !== 'CONFIRMED' || plan.packageVersion !== packageStatus.value?.version) return { notice: '请先在“整站结构”审核并确认当前页面包的结构计划。已有字段计划仍可读取与维护。' };
  if (!sourcePath.value || !planTargetMenuId.value) return { notice: '请选择已纳入结构计划的页面及对应的 CMS 栏目。' };
  const models = modelsForMenu(planTargetMenuId.value,plan);
  const regions = plan.proposal.regions.filter(region => !region.maintenance && region.sourcePath === sourcePath.value && models.some(model => model.key === region.modelKey));
  if (!regions.length) return { notice: '当前页面与栏目未在已确认结构计划中对应，请回整站结构核对。' };
  const eligible = models.flatMap(model => {
    if (plan.proposal.regions.some(region => region.modelKey === model.key && region.maintenance?.mode === 'OWNED')) return [];
    const uses = regions.filter(region => region.modelKey === model.key);
    const region = uses[0];
    if (uses.length !== 1 || region.records.length !== 1 || !['SINGLE', 'DETAIL'].includes(region.mode)) return [];
    return [{ model, record: region.records[0], mode: region.mode }];
  });
  if (eligible.length !== 1) return { notice: '当前页面未找到唯一可绑定的单记录区域。重复区域需按列表记录单独处理。' };
  const { model, record, mode } = eligible[0];
  const workspace = unitsWorkspace.value;
  const sources = new Map(workspace?.sources.map(source => [source.id, source]));
  const priorPlan = fieldPlan.value;
  const navigation = reviewedNavigation.value;
  const owner = navigation?.navigation.nodes.filter(node => node.menuId === planTargetMenuId.value);
  // 沿用已确认计划时，可在业务名称不一致的情况下使用同一栏目的原文绑定。
  const priorReusable = !planStale.value && priorPlan?.status === 'CONFIRMED'
    && priorPlan.siteId === plan.siteId && priorPlan.packageVersion === plan.packageVersion
    && !priorPlan.origins?.length && navigation?.plan.id === plan.id
    && navigation.navigation.planRevision === plan.revision && owner?.length === 1
    && !owner[0]?.drift && owner[0]?.sourcePath === sourcePath.value && owner[0]?.modelKey === model.key;
  const choices: Record<string, string> = {};
  const assigned = new Set<string>();
  for (const unit of workspace?.units || []) {
    if (unit.ignored) continue;
    const fields = model.fields.filter(field => field.type === unit.fieldType && record.bindings[field.key]);
    const namedFields = fields.filter(field => field.name === unit.name);
    let slot = namedFields.length === 1 ? namedFields[0]?.fieldName : null;
    const reference = unit.sources[0];
    const source = reference ? sources.get(reference.sourceId) : undefined;
    if (!namedFields.length && priorReusable && reference && source) {
      const priorBinding = priorPlan.bindings.filter(row => row.unitId === unit.id);
      const row = priorBinding.length === 1 ? priorBinding[0] : undefined;
      const priorReference = row?.sources?.length === 1 ? row.sources[0] : undefined;
      const priorSource = priorPlan.sources.find(item => item.id === priorReference?.sourceId);
      if (row?.action === 'REUSE' && row.fieldName && row.fieldType === unit.fieldType
          && priorReference?.sourceId === reference.sourceId && priorReference.start === reference.start && priorReference.end === reference.end
          && priorSource?.selector === source.selector && priorSource.rawValue === source.rawValue && priorSource.kind === source.kind
          && fields.filter(field => field.fieldName === row.fieldName).length === 1) slot = row.fieldName;
    }
    if (!slot || assigned.has(slot)) return { notice: `“${unit.name}”尚无该记录中唯一的已审核 CMS 字段。请核对区域证据和结构计划中的字段对应。` };
    const fullRange = source && reference.start === 0 && reference.end === source.rawValue.length;
    const detailTextRange = source && mode === 'DETAIL' && unit.fieldType === 'SingleLine' && source.kind === 'TEXT'
      && Number.isInteger(reference.start) && Number.isInteger(reference.end)
      && reference.start >= 0 && reference.end > reference.start && reference.end <= source.rawValue.length;
    if (unit.sources.length !== 1 || !source || source.scriptInput || (!fullRange && !detailTextRange)) {
      return { notice: `“${unit.name}”需要对应已审核原文的有效范围；仅详情单行文本可单独选择值，其余类型须保留完整来源。` };
    }
    assigned.add(slot);
    choices[unit.id] = slot;
  }
  if (!Object.keys(choices).length) return { notice: '当前没有可绑定的内容项，请先读取并保存页面证据修正。' };
  return { notice: '', plan, modelKey: model.key, choices };
});
const confirmedPackageBinding=computed(()=>{
  const navigation=appliedPackageNavigation.value;
  const planId=packageAnalysisPanel.value?.structurePlanId;
  const plan=siteModelPlan.value?.id===planId ? siteModelPlan.value : packageStructure.value?.plan;
  const menuId=planTargetMenuId.value, path=sourcePath.value;
  if(!planId || !plan || plan.id!==planId || plan.status!=='CONFIRMED' || plan.packageVersion!==packageStatus.value?.version ||
    !navigation || navigation.planRevision!==plan.revision || !menuId || !path || !packageAnalysisPanel.value?.hasConfirmedTarget(path,menuId))return null;
  const sources=navigation.nodes.filter(node=>node.sourcePath===path);
  const root=rootMenuId.value;
  if(!sources.length){
    if(!root || menuId===root || navigation.nodes.some(node=>!node.menuId) ||
      new Set(navigation.nodes.map(node=>node.menuId)).size!==navigation.nodes.length ||
      new Set(navigation.nodes.map(node=>node.key)).size!==navigation.nodes.length)return null;
    const node=navigation.nodes.find(node=>node.menuId===menuId);
    const target=columns.value.find(column=>Number(column.id)===menuId);
    if(!node || node.kind!=='SINGLE' || node.drift ||
      node.sourcePath!=null || node.proposedUrl!=null || node.reuseMenuId!=null ||
      !/^#+$/.test(node.sourceHref || '') || !node.observations?.length ||
      node.observations.some(observation=>observation.linkKind!=='PLACEHOLDER' || observation.targetPath!=null) ||
      navigation.nodes.some(child=>child.parentKey===node.key) || target?.pageType!==0 || !target.hasManageChannel)return null;
    const models=plan.proposal.models.filter(model=>model.menuId===menuId);
    const model=models[0];
    if(models.length!==1 || !model || model.kind!=='SINGLE' || node.modelKey!=null && node.modelKey!==model.key)return null;
    const uses=plan.proposal.regions.filter(region=>!region.maintenance && ['SINGLE','DETAIL'].includes(region.mode) &&
      (region.sourcePath===path || region.modelKey===model.key));
    const region=uses[0];
    return uses.length===1 && region?.modelKey===model.key && region.sourcePath===path && region.mode==='SINGLE' &&
      region.records.length===1 && Object.keys(region.records[0]!.bindings).length>0
      ? {plan,navigation,node} : null;
  }
  const node=sources[0];
  if(sources.length!==1 || !node || node.kind!=='SINGLE' || node.drift || !node.menuId)return null;
  if(navigation.nodes.filter(item=>item.menuId===node.menuId).length!==1)return null;
  if(node.menuId===menuId && menuId!==root)return {plan,navigation,node};
  if(!root || node.menuId!==root || menuId===root || node.parentKey || node.name!=='首页')return null;
  const target=columns.value.find(column=>Number(column.id)===menuId);
  const models=plan.proposal.models.filter(model=>model.menuId===menuId && model.kind==='SINGLE');
  if(!target || target.pageType!==0 || Number(target.parentId)!==root || !target.hasManageChannel || models.length!==1)return null;
  const uses=plan.proposal.regions.filter(region=>region.modelKey===models[0]?.key && !region.maintenance);
  const region=uses[0];
  return uses.length===1 && region?.sourcePath===path && region.mode==='SINGLE' && region.records.length===1
    ? {plan,navigation,node} : null;
});
const sharedDetailEntry = ref<{ path: string; siteId: number; version: string; scope: SharedDetailTemplateScope }>();
const sharedDetailTemplate = computed(() => {
  const entry = sharedDetailEntry.value;
  return entry?.path === sourcePath.value && entry?.scope.pairing.menuId === planTargetMenuId.value
    && entry?.siteId === Number(siteContext.currentSite?.id) && entry?.version === packageStatus.value?.version
    ? entry.scope : undefined;
});
const usePackageFieldPlan=computed(()=>!sharedDetailTemplate.value && !!modelBinding.value.notice && !!confirmedPackageBinding.value);
const newBindingNotice = computed(() => sharedDetailTemplate.value || usePackageFieldPlan.value ? '' : modelBinding.value.notice);
function editableFieldSource(row:FieldPlanBinding){
  return usePackageFieldPlan.value && fieldPlan.value?.status==='DRAFT' && !planStale.value && !row.locked && row.action!=='IGNORED' && row.action!=='PENDING_TYPE';
}
const adoptedMappingSource=shallowRef<DomainAgentResult|null>(null);
function reviewedMapping(row:FieldPlanBinding){
  const result=adoptedMappingSource.value, plan=fieldPlan.value;
  if(!result || !plan || result.packageVersion!==plan.packageVersion || result.digest!==plan.digest ||
      result.sourcePath!==plan.sourcePath || result.menuId!==plan.menuId)return;
  const unit=result.units.find(item=>item.id===row.unitId);
  if(unit?.name!==row.name || unit.fieldType!==row.fieldType || JSON.stringify(unit.sources)!==JSON.stringify(row.sources))return;
  return result.mappings.find(item=>item.unitId===row.unitId);
}
const childMenuNames=computed(()=>columns.value.filter(menu=>menu.id && Number(menu.parentId)===fieldPlan.value?.menuId)
  .map(menu=>({id:Number(menu.id),name:(menu.className || menu.name || '').trim()})).filter(menu=>menu.name));
function childNameOptions(row:FieldPlanBinding){
  if(row.fieldType!=='SingleLine' || row.sources.length!==1)return [];
  const ref=row.sources[0]!;
  const source=fieldPlan.value?.sources.find(item=>item.id===ref.sourceId);
  if(source?.kind!=='TEXT' || source.scriptInput)return [];
  const original=source.rawValue.slice(ref.start,ref.end).trim();
  const hint=reviewedMapping(row);
  return childMenuNames.value.map(menu=>({...menu,recommended:hint?.action==='MENU_NAME' && hint.menuId===menu.id && menu.name===original}))
    .sort((a,b)=>Number(b.recommended)-Number(a.recommended));
}
function menuReferenceLabel(row:FieldPlanBinding){
  const name=childMenuNames.value.find(menu=>menu.id===row.menuId)?.name;
  return (name || '栏目')+' · 栏目名称 #'+row.menuId;
}
function compatiblePackageFields(row:FieldPlanBinding){
  const reserved=new Set(fieldPlan.value?.bindings.filter(binding=>binding.locked).map(binding=>binding.fieldName));
  return (fieldPlan.value?.fields || []).filter(field=>!reserved.has(field.fieldName) && field.writable && field.fieldType===row.fieldType && field.capacity>=row.valueLength);
}
const repeatedFieldUnits=computed(()=>{
  const plan=fieldPlan.value;
  const units=new Set<string>();
  if(!usePackageFieldPlan.value || plan?.pageType!==0 || plan.status!=='DRAFT')return units;
  // 同级结构重复也可能是固定布局。只将列表、描述列表及轮播条目交给分组审核。
  const repeatedSources=new Set(plan.sources.filter(source=>{
    const signature=source.repeatGroup?.split(' > ').at(-1);
    return signature && (/^(?:li|dd)\./.test(signature) || signature.slice(signature.indexOf('.')+1).split(/\s+/).includes('swiper-slide'));
  }).map(source=>source.id));
  for(const row of plan.bindings){
    if(!row.locked && (row.action==='CONFLICT' || row.action==='CREATE')
      && row.sources.some(reference=>repeatedSources.has(reference.sourceId)))units.add(row.unitId);
  }
  return units;
});
const pageSuggestions=computed(()=>{
  const result:Record<string,string>={};
  const rows=fieldPlan.value?.bindings || [];
  const reserved=new Set(Object.values(fieldChoices.value).filter(Boolean));
  for(const row of rows){
    if(!editableFieldSource(row) || fieldChoices.value[row.unitId] || row.action!=='CONFLICT')continue;
    const menus=childNameOptions(row).filter(menu=>menu.name===row.valuePreview.trim());
    const fields=compatiblePackageFields(row).filter(field=>field.name.trim()===row.name.trim());
    const hint=reviewedMapping(row);
    if(hint?.action==='PENDING')continue;
    if(menus.length===1 && menus[0]!.recommended)result[row.unitId]='MENU_NAME:'+menus[0]!.id;
    else if(!menus.length && fields.length===1 && !reserved.has(fields[0]!.fieldName)
      && rows.filter(other=>!other.locked && other.name.trim()===row.name.trim() && other.fieldType===row.fieldType).length===1){
      result[row.unitId]=fields[0]!.fieldName;
      reserved.add(fields[0]!.fieldName);
    }else if(!menus.length && !fields.length && !repeatedFieldUnits.value.has(row.unitId) && !(row.fieldType==='SingleLine' && columns.value.some(menu=>
      (menu.className || menu.name || '').trim()===row.valuePreview.trim())))result[row.unitId]='$new';
  }
  return result;
});
const pageDraftSummary=ref('');
watch(()=>fieldPlan.value?.id,()=>{pageDraftSummary.value='';},{flush:'sync'});
async function draftWholePage(){
  if(busy.value || planLoading.value || planStale.value || fieldPlan.value?.status!=='DRAFT')return;
  fieldChoices.value={...fieldChoices.value,...pageSuggestions.value};
  if(!await createPlan())return;
  const rows=fieldPlan.value.bindings;
  const count=(action:FieldPlanBinding['action'])=>rows.filter(row=>row.action===action).length;
  pageDraftSummary.value='整页草稿已生成：引用栏目 '+count('MENU_NAME')+' 项，复用 '+count('REUSE')+' 项，拟新增 '+count('CREATE')+' 项，需核对 '+(count('CONFLICT')+count('PENDING_TYPE'))+' 项。核对后可确认整页字段计划。';
}
function focusOverlapReview(){document.getElementById('binding-overlap-review')?.focus();}
const keepBinding=ref<Record<string,string>>({});
watch(()=>fieldPlan.value?.id,()=>{keepBinding.value={};});
const overlaps=computed(()=>{
  const plan=fieldPlan.value;
  const groups:{key:string;text:string;rows:FieldPlanBinding[];exact:boolean}[]=[];
  if(!plan || plan.origins?.length)return groups;
  const bySource=new Map<string,{row:FieldPlanBinding;ref:FieldPlanReference}[]>();
  for(const row of plan.bindings){
    if(row.action==='IGNORED' || row.action==='PENDING_TYPE')continue;
    for(const ref of row.sources){
      const entries=bySource.get(ref.sourceId) || [];
      entries.push({row,ref});
      bySource.set(ref.sourceId,entries);
    }
  }
  for(const [id,entries] of bySource){
    const source=plan.sources.find(item=>item.id===id);
    if(!source || source.kind!=='TEXT')continue;
    entries.sort((a,b)=>a.ref.start-b.ref.start || a.ref.end-b.ref.end);
    for(let i=0;i<entries.length;){
      const first=entries[i++]!, members=[first];let end=first.ref.end;
      while(i<entries.length && entries[i]!.ref.start<end){
        const entry=entries[i++]!;members.push(entry);end=Math.max(end,entry.ref.end);
      }
      if(members.length<2)continue;
      const rows=[...new Map(members.map(item=>[item.row.unitId,item.row])).values()];
      groups.push({key:id+':'+first.ref.start,text:source.rawValue.slice(first.ref.start,end).slice(0,120),rows,
        exact:rows.length===members.length && members.every(item=>item.row.sources.length===1 && item.ref.start===first.ref.start && item.ref.end===end)});
    }
  }
  return groups;
});
async function repairOverlapPlan(){
  const plan=fieldPlan.value, ws=unitsWorkspace.value;
  if(!plan || !ws || planStale.value || busy.value || unitsSaving.value || planLoading.value || !overlaps.value.length)return;
  const ignored=new Set<string>();
  for(const group of overlaps.value){
    const keep=keepBinding.value[group.key];
    if(!group.exact || !group.rows.some(row=>row.unitId===keep))return;
    for(const row of group.rows)if(row.unitId!==keep)ignored.add(row.unitId);
  }
  for(const unit of ws.units)if(ignored.has(unit.id))unit.ignored=true;
  markUnitsDirty();
  if(await saveUnits())await createPlan();
}
const draftMappingNotice = computed(() => planChoicesDirty.value ? '本机仍保留未提交的旧映射选择，请在字段映射步骤核对。'
  : fieldPlan.value && planPreviewUnavailable.value ? '当前字段计划不适用于此草稿，请在字段映射步骤核对。' : undefined);
function planStorageKey() { return `cms-field-plan:${siteContext.currentAdmin?.id}:${siteContext.currentSite?.id}:${packageStatus.value?.version}:${sourcePath.value}:${planTargetMenuId.value}`; }
function installPlan(plan: FieldPlan) {
  fieldPlan.value = plan;
  savedPlanId.value = plan.id;
  savedPlans.value = [{id: plan.id, sourcePath: plan.sourcePath, menuId: plan.menuId, status: plan.status, unitsRevision: plan.unitsRevision, digest: plan.digest}, ...savedPlans.value.filter(item => item.id !== plan.id)].slice(0, 30);

  fieldChoices.value = Object.fromEntries(plan.bindings.filter(row => row.action !== 'IGNORED' && row.action !== 'PENDING_TYPE').map(row => [row.unitId, row.action === 'MENU_NAME' ? 'MENU_NAME:'+row.menuId : row.action === 'CREATE' ? '$new' : row.fieldName || '']));
  planChoicesDirty.value = false;choicesBaseline=JSON.stringify(fieldChoices.value);
  try { localStorage.setItem(planStorageKey(), plan.id); } catch { resumeNotice.value='本机继续位置无法保存，请先保存当前人工修正。'; }
  restoreLocalChoices(plan);rememberLocation();
}
async function createPlan() { return generateFieldPlan(); }
async function generateFieldPlan(retained?: Record<string,string>) {
  const siteId = siteContext.currentSite?.id;
  const workspace = unitsWorkspace.value;
  const menuId=planTargetMenuId.value;
  if (!siteId || !workspace?.revision || !menuId || busy.value || unitsDirty.value || planLoading.value) return;
  const binding = modelBinding.value;
  const fallback=confirmedPackageBinding.value;
  const shared = sharedDetailTemplate.value;
  if (binding.notice && !fallback && !shared) { planError.value = binding.notice; return; }
  const version = ++planVersion;
  planLoading.value = true; planError.value = '';
  try {
    const selectedChoices=usePackageFieldPlan.value && fieldPlan.value?.status==='DRAFT' && !planStale.value
      ? Object.fromEntries(Object.entries(fieldChoices.value).filter(([unitId,value])=>!!value && !planBindingsById.value.get(unitId)?.locked)) : {};
    if(!retained && fallback && fieldPlan.value && planStale.value){
      const previous=fieldPlan.value;
      if(previous.digest===workspace.digest && previous.menuId===menuId && previous.sourcePath===workspace.sourcePath && previous.packageVersion===packageStatus.value?.version){
        retained={};
        for(const unit of workspace.units){
          const row=planBindingsById.value.get(unit.id);
          if(unit.ignored || !row || row.locked || row.name!==unit.name || row.fieldType!==unit.fieldType || JSON.stringify(unit.sources)!==JSON.stringify(row.sources))continue;
          const choice=previous.status==='DRAFT' ? fieldChoices.value[unit.id] : row.action==='MENU_NAME' ? 'MENU_NAME:'+row.menuId : row.fieldName;
          if(choice)retained[unit.id]=choice;
        }
      }
    }
    const request={sourcePath:workspace.sourcePath,digest:workspace.digest,unitsRevision:workspace.revision,menuId,choices:binding.choices || retained || selectedChoices};
    let plan:FieldPlan;
    if(shared) {
      plan = await createSharedDetailTemplatePlan(Number(siteId), {
        ...shared, sourcePath: request.sourcePath, digest: request.digest, unitsRevision: request.unitsRevision,
      });
    } else if(binding.plan && binding.modelKey && binding.choices){
      plan=await siteModelApi.fieldPlan(Number(siteId),binding.plan,binding.modelKey,request);
    } else if(fallback){
      const {plan:structure,navigation,node}=fallback;
      const packageVersion=structure.packageVersion, signal=new AbortController().signal;
      const [analysis,review]=await Promise.all([packageAnalysisApi.read(Number(siteId),packageVersion,signal),
        packageAnalysisApi.page(Number(siteId),packageVersion,request.sourcePath,signal)]);
      if(version!==planVersion || workspace!==unitsWorkspace.value || unitsDirty.value || menuId!==planTargetMenuId.value)return;
      if(analysis.structurePlanId!==structure.id || analysis.structurePlanRevision!==structure.revision || review.page.sourcePath!==request.sourcePath ||
          review.page.review!=='CONFIRMED' || review.page.reviewMenuId!==menuId || !Number.isSafeInteger(review.fieldRevision))
        throw new Error('整包审核或栏目归属已变化，请先重新读取同一原页的审核结果。');
      plan=await packageAnalysisApi.fieldPlan(Number(siteId),{packageVersion,packageRevision:analysis.revision,fieldRevision:review.fieldRevision,
        structurePlanId:structure.id,structurePlanRevision:structure.revision,navigationRevision:navigation.revision,nodeKey:node.key,request},signal);
    } else return;
    if(plan.menuId!==menuId || plan.sourcePath!==request.sourcePath || plan.digest!==request.digest || plan.unitsRevision!==request.unitsRevision)
      throw new Error('返回的字段计划与本页栏目或已保存版本不同，请重读。');
    if (version === planVersion) { installPlan(plan); return true; }
  } catch (cause) { if (version === planVersion) planError.value = cause instanceof Error ? cause.message : '生成字段计划失败'; }
  finally { if (version === planVersion) planLoading.value = false; }
}
async function openPlanReview() {
  if (busy.value || unitsDirty.value || !planTargetMenuId.value) return;
  if (!fieldPlan.value || planStale.value || planPreviewUnavailable.value) {
    if (!await createPlan()) return;
  }
  if (!fieldPlan.value || planPreviewUnavailable.value) return;
  if(!await changeStep(2))return;
  await nextTick();
  void planBindingPreview.value?.open();
}
async function reloadPlan() {
  const siteId = siteContext.currentSite?.id;
  const id = manualPlanId.value.trim() || savedPlanId.value || fieldPlan.value?.id || readLocalPlanId();
  if (!siteId || !id || planLoading.value || planApplying.value) return;
  if(planChoicesDirty.value){
    const currentVersion=planVersion;
    try{await ElMessageBox.confirm('重新读取会放弃当前尚未提交的映射选择，并以服务端计划为准。内容单元修正不受影响。','确认重新读取计划',{type:'warning',confirmButtonText:'放弃当前映射并读取',cancelButtonText:'保留当前映射'});}catch{return;}
    if(currentVersion!==planVersion)return;
  }
  const version = ++planVersion;
  planLoading.value = true; planError.value = '';
  try {
    const plan = await readFieldPlan(Number(siteId), id);
    if (plan.sourcePath !== sourcePath.value || plan.menuId !== planTargetMenuId.value) throw new Error('该计划不属于当前页面与栏目，请切换到对应目标后读取');
    if (version === planVersion) { pendingLocalDraft=null;installPlan(plan);stashLocalDraft(); }
  }
  catch (cause) { if (version === planVersion) planError.value = cause instanceof Error ? cause.message : '读取字段计划失败'; }
  finally { if (version === planVersion) planLoading.value = false; }
}
async function applyPlan() {
  if(overlaps.value.length){planError.value='请先处理原文绑定冲突。';return;}
  const siteId = siteContext.currentSite?.id;
  const plan = fieldPlan.value;
  if (!siteId || !plan || busy.value || planChoicesDirty.value || planStale.value) return;
  const version = planVersion;
  const creates = plan.bindings.filter(row => row.action === 'CREATE');
  const reuses = plan.bindings.filter(row => row.action === 'REUSE');
  const pending = plan.bindings.filter(row => row.action === 'PENDING_TYPE');
  try { await ElMessageBox.confirm(`引用 ${plan.bindings.filter(row=>row.action==='MENU_NAME').length} 项子栏目名称，复用 ${reuses.length} 项已有字段，新增${creates.length}个字段？${creates.map(row => `${row.name}（${row.fieldType}，容量${row.capacity}${row.message.includes('counterDecimal') ? '，显式纯数字约束counterDecimal' : row.message.includes('counterNumber') ? '，显式统计数值约束counterNumber' : ''}）`).join('；')}。${pending.length}项保持待处理。已有用途和值不改，不执行DDL、不初始化内容。`, '确认字段计划', {type: 'warning', confirmButtonText: '确认计划', cancelButtonText: '取消'}); } catch { return; }
  if (version !== planVersion) return;
  planApplying.value = true; planError.value = '';
  try { const saved = await confirmFieldPlan(Number(siteId), plan.id); if (version === planVersion) { installPlan(saved); ElMessage.success('字段计划已确认，未初始化内容'); } }
  catch (cause) { if (version === planVersion) planError.value = cause instanceof Error ? cause.message : '确认计划失败'; }
  finally { planApplying.value = false; }
}
async function loadSavedPlans(version: number) {
  const siteId = siteContext.currentSite?.id;
  if (!siteId || !sourcePath.value || !planTargetMenuId.value) return;
  planHistoryLoading.value = true;
  try {
    const recent = await recentFieldPlans(Number(siteId), sourcePath.value, planTargetMenuId.value);
    if (version === planVersion) {
      savedPlans.value=recent.plans; savedPlanId.value=wantedPlanId || recent.plans[0]?.id || '';planHistoryWarnings.value=recent.warnings;
      const id=savedPlanId.value;wantedPlanId='';
      if(id){const plan=await readFieldPlan(Number(siteId),id);if(version!==planVersion)return;if(plan.sourcePath!==sourcePath.value||plan.menuId!==planTargetMenuId.value||plan.packageVersion!==packageStatus.value?.version)throw new Error('保存的计划与当前页面、栏目或包版本不一致，未自动恢复');installPlan(plan);}
    }
  } catch (cause) { if (version === planVersion) planHistoryWarnings.value = [cause instanceof Error ? cause.message : '读取服务端计划列表失败，可输入已知ID读取']; }
  finally { if (version === planVersion) planHistoryLoading.value = false; }
}
watch([() => siteContext.currentSite?.id, sourcePath, planTargetMenuId], () => {
  const version = ++planVersion;
  fieldPlan.value = null; fieldChoices.value = {}; planChoicesDirty.value = false; planError.value = ''; planLoading.value = false;
  savedPlans.value = []; savedPlanId.value = ''; manualPlanId.value = ''; planHistoryWarnings.value = []; planHistoryLoading.value = false;
  void loadSavedPlans(version);
});

const textBinding = ref<TextBindingResult | null>(null);
const onlyMenuNames=computed(()=>!!textBinding.value?.bound.length && textBinding.value.bound.every(row=>row.action==='MENU_NAME'));
const recoveryState = ref<BindingRecovery | null>(null);
const recoveryLabels: Record<RecoveryAction, string> = {KEEP_SINGLE_CURRENT: '保留当前值并恢复单页预览', RESUME_EXISTING_DETAIL: '确认已有详情草稿', RETRY_MISSING_DETAIL: '确认缺失并重试创建详情'};
const textBindingBusy = ref(false);
const textBindingError = ref('');
const textDraftHtml = ref('');
const textDraftWarnings = ref<string[]>([]);
const detailMode = ref<'existing' | 'preserve' | 'new'>('existing');
const isExistingDetail = computed(() => fieldPlan.value?.pageType === 1 && detailMode.value !== 'new');
const preserveDetailCurrent = computed(() => detailMode.value === 'preserve');
const canCheckTextBinding = computed(() => !!fieldPlan.value && fieldPlan.value.status !== 'DRAFT' && !planStale.value);
const detailPageSize = 20;
const detailCandidates = ref<NativeContentRow[]>([]);
const detailCandidatesLoaded = ref(false);
const detailCandidatePage = ref(1);
const detailCandidateTotal = ref(0);
const selectedDetailId = ref<number>();
const existingDetailPreview = ref<ExistingDetailPreview | null>(null);
const detailChangeLabels = { FILL: '填入空字段', KEEP: '保留当前值', REPLACE: '覆盖当前值' };
const canConfirmExistingDetail = computed(() => {
  const preview = existingDetailPreview.value;
  return canCheckTextBinding.value && !!preview?.applicable && !preview.initialized
    && preview.recordId === selectedDetailId.value && !!preview.preserveCurrent === preserveDetailCurrent.value
    && !textBinding.value?.initialized;
});
let textBindingVersion = 0;
watch([() => siteContext.currentSite?.id, () => fieldPlan.value?.id, planStale], () => {
  recoveryState.value = null; textBinding.value = null;
  clearDetailCandidates();
  clearDetailPreview();
  detailMode.value = fieldPlan.value?.sharedDetailSeedPlanId ? 'preserve' : 'existing';
}, { flush: 'sync' });
watch(detailMode, (mode, previous) => {
  if (mode === 'new' || previous === 'new') clearDetailCandidates();
  clearDetailPreview();
  if (selectedDetailId.value) void inspectSelectedDetail();
}, { flush: 'sync' });
watch(selectedDetailId, clearDetailPreview, { flush: 'sync' });

function clearDetailCandidates() {
  selectedDetailId.value = undefined;
  detailCandidates.value = [];
  detailCandidatesLoaded.value = false;
  detailCandidatePage.value = 1;
  detailCandidateTotal.value = 0;
}

function clearDetailPreview() {
  ++textBindingVersion;
  existingDetailPreview.value = null;
  textBindingBusy.value = false;
  textBindingError.value = ''; textDraftHtml.value = ''; textDraftWarnings.value = [];
}

async function loadDetailCandidates(page: number) {
  const plan = fieldPlan.value;
  if (!plan || !isExistingDetail.value || !canCheckTextBinding.value || busy.value) return;
  selectedDetailId.value = undefined;
  clearDetailPreview();
  const version = textBindingVersion;
  textBindingBusy.value = true;
  try {
    const data = await listNativeContent({ menuId: plan.menuId, statuses: ['DRAFT'], page, limit: detailPageSize });
    if (version !== textBindingVersion) return;
    detailCandidates.value = data.rows;
    detailCandidatePage.value = data.page;
    detailCandidateTotal.value = data.total;
    detailCandidatesLoaded.value = true;
  } catch (cause) {
    if (version === textBindingVersion) textBindingError.value = cause instanceof Error ? cause.message : '本栏目草稿读取失败';
  } finally {
    if (version === textBindingVersion) textBindingBusy.value = false;
  }
}

async function inspectSelectedDetail() {
  const siteId = Number(siteContext.currentSite?.id);
  const plan = fieldPlan.value;
  const recordId = selectedDetailId.value;
  if (!siteId || !plan || !recordId || !isExistingDetail.value || !canCheckTextBinding.value || busy.value) return;
  const version = ++textBindingVersion, preserveCurrent = preserveDetailCurrent.value;
  textBindingBusy.value = true;
  textBindingError.value = '';
  existingDetailPreview.value = null;
  try {
    const preview = await inspectExistingDetail(siteId, plan.id, recordId, preserveCurrent);
    if (version !== textBindingVersion) return;
    if (preview.recordId !== recordId) throw new Error('返回的详情草稿与所选记录不一致');
    if (!!preview.preserveCurrent !== preserveCurrent) throw new Error('返回的详情预览与所选操作不一致，请重新读取。');
    if (preview.initialized) await readPrivateTextDraft(siteId, plan.id, version, recordId);
    else existingDetailPreview.value = preview;
  } catch (cause) {
    if (version === textBindingVersion) textBindingError.value = cause instanceof Error ? cause.message : '详情差异读取失败';
  } finally {
    if (version === textBindingVersion) textBindingBusy.value = false;
  }
}

async function readPrivateTextDraft(siteId: number, planId: string, version: number, recordId?: number | null) {
  const draft = await previewTextBindingDraft(siteId, planId);
  if (version !== textBindingVersion) return;
  if (recordId != null && draft.binding.recordId !== recordId) throw new Error('私有预览与所选记录不一致');
  textBinding.value = draft.binding;
  textDraftHtml.value = draft.preview.html;
  textDraftWarnings.value = draft.preview.warnings;
}
async function checkBindingRecovery() {
  const siteId = Number(siteContext.currentSite?.id); const plan = fieldPlan.value; const version = textBindingVersion;
  if (!siteId || !plan || busy.value || planStale.value || plan.status === 'DRAFT') return;
  textBindingBusy.value = true; textBindingError.value = '';
  try { const state = await getBindingRecovery(siteId, plan.id); if (version === textBindingVersion) recoveryState.value = state; }
  catch (cause) { if (version === textBindingVersion) textBindingError.value = cause instanceof Error ? cause.message : '恢复状态读取失败'; }
  finally { if (version === textBindingVersion) textBindingBusy.value = false; }
}
async function applyBindingRecovery(action: RecoveryAction) {
  const siteId = Number(siteContext.currentSite?.id); const plan = fieldPlan.value; const state = recoveryState.value; const version = textBindingVersion;
  if (!siteId || !plan || !state || busy.value || planStale.value || !state.actions.includes(action)) return;
  try { await ElMessageBox.confirm(`${state.message}。记录ID：${state.recordId ?? '尚未分配'}。来源、配置与当前值会由服务器重新核验，恢复不会覆盖人工值；只有确认FAILED记录确实不存在才创建新草稿。`, recoveryLabels[action], {type: 'warning', confirmButtonText: '确认恢复', cancelButtonText: '取消'}); } catch { return; }
  if (version !== textBindingVersion || planStale.value) return;
  textBindingBusy.value = true; textBindingError.value = '';
  try {
    const result = await recoverTextBinding(siteId, plan.id, state, action); if (version !== textBindingVersion) return; textBinding.value = result;
    const latest = await getBindingRecovery(siteId, plan.id); if (version !== textBindingVersion) return; recoveryState.value = latest;
    const draft = await previewTextBindingDraft(siteId, plan.id); if (version !== textBindingVersion) return; textBinding.value = draft.binding; textDraftHtml.value = draft.preview.html; textDraftWarnings.value = draft.preview.warnings;
  } catch (cause) {
    if (version !== textBindingVersion) return;
    textBindingError.value = cause instanceof Error ? cause.message : '恢复失败，请重新检查持久状态';
    try { const latest = await getBindingRecovery(siteId, plan.id); if (version === textBindingVersion) recoveryState.value = latest; }
    catch (refreshError) { if (version === textBindingVersion) { recoveryState.value = null; textBindingError.value += `；状态读取失败：${refreshError instanceof Error ? refreshError.message : '请稍后重新检查'}`; } }
  } finally { if (version === textBindingVersion) textBindingBusy.value = false; }
}
function openTextBindingEditor() {
  const plan = fieldPlan.value;
  if (!plan || !textBinding.value?.initialized) return;
  if (plan.pageType === 0) void router.push(`/admin/v2/cms/single/${plan.menuId}`);
  else if (textBinding.value.recordId) void router.push({path: `/admin/v2/cms/content/${plan.menuId}`, query: {recordId: String(textBinding.value.recordId)}});
}
async function textBindingAction(action: 'inspect' | 'initialize' | 'preview') {
  const siteId = Number(siteContext.currentSite?.id);
  const plan = fieldPlan.value;
  if (!siteId || !plan || busy.value || !canCheckTextBinding.value) return;
  const existing = isExistingDetail.value ? existingDetailPreview.value : null;
  if (action === 'initialize') {
    if (isExistingDetail.value ? !canConfirmExistingDetail.value : !textBinding.value?.bound.length || textBinding.value.initialized) return;
  }
  const version = textBindingVersion;
  textBindingBusy.value = true;
  try {
    if (action === 'initialize') {
      const title = existing
        ? existing.preserveCurrent ? '确认关联并保留内容' : '确认补齐所选草稿'
        : plan.pageType === 1 ? '确认新建独立草稿' : onlyMenuNames.value ? '生成栏目引用草稿' : '确认原文初始化';
      const message = existing
        ? existing.preserveCurrent
          ? `将模板计划关联到「${existing.title || '无标题'}」（ID ${existing.recordId}）。草稿的所有字段保留当前值。`
          : `确认将计划初值写入「${existing.title || '无标题'}」（ID ${existing.recordId}）？将填入 ${existing.changes.filter(change => change.action === 'FILL').length} 项空字段，覆盖 ${existing.changes.filter(change => change.action === 'REPLACE').length} 项已有值，并保存为私有草稿。`
        : `确认按同一计划初始化 ${textBinding.value?.bound.length || 0} 项来源内容并${plan.pageType === 1 ? '新建一条独立私有草稿' : '保存私有草稿'}？正文使用已展示的清洗后 HTML 与资源映射，${textBinding.value?.pending.length || 0} 项保持待处理。`;
      try { await ElMessageBox.confirm(onlyMenuNames.value && !existing ? '生成私有草稿，标题从对应子栏目读取。' : message, title, { type: 'warning', confirmButtonText: existing?.preserveCurrent ? '确认关联' : onlyMenuNames.value && !existing ? '生成草稿' : '确认写入', cancelButtonText: '取消' }); } catch { return; }
      if (version !== textBindingVersion || !canCheckTextBinding.value) return;
    }
    textBindingError.value = ''; textDraftHtml.value = ''; textDraftWarnings.value = [];
    if (action === 'inspect') {
      const result = await inspectTextBinding(siteId, plan.id);
      if (version === textBindingVersion) textBinding.value = result;
    } else {
      if (action === 'initialize') {
        existingDetailPreview.value = null;
        const result = existing ? await initializeExistingDetail(siteId, plan.id, existing) : await initializeTextBinding(siteId, plan.id);
        if (version !== textBindingVersion) return;
        if (existing && result.recordId !== existing.recordId) throw new Error('初始化结果与所选记录不一致');
        textBinding.value = result;
        recoveryState.value = null;
      }
      await readPrivateTextDraft(siteId, plan.id, version, textBinding.value?.recordId ?? recoveryState.value?.recordId);
    }
  } catch (cause) { if (version === textBindingVersion) textBindingError.value = cause instanceof Error ? cause.message : '文本草稿操作失败'; }
  finally { if (version === textBindingVersion) textBindingBusy.value = false; }
}

const packageInspection = ref<{ version: string; files: {path: string; size: number}[]; warnings: string[] } | null>(null);
const previewPath = ref('');
const isolatedHtml = ref('');
const isolatedWarnings = ref<string[]>([]);
function previewWarning(warning: string) {
  const dataUri = warning.search(/data:[^,\s]*,/i);
  if (dataUri >= 0) return `${warning.slice(0, dataUri)}内嵌数据资源（内容已省略）`;
  return warning.length > 360 ? `${warning.slice(0, 360)}…（提示已缩略）` : warning;
}
const isolatedLoading = ref(false);
let isolatedVersion = 0;
async function previewIsolated() {
  const siteId = siteContext.currentSite?.id;
  const sourcePath=previewPath.value, packageVersion=packageStatus.value?.version;
  if (!siteId || !sourcePath || !packageVersion) return;
  const version = ++isolatedVersion;
  isolatedLoading.value = true;
  isolatedHtml.value = '';
  isolatedWarnings.value = [];
  try {
    const result = await http.get<{html: string; warnings: string[]}>(`/api/admin/v2/sites/${siteId}/template-seed/isolated-preview`, {params: {sourcePath,packageVersion},timeout:45000});
    if (version === isolatedVersion) { isolatedHtml.value = result.html; isolatedWarnings.value = result.warnings; }
  } catch (cause) { if (version === isolatedVersion) isolatedWarnings.value = [cause instanceof Error ? cause.message : '隔离预览失败']; }
  finally { if (version === isolatedVersion) isolatedLoading.value = false; }
}
watch([previewPath,()=>packageStatus.value?.version,()=>siteContext.currentSite?.id], () => { ++isolatedVersion; isolatedHtml.value = ''; isolatedWarnings.value = []; isolatedLoading.value = false; });

const activeAnalysisTask = ref<{id: string; siteId: number; key: string} | null>(null);
const analysisCancelling = ref(false);
const analysisProgress = ref('');
onBeforeUnmount(() => { stashLocalDraft();rememberLocation();++unitsVersion;++planVersion;++requestVersion;++textBindingVersion;window.removeEventListener('beforeunload',protectRefresh);stopSiteSwitchGuard(); });
window.addEventListener('beforeunload',protectRefresh);
onBeforeRouteLeave(async()=>confirmLeaving('离开工作台'));
function taskProgress(task: PageAnalysisTask) {
  if (!task.progress) return `任务 ${task.taskId}：${task.state}（服务器串行自动分批，最长5分钟）`;
  try {
    const p = JSON.parse(task.progress);
    const summary = `已完成 ${p.completedBatches}/${p.totalBatches} 批，成功分析 ${p.analyzedSources}/${p.totalSources} 项；排除 ${p.excludedSources} 项，尚未完成 ${p.unprocessedSources} 项，本批已发出 ${p.inFlightSources} 项。`;
    const failureCodes = ['BATCH_TIMEOUT', 'TASK_TIMEOUT', 'MODEL_REQUEST_FAILED', 'OUTPUT_TOO_LARGE', 'OUTPUT_EMPTY', 'OUTPUT_PARSE_FAILED', 'SUMMARY_VALIDATION_FAILED'];
    if (failureCodes.includes(p.errorCode) && Number.isInteger(p.failedBatch) && p.failedBatch > 0 && p.failedBatch <= 64 && typeof p.errorHint === 'string' && p.errorHint.length <= 160) {
      return `第${p.failedBatch}批未完成：${p.errorHint}（${p.errorCode}）。${summary}`;
    }
    return summary;
  } catch { return task.progress; }
}
async function forgetPageAnalysis() {
  const task = activeAnalysisTask.value; if (!task) return;
  try { await ElMessageBox.confirm('仅在任务已过期或服务重启无法恢复时清除。此操作不会取消服务器任务；再次识别会创建新任务。', '清除任务引用', {type: 'warning'}); } catch { return; }
  removeTaskReference(task.key);activeAnalysisTask.value = null; analysisProgress.value = '';
}
async function cancelPageAnalysis() {
  const task = activeAnalysisTask.value;
  if (!task || analysisCancelling.value) return;
  analysisCancelling.value = true;
  try { const result = await pageAnalysisTasksApi.cancel(task.siteId, task.id); analysisProgress.value = taskProgress(result); }
  catch (cause) { analysisError.value = cause instanceof Error ? cause.message : '取消结果未知，请继续查询原任务'; }
  finally { analysisCancelling.value = false; }
}
async function analyzePage() {
  const siteId = Number(siteContext.currentSite?.id); const workspace = unitsWorkspace.value;
  if (!siteId || !workspace || busy.value) return;
  const version = unitsVersion;
  const scope=resumeScope.value;if(!scope)return;
  const key=analysisKey(scope,workspace.sourcePath,workspace.digest);
  const existingId=readTaskReference(key)|| (activeAnalysisTask.value?.key===key?activeAnalysisTask.value.id:null);
  if (existingId && unitsDirty.value) {
    analysisError.value = '当前页面有未保存的人工修改，请先保存或重新读取页面，再恢复原识别任务。';
    return;
  }
  if (!existingId && (unitsDirty.value || workspace.revision > 0)) {
    try { await ElMessageBox.confirm('自动分批识别只生成待确认草稿；同页来源未变的已确认判断将保留且不重新调用模型。', '确认识别', {type: 'warning'}); } catch { return; }
  }
  if (version !== unitsVersion) return;
  const initialUnits = JSON.stringify(workspace.units);
  const initialRevision = workspace.revision;
  analyzing.value = true; analysisError.value = ''; analysisResult.value = null;
  const id = existingId || crypto.randomUUID();
  activeAnalysisTask.value = {id, siteId, key}; rememberTaskReference(key,id);
  try {
    let task = existingId ? await pageAnalysisTasksApi.get(siteId, id) : await pageAnalysisTasksApi.start(siteId, workspace.sourcePath, id);
    while (version === unitsVersion && (task.state === 'pending' || task.state === 'running')) {
      analysisProgress.value = taskProgress(task);
      await new Promise(resolve => setTimeout(resolve, 1000));
      if (version !== unitsVersion) return;
      task = await pageAnalysisTasksApi.get(siteId, id);
    }
    if (version !== unitsVersion) return;
    analysisProgress.value = taskProgress(task);
    if (task.state !== 'completed') { removeTaskReference(key); activeAnalysisTask.value = null; throw new Error(task.error || '任务未完成；原人工方案保持不变'); }
    const result = JSON.parse(task.text) as UnitAnalysis;
    if (result.digest !== workspace.digest) throw new Error('页面来源已变更，请重新读取后再识别');
    if (workspace.revision !== initialRevision || JSON.stringify(workspace.units) !== initialUnits || result.revision < workspace.revision) {
      removeTaskReference(key); activeAnalysisTask.value = null;
      throw new Error('识别期间页面已修改，或任务结果早于当前修订；已保留当前人工方案，请重新读取后决定是否再次识别');
    }
    installingUnits=true;workspace.units = result.units; workspace.revision = result.revision;installingUnits=false;
    analysisResult.value = result; selectedUnitIds.value = []; unitsDirty.value = true;stashLocalDraft();
    removeTaskReference(key); activeAnalysisTask.value = null;
  } catch (cause) {
    if (version === unitsVersion) {
      const retained = readTaskReference(key) === id;
      analysisError.value = `${cause instanceof Error ? cause.message : '任务查询中断'}。${retained ? '任务标识已保留，再点识别仅查询原任务，不自动重建；服务重启/过期会丢失内存进度。' : '该任务已明确结束，再次点击识别将创建新任务。'}`;
    }
  } finally { analyzing.value = false; }
}

async function loadSeedFields() {
  const siteId = siteContext.currentSite?.id;
  const menuId = targetMenuId.value;
  const version = seedVersion;
  seedError.value = '';
  seedFields.value = [];
  mapping.value = {};
  importResult.value = '';
  if (!siteId || !menuId) return;
  try {
    const fields = await http.get<typeof seedFields.value>(`/api/admin/v2/sites/${siteId}/template-seed/fields`, {params: {menuId}});
    if (version === seedVersion && siteContext.currentSite?.id === siteId && targetMenuId.value === menuId) seedFields.value = fields;
  } catch (cause) { if (version === seedVersion) seedError.value = cause instanceof Error ? cause.message : '读取字段失败'; }
}

async function importSeed() {
  if (!seedPreview.value || !targetMenuId.value || !mapping.value.title || importing.value) return;
  const siteId = siteContext.currentSite?.id;
  const payload = {menuId: targetMenuId.value, sourcePath: seedPreview.value.sourcePath, digest: seedPreview.value.digest, mapping: Object.fromEntries(Object.entries(mapping.value).filter(([, value]) => value))};
  try { await ElMessageBox.confirm('确认按当前字段映射创建一条草稿？请先核对文字、资源路径和目标栏目。', '确认导入', {type: 'warning', confirmButtonText: '导入草稿', cancelButtonText: '取消'}); }
  catch { return; }
  if (siteContext.currentSite?.id !== siteId) return;
  importing.value = true;
  try {
    const result = await http.post<{recordId: number; alreadyImported: boolean}>(`/api/admin/v2/sites/${siteId}/template-seed/import`, payload);
    if (siteContext.currentSite?.id === siteId) importResult.value = result.alreadyImported ? `该候选已导入，记录 ID：${result.recordId}` : `草稿已创建，记录 ID：${result.recordId}。请进入内容管理检查。`;
  } catch (cause) { if (siteContext.currentSite?.id === siteId) seedError.value = cause instanceof Error ? cause.message : '导入失败'; }
  finally { importing.value = false; }
}

async function previewSeed() {
  const siteId = siteContext.currentSite?.id;
  if (!siteId || !sourcePath.value) return;
  const version = ++seedVersion;
  seedLoading.value = true;
  seedError.value = '';
  seedFields.value = [];
  targetMenuId.value = undefined;
  mapping.value = {};
  importResult.value = '';
  seedPreview.value = null;
  try {
    const result = await http.get<SeedPreview>(`/api/admin/v2/sites/${siteId}/template-seed/preview`, { params: { sourcePath: sourcePath.value } });
    if (version === seedVersion) seedPreview.value = result;
  } catch (cause) {
    if (version === seedVersion) seedError.value = cause instanceof Error ? cause.message : '提取失败，请重试';
  } finally { if (version === seedVersion) seedLoading.value = false; }
}

async function selectSite(id: number | string) {
  if(String(id)===String(siteContext.currentSite?.id))return;
  if(!await confirmLeaving('切换站点'))return;stashLocalDraft();rememberLocation();
  approvedSiteSwitch=true;
  try { await siteContext.switchSite(id); }
  catch (cause) { error.value = cause instanceof Error ? cause.message : '切换站点失败'; }
  finally {approvedSiteSwitch=false;}
}

async function refresh() {
  stashLocalDraft();rememberLocation();restoringSession=true;resumeScope.value=null;
  nativeFormBusy.value = false;
  publicBindingBusy.value = false;
  workbenchPublishBusy.value = false;
  homeBannerBusy.value = false;
  navigationLandingBusy.value = false;
  navigationLinksBusy.value = false;
  sharedContentBusy.value = false;
  const version = ++requestVersion;
  const site = siteContext.currentSite;
  siteModelPlan.value = null;
  navigationSnapshot.value = null;
  navigationPlanId.value = undefined;
  packageStatus.value = null;
  ++unitsVersion;
  unitsWorkspace.value = null;
  unitsDirty.value = false;
  unitsError.value = '';
  unitsLoading.value = false;
  packageInspection.value = null;
  previewPath.value = '';
  ++isolatedVersion;
  isolatedHtml.value = '';
  isolatedWarnings.value = [];
  isolatedLoading.value = false;
  columns.value = [];
  rootColumn.value = null;
  columnsLoaded.value = false;
  columnsRefreshError.value = false;
  error.value = '';
  ++seedVersion;
  seedLoading.value = false;
  seedPages.value = [];
  analysisResult.value = null;
  analysisError.value = '';
  sourcePath.value = '';
  planTargetMenuId.value=undefined;wantedPlanId='';activeAnalysisTask.value=null;analysisProgress.value='';unrestoredDraft.value=null;pendingLocalDraft=null;
  seedPreview.value = null;
  seedError.value = '';
  targetMenuId.value = undefined;
  seedFields.value = [];
  mapping.value = {};
  importResult.value = '';
  if (!site) { loading.value = false; restoringSession=false;return; }
  loading.value = true;
  packageReadProblem.value=null;
  const uiRequest = getUiPackageStatus(Number(site.id));
  const pagesRequest = uiRequest.then(status => status.status === 'NOT_UPLOADED'
    ? [] as SeedPage[]
    : http.get<SeedPage[]>(`/api/admin/v2/sites/${site.id}/template-seed/pages`));
  const inspectionRequest = uiRequest.then(status => status.status === 'NOT_UPLOADED' ? null : http.get<NonNullable<typeof packageInspection.value>>(`/api/admin/v2/sites/${site.id}/template-seed/package-inspection`));
  const results = await Promise.allSettled([uiRequest, getExistingColumns(Number(site.id)), pagesRequest, inspectionRequest]);
  if (version !== requestVersion) return;
  const [ui, menus, pages, inspection] = results;
  if (inspection.status === 'fulfilled') packageInspection.value = inspection.value;
  if (pages.status === 'fulfilled') seedPages.value = pages.value;
  else seedError.value = '初始内容候选页面加载失败，请刷新重试';
  if (ui.status === 'fulfilled') packageStatus.value = ui.value;
  if (menus.status === 'fulfilled') {
    rootColumn.value = menus.value.flat.find(item => String(item.id)===String(site.rootMenuId)) ?? null;
    columns.value = menus.value.flat.filter(item => String(item.id) !== String(site.rootMenuId));
    columnsLoaded.value = true;
  }
  const failures = results.filter(result => result.status === 'rejected');
  if (failures.length) error.value = '部分阶段状态加载失败，请刷新重试；未加载的数据不会标记为完成。';
  if (ui.status === 'rejected') {
    packageReadProblem.value = ui.reason instanceof AdminHttpError && (ui.reason.status===403 || String(ui.reason.code)==='403') ? 'forbidden' : 'transient';
    activeStep.value=0;
    error.value=packageReadProblem.value==='forbidden' ? '当前账号无权读取此站页面包。请选择其他已授权站点，或请平台管理员开通本站工作台权限。' : '暂时无法读取页面包状态，可重新读取继续工作。';
  }
  const scope=currentScope();resumeScope.value=scope;
  if(scope&&pages.status==='fulfilled'&&menus.status==='fulfilled'){
    try{const saved=readWorkbenchLocation(localStorage,scope,seedPages.value.map(page=>page.sourcePath),[...columns.value.map(menu=>Number(menu.id)),...(rootMenuId.value ? [rootMenuId.value] : [])]);resumeNotice.value=saved.notices.join(' ');if(saved.state){activeStep.value=saved.state.step;wantedPlanId=saved.state.planId||'';planTargetMenuId.value=saved.state.menuId;sourcePath.value=saved.state.sourcePath;previewPath.value=saved.state.sourcePath;resumeNotice.value ||= '已恢复当前页面和步骤，已保存方案按当前权限重新读取与校验。';}else{activeStep.value=hasPackage.value?7:0;}}
    catch{resumeNotice.value='本机继续位置无法读取，请选择页面；服务端已保存方案不受影响。';}
  }
  if (route.query.uploadedVersion && String(site.id) === route.query.siteId && ui.status === 'fulfilled') {
    activeStep.value = route.query.uploadedVersion === ui.value.version ? 7 : 0;
    if (activeStep.value === 0) resumeNotice.value = '此站的页面包已更换，请核对当前版本后再分析。';
    await router.replace({path:route.path,query:{siteId:String(site.id)}});
    if (version !== requestVersion) return;
  }
  if(route.query.packageReview==='1' && hasPackage.value)activeStep.value=7;
  restoringSession=false;loading.value = false;
  if(route.query.releaseCheck==='1' && String(site.id)===route.query.siteId)await focusReleaseCheck();
}

async function focusReleaseCheck(){
  if(!await changeStep(6))return;
  await nextTick();
  const panel=document.getElementById('workbench-static-audit');
  panel?.focus({preventScroll:true});
  panel?.scrollIntoView({block:'start'});
}

async function refreshColumns() {
  const site = siteContext.currentSite;
  if (!site) return;
  const version = requestVersion, columnsVersion = ++columnsRequestVersion;
  columnsRefreshError.value = false;
  try {
    const result = await getExistingColumns(Number(site.id));
    if (version !== requestVersion || columnsVersion !== columnsRequestVersion) return;
    rootColumn.value = result.flat.find(item => String(item.id)===String(site.rootMenuId)) ?? null;
    columns.value = result.flat.filter(item => String(item.id) !== String(site.rootMenuId));
    columnsLoaded.value = true;
  } catch {
    if (version !== requestVersion || columnsVersion !== columnsRequestVersion) return;
    columnsLoaded.value = false;
    columnsRefreshError.value = true;
  }
}

async function upload(file: UploadFile) {
  if (!file.raw || busy.value || !siteContext.currentSite) return;
  if (!/\.zip$/i.test(file.raw.name)) { ElMessage.warning('请选择 ZIP 文件'); return; }
  const siteId = siteContext.currentSite.id;
  uploading.value = true;
  error.value = '';
  try {
    await uploadUiPackage(Number(siteId), file.raw);
    if (String(siteContext.currentSite?.id) === String(siteId)) {
      ElMessage.success('UI 包上传成功');
      await refresh();
    }
  } catch (cause) {
    if (String(siteContext.currentSite?.id) === String(siteId)) error.value = cause instanceof Error ? cause.message : '上传失败，请重试';
  } finally { uploading.value = false; }
}

const rootColumn=ref<MenuNode|null>(null);
const packagePagePaths = computed(() => seedPages.value.map(page => page.sourcePath));
const checklistColumns=computed(()=>rootColumn.value ? [rootColumn.value,...columns.value] : columns.value);
const packageColumns=computed(()=>{
  const items=checklistColumns.value.filter(menu=>menu.pageType===0 || menu.pageType===1);
  return rootMenuId.value && !items.some(menu=>Number(menu.id)===rootMenuId.value)
    ? [{id:rootMenuId.value,name:'首页',pageType:0} as MenuNode,...items] : items;
});
const packageStructureRead = ref<'loading' | 'ready' | 'error'>('loading');
const packageStructure=ref<{plan:SiteModelPlan;navigation:SiteNavigation|null}|null>(null);
const appliedPackageNavigation=computed(()=>{
  const id=packageAnalysisPanel.value?.structurePlanId;
  if(!id)return null;
  const nav=navigationPlanId.value===id && navigationSnapshot.value ? navigationSnapshot.value
    : packageStructure.value?.plan.id===id ? packageStructure.value.navigation : null;
  return nav?.status==='APPLIED' ? nav : null;
});
const reviewedNavigation=computed(()=>{
  const active=siteModelPlan.value;
  const navigation=navigationSnapshot.value;
  const current=(plan:SiteModelPlan)=>plan.siteId===Number(siteContext.currentSite?.id) && plan.packageVersion===packageStatus.value?.version && plan.status==='CONFIRMED';
  if(active && navigation?.status==='APPLIED' && navigationPlanId.value===active.id && current(active))return {plan:active,navigation};
  const attached=packageStructure.value;
  if(attached?.navigation?.status==='APPLIED' && attached.plan.id===packageAnalysisPanel.value?.structurePlanId && current(attached.plan))
    return {plan:attached.plan,navigation:attached.navigation};
  return null;
});
const releaseReviewScope=computed<ReleaseScope|null>(()=>{
  const reviewed=reviewedNavigation.value;
  if(!reviewed || reviewed.plan.revision!==reviewed.navigation.planRevision)return null;
  return {id:reviewed.plan.id,packageVersion:reviewed.plan.packageVersion,
    planRevision:reviewed.plan.revision,navigationRevision:reviewed.navigation.revision};
});
const restoreReleaseProgress = computed(() => {
  const adminId = siteContext.currentAdmin?.id, siteId = siteContext.currentSite?.id;
  return !!adminId && !!siteId && !!releaseReviewScope.value &&
    hasRememberedReleaseReads(String(adminId), Number(siteId), releaseReviewScope.value);
});
function invalidateReleaseReview() {
  releaseNavigationEvidence.value=undefined;
  releasePanel.value?.invalidateProgress();
}
function refreshChangedBusinessEvidence() {
  invalidateReleaseReview();
  void checklistPanel.value?.refreshProgress(false);
}
// 公共链接可以指向原生发布目标，其他工作台步骤仍使用已应用结构。
const navigationLinkScope=computed(()=>{
  const active=siteModelPlan.value;
  const attached=packageStructure.value;
  const candidates=active && navigationPlanId.value===active.id
    ? [{plan:active,navigation:navigationSnapshot.value},attached] : [attached];
  for (const candidate of candidates) {
    if (!candidate?.navigation || candidate.navigation.status==='APPLYING') continue;
    const {plan,navigation}=candidate;
    if (plan.siteId===Number(siteContext.currentSite?.id) && plan.packageVersion===packageStatus.value?.version &&
      plan.status==='CONFIRMED') return {plan,navigation};
  }
  return reviewedNavigation.value;
});
const packageApplicationScope=computed(()=>{
  const id=packageAnalysisPanel.value?.structurePlanId;
  if(!id)return null;
  const plan=siteModelPlan.value?.id===id ? siteModelPlan.value : packageStructure.value?.plan;
  const navigation=navigationPlanId.value===id && navigationSnapshot.value ? navigationSnapshot.value : packageStructure.value?.navigation;
  if(plan?.id!==id || plan.status!=='CONFIRMED' || !navigation?.nodes.length ||
    (navigation.status!=='CONFIRMED' && navigation.status!=='APPLYING' &&
      (navigation.status!=='APPLIED' || packageAnalysisPanel.value?.structurePlanRevision===plan.revision)))return null;
  return {plan,navigation};
});
const packageNext=computed(()=>{
  const action=packageAnalysisPanel.value?.nextAction;
  const application=packageApplicationScope.value;
  if(application){
    const label=application.navigation.status==='CONFIRMED' ? workbenchApplyLabel : workbenchApplyResumeLabel;
    return {kind:'application',label,button:label,text:'查看将创建和复用的栏目、字段模型及影响范围，确认后应用。'};
  }
  if(action?.kind!=='structure')return action;
  const id=packageAnalysisPanel.value?.structurePlanId;
  const plan=siteModelPlan.value?.id===id ? siteModelPlan.value : packageStructure.value?.plan;
  const navigation=navigationPlanId.value===id ? navigationSnapshot.value : packageStructure.value?.navigation;
  if(plan?.status==='CONFIRMED' && navigation?.nodes.length && navigation.status==='DRAFT')
    return {...action,label:'审核导航与栏目对应',button:'审核导航与栏目对应',text:'先核对原页、内容模型及栏目归属，再确认导航。'};
  if(plan?.status==='DRAFT')return {...action,label:'先核对内容模型',button:'审核内容模型'};
  return action;
});
watch([()=>packageAnalysisPanel.value?.structurePlanId,()=>siteContext.currentSite?.id,()=>packageStatus.value?.version],
  async ([id,siteId,version],_,onCleanup)=>{
    packageStructure.value=null;
    packageStructureRead.value='loading';
    if(!id || !siteId || !version)return;
    const controller=new AbortController();onCleanup(()=>controller.abort());
    try {
      const plan=await siteModelApi.get(Number(siteId),id);
      if(controller.signal.aborted || plan.siteId!==Number(siteId) || plan.packageVersion!==version)return;
      packageStructure.value={plan,navigation:null};
      if(plan.status!=='CONFIRMED'){packageStructureRead.value='ready';return;}
      const navigation=await siteModelApi.navigation(Number(siteId),plan,controller.signal);
      if(!controller.signal.aborted){packageStructure.value={plan,navigation};packageStructureRead.value='ready';}
    } catch {if(!controller.signal.aborted)packageStructureRead.value='error';}
  });
// Persistent step numbers remain compatible with saved drafts, review links and handoffs.
const stageSteps: Record<SiteBuildStageId, readonly number[]> = {
  package: [0], structure: [7, 5], content: [1, 2, 3], preview: [4], release: [6],
};
const viewingStage = computed<SiteBuildStageId>(() =>
  (Object.keys(stageSteps) as SiteBuildStageId[]).find(id => stageSteps[id].includes(activeStep.value)) ?? 'package');
const stageTools = computed(() => workSteps.filter(([step]) => stageSteps[viewingStage.value].includes(step)));
const currentStepLabel = computed(() => workSteps.find(([step]) => step === activeStep.value)?.[1] ?? '选择项目');
const siteProgress = computed(() => {
  const siteId = Number(siteContext.currentSite?.id) || null;
  const version = packageStatus.value?.version;
  const applied = checklistPanel.value?.appliedStructure;
  const model = reviewedNavigation.value ?? (applied?.plan.siteId === siteId && applied?.plan.packageVersion === version ? applied : null)
    ?? packageStructure.value ?? (siteModelPlan.value ? { plan: siteModelPlan.value,
      navigation: navigationPlanId.value === siteModelPlan.value.id ? navigationSnapshot.value : null } : null);
  const analysis = packageAnalysisPanel.value?.stageEvidence;
  const pack = packageStatus.value;
  const input: SiteBuildProgressInput = {
    siteId, switching: siteContext.switching,
    package: !siteId ? undefined : packageReadProblem.value ? { siteId, state: 'error' }
      : pack ? { siteId, state: 'ready', value: pack } : { siteId, state: 'loading' },
    analysis,
    structure: !siteId || !version ? undefined : model && (model.navigation || packageStructureRead.value !== 'error')
      ? { siteId: model.plan.siteId, packageVersion: model.plan.packageVersion, state: 'ready', value: model }
      : packageStructureRead.value === 'error' ? { siteId, packageVersion: version, state: 'error' }
      : analysis?.state === 'ready' && !packageAnalysisPanel.value?.structurePlanId
        ? { siteId, packageVersion: version, state: 'ready', value: { plan: null, navigation: null } }
        : undefined,
    checklist: checklistPanel.value?.stageEvidence,
    release: releasePanel.value?.stageEvidence,
  };
  return getSiteBuildProgress(input);
});
function structureStep() {
  return siteProgress.value.stages.find(stage => stage.id === 'structure')?.step === 5 ? 5 : 7;
}
const reviewingRelease = computed(() => {
  const scope = releaseReviewScope.value;
  return !!scope && Object.values(releasePanel.value?.stageEvidence ?? {}).some(item =>
    item?.state === 'loading' && item.siteId === Number(siteContext.currentSite?.id) &&
    item.packageVersion === scope.packageVersion && item.planId === scope.id &&
    item.planRevision === scope.planRevision && item.navigationRevision === scope.navigationRevision);
});
const suggestedStage = computed(() => reviewingRelease.value ? 'release' : siteProgress.value.currentStageId);
const workflowTaskLabel = computed(() => {
  if (reviewingRelease.value) return '正在核对当前版本的签收回执';
  const id = suggestedStage.value;
  return id === 'content' || id === 'preview' ? checklistPanel.value?.nextTaskLabel(id) : undefined;
});
const workflowActionLabel = computed(() => {
  switch (suggestedStage.value) {
    case 'package': return !siteContext.currentSite ? '选择站点' : packageReadProblem.value ? '核对页面包状态' : '打开页面包';
    case 'structure': return structureStep() === 5 ? '核对栏目结构' : '进入整包分析与审核';
    case 'content': return workflowTaskLabel.value ? '处理下一项内容绑定' : '核对内容待办';
    case 'preview': return workflowTaskLabel.value ? '处理下一项模板绑定' : '核对模板待办';
    default: return '查看整站签收';
  }
});
async function focusWork(element?: HTMLElement | null) {
  await nextTick();
  element?.focus({ preventScroll: true });
  element?.scrollIntoView({ block: 'start' });
}
async function openWorkflowStage(id: SiteBuildStageId) {
  if (busy.value || (id !== 'package' && !hasPackage.value)) return false;
  if (id === 'release') return showReleaseReview();
  const step = id === viewingStage.value ? activeStep.value : id === 'structure' ? structureStep() : stageSteps[id][0]!;
  if (await changeStep(step)) {
    await nextTick();
    if (!siteContext.currentSite) await focusWork(document.getElementById('workbench-project-site'));
    else await focusWork(workspaceHeading.value);
  }
}
async function openTaskList() {
  if (!await changeStep(6)) return;
  await nextTick();
  await focusWork(checklistPanel.value?.$el?.querySelector('strong[tabindex]'));
}
async function showReleaseReview() {
  if (!await changeStep(6)) return;
  await nextTick();
  releasePanel.value?.focusProgress();
}
async function continueWorkflow(event?: MouseEvent) {
  if (busy.value) return;
  const id = suggestedStage.value;
  if (!id || id === 'release') return showReleaseReview();
  if (id === 'package') {
    if (!await changeStep(0)) return;
    await nextTick();
    return focusWork(document.getElementById(siteContext.currentSite && !packageReadProblem.value ? 'workbench-project-package' : 'workbench-project-site'));
  }
  if (id === 'structure') {
    if (structureStep() === 5) {
      await openPackageStructure(reviewedNavigation.value?.plan.id ?? packageAnalysisPanel.value?.structurePlanId ?? siteModelPlan.value?.id ?? null);
    } else if (await changeStep(7)) {
      await nextTick();
      await focusWork(workspaceHeading.value);
    }
    return;
  }
  if (!await changeStep(6)) return;
  await nextTick();
  if (checklistPanel.value?.openNext(id, event)) return;
  await focusWork(checklistPanel.value?.$el?.querySelector('strong[tabindex]'));
}
const rootMenuId=computed(()=>Number(siteContext.currentSite?.rootMenuId)||undefined);
const targetHome=computed(()=>!!rootMenuId.value && rootMenuId.value===planTargetMenuId.value);
const targetColumn = computed<MenuNode|undefined>(() => targetHome.value
  ? rootColumn.value ?? {id:rootMenuId.value,name:'首页'}
  : columns.value.find(menu => Number(menu.id) === planTargetMenuId.value));
const currentMenuLabel=computed(()=>targetColumn.value?.className||targetColumn.value?.name||'请在上方选择栏目');
const targetNavigation = computed(() => navigationSnapshot.value?.nodes.find(node => node.menuId === planTargetMenuId.value));
const pageListScope = computed(() => {
  const context = reviewedNavigation.value;
  const ownerMenuId = planTargetMenuId.value;
  if (!context || !ownerMenuId || !sourcePath.value || targetColumn.value?.pageType !== 0
      || !context.navigation.nodes.some(node => node.kind === 'SINGLE' && !node.drift
        && node.menuId === ownerMenuId && node.sourcePath === sourcePath.value)) return null;
  const usedMenuIds = new Set(context.plan.proposal.models.map(model => model.menuId));
  return {
    plan: context.plan, ownerMenuId,
    menus: columns.value.filter(menu => menu.pageType === 1 && Number(menu.parentId) === ownerMenuId
      && !usedMenuIds.has(Number(menu.id)) && !context.navigation.nodes.some(node => node.menuId === Number(menu.id))),
  };
});
async function receivePageListSupplement(next:SiteModelPlan, name:string, menuId:number) {
  const scope = pageListScope.value;
  if (!scope || scope.plan.id !== next.id || next.revision !== scope.plan.revision + 1
      || next.siteId !== Number(siteContext.currentSite?.id) || next.packageVersion !== packageStatus.value?.version)
    return;
  pageListNotice.value = { siteId:next.siteId, sourcePath:sourcePath.value,
    message:`已将「${name}」增补至维护栏目 #${menuId}。前往整站结构核对当前修订、导航接续和列表初值。` };
  siteModelPlan.value = next;
  if (packageStructure.value?.plan.id === next.id) packageStructure.value = { ...packageStructure.value, plan: next };
  await siteModelPanel.value?.reload(next.id);
}
function isOverviewNode(node: SiteNavigationNode, nodes = navigationSnapshot.value?.nodes || []) {
  const children = nodes.filter(child => child.parentKey === node.key);
  return node.kind === 'LIST' && !!node.sourcePath && children.length >= 2 &&
    children.every(child => child.kind === 'LIST' && child.sourcePath === node.sourcePath);
}
const targetOverview = computed(() => {
  const nodes = reviewedNavigation.value?.navigation.nodes || [];
  const node = nodes.find(item => item.menuId === planTargetMenuId.value);
  return node && !node.drift && isOverviewNode(node, nodes) ? node : null;
});
function modelsForMenu(menuId: number, plan=reviewedNavigation.value?.plan || siteModelPlan.value) {
  if (plan?.status !== 'CONFIRMED' || plan.packageVersion !== packageStatus.value?.version) return [];
  const navigation = navigationPlanId.value===plan.id ? navigationSnapshot.value
    : packageStructure.value?.plan.id===plan.id ? packageStructure.value.navigation : null;
  const navigationKeys = new Set(navigation?.status === 'APPLIED' ? navigation.nodes
    .filter(node => node.menuId === menuId && !node.drift && node.kind !== 'DIRECTORY')
    .map(node => node.modelKey) : []);
  return plan.proposal.models.filter(model => model.menuId === menuId || navigationKeys.has(model.key));
}
function sourcesForMenu(menuId: number, modes: string[], modelKey?: string | null) {
  const model = reviewedNavigation.value?.plan || siteModelPlan.value;
  if (model?.status !== 'CONFIRMED' || model.packageVersion !== packageStatus.value?.version) return [];
  const keys = new Set(modelsForMenu(menuId).filter(item => !modelKey || item.key === modelKey).map(item => item.key));
  return [...new Set(model.proposal.regions.filter(region => keys.has(region.modelKey) &&
    modes.includes(region.mode) && (region.mode === 'LIST' || region.records.length === 1))
    .map(region => region.sourcePath).filter(path => seedPages.value.some(page => page.sourcePath === path)))];
}
const targetBindingSource = computed(() => {
  const id = planTargetMenuId.value;
  if (!id || targetNavigation.value?.kind==='DIRECTORY' || targetNavigation.value?.drift) return undefined;
  const paths = sourcesForMenu(id, ['SINGLE','DETAIL'], targetNavigation.value?.modelKey);
  return paths.length === 1 ? paths[0] : undefined;
});
const targetListSource = computed(() => {
  const nav = targetNavigation.value || appliedPackageNavigation.value?.nodes.find(node=>node.menuId===planTargetMenuId.value);
  if (nav?.kind==='DIRECTORY' || nav?.drift) return undefined;
  if (nav?.kind === 'LIST' && !nav.drift && seedPages.value.some(page => page.sourcePath === nav.sourcePath)) return nav.sourcePath!;
  const paths = planTargetMenuId.value ? sourcesForMenu(planTargetMenuId.value, ['LIST']) : [];
  return paths.length === 1 ? paths[0] : undefined;
});
const listReviewTarget=computed(()=>targetColumn.value?.pageType===1 && targetListSource.value===sourcePath.value);
const reviewReturn=ref<{sourcePath:string;revision:number;digest:string;unitCount:number}>();
watch([()=>siteContext.currentSite?.id,()=>packageStatus.value?.version,sourcePath],()=>{reviewReturn.value=undefined;});
watch(activeStep,step=>{if(step!==7)reviewReturn.value=undefined;});
const reviewNext=computed(()=>{
  if(listReviewTarget.value)return {kind:'list',label:'核对列表卡片',help:'继续核对本栏目的卡片、详情链接和分页。'};
  if(planTargetMenuId.value && (canOpenSavedPlan.value || !newBindingNotice.value))
    return {kind:'fields',label:'审核字段映射',help:'核对每项内容要复用或新增的 CMS 字段。'};
  const confirmed=packageAnalysisPanel.value?.confirmedMenuFor(sourcePath.value);
  if(confirmed && confirmed===planTargetMenuId.value)
    return {kind:'structure',label:'核对栏目结构',help:newBindingNotice.value || '请核对当前原页在整站结构中的用途。'};
  return {kind:'owner',label:confirmed ? '继续到已确认栏目' : '核对本页维护栏目',
    help:confirmed ? '使用已确认的栏目继续处理本页修正。' : '在整包中确认由哪个栏目维护本页，再使用这里保存的修正。'};
});
async function continueConfirmedReview(path:string,menuId:number){
  if(path!==sourcePath.value || unitsDirty.value || !unitsWorkspace.value?.revision ||
    !packageAnalysisPanel.value?.hasConfirmedTarget(path,menuId))return;
  const node=appliedPackageNavigation.value?.nodes.find(item=>item.menuId===menuId && item.sourcePath===path && !item.drift);
  await openWorkbenchTarget(path,menuId,node?.kind==='LIST' ? 3 : 2);
  await nextTick();
  if(activeStep.value===2 && sourcePath.value===path && planTargetMenuId.value===menuId)document.getElementById('workbench-field-plan-heading')?.focus();
}
async function continueUnitReview(){
  const workspace=unitsWorkspace.value;
  if(!workspace || unitsSaving.value || busy.value || workspace.sourcePath!==sourcePath.value)return;
  if(unitsDirty.value && !await saveUnits())return;
  if(!unitsWorkspace.value?.revision){ElMessage.warning('请先保存本页的内容单元。');return;}
  const next=reviewNext.value.kind;
  if(next==='list')return goToList();
  if(next==='fields')return openPlanReview();
  if(next==='structure')return openPackageStructure(packageAnalysisPanel.value?.structurePlanId || null);
  const menuId=packageAnalysisPanel.value?.confirmedMenuFor(sourcePath.value);
  if(menuId)return continueConfirmedReview(sourcePath.value,menuId);
  const saved=unitsWorkspace.value;
  reviewReturn.value={sourcePath:saved.sourcePath,revision:saved.revision,digest:saved.digest,unitCount:saved.units.length};
  await openPackagePageForReview(sourcePath.value);
}

const navigationFact = computed(() => {
  const node = targetNavigation.value;
  if (!node) return '已读取 CMS 栏目，尚无对应的导航应用证据';
  if (node.drift) return '实际栏目与导航审核记录有差异';
  if (navigationSnapshot.value?.originPlanId) return '沿用已应用栏目结构，新入口待核对';
  return navigationSnapshot.value?.status === 'APPLIED' ? '导航对应已应用' : '导航对应待应用';
});
const fieldFact = computed(() => {
  if (fieldPlan.value && !planStale.value) {
    if (fieldPlan.value.status === 'DRAFT') return '当前来源的字段计划待确认';
    return fieldPlan.value.status === 'CONFIRMED_WITH_PENDING' ? '当前来源的字段计划有待处理项' : '当前来源的字段计划已确认';
  }
  const models = planTargetMenuId.value ? modelsForMenu(planTargetMenuId.value) : [];
  const model = models.length === 1 ? models[0] : null;
  if (!model) return '当前栏目没有已读取的字段对应计划';
  const bound = model.fields.filter(field => field.fieldName).length;
  return `结构计划对应了 ${bound} / ${model.fields.length} 项字段，需检查页面绑定`;
});
const initializationFact = computed(() => {
  if (fieldPlan.value && !planStale.value) {
    if (textBinding.value?.initialized || recoveryState.value?.phase === 'INITIALIZED' && recoveryState.value.activePlanId === fieldPlan.value.id)
      return fieldPlan.value.pageType === 1 ? '当前详情已关联到草稿' : '当前来源的初值已保存';
    if (recoveryState.value?.phase === 'PREPARED' || recoveryState.value?.phase === 'FAILED') return '初值应用待核对或恢复';
  }
  if (targetNavigation.value?.contentStatus === 'HAS_CONTENT') return '导航读到已有内容，待核对对应初值';
  return '当前来源的初值尚未核实';
});
const templateFact = computed(() => {
  const column = targetColumn.value;
  if (!column) return '请选择栏目';
  if (targetHome.value && !rootColumn.value) return '首页模板指针未读取';
  const listConfigured = column.listPageTemplate?.trim() ? '已配置' : '未配置';
  return column.pageType === 1
    ? `列表${listConfigured} · 详情${column.detailPageTemplate?.trim() ? '已配置' : '未配置'}`
    : `单页模板${listConfigured}`;
});
const targetReading=ref(false);
const targetReadErrors=ref<string[]>([]);
const registeredRoutes=ref<string[]|null>(null);
const serviceRunning=ref<boolean|null>(null);
const publicBinding=ref<Awaited<ReturnType<typeof inspectPublicBinding>>|null>(null);
const targetReadScope=computed(()=>JSON.stringify([siteContext.currentSite?.id,siteContext.currentAdmin?.id,planTargetMenuId.value,fieldPlan.value?.id,planStale.value]));
let targetReadVersion=0;
watch(targetReadScope,()=>{
  targetReadVersion++;targetReading.value=false;targetReadErrors.value=[];
  registeredRoutes.value=null;serviceRunning.value=null;publicBinding.value=null;
},{flush:'sync'});
const publicationFact=computed(()=>{
  const column=targetColumn.value;
  if (column?.hasFrontChannel===undefined) return registeredRoutes.value?.length
    ? `读取到运行路由 ${registeredRoutes.value.join('、')}，栏目公开配置未核实` : '栏目公开配置未核实';
  if (!column.hasFrontChannel) return '栏目前台入口未开放';
  const urls=[column.listPageUrl,column.pageType===1 ? column.detailPageUrl : null].filter(Boolean);
  if (!urls.length) return '栏目前台已开放，尚无配置路由';
  const service=serviceRunning.value===null ? '站点服务状态未核实' : serviceRunning.value ? '站点服务运行中' : '站点服务已停止';
  const routes=registeredRoutes.value===null ? '运行路由未核实' : registeredRoutes.value.join('、') || '当前未读到此栏目运行路由';
  return `已配置 ${urls.join('、')} · ${service} · ${routes}${publicBinding.value?.applied ? ' · 当前字段计划的模板已应用，待前台访问验收' : ''}`;
});
async function readTargetStatus() {
  const siteId=Number(siteContext.currentSite?.id), columnId=Number(targetColumn.value?.id);
  if (!siteId || !columnId || targetReading.value) return;
  const version=++targetReadVersion;
  const plan=fieldPlan.value;
  targetReading.value=true;targetReadErrors.value=[];
  registeredRoutes.value=null;serviceRunning.value=null;publicBinding.value=null;
  try {
    const [routes, service, binding]=await Promise.allSettled([
      getSiteRoutes(siteId), getSiteServiceStatus(siteId),
      plan && !planStale.value && plan.status!=='DRAFT' ? inspectPublicBinding(siteId,plan.id) : Promise.resolve(null)
    ]);
    if(version!==targetReadVersion)return;
    if(routes.status==='fulfilled')registeredRoutes.value=routes.value.routes
      .filter(route=>Number(route.columnId)===columnId).map(route=>route.pattern || route.originalUrl || '').filter(Boolean);
    else targetReadErrors.value.push('运行路由读取失败');
    if(service.status==='fulfilled')serviceRunning.value=service.value.status==='RUNNING';
    else targetReadErrors.value.push('站点服务状态读取失败');
    if(binding.status==='fulfilled')publicBinding.value=binding.value;
    else targetReadErrors.value.push('字段计划公开应用状态读取失败');
  } finally {
    if(version===targetReadVersion)targetReading.value=false;
  }
}
async function refreshTargetAfterChange() {
  const siteId=siteContext.currentSite?.id;
  invalidateReleaseReview();
  targetReadVersion++;
  targetReading.value=false;
  await refreshColumns();
  if(siteId===siteContext.currentSite?.id) await readTargetStatus();
}
function homeBannerChanged() {
  publishRefreshKey.value++;
  return refreshTargetAfterChange();
}
function publicBindingChanged() {
  invalidateReleaseReview();
  publishRefreshKey.value++;
  targetReadVersion++;
  targetReading.value=false;
  targetReadErrors.value=[];
  registeredRoutes.value=null;
  publicBinding.value=null;
  return refreshColumns();
}
const searchEvidence = computed(()=>{
  if(unitsWorkspace.value?.sourcePath!==sourcePath.value) return null;
  const texts=pageContentSources.value.filter(row=>row.kind==='TEXT');
  const heading=texts.find(row=>/(?:^|>\s*)h[1-3]\b/i.test(row.selector) &&
    /^(搜索结果|检索结果|站内搜索|站内检索|全站搜索|全站检索|search results?)$/i.test(row.rawValue.trim()));
  const counter=texts.find(row=>row!==heading && row.rawValue.length<180 &&
    /(?:搜索|检索).*(?:共|找到).*\d+.*(?:条|个)|\d+\s+(?:search )?results?\b/i.test(row.rawValue));
  return heading && counter ? {heading:heading.rawValue.trim(),counter:counter.rawValue.trim()} : null;
});
const singlePageMenu = computed(() => columns.value.find(menu => Number(menu.id)===planTargetMenuId.value && menu.pageType===0));
const formScope = computed(() => {
  if (!singlePageMenu.value || !siteContext.currentSite || !packageStatus.value?.version ||
      !sourcePath.value || unitsWorkspace.value?.sourcePath!==sourcePath.value || searchEvidence.value) return '';
  return JSON.stringify([siteContext.currentSite.id, packageStatus.value.version, singlePageMenu.value.id, sourcePath.value]);
});
const formCheck = ref<{scope:string; available:boolean} | null>(null);
watch([formScope, activeStep], async ([scope, step], _, onCleanup) => {
  if (!scope || step!==1 || formCheck.value?.scope===scope) return;
  let current=true;
  onCleanup(()=>{current=false;});
  try {
    const result=await inspectNativeForms(Number(siteContext.currentSite!.id), Number(singlePageMenu.value!.id), sourcePath.value);
    if (current) formCheck.value={scope,available:result.forms.length>0};
  } catch {
    // 读取失败时保留面板内的人工重试入口。
    if (current) formCheck.value={scope,available:true};
  }
});
const formTargetMenu = computed(() => formScope.value && formCheck.value?.scope===formScope.value && formCheck.value.available ? singlePageMenu.value : undefined);
const completedDetail = computed(() => fieldPlan.value?.pageType === 1 && !planStale.value
  && (textBinding.value?.initialized || recoveryState.value?.phase === 'INITIALIZED' && recoveryState.value.activePlanId === fieldPlan.value.id));
const listDetailPlan = ref<{ id:string; sourcePath:string; menuId:number } | null>(null);
watch(() => [siteContext.currentSite?.id, packageStatus.value?.version], () => { listDetailPlan.value = null; });
const recoveryStateLabels:Record<string,string>={INITIALIZED:'已生成初始内容',NONE:'尚未初始化',PREPARED:'初始内容需要核对恢复',FAILED:'需要检查并恢复'};
const currentWorkStatus=computed(()=>{
  if (unitsDirty.value) return localDraftStored.value ? '内容修正已本机暂存，尚未保存到CMS' : '内容修正尚未保存，请先保存到CMS';
  if (planChoicesDirty.value) return '映射选择尚未提交';
  if (activeStep.value===3) return '列表与查询';
  if (activeStep.value===4) return '预览与公开';
  if (completedDetail.value) return `详情已关联 · 记录 ${textBinding.value?.recordId ?? recoveryState.value?.recordId ?? '待读取'}`;
  if (recoveryState.value) return recoveryStateLabels[recoveryState.value.phase] || '应用状态需要检查';
  if (fieldPlan.value) return `已读取${fieldPlan.value.status==='DRAFT'?'待确认':fieldPlan.value.status==='CONFIRMED_WITH_PENDING'?'部分确认，仍有待处理':'已确认'}字段计划`;
  return unitsWorkspace.value ? `服务端草稿已读取 · 修订${unitsWorkspace.value.revision}` : '请选择页面继续';
});
const nextWorkAction=computed(()=>{
  if (packageReadProblem.value==='forbidden') return '请在「目标站点」选择已授权站点。';
  if (packageReadProblem.value || !packageStatus.value) return '等待读取页面包状态。';
  if (!hasPackage.value) return '在下方选择 ZIP 并上传，随后进行整包分析。';
  if (unitsDirty.value) return '核对后点击“保存人工修正”，才会保存到CMS。';
  if (planChoicesDirty.value) return '按当前选择生成计划，再明确确认。';
  if (activeStep.value===4) return planTargetMenuId.value
    ? '核对当前栏目路由、模板应用与内容发布状态，再访问实际前台。' : '选择要配置路由或发布内容的栏目。';
  if (targetNavigation.value?.kind==='DIRECTORY' && !targetHome.value) return '核对目录是否有独立落地来源；需要修改类型时先核对导航证据。';
  if (!sourcePath.value) return '选择当前页面，读取已有草稿。';
  if (activeStep.value===3) return '选定列表源页面和目标栏目，依次核对卡片、链接与分页。';
  if (!planTargetMenuId.value) return '选择目标栏目，读取服务端已有计划。';
  if (completedDetail.value) return '读取私有预览，或继续配置同栏目的列表。';
  if (recoveryState.value?.phase==='PREPARED' || recoveryState.value?.phase==='FAILED') return '先核对持久状态与可用的恢复操作。';
  if (!fieldPlan.value) return newBindingNotice.value || '可核对内容或按已确认结构生成字段计划。';
  return fieldPlan.value.status==='DRAFT' ? '核对映射并确认计划。' : '检查绑定与当前内容状态。';
});
function currentScope():WorkbenchScope|null {
  const admin=siteContext.currentAdmin?.id,site=siteContext.currentSite?.id,version=packageStatus.value?.version;
  return admin&&site&&version?{admin:String(admin),site:String(site),version}:null;
}
function rememberLocation(){
  if(route.query.packageReview==='1' && activeStep.value===7)return;
  if(restoringSession||!resumeScope.value)return;
  try{saveWorkbenchLocation(localStorage,resumeScope.value,{sourcePath:sourcePath.value,menuId:planTargetMenuId.value,step:activeStep.value,planId:fieldPlan.value?.id||savedPlanId.value||undefined});}
  catch{resumeNotice.value='本机继续位置无法保存；离开前请保存人工修正。';}
}
function stashLocalDraft(){
  const context=loadedUnitsContext,workspace=unitsWorkspace.value;
  if(installingUnits||!context||!workspace||context.path!==sourcePath.value||context.scope.site!==String(siteContext.currentSite?.id)||context.scope.admin!==String(siteContext.currentAdmin?.id)||unrestoredDraft.value)return;
  try{saveWorkbenchDraft(localStorage,context.scope,context.path,{digest:workspace.digest,revision:workspace.revision,units:workspace.units,unitsDirty:unitsDirty.value,menuId:planTargetMenuId.value,planId:fieldPlan.value?.id,fieldChoices:fieldChoices.value,choicesDirty:planChoicesDirty.value});localDraftStored.value=unitsDirty.value||planChoicesDirty.value;}
  catch(cause){localDraftStored.value=false;resumeNotice.value=cause instanceof Error?cause.message:'本机暂存失败，请先保存人工修正。';}
}
function installWorkspace(workspace:UnitsWorkspace,restoreLocal=true){
  installingUnits=true;unitsBaseline=JSON.stringify(workspace.units);unitsWorkspace.value=workspace;unitsDirty.value=false;installingUnits=false;
  const scope=resumeScope.value;if(!scope)return;loadedUnitsContext={scope,path:workspace.sourcePath};
  if(!restoreLocal)return;
  try{const draft=readWorkbenchDraft<ContentUnit>(localStorage,scope,workspace.sourcePath);pendingLocalDraft=draft;
    // 未编辑的浏览器缓存不是人工草稿，保留服务端最新生成的默认单元。
    if(draft && !draft.unitsDirty && !draft.choicesDirty){pendingLocalDraft=null;localDraftStored.value=false;return;}
    if(draft){const valid=draft.digest===workspace.digest&&draft.revision===workspace.revision&&draft.units.every(unit=>typeof unit.name==='string'&&unit.name.length<=100&&unitTypes.some(type=>type.value===unit.fieldType)&&Array.isArray(unit.sources)&&unit.sources.every(ref=>{const source=workspace.sources.find(item=>item.id===ref.sourceId);return !!source&&Number.isInteger(ref.start)&&Number.isInteger(ref.end)&&ref.start>=0&&ref.end>ref.start&&ref.end<=source.rawValue.length;}));
      if(!valid){localDraftStored.value=false;unrestoredDraft.value=draft;resumeNotice.value='服务端来源或修订已更新，未自动套用旧本机修正；旧修正保留供人工核对，服务端内容未被覆盖。';return;}
      installingUnits=true;workspace.units=structuredClone(draft.units);unitsDirty.value=draft.unitsDirty;localDraftStored.value=draft.unitsDirty||draft.choicesDirty;installingUnits=false;resumeNotice.value='已恢复此浏览器未保存修正；仅本机暂存，尚未保存到CMS或确认AI草稿。';if(fieldPlan.value)restoreLocalChoices(fieldPlan.value);
    }
  }catch(cause){resumeNotice.value=cause instanceof Error?cause.message:'本机临时修正读取失败';}
}
function restoreLocalChoices(plan:FieldPlan){
  const draft=pendingLocalDraft;if(!draft||!draft.choicesDirty||draft.planId!==plan.id||draft.menuId!==plan.menuId)return;
  if(Object.entries(draft.fieldChoices||{}).every(([id,value])=>plan.bindings.some(row=>row.unitId===id)&&(value==='' || value==='$new'||/^MENU_NAME:[1-9]\d*$/.test(value)||plan.fields.some(field=>field.fieldName===value)))){pendingLocalDraft=null;fieldChoices.value={...draft.fieldChoices};planChoicesDirty.value=true;resumeNotice.value='已恢复未提交的映射选择；请明确生成新计划后再确认，未自动修改字段。';}
}
const hasUnsavedEdits=computed(()=>unitsDirty.value||planChoicesDirty.value);
async function confirmLeaving(action:string, preserveStructure=false){
  if(handoffPanel.value?.saving){ElMessage.warning('请等待本站约定保存完成。');return false;}
  if(handoffPanel.value?.dirty){
    const kept=handoffPanel.value.cached;
    try { await ElMessageBox.confirm(kept ? '本站约定已暂存在当前标签页，返回本站待办可继续编辑。' : '本站约定尚未保存，本机也无法暂存。',action,
      {confirmButtonText:kept?'保留草稿并继续':'放弃草稿并继续',cancelButtonText:'留在此页',type:'warning'}); }
    catch {return false;}
  }
  if(applicationBusy.value){ElMessage.warning('正在应用栏目与字段，请等待结果。');return false;}
  if(!await confirmReleaseLeave(action))return false;
  if(packageAnalysisPanel.value?.isSaving()){ElMessage.warning('请等待整包操作返回。');return false;}
  if(!preserveStructure && packageAnalysisPanel.value?.hasUnsavedChanges()){
    try{await ElMessageBox.confirm(`${action}会放弃尚未保存的页面审核意见。`,'保护整包审核',{type:'warning',confirmButtonText:'放弃意见并继续',cancelButtonText:'继续编辑'});}catch{return false;}
  }
  if(checklistPanel.value?.isSaving()){ElMessage.warning('请等待入口职责保存结果。');return false;}
  if(checklistPanel.value?.hasUnsavedChanges()){
    try{await ElMessageBox.confirm(`${action}会放弃尚未保存的入口职责。`,'保护入口职责',{type:'warning',confirmButtonText:'放弃修改并继续',cancelButtonText:'继续编辑'});}catch{return false;}
  }
  if(publicBindingBusy.value||workbenchPublishBusy.value||homeBannerBusy.value||navigationLandingBusy.value||navigationLinksBusy.value||sharedContentBusy.value){ElMessage.warning('请等待当前模板或发布操作返回。');return false;}
  if(!preserveStructure && siteModelPanel.value?.hasUnsavedChanges()){
    try{await ElMessageBox.confirm(`${action}会放弃尚未保存的整站结构修正。`,'保护整站结构修正',{type:'warning',confirmButtonText:'放弃修正并继续',cancelButtonText:'留在此页'});}catch{return false;}
  }
  if(workbenchPublishPanel.value?.hasUnsavedChanges){
    try{await ElMessageBox.confirm(`${action}会放弃尚未保存的栏目路由修正。`,'保护栏目路由修正',{type:'warning',confirmButtonText:'放弃路由修正并继续',cancelButtonText:'留在此页'});}catch{return false;}
  }
  if(navigationLinksPanel.value?.hasUnsavedChanges){
    try{await ElMessageBox.confirm(`${action}会离开当前未确认的公共链接映射。`,'保护公共链接映射',{type:'warning',confirmButtonText:'继续',cancelButtonText:'留在此页'});}catch{return false;}
  }
  if(sharedContentPanel.value?.hasUnsavedChanges){
    try{await ElMessageBox.confirm(`${action}会离开当前未确认的公共信息映射。`,'保护公共信息映射',{type:'warning',confirmButtonText:'继续',cancelButtonText:'留在此页'});}catch{return false;}
  }
  if(publicBindingPanel.value?.hasUnsavedChanges){
    try{await ElMessageBox.confirm(`${action}可能丢失尚未应用的详情模板配置。`,'保护详情模板修改',{type:'warning',confirmButtonText:'继续',cancelButtonText:'留在此页'});}catch{return false;}
  }
  if(hasUnappliedReview()){
    try{await ElMessageBox.confirm(`审核窗口内仍有未应用的修正；${action}会丢弃这些修正，已应用的本机草稿不受影响。`,'保护未应用修正',{type:'warning',confirmButtonText:'放弃窗口内修正并继续',cancelButtonText:'留在此页'});}catch{return false;}
  }
  if(!hasUnsavedEdits.value)return true;stashLocalDraft();
  try{await ElMessageBox.confirm(`${localDraftStored.value?'当前修正已暂存在此浏览器，下次读取同页面后可恢复，但尚未保存到CMS。':'当前修正尚未可靠暂存；建议取消并先保存人工修正，继续离开可能丢失。'}是否${action}？`,'保护未保存修正',{type:'warning',confirmButtonText:localDraftStored.value?'保留本机修正并继续':'放弃未保存修正并继续',cancelButtonText:'留在此页'});return true;}catch{return false;}
}
async function confirmReleaseLeave(action:string){
  if(releasePanel.value?.isSaving()){ElMessage.warning('请等待签收回执保存结果。');return false;}
  if(!releasePanel.value?.hasUnsavedChanges())return true;
  try{
    await ElMessageBox.confirm(`${action}会放弃未保存的交互回执。`,'保护签收回执',
      {type:'warning',confirmButtonText:'放弃回执并继续',cancelButtonText:'继续编辑'});
    return true;
  }catch{return false;}
}
function hasUnappliedReview(){return !!(draftBindingPreview.value?.hasUnappliedChanges()||planBindingPreview.value?.hasUnappliedChanges());}
function protectRefresh(event:BeforeUnloadEvent){stashLocalDraft();rememberLocation();if(handoffPanel.value?.dirty||handoffPanel.value?.saving||hasUnsavedEdits.value||hasUnappliedReview()||packageAnalysisPanel.value?.hasUnsavedChanges()||checklistPanel.value?.hasUnsavedChanges()||siteModelPanel.value?.hasUnsavedChanges()||workbenchPublishPanel.value?.hasUnsavedChanges||navigationLinksPanel.value?.hasUnsavedChanges||sharedContentPanel.value?.hasUnsavedChanges||publicBindingPanel.value?.hasUnsavedChanges||releasePanel.value?.hasUnsavedChanges()||releasePanel.value?.isSaving()){event.preventDefault();event.returnValue='';}}
async function changeStep(step:number) {
  if(step===activeStep.value)return true;
  if(!workSteps.some(([value])=>value===step)||!await confirmLeaving('切换工作步骤',true)){
    // 原生单选已响应点击，从实际步骤重建选中状态。
    stepNavRevision.value++;
    return false;
  }
  activeStep.value=step;
  return true;
}
async function changeSourcePath(value:string){if(value===sourcePath.value)return;if(!await confirmLeaving('切换页面',true))return;stashLocalDraft();sourcePath.value=value;previewPath.value=value;}
async function openPackageAnalysis(){
  if(!await changeStep(7))return;
  await nextTick();
  await packageAnalysisPanel.value?.primary();
}
async function openPackagePageForReview(path:string){
  if(!await changeStep(7))return;
  await nextTick();
  await packageAnalysisPanel.value?.openPageForReview(path);
}
async function continueAppendedNavigation(plan:SiteModelPlan, navigation:SiteNavigation, nodeKey:string){
  const current=()=>plan.siteId===Number(siteContext.currentSite?.id) && plan.packageVersion===packageStatus.value?.version;
  const node=navigation.nodes.find(item=>item.key===nodeKey);
  if (!current() || !node?.menuId) return;
  checklistTarget.value=null;
  structureFocus.value={section:'navigation',nodeKey,menuId:node.menuId,sourcePath:node.sourcePath};
  await nextTick();
  if (!current()) return;
  const linked=await packageAnalysisPanel.value?.useStructure(plan);
  if (!current()) return;
  if (linked) {
    packageStructure.value={plan,navigation};
    if(navigation.nodes.some(item=>item.sourcePath && !seedPages.value.some(page=>page.sourcePath===item.sourcePath))){
      try {
        const updated=await http.get<SeedPage[]>(`/api/admin/v2/sites/${plan.siteId}/template-seed/pages`);
        if(!current())return;
        seedPages.value=updated;
      } catch {
        if(current()) ElMessage.warning('已追加栏目。页面清单暂时读取失败，可点击“刷新阶段状态”重读。');
        return;
      }
    }
    ElMessage.success('已追加「'+node.name+'」，可在此栏目继续审核字段和模板。');
  } else {
    ElMessage.warning('「'+node.name+'」已追加。请在整站结构点击“用此结构继续整包审核”，接续剩余的页面任务。');
  }
}
async function continueAppliedNavigation(plan:SiteModelPlan,navigation:SiteNavigation){
  const current=()=>plan.siteId===Number(siteContext.currentSite?.id) && plan.packageVersion===packageStatus.value?.version &&
    plan.id===siteModelPlan.value?.id && plan.revision===siteModelPlan.value.revision;
  if(!current())return;
  packageStructure.value={plan,navigation};
  publishRefreshKey.value++;
  const fieldTargetVersion=planVersion;
  await Promise.all([packageAnalysisPanel.value?.refresh(),refreshTargetAfterChange()]);
  if(!current())return;
  if(fieldTargetVersion===planVersion && !planChoicesDirty.value && !planLoading.value && !planApplying.value && !planHistoryLoading.value)
    void loadSavedPlans(planVersion);
  if(activeStep.value===5){
    if(structureFocus.value)structureFocus.value={...structureFocus.value};
    else siteModelPanel.value?.focusChecklistStep('navigation');
  }
  ElMessage.success(`已应用导航已接续至结构修订 ${plan.revision}，可继续审核当前栏目的字段和模板。`);
}
async function continueCorrectedNavigation(plan:SiteModelPlan,navigation:SiteNavigation,nodeKey:string,detailPath?:string){
  const current=()=>plan.siteId===Number(siteContext.currentSite?.id) && plan.packageVersion===packageStatus.value?.version &&
    plan.id===siteModelPlan.value?.id && plan.revision===siteModelPlan.value.revision;
  const node=navigation.nodes.find(item=>item.key===nodeKey);
  if(!current() || !node?.menuId || !node.sourcePath || node.kind!=='LIST')return;
  checklistTarget.value=null;
  publishRefreshKey.value++;
  await nextTick();
  if(!current())return;
  const panel=packageAnalysisPanel.value;
  const [linked]=await Promise.all([
    panel && !panel.hasUnsavedChanges() ? panel.useStructure(plan) : Promise.resolve(false),
    refreshTargetAfterChange(),
  ]);
  if(!current())return;
  structureFocus.value={section:'navigation',nodeKey,menuId:node.menuId,sourcePath:node.sourcePath,detailPath};
  if(linked)packageStructure.value={plan,navigation};
  else ElMessage.warning('栏目已修正为列表。请核对整包分析状态和待保存审核，再用此结构接续整包审核。');
  if(columnsRefreshError.value)ElMessage.warning('栏目已修正。请刷新阶段状态后再继续列表与查询。');
  else if(linked)ElMessage.success(`「${node.name}」已修正为列表。请继续审核此栏目的字段和列表。`);
}
async function usePackageStructure(plan:SiteModelPlan){
  if(packageStructureLinkBusy.value)return;
  const current=()=>plan.siteId===Number(siteContext.currentSite?.id) && plan.packageVersion===packageStatus.value?.version;
  packageStructureLinkError.value='';
  packageStructureLinkBusy.value=true;
  try {
    if(!await confirmLeaving('将当前结构用于整包审核',true) || !current())return;
    const panel=packageAnalysisPanel.value;
    const linked=await panel?.useStructure(plan);
    if(!current())return;
    if(linked){
      activeStep.value=7;
      ElMessage.success(`整包审核已接续至结构修订 ${plan.revision}。`);
    } else {
      packageStructureLinkError.value=panel?.structureLinkError || '未能接续整包审核，请重新读取整包状态。';
      ElMessage.error(packageStructureLinkError.value);
    }
  } finally {
    packageStructureLinkBusy.value=false;
  }
}
async function openPackageNext(){
  const application=packageApplicationScope.value;
  if(application){openWorkbenchApplication(application.plan,application.navigation);return;}
  if(packageAnalysisPanel.value?.nextAction?.kind!=='structure'){
    if(!await changeStep(7))return;
    await nextTick();
  }
  await packageAnalysisPanel.value?.followNext();
}
function openWorkbenchApplication(plan:SiteModelPlan,navigation:SiteNavigation){
  if(busy.value || applicationContext.value || plan.siteId!==Number(siteContext.currentSite?.id) || plan.packageVersion!==packageStatus.value?.version ||
    plan.status!=='CONFIRMED')return;
  if(siteModelPanel.value?.isWriting() || siteModelPanel.value?.hasUnsavedChanges() || checklistPanel.value?.isSaving() || checklistPanel.value?.hasUnsavedChanges()){
    ElMessage.warning('请先保存当前的结构、导航或入口职责修正，再核对应用范围。');return;
  }
  applicationTrigger=document.activeElement instanceof HTMLElement ? document.activeElement : null;
  applicationContext.value={plan,navigation};
}
async function closeWorkbenchApplication(restoreFocus=true){
  if(applicationBusy.value)return;
  applicationContext.value=null;
  await nextTick();
  if(restoreFocus && applicationTrigger?.isConnected && applicationTrigger.getClientRects().length)applicationTrigger.focus();
  applicationTrigger=null;
}
function receiveWorkbenchApplication(receipt:WorkbenchApplyResult){
  const context=applicationContext.value;
  if(!context || receipt.status!=='APPLIED')return;
  const base=context.plan;
  const current=()=>Number(siteContext.currentSite?.id)===base.siteId && packageStatus.value?.version===base.packageVersion;
  if(!current())return;
  invalidateReleaseReview();
  const captured:SiteNavigation={...context.navigation,status:'APPLIED',planRevision:receipt.planRevision,revision:receipt.navigationRevision,nodes:receipt.nodes};
  receiveModelNavigation(captured,base.id);
  applicationRefresh=(async()=>{
    const [fresh]=await Promise.allSettled([siteModelApi.get(base.siteId,base.id),refreshColumns(),packageAnalysisPanel.value?.refresh()]);
    if(!current())return;
    if(fresh.status!=='fulfilled' || fresh.value.siteId!==base.siteId || fresh.value.packageVersion!==base.packageVersion){
      ElMessage.warning('应用已完成，暂时无法更新结构详情。');return;
    }
    const plan=fresh.value;
    try {
      const navigation=await siteModelApi.navigation(base.siteId,plan);
      if(!current())return;
      if(packageAnalysisPanel.value?.structurePlanId===plan.id)packageStructure.value={plan,navigation};
      receiveModelNavigation(navigation,plan.id);
      await siteModelPanel.value?.reload(plan.id);
    } catch {if(current())ElMessage.warning('应用已完成，暂时无法更新导航详情。');}
  })();
}
async function openAppliedFields(node:SiteNavigationNode){
  const context=applicationContext.value;
  if(!context || !node.menuId || !node.sourcePath || node.kind==='DIRECTORY' || node.drift)return;
  await applicationRefresh;
  if(applicationContext.value!==context || Number(siteContext.currentSite?.id)!==context.plan.siteId || !await changeStep(7))return;
  await closeWorkbenchApplication(false);
  await nextTick();
  await packageAnalysisPanel.value?.openPageForReview(node.sourcePath);
}
watch([()=>siteContext.currentSite?.id,()=>packageStatus.value?.version],()=>{
  applicationContext.value=null;applicationTrigger=null;applicationRefresh=undefined;
});
async function openPackageStructure(planId:string|null){
  const siteId=Number(siteContext.currentSite?.id), version=packageStatus.value?.version;
  if(!await changeStep(5))return;
  await nextTick();
  if(!planId){siteModelPanel.value?.focusChecklistStep('model');return;}
  try {
    const plan=siteModelPlan.value?.id===planId ? siteModelPlan.value : packageStructure.value?.plan.id===planId
      ? packageStructure.value.plan : await siteModelApi.get(siteId,planId);
    if(plan.siteId!==siteId || plan.packageVersion!==version || Number(siteContext.currentSite?.id)!==siteId || packageStatus.value?.version!==version || activeStep.value!==5)return;
    const opened=await siteModelPanel.value?.openChecklistPlan(plan,plan.status==='CONFIRMED' ? 'navigation' : 'model');
    if(!opened)ElMessage.warning('请核对当前整站结构草稿和修订。');
  } catch {ElMessage.warning('暂时无法读取整包关联的结构计划。');}
}
async function openPackageSource(path:string,menuId:number|null){
  const siteId=siteContext.currentSite?.id, version=packageStatus.value?.version;
  if(busy.value || !await confirmLeaving('打开单页修正',true) || siteContext.currentSite?.id!==siteId || packageStatus.value?.version!==version)return false;
  stashLocalDraft();
  sourcePath.value=previewPath.value=path;
  planTargetMenuId.value=menuId ?? undefined;
  checklistTarget.value=null;
  activeStep.value=1;
  return true;
}
async function openRunResult(run:AgentRunReference,task:DomainAgentTask){
  if(busy.value || task.taskId!==run.taskId)return false;
  if(!['completed','failed','cancelled'].includes(task.state))return false;
  if(task.state!=='completed' || !task.text?.trim()){
    if(task.state==='completed')ElMessage.info('本次分析未返回审核结果，可在对应阶段查看已有方案。');
    if(run.kind==='site')return changeStep(5);
    if(!run.sourcePath)return false;
    await openModelPage(run.sourcePath);
    return activeStep.value===1 && sourcePath.value===run.sourcePath;
  }
  const originSite=siteContext.currentSite?.id;
  const parsed=JSON.parse(task.text);
  if(parsed.packageVersion!==packageStatus.value?.version){ElMessage.warning('结果属于其他页面包版本，请核对原版本。');return false;}
  if(run.kind==='site'){
    if(!parsed.id || parsed.siteId!==Number(siteContext.currentSite?.id))return false;
    const plan=await siteModelApi.get(parsed.siteId,parsed.id);
    if(siteContext.currentSite?.id!==originSite || plan.siteId!==parsed.siteId || plan.packageVersion!==packageStatus.value?.version)return false;
    if(plan.revision!==parsed.revision){ElMessage.warning('该方案已有新修订，请到整站结构核对当前版本。');return false;}
    if(!await changeStep(5))return false;
    await nextTick();
    if(plan.siteId!==Number(siteContext.currentSite?.id)||plan.packageVersion!==packageStatus.value?.version)return false;
    return !!await siteModelPanel.value?.openChecklistPlan(plan,plan.status==='CONFIRMED'?'navigation':'model');
  }
  const result=parsed as DomainAgentResult;
  if(run.sourcePath!==result.sourcePath || !await openPackageSource(result.sourcePath,result.menuId))return false;
  await nextTick(); await currentUnitsRead; await nextTick();
  if(siteContext.currentSite?.id!==originSite || packageStatus.value?.version!==result.packageVersion || sourcePath.value!==result.sourcePath)return false;
  const opened=domainAgentPanel.value?.restoreResult(task);
  if(!opened)ElMessage.warning('任务范围或草稿修订已变化，或当前页仍在运行；请核对当前审核面板。');
  return !!opened;
}
async function adoptPackagePage(result:DomainAgentResult){
  if(result.packageVersion!==packageStatus.value?.version || !await openPackageSource(result.sourcePath,result.menuId))return;
  await nextTick();
  await currentUnitsRead;
  if(await adoptDomainAgent(result))await openDraftReview();
}
async function openModelPage(path:string){if(path===sourcePath.value){await changeStep(1);return;}await changeSourcePath(path);if(sourcePath.value===path)activeStep.value=1;}
function openExistingMenuContent(menuId:number) {
  const menu=checklistColumns.value.find(item=>Number(item.id)===menuId);
  if (menu?.pageType===0) return router.push(`/admin/v2/cms/single/${menuId}`);
  return openModelContent(menuId);
}
function openModelContent(menuId: number, receipt?:{groupField:string;seedIds:number[]}) {
  const query={menuId:String(menuId),...(receipt ? {siteId:String(siteContext.currentSite?.id),groupField:receipt.groupField,
    groupValue:'',groupDisplay:'records',seedIds:receipt.seedIds.join(',')} : {})};
  return router.push({path:'/admin/v2/cms/content-management',query});
}
async function openModelFields(path: string, menuId: number) {
  await openWorkbenchTarget(path, menuId, 2);
}
async function resumeHandoff(action: {step:number; sourcePath?:string; menuId?:number}) {
  if (busy.value || !workSteps.some(([step]) => step === action.step)) return;
  if (action.menuId && action.sourcePath) await openWorkbenchTarget(action.sourcePath, action.menuId, action.step);
  else await changeStep(action.step);
}
type ChecklistTarget = { initialize?:boolean; fieldPlanId?:string; auditHref?:string; sourcePath:string|null;menuId:number|null;nodeId:string|null;action:string;label:string;reason:string };
const checklistTarget = ref<ChecklistTarget|null>(null);
let lastChecklistPreview:ChecklistTarget|null=null;
const structureFocus = ref<{nodeKey?:string|null;menuId?:number|null;sourcePath?:string|null;detailPath?:string;section:'navigation'|'list-seed'}|null>(null);
async function openChecklistSeeds(path:string, menuId:number){
  if(await changeStep(5)) structureFocus.value={section:'list-seed',sourcePath:path,menuId};
}
async function openChecklistPage(path:string,target:ChecklistTarget){
  await openModelPage(path);
  if(activeStep.value===1 && sourcePath.value===path) checklistTarget.value=target;
}
watch([()=>siteContext.currentSite?.id,()=>packageStatus.value?.version],()=>{
  checklistTarget.value=null;structureFocus.value=null;packageStructureLinkError.value='';
});
type ChecklistContext = {plan:SiteModelPlan; navigation:SiteNavigation};
async function restoreChecklistContext(context?:ChecklistContext) {
  if (!context) return true;
  const matchesScope = () => Number(siteContext.currentSite?.id)===context.plan.siteId &&
    packageStatus.value?.version===context.plan.packageVersion;
  if (!matchesScope()) return false;
  if (siteModelPlan.value?.id!==context.plan.id || siteModelPlan.value?.revision!==context.plan.revision)
    await siteModelPanel.value?.reload(context.plan.id);
  if (!matchesScope() || siteModelPlan.value?.id!==context.plan.id || siteModelPlan.value?.revision!==context.plan.revision) {
    ElMessage.warning('当前结构计划正在编辑或已变化，请在整站结构中核对后重试。');
    return false;
  }
  receiveModelNavigation(context.navigation, context.plan.id);
  return true;
}
async function reviewLandingEntry(node:SiteNavigationNode) {
  if (await changeStep(5)) structureFocus.value={section:'navigation',nodeKey:node.key,menuId:node.menuId,sourcePath:node.sourcePath};
}
async function openChecklistPrerequisite(context: ChecklistContext) {
  if(context.plan.status==='CONFIRMED' && (context.navigation.status==='CONFIRMED' || context.navigation.status==='APPLYING')){
    openWorkbenchApplication(context.plan,context.navigation);return;
  }
  if (!await changeStep(5)) return;
  checklistTarget.value=null;
  structureFocus.value=null;
  await nextTick();
  const opened=await siteModelPanel.value?.openChecklistPlan(context.plan,context.plan.status === 'DRAFT' || !context.navigation.nodes.length ? 'model' : 'navigation');
  if(!opened)ElMessage.warning('结构草稿正在读取或修订已变化，请核对当前整站结构。');
}
async function openChecklistStructure(context?:ChecklistContext,target?:ChecklistTarget) {
  if (await changeStep(5) && await restoreChecklistContext(context) && target) {
    checklistTarget.value=target;
    structureFocus.value={section:'navigation',nodeKey:target.nodeId,menuId:target.menuId,sourcePath:target.sourcePath};
  }
}
const linkReviewOrigin=ref<{path:string;menuId?:number;auditHref?:string;landingCard?:string;list?:boolean}>();
watch([()=>siteContext.currentSite?.id,()=>packageStatus.value?.version],()=>{linkReviewOrigin.value=undefined;});
async function openLinkPrerequisite(node:SiteNavigationNode,landingCard?:string){
  const origin={path:sourcePath.value,menuId:planTargetMenuId.value,auditHref:checklistTarget.value?.auditHref,landingCard};
  if(!node.menuId || !origin.path)return;
  if(!node.sourcePath || (landingCard && node.sourcePath===origin.path)){
    if(!await changeStep(5))return;
    structureFocus.value={section:'navigation',nodeKey:node.key,menuId:node.menuId,sourcePath:node.sourcePath};
  } else {
    await openWorkbenchTarget(node.sourcePath,node.menuId,landingCard ? 4 : node.kind==='LIST' ? 3 : 4);
    if(sourcePath.value!==node.sourcePath || planTargetMenuId.value!==node.menuId)return;
  }
  linkReviewOrigin.value=origin;
}
async function returnToLinkReview(){
  const origin=linkReviewOrigin.value;
  if(!origin)return;
  const step=origin.list ? 3 : 4;
  await openWorkbenchTarget(origin.path,origin.menuId,step,undefined,origin.list ? undefined : {sourcePath:origin.path,menuId:origin.menuId ?? null,nodeId:origin.landingCard ?? null,action:origin.landingCard ? 'navigation-landing' : 'navigation-links',label:origin.landingCard ? '栏目概览审核' : '公共导航链接审核',reason:'重新核对已准备的栏目',auditHref:origin.auditHref});
  if(activeStep.value===step && sourcePath.value===origin.path && planTargetMenuId.value===origin.menuId)linkReviewOrigin.value=undefined;
}
async function openSharedDetailReview(path:string,menuId:number,scope?:SharedDetailTemplateScope){
  const origin={path:sourcePath.value,menuId,list:true};
  if(!origin.path)return;
  const version=packageStatus.value?.version, siteId=Number(siteContext.currentSite?.id);
  await openWorkbenchTarget(path,menuId,2);
  if(activeStep.value===2 && sourcePath.value===path && planTargetMenuId.value===menuId){
    linkReviewOrigin.value=origin;
    sharedDetailEntry.value=scope && version && siteId ? {path,siteId,version,scope} : undefined;
  }
}
function canReviewStaticSource(path:string) {
  return !!(checklistPanel.value?.appliedStructure || navigationLinkScope.value) &&
    seedPages.value.some(page=>page.sourcePath===path);
}
async function reviewStaticSource(path:string,href:string) {
  const context=checklistPanel.value?.appliedStructure || navigationLinkScope.value;
  if(!context || !canReviewStaticSource(path))return;
  const menus=[...new Set(context.navigation.nodes.filter(node=>!node.drift && node.sourcePath===path && node.menuId &&
    checklistColumns.value.some(column=>Number(column.id)===node.menuId)).map(node=>node.menuId as number))];
  const menuId=menus.length===1 ? menus[0] : undefined;
  await openWorkbenchTarget(path,menuId,4,context,{
    sourcePath:path,menuId:menuId ?? null,nodeId:null,action:'navigation-links',label:'公共导航链接审核',reason:'核对静态产物里的原页链接',auditHref:href,
  });
}
// 签收接口只在原因字段中区分这些已进入发布核验的情况。
const releaseRegenerationReasons=new Set([
  '缺少正式产物，CMS 可能回退到原页面包',
  '缺少正式静态产物',
  '文件缺少可核对的发布回执，请重新发布',
  '产物由其他栏目或模板生成，可能是旧文件',
  '模板更新后尚未重新生成产物',
]);
function resolveReleaseAction(page: ReleasePage, scope: ReleaseScope): ReleaseAction | undefined {
  const panel=checklistPanel.value;
  if (!page.menuId || !panel) return;
  const context=panel.releaseContext(scope);
  if (context && page.slot==='NAVIGATION' && page.reason.startsWith('已审核保留文字，待重新生成并核对')) {
    return {label:'打开原生静态发布',run:()=>router.push({path:'/admin/v2/cms/static-publish',query:{siteId:String(context.plan.siteId)}})};
  }
  if (context && releaseRegenerationReasons.has(page.reason)) {
    const query={siteId:String(context.plan.siteId),menuId:String(page.menuId)};
    return {label:'重新生成该栏目产物',run:()=>router.push({path:'/admin/v2/cms/static-publish',query})};
  }
  if (context && page.reason==='请先发布 CMS 单页的实际维护内容') {
    const menuId=page.menuId;
    return {label:'核对栏目已发布内容',run:()=>openExistingMenuContent(menuId)};
  }
  if (context && page.reason==='从静态首页沿链接无法到达此页') {
    const root=context.navigation.nodes.find(node=>node.menuId===rootMenuId.value);
    if (root?.sourcePath && !root.drift && canReviewStaticSource(root.sourcePath)) {
      const path=root.sourcePath;
      return {label:'核对静态首页入口',run:()=>openWorkbenchTarget(path,root.menuId ?? undefined,4,context,{
        sourcePath:path,menuId:root.menuId,nodeId:root.key,action:'navigation-links',label:'核对静态首页入口',
        reason:page.reason,
      })};
    }
    return {label:'查看静态产物核验',run:focusReleaseCheck};
  }
  if (context && page.sourcePath && page.artifact && /公共导航|公共链接|地址无效，请重新绑定并发布/.test(page.reason)) {
    const path=page.sourcePath;
    const menuId=page.menuId;
    return {label:'审核原页公共链接',run:()=>openWorkbenchTarget(path,menuId,4,context,{
      sourcePath:path,menuId,nodeId:null,action:'navigation-links',label:'审核原页公共链接',reason:page.reason,
    })};
  }
  return panel.releaseAction(page,scope);
}
async function openWorkbenchTarget(path:string,menuId:number|undefined,step:number,context?:ChecklistContext,target?:ChecklistTarget) {
  const siteId=siteContext.currentSite?.id;
  if (busy.value || !await confirmLeaving('切换工作台目标', true) || siteContext.currentSite?.id!==siteId) return;
  if (!await restoreChecklistContext(context) || siteContext.currentSite?.id!==siteId) return;
  stashLocalDraft();
  const sameTarget=sourcePath.value===path && planTargetMenuId.value===menuId;
  if(target?.fieldPlanId && !sameTarget) wantedPlanId=target.fieldPlanId;
  sourcePath.value = path;
  previewPath.value = path;
  planTargetMenuId.value = menuId;
  activeStep.value = step;
  checklistTarget.value=target || null;
  if(target?.action==='navigation-links') {
    await nextTick();
    focusNavigationLinks();
  }
  if(target?.fieldPlanId && sameTarget && fieldPlan.value?.id!==target.fieldPlanId) {
    manualPlanId.value=target.fieldPlanId;
    await reloadPlan();
  }
}
async function reviewFormNavigation() {
  if (!navigationLinkScope.value || !await changeStep(4)) return;
  await nextTick();
  focusNavigationLinks();
}
function focusNavigationLinks() {
  const links=document.getElementById('workbench-navigation-links');
  links?.focus({preventScroll:true});
  links?.scrollIntoView({block:'start'});
}
function focusPublicBinding() {
  const panel=document.getElementById('workbench-public-binding');
  panel?.focus({preventScroll:true});
  panel?.scrollIntoView({block:'start'});
}
// 只在进入清单指定的私有草稿时读取一次预览。已有计划和源修订均须与当前上下文一致。
watch([checklistTarget,activeStep,busy,fieldPlan,planStale,canCheckTextBinding], async ()=>{
  const target=checklistTarget.value;
  if(activeStep.value!==2 || !target?.fieldPlanId || busy.value || planStale.value ||
    !canCheckTextBinding.value || fieldPlan.value?.id!==target.fieldPlanId ||
    target.menuId!==planTargetMenuId.value || target.sourcePath!==sourcePath.value || lastChecklistPreview===target) return;
  const version=textBindingVersion;
  lastChecklistPreview=target;
  if(overlaps.value.length){ await nextTick(); focusOverlapReview(); return; }
  if(target.initialize){
    await textBindingAction('inspect');
    if(checklistTarget.value===target && activeStep.value===2)document.getElementById('workbench-initial-content')?.focus();
    return;
  }
  await textBindingAction('preview');
  if(version===textBindingVersion && !textDraftHtml.value && checklistTarget.value===target && !textBindingError.value)
    textBindingError.value='未能读取私有草稿，请重新检查绑定状态。';
},{flush:'post'});
async function openNavigationNode(node:SiteNavigationNode) {
  if (!node.menuId || node.menuId!==rootMenuId.value && !columns.value.some(column => Number(column.id)===node.menuId)) return;
  const source=seedPages.value.find(page=>page.sourcePath===node.sourcePath)?.sourcePath || '';
  if(node.menuId===rootMenuId.value)return openWorkbenchTarget(source,node.menuId,4);
  if(node.kind==='DIRECTORY'||node.drift)return openWorkbenchTarget(source,node.menuId,1);
  if(isOverviewNode(node))return openWorkbenchTarget(source,node.menuId,4);
  if(source && usesPageFieldReview(appliedPackageNavigation.value,node,rootMenuId.value)){
    if(packageAnalysisPanel.value?.hasConfirmedTarget(source,node.menuId))
      return openModelFields(source,node.menuId);
    return openPackagePageForReview(source);
  }
  const paths=sourcesForMenu(node.menuId,['SINGLE','DETAIL'],node.modelKey);
  if (paths.length===1) return openModelFields(paths[0]!,node.menuId);
  return openWorkbenchTarget(source,node.menuId,node.kind==='LIST' && source ? 3 : 1);
}
async function openNavigationList(node:SiteNavigationNode,detailPath?:string){
  if(!node.menuId || !node.sourcePath || node.kind!=='LIST' || node.drift)return;
  if(busy.value){ElMessage.info('当前步骤正在读取状态，请稍后继续审核。');return;}
  const siteId=Number(siteContext.currentSite?.id),menuId=node.menuId,listPath=node.sourcePath;
  const packageVersion=packageStatus.value?.version,planId=reviewedNavigation.value?.plan.id;
  const current=()=>siteId===Number(siteContext.currentSite?.id) && packageVersion===packageStatus.value?.version &&
    planId===reviewedNavigation.value?.plan.id && activeStep.value===5;
  const hasListColumn=()=>columns.value.some(column=>Number(column.id)===menuId && column.pageType===1);
  if(!hasListColumn())await refreshColumns();
  if(!current())return;
  if(!hasListColumn()){
    ElMessage.warning('尚未读到该栏目的列表配置，请刷新导航与栏目状态后重试。');
    return;
  }
  const knownPaths=sourcesForMenu(menuId,['DETAIL'],node.modelKey);
  const detail=detailPath || (knownPaths.length===1 ? knownPaths[0] : undefined);
  if(detail){
    try {
      const recent=await recentFieldPlans(siteId,detail,menuId);
      if(!current())return;
      const confirmed=recent.plans.find(plan=>plan.status==='CONFIRMED' && plan.menuId===menuId && plan.sourcePath===detail);
      if(confirmed){
        await openWorkbenchTarget(listPath,menuId,3);
        if(activeStep.value===3 && sourcePath.value===listPath && planTargetMenuId.value===menuId)
          listDetailPlan.value={id:confirmed.id,sourcePath:detail,menuId};
        return;
      }
    } catch {
      if(current())ElMessage.warning('读取该栏目的详情字段计划失败，请稍后重试。');
      return;
    }
  }
  if(detail && knownPaths.includes(detail)){
    ElMessage.info('请确认该栏目的详情字段计划，再继续审核列表卡片和分页。');
    await openModelFields(detail,menuId);
    return;
  }
  ElMessage.info('请先核对详情原页的维护栏目与内容模型，再确认字段计划。');
  await openWorkbenchTarget(detail || listPath,menuId,1);
}
async function goToList() {
  const menuId=planTargetMenuId.value;
  if (completedDetail.value) return continueDetailList();
  if (targetListSource.value && menuId) return openWorkbenchTarget(targetListSource.value,menuId,3);
}
async function openAgentRelation(item:RecognitionSuggestion) {
  const menuId=planTargetMenuId.value;
  if (item.kind==='BODY_FRAGMENT_REVIEW') {
    if (item.path!==sourcePath.value || !await changeStep(1)) return;
    await nextTick();
    document.getElementById('workbench-fragment-evidence')?.focus();
    await asyncFragmentPanel.value?.inspect();
    return;
  }
  if (!menuId || ['SHARED_FRAGMENT','SHARED_NAVIGATION','NAV_EXPANSION_REVIEW','MAINTAINABLE_CARD_LIST'].includes(item.kind)) {
    if (await changeStep(5)) {
      structureFocus.value={section:'navigation',menuId,sourcePath:item.path};
    }
    return;
  }
  if (item.kind==='DETAIL_NEIGHBORS') {
    await openWorkbenchTarget(item.path,menuId,4);
    return;
  }
  await openWorkbenchTarget(item.path,menuId,3);
}
async function openTargetPublication() {
  const menuId=planTargetMenuId.value;
  if (menuId) return openWorkbenchTarget(targetBindingSource.value || sourcePath.value,menuId,4);
}
async function continueDetailList() {
  const plan = fieldPlan.value;
  if (!plan || busy.value || !completedDetail.value || !await confirmLeaving('继续配置此栏目列表', true)) return;
  if (fieldPlan.value?.id !== plan.id || !completedDetail.value) return;
  listDetailPlan.value = { id: plan.id, sourcePath: plan.sourcePath, menuId: plan.menuId };
  if (targetListSource.value) {
    stashLocalDraft();
    sourcePath.value=targetListSource.value;
    previewPath.value=targetListSource.value;
    activeStep.value=3;
    return;
  }
  activeStep.value = 3;
}
async function changePlanMenu(value:number){if(value===planTargetMenuId.value||!await confirmLeaving('切换目标栏目',true))return;stashLocalDraft();planTargetMenuId.value=value;}
async function refreshCurrent(){if(await confirmLeaving('重新读取阶段状态'))await refresh();}
async function clearUnrestoredDraft(){
  const scope=resumeScope.value, path=sourcePath.value, draft=unrestoredDraft.value;
  if(!scope || !path || !draft)return;
  try { await ElMessageBox.confirm('只清除此浏览器的旧临时修正，不更改服务端方案。','清除旧本机修正',{type:'warning'}); }
  catch {return;}
  if(resumeScope.value!==scope || sourcePath.value!==path || unrestoredDraft.value!==draft)return;
  try {localStorage.removeItem(draftKey(scope,path));unrestoredDraft.value=null;pendingLocalDraft=null;stashLocalDraft();}
  catch {resumeNotice.value='本机缓存无法清理，旧修正仍保留；服务端保存功能不受影响。';}
}
function readLocalPlanId(){try{return localStorage.getItem(planStorageKey());}catch{resumeNotice.value='本机计划引用无法读取，仍可从服务端列表或明确ID恢复。';return null;}}
function readTaskReference(key:string){try{return localStorage.getItem(key)||sessionStorage.getItem(key);}catch{resumeNotice.value='本机任务引用无法读取；服务端草稿仍可读取，任务中断后需人工核对。';return null;}}
function rememberTaskReference(key:string,id:string){try{localStorage.setItem(key,id);sessionStorage.setItem(key,id);}catch{resumeNotice.value='本机任务引用无法暂存；请在当前页查看完成结果，离开后需人工核对原任务。';}}
function removeTaskReference(key:string){try{localStorage.removeItem(key);sessionStorage.removeItem(key);}catch{resumeNotice.value='本机任务引用无法清理，未重新创建任务。';}}
async function restoreAnalysisReference(workspace:UnitsWorkspace,version:number){
  const scope=resumeScope.value;if(!scope)return;const key=analysisKey(scope,workspace.sourcePath,workspace.digest);const id=readTaskReference(key);if(!id)return;
  if(!/^[a-f0-9-]{36}$/i.test(id)){resumeNotice.value='本机任务引用无效，未发起新任务。';return;}
  activeAnalysisTask.value={id,siteId:Number(scope.site),key};
  try{const task=await pageAnalysisTasksApi.get(Number(scope.site),id);if(version!==unitsVersion)return;analysisProgress.value=taskProgress(task)+'。恢复仅查询状态；明确点击识别按钮才读取结果或继续查询，不会自动新建任务。';}
  catch{if(version===unitsVersion)analysisProgress.value='原任务已过期、服务重启或无法读取。引用保留，请明确清除后再决定是否重新识别；未创建新任务。';}
}
watch(()=>JSON.stringify(unitsWorkspace.value?.units),()=>{if(installingUnits||!unitsWorkspace.value||loadedUnitsContext?.path!==sourcePath.value)return;unitsDirty.value=JSON.stringify(unitsWorkspace.value.units)!==unitsBaseline;stashLocalDraft();},{flush:'sync'});
watch(()=>JSON.stringify(fieldChoices.value),()=>{if(fieldPlan.value?.status==='DRAFT'&&JSON.stringify(fieldChoices.value)!==choicesBaseline)planChoicesDirty.value=true;stashLocalDraft();},{flush:'sync'});
watch([unitsDirty,planChoicesDirty],stashLocalDraft,{flush:'sync'});
watch([sourcePath,planTargetMenuId,activeStep,savedPlanId],rememberLocation,{flush:'sync'});
watch(()=>[fieldPlan.value?.id,planStale.value,busy.value],async()=>{await nextTick();if(fieldPlan.value&&fieldPlan.value.status!=='DRAFT'&&!planStale.value&&!busy.value&&!recoveryState.value&&!textBindingError.value)void checkBindingRecovery();});

const stopSiteSwitchGuard=siteContext.$onAction(({name,args})=>{
  if(name!=='switchSite'||String(args[0])===String(siteContext.currentSite?.id))return;
  if(releasePanel.value?.isSaving()){const message='请等待签收回执保存结果后切换站点。';ElMessage.warning(message);throw new SiteSwitchBlockedError(message);}
  if(applicationBusy.value){const message='正在应用栏目与字段，请等待结果后切换站点。';ElMessage.warning(message);throw new Error(message);}
  if(publicBindingBusy.value||workbenchPublishBusy.value||homeBannerBusy.value||navigationLandingBusy.value||navigationLinksBusy.value||sharedContentBusy.value){const message='请等待当前模板或发布操作返回，再切换站点。';ElMessage.warning(message);throw new Error(message);}
  if(siteModelPanel.value?.isWriting()){const message='整站结构正在写入栏目或内容草稿，请等到结果返回后切换站点。';ElMessage.warning(message);throw new Error(message);}
  if(unitsSaving.value || planApplying.value || textBindingBusy.value || nativeFormBusy.value || importing.value || uploading.value || checklistPanel.value?.isSaving()){
    const message='当前站点的保存或应用仍在进行，请等待结果后切换。';ElMessage.warning(message);throw new Error(message);
  }
  if(approvedSiteSwitch){approvedSiteSwitch=false;return;}
  if(releasePanel.value?.hasUnsavedChanges() && !window.confirm('切换站点会放弃未保存的交互回执。继续切换站点吗？')){
    throw new SiteSwitchBlockedError('已留在原站点，签收回执仍可继续编辑。');
  }
  if(checklistPanel.value?.hasUnsavedChanges()){
    const message='待办中的入口职责尚未保存，请先保存或从待办编辑窗口确认放弃。';ElMessage.warning(message);throw new Error(message);
  }
  if(handoffPanel.value?.saving || handoffPanel.value?.dirty && !handoffPanel.value.cached){const message='请先保存本站约定或放弃本机草稿，再切换站点。';ElMessage.warning(message);throw new Error(message);}
  if(packageAnalysisPanel.value?.isSaving()||packageAnalysisPanel.value?.hasUnsavedChanges()){const message='请先保存整包审核意见，或在工作台的站点选择中确认离开。';ElMessage.warning(message);throw new Error(message);}
  if(publicBindingPanel.value?.hasUnsavedChanges){const message='请先应用详情模板修改，或在工作台的站点选择中确认离开。';ElMessage.warning(message);throw new Error(message);}
  if(workbenchPublishPanel.value?.hasUnsavedChanges||navigationLinksPanel.value?.hasUnsavedChanges||sharedContentPanel.value?.hasUnsavedChanges||hasUnappliedReview()){const message='当前栏目路由、公共信息、导航链接或审核窗口仍有修正，请先保存或在工作台确认放弃。';ElMessage.warning(message);throw new Error(message);}
  if(siteModelPanel.value?.hasUnsavedChanges()){const message='请先保存整站结构修正，或在工作台的站点选择中明确放弃后切换。';ElMessage.warning(message);throw new Error(message);}
  if(!hasUnsavedEdits.value)return;
  stashLocalDraft();
  if(!localDraftStored.value){resumeNotice.value='当前未保存修正无法可靠暂存，已阻止全局切站。请先保存人工修正，或在本页站点选择中明确放弃后切换。';ElMessage.warning(resumeNotice.value);throw new Error(resumeNotice.value);}
  if ((unitsDirty.value || planChoicesDirty.value || handoffPanel.value?.dirty) &&
      !window.confirm('当前修正仅暂存在本机，尚未保存到 CMS。要保留暂存并切换站点吗？')) {
    throw new Error('已留在原站点，当前修正仍可继续编辑。');
  }
});
watch(() => siteContext.currentSite?.id, (id,previous) => {
  if (id && previous && route.name==='site-build-workbench' && route.query.siteId===String(previous) && String(id)!==String(previous)) {
    void router.replace({path:route.path,query:{siteId:String(id)}});
  }
});
watch(() => [siteContext.currentSite?.id,siteContext.currentAdmin?.id], refresh, { immediate: true });
watch(sourcePath, () => {
  currentUnitsRead=loadUnits();
  ++seedVersion;
  seedLoading.value = false;
  seedPreview.value = null;
  seedError.value = '';
  seedFields.value = [];
  targetMenuId.value = undefined;
  mapping.value = {};
  importResult.value = '';
  analysisResult.value = null;
  analysisError.value = '';
});
</script>

<style scoped>
.site-build { padding: 0; }
.build-header, .build-card-title { display: flex; align-items: center; justify-content: space-between; gap: var(--admin-space-4); }
.build-package-next { display:flex; flex-wrap:wrap; align-items:center; justify-content:space-between; gap:8px 16px; margin-block:10px; padding:10px 12px; background:var(--el-color-primary-light-9); border-radius:4px; }
.build-package-next p { margin:3px 0 0; overflow-wrap:anywhere; }
.build-workspace-nav {display:flex;gap:12px;align-items:center;flex-wrap:wrap;margin-block:8px;}
.build-header h1, .build-header p { margin: 0; }
h1 { font-size: var(--admin-font-size-page); font-weight: 600; }
h3 { font-size: var(--admin-font-size-section); font-weight: 600; }
p { margin: var(--admin-space-3) 0; color: var(--admin-text-secondary); line-height: var(--admin-line-height); }
.site-build :deep(.el-card) { border: var(--admin-card-border); border-radius: var(--admin-card-radius); }
.site-build :deep(.el-card__body), .site-build :deep(.el-card__header) { padding: var(--admin-card-padding); }
.site-build :deep(.el-card__header) { color: var(--admin-text-primary); font-size: var(--admin-font-size-section); }
.site-build :deep(.el-card__header strong) { font-weight: 600; }
.build-controls { display: flex; flex-wrap: wrap; gap: var(--admin-space-2); margin-top: var(--admin-space-3); }
.build-controls .el-select { width: 320px; max-width: 100%; }
.build-unit-value, .build-source pre { white-space: pre-wrap; overflow-wrap: anywhere; font: inherit; }
.build-source { padding: var(--admin-card-padding); }
.build-analysis { margin: var(--admin-space-3) 0 0; padding: var(--admin-card-padding); border: var(--admin-card-border); border-radius: var(--admin-card-radius); background: var(--admin-bg-page); color: var(--admin-text-primary); font: inherit; white-space: pre-wrap; overflow-wrap: anywhere; max-height: 480px; overflow: auto; }
@media (max-width: 640px) { .build-header { align-items: flex-start; flex-direction: column; } }

.build-resume{position:relative;border-color:var(--el-color-primary-light-7)!important}.build-resume-scope{font-size:13px;margin:8px 0}.build-next{display:flex;flex-wrap:wrap;gap:12px;align-items:center}.build-next strong{color:var(--admin-text-primary)}.build-step-nav{display:flex;flex-wrap:wrap;margin-top:16px;gap:0}.build-step-nav :deep(.el-radio-button__inner){padding:12px 16px}.build-advanced{border:var(--admin-card-border);border-radius:var(--admin-card-radius);padding:16px;margin-top:20px}.site-build details{margin:12px 0}.site-build summary{cursor:pointer;color:var(--admin-text-secondary);font-size:14px;line-height:1.8}.build-advanced>summary{font-weight:600;color:var(--admin-text-primary)}
.build-unit-value { max-height: 140px; overflow: auto; margin: 0; font-size: 12px; }
.site-build :deep(.el-table__cell) { vertical-align: top; }
.review-continuation{display:flex;align-items:center;justify-content:space-between;gap:12px;flex-wrap:wrap;margin:12px 0;padding:12px 16px;border:1px solid var(--el-border-color);border-radius:6px}.review-continuation p{margin:4px 0 0}
@media (max-width: 768px) {
  .site-build { padding-bottom: calc(64px + env(safe-area-inset-bottom)); }
  .build-controls :deep(.el-button), .build-resume > :deep(.el-card__body > details > summary) {
    min-height: 44px; white-space: normal; height: auto; align-content: center;
  }
}
</style>
