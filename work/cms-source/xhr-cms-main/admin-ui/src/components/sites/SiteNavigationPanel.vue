<template>
  <section class="navigation-panel" aria-labelledby="site-navigation-heading">
    <div class="navigation-toolbar">
      <h3 id="site-navigation-heading">站点导航与栏目</h3>
      <el-tag v-if="navigation && !needsReload" :type="navigation.status === 'APPLIED' ? 'success' : 'info'">{{ navigationStates[navigation.status] }}</el-tag>
      <el-button size="small" :loading="loading" :disabled="disabled || busy" @click="reload">刷新导航与内容状态</el-button>
    </div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <section v-if="(olderPlan || modelLinkPending) && navigation?.status==='APPLIED'" ref="continuationActions" tabindex="-1" class="navigation-focus" aria-label="导航修订接续">
      <el-alert :title="olderPlan ? `已应用导航对应结构修订 ${navigation.planRevision}，当前结构修订为 ${plan.revision}。` : '结构模型与已应用栏目的对应仍需核对。'" type="warning" :closable="false" />
      <p v-if="plan.status!=='CONFIRMED'">请先审核确认当前结构，再接续已应用导航。</p>
      <el-button v-else size="small" type="primary" :disabled="!canContinueRevision"
        @click="previewContinuation">预览接续已应用导航</el-button>
    </section>
    <section v-if="focusTarget" class="navigation-focus" aria-label="清单目标导航">
      <h4 ref="focusHeading" tabindex="-1">清单目标 · {{ focusedNode ? evidenceName(focusedNode) : focusedEvidence ? evidenceName(focusedEvidence) : focusTarget.sourcePath || (focusTarget.menuId ? '栏目 #' + focusTarget.menuId : '导航入口') }}</h4>
      <p v-if="focusedNode">
        {{ kinds[focusedNode.kind] }} · {{ focusedNode.sourcePath || '缺少独立来源页' }}<template v-if="focusedNode.menuId || focusedNode.reuseMenuId"> · CMS 栏目 #{{ focusedNode.menuId || focusedNode.reuseMenuId }}</template>
      </p>
      <p role="status">{{ focusNext }}</p>
      <ParentEntryDecisionEditor v-if="entryScope" ref="entryEditor" :site-id="siteId" :plan="plan"
        :navigation="entryScope.snapshot" :node="entryScope.node" :disabled="disabled || busy"
        @decision="focusedEntry=$event" @busy="emit('busy',$event)" />
      <div class="navigation-toolbar">
        <el-button v-if="focusedNode" size="small" type="primary" :disabled="busy || loading || needsReload" @click="inspect(focusedNode)">{{ canEdit(focusedNode) ? '核对本入口对应' : '查看本入口证据' }}</el-button>
        <el-button v-if="focusedNode && supportsModelDraft(focusedNode)" size="small" :disabled="!canOpenModelDraft(focusedNode)"
          @click="openModelSuggestionForNode(focusedNode.key,$event)">{{ modelDraftLabel(focusedNode) }}</el-button>
        <el-button v-if="focusedNode && supportsKindCorrection(focusedNode)" size="small" :disabled="!canOpenKindCorrection()"
          @click="openKindCorrection(focusedNode)">复核栏目类型</el-button>
        <el-button v-if="focusedNode && supportsListReview(focusedNode)" type="primary" size="small" :disabled="disabled || busy || needsReload || olderPlan"
          @click="emit('continue-list',focusedNode,focusTarget?.detailPath)">继续审核此栏目</el-button>
        <el-button v-if="focusedNode?.sourcePath && navigation?.status==='APPLIED' && !focusedNode.drift" size="small"
          :disabled="disabled || busy || loading" @click="emit('open-package-page',focusedNode.sourcePath)">查看此页识别与审核</el-button>
        <el-button v-if="focusedEvidence && navigation?.status==='APPLIED' && !retainedNavigationLink(navigation,focusedEvidence)" type="primary" size="small"
          :disabled="disabled || busy || loading || needsReload || olderPlan" @click="openAppend(focusedEvidence)">复核并追加导航</el-button>
        <el-button v-if="focusedEvidence?.sourcePath" size="small" :disabled="disabled || busy || loading || needsReload" @click="emit('open-page', focusedEvidence.sourcePath)">打开候选原页</el-button>
        <el-button v-if="focusedNode?.menuId && navigation?.status === 'APPLIED' && !focusedNode.drift && !supportsListReview(focusedNode) && focusedNode.kind !== 'DIRECTORY' && !(focusedEntry?.valid && focusedEntry.role==='DIRECTORY')" size="small" :disabled="disabled || busy || needsReload"
          @click="emit('continue-node', focusedNode)">继续此栏目</el-button>
        <el-button v-else-if="focusedNode?.menuId && navigation?.status === 'APPLIED' && !supportsListReview(focusedNode)" size="small" :disabled="disabled || busy || needsReload" @click="emit('manage-columns')">打开栏目管理</el-button>
      </div>
    </section>
    <p v-if="loading" role="status">正在读取页面包的导航证据和 CMS 栏目。</p>
    <p v-else-if="state === 'empty'" role="status">当前页面包的页头和页脚未找到可审核的站点导航。请核对原页公共区及包内链接后，刷新导航与内容状态。</p>
    <template v-else-if="navigation">
      <p>一级导航 {{ primaryCount }} · 二级栏目 {{ secondaryCount }}<template v-if="deeperCount"> · 三级及以下 {{ deeperCount }}</template> · 栏目已对应 {{ boundCount }} · 有内容（完整性待核对）{{ withContentCount }} · 内容待匹配或复核 {{ pendingCount }}</p>
      <p>指向同一源页面的栏目可共享模板，各栏目独立维护内容。仅为来源明确的栏目录入初值，其他栏目保留待核对状态。</p>
      <el-alert v-if="navigation.originPlanId" :title="`沿用已应用栏目结构（来源方案 ${navigation.originPlanId} · 修订 ${navigation.originPlanRevision}），新入口待核对。`" type="info" :closable="false" />
      <el-alert v-if="olderPlan && navigation.status!=='APPLIED'" :title="`本次导航应用对应整站修订 ${navigation.planRevision}，当前整站修订为 ${plan.revision}。`" type="warning" :closable="false" />
      <p v-if="navigation.status === 'APPLYING'">核对上次确认范围，并继续未完成的栏目应用。</p>
      <p v-else-if="navigation.status === 'APPLIED'">选择实际栏目，继续核对字段、初值、模板和公开状态。</p>
      <p v-else-if="plan.status !== 'CONFIRMED'">请先确认下方的内容模型计划，再审核导航、对应栏目。</p>
      <p v-else-if="dirty">导航对应有未保存修正，请重新审核后应用。</p>
      <p v-else-if="navigation.status === 'CONFIRMED'">先核对将创建和复用的栏目、字段模型与待处理事项。</p>
      <p v-else>逐项核对导航、模型和已有栏目，再确认对应关系。</p>
      <el-alert v-if="conflictingCount" :title="`${conflictingCount} 项已有入口的来源目标存在差异，请在导航表中查看证据。`"
        type="warning" :closable="false" />
      <section v-if="supplementalEvidence.length" class="navigation-supplement" aria-label="新增待审核导航">
        <h4>新增导航来源 · {{ supplementalEvidence.length }} 项</h4>
        <p>打开原页，核对入口的维护栏目和字段。</p>
        <p v-if="navigation.status==='APPLIED' && olderPlan">先接续已应用导航，再复核并追加新入口。</p>
        <p v-else-if="navigation.status==='APPLIED'">选择「复核并追加导航」查看栏目归属和影响范围。确认后会生成新的导航版本，随后可继续绑定字段和模板。</p>
        <el-table :data="supplementalEvidence" row-key="key" size="small" max-height="340">
          <el-table-column label="导航入口" min-width="155">
            <template #default="{ row }">
              {{ evidenceName(row) }}
              <small v-if="hasSourceConflict(row)" class="navigation-note">来源目标待核对</small>
            </template>
          </el-table-column>
          <el-table-column label="来源页面" min-width="165">
            <template #default="{ row }">
              <el-button v-if="row.sourcePath" link type="primary" :disabled="disabled || busy"
                @click="emit('open-page', row.sourcePath)">{{ row.sourcePath }}</el-button>
              <span v-else>待核对</span>
            </template>
          </el-table-column>
          <el-table-column label="来源证据" min-width="250">
            <template #default="{ row }">
              <small v-for="item in row.observations" :key="observationKey(item)" class="navigation-note">
                {{ originLabel(item.origin) }} · {{ item.path }} · {{ item.href || '空链接' }}
              </small>
              <small v-if="!row.observations?.length" class="navigation-note">{{ row.evidencePath }} · {{ row.sourceHref || '空链接' }}</small>
            </template>
          </el-table-column>
          <el-table-column label="操作" min-width="145">
            <template #default="{ row }">
              <span v-if="retainedNavigationLink(navigation,row)">沿用原链接</span>
              <el-button v-else-if="navigation.status==='APPLIED'" link type="primary" :disabled="disabled || busy || needsReload || olderPlan"
                @click="openAppend(row)">复核并追加导航</el-button>
              <el-button v-if="retainedNavigationLink(navigation,row)" link :disabled="disabled || busy" @click="emit('open-page',row.evidencePath)">查看来源</el-button>
              <el-button v-else-if="row.sourcePath" link type="primary" :disabled="disabled || busy || needsReload"
                @click="emit('open-package-page', row.sourcePath)">补审栏目与字段</el-button>
              <el-button v-else link type="primary" :disabled="disabled || busy" @click="emit('manage-columns')">核对栏目层级</el-button>
            </template>
          </el-table-column>
        </el-table>
      </section>
      <div ref="reviewActions" class="navigation-toolbar" role="group" aria-label="导航审核操作" tabindex="-1">
        <el-button v-if="plan.status === 'CONFIRMED' && (navigation.status === 'DRAFT' || (navigation.status === 'CONFIRMED' && dirty))" size="small" type="primary"
          :disabled="disabled || busy || needsReload" @click="submit">确认导航与栏目对应</el-button>
        <el-button v-if="plan.status === 'CONFIRMED' && navigation.status === 'CONFIRMED' && !dirty" size="small" type="primary"
          :disabled="disabled || busy || needsReload" @click="openApplication">{{ workbenchApplyLabel }}</el-button>
        <el-button v-if="navigation.status==='DRAFT' && plan.status==='CONFIRMED' && !dirty && plan.proposal.models.some(model=>model.menuId)"
          :disabled="disabled || busy || needsReload" @click="openApplication">检查上次栏目应用</el-button>
        <el-button v-if="navigation.status === 'APPLYING'" size="small" type="primary"
          :disabled="disabled || busy || needsReload" @click="openApplication">{{ workbenchApplyResumeLabel }}</el-button>
        <el-button v-if="navigation.status === 'APPLIED'" size="small" :disabled="disabled || busy" @click="emit('manage-columns')">打开栏目管理</el-button>
      </div>
      <el-table :data="tree" row-key="key" default-expand-all size="small" max-height="550">
        <el-table-column prop="name" label="导航层级" min-width="208" />
        <el-table-column label="源页面与模板" min-width="190">
          <template #default="{ row }">
            <el-button v-if="row.sourcePath" link type="primary" :disabled="disabled || busy" @click="emit('open-page', row.sourcePath)">{{ row.sourcePath }}</el-button>
            <span v-else>没有独立来源页</span>
            <small v-if="hasSourceConflict(row)" class="navigation-note">来源目标待核对</small>
            <small v-if="edited(row)" class="navigation-note">地址待重新核验</small>
            <small v-else-if="row.proposedUrl" class="navigation-note">预定地址 {{ row.proposedUrl }} · 模板另行配置</small>
          </template>
        </el-table-column>
        <el-table-column label="类型与字段结构" min-width="160">
          <template #default="{ row }">
            {{ kinds[row.kind as SiteNavigationNode['kind']] }}<template v-if="row.kind !== 'DIRECTORY'"> · {{ usesPageFieldReview(navigation,row,rootMenuId) ? '按原页审核字段' : modelName(row.modelKey) }}</template>
            <small v-if="row.kind !== 'DIRECTORY'" class="navigation-note">{{ fieldSummary(row) }}</small>
          </template>
        </el-table-column>
        <el-table-column label="CMS 栏目" min-width="150">
          <template #default="{ row }">
            <span v-if="isHome(row) && (row.menuId || row.reuseMenuId)">站点根栏目 · {{ cmsName(row) }} · #{{ row.menuId || row.reuseMenuId }}</span>
            <span v-else-if="row.menuId">实际：{{ cmsName(row) }} · #{{ row.menuId }}</span>
            <span v-else-if="row.reuseMenuId">拟挂载：{{ cmsName(row) }} · #{{ row.reuseMenuId }}</span>
            <span v-else>{{ row.kind === 'DIRECTORY' ? '待建目录' : '待建内容栏目' }}</span>
            <small v-if="row.drift" class="navigation-note">实际栏目与审核记录不一致</small>
          </template>
        </el-table-column>
        <el-table-column label="栏目内容状态" min-width="180">
          <template #default="{ row }">
            <el-tag size="small" :type="contentTag(row)">{{ contentLabel(row) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" min-width="255">
          <template #default="{ row }">
            <el-button size="small" link type="primary" :disabled="busy" @click="inspect(row)">{{ canEdit(row) ? '核对对应' : '查看证据' }}</el-button>
            <el-button v-if="supportsModelDraft(row)" size="small" link type="primary" :disabled="!canOpenModelDraft(row)"
              @click="openModelSuggestionForNode(row.key,$event)">{{ modelDraftLabel(row) }}</el-button>
            <el-button v-if="supportsPlaceholderModel(row)" size="small" link type="primary" :disabled="!canOpenKindCorrection()"
              @click="confirmPlaceholderModel(row)">按已审原页补建单页模型</el-button>
            <el-button v-if="navigation.status === 'APPLIED' && row.menuId" size="small" link type="primary"
              :disabled="disabled || busy || needsReload" @click="emit('continue-node', row)">在工作台继续</el-button>
            <el-button v-if="supportsKindCorrection(row)" size="small" link type="primary" :disabled="!canOpenKindCorrection()"
              @click="openKindCorrection(row)">复核栏目类型</el-button>
            <el-button v-if="navigation.status === 'APPLIED' && row.menuId && row.kind !== 'DIRECTORY' && !isHome(row)" size="small" link type="primary"
              :disabled="disabled || busy || !!row.drift" @click="row.modelKey ? emit('manage-content', row.menuId) : emit('manage-columns')">{{ row.modelKey ? '内容管理' : '配置字段' }}</el-button>
          </template>
        </el-table-column>
      </el-table>
    </template>
    <el-dialog v-model="inspecting" :title="selected ? '栏目对应 · ' + selected.name : '栏目对应'" width="min(560px, 95vw)" append-to-body>
      <template v-if="selected">
        <dl class="navigation-evidence">
          <dt>导航证据</dt><dd>{{ selected.evidencePath }} · {{ selected.evidenceSelector }}</dd>
          <dt>源链接</dt><dd>{{ selected.sourceHref || '无独立链接' }}</dd>
          <dt>来源页面</dt><dd>{{ selected.sourcePath || '无独立来源页' }}</dd>
          <dt>公开地址</dt><dd>{{ selected.proposedUrl ? '预定 ' + selected.proposedUrl + '，模板另行配置' : '未分配独立地址' }}</dd>
          <template v-if="selected.menuId || selected.reuseMenuId">
            <dt>CMS 栏目名称</dt><dd>{{ cmsName(selected) }} · #{{ selected.menuId || selected.reuseMenuId }}</dd>
          </template>
          <dt>内容状态</dt><dd>{{ contentLabel(selected) }}</dd>
        </dl>
        <p v-if="hasSourceConflict(selected)" role="status">同一入口的来源目标有差异。请核对实际目标页面及内容归属。</p>
        <p v-if="selected.independentLanding">来源页包含独立落地正文。</p>
        <ul v-if="selected.observations?.length" class="navigation-observations" aria-label="来源观察">
          <li v-for="item in selected.observations" :key="observationKey(item)">
            <strong>{{ originLabel(item.origin) }}</strong> · {{ item.path }} · {{ item.selector }}
            <br />{{ item.href || '空链接' }}<template v-if="item.targetPath"> → {{ item.targetPath }}</template>
            · {{ linkKinds[item.linkKind] }}
          </li>
        </ul>
        <p v-if="isHome(selected)">首页绑定站点根栏目。轮播等已有内容继续从原内容栏目维护。</p>
        <el-button v-if="supportsKindCorrection(selected)" size="small" type="primary" plain :disabled="!canOpenKindCorrection()"
          @click="openKindCorrection(selected)">复核栏目类型</el-button>
        <div v-if="supportsModelDraft(selected)" class="navigation-toolbar">
          <el-button size="small" type="primary" plain :disabled="!canOpenModelDraft(selected)"
            @click="openModelSuggestionForNode(selected.key,$event)">{{ modelDraftLabel(selected) }}</el-button>
          <small v-if="dirty || selectionDirty">请先保存并重新审核导航修正。</small>
        </div>
        <el-form v-else-if="canEdit(selected)" label-position="top">
          <el-form-item label="栏目类型">
            <el-select :model-value="selected.kind" aria-label="栏目类型" @update:model-value="changeKind">
              <el-option v-for="(name, kind) in kinds" :key="kind" :value="kind" :label="name"
                :disabled="kind === 'DIRECTORY' && (!hasChildren || selected.independentLanding)" />
            </el-select>
          </el-form-item>
          <el-form-item label="字段结构" v-if="selected.kind !== 'DIRECTORY'">
            <el-select :model-value="selected.modelKey || ''" aria-label="字段结构" filterable @update:model-value="changeModel">
              <el-option value="" label="未匹配模型（字段待配置）" />
              <el-option v-for="model in compatibleModels" :key="model.key" :value="model.key" :label="model.name" />
            </el-select>
          </el-form-item>
          <el-form-item label="CMS 栏目与内容归属" v-if="selected.kind !== 'DIRECTORY'">
            <el-select :model-value="selected.reuseMenuId || 0" aria-label="CMS 栏目与内容归属" @update:model-value="changeReuse($event || null)">
              <el-option :value="0" :label="selected.modelKey ? '新建独立栏目，复制字段结构' : '新建独立栏目，字段待配置'" />
              <el-option v-if="reusableId" :value="reusableId" :disabled="!!reuseBlocker" :label="'挂载已有栏目及内容 · ' + menuLabel(reusableId) + ' · #' + reusableId" />
            </el-select>
            <small v-if="reuseBlocker">{{ reuseBlocker }}</small>
            <el-checkbox v-if="requiresReuseConfirmation" v-model="selected.confirmReuse">我已核对已有栏目及其内容归属，确认挂载到此入口</el-checkbox>
          </el-form-item>
        </el-form>
      </template>
      <template #footer>
        <el-button @click="inspecting = false">关闭</el-button>
        <el-button v-if="selected && canEdit(selected)" type="primary" :disabled="selectionBlocked" @click="saveSelection">保存到导航草稿</el-button>
      </template>
    </el-dialog>
    <el-dialog v-model="continuationOpen" title="接续已应用导航" append-to-body width="min(620px, 95vw)"
      :show-close="!busy" :close-on-click-modal="false" :close-on-press-escape="!busy">
      <p v-if="navigation">来源结构修订 {{ navigation.planRevision }} · 目标结构修订 {{ plan.revision }} · 导航修订 {{ navigation.revision }}</p>
      <p v-if="busy && !continuationPreview" role="status">正在核对现有导航、栏目对应与当前结构。</p>
      <el-alert v-if="continuationError" :title="continuationError" type="error" :closable="false" show-icon />
      <template v-if="continuationPreview">
        <el-alert :title="`可保留 ${continuationPreview.nodeCount} 个导航入口及其已应用栏目。`" type="success" :closable="false" show-icon />
        <template v-if="continuationPreview.modelLinks?.length">
          <p>下列栏目将采用已审核模型。确认后可继续核对栏目字段。</p>
          <ul><li v-for="link in continuationPreview.modelLinks" :key="link.nodeKey">
            {{ navigation?.nodes.find(node=>node.key===link.nodeKey)?.name }}（#{{ link.menuId }}）：
            {{ link.oldModelKey || '尚未关联' }} → {{ plan.proposal.models.find(model=>model.key===link.newModelKey)?.name }}
            <code>{{ link.newModelKey }}</code>
          </li></ul>
        </template>
        <p v-else-if="olderPlan">确认后可接续整包审核并刷新字段状态。</p>
        <template v-if="continuationPreview.modelLinkIssues?.length">
          <p>以下栏目需先核对来源，可在整站结构中继续处理。</p>
          <ul><li v-for="issue in continuationPreview.modelLinkIssues" :key="issue.nodeKey">
            {{ navigation?.nodes.find(node=>node.key===issue.nodeKey)?.name }}（#{{ issue.menuId }}）· {{ issue.message }}
          </li></ul>
        </template>
      </template>
      <template #footer>
        <el-button :disabled="busy" @click="continuationOpen=false">关闭</el-button>
        <el-button v-if="continuationPreview && (olderPlan || continuationPreview.modelLinks?.length)" type="primary" :loading="busy" :disabled="disabled"
          @click="applyContinuation">确认接续已应用导航</el-button>
        <el-button v-else-if="continuationError" :disabled="!canContinueRevision" @click="previewContinuation">重新预览</el-button>
      </template>
    </el-dialog>
    <NavigationAppendDialog v-if="appendEntry && navigation" :key="plan.id+':'+navigation.revision+':'+appendEntry.key"
      :site-id="siteId" :plan="plan" :navigation="navigation" :entry="appendEntry" :disabled="!!disabled"
      :parent-name="parentLabel(appendEntry)" @busy="appendBusy=$event;emit('busy',$event)" @close="appendEntry=undefined"
      @reload-plan="appendEntry=undefined;emit('reload-plan')" @source="path=>{appendEntry=undefined;emit('open-package-page',path)}"
      @completed="(next,nav,key)=>emit('navigation-appended',next,nav,key)" />
    <NavigationKindCorrectionDialog v-if="kindCorrectionNode && navigation" :site-id="siteId" :plan="plan" :navigation="navigation"
      :node="kindCorrectionNode" :disabled="!!disabled" @busy="busy=$event;emit('busy',$event)" @close="closeKindCorrection"
      @source="path=>{kindCorrectionNode=undefined;emit('open-page',path)}" @completed="completeKindCorrection" />
    <NavigationModelDraftDialog v-if="modelDraftNode && navigation" ref="modelDraftEditor" :site-id="siteId" :plan="plan"
      :node="modelDraftNode" :navigation-revision="navigation.revision" @busy="emit('busy',$event)" @close="closeModelDraft"
      @source="openModelDraftSource" @saved="receiveModelDraft" />
  </section>
</template>

<script setup lang="ts">
import { computed, h, nextTick, onBeforeUnmount, ref, watch } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { siteModelApi, retainedNavigationLink, usesPageFieldReview, workbenchApplyLabel, workbenchApplyResumeLabel, type NavigationContinuationPreview, type ParentEntryDecision, type SiteModelPlan, type SiteNavigation, type SiteNavigationEvidence,
  type SiteNavigationNode, type SiteNavigationObservation } from '@/api/siteModel';
import ParentEntryDecisionEditor from './ParentEntryDecisionEditor.vue';
import NavigationModelDraftDialog from './NavigationModelDraftDialog.vue';
import NavigationAppendDialog from './NavigationAppendDialog.vue';
import NavigationKindCorrectionDialog from './NavigationKindCorrectionDialog.vue';

type NavigationState = SiteNavigation['status'] | 'loading' | 'unavailable' | 'empty';
type TreeRow = SiteNavigationNode & { children: TreeRow[] };

const props = defineProps<{ siteId: number; plan: SiteModelPlan; menuLabel: (id: number) => string; rootMenuId?: number; disabled?: boolean;
  focusTarget?: { nodeKey?: string | null; menuId?: number | null; sourcePath?: string | null; detailPath?:string } | null }>();
const emit = defineEmits<{
  state: [NavigationState]; busy: [boolean]; 'open-page': [string]; 'manage-content': [number];
  'manage-columns': []; 'continue-node': [SiteNavigationNode];
  'continue-list': [SiteNavigationNode,string|undefined];
  navigation: [SiteNavigation | null];
  'navigation-appended': [plan:SiteModelPlan,navigation:SiteNavigation,nodeKey:string];
  'navigation-kind-corrected': [plan:SiteModelPlan,navigation:SiteNavigation,nodeKey:string,detailPath:string|undefined];
  'navigation-continued': [plan:SiteModelPlan,navigation:SiteNavigation];
  'reload-plan': [];
  'model-draft': [next:SiteModelPlan,modelKey:string,nodeKey:string];
  application:[plan:SiteModelPlan,navigation:SiteNavigation];
  'open-package-page': [path:string];
}>();
const kinds = { DIRECTORY: '目录', SINGLE: '单页', LIST: '列表' };
const navigationStates = { DRAFT: '导航待审核', CONFIRMED: '导航已审核', APPLYING: '应用结果待核对', APPLIED: '已审核栏目已应用' };
const origins: Record<string, string> = {
  HEADER: '页眉', FOOTER: '页脚', PAGE_CATEGORY: '页内分类', BODY_ACTION: '正文入口',
  HEADER_SEARCH: '页眉搜索', BODY_SEARCH: '正文搜索'
};
const linkKinds = { LOCAL_PAGE: '站内页面', ANCHOR: '锚点', PLACEHOLDER: '占位', EXTERNAL: '外链', UNRESOLVED: '目标未核实' };
const navigation = ref<SiteNavigation>();
const appendEntry=ref<SiteNavigationEvidence>();
const appendBusy=ref(false);
const kindCorrectionNode=ref<SiteNavigationNode>();
function parentLabel(entry:SiteNavigationEvidence) {
  if (!entry.parentKey) return '站点一级导航';
  return evidenceNames.value.get(entry.parentKey) || '待核对的上级入口';
}
function openAppend(entry:SiteNavigationEvidence){
  if(!props.disabled && !busy.value && !needsReload.value && !olderPlan.value && navigation.value?.status==='APPLIED') appendEntry.value=entry;
}
const nodes = ref<SiteNavigationNode[]>([]);
const loading = ref(false), busy = ref(false), error = ref(''), needsReload = ref(false);
const inspecting = ref(false), selected = ref<SiteNavigationNode>();
const modelDraftNode=ref<SiteNavigationNode>();
const modelDraftEditor=ref<InstanceType<typeof NavigationModelDraftDialog>|null>(null);
let modelDraftTrigger:HTMLElement|null=null, returnToInspection='';
const focusHeading = ref<HTMLElement | null>(null);
const entryEditor=ref<InstanceType<typeof ParentEntryDecisionEditor>|null>(null);
const focusedEntry=ref<ParentEntryDecision>();
const supplementalEvidence = computed(() => navigation.value?.supplementalEvidence ?? []);
const state = computed<NavigationState>(() => loading.value ? 'loading' : !navigation.value || needsReload.value
  ? 'unavailable' : navigation.value.nodes.length || supplementalEvidence.value.length ? navigation.value.status : 'empty');
const olderPlan = computed(() => !!navigation.value && navigation.value.planRevision < props.plan.revision);
const continuationOpen=ref(false), continuationError=ref(''), continuationActions=ref<HTMLElement|null>(null);
const continuationPreview=ref<NavigationContinuationPreview>();
const modelLinkPending = computed(() => navigation.value?.nodes.some(node => node.menuId && node.kind!=='DIRECTORY' &&
  props.plan.proposal.models.some(model=>model.menuId===node.menuId && model.key!==node.modelKey && model.kind===node.kind)));
const canContinueRevision = computed(() => (olderPlan.value || modelLinkPending.value) && navigation.value?.status==='APPLIED' && props.plan.status==='CONFIRMED' &&
  !props.disabled && !busy.value && !loading.value && !needsReload.value && !entryEditor.value?.hasUnsavedChanges() && !entryEditor.value?.isSaving());
const dirty = computed(() => !!navigation.value && JSON.stringify(navigation.value.nodes) !== JSON.stringify(nodes.value));
const selectionDirty=computed(()=>!!(inspecting.value && selected.value &&
  JSON.stringify(selected.value)!==JSON.stringify(nodes.value.find(node=>node.key===selected.value?.key))));
const models = computed(() => props.plan.proposal.models);
const compatibleModels = computed(() => models.value.filter(model => (model.kind === 'SHARED' ? 'LIST' : model.kind) === selected.value?.kind));
const reusableId = computed(() => findModel(selected.value?.modelKey || null)?.menuId);
const hasChildren = computed(() => nodes.value.some(node => node.parentKey === selected.value?.key));
const reuseBlocker = computed(() => {
  const node = selected.value;
  if (!node || !reusableId.value) return '';
  if (nodes.value.some(other => other.key !== node.key && other.reuseMenuId === reusableId.value))
    return '该栏目已对应其他导航节点。可以复制字段结构并新建独立栏目。';
  const regionSources = props.plan.proposal.regions
    .filter(region => region.modelKey === node.modelKey && region.mode !== 'DETAIL')
    .map(region => region.sourcePath);
  const homeSources = new Set(nodes.value.filter(isHome).map(home => home.sourcePath));
  if (!node.sourcePath || !regionSources.includes(node.sourcePath) || regionSources.every(path => homeSources.has(path)))
    return '该已有栏目缺少与此入口对应的来源证据。可以复制字段结构并新建独立栏目。';
  return '';
});
const requiresReuseConfirmation = computed(() => {
  const node = selected.value;
  if (!node?.reuseMenuId || reuseBlocker.value) return false;
  const saved = serverNodes.value.get(node.key);
  return saved?.reuseMenuId !== node.reuseMenuId || saved?.modelKey !== node.modelKey || !!saved?.confirmReuse;
});
const selectionBlocked = computed(() => !!selected.value?.reuseMenuId &&
  (!!reuseBlocker.value || (requiresReuseConfirmation.value && !selected.value.confirmReuse)));
let request = 0;

const tree = computed(() => {
  const byKey = new Map<string, TreeRow>();
  const roots: TreeRow[] = [];
  const sorted = [...nodes.value].sort((a, b) => a.order - b.order);
  sorted.forEach(node => byKey.set(node.key, { ...node, children: [] }));
  for (const node of sorted) {
    const parent = node.parentKey && byKey.get(node.parentKey);
    (parent ? parent.children : roots).push(byKey.get(node.key)!);
  }
  return roots;
});
const primaryCount = computed(() => tree.value.filter(node => !isHome(node)).length);
const secondaryCount = computed(() => tree.value.reduce((count, node) => count + node.children.length, 0));
const deeperCount = computed(() => Math.max(0, nodes.value.length - tree.value.length - secondaryCount.value));
const boundCount = computed(() => nodes.value.filter(node => node.menuId && !node.drift).length);
const serverNodes = computed(() => new Map(navigation.value?.nodes.map(node => [node.key, node])));
const withContentCount = computed(() => nodes.value.filter(hasContent).length);
const pendingCount = computed(() => nodes.value.filter(node => node.kind !== 'DIRECTORY' && !hasContent(node)).length);
const conflictingCount = computed(() => nodes.value.filter(hasSourceConflict).length);
const evidenceNames = computed(() => new Map([...nodes.value, ...supplementalEvidence.value].map(item => [item.key, item.name])));
const navigationMatches = computed(() => {
  const target = props.focusTarget;
  if (!target || !navigation.value || loading.value || needsReload.value) return [];
  if (target.nodeKey) return nodes.value.filter(node => node.key === target.nodeKey);
  if (!target.menuId && !target.sourcePath) return [];
  return nodes.value.filter(node =>
    (!target.menuId || (node.menuId ?? node.reuseMenuId) === target.menuId) &&
    (!target.sourcePath || node.sourcePath === target.sourcePath));
});
const focusedNode = computed(() => navigationMatches.value.length === 1 ? navigationMatches.value[0] : undefined);
const entryScope = computed(() => {
  const node=focusedNode.value, snapshot=navigation.value;
  if(!props.rootMenuId || !node?.menuId || node.menuId===props.rootMenuId || node.drift || props.plan.status!=='CONFIRMED' ||
    snapshot?.status!=='APPLIED' || olderPlan.value || snapshot.originPlanId || !snapshot.nodes.some(child=>child.parentKey===node.key))return;
  return {node,snapshot};
});
const focusedEvidence = computed(() => {
  const target = props.focusTarget;
  if (!target || !navigation.value || loading.value || needsReload.value || navigationMatches.value.length) return;
  const matches = supplementalEvidence.value.filter(item => target.nodeKey ? item.key === target.nodeKey
    : !target.menuId && !!target.sourcePath && item.sourcePath === target.sourcePath);
  return matches.length === 1 ? matches[0] : undefined;
});
const focusNext = computed(() => {
  if (loading.value) return '正在读取当前计划中的导航对应。';
  if (state.value === 'unavailable') return '先刷新导航与内容状态，再核对清单目标。';
  if (navigation.value?.status==='APPLIED' && olderPlan.value) return '先接续已应用导航，再继续核对这个入口。';
  if (focusedEvidence.value && navigation.value && retainedNavigationLink(navigation.value,focusedEvidence.value))
    return '此入口沿用原地址，发布后可在链接核验中检查。';
  if (focusedEvidence.value) return '此入口来自原页导航。先核对追加范围和栏目归属，再继续字段与模板审核。';
  const node = focusedNode.value;
  if (!node) return navigationMatches.value.length > 1
    ? '多个入口符合来源，请在导航表核对各自的栏目与原页。'
    : '当前导航未找到此目标。请核对清单使用的结构计划和原始页面。';
  if (navigation.value?.status === 'APPLIED') {
    if (node.drift) return '已应用的对应与当前栏目配置不一致，先核对入口证据并在栏目管理修正。';
    if (node.kind === 'DIRECTORY') return '当前按目录维护，先核对是否有独立落地页；栏目类型可在栏目管理修正。';
    if (!node.sourcePath) return '尚缺可靠原页，先核对入口证据和实际用途。';
    if (supportsListReview(node)) return '先核对同栏目的详情字段计划；字段确认后继续审核列表卡片与分页。';
    return '核对本入口绑定的原页与栏目，再继续该栏目的字段、内容或模板。';
  }
  if (props.plan.status !== 'CONFIRMED') return '先确认下方的整站结构计划，再审核本入口的栏目对应。';
  if (navigation.value?.status === 'APPLYING') return '按已审核修订核对或重试栏目应用，再继续本入口。';
  if (navigation.value?.status === 'CONFIRMED' && !dirty.value) return '核对证据后，可通过栏目应用按钮创建或挂载已审核栏目。';
  return '核对本入口的来源、字段结构与 CMS 栏目，再提交导航审核。';
});
watch([focusHeading, () => props.focusTarget], ([heading]) => {
  heading?.focus({ preventScroll: true });
  heading?.scrollIntoView({ block: 'nearest' });
}, { flush: 'post' });

function evidenceName(item: SiteNavigationEvidence) {
  const parent = item.parentKey && evidenceNames.value.get(item.parentKey);
  return parent ? `${parent} / ${item.name}` : item.name;
}
function originLabel(origin: string) { return origins[origin] ?? origin; }
function observationKey(item: SiteNavigationObservation) {
  return JSON.stringify([item.origin, item.path, item.selector, item.href]);
}
function hasSourceConflict(entry: SiteNavigationEvidence) {
  const targets = new Set((entry.observations ?? []).flatMap(item => {
    if (!item.targetPath) return [];
    const suffix = item.href.match(/[?#].*$/)?.[0] ?? '';
    return [item.targetPath + suffix];
  }));
  return targets.size > 1 || (targets.size === 1 && !entry.sourcePath);
}
function isHome(node: SiteNavigationNode) { return !node.parentKey && node.name === '首页'; }
function supportsKindCorrection(node:SiteNavigationNode){
  return navigation.value?.status==='APPLIED' && node.kind==='SINGLE' && !!node.menuId &&
    node.menuId!==props.rootMenuId && !isHome(node) && !nodes.value.some(child=>child.parentKey===node.key);
}
function supportsListReview(node:SiteNavigationNode){
  return navigation.value?.status==='APPLIED' && node.kind==='LIST' && !!node.menuId && !!node.sourcePath && !node.drift;
}
function canOpenKindCorrection(){
  return props.plan.status==='CONFIRMED' && !props.disabled && !busy.value && !loading.value && !needsReload.value && !olderPlan.value;
}
function openKindCorrection(node:SiteNavigationNode){
  if(!supportsKindCorrection(node) || !canOpenKindCorrection())return;
  inspecting.value=false;
  kindCorrectionNode.value=node;
}
function closeKindCorrection(attempted:boolean){
  kindCorrectionNode.value=undefined;
  if(attempted)emit('reload-plan');
}
function completeKindCorrection(next:SiteModelPlan,nav:SiteNavigation,nodeKey:string,detailPath?:string){
  kindCorrectionNode.value=undefined;
  emit('navigation-kind-corrected',next,nav,nodeKey,detailPath);
}
function cmsName(node: SiteNavigationNode) {
  const id = node.menuId || node.reuseMenuId;
  return (!edited(node) && node.cmsName) || (id ? props.menuLabel(id) : '');
}
function findModel(key: string | null) { return models.value.find(model => model.key === key); }
function modelName(key: string | null) { return key ? findModel(key)?.name || key : '未匹配模型'; }
function fieldSummary(node: SiteNavigationNode) {
  if (usesPageFieldReview(navigation.value,node,props.rootMenuId))
    return hasContent(node) ? '已有栏目内容，可继续核对字段映射' : '确认维护栏目后，可复用或新增字段';
  const model = findModel(node.modelKey);
  return model ? `计划字段 ${model.fields.length} 个 · 已选槽位 ${model.fields.filter(field => field.fieldName).length} 个` : '字段待配置';
}
function edited(node: SiteNavigationNode) {
  const saved = serverNodes.value.get(node.key);
  return !!saved && (node.kind !== saved.kind || node.modelKey !== saved.modelKey || node.reuseMenuId !== saved.reuseMenuId);
}
function hasContent(node: SiteNavigationNode) {
  return !node.drift && !edited(node) && node.contentStatus === 'HAS_CONTENT';
}
function contentLabel(node: SiteNavigationNode) {
  if (node.drift) return '栏目对应待复核';
  if (node.kind === 'DIRECTORY') return '目录 · 内容见下级';
  if (edited(node)) return '对应变更 · 待重新核验';
  if (hasContent(node)) return '已有内容 · 完整性待核对';
  if (node.contentStatus === 'MISSING_SOURCE') return '缺可靠内容来源 · 待补';
  return node.modelKey ? '尚未导入 · 核对内容归属' : '来源内容待匹配';
}
function contentTag(node: SiteNavigationNode) {
  if (node.drift) return 'warning';
  if (hasContent(node)) return 'info';
  return node.kind === 'DIRECTORY' ? 'info' : 'warning';
}
function canEdit(node: SiteNavigationNode) {
  return !props.disabled && !busy.value && !needsReload.value && props.plan.status === 'CONFIRMED' &&
    isReviewable(navigation.value) && !isHome(node);
}
function isReviewable(current?: SiteNavigation) { return current?.status === 'DRAFT' || current?.status === 'CONFIRMED'; }
function inspect(node: SiteNavigationNode) {
  selected.value = { ...nodes.value.find(item => item.key === node.key)! };
  inspecting.value = true;
}
function supportsModelDraft(node:SiteNavigationNode){return !!node.sourcePath && node.kind!=='DIRECTORY' && !isHome(node);}
function supportsPlaceholderModel(node:SiteNavigationNode) {
  return supportsKindCorrection(node) && !node.drift && !node.sourcePath && !node.modelKey && /^#+$/.test(node.sourceHref || '')
    && !props.plan.proposal.models.some(model => model.menuId === node.menuId);
}
async function confirmPlaceholderModel(node:SiteNavigationNode) {
  const nav = navigation.value;
  if (!nav || !supportsPlaceholderModel(node) || !canOpenKindCorrection()) return;
  const {siteId, plan} = props;
  busy.value = true;
  emit('busy', true);
  try {
    const preview = await siteModelApi.previewPlaceholderModel(siteId, plan, nav.revision, node.key);
    if (preview.menuId !== node.menuId || !preview.digest || !preview.fields.length) throw new Error('补建预览与当前栏目不一致');
    await ElMessageBox.confirm(h('div', [
      h('p', `${preview.nodeName} #${preview.menuId} · ${preview.sourcePath}`),
      h('ul', preview.fields.map(field => h('li', `${field.name} · ${field.type} · ${field.value.slice(0, 90)}`))),
      h('p', '确认后将保存结构修订。字段和发布继续使用工作台现有步骤。'),
    ]), '核对占位单页模型', {confirmButtonText:'确认保存结构修订',cancelButtonText:'返回'});
    const result = await siteModelApi.confirmPlaceholderModel(siteId, plan, nav.revision, node.key, preview.digest);
    if (result.id !== plan.id || result.revision !== plan.revision + 1) throw new Error('补建回执与当前结构修订不一致');
    ElMessage.success('已保存单页模型。请接续整包审核及已应用导航。');
    emit('reload-plan');
  } catch (cause) {
    if (cause !== 'cancel' && cause !== 'close') ElMessage.error(cause instanceof Error ? cause.message : '无法补建单页模型');
  } finally {
    busy.value = false;
    emit('busy', false);
  }
}
function modelDraftLabel(node:SiteNavigationNode){
  if (usesPageFieldReview(navigation.value,node,props.rootMenuId)) return '审核本栏目的原页字段';
  if (navigation.value?.status==='APPLIED' && node.menuId && !node.modelKey)
    return '核对原页与栏目归属';
  return node.modelKey ? '从页面建议补充字段' : '从页面建议建立模型';
}
function canOpenModelDraft(node:SiteNavigationNode){
  return supportsModelDraft(node) && props.plan.status==='CONFIRMED' && !!navigation.value && navigation.value.status!=='APPLYING' &&
    !(navigation.value.status==='APPLIED' && olderPlan.value) && !props.disabled && !busy.value && !loading.value && !needsReload.value && !dirty.value && !selectionDirty.value && !node.drift;
}
function openModelSuggestionForNode(nodeKey:string,event?:MouseEvent){
  const node=nodes.value.find(item=>item.key===nodeKey);
  if(!node || !canOpenModelDraft(node))return false;
  if(usesPageFieldReview(navigation.value,node,props.rootMenuId)){
    inspecting.value=false;
    emit('continue-node',node);
    return true;
  }
  if(navigation.value?.status==='APPLIED' && node.menuId && !node.modelKey && node.sourcePath){
    inspecting.value=false;
    emit('open-package-page',node.sourcePath);
    return true;
  }
  modelDraftTrigger=event?.currentTarget instanceof HTMLElement ? event.currentTarget : null;
  returnToInspection=inspecting.value ? node.key : '';
  inspecting.value=false;modelDraftNode.value={...node};return true;
}
async function closeModelDraft(){
  modelDraftNode.value=undefined;
  const inspected=nodes.value.find(node=>node.key===returnToInspection);
  returnToInspection='';
  if(inspected){inspect(inspected);return;}
  await nextTick();
  if(modelDraftTrigger?.isConnected)modelDraftTrigger.focus();
  else focusReviewActions();
  modelDraftTrigger=null;
}
function openModelDraftSource(path:string){returnToInspection='';void closeModelDraft();emit('open-package-page',path);}
function receiveModelDraft(plan:SiteModelPlan,modelKey:string,nodeKey:string){
  if(modelDraftNode.value?.key!==nodeKey)return;
  modelDraftNode.value=undefined;returnToInspection='';modelDraftTrigger=null;
  emit('model-draft',plan,modelKey,nodeKey);
}
function hasUnsavedChanges() {
  const draft=selected.value;
  return modelDraftEditor.value?.hasUnsavedChanges() || entryEditor.value?.hasUnsavedChanges() || dirty.value || !!(inspecting.value && draft &&
    JSON.stringify(draft)!==JSON.stringify(nodes.value.find(node=>node.key===draft.key)));
}
const reviewActions=ref<HTMLElement|null>(null);
function focusReviewActions() {
  const actions=continuationActions.value || reviewActions.value;
  if (!actions) return false;
  const target=actions.querySelector<HTMLElement>('button:not(:disabled)') || actions;
  target.focus({preventScroll:true});
  target.scrollIntoView({block:'center'});
  return true;
}
defineExpose({hasUnsavedChanges,focusReviewActions,openModelSuggestionForNode});
function changeKind(kind: SiteNavigationNode['kind']) {
  if (!selected.value || kind === selected.value.kind) return;
  selected.value.kind = kind;
  changeModel('');
}
function changeModel(key: string) {
  if (!selected.value) return;
  selected.value.modelKey = key || null;
  changeReuse(null);
}
function changeReuse(id: number | null) {
  if (!selected.value || selected.value.reuseMenuId === id) return;
  selected.value.reuseMenuId = id;
  selected.value.confirmReuse = false;
}
function saveSelection() {
  const edited = selected.value;
  if (!edited || !canEdit(edited) || selectionBlocked.value) return;
  nodes.value = nodes.value.map(node => node.key === edited.key ? { ...edited } : node);
  inspecting.value = false;
}
function receive(result: SiteNavigation) {
  if (result.planRevision > props.plan.revision || (result.planRevision < props.plan.revision && isReviewable(result)))
    throw new Error('内容模型计划已变化，请重新读取整站结构。');
  navigation.value = result;
  emit('navigation', result);
  nodes.value = result.nodes.map(node => ({ ...node }));
  needsReload.value = false;
}
async function load() {
  const current = ++request;
  loading.value = true;
  inspecting.value = false;
  error.value = '';
  try {
    const result = await siteModelApi.navigation(props.siteId, props.plan);
    if (current === request) receive(result);
  } catch (cause) {
    if (current === request) {
      needsReload.value = true;
      emit('navigation', null);
      error.value = cause instanceof Error ? cause.message : '读取导航失败';
    }
  } finally {
    if (current === request) loading.value = false;
  }
}
async function reload() {
  if(continuationOpen.value || appendEntry.value || kindCorrectionNode.value || appendBusy.value || modelDraftNode.value || modelDraftEditor.value?.isSaving())return;
  if ((dirty.value || entryEditor.value?.hasUnsavedChanges()) && !await confirmAction('刷新会放弃尚未保存的导航对应或入口职责修正。', '刷新导航')) return;
  if (!props.disabled && !busy.value && !entryEditor.value?.isSaving()) await load();
}
async function previewContinuation() {
  const snapshot=navigation.value, plan=props.plan, token=request;
  if(!snapshot || !canContinueRevision.value)return;
  continuationOpen.value=true;
  continuationError.value='';
  continuationPreview.value=undefined;
  busy.value=true;
  emit('busy',true);
  try {
    const result=await siteModelApi.previewNavigationContinuation(props.siteId,plan,{
      packageVersion:plan.packageVersion,planRevision:plan.revision,navigationRevision:snapshot.revision,
    });
    if(token!==request)return;
    // 已接续的导航在页面展示生效修订，持久化的来源回执仍携带起始修订。
    const sameRevision=snapshot.planRevision===plan.revision;
    const validSourceRevision=result.sourcePlanRevision===snapshot.planRevision || (sameRevision &&
      Number.isInteger(result.sourcePlanRevision) && result.sourcePlanRevision>0 &&
      result.sourcePlanRevision<snapshot.planRevision && !!(result.modelLinks?.length || result.modelLinkIssues?.length));
    if(!validSourceRevision || result.planRevision!==plan.revision || result.navigationRevision!==snapshot.revision || !result.previewDigest)
      throw new Error('导航或结构修订已变化，请关闭窗口并刷新导航。');
    continuationPreview.value=result;
  } catch(cause) {
    if(token===request)continuationError.value=cause instanceof Error ? cause.message : '无法核对导航接续范围。';
  } finally {
    busy.value=false;
    emit('busy',false);
  }
}
async function applyContinuation() {
  const preview=continuationPreview.value, plan=props.plan, token=request;
  if(!preview || !canContinueRevision.value)return;
  continuationError.value='';
  busy.value=true;
  emit('busy',true);
  let updated: SiteNavigation | undefined;
  try {
    const result=await siteModelApi.applyNavigationContinuation(props.siteId,plan,{
      packageVersion:plan.packageVersion,planRevision:preview.planRevision,navigationRevision:preview.navigationRevision,
      previewDigest:preview.previewDigest,confirmed:true,
    });
    if(token!==request)return;
    if(result.status!=='APPLIED' || result.planRevision!==plan.revision || result.revision!==preview.navigationRevision)
      throw new Error('导航接续结果需重新核实，请关闭窗口并刷新导航。');
    continuationOpen.value=false;
    receive(result);
    updated=result;
  } catch(cause) {
    if(token===request) {
      continuationPreview.value=undefined;
      continuationError.value=(cause instanceof Error ? cause.message : '接续结果未能确认。')+' 请刷新导航并重新核对。';
    }
  } finally {
    busy.value=false;
    emit('busy',false);
  }
  if(updated)emit('navigation-continued',plan,updated);
}
async function confirmAction(message: string, title: string) {
  try { await ElMessageBox.confirm(message, title, { type: 'warning' }); return true; }
  catch { return false; }
}
function openApplication(){
  const current=navigation.value;
  if(!current || props.disabled || busy.value || needsReload.value || dirty.value || props.plan.status!=='CONFIRMED' ||
      !['DRAFT','CONFIRMED','APPLYING'].includes(current.status))return;
  emit('application',props.plan,current);
}
async function submit() {
  const current = navigation.value, plan = props.plan, scope = request;
  if (!current || props.disabled || busy.value || needsReload.value) return;
  const allowed = () => plan.status === 'CONFIRMED' && isReviewable(current);
  if (!allowed()) return;
  const title='确认导航与栏目对应';
  const message='保存本次审核的导航对应关系，随后可核对栏目与字段应用的影响范围。';
  if (!await confirmAction(message, title) || request !== scope || props.plan !== plan ||
      props.disabled || busy.value || needsReload.value || navigation.value !== current || !allowed()) return;
  busy.value = true;
  emit('busy', true);
  error.value = '';
  try {
    const result=await siteModelApi.reviewNavigation(props.siteId,plan,current.revision,nodes.value);
    if (request !== scope) return;
    receive(result);
  } catch (cause) {
    if (request === scope) {
      needsReload.value = true;
      error.value = (cause instanceof Error ? cause.message : '导航操作未确认') + '。请刷新导航后核对结果。';
    }
  } finally {
    busy.value = false;
    emit('busy', false);
  }
}
watch(state, value => emit('state', value), { immediate: true });
watch(() => [props.siteId, props.plan.id, props.plan.packageVersion, props.plan.revision], () => {
  continuationOpen.value=false;
  appendEntry.value=undefined;
  kindCorrectionNode.value=undefined;
  navigation.value = undefined;
  nodes.value = [];
  void load();
}, { immediate: true });
onBeforeUnmount(() => { request++; emit('busy', false); });
</script>

<style scoped>
.navigation-panel { border: 1px solid var(--el-border-color-lighter); border-radius: var(--admin-card-radius); padding: var(--admin-card-padding-compact); margin-bottom: var(--admin-card-gap); }
.navigation-panel p { color: var(--el-text-color-regular); }
.navigation-focus { border-inline-start: 3px solid var(--el-color-primary); padding-inline-start: 12px; margin-block: 12px; }
.navigation-focus h4 { margin-block: 0 6px; overflow-wrap: anywhere; }
.navigation-toolbar { display: flex; flex-wrap: wrap; align-items: center; gap: 8px 12px; margin-bottom: 8px; }
.navigation-toolbar h3 { margin: 0; }
.navigation-note { display: block; color: var(--el-text-color-secondary); overflow-wrap: anywhere; }
.navigation-evidence { display: grid; grid-template-columns: max-content 1fr; gap: 6px 12px; margin: 0 0 16px; }
.navigation-evidence dt { font-weight: 600; }
.navigation-evidence dd { margin: 0; overflow-wrap: anywhere; }
.navigation-supplement { margin-block: 12px; }
.navigation-supplement h4 { margin: 0; }
.navigation-observations { padding-inline-start: 20px; overflow-wrap: anywhere; }
.navigation-observations li + li { margin-top: 8px; }
</style>
