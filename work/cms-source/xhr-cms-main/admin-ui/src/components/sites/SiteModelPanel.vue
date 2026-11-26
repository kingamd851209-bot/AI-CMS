<template>
  <el-card v-if="displayPanel !== false" shadow="never" v-loading="loading">
    <template #header><div class="model-heading"><strong>整站内容结构</strong><el-tag :type="plan?.status === 'CONFIRMED' ? 'success' : 'info'">{{ plan?.status === 'CONFIRMED' ? '结构计划已审核' : plan ? '结构待审核' : '尚未读取结构计划' }}</el-tag></div></template>
    <section v-if="focusTarget?.section === 'list-seed'" class="model-next" aria-label="清单目标列表初值">
      <h3 ref="focusedSeedHeading" tabindex="-1">清单目标 · 列表初值</h3>
      <p>{{ focusTarget.sourcePath || '缺少原页' }}<template v-if="focusTarget.menuId"> · {{ menuLabel(focusTarget.menuId) }} · #{{ focusTarget.menuId }}</template></p>
      <p role="status">{{ focusedSeedNext }}</p>
      <div v-if="focusedSeedTargets.length" class="model-toolbar">
        <el-button v-for="target in focusedSeedTargets" :key="target.key" type="primary" plain :disabled="locked || activeTask"
          @click="focusRecordSeed(target.key)">{{ focusedSeedTargets.length === 1 ? '前往此列表的初值审核' : target.label }}</el-button>
      </div>
      <template v-else-if="focusedRegionModel && focusTarget.sourcePath">
        <p v-if="loadingEvidence.has(focusTarget.sourcePath)" role="status">正在读取原页证据。</p>
        <RecordSeedRegionReview v-else-if="focusedRegionPage" :key="`${plan?.id}:${plan?.revision}:${focusTarget.sourcePath}:${focusedRegionModel.key}`"
          :site-id="siteId" :page="focusedRegionPage" :model="focusedRegionModel" :used-region-ids="focusedUsedRegionIds"
          :disabled="locked || activeTask" @review="reviewFocusedRegion" />
        <el-button v-else plain :disabled="locked || activeTask" @click="inspectPage(focusTarget.sourcePath)">读取原页证据</el-button>
        <el-button plain :disabled="locked" @click="emit('open-package-page', focusTarget.sourcePath)">查看原页</el-button>
      </template>
      <el-button v-else-if="focusTarget.menuId && columns.some(column => Number(column.id) === focusTarget?.menuId)" plain :disabled="locked"
        @click="emit('manage-content', focusTarget.menuId)">打开此栏目的内容管理</el-button>
    </section>
    <el-alert v-else-if="focusTarget && !plan && !loading" title="当前包尚无可定位此入口的结构计划，可先读取或启动整站结构分析。" type="info" :closable="false" />
    <SiteNavigationPanel v-if="plan" ref="navigationPanel" :key="navigationScope" :site-id="siteId" :plan="plan" :menu-label="menuLabel" :root-menu-id="rootMenuId"
      :focus-target="focusTarget?.section === 'list-seed' ? null : focusTarget"
      :disabled="modelLocked || activeTask || dirty || editingConfirmed" @state="navigationState = $event" @busy="navigationBusy = $event"
      @navigation="receiveNavigation" @continue-node="emit('continue-node', $event)" @continue-list="(node,path)=>emit('continue-list',node,path)"
      @model-draft="openNavigationModelDraft"
      @navigation-appended="installAppendedNavigation"
      @navigation-kind-corrected="(next,nav,key,path)=>installAppendedNavigation(next,nav,key,true,path)"
      @navigation-continued="installContinuedNavigation"
      @reload-plan="reload()"
      @open-package-page="emit('open-package-page',$event)"
      @open-page="emit('open-page', $event)" @manage-content="emit('manage-content', $event)"
      @application="(plan,navigation)=>emit('application',plan,navigation)" @manage-columns="emit('manage-columns')" />
    <section ref="modelStep" tabindex="-1" class="model-next" aria-label="整站结构下一步">
      <ol class="model-stages" aria-label="建站流程">
        <li v-for="(stage, index) in stages" :key="stage" :aria-current="index === currentStage ? 'step' : undefined">{{ stage }}</li>
      </ol>
      <strong>下一步 · {{ nextStep.title }}</strong>
      <p>{{ nextStep.description }}</p>
      <div class="model-toolbar">
        <el-button v-if="packageRunning && nextStep.action === 'start'" type="primary" @click="emit('show-package-run')">查看整包分析进度</el-button>
        <el-button v-else-if="nextStep.action === 'start'" type="primary" :disabled="locked || dirty" :loading="starting" @click="start(false)">{{ retryStart ? '重试同一任务' : '启动整站结构分析' }}</el-button>
        <el-button v-else-if="nextStep.action === 'recover'" type="primary" :disabled="locked || dirty" :loading="recovering" @click="recoverDraft">恢复已校验草案</el-button>
        <el-button v-else-if="nextStep.action === 'resume'" type="primary" :disabled="locked || cancelling" @click="resume">继续查询任务</el-button>
        <el-button v-else-if="nextStep.action === 'confirm'" type="primary" :disabled="readOnly || !draft?.models.length || (editingConfirmed && !dirty)" :loading="saving" @click="save(true)">确认结构计划</el-button>
        <el-button v-else-if="nextStep.action === 'read'" type="primary" :disabled="locked" @click="reload(plan?.id)">读取服务端结果</el-button>
        <template v-else-if="nextStep.action === 'apply' || (plan?.status === 'CONFIRMED' && !editingConfirmed && !dirty && navigationState === 'APPLIED' && pendingModels.length && !applyNeedsRead)">
          <label>本次应用的内容类型 <el-select v-model="applyModelKey" aria-label="本次应用的内容类型" filterable :disabled="locked" placeholder="选择内容类型">
            <el-option v-for="model in pendingModels" :key="model.key" :value="model.key" :label="model.name" />
          </el-select></label>
          <label>上级栏目 <el-select v-model="applyParentId" aria-label="上级栏目" clearable filterable :disabled="locked" placeholder="本站根栏目">
            <el-option v-for="menu in columns.filter(item => item.id)" :key="menu.id" :value="Number(menu.id)" :label="menu.className || menu.name || String(menu.id)" />
          </el-select></label>
          <el-button type="primary" :disabled="locked || !!applyBlocker" :loading="applying" @click="applyStructure()">创建栏目与字段</el-button>
          <span v-if="applyBlocker" role="status">{{ applyBlocker }}</span>
        </template>
        <el-button v-else-if="nextStep.action === 'owned'" type="primary" :disabled="locked" @click="openOwnedStructure">查看归属条目栏目</el-button>
        <el-button v-else-if="nextStep.action === 'seed'" type="primary" :disabled="locked" @click="openRecordSeed">查看重复区域初值</el-button>
        <el-button v-else-if="nextStep.action === 'fields'" type="primary" :disabled="locked" @click="emit('open-fields', nextBindingPath!, nextContentModel!.menuId!)">继续字段与初值</el-button>
        <el-button v-else-if="nextStep.action === 'columns'" type="primary" :disabled="locked" @click="emit('manage-columns')">打开栏目管理</el-button>
      </div>
    </section>
    <div v-if="navigationDraftId || plan?.status==='CONFIRMED' && packageAnalysisPlanId" class="model-toolbar" aria-label="整包审核关联的结构">
      <el-button v-if="navigationDraftId && plan?.id!==navigationDraftId" size="small" :disabled="locked" @click="reload(navigationDraftId)">继续上次页面模型草稿</el-button>
      <template v-if="plan && (plan.status==='CONFIRMED' || plan.id===navigationDraftId) && (packageAnalysisPlanId!==plan.id || packageAnalysisPlanRevision!==plan.revision)">
        <el-button size="small" plain type="primary" :loading="packageLinking" :disabled="locked" @click="emit('use-package-plan',plan)">用此结构继续整包审核</el-button>
        <span>整包审核改用当前结构，已保存的页面建议保留。</span>
      </template>
    </div>
    <el-alert v-if="structureResult" class="model-result" :title="structureResult.title" type="success" :closable="false" show-icon>
      <p>{{ structureResult.description }}</p>
      <el-button v-if="nextBindingPath && nextContentModel?.menuId === structureResult.menuId" type="primary" plain size="small" :disabled="locked" @click="emit('open-fields', nextBindingPath, structureResult.menuId)">继续字段与初值</el-button>
    </el-alert>
    <el-alert v-if="structureError" class="model-result" title="栏目应用未确认" :description="structureError" type="error" :closable="false" show-icon />
    <div class="model-toolbar">
      <el-button v-if="!packageRunning && (plan || canRecoverDraft) && nextStep.action !== 'start'" :disabled="locked || activeTask || dirty" :loading="starting" @click="start(false)">{{ retryStart ? '重试同一任务' : '重新分析整站' }}</el-button>
      <el-button v-if="!packageRunning && plan?.status === 'DRAFT' && !retryStart" :disabled="locked || activeTask || dirty" @click="start(true)">继续完善此草案</el-button>
      <el-button v-if="nextStep.action !== 'read'" :disabled="locked || activeTask" @click="reload()">读取最新结构</el-button>
      <el-button v-if="confirmedPlan && confirmedPlan.id !== plan?.id" :disabled="locked || activeTask" @click="reload(confirmedPlan.id)">返回已确认计划</el-button>
      <el-button v-if="nextStep.action !== 'resume' && taskId && !polling && (activeTask || !task || taskUnavailable)" :disabled="cancelling || locked" @click="resume">继续查询任务</el-button>
      <el-button v-if="activeTask" :loading="cancelling" @click="cancel">取消任务</el-button>
      <el-button v-if="nextStep.action !== 'recover' && canRecoverDraft" :disabled="locked || dirty" :loading="recovering" @click="recoverDraft">恢复已校验草案</el-button>
      <span v-if="taskUnavailable" role="status">原分析任务已失联</span>
      <span v-else-if="task" role="status">{{ taskLabels[task.state] || '等待检查任务状态' }}{{ progress ? ` · ${progress}` : '' }}</span>
    </div>
    <section v-if="task" class="model-agent-progress" aria-label="整站 Agent 最近步骤">
      <p v-if="taskProgress.observedValues != null">已观察 {{ taskProgress.observedValues }} 项字段值<template v-if="taskProgress.checkpointAvailable">，已有校验草案可供中断后恢复。</template></p>
      <details v-if="taskProgress.steps.length" open>
        <summary>最近步骤（{{ taskProgress.steps.length }}）</summary>
        <ol>
          <li v-for="(step,index) in taskProgress.steps" :key="`${step.round}-${step.tool}-${step.at || index}`">
            <time v-if="step.at" :datetime="step.at">{{ stepTime(step.at) }}</time>
            第 {{ step.round }} 轮 · {{ progressToolLabel(step.tool) }} · {{ progressStatusLabel(step.status) }}
            <p>{{ step.message }}</p>
          </li>
        </ol>
      </details>
      <p v-else-if="activeTask" role="status">{{ taskProgress.message || '等待 Agent 返回下一步进度。' }}</p>
    </section>
    <p v-if="plan?.status === 'DRAFT'">继续分析会生成独立草案，原计划仍可按 ID 读取。要修改原计划，请保存结构修正。</p>
    <details><summary>按计划 ID 读取</summary><div class="model-toolbar">
      <label>已保存计划 ID <el-input v-model="lookupId" maxlength="36" :disabled="locked || activeTask" placeholder="输入计划 ID" aria-label="已保存计划 ID" /></label>
      <el-button :disabled="locked || activeTask || !lookupId.trim()" @click="reload(lookupId.trim())">按 ID 读取</el-button>
    </div><p>最新计划可能只覆盖专项栏目。可按 ID 打开已有整站计划，审核对应区域后继续公开应用。</p></details>
    <el-alert v-if="rememberedPlanNotice" :title="rememberedPlanNotice" type="warning" :closable="false" show-icon />
    <p v-if="confirmedPlan && confirmedPlan.id !== plan?.id">当前用于生成字段计划的是已确认方案 {{ confirmedPlan.id }}，修订 {{ confirmedPlan.revision }}。</p>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <el-empty v-if="!draft && !loading && !activeTask" description="尚无当前页面包的结构计划" />
    <template v-if="plan && draft">
      <p>包 {{ plan.packageVersion }} · 计划 {{ plan.id }} · 修订 {{ plan.revision }} · 已观察 {{ plan.observedPages }} / {{ plan.totalPages }} 个页面或片段。覆盖数仅表示取证范围。</p>
      <p v-if="draft.summary">{{ draft.summary }}</p>
      <el-alert v-if="interruptionNotice" :title="interruptionNotice" type="warning" :closable="false" />
      <el-alert v-if="dirty" title="请先保存整站结构修正，再继续分析或恢复已校验草案。" type="warning" :closable="false" />
      <p v-if="editingConfirmed">保存或重新确认会产生新修订。依赖旧修订生成的字段计划需要重新核对。</p>
      <template v-if="plan.status === 'CONFIRMED' && !editingConfirmed">
        <h3>{{ navigationState === 'empty' ? 'CMS 栏目与字段' : '内容模型与已有数据栏目' }}</h3>
        <p v-if="navigationState !== 'empty'">代表栏目用于维护模型字段。适用栏目可分别维护各自的内容。</p>
        <el-table :data="plan.proposal.models" size="small" empty-text="计划中没有内容类型">
          <el-table-column prop="name" label="内容类型" min-width="140" />
          <el-table-column label="代表栏目" min-width="160"><template #default="{ row }">
            <span v-if="row.menuId">{{ menuLabel(row.menuId) }} · #{{ row.menuId }}</span>
            <span v-else>{{ modelStructureBlocker(row) || '待创建或对应' }}</span>
          </template></el-table-column>
          <el-table-column label="适用栏目" min-width="180"><template #default="{ row }">
            <div v-for="id in modelMenuIds(row)" :key="id">
              {{ menuLabel(id) }} · #{{ id }}
              <el-button v-if="modelBindingPath(row)" link :disabled="locked" @click="emit('open-fields', modelBindingPath(row)!, id)">字段与初值</el-button>
              <el-button v-if="row.kind==='LIST' && row.menuId && row.menuId!==id && row.fields.some((field: SiteModelField)=>field.fieldName?.startsWith('ext_'))"
                link :disabled="locked || dirty" @click="syncSharedFields(row,id)">核对共享字段</el-button>
            </div>
            <span v-if="!modelMenuIds(row).length">待对应</span>
          </template></el-table-column>
          <el-table-column label="字段对应" min-width="130"><template #default="{ row }">
            {{ row.fields.filter((field: SiteModelField) => field.fieldName).length }} / {{ row.fields.length }}
          </template></el-table-column>
          <el-table-column label="操作" min-width="280"><template #default="{ row }">
            <el-button v-if="row.menuId" link type="primary" :disabled="locked" @click="emit('manage-content', row.menuId)">内容管理</el-button>
            <el-button v-if="row.menuId" link :disabled="locked" @click="emit('manage-columns')">栏目管理</el-button>
            <el-button v-if="row.menuId && canSupplementFields(row)" link type="primary" :disabled="locked" @click="supplementFields(row)">增补待分配字段</el-button>
          </template></el-table-column>
        </el-table>
        <section v-if="referenceRelations.length" aria-labelledby="reference-scope-heading">
          <h3 id="reference-scope-heading">引用来源栏目</h3>
          <div class="model-toolbar">
            <label>引用字段 <el-select v-model="referenceRelationIndex" aria-label="核对范围的引用字段" :disabled="locked">
              <el-option v-for="(relation, index) in referenceRelations" :key="index" :value="index"
                :label="`${modelLabel(relation.sourceModelKey)} · ${fieldLabel(relation.sourceModelKey, relation.fieldKey)}`" />
            </el-select></label>
            <label>所属栏目 <el-select v-model="referenceMenuId" aria-label="引用字段所属栏目" :disabled="locked">
              <el-option v-for="id in referenceOwnerMenus" :key="id" :value="id" :label="`${menuLabel(id)} · #${id}`" />
            </el-select></label>
            <el-button size="small" :loading="referenceReading" :disabled="locked || !referenceMenuId" @click="readReferenceScope">读取引用范围</el-button>
          </div>
          <p v-if="!referenceOwnerMenus.length">请先应用所属内容栏目。</p>
          <el-alert v-if="referenceError" :title="referenceError" type="error" :closable="false" />
          <template v-if="referenceScope">
            <p>当前来源栏目 · {{ referenceMenuLabels(referenceScope.currentMenuIds) || '待配置' }}</p>
            <p>审核允许的来源栏目 · {{ referenceMenuLabels(referenceScope.targetMenuIds) || '待对应' }}</p>
            <p v-if="!referenceScope.fieldName">请先增补引用字段。</p>
            <el-button v-else-if="referenceScopeChanged" size="small" type="primary" :loading="referenceApplying" :disabled="locked"
              @click="applyReferenceScope">更新本栏引用范围</el-button>
            <p v-else role="status">当前来源已覆盖审核范围。</p>
            <template v-if="referenceScope.fieldName">
              <p>尚未公开应用的栏目可更新范围。已有公开应用记录的栏目暂不支持凭据迁移，请勿仅撤回旧模板后直接更新。</p>
              <p>更新后，请在本栏「字段与初值」重新生成并确认字段计划，保留当前内容完成详情绑定。</p>
            </template>
          </template>
        </section>
        <section v-if="ownedModels.length" ref="ownedStructureHeading" aria-labelledby="owned-structure-heading">
          <h3 id="owned-structure-heading" tabindex="-1">归属条目栏目</h3>
          <p>每个所属栏目独立建立子列表。内容初值在下方选择所属记录后导入。</p>
          <el-alert v-if="ownedInstancesError" :title="ownedInstancesError" type="error" :closable="false" />
          <el-button v-if="ownedInstancesError" size="small" :disabled="locked" @click="readOwnedInstances">重新读取归属条目栏目</el-button>
          <el-table v-loading="ownedInstancesLoading" :data="ownedInstanceRows" size="small" empty-text="请先应用所属内容栏目">
            <el-table-column label="内容类型" prop="model.name" min-width="140" />
            <el-table-column label="所属栏目" min-width="150"><template #default="{ row }">{{ menuLabel(row.parentMenuId) }} · #{{ row.parentMenuId }}</template></el-table-column>
            <el-table-column label="子列表" min-width="160"><template #default="{ row }">
              <span v-if="row.menuId">已建立 · {{ menuLabel(row.menuId) }} · #{{ row.menuId }}</span>
              <span v-else>待建立</span>
            </template></el-table-column>
            <el-table-column label="操作" min-width="150"><template #default="{ row }">
              <el-button v-if="row.menuId" link type="primary" :disabled="locked" @click="emit('manage-content', row.menuId)">内容管理</el-button>
              <el-button v-else link type="primary" :disabled="locked || activeTask || applyNeedsRead || ownedInstancesLoading || !!modelStructureBlocker(row.model)" @click="applyStructure(row.model, row.parentMenuId)">建立子列表</el-button>
            </template></el-table-column>
          </el-table>
        </section>
        <section v-if="seedTarget" aria-labelledby="record-seed-heading">
          <h3 id="record-seed-heading" ref="recordSeedHeading" tabindex="-1">重复区域内容初值</h3>
          <div class="model-toolbar">
            <label>来源区域 <el-select :model-value="seedTarget.key" aria-label="重复记录来源区域" filterable :disabled="locked || activeTask" @update:model-value="selectSeedRegion">
              <el-option v-for="target in visibleSeedTargets" :key="target.key" :value="target.key" :label="target.label" />
            </el-select></label>
            <span v-if="seedTarget.menuId">目标栏目 {{ menuLabel(seedTarget.menuId) }} · #{{ seedTarget.menuId }}</span>
            <el-button size="small" :loading="seedLoading" :disabled="locked || activeTask || !seedTarget.menuId || (seedTarget.region.maintenance?.mode === 'OWNED' && !ownerRecord)" @click="readRecordSeed">读取预览和导入结果</el-button>
          </div>
          <div v-if="seedTarget.region.maintenance?.mode === 'OWNED'" class="model-toolbar">
            <label>所属栏目 <el-select v-model="seedParentMenuId" aria-label="初值所属栏目" placeholder="请选择实际栏目" filterable clearable
              :value-on-clear="null" :disabled="locked || activeTask || ownedInstancesLoading" :loading="ownedInstancesLoading">
              <el-option v-for="item in seedInstances" :key="item.parentMenuId" :value="item.parentMenuId" :disabled="!item.menuId"
                :label="`${menuLabel(item.parentMenuId)} · #${item.parentMenuId}${item.menuId ? '' : ' · 子列表待建立'}`" />
            </el-select></label>
            <span>所属记录 {{ ownerRecord ? (ownerRecord.title || '未命名') + ' · #' + ownerRecord.recordId : '请从所属栏目选择实际记录' }}</span>
            <el-button size="small" :disabled="locked || activeTask || !seedTarget.ownerMenuId" @click="openOwnerPicker">选择所属记录</el-button>
          </div>
          <el-dialog v-model="ownerPickerOpen" title="选择所属记录" width="min(640px, 90vw)">
            <div class="model-toolbar">
              <el-input v-model="ownerKeyword" placeholder="按标题搜索" aria-label="所属记录标题" @keyup.enter="readOwnerRecords(1)" />
              <el-button :loading="ownerLoading" @click="readOwnerRecords(1)">搜索</el-button>
            </div>
            <el-alert v-if="ownerError" :title="ownerError" type="error" :closable="false" />
            <el-table v-loading="ownerLoading" :data="ownerRows" size="small" empty-text="没有可选择的所属记录">
              <el-table-column prop="title" label="标题" />
              <el-table-column prop="recordId" label="ID" width="90" />
              <el-table-column label="选择" width="90"><template #default="{ row }"><el-button link type="primary" :disabled="locked || ownerLoading" @click="chooseOwnerRecord(row)">选择</el-button></template></el-table-column>
            </el-table>
            <el-pagination :current-page="ownerPage" :page-size="20" :total="ownerTotal" layout="prev, pager, next" @current-change="readOwnerRecords" />
          </el-dialog>
          <el-alert v-if="seedError" :title="seedError" class="model-result" type="error" :closable="false" show-icon />
          <template v-if="seedPreview">
            <div class="model-toolbar" aria-live="polite">
              <strong>{{ seedPreview.records.length }} 条预览记录 · {{ importedRecords }} 条已导入</strong>
              <el-tag v-if="!seedPreview.ready" type="warning">需处理导入问题</el-tag>
              <el-tag v-else-if="pendingRecords" type="info">{{ pendingRecords }} 条待导入</el-tag>
              <el-tag v-else-if="importedRecords" type="success">初值已导入</el-tag>
            </div>
            <p v-if="seedMissingFields.length" role="status">本区域未提供「{{ seedMissingFields.join('、') }}」的初值。导入后可在每条草稿中补充。</p>
            <ul v-if="seedPreview.issues.length"><li v-for="(issue, index) in seedPreview.issues" :key="index">{{ issue }}</li></ul>
            <el-table :data="seedPreview.records" size="small" empty-text="当前区域没有可预览的初值记录" max-height="480">
              <el-table-column label="来源记录" width="112"><template #default="{ row, $index }"><span :title="row.sourceRecordId">第 {{ $index + 1 }} 条</span></template></el-table-column>
              <el-table-column label="字段和页面来源" min-width="330"><template #default="{ row }">
                <dl v-if="row.fields.length" class="model-seed-fields">
                  <template v-for="field in row.fields" :key="field.fieldName">
                    <dt>{{ field.label }} · {{ field.fieldName }}</dt>
                    <dd v-if="typeof field.value==='string' && field.value.startsWith('ui:/') && seedImageFields.has(field.fieldName)">
                      <RecordSeedPhoto :site-id="siteId" :source-path="seedPreview.sourcePath" :raw="field.value" :label="field.label" />
                    </dd>
                    <dd class="model-value">来源原值 {{ field.rawValue ?? '—' }}</dd>
                    <dd v-if="field.value !== field.rawValue" class="model-value">导入初值 {{ field.value ?? '—' }}</dd>
                  </template>
                </dl>
                <span v-else>无可预览字段</span>
              </template></el-table-column>
              <el-table-column label="导入结果" min-width="150"><template #default="{ row }">
                <span v-if="row.contentId != null">{{ row.status === 'CREATED' ? '已创建草稿' : '已有导入记录' }} #{{ row.contentId }}</span>
                <span v-else>{{ seedRecordStatuses[row.status as string] }}</span>
                <ul v-if="row.issues.length"><li v-for="(issue, index) in row.issues" :key="index">{{ issue }}</li></ul>
              </template></el-table-column>
            </el-table>
            <p>仅为当前区域新增内容草稿，保留已有记录。模板配置和公开预览需要后续操作。</p>
            <div class="model-toolbar">
              <el-button v-if="seedPreview.ready && pendingRecords" type="primary" :disabled="locked || activeTask" :loading="seedImporting" @click="importRecordSeed">导入 {{ pendingRecords }} 条为草稿</el-button>
              <template v-if="importedRecords && unfilledGroupFields.length">
                <span v-if="unfilledGroupFields.length===1">分组字段：{{ unfilledGroupFields[0]?.name }}</span>
                <label v-else>分组字段
                  <el-select v-model="selectedSeedGroup" aria-label="本批草稿分组字段" placeholder="选择分组字段" :disabled="locked">
                    <el-option v-for="field in unfilledGroupFields" :key="field.fieldName!" :value="field.fieldName!" :label="field.name" />
                  </el-select>
                </label>
                <el-button type="primary" plain :disabled="locked || !receiptGroupField" @click="openSeedGrouping">
                  查看本批未分组草稿
                </el-button>
              </template>
              <el-button v-if="importedRecords" :disabled="locked" @click="emit('manage-content', seedPreview.menuId)">进入本栏目内容管理</el-button>
            </div>
            <p v-if="importedRecords && ownerRecord">可在所属内容栏目中，通过对应记录行的子栏目入口维护条目。</p>
          </template>
        </section>
      </template>
      <el-collapse v-model="expandedStructureModels">
        <el-collapse-item v-for="model in draft.models" :key="model.key" :name="model.key" :title="`${model.name} · ${modelKinds[model.kind] || model.kind} · ${regionsByModel.get(model.key)?.length || 0} 处使用`">
          <div class="model-toolbar">
            <label>内容类型名称 <el-input v-model="model.name" :disabled="readOnly" :aria-label="`内容类型名称：${model.key}`" /></label>
            <label>对应 CMS 栏目 <el-select v-model="model.menuId" :disabled="readOnly" :aria-label="`${model.name}对应栏目`" placeholder="暂未对应" clearable filterable :value-on-clear="null" @change="clearSlots(model)">
              <el-option v-for="menu in modelMenus(model.kind)" :key="menu.id" :value="Number(menu.id)" :label="`${menu.className || menu.name}（${menu.pageType === 0 ? '单页' : '列表'}）`" />
            </el-select></label>
          </div>
          <el-table :data="model.fields" size="small" empty-text="尚无已识别的共用字段">
            <el-table-column label="字段业务名" min-width="180"><template #default="{ row }"><el-input v-model="row.name" :disabled="readOnly" :aria-label="`${model.name}字段${row.key}业务名`" /></template></el-table-column>
            <el-table-column label="字段类型" min-width="140"><template #default="{ row }"><span v-if="referenceRegion(draft?.regions, model.key, row.key)">原生内容多选</span><el-select v-else v-model="row.type" :disabled="readOnly" :aria-label="`${row.name}的类型`" @change="row.fieldName = row.type === 'Sort' ? 'sort' : null"><el-option v-for="(label, type) in fieldTypes" :key="type" :value="type" :label="label" /></el-select></template></el-table-column>
            <el-table-column label="现有 CMS 字段槽位" min-width="230"><template #default="{ row }">
              <span v-if="row.type === 'Sort'">原生排序（sort）</span>
              <span v-else-if="referenceRegion(draft?.regions, model.key, row.key)">{{ row.fieldName || '审核后从增补待分配字段配置' }}</span>
              <el-select v-else v-model="row.fieldName" :disabled="readOnly || !model.menuId" :aria-label="`${row.name}对应字段`" clearable filterable :value-on-clear="null" placeholder="待核对对应字段" :loading="loadingFields.has(model.menuId || 0)" @visible-change="(visible: boolean) => { if (visible) void loadFields(model.menuId); }">
                <el-option v-if="row.fieldName && !fieldOptions(model.menuId, row.type).some(field => field.fieldName === row.fieldName)" :value="row.fieldName" :label="row.fieldName" disabled />
                <el-option v-for="field in fieldOptions(model.menuId, row.type)" :key="field.fieldName" :value="field.fieldName" :label="`${field.title || field.fromName}（${field.fieldName}）`" />
              </el-select>
            </template></el-table-column>
          </el-table>
          <p v-if="model.menuId && model.fields.some(field => !field.fieldName && field.type !== 'Sort')">待核对的字段可以随计划保存。可在栏目管理补充兼容字段，再回此处选择。</p>
          <div v-for="region in regionsByModel.get(model.key) || []" :key="regionKey(region)" class="model-region">
            <div class="model-toolbar"><strong>{{ region.sourcePath }}</strong><el-tag type="info">{{ regionModes[region.mode] || region.mode }}</el-tag><span>{{ region.records.length }} 条候选记录</span></div>
            <template v-if="region.mode === 'LIST' && (region.maintenance || maintenanceOwners(region, 'OWNED').length || maintenanceOwners(region, 'PAGE_LIST').length)">
              <div class="model-toolbar">
                <label>维护方式 <el-select :model-value="region.maintenance?.mode ?? ''" aria-label="维护方式" :disabled="readOnly" @update:model-value="setMaintenanceMode(region, $event)">
                  <el-option label="独立记录" value="" />
                  <el-option v-if="maintenanceOwners(region, 'OWNED').length" label="随所属记录维护" value="OWNED" />
                  <el-option v-if="maintenanceOwners(region, 'OWNED').length" label="引用已有内容" value="REFERENCE" />
                  <el-option v-if="maintenanceOwners(region, 'PAGE_LIST').length" label="单页重复区域" value="PAGE_LIST" />
                </el-select></label>
                <template v-if="region.maintenance">
                  <label>所属内容 <el-select v-model="region.maintenance.ownerModelKey" aria-label="所属内容类型" :disabled="readOnly" @change="region.maintenance.fieldKey = null">
                    <el-option v-for="owner in maintenanceOwners(region)" :key="owner.key" :label="owner.name" :value="owner.key" />
                  </el-select></label>
                  <label v-if="region.maintenance.mode === 'REFERENCE'">引用字段 <el-select v-model="region.maintenance.fieldKey" aria-label="引用字段" :disabled="readOnly" @visible-change="(visible: boolean) => { if (visible) void loadFields(maintenanceOwner(region)?.menuId ?? null); }">
                    <el-option v-if="region.maintenance.fieldKey && !maintenanceFields(region).some(field => field.key === region.maintenance?.fieldKey)" :value="region.maintenance.fieldKey" :label="fieldLabel(region.maintenance.ownerModelKey, region.maintenance.fieldKey)" disabled />
                    <el-option v-for="field in maintenanceFields(region)" :key="field.key" :label="field.name" :value="field.key" />
                  </el-select></label>
                </template>
              </div>
              <label v-if="region.maintenance">判断依据 <el-input v-model="region.maintenance.reason" :disabled="readOnly" maxlength="500" aria-label="维护方式判断依据" /></label>
              <p v-if="region.maintenance?.mode === 'PAGE_LIST'">使用单页下独立的列表子栏目。条目可分别维护，按原生排序展示。没有已发布条目时保留区块标题，隐藏列表。</p>
              <template v-if="region.maintenance?.mode === 'REFERENCE'">
                <p>在所属记录的原生引用字段选择已有内容并调整顺序。原页面候选卡片仅供取证。</p>
                <el-button v-if="!readOnly && (!maintenanceRelation(region) || maintenanceRelation(region)?.reason !== region.maintenance.reason.trim())"
                  size="small" :disabled="!canSaveMaintenanceRelation(region)" @click="saveMaintenanceRelation(region)">
                  {{ maintenanceRelation(region) ? '同步内容关联依据' : '建立此内容关联' }}
                </el-button>
              </template>
              <p v-if="maintenanceIssue(region)" role="status">{{ maintenanceIssue(region) }}</p>
            </template>
            <div class="model-toolbar">
              <el-button size="small" :loading="loadingEvidence.has(region.sourcePath)" @click="openEvidence(region)">{{ openRegions.has(regionKey(region)) ? '收起记录与证据' : '查看初始记录与来源' }}</el-button>
              <el-button v-if="pages.includes(region.sourcePath)" size="small" :disabled="disabled" @click="emit('open-page', region.sourcePath)">打开来源页面修正</el-button>
            </div>
            <template v-if="openRegions.has(regionKey(region))">
              <p v-if="sourceRegion(region)" class="model-value">原页面区域：{{ sourceRegion(region)?.selector }}</p>
              <p v-for="(warning, index) in sourceRegion(region)?.warnings || []" :key="index">{{ warning }}</p>
              <el-table :data="region.records" size="small" empty-text="没有纳入计划的候选记录" max-height="440">
                <el-table-column label="来源记录" width="120"><template #default="{ row, $index }"><span :title="row.recordId">第 {{ sourceRecord(region, row)?.order ?? ($index + 1) }} 条</span></template></el-table-column>
                <el-table-column v-for="field in model.fields" :key="field.key" :label="field.name" min-width="170">
                  <template #default="{ row, $index }">
                    <span v-if="field.type === 'Sort'">{{ sourceRecord(region, row)?.order != null ? `第 ${sourceRecord(region, row)?.order} 位` : '等待页面顺序证据' }}</span>
                    <el-select v-else-if="field.type === 'Image' && !readOnly && imageSources(region, row).length"
                      :model-value="row.bindings[field.key]" :aria-label="`${region.sourcePath} 第 ${$index + 1} 条${field.name}来源`"
                      placeholder="选择此记录原图" clearable @change="setImageSource(region, row, field.key, $event)">
                      <el-option v-for="image in imageSources(region, row)" :key="image.id" :value="image.id"
                        :label="`${image.rawValue} · ${image.selector}`" />
                    </el-select>
                    <span v-else class="model-value" :title="sourceValue(region, row, field.key)?.selector">{{ sourceValue(region, row, field.key)?.rawValue || (row.bindings[field.key] ? '未读取到来源原值' : '未提取') }}</span>
                  </template>
                </el-table-column>
                <el-table-column v-if="sourceRegion(region)?.records.some(record => record.warnings.length)" label="来源提示" min-width="190"><template #default="{ row }">{{ sourceRecord(region, row)?.warnings.join('；') }}</template></el-table-column>
              </el-table>
              <p v-if="model.fields.some(field => field.type === 'Sort')">排序列展示原始页面顺序，初始化时转换为 CMS 排序。</p>
            </template>
          </div>
        </el-collapse-item>
      </el-collapse>
      <details><summary>页面与片段用途（{{ plan.pages.length }}）</summary><el-table :data="plan.pages" size="small" max-height="420"><el-table-column prop="sourcePath" label="页面或片段" min-width="180" /><el-table-column prop="purpose" label="用途" min-width="140" /><el-table-column label="状态" min-width="220"><template #default="{ row }">{{ pageStates[row.status] || '待核对' }}</template></el-table-column></el-table></details>
      <details v-if="draft.relations.length" open><summary>内容关联（{{ draft.relations.length }}）</summary>
        <p>普通跳转链接保存在字段里，内容关联用于引用 CMS 中的内容记录。移除误判的关联会保留原字段和绑定，保存结构修正后生效。</p>
        <ul>
          <li v-for="(relation, index) in draft.relations" :key="`${relation.sourceModelKey}:${relation.fieldKey}`">
            {{ modelLabel(relation.sourceModelKey) }} · {{ fieldLabel(relation.sourceModelKey, relation.fieldKey) }} → {{ relation.targetModelKey ? modelLabel(relation.targetModelKey) : '目标待核对' }}：{{ relation.reason }}
            <el-button link type="danger" size="small" :disabled="readOnly"
              :aria-label="`移除 ${modelLabel(relation.sourceModelKey)} 的 ${fieldLabel(relation.sourceModelKey, relation.fieldKey)} 内容关联`"
              @click="removeRelation(index)">移除关联</el-button>
          </li>
        </ul>
      </details>
      <details v-if="draft.issues.length">
        <summary>识别时的待核对记录（{{ draft.issues.length }}）</summary>
        <p v-if="plan.status === 'CONFIRMED' && !editingConfirmed">当前栏目创建状态以「CMS 栏目与字段」表为准。</p>
        <ul><li v-for="(issue, index) in draft.issues" :key="index">{{ issue }}</li></ul>
      </details>
      <div class="model-toolbar">
        <el-button v-if="plan.status === 'CONFIRMED' && !editingConfirmed" :disabled="locked || activeTask" @click="editingConfirmed = true">修订已确认结构</el-button>
        <el-button v-else :disabled="readOnly || !dirty" :loading="saving" @click="save(false)">保存结构修正</el-button>
      </div>
    </template>
    <details @toggle="($event.target as HTMLDetailsElement).open && loadCatalog()">
      <summary>整站取证警告与公共片段候选</summary>
      <p v-if="catalogLoading">正在读取页面包取证清单。</p>
      <template v-if="catalog">
        <ul v-if="catalog.warnings.length"><li v-for="(warning, index) in catalog.warnings" :key="index">{{ warning }}</li></ul>
        <details v-if="catalog.pages.some(page => page.warnings.length)"><summary>查看页面级取证警告</summary>
          <ul><template v-for="page in catalog.pages" :key="page.sourcePath"><li v-for="(warning, index) in page.warnings" :key="index">{{ page.sourcePath }} · {{ warning }}</li></template></ul>
        </details>
        <details v-for="fragment in catalog.sharedFragments" :key="`${fragment.kind}:${fragment.digest}`"><summary>{{ fragment.kind }} · {{ fragment.occurrences.length }} 处公共片段候选</summary>
          <ul><li v-for="(occurrence, index) in fragment.occurrences" :key="index" class="model-value">{{ occurrence.sourcePath }} · {{ occurrence.selector }}</li></ul>
        </details>
        <div class="model-toolbar"><label>核对页面中的区域 <el-select v-model="inspectionPath" placeholder="选择页面或片段" filterable aria-label="核对页面中的区域" @change="inspectPage">
          <el-option v-for="page in catalog.pages" :key="page.sourcePath" :label="page.sourcePath" :value="page.sourcePath" />
        </el-select></label><el-button v-if="inspectionPath" :loading="loadingEvidence.has(inspectionPath)" @click="inspectPage(inspectionPath)">读取所选页面证据</el-button></div>
        <template v-if="inspectionPage">
          <ul v-if="inspectionPage.warnings.length"><li v-for="(warning, index) in inspectionPage.warnings" :key="index">{{ warning }}</li></ul>
          <p v-if="inspectionPage.includedPaths.length">包内引入片段 · {{ inspectionPage.includedPaths.join('、') }}</p>
          <ul v-if="inspectionPage.fragmentLinks.length"><li v-for="(link, index) in inspectionPage.fragmentLinks" :key="index">片段引用待核对 · {{ link.targetPath || link.rawValue }}</li></ul>
          <p>请核对下列未纳入区域是否需要维护。整页原文候选可用于定位来源。</p>
          <el-table :data="unboundRegions" size="small" empty-text="本次取证未发现其他区域" max-height="360">
            <el-table-column label="区域" min-width="170"><template #default="{ row }">{{ evidenceRegionKinds[row.kind] || row.kind }}</template></el-table-column>
            <el-table-column prop="selector" label="原文位置" min-width="170" />
            <el-table-column label="候选及警告" min-width="180"><template #default="{ row }">{{ row.records.length }} 条候选记录<span v-if="row.warnings.length"> · {{ row.warnings.join('；') }}</span></template></el-table-column>
          </el-table>
        </template>
      </template>
    </details>
  </el-card>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, toRaw, watch } from 'vue';
import { ElMessageBox } from 'element-plus';
import { AdminHttpError } from '@/api/http';
import { siteModelApi, type SiteContentModel, type SiteModelCatalog, type SiteModelField, type SiteModelOwnedInstance, type SiteModelPlan, type SiteModelProposal, type SiteModelRecord, type SiteModelRecordSeed, type SiteModelReferenceScope, type SiteModelRegion, type SiteModelSupplementPreview, type SiteNavigation, type SiteNavigationNode, type SitePageEvidence } from '@/api/siteModel';
import { parseDomainAgentProgress, type DomainAgentTask } from '@/api/domainAgent';
import { getMenuFieldConfig, type MenuNode, type FieldOption } from '@/api/uiPackage';
import { listNativeContent, type NativeContentRow } from '@/api/cms';
import SiteNavigationPanel from './SiteNavigationPanel.vue';
import RecordSeedPhoto from './RecordSeedPhoto.vue';
import RecordSeedRegionReview from './RecordSeedRegionReview.vue';

const props = defineProps<{ packageRunning?:boolean; packageLinking?:boolean; displayPanel?:boolean; siteId: number; adminId: string; packageVersion: string; packageAnalysisPlanId?:string|null;packageAnalysisPlanRevision?:number|null; rootMenuId?: number; columns: MenuNode[]; columnsLoaded: boolean; pages: string[]; disabled?: boolean;
  focusTarget?: { nodeKey?: string | null; menuId?: number | null; sourcePath?: string | null; detailPath?:string; section?: 'navigation' | 'list-seed' } | null }>();
const emit = defineEmits<{ 'navigation-kind-corrected':[plan:SiteModelPlan,navigation:SiteNavigation,nodeKey:string,detailPath:string|undefined]; 'continue-list':[SiteNavigationNode,string|undefined]; 'navigation-appended':[plan:SiteModelPlan,navigation:SiteNavigation,nodeKey:string]; 'navigation-continued':[plan:SiteModelPlan,navigation:SiteNavigation]; 'show-package-run': []; plan: [SiteModelPlan | null]; 'agent-task': [DomainAgentTask]; 'open-page': [string]; 'open-package-page':[string]; 'use-package-plan':[SiteModelPlan]; 'structure-applied': []; 'manage-columns': []; 'manage-content': [number, {groupField:string;seedIds:number[]}?]; 'open-fields': [string, number]; 'continue-node': [SiteNavigationNode]; navigation: [SiteNavigation | null, string | undefined]; application:[plan:SiteModelPlan,navigation:SiteNavigation] }>();
const modelKinds: Record<string, string> = { SINGLE: '单记录', LIST: '列表', SHARED: '公共数据' };
const regionModes: Record<string, string> = { SINGLE: '单记录', LIST: '重复列表', DETAIL: '详情', QUERY: '查询复用', SHARED: '公共区域' };
const seedRecordStatuses: Record<string, string> = { PENDING: '待导入', BLOCKED: '待修正', REMOVED: '已导入后移除' };
const fieldTypes = { SingleLine: '单行文字／链接', MultiLine: '多行文字', Editor: '富文本', Image: '图片', Video: '视频', File: '文件', DateTime: '日期时间', Sort: '原生排序' };
const pageStates: Record<string, string> = { MODELED: '已有区域建议（不代表整页完成）', REVIEW_REQUIRED: '待核对', NOT_OBSERVED: '未观察' };
const evidenceRegionKinds: Record<string, string> = { BANNER: 'Banner 轮播', REPEATED: '重复区域', PAGE_SOURCE: '整页原文候选（按需核对）' };
const taskLabels: Record<string, string> = { pending: '等待执行', running: '正在分析整站', completed: '结构计划已保存', failed: '任务失败', cancelled: '任务已取消' };
const plan = ref<SiteModelPlan | null>(null), draft = ref<SiteModelProposal | null>(null), task = ref<DomainAgentTask | null>(null);
const confirmedPlan = ref<SiteModelPlan | null>(null), lookupId = ref('');
const rememberedPlanNotice = ref('');
const loading = ref(false), starting = ref(false), saving = ref(false), cancelling = ref(false), polling = ref(false), error = ref(''), requestId = ref('');
const taskId = ref(''), taskUnavailable = ref(false), recovering = ref(false);
const applying = ref(false), applyModelKey = ref(''), applyParentId = ref<number>();
const structureError = ref(''), applyNeedsRead = ref(false);
const structureResult = ref<{ title: string; description: string; modelKey: string; menuId: number } | null>(null);
const navigation = ref<SiteNavigation | null>(null);
const navigationPanel = ref<InstanceType<typeof SiteNavigationPanel> | null>(null);
const referenceRelationIndex = ref(0), referenceMenuId = ref<number>();
const referenceScope = ref<SiteModelReferenceScope | null>(null);
const referenceReading = ref(false), referenceApplying = ref(false), referenceError = ref('');
const ownedInstances = ref(new Map<string, SiteModelOwnedInstance[]>());
const ownedInstancesError = ref(''), ownedInstancesLoading = ref(false);
const ownedStructureHeading = ref<HTMLElement>();
const seedParentMenuId = ref<number | null>(null);
const recordSeedHeading = ref<HTMLElement>();
const seedRegionKey = ref(''), seedError = ref('');
const seedPreview = ref<SiteModelRecordSeed | null>(null);
const seedLoading = ref(false), seedImporting = ref(false);
const ownerRecord = ref<NativeContentRow | null>(null);
const ownerPickerOpen = ref(false), ownerKeyword = ref(''), ownerError = ref('');
const ownerRows = ref<NativeContentRow[]>([]), ownerPage = ref(1), ownerTotal = ref(0), ownerLoading = ref(false);
let startBasePlan: { id: string; revision: number } | undefined;
const editingConfirmed = ref(false);
const expandedStructureModels=ref<string[]>([]);
const navigationDraftId=ref('');
const navigationDraftStorageKey=()=>JSON.stringify(['workbench-navigation-model',props.adminId,props.siteId,props.packageVersion]);
try {navigationDraftId.value=localStorage.getItem(navigationDraftStorageKey()) || '';} catch {/* 草稿可通过结构计划 ID 恢复。 */}
const openRegions = ref(new Set<string>()), evidence = ref(new Map<string, SitePageEvidence>()), loadingEvidence = ref(new Set<string>());
const catalog = ref<SiteModelCatalog | null>(null), catalogLoading = ref(false), inspectionPath = ref('');
const inspectionPage = computed(() => evidence.value.get(inspectionPath.value));
const unboundRegions = computed(() => {
  const used = new Set(draft.value?.regions.filter(region => region.sourcePath === inspectionPath.value).map(region => region.regionId));
  return inspectionPage.value?.regions.filter(region => !used.has(region.id)) || [];
});
const menuFields = ref(new Map<number, FieldOption[]>()), loadingFields = ref(new Set<number>());
const activeTask = computed(() => !taskUnavailable.value && (task.value?.state === 'pending' || task.value?.state === 'running'));
const canRecoverDraft = computed(() => !!taskId.value && !activeTask.value && !polling.value && (taskUnavailable.value || task.value?.state === 'failed' || task.value?.state === 'cancelled'));
const retryStart = computed(() => !!requestId.value && !activeTask.value && !starting.value);
const navigationScope = computed(() => `${props.siteId}:${plan.value?.id}:${props.packageVersion}`);
const navigationState = ref<'loading' | 'unavailable' | 'empty' | 'DRAFT' | 'CONFIRMED' | 'APPLYING' | 'APPLIED'>('loading');
const navigationBusy = ref(false);
watch(navigationScope, () => { navigationState.value = 'loading'; });
const modelLocked = computed(() => props.disabled || loading.value || starting.value || saving.value || recovering.value || applying.value || referenceApplying.value || seedImporting.value || (polling.value && !activeTask.value));
const locked = computed(() => modelLocked.value || navigationBusy.value);
const readOnly = computed(() => locked.value || activeTask.value || (plan.value?.status === 'CONFIRMED' && !editingConfirmed.value));
const dirty = computed(() => JSON.stringify(plan.value?.proposal) !== JSON.stringify(draft.value) && draft.value !== null);
const interruptionNotice = computed(() => plan.value?.proposal.issues.find(issue => /分析(?:任务)?中断/.test(issue)) || '');
const stages = ['识别草案', '审核结构', '创建栏目与字段', '内容初值与模板', '预览验证'];
const ownedModels = computed(() => plan.value?.proposal.models.filter(model =>
  plan.value?.proposal.regions.some(region => region.maintenance?.mode === 'OWNED' && region.modelKey === model.key)) || []);
const ownedInstanceRows = computed(() => ownedModels.value.flatMap(model =>
  (ownedInstances.value.get(model.key) || []).map(instance => ({ ...instance, model }))));
const pendingModels = computed(() => plan.value?.proposal.models.filter(model => !model.menuId && !ownedModels.value.includes(model) &&
  (navigationState.value !== 'APPLIED' || plan.value?.proposal.regions.some(region => region.maintenance && region.modelKey === model.key))) || []);
let instanceReadVersion = 0;
function receiveNavigation(result: SiteNavigation | null) {
  navigation.value = result;
  emit('navigation', result, plan.value?.id);
}
function installAppendedNavigation(next:SiteModelPlan,nav:SiteNavigation,nodeKey:string,kindCorrected=false,detailPath?:string){
  assertScope(next);
  install(next);
  receiveNavigation(nav);
  if(kindCorrected)emit('navigation-kind-corrected',next,nav,nodeKey,detailPath);
  else {
    emit('structure-applied');
    emit('navigation-appended',next,nav,nodeKey);
  }
}
function installContinuedNavigation(next:SiteModelPlan,nav:SiteNavigation){
  assertScope(next);
  receiveNavigation(nav);
  emit('navigation-continued',next,nav);
}
async function readOwnedInstances() {
  const version = ++instanceReadVersion, current = plan.value;
  ownedInstances.value = new Map();
  ownedInstancesError.value = '';
  ownedInstancesLoading.value = current?.status === 'CONFIRMED' && ownedModels.value.length > 0;
  if (!current || !ownedInstancesLoading.value) return;
  try {
    const entries = await Promise.all(ownedModels.value.map(async model =>
      [model.key, await siteModelApi.ownedInstances(props.siteId, current, model.key)] as const));
    if (version !== instanceReadVersion || plan.value !== current) return;
    ownedInstances.value = new Map(entries);
  } catch (cause) {
    if (version === instanceReadVersion) ownedInstancesError.value = cause instanceof Error ? cause.message : '读取归属条目栏目失败。';
  } finally {
    if (version === instanceReadVersion) ownedInstancesLoading.value = false;
  }
}
watch([plan, navigation], readOwnedInstances);
function canSupplementFields(model: SiteContentModel) {
  return model.fields.some(field => {
    if (field.fieldName) return false;
    const targetKey = referenceTargetKey(model.key, field.key);
    return !targetKey || !!plan.value?.proposal.models.some(item => item.key === targetKey && item.menuId);
  });
}
function referenceRegion(regions: SiteModelRegion[] | undefined, ownerKey: string, fieldKey: string) {
  return regions?.find(region => region.maintenance?.mode === 'REFERENCE' &&
    region.maintenance.ownerModelKey === ownerKey && region.maintenance.fieldKey === fieldKey);
}
function referenceTargetKey(ownerKey:string, fieldKey:string) {
  const proposal=plan.value?.proposal;
  const region=referenceRegion(proposal?.regions,ownerKey,fieldKey);
  if (region) return region.modelKey;
  const relation=proposal?.relations.find(item=>item.sourceModelKey===ownerKey && item.fieldKey===fieldKey);
  return proposal?.models.find(target=>target.key===relation?.targetModelKey && target.kind==='LIST')?.key;
}
function modelStructureBlocker(model: SiteContentModel) {
  return plan.value?.proposal.relations.some(relation => relation.sourceModelKey === model.key &&
    !referenceTargetKey(model.key, relation.fieldKey))
    ? '含待处理的内容关联'
    : '';
}
const selectedModel = computed(() => pendingModels.value.find(model => model.key === applyModelKey.value));
watch(pendingModels, models => {
  if (!models.some(model => model.key === applyModelKey.value)) {
    applyModelKey.value = (models.find(model => !modelStructureBlocker(model)) || models[0])?.key || '';
    applyParentId.value = undefined;
  }
}, { immediate: true });
const applyBlocker = computed(() => {
  if (!selectedModel.value) return '请选择要创建的内容类型。';
  if (modelStructureBlocker(selectedModel.value)) return '请先核对该类型的内容关联。误判的关联可在修订计划时移除，真实关联需在 CMS 中配置。';
  return '';
});
const menuLabel = (id: number) => {
  const menu = props.columns.find(item => Number(item.id) === id);
  return menu?.className || menu?.name || (props.columnsLoaded ? '计划对应栏目' : '栏目');
};
function modelMenuIds(model: SiteContentModel) {
  const nodes = navigation.value?.status === 'APPLIED' ? navigation.value.nodes : [];
  return [...new Set([
    model.menuId,
    ...nodes.filter(node => !node.drift && node.kind !== 'DIRECTORY' && node.modelKey === model.key).map(node => node.menuId),
    ...(ownedInstances.value.get(model.key) || []).map(instance => instance.menuId),
  ].filter((id): id is number => !!id))];
}
const referenceRelations = computed(() => {
  const proposal = plan.value?.proposal;
  return proposal?.relations.filter(relation =>
    referenceTargetKey(relation.sourceModelKey,relation.fieldKey)) || [];
});
const selectedReferenceRelation = computed(() => referenceRelations.value[referenceRelationIndex.value]);
const referenceOwnerMenus = computed(() => {
  const owner = plan.value?.proposal.models.find(model => model.key === selectedReferenceRelation.value?.sourceModelKey);
  return owner ? modelMenuIds(owner) : [];
});
const referenceScopeChanged = computed(() => {
  const scope = referenceScope.value;
  return scope && scope.targetMenuIds.some(id => !scope.currentMenuIds.includes(id));
});
let referenceReadVersion = 0;
watch(referenceRelations, relations => {
  if (!relations[referenceRelationIndex.value]) referenceRelationIndex.value = 0;
});
watch(referenceOwnerMenus, (menus) => {
  if (!menus.includes(referenceMenuId.value ?? 0)) referenceMenuId.value = menus[0];
});
watch([plan, navigation, selectedReferenceRelation, referenceMenuId, editingConfirmed], () => {
  ++referenceReadVersion;
  referenceScope.value = null;
  referenceReading.value = false;
  referenceError.value = '';
}, { flush: 'sync' });
function referenceMenuLabels(ids: number[]) {
  return ids.map(id => `${menuLabel(id)} #${id}`).join('、');
}
async function readReferenceScope() {
  const current = plan.value, relation = selectedReferenceRelation.value, menuId = referenceMenuId.value;
  if (current?.status !== 'CONFIRMED' || !relation || !menuId) return;
  const version = ++referenceReadVersion;
  referenceScope.value = null;
  referenceError.value = '';
  referenceReading.value = true;
  try {
    const result = await siteModelApi.referenceScope(props.siteId, current, relation.sourceModelKey, relation.fieldKey, menuId);
    if (version === referenceReadVersion) referenceScope.value = result;
  } catch (cause) {
    if (version === referenceReadVersion) referenceError.value = cause instanceof Error ? cause.message : '读取引用范围失败。';
  } finally {
    if (version === referenceReadVersion) referenceReading.value = false;
  }
}
async function applyReferenceScope() {
  const current = plan.value, relation = selectedReferenceRelation.value, scope = referenceScope.value;
  if (locked.value || current?.status !== 'CONFIRMED' || !relation || !scope?.fieldName || !referenceScopeChanged.value) return;
  const version = referenceReadVersion;
  try {
    await ElMessageBox.confirm(
      `将「${menuLabel(scope.menuId)}」的「${fieldLabel(relation.sourceModelKey, relation.fieldKey)}」来源更新为 ${referenceMenuLabels(scope.targetMenuIds)}，保留已有引用值及顺序。请确认本栏尚未公开应用，且未绑定详情模板。`,
      '确认更新引用范围', { type: 'warning', confirmButtonText: '更新本栏', cancelButtonText: '取消' },
    );
  } catch { return; }
  if (version !== referenceReadVersion || locked.value) return;
  referenceApplying.value = true;
  referenceError.value = '';
  referenceScope.value = null;
  try {
    const result = await siteModelApi.applyReferenceScope(props.siteId, current, relation.sourceModelKey, relation.fieldKey, scope);
    if (version === referenceReadVersion) {
      referenceScope.value = result;
      menuFields.value.delete(scope.menuId);
    }
  } catch (cause) {
    if (version === referenceReadVersion) referenceError.value = `${cause instanceof Error ? cause.message : '暂时无法确认更新结果。'}请重新读取本栏引用范围。`;
  } finally {
    referenceApplying.value = false;
  }
}
const matchedModels = computed(() => plan.value?.proposal.models.filter(model => model.menuId &&
  model.fields.every(field => field.fieldName) &&
  (!props.columnsLoaded || props.columns.some(menu => Number(menu.id) === model.menuId))) || []);
const seedTargets = computed(() => {
  const current = plan.value;
  if (current?.status !== 'CONFIRMED' || editingConfirmed.value || dirty.value) return [];
  const targets = [];
  const regions = current.proposal.regions.filter(region => region.mode === 'LIST' && region.maintenance?.mode !== 'REFERENCE'
    && (region.maintenance?.mode === 'OWNED' ? region.records.length > 0 : region.records.length > 1));
  for (const region of regions) {
    const owned = region.maintenance?.mode === 'OWNED';
    const model = (owned ? ownedModels.value : matchedModels.value).find(item => item.key === region.modelKey);
    if (!model) continue;
    const samePage = regions.filter(item => item.sourcePath === region.sourcePath && item.modelKey === region.modelKey);
    const area = samePage.length > 1 ? ` · 区域 ${samePage.indexOf(region) + 1}` : '';
    targets.push({
      key: regionKey(region), region, menuId: model.menuId,
      label: `${model.name} · ${region.sourcePath}${area}`,
    });
  }
  return targets;
});
const focusedSeedHeading = ref<HTMLElement | null>(null);
const focusedSeedTargets = computed(() => {
  const target = props.focusTarget;
  if (target?.section !== 'list-seed' || !target.menuId || !target.sourcePath) return [];
  return seedTargets.value.filter(item => item.menuId === target.menuId && item.region.sourcePath === target.sourcePath);
});
const focusedRegionModel = computed(() => {
  const target = props.focusTarget;
  if (target?.section !== 'list-seed' || !target.sourcePath || !target.menuId ||
      plan.value?.status !== 'CONFIRMED' || focusedSeedTargets.value.length || dirty.value ||
      editingConfirmed.value || navigationPanel.value?.hasUnsavedChanges()) return;
  const models = matchedModels.value.filter(model => model.kind === 'LIST' && model.menuId === target.menuId &&
    !ownedModels.value.includes(model) && model.fields.some(field => field.type === 'Sort' && field.fieldName === 'sort'));
  return models.length === 1 ? models[0] : undefined;
});
const focusedRegionPage = computed(() => evidence.value.get(props.focusTarget?.sourcePath || ''));
const focusedUsedRegionIds = computed(() => plan.value?.proposal.regions
  .filter(region => region.sourcePath === props.focusTarget?.sourcePath).map(region => region.regionId) || []);
watch([focusedRegionModel, () => props.focusTarget?.sourcePath], ([model, path]) => {
  if (model && path) void inspectPage(path);
}, { immediate: true });
const visibleSeedTargets = computed(() => props.focusTarget?.section === 'list-seed' ? focusedSeedTargets.value : seedTargets.value);
const seedSource = computed(() => {
  const targets = visibleSeedTargets.value;
  const selected = targets.find(target => target.key === seedRegionKey.value);
  return selected || (props.focusTarget?.section !== 'list-seed' || targets.length === 1 ? targets[0] : undefined);
});
const focusedSeedNext = computed(() => {
  if (loading.value) return '正在读取结构方案及字段对应。';
  if (focusedSeedTargets.value.length) return '此列表有同原页、同栏目且字段已对应的重复区域，可读取预览并核对初值。';
  if (!plan.value) return '请先读取或启动整站结构分析。';
  if (plan.value.status !== 'CONFIRMED' || dirty.value || editingConfirmed.value) return '先确认结构计划，再核对这个列表的重复区域与字段。';
  if (navigationPanel.value?.hasUnsavedChanges()) return '请先处理未保存的导航修订，再核对重复区域。';
  if (focusedRegionModel.value) return '选择一处原页重复区域，核对每条图文与栏目字段对应。审核后可读取初值预览。';
  return '当前确认计划没有可直接导入到此栏目的重复区域。请核对原页、栏目和字段对应，或在内容管理中人工维护记录。';
});
watch([focusedSeedHeading, () => props.focusTarget], ([heading]) => {
  heading?.focus({ preventScroll: true });
  heading?.scrollIntoView({ block: 'nearest' });
}, { flush: 'post' });
const seedInstances = computed(() => ownedInstances.value.get(seedSource.value?.region.modelKey || '') || []);
const seedTarget = computed(() => {
  const source = seedSource.value;
  if (!source) return;
  const instance = seedInstances.value.find(item => item.parentMenuId === seedParentMenuId.value && item.menuId);
  return { ...source, menuId: source.region.maintenance?.mode === 'OWNED' ? instance?.menuId ?? null : source.menuId, ownerMenuId: instance?.parentMenuId };
});
watch([plan, () => seedSource.value?.key], () => { seedParentMenuId.value = null; }, { flush: 'sync' });
const importedRecords = computed(() => seedPreview.value?.records.filter(record => record.contentId != null).length || 0);
const seedMissingFields=computed(()=>{
  const records=seedPreview.value?.records.filter(row=>row.status==='PENDING' || row.status==='CREATED') || [];
  const first=records[0];
  if(!first)return [];
  return first.fields.filter(field=>records.every(row=>row.fields.some(item=>item.fieldName===field.fieldName && (item.value==null || item.value==='')))).map(field=>field.label);
});
const selectedSeedGroup=ref('');
watch([()=>seedPreview.value?.menuId,()=>seedPreview.value?.regionId],()=>{selectedSeedGroup.value='';});
const unfilledGroupFields=computed(()=>{
  const preview=seedPreview.value;
  if(!preview)return [];
  const model=plan.value?.proposal.models.find(item=>item.key===preview.modelKey);
  return model?.fields.filter(field=>field.type==='SingleLine' && field.fieldName &&
    preview.records.some(row=>row.contentId && row.fields.some(item=>item.fieldName===field.fieldName && (item.value==null || item.value==='')))) || [];
});
const receiptGroupField=computed(()=>unfilledGroupFields.value.length===1 ? unfilledGroupFields.value[0] :
  unfilledGroupFields.value.find(field=>field.fieldName===selectedSeedGroup.value));
function openSeedGrouping(){
  const preview=seedPreview.value,field=receiptGroupField.value?.fieldName;
  if(locked.value || !preview || !field || seedTarget.value?.menuId!==preview.menuId)return;
  const seedIds=[...new Set(preview.records.flatMap(record=>record.contentId && ['CREATED','EXISTING'].includes(record.status) ? [record.contentId] : []))];
  if(seedIds.length)emit('manage-content',preview.menuId,{groupField:field,seedIds});
}
const seedImageFields=computed(()=>new Set(plan.value?.proposal.models
  .find(model=>model.key===seedTarget.value?.region.modelKey)?.fields
  .filter(field=>field.type==='Image').map(field=>field.fieldName)));
const pendingRecords = computed(() => seedPreview.value?.records.filter(record => record.status === 'PENDING').length || 0);
const nextContentModel = computed(() => matchedModels.value.find(model => model.key === structureResult.value?.modelKey) || matchedModels.value[0]);
const nextBindingPath = computed(() => nextContentModel.value ? modelBindingPath(nextContentModel.value) : undefined);
const currentStage = computed(() => !plan.value ? 0 : plan.value.status === 'DRAFT' || editingConfirmed.value ? 1 :
  navigationState.value === 'APPLIED' || (navigationState.value === 'empty' &&
    matchedModels.value.length === plan.value.proposal.models.length && matchedModels.value.length > 0) ? 3 : 2);
const nextStep = computed(() => {
  if (taskId.value && !polling.value && !taskUnavailable.value && (activeTask.value || !task.value)) {
    return { title: '查询原分析任务', description: '查询现有任务的状态并读取已保存结果。', action: 'resume' };
  }
  if (activeTask.value || polling.value || starting.value) {
    return { title: '等待分析结果', description: progress.value || '完成后会读取已保存的结构草案。' };
  }
  if (canRecoverDraft.value && !plan.value) {
    return { title: '恢复已校验草案', description: '读取分析中断前已校验的建议，恢复后可继续审核。', action: 'recover' };
  }
  if (!plan.value) {
    return { title: '识别内容结构', description: '从当前页面包识别内容类型、共用字段及候选记录。', action: 'start' };
  }
  if (plan.value.status === 'DRAFT' || editingConfirmed.value) {
    return { title: '审核内容模型草案', description: navigationState.value === 'empty'
      ? '展开下方内容类型，核对名称、字段、使用区域和待处理项。确认后可逐个创建栏目。'
      : '展开下方内容类型，核对名称、字段、使用区域和待处理项。确认后在导航树中审核对应栏目。', action: 'confirm' };
  }
  if (applyNeedsRead.value) {
    return { title: '核对上次应用结果', description: '读取同一计划的最新修订，确认栏目是否已经创建，再继续操作。', action: 'read' };
  }
  if (navigationState.value !== 'empty') {
    if (navigationState.value === 'APPLIED') {
      if (ownedInstanceRows.value.some(row => !row.menuId)) return { title: '建立归属条目栏目', description: '按所属栏目逐个建立独立子列表。', action: 'owned' };
      if (seedTargets.value.length) return { title: '审核重复区域初值', description: '选择来源区域与目标记录，核对初值后导入草稿。', action: 'seed' };
      return { title: '选择栏目继续', description: '导航栏目已应用。在上方选择“在工作台继续”，核对该栏目的字段、初值、模板及公开状态。' };
    }
    if (navigationState.value === 'APPLYING') return { title: '继续上次栏目应用', description: '在上方打开应用范围，核对上一份确认并继续。' };
    if (navigationState.value === 'unavailable') return { title: '重新读取导航', description: '在上方刷新导航与内容状态，再审核本页面包的栏目。' };
    if (navigationState.value==='CONFIRMED') return { title:'核对并应用栏目与字段',description:'在上方查看应用范围及模型归属。需要修正模型时，仍可从相关导航行打开页面建议。' };
    return { title: '审核站点导航', description: '在上方导航树核对层级、来源、模型和 CMS 栏目，并确认对应关系。' };
  }
  if (pendingModels.value.length) {
    return { title: '创建 CMS 栏目与字段', description: '选择本次要落地的内容类型。CMS 会建立实际栏目及字段，初值、模板和预览随后处理。', action: 'apply' };
  }
  if (ownedInstanceRows.value.some(row => !row.menuId)) {
    return { title: '建立归属条目栏目', description: '按所属栏目逐个建立独立子列表。', action: 'owned' };
  }
  if (seedTargets.value.length) {
    return { title: '审核并导入重复区域初值', description: '按来源区域核对记录数量和字段值，再导入内容草稿。模板配置和预览验证随后处理。', action: 'seed' };
  }
  if (nextContentModel.value && nextBindingPath.value && currentStage.value === 3) {
    return { title: '核对字段与初值', description: `继续「${nextContentModel.value.name}」的字段和内容初值。`, action: 'fields' };
  }
  return { title: '检查对应的 CMS 栏目', description: '请在栏目管理检查计划中对应的栏目是否存在，补充缺失字段，再回工作台修订计划。', action: 'columns' };
});
const taskProgress = computed(() => parseDomainAgentProgress(task.value?.progress));
const progress = computed(() => {
  const current = taskProgress.value;
  return current.observedPages != null && current.totalPages != null
    ? `${current.message || '读取页面证据'}（${current.observedPages} / ${current.totalPages} 页）`
    : current.message || '';
});
function progressToolLabel(tool: string) {
  return ({ model: '模型决策', model_decision: '模型决策', read_catalog: '读取页面清单', read_fragments: '读取公共片段',
    read_model: '读取栏目与字段', read_page: '读取页面证据', read_pages: '读取页面证据', read_region: '读取区域原文',
    read_shared: '读取公共信息', read_navigation: '核对导航', read_coverage: '核对覆盖范围', read_interactions: '读取交互证据',
    validate_proposal: '校验方案', propose: '校验方案', finish: '保存待审方案', context: '准备分析上下文',
    stopped: '任务停止' } as Record<string, string>)[tool] || '工具步骤';
}
function progressStatusLabel(status: string) {
  return ({ waiting: '等待模型决策', running: '执行中', pending: '等待执行', ok: '已完成', completed: '已完成',
    error: '待处理', failed: '失败', cancelled: '已取消' } as Record<string, string>)[status] || '等待更新';
}
function stepTime(at: string) {
  return new Date(at).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });
}
const regionsByModel = computed(() => {
  const map = new Map<string, SiteModelRegion[]>();
  for (const region of draft.value?.regions || []) {
    if (!map.has(region.modelKey)) map.set(region.modelKey, []);
    map.get(region.modelKey)?.push(region);
  }
  return map;
});
function modelBindingPath(model: SiteContentModel) {
  if (!matchedModels.value.includes(model) || ownedModels.value.includes(model)) return;
  const regions = (regionsByModel.value.get(model.key) || []).filter(region => !region.maintenance);
  return regions.find(region =>
    ['SINGLE', 'DETAIL'].includes(region.mode) && region.records.length === 1 &&
    props.pages.includes(region.sourcePath) && regions.filter(use => use.sourcePath === region.sourcePath).length === 1,
  )?.sourcePath;
}
const modelLabel = (key: string) => draft.value?.models.find(model => model.key === key)?.name || key;
const fieldLabel = (modelKey: string, fieldKey: string) =>
  draft.value?.models.find(model => model.key === modelKey)?.fields.find(field => field.key === fieldKey)?.name || fieldKey;
function removeRelation(index: number) {
  if (!readOnly.value) draft.value?.relations.splice(index, 1);
}
const modelMenus = (kind: string) => props.columns.filter(menu => kind === 'LIST' ? menu.pageType === 1 : kind === 'SINGLE' ? menu.pageType === 0 : menu.pageType === 0 || menu.pageType === 1);
const fieldOptions = (menuId: number | null, type: string) => (menuFields.value.get(menuId || 0) || []).filter(field => field.fieldType === type);
function clearSlots(model: SiteContentModel) { for (const field of model.fields) field.fieldName = field.type === 'Sort' ? 'sort' : null; }
const regionKey = (region: SiteModelRegion) => `${region.sourcePath}:${region.regionId}`;
function maintenanceOwners(region: SiteModelRegion, mode = region.maintenance?.mode) {
  const role = mode === 'PAGE_LIST' ? 'SINGLE' : 'DETAIL';
  const details = draft.value?.regions.filter(item => item.sourcePath === region.sourcePath && item.mode === role
    && (role !== 'SINGLE' || !['HEADER', 'FOOTER'].includes(sourceRegion(item)?.kind || ''))) || [];
  if (details.length !== 1 || details[0]?.records.length !== 1) return [];
  return draft.value?.models.filter(model => model.key === details[0]?.modelKey) || [];
}
function maintenanceOwner(region: SiteModelRegion) {
  return maintenanceOwners(region).find(model => model.key === region.maintenance?.ownerModelKey);
}
function relationForField(ownerKey: string | undefined, fieldKey: string | null | undefined) {
  return draft.value?.relations.find(relation => relation.sourceModelKey === ownerKey && relation.fieldKey === fieldKey);
}
function maintenanceRelation(region: SiteModelRegion) {
  return relationForField(region.maintenance?.ownerModelKey, region.maintenance?.fieldKey);
}
function fieldUsedByRecord(ownerKey: string, fieldKey: string) {
  return regionsByModel.value.get(ownerKey)?.some(region => region.records.some(record => fieldKey in record.bindings)) ?? false;
}
function maintenanceFields(region: SiteModelRegion) {
  const owner = maintenanceOwner(region);
  const target = draft.value?.models.find(model => model.key === region.modelKey);
  if (!owner) return [];
  return owner.fields.filter(field => {
    if (field.type !== 'SingleLine' || fieldUsedByRecord(owner.key, field.key)) return false;
    const relation = relationForField(owner.key, field.key);
    if (relation && relation.targetModelKey !== region.modelKey) return false;
    if (!field.fieldName) return true;
    const config = fieldOptions(owner.menuId, field.type).find(option => option.fieldName === field.fieldName);
    if (!config) return field.key === region.maintenance?.fieldKey && relation?.targetModelKey === region.modelKey
      && /^ext_[a-f0-9]{32}$/.test(field.fieldName) && !menuFields.value.has(owner.menuId || 0);
    const sources = /^\{\$([1-9]\d*(?:,[1-9]\d*)*)_title_id\$\}$/.exec(config.defaultValue || '')?.[1]?.split(',').map(Number);
    const targetMenus = target ? modelMenuIds(target) : [];
    return !!target?.menuId && /^ext_[a-f0-9]{32}$/.test(field.fieldName) && config.inputType === 'content'
      && config.inputMultiple === true && !!sources?.includes(target.menuId) && sources.every(id => targetMenus.includes(id));
  });
}
function maintenanceIssue(region: SiteModelRegion) {
  const config = region.maintenance;
  if (!config) return '';
  if (!maintenanceOwner(region)) return '请选择同页详情的所属内容类型。';
  if (!config.reason.trim()) return '请填写区域维护的判断依据。';
  if (config.mode !== 'REFERENCE') return '';
  if (!config.fieldKey) return '请选择所属内容的引用字段。';
  const relation = maintenanceRelation(region);
  if (relation && relation.targetModelKey !== region.modelKey) return '所选字段已有其他或未确定的关联目标，请选择其他字段。';
  if (!maintenanceFields(region).some(field => field.key === config.fieldKey)) return '请选择未分配且未绑定原文的字段，或已配置为对应目标的原生内容多选字段。';
  if (draft.value?.regions.some(other => other !== region && other.sourcePath === region.sourcePath &&
    other.maintenance?.mode === 'REFERENCE' && other.maintenance.fieldKey === config.fieldKey)) return '同页已有其他区域使用此引用字段，请另选字段。';
  if (!relation) return '请建立此内容关联。';
  return '';
}
function canSaveMaintenanceRelation(region: SiteModelRegion) {
  const config = region.maintenance;
  return config?.mode === 'REFERENCE' && !!config.reason.trim() &&
    maintenanceFields(region).some(field => field.key === config.fieldKey);
}
function saveMaintenanceRelation(region: SiteModelRegion) {
  const config = region.maintenance;
  if (!draft.value || readOnly.value || !config?.fieldKey || !canSaveMaintenanceRelation(region)) return;
  const existing = maintenanceRelation(region);
  if (existing) {
    if (existing.targetModelKey === region.modelKey) existing.reason = config.reason.trim();
    return;
  }
  draft.value.relations.push({ sourceModelKey: config.ownerModelKey, fieldKey: config.fieldKey,
    targetModelKey: region.modelKey, reason: config.reason.trim() });
}
function setMaintenanceMode(region: SiteModelRegion, mode: '' | 'OWNED' | 'REFERENCE' | 'PAGE_LIST') {
  if (readOnly.value) return;
  const previous = region.maintenance;
  const owners = mode ? maintenanceOwners(region, mode) : [];
  region.maintenance = mode ? { mode, ownerModelKey: owners.some(owner => owner.key === previous?.ownerModelKey) ? previous!.ownerModelKey : owners[0]?.key || '', fieldKey: mode === 'REFERENCE' ? previous?.fieldKey ?? null : null, reason: previous?.reason || '' } : null;
}
const sourceRegion = (region: SiteModelRegion) => evidence.value.get(region.sourcePath)?.regions.find(item => item.id === region.regionId);
const sourceRecord = (region: SiteModelRegion, record: SiteModelRecord) => sourceRegion(region)?.records.find(item => item.id === record.recordId);
const sourceValue = (region: SiteModelRegion, record: SiteModelRecord, field: string) => sourceRecord(region, record)?.values.find(value => value.id === record.bindings[field]);
const imageSources = (region: SiteModelRegion, record: SiteModelRecord) => sourceRecord(region, record)?.values.filter(value => value.typeHint === 'IMAGE') || [];
function setImageSource(region: SiteModelRegion, record: SiteModelRecord, field: string, sourceId: string) {
  if (readOnly.value) return;
  if (!sourceId) delete record.bindings[field];
  else if (imageSources(region, record).some(value => value.id === sourceId)) record.bindings[field] = sourceId;
}
let generation = 0, pollVersion = 0;
let seedReadVersion = 0;
function openOwnedStructure() {
  const heading = ownedStructureHeading.value?.querySelector('h3');
  heading?.focus({ preventScroll: true });
  heading?.scrollIntoView({ block: 'start' });
}
let ownerReadVersion = 0;
let timer: ReturnType<typeof setTimeout> | undefined;

function openRecordSeed() {
  recordSeedHeading.value?.focus({ preventScroll: true });
  recordSeedHeading.value?.scrollIntoView({ block: 'start' });
}
function focusRecordSeed(key: string) {
  const selected=seedTarget.value?.key===key;
  selectSeedRegion(key);
  if(selected && !seedPreview.value && !seedLoading.value)void readRecordSeed();
  openRecordSeed();
}
function selectSeedRegion(key: string) {
  if (!visibleSeedTargets.value.some(item => item.key === key)) return;
  seedRegionKey.value = key;
}
function checkRecordSeedScope(result: SiteModelRecordSeed, target: NonNullable<typeof seedTarget.value>, ownerRecordId?: number) {
  if (result.sourcePath !== target.region.sourcePath || result.regionId !== target.region.regionId ||
      result.modelKey !== target.region.modelKey || result.menuId !== target.menuId ||
      (result.ownerRecordId ?? undefined) !== ownerRecordId) {
    throw new Error('返回结果与所选来源区域或栏目不一致，请重新读取结构。');
  }
  if (!/^[0-9a-f]{64}$/.test(result.previewDigest)) {
    throw new Error('服务端未返回有效的预览摘要，请重新读取预览。');
  }
}
// 同一修订可能因刷新栏目树而重新回读；以实际来源和修订判断回执归属。
const seedReadScope=computed(()=>JSON.stringify([
  props.siteId,props.adminId,props.packageVersion,plan.value?.id,plan.value?.revision,plan.value?.status,
  props.focusTarget?.section,props.focusTarget?.sourcePath,props.focusTarget?.menuId,
  seedTarget.value?.key,seedTarget.value?.menuId,seedTarget.value?.ownerMenuId
]));
async function readRecordSeed() {
  const version = ++seedReadVersion, current = plan.value, target = seedTarget.value;
  const focus = props.focusTarget, scope = seedReadScope.value;
  const ownerRecordId = target?.region.maintenance?.mode === 'OWNED' ? ownerRecord.value?.recordId : undefined;
  seedPreview.value = null;
  seedError.value = '';
  seedLoading.value = !!current && !!target?.menuId && (target.region.maintenance?.mode !== 'OWNED' || !!ownerRecordId);
  if (!current || !target?.menuId || !seedLoading.value) return;
  if (focus?.section === 'list-seed' && (focus.sourcePath !== target.region.sourcePath || focus.menuId !== target.menuId)) {
    seedLoading.value = false;
    return;
  }
  try {
    const result = await siteModelApi.recordSeed(props.siteId, current, target.region, ownerRecordId, ownerRecordId ? target.menuId : undefined);
    if (version !== seedReadVersion || scope !== seedReadScope.value || ownerRecordId !== (target.region.maintenance?.mode === 'OWNED' ? ownerRecord.value?.recordId : undefined)) return;
    checkRecordSeedScope(result, target, ownerRecordId);
    seedPreview.value = result;
  } catch (cause) {
    if (version === seedReadVersion) seedError.value = cause instanceof Error ? cause.message : '读取区域初值失败。';
  } finally {
    if (version === seedReadVersion) seedLoading.value = false;
  }
}
async function importRecordSeed() {
  const current = plan.value, target = seedTarget.value, preview = seedPreview.value;
  if (!current || !target?.menuId || !preview?.ready || !pendingRecords.value || locked.value || activeTask.value) return;
  const version = seedReadVersion, count = pendingRecords.value, focus = props.focusTarget;
  const parent = ownerRecord.value;
  if (target.region.maintenance?.mode === 'OWNED' && !parent) return;
  if (focus?.section === 'list-seed' && (focus.sourcePath !== target.region.sourcePath || focus.menuId !== target.menuId)) return;
  try {
    await ElMessageBox.confirm(
      `将「${target.region.sourcePath}」所选区域的 ${count} 条初值导入「${menuLabel(target.menuId)}」为草稿。${parent ? `所属记录为「${parent.title || '未命名'}」（#${parent.recordId}）。` : ''}已有内容将保留。`,
      '确认导入内容草稿',
      { type: 'warning', confirmButtonText: '导入为草稿', cancelButtonText: '取消' },
    );
  } catch { return; }
  if (version !== seedReadVersion || current !== plan.value || focus !== props.focusTarget || preview !== seedPreview.value ||
      target.key !== seedTarget.value?.key || target.menuId !== seedTarget.value?.menuId || locked.value || activeTask.value) return;
  seedImporting.value = true;
  seedError.value = '';
  try {
    const result = await siteModelApi.importRecordSeed(props.siteId, current, target.region, preview.previewDigest, parent?.recordId, parent ? target.menuId : undefined);
    if (version !== seedReadVersion) return;
    checkRecordSeedScope(result, target, parent?.recordId);
    seedPreview.value = result;
  } catch (cause) {
    if (version !== seedReadVersion) return;
    seedPreview.value = null;
    seedError.value = `${cause instanceof Error ? cause.message : '暂时无法确认导入结果。'} 请重新读取该区域的预览和导入结果。`;
  } finally {
    seedImporting.value = false;
  }
}
async function readOwnerRecords(page: number) {
  const menuId = seedTarget.value?.ownerMenuId;
  if (!menuId || !ownerPickerOpen.value || locked.value) return;
  const version = ++ownerReadVersion;
  ownerLoading.value = true;
  ownerRows.value = [];
  ownerError.value = '';
  try {
    const result = await listNativeContent({ menuId, page, limit: 20, keyword: ownerKeyword.value.trim() });
    if (version !== ownerReadVersion || seedTarget.value?.ownerMenuId !== menuId) return;
    ownerRows.value = result.rows.filter(row => Number(row.menuId) === menuId && row.recordId > 0);
    ownerPage.value = result.page;
    ownerTotal.value = result.total;
  } catch (cause) {
    if (version === ownerReadVersion) ownerError.value = cause instanceof Error ? cause.message : '读取所属记录失败。';
  } finally {
    if (version === ownerReadVersion) ownerLoading.value = false;
  }
}
function openOwnerPicker() {
  ownerPickerOpen.value = true;
  ownerKeyword.value = '';
  void readOwnerRecords(1);
}
function chooseOwnerRecord(record: NativeContentRow) {
  if (locked.value || ownerLoading.value || !ownerRows.value.includes(record) || Number(record.menuId) !== seedTarget.value?.ownerMenuId) return;
  ownerRecord.value = record;
  ownerPickerOpen.value = false;
  void readRecordSeed();
}
watch(seedReadScope, () => {
  ++ownerReadVersion;
  ownerLoading.value = false;
  ownerPickerOpen.value = false;
  ownerRows.value = [];
  ownerTotal.value = 0;
  ownerRecord.value = null;
  ++seedReadVersion;
  seedLoading.value = false;
  seedPreview.value = null;
  seedError.value = '';
  if (props.focusTarget?.section === 'list-seed' || seedRegionKey.value === seedTarget.value?.key) void readRecordSeed();
}, { flush: 'post' });

function stopPolling() { ++pollVersion; clearTimeout(timer); polling.value = false; }
const confirmedKey = () => `cms-site-model:confirmed:${props.adminId}:${props.siteId}:${encodeURIComponent(props.packageVersion)}`;
const taskKey = () => `cms-site-model:task:${props.adminId}:${props.siteId}:${encodeURIComponent(props.packageVersion)}`;
function rememberTask(id: string) {
  taskId.value = id;
  if (!props.adminId) return;
  try { localStorage.setItem(taskKey(), id); }
  catch { /* 当前会话仍可查询和恢复该任务。 */ }
}
function clearTaskReference(id: string) {
  if (taskId.value === id) taskId.value = '';
  if (!props.adminId) return;
  try { if (localStorage.getItem(taskKey()) === id) localStorage.removeItem(taskKey()); }
  catch { /* 服务端草案仍可按 ID 读取。 */ }
}
function clearPendingStart(id: string) {
  if (requestId.value !== id) return;
  requestId.value = '';
  startBasePlan = undefined;
}
function rememberedConfirmedId() {
  try { return props.adminId ? localStorage.getItem(confirmedKey()) : null; }
  catch { return null; }
}
function setConfirmed(next: SiteModelPlan | null) {
  confirmedPlan.value = next;
  emit('plan', next);
  if (!props.adminId) return;
  try {
    if (next) localStorage.setItem(confirmedKey(), next.id);
    else localStorage.removeItem(confirmedKey());
  } catch { /* 确认计划仍可按 ID 从服务端读取。 */ }
}
function assertScope(next: SiteModelPlan) {
  if (next.siteId !== props.siteId || next.packageVersion !== props.packageVersion) throw new Error('已保存计划不属于当前页面包。');
}
function install(next: SiteModelPlan | null) {
  if (next) assertScope(next);
  if (next?.id !== plan.value?.id) {
    expandedStructureModels.value=[];
    structureResult.value = null;
    receiveNavigation(null);
  }
  structureError.value = '';
  applyNeedsRead.value = false;
  plan.value = next;
  draft.value = next ? structuredClone(toRaw(next.proposal)) : null;
  editingConfirmed.value = false;
  openRegions.value.clear();
  if (next?.status === 'CONFIRMED') setConfirmed(next);
  else if (next?.id === confirmedPlan.value?.id || (next && next.id === rememberedConfirmedId())) setConfirmed(null);
}
async function openNavigationModelDraft(next:SiteModelPlan,modelKey:string){
  if(next.siteId!==props.siteId || next.packageVersion!==props.packageVersion || next.status!=='DRAFT' || dirty.value || editingConfirmed.value)return;
  install(next);
  expandedStructureModels.value=[modelKey];
  navigationDraftId.value=next.id;
  try{localStorage.setItem(navigationDraftStorageKey(),next.id);}catch{/* 草稿已经在服务端保存。 */}
  await nextTick();
  focusChecklistStep('model');
}
function showError(cause: unknown, fallback: string) { error.value = cause instanceof Error ? cause.message : fallback; }
const enabled = (flag?: string) => ['on', 'true', '1'].includes(String(flag).toLowerCase());
const writable = (field: FieldOption & { isReadOnly?: string }) => enabled(field.isOpen) && !enabled(field.isReadOnly);
async function loadFields(menuId: number | null) {
  if (!menuId || loadingFields.value.has(menuId)) return;
  const menu = props.columns.find(item => Number(item.id) === menuId);
  if (!menu) return;
  const version = generation;
  loadingFields.value.add(menuId);
  try {
    const config = await getMenuFieldConfig(menuId, menu.pageType === 0 ? 1 : 0);
    if (version === generation) menuFields.value.set(menuId, config.fieldOptions.filter(writable));
  } catch (cause) { if (version === generation) showError(cause, '读取栏目字段失败'); }
  finally { if (version === generation) loadingFields.value.delete(menuId); }
}
async function discardEdits(action: string) {
  if (!dirty.value) return true;
  try { await ElMessageBox.confirm(`${action}将放弃尚未保存的整站结构修正。`, action, { type: 'warning' }); return true; }
  catch { return false; }
}
async function reload(id?: string) {
  if (loading.value || applying.value || activeTask.value || !await discardEdits('重新读取整站结构')) return;
  const version = generation;
  const refreshColumns = !!plan.value && (applyNeedsRead.value || plan.value.status === 'CONFIRMED');
  loading.value = true; error.value = '';
  try {
    const next = await (id ? siteModelApi.get(props.siteId, id) : siteModelApi.latest(props.siteId));
    if (version !== generation) return;
    install(next);
    if (refreshColumns) emit('structure-applied');
    const lastId = rememberedConfirmedId() || confirmedPlan.value?.id;
    if (!id && lastId && next?.id !== lastId) {
      const last = await siteModelApi.get(props.siteId, lastId);
      if (version !== generation) return;
      assertScope(last);
      setConfirmed(last.status === 'CONFIRMED' ? last : null);
    }
  } catch (cause) { if (version === generation) showError(cause, '读取整站结构失败'); }
  finally { if (version === generation) loading.value = false; }
}
async function save(confirm: boolean) {
  if (!plan.value || !draft.value || readOnly.value) return;
  const invalid = draft.value.regions.find(region => maintenanceIssue(region));
  if (invalid) {
    error.value = `${invalid.sourcePath} · ${modelLabel(invalid.modelKey)}：${maintenanceIssue(invalid)}`;
    return;
  }
  const version = generation, current = plan.value;
  if (confirm || editingConfirmed.value) {
    const impact = editingConfirmed.value ? '保存后将替换当前审核修订；依赖旧修订的字段计划需重新核对。' : '';
    const confirmation = navigationState.value === 'empty'
      ? '确认这份整站结构计划？未决关系仍可保留。确认后可逐个创建 CMS 栏目和字段。'
      : '确认这份内容模型计划？未决关系仍可保留。确认后可审核站点导航与对应栏目。';
    try { await ElMessageBox.confirm(`${confirm ? confirmation : '保存为待重新审核的结构计划？'}${impact}`, confirm ? '确认整站结构计划' : '保存整站结构修正', { type: 'warning' }); }
    catch { return; }
  }
  if (version !== generation || current !== plan.value || readOnly.value || !draft.value) return;
  saving.value = true; error.value = '';
  try {
    const next = await siteModelApi.review(props.siteId, current.id, { revision: current.revision, packageVersion: current.packageVersion, proposal: draft.value, confirm });
    if (version === generation) install(next);
  } catch (cause) { if (version === generation) showError(cause, '保存整站结构失败'); }
  finally { if (version === generation) saving.value = false; }
}
async function reviewFocusedRegion(region: SiteModelRegion) {
  const current = plan.value, version = generation, target = props.focusTarget;
  const canReview = () => current?.status === 'CONFIRMED' && current === plan.value &&
    version === generation && target === props.focusTarget && !locked.value && !activeTask.value &&
    focusedRegionModel.value?.key === region.modelKey && target?.sourcePath === region.sourcePath &&
    !focusedUsedRegionIds.value.includes(region.regionId) &&
    focusedRegionPage.value?.regions.some(item => item.id === region.regionId);
  if (!current || !canReview()) return;
  try {
    await ElMessageBox.confirm(
      `将「${region.sourcePath}」选定区域的 ${region.records.length} 条记录映射加入已审核结构。确认会生成新修订，内容仍需核对初值后导入。`,
      '确认重复区域和字段映射',
      { type: 'warning', confirmButtonText: '确认区域', cancelButtonText: '取消' },
    );
  } catch { return; }
  if (!canReview()) return;
  saving.value = true;
  error.value = '';
  try {
    const proposal = { ...current.proposal, regions: [...current.proposal.regions, region] };
    const next = await siteModelApi.review(props.siteId, current.id, {
      revision: current.revision, packageVersion: current.packageVersion, proposal, confirm: true,
    });
    if (version !== generation || current !== plan.value) return;
    install(next);
    await nextTick();
    if (target === props.focusTarget && plan.value?.id === next.id) focusRecordSeed(regionKey(region));
  } catch (cause) {
    if (version === generation) showError(cause, '保存重复区域失败，请重新读取结构计划。');
  } finally {
    if (version === generation) saving.value = false;
  }
}
async function applyStructure(model = selectedModel.value, parent = applyParentId.value) {
  const current = plan.value;
  if (!current || current.status !== 'CONFIRMED' || !model) return;
  const canApply = () => !locked.value && !activeTask.value && !dirty.value && !editingConfirmed.value && !applyNeedsRead.value &&
    (ownedModels.value.includes(model)
      ? !ownedInstancesLoading.value && !modelStructureBlocker(model) &&
        ownedInstanceRows.value.some(row => row.model === model && row.parentMenuId === parent && !row.menuId)
      : !applyBlocker.value && model === selectedModel.value && parent === applyParentId.value);
  if (!canApply()) return;
  const version = generation;
  try {
    await ElMessageBox.confirm(
      `将在「${parent ? menuLabel(parent) : '本站根栏目'}」下为「${model.name}」创建 CMS 栏目与字段。内容初值、模板和发布随后处理。`,
      '确认创建 CMS 栏目与字段',
      { type: 'warning', confirmButtonText: '创建栏目与字段', cancelButtonText: '取消' },
    );
  } catch { return; }
  if (version !== generation || current !== plan.value || !canApply()) return;
  applying.value = true;
  structureError.value = '';
  structureResult.value = null;
  try {
    const result = await siteModelApi.applyStructure(props.siteId, current, model.key, parent || undefined);
    if (version !== generation) return;
    install(result.plan);
    menuFields.value.delete(result.menuId);
    structureResult.value = {
      modelKey: result.modelKey,
      title: `${result.reused ? '已确认现有栏目' : '已创建 CMS 栏目'} · ${model.name} #${result.menuId}`,
      description: [
        `已对应 ${Object.keys(result.fields).length} 个字段。`,
        result.initializationPending ? '请核对来源区域的初值，或进入栏目维护内容。' : '',
        result.publicationPending ? '模板和公开验证待处理。' : '',
      ].join(''),
      menuId: result.menuId,
    };
    emit('structure-applied');
  } catch (cause) {
    if (version !== generation) return;
    structureError.value = cause instanceof Error ? cause.message : '暂时无法确认创建结果。';
    const status = cause instanceof AdminHttpError ? cause.status : undefined;
    applyNeedsRead.value = !status || status === 409 || status >= 500;
  } finally {
    if (version === generation) applying.value = false;
  }
}
const supplementRetry = ref<{planId:string;revision:number;menuId:number;modelKey:string;preview:SiteModelSupplementPreview}|null>(null);
async function supplementFields(model:SiteContentModel) {
  const current=plan.value, menuId=model.menuId;
  if (!current || !menuId || current.status!=='CONFIRMED' || locked.value || activeTask.value || dirty.value) return;
  const version=generation, siteId=props.siteId;
  applying.value=true;
  structureError.value='';
  structureResult.value=null;
  try {
    const saved=supplementRetry.value;
    const previous=saved?.planId===current.id && saved.revision===current.revision && saved.menuId===menuId &&
      saved.modelKey===model.key ? saved.preview : null;
    const preview=previous ?? await siteModelApi.previewFieldSupplement(siteId,current,model.key);
    if (version!==generation || current!==plan.value || preview.menuId!==menuId) return;
    try {
      await ElMessageBox.confirm(
        `将在「${menuLabel(menuId)}」（${menuId}）增补以下字段：${preview.additions.map(field=>`${field.name}（${field.type}）`).join('、')}。初值及模板随后核对。`,
        '确认增补字段', {type:'warning',confirmButtonText:'增补字段',cancelButtonText:'取消'},
      );
    } catch { return; }
    if (version!==generation || current!==plan.value) return;
    supplementRetry.value={planId:current.id,revision:current.revision,menuId,modelKey:model.key,preview};
    const result=await siteModelApi.applyFieldSupplement(siteId,current,model.key,preview);
    if (version!==generation) return;
    supplementRetry.value=null;
    install(result.plan);
    menuFields.value.delete(result.menuId);
    structureResult.value={modelKey:result.modelKey,menuId:result.menuId,
      title:`已增补字段 · ${menuLabel(menuId)} #${menuId}`,
      description:`已增补 ${preview.additions.length} 项。请重新读取字段计划，再核对详情或单页初值。`};
    emit('structure-applied');
  } catch (cause) {
    if (version!==generation) return;
    if (cause instanceof AdminHttpError && cause.status !== undefined && cause.status < 500) {
      supplementRetry.value=null;
    }
    structureError.value=cause instanceof Error ? cause.message : '暂时无法确认增补结果，请重试同一方案。';
  } finally {
    if (version===generation) applying.value=false;
  }
}

async function syncSharedFields(model: SiteContentModel, menuId: number) {
  const current = plan.value, siteId = props.siteId, version = generation;
  if (current?.status !== 'CONFIRMED' || locked.value || dirty.value) return;
  applying.value = true;
  structureError.value = '';
  structureResult.value = null;
  try {
    const preview = await siteModelApi.sharedFields(siteId, current, model.key, menuId);
    if (version !== generation || plan.value !== current || preview.menuId !== menuId) return;
    if (preview.additions.length) {
      try {
        await ElMessageBox.confirm(
          `为「${menuLabel(menuId)}」（${menuId}）补齐 ${preview.additions.map(field => field.name).join('、')}，该栏目继续独立维护记录。`,
          '核对共享字段', { type: 'warning', confirmButtonText: '补齐共享字段' },
        );
      } catch { return; }
      if (version !== generation || plan.value !== current) return;
      await siteModelApi.sharedFields(siteId, current, model.key, menuId, preview);
    }
    if (version !== generation) return;
    menuFields.value.delete(menuId);
    structureResult.value = { modelKey: model.key, menuId, title: `共享字段已核对 · ${menuLabel(menuId)} #${menuId}`,
      description: preview.additions.length ? `已补齐 ${preview.additions.length} 个字段槽位。` : '当前栏目所需的共享字段已配置。' };
    if (preview.additions.length) emit('structure-applied');
  } catch (cause) {
    if (version === generation) structureError.value = cause instanceof Error ? cause.message : '无法核对共享模型字段';
  } finally {
    if (version === generation) applying.value = false;
  }
}

async function loadCatalog() {
  if (catalog.value || catalogLoading.value) return;
  const version = generation;
  catalogLoading.value = true; error.value = '';
  try {
    const next = await siteModelApi.catalog(props.siteId, props.packageVersion);
    if (version !== generation) return;
    if (next.packageVersion !== props.packageVersion) throw new Error('页面包已变化，请刷新后读取取证清单。');
    catalog.value = next;
  } catch (cause) { if (version === generation) showError(cause, '读取整站取证清单失败'); }
  finally { if (version === generation) catalogLoading.value = false; }
}
async function inspectPage(path: string) {
  if (!path || evidence.value.has(path) || loadingEvidence.value.has(path)) return;
  const version = generation;
  loadingEvidence.value.add(path); error.value = '';
  try {
    const page = await siteModelApi.evidence(props.siteId, props.packageVersion, path);
    if (version !== generation) return;
    if (page.sourcePath !== path) throw new Error('返回证据与所选页面不一致。');
    evidence.value.set(path, page);
  } catch (cause) { if (version === generation) showError(cause, '读取页面来源失败'); }
  finally { if (version === generation) loadingEvidence.value.delete(path); }
}
function openEvidence(region: SiteModelRegion) {
  const key = regionKey(region);
  if (openRegions.value.delete(key)) return;
  openRegions.value.add(key);
  void inspectPage(region.sourcePath);
}
function receive(snapshot: DomainAgentTask, showCompleted = true) {
  emit('agent-task', snapshot);
  task.value = snapshot;
  taskUnavailable.value = false;
  clearPendingStart(snapshot.taskId);
  if (snapshot.state === 'completed') {
    stopPolling();
    clearTaskReference(snapshot.taskId);
    if (showCompleted && !dirty.value) {
      const next = JSON.parse(snapshot.text) as SiteModelPlan;
      if (plan.value?.id !== next.id || plan.value.revision < next.revision) install(next);
    }
    return;
  }
  if (snapshot.state === 'failed') error.value = snapshot.error || '站级 Agent 未生成结构计划，请查看进度或重试。';
  if (activeTask.value) {
    polling.value = true;
    const version = pollVersion;
    timer = setTimeout(() => void poll(version, snapshot.taskId), 1200);
  } else stopPolling();
}
async function poll(version: number, id: string, restoring = false) {
  try {
    const next = await siteModelApi.task(props.siteId, id);
    if (version === pollVersion) receive(next, !restoring);
  } catch (cause) {
    if (version !== pollVersion) return;
    stopPolling();
    if (cause instanceof AdminHttpError && (cause.status === 404 || cause.status === 410)) {
      if (restoring && plan.value) {
        clearTaskReference(id);
        return;
      }
      taskUnavailable.value = true;
    }
    showError(cause, '查询任务失败，可继续查询同一任务。');
  }
}
function resume() {
  if (!taskId.value || polling.value || cancelling.value || recovering.value) return;
  error.value = ''; polling.value = true;
  void poll(pollVersion, taskId.value);
}
async function restoreTask() {
  const version = generation;
  const rememberedId = rememberedConfirmedId();
  rememberedPlanNotice.value = '';
  await reload(rememberedId || undefined);
  if (version !== generation) return;
  if (rememberedId && (plan.value?.id !== rememberedId || plan.value.status !== 'CONFIRMED')) {
    const reason = error.value || '原计划已取消确认。';
    setConfirmed(null);
    await reload();
    if (version !== generation) return;
    const fallback = error.value ? '请重试读取最新结构，或按计划 ID 打开已有方案。' : '已重新读取当前页面包的最新结构。';
    rememberedPlanNotice.value = `上次选用的计划无法恢复（${reason}）${fallback}`;
  }
  if (version !== generation || !props.adminId) return;
  try {
    const id = localStorage.getItem(taskKey());
    if (!id || !/^[a-f0-9-]{36}$/i.test(id)) return;
    taskId.value = id;
    polling.value = true;
    await poll(pollVersion, id, true);
  } catch { /* 无法读取浏览器缓存时仍可加载已保存的结构。 */ }
}
async function start(fromDraft: boolean) {
  if (props.packageRunning) { emit('show-package-run'); return; }
  if (locked.value || activeTask.value || dirty.value) return;
  const base = fromDraft && plan.value?.status === 'DRAFT' ? plan.value : null;
  if (fromDraft && (!base || requestId.value)) return;
  if (!fromDraft && plan.value && !requestId.value) {
    try { await ElMessageBox.confirm('重新分析将生成另一份结构建议，可按计划 ID 读取已保存的方案。', '重新分析整站结构', { type: 'warning' }); }
    catch { return; }
  }
  if (locked.value || activeTask.value || dirty.value || (fromDraft && base !== plan.value)) return;
  const version = generation;
  const retrying = !!requestId.value;
  let id = requestId.value;
  starting.value = true; error.value = '';
  try {
    if (!id) {
      startBasePlan = base ? { id: base.id, revision: base.revision } : undefined;
      id = crypto.randomUUID();
      requestId.value = id;
    }
    rememberTask(id);
    task.value = null;
    taskUnavailable.value = false;
    const next = await siteModelApi.start(props.siteId, props.packageVersion, id, startBasePlan?.id, startBasePlan?.revision);
    if (version !== generation) return;
    rememberTask(next.taskId);
    receive(next);
  }
  catch (cause) {
    if (version !== generation) return;
    if (cause instanceof AdminHttpError && cause.status && cause.status >= 400 && cause.status < 500) {
      clearPendingStart(id);
      // 冲突可能指向已有任务，重试遭拒也不能证明上一请求没有创建任务。
      if (!retrying && [400, 401, 403, 404, 422, 429].includes(cause.status)) clearTaskReference(id);
    }
    showError(cause, '启动响应未确认，可重试同一任务。');
  }
  finally { if (version === generation) starting.value = false; }
}
async function cancel() {
  if (!task.value || cancelling.value) return;
  const version = generation, id = task.value.taskId;
  cancelling.value = true; stopPolling(); error.value = '';
  try { const next = await siteModelApi.cancel(props.siteId, id); if (version === generation) receive(next); }
  catch (cause) {
    if (version !== generation) return;
    if (cause instanceof AdminHttpError && (cause.status === 404 || cause.status === 410)) taskUnavailable.value = true;
    showError(cause, '取消未确认，可继续查询同一任务。');
  }
  finally { if (version === generation) cancelling.value = false; }
}
async function recoverDraft() {
  if (!canRecoverDraft.value || locked.value || dirty.value) return;
  const version = generation, id = taskId.value;
  recovering.value = true; error.value = '';
  try {
    const next = await siteModelApi.recoverDraft(props.siteId, id, props.packageVersion);
    if (version !== generation) return;
    install(next);
    clearPendingStart(id);
    clearTaskReference(id);
    task.value = null;
    taskUnavailable.value = false;
  } catch (cause) { if (version === generation) showError(cause, '恢复已校验草案失败。'); }
  finally { if (version === generation) recovering.value = false; }
}
function reset() { ++generation; ++seedReadVersion; ++instanceReadVersion; ++referenceReadVersion; stopPolling(); }
const modelStep=ref<HTMLElement|null>(null);
const checklistFocus=ref<'model'|'navigation'|null>(null);
async function openChecklistPlan(expected:SiteModelPlan,step:'model'|'navigation') {
  if(expected.siteId!==props.siteId || expected.packageVersion!==props.packageVersion)return false;
  if(plan.value?.id!==expected.id || plan.value.revision!==expected.revision)await reload(expected.id);
  if(plan.value?.id!==expected.id || plan.value.revision!==expected.revision)return false;
  focusChecklistStep(step);
  return true;
}
function focusChecklistStep(step:'model'|'navigation') {
  checklistFocus.value=step;
  if(step==='navigation' && navigationState.value==='loading')return;
  if(step==='navigation' && navigationPanel.value?.focusReviewActions()) {
    checklistFocus.value=null;
    return;
  }
  if(!modelStep.value)return;
  modelStep.value.focus({preventScroll:true});
  modelStep.value.scrollIntoView({block:'center'});
  checklistFocus.value=null;
}
watch([navigationState,modelStep,navigationPanel],()=>{
  if(checklistFocus.value)focusChecklistStep(checklistFocus.value);
},{flush:'post'});
defineExpose({
  openChecklistPlan,
  focusChecklistStep,
  reload,
  hasUnsavedChanges: () => dirty.value || !!navigationPanel.value?.hasUnsavedChanges(),
  isWriting: () => saving.value || applying.value || referenceApplying.value || seedImporting.value || navigationBusy.value
});
onBeforeUnmount(() => { ++ownerReadVersion; reset(); });
void restoreTask();
</script>

<style scoped>
.model-toolbar { display: flex; flex-wrap: wrap; gap: 12px; align-items: center; margin-block: 12px; }
.model-heading { display: flex; flex-wrap: wrap; gap: var(--admin-space-3); align-items: center; }
.model-next { padding: var(--admin-space-4); border: var(--admin-card-border); border-radius: var(--admin-card-radius); }
.model-next p { margin-block: var(--admin-space-2); }
.model-next .model-toolbar:last-child { margin-bottom: 0; }
.model-next .model-toolbar:empty { display: none; }
.model-stages { display: flex; flex-wrap: wrap; gap: var(--admin-space-2) var(--admin-space-6); padding-left: var(--admin-space-5); margin: 0 0 var(--admin-space-4); color: var(--el-text-color-secondary); }
.model-stages [aria-current="step"] { color: var(--el-color-primary); font-weight: 600; }
.model-result { margin-top: var(--admin-space-3); }
.model-agent-progress { margin-block: var(--admin-space-3); }
.model-agent-progress li { margin-block: var(--admin-space-2); overflow-wrap: anywhere; }
.model-agent-progress li p { margin: var(--admin-space-1) 0; }
.model-agent-progress time { margin-right: var(--admin-space-2); color: var(--admin-text-secondary); }
.model-toolbar label { flex: 1 1 230px; max-width: 360px; }
.model-toolbar .el-button + .el-button { margin-left: 0; }
.model-region { padding-block: 12px; border-bottom: var(--admin-card-border); }
.model-value { white-space: pre-wrap; overflow-wrap: anywhere; }
.model-seed-fields { margin: 0; }
.model-seed-fields dt { font-weight: 600; }
.model-seed-fields dt:not(:first-child) { margin-top: var(--admin-space-2); }
.model-seed-fields dd { margin-inline-start: 0; color: var(--el-text-color-secondary); }
</style>
