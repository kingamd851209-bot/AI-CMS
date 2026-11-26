<template>
  <el-card shadow="never" class="domain-agent">
    <template #header><strong>领域 Agent · 单页待审核方案</strong></template>
    <p>读取当前页面证据、栏目与字段，经工具校验后提供建议。不会创建栏目、字段或内容；采纳后仍须人工保存。</p>
    <el-alert title="任务保存在服务内存中；服务重启后无法恢复。离开页面仅停止轮询，不代表远端任务已取消。" type="info" :closable="false" />
    <p v-if="!menuId">尚未选择栏目：可先分析页面，审核建立栏目与字段的建议。</p>
    <div class="domain-agent-actions">
      <el-button type="primary" :disabled="disabled || running || unresolved || !siteId || !sourcePath" @click="start">{{ requestId && !task ? '重试确认同一任务' : '启动领域 Agent' }}</el-button>
      <el-button :disabled="disabled || !sourcePath" :loading="checkingEvidence" @click="checkEvidence">查看本包识别依据</el-button>
      <el-button v-if="unresolved && !running" @click="resume">继续查询任务</el-button>
      <el-button v-if="unresolved && task?.taskId" :loading="cancelling" @click="cancel">取消任务</el-button>
      <el-button v-if="result" :disabled="disabled || running" :loading="adopting" @click="adoptAndReview()">采纳到草稿并审核</el-button>
      <span v-if="task" role="status">{{ stateLabel }} · 总时限 {{ Math.ceil(task.timeoutMs / 1000) }} 秒</span>
    </div>
    <p v-if="task && progressMessage" role="status">{{ progressMessage }}</p>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-alert v-if="evidenceError" :title="evidenceError" type="warning" :closable="false" />
    <section v-if="recognition" aria-label="页面关系与绑定建议" class="domain-agent-media">
      <strong>页面关系与绑定建议（{{ recognition.suggestions.length }}）</strong>
      <p v-if="!recognition.suggestions.length">当前包内证据尚不足以确认页面关系，可继续审核页面原文。</p>
      <p v-if="recognition.excludedSourceIds.length">{{ recognition.excludedSourceIds.length }} 处显示值由模板根据记录生成。</p>
      <details v-for="item in recognition.suggestions" :key="[item.kind,item.path,item.selector,item.targetPath].join('|')">
        <summary>{{ kindLabel(item.kind) }} · {{ item.targetPath || item.path }}</summary>
        <p>{{ item.reason }}</p>
        <p v-if="item.kind==='NAV_EXPANSION_REVIEW' && item.facts.panelSelector">导航面板 <code>{{ item.facts.panelSelector }}</code></p>
        <p v-if="item.kind==='NAV_EXPANSION_REVIEW' && Array.isArray(item.facts.localScriptCandidates) && item.facts.localScriptCandidates.length">需核对的包内脚本：{{ item.facts.localScriptCandidates.join('、') }}</p>
        <p v-if="Array.isArray(item.facts.labels)">原页标签 {{ item.facts.labels.join('、') }} · 默认记录 {{ item.facts.sourceRows }} 条</p>
        <p v-if="item.kind==='MAINTAINABLE_CARD_LIST' && Array.isArray(item.facts.cardTitles)">卡片标题 {{ item.facts.cardTitles.join('、') }}</p>
        <p v-if="item.kind==='MAINTAINABLE_CARD_LIST' && item.facts.directorySelector">同组目录 <code>{{ item.facts.directorySelector }}</code></p>
        <p v-if="item.facts.recordComparison==='MATCHING'">核对 {{ item.facts.checkedFragmentCount }} 份片段，每份与默认列表有相同的 {{ item.facts.matchingRecordCount }} 条记录。{{ item.facts.orderOnlyChange ? '部分片段调整了顺序。' : '' }}请按记录原文核对分组归属。</p>
        <p v-else-if="item.facts.recordComparison">片段中的记录或资源还需核对，确认后再提取初值。</p>
        <p>来源 <code>{{ item.path }}</code> <code>{{ item.selector }}</code></p>
        <el-button link type="primary" :disabled="disabled" @click="emit('inspect-relation',item)">{{ reviewLabel(item) }}</el-button>
      </details>
      <p v-for="warning in recognition.warnings" :key="warning" role="status">{{ warning }}</p>
    </section>
    <details v-if="steps.length" class="domain-agent-media" open>
      <summary>查看 Agent 工具步骤（{{ steps.length }}）</summary>
      <ol aria-label="Agent 工具步骤"><li v-for="(step,index) in steps" :key="index">第 {{ step.round }} 轮 · {{ toolLabel(step.tool) }} · {{ stepStatusLabel(step.status) }}：{{ step.message }}</li></ol>
    </details>
    <template v-if="result">
      <p><strong>页面角色：</strong>{{ result.pageRole }}；{{ result.summary }}</p>
      <p>已观察来源 {{ result.observedSources }} / {{ result.totalSources }}；未读取 {{ Math.max(0,result.totalSources-result.observedSources) }}。方案引用 {{ referencedSources }} 项不同来源。已读取不等于已纳入方案；来源不必逐项维护，此统计不代表动态页面完整性。</p>
      <VisualBindingSuggestions v-if="previewActive" title="领域 Agent · 原页面建议审核" :site-id="siteId"
        :source-path="sourcePath" :package-version="result.packageVersion" :digest="result.digest" :revision="result.revision"
        :units="result.units" :sources="sources" :mappings="result.mappings" :disabled="disabled || adopting"
        footer-notice="这是 Agent 建议。采纳到本地草稿后，才能批量修正并保存整页内容单元。">
        <template #actions="{ close }">
          <el-button type="primary" :disabled="disabled" :loading="adopting" @click="adoptAndReview(close)">采纳到草稿并审核</el-button>
        </template>
      </VisualBindingSuggestions>
      <details class="domain-agent-media">
        <summary>查看维护单元及来源证据表</summary>
        <el-table :data="result.units" size="small"><el-table-column prop="name" label="维护单元"/><el-table-column prop="fieldType" label="建议类型"/><el-table-column label="来源证据与原文片段" min-width="240"><template #default="{row}"><div v-for="(source,index) in row.sources" :key="index" class="domain-agent-evidence"><span>{{ source.sourceId }} [{{ source.start }},{{ source.end }})</span><div class="domain-agent-raw">{{ evidenceText(source) }}</div></div><span v-if="!row.sources.length">未提供来源证据</span></template></el-table-column><el-table-column prop="doubts" label="不确定项"/></el-table>
      </details>
      <el-collapse class="domain-agent-media">
        <el-collapse-item name="unreferenced-media" :title="`未纳入方案的媒体（${unreferencedMedia.length}）`">
          <p>以下媒体已存在于页面来源中，但未被方案独立引用，也未落在可明确定位的完整富文本内部；这不代表识别失败。请人工确认其属于业务素材、装饰或公共区，本清单不会自动创建字段。</p>
          <p v-if="!sources">页面来源尚不可用，暂无法核对未纳入方案的媒体。</p>
          <el-table v-else :data="unreferencedMedia" size="small" empty-text="没有未纳入方案的媒体来源">
            <el-table-column prop="id" label="来源 ID"/>
            <el-table-column prop="kind" label="类型"/>
            <el-table-column label="原文 URL" min-width="240"><template #default="{row}"><span class="domain-agent-raw">{{ row.rawValue || '原文 URL 为空' }}</span></template></el-table-column>
            <el-table-column label="定位 selector" min-width="200"><template #default="{row}"><span class="domain-agent-raw">{{ row.selector || '未提供 selector' }}</span></template></el-table-column>
          </el-table>
        </el-collapse-item>
      </el-collapse>
      <details class="domain-agent-media"><summary>查看字段映射表</summary>
        <el-table :data="result.mappings" size="small"><el-table-column prop="unitId" label="内容单元"/><el-table-column label="建议动作"><template #default="{row}">{{ actionLabel(row.action) }}</template></el-table-column><el-table-column prop="fieldName" label="建议字段"/><el-table-column prop="reason" label="判断依据"/></el-table>
      </details>
      <p>当前采纳仅包含内容单元；字段映射仅供审核，必须在后续字段计划中人工选择，待处理项仍须人工决定。</p>
      <el-alert v-for="(warning,index) in result.warnings" :key="index" :title="warning" type="warning" :closable="false" />
    </template>
  </el-card>
</template>
<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import VisualBindingSuggestions from './VisualBindingSuggestions.vue';
import type { PageRecognition, RecognitionSuggestion } from '@/api/domainAgent';
import { domainAgentApi, parseDomainAgentProgress, type DomainAgentTask, type DomainAgentResult, type DomainStep, type DomainUnit } from '@/api/domainAgent';
const props = defineProps<{siteId: number; sourcePath: string; menuId?: number; disabled?: boolean; previewActive?: boolean; workspacePackageVersion?: string | null; workspaceDigest?: string; workspaceRevision?: number; sources?: {id: string; kind: string; rawValue: string; selector: string}[]; adopt: (result: DomainAgentResult) => Promise<boolean>}>();
const emit = defineEmits<{review: []; 'agent-task': [DomainAgentTask]; 'inspect-relation': [RecognitionSuggestion]}>();
const inspected = ref<PageRecognition|null>(null), checkingEvidence=ref(false), evidenceError=ref('');
const recognition=computed(()=>inspected.value || result.value?.recognition);
function kindLabel(kind:string) {
  return ({LIST_DETAIL:'列表与详情',LIST_ONLY:'独立列表',MAINTAINABLE_CARD_LIST:'可维护的卡片列表',SHARED_FRAGMENT:'公共区域',GROUPED_LIST:'分组列表',
    ADDITIONAL_FIELD:'卡片附加字段',CAROUSEL_COUNTER:'轮播序号',DETAIL_NEIGHBORS:'详情上下篇',SHARED_NAVIGATION:'公共导航',
    NAV_EXPANSION_REVIEW:'导航展开控件',BODY_FRAGMENT_REVIEW:'正文片段入口'} as Record<string,string>)[kind] || '页面关系';
}
function reviewLabel(item:RecognitionSuggestion) {
  if (item.kind==='BODY_FRAGMENT_REVIEW') return '检查包内异步片段';
  if (!props.menuId || ['SHARED_FRAGMENT','SHARED_NAVIGATION','NAV_EXPANSION_REVIEW','MAINTAINABLE_CARD_LIST'].includes(item.kind)) return '核对整站结构';
  return item.kind==='DETAIL_NEIGHBORS' ? '核对详情模板' : '核对列表模板';
}
let evidenceRequest=0;
async function checkEvidence() {
  const request=++evidenceRequest;
  checkingEvidence.value=true; evidenceError.value='';
  try {
    const evidence=await domainAgentApi.recognition(props.siteId,props.sourcePath,props.menuId,props.workspacePackageVersion,props.workspaceDigest);
    if(request===evidenceRequest) inspected.value=evidence;
  } catch(e) {
    if(request===evidenceRequest) evidenceError.value=e instanceof Error ? e.message : '包内识别依据暂时无法读取';
  } finally {if(request===evidenceRequest) checkingEvidence.value=false;}
}
const task=ref<DomainAgentTask|null>(null), result=ref<DomainAgentResult|null>(null), steps=ref<DomainStep[]>([]);
const running=ref(false), cancelling=ref(false), error=ref('');
const adopting = ref(false);
async function adoptAndReview(close?: () => Promise<void>) {
  if (!result.value || props.disabled || adopting.value) return;
  adopting.value = true;
  try {
    if (await props.adopt(result.value)) {
      await close?.();
      emit('review');
    }
  } finally { adopting.value = false; }
}
const referencedSourceIds=computed(()=>new Set(result.value?.units.flatMap(unit=>unit.sources.map(source=>source.sourceId)) || []));
const referencedSources=computed(()=>referencedSourceIds.value.size);
const sourceById=computed(()=>new Map(props.sources?.map(source=>[source.id,source])));
const editorSelectors=computed(()=>result.value?.units
  .filter(unit=>unit.fieldType==='Editor'&&!unit.ignored)
  .flatMap(unit=>unit.sources.filter(reference=>{
    const source=sourceById.value.get(reference.sourceId);
    return source?.kind==='HTML_REGION'&&reference.start===0&&reference.end===source.rawValue.length;
  }).map(reference=>sourceById.value.get(reference.sourceId)?.selector).filter((selector):selector is string=>!!selector)) || []);
const unreferencedMedia=computed(()=>(props.sources || []).filter(source=>['IMAGE','VIDEO_POSTER','VIDEO_SRC','VIDEO_SOURCE'].includes(source.kind)
  &&!referencedSourceIds.value.has(source.id)
  &&!editorSelectors.value.some(selector=>source.selector.startsWith(selector+' > '))));
function evidenceText(reference:DomainUnit['sources'][number]){
  const source=sourceById.value.get(reference.sourceId);
  if(!source)return '来源原文不可用，请重新读取页面来源后核对';
  if(!Number.isInteger(reference.start)||!Number.isInteger(reference.end)||reference.start<0||reference.end<=reference.start||reference.end>source.rawValue.length)return '引用范围无效，无法显示原文片段';
  return source.rawValue.slice(reference.start,reference.end);
}
const requestId=ref('');
const unresolved=computed(()=>!!task.value && !['completed','failed','cancelled'].includes(task.value.state));
let generation=0, timer: ReturnType<typeof setTimeout> | undefined;
const stateLabel=computed(()=>task.value?.state==='completed'&&!result.value?'方案不可用':({pending:'等待执行',running:'执行中',completed:'待人工审核',failed:'执行失败',cancelled:'已取消'}[task.value?.state || ''] || '状态未知'));
const progressMessage=computed(()=>parseDomainAgentProgress(task.value?.progress).message ||
  (running.value && !steps.value.length ? '等待 Agent 返回下一步进度。' : ''));
const canStart = computed(() => !props.disabled && !running.value && !unresolved.value && !!props.siteId && !!props.sourcePath);
function restoreResult(snapshot: DomainAgentTask) {
  if(running.value || unresolved.value || snapshot.state!=='completed')return false;
  error.value='';
  receive(snapshot);
  return result.value!==null && !error.value;
}
defineExpose({ canStart, start, restoreResult });
function actionLabel(action:string){return ({REUSE:'复用已有字段',CREATE:'建议新增字段',MENU_NAME:'引用子栏目名称',PENDING:'待人工核对'}[action] || '待人工核对');}
function toolLabel(tool:string){return ({read_capabilities:'识别页面关系',read_page:'读取页面证据',read_model:'读取栏目与字段',validate_proposal:'校验建议方案',finish:'完成待审核方案',parse:'解析模型动作',stopped:'任务停止'}[tool] || '工具步骤');}
function stepStatusLabel(status:string){return ({ok:'通过',error:'待修正',waiting:'等待模型决策',running:'执行中',pending:'等待执行',completed:'已完成',failed:'失败',cancelled:'已取消'}[status] || '待确认');}
function detach(){generation++;if(timer)clearTimeout(timer);running.value=false;cancelling.value=false;}
watch(()=>[props.siteId,props.sourcePath,props.menuId,props.workspacePackageVersion,props.workspaceDigest,props.workspaceRevision],()=>{detach();evidenceRequest++;checkingEvidence.value=false;inspected.value=null;evidenceError.value='';task.value=null;result.value=null;steps.value=[];error.value='';requestId.value='';});
onBeforeUnmount(detach);
function receive(snapshot:DomainAgentTask){
  emit('agent-task',snapshot);
  task.value=snapshot;
  steps.value=parseDomainAgentProgress(snapshot.progress).steps;
  if(snapshot.state==='completed'){
    const parsed=JSON.parse(snapshot.text) as DomainAgentResult;
    if(parsed.sourcePath!==props.sourcePath || parsed.menuId!==(props.menuId??null) || !Array.isArray(parsed.units) || !Array.isArray(parsed.mappings) || !Array.isArray(parsed.steps) || !Array.isArray(parsed.warnings))throw new Error('方案范围或结构无效，请重新分析');
    if(!props.workspacePackageVersion || parsed.packageVersion!==props.workspacePackageVersion || !props.workspaceDigest || parsed.digest!==props.workspaceDigest || parsed.revision!==props.workspaceRevision){
      result.value=null;running.value=false;requestId.value='';error.value='页面来源或修订已变化，请重新读取后分析';return;
    }
    result.value=parsed;inspected.value=parsed.recognition || null;steps.value=parseDomainAgentProgress(JSON.stringify({steps:parsed.steps})).steps;
  }
  running.value=snapshot.state==='pending'||snapshot.state==='running';
  if(['completed','failed','cancelled'].includes(snapshot.state))requestId.value='';
  if(snapshot.state==='failed')error.value=steps.value.at(-1)?.tool==='stopped' ? steps.value.at(-1)!.message : '领域 Agent 执行失败或超时，请查看已完成步骤后重试。';
  if(!['pending','running','completed','failed','cancelled'].includes(snapshot.state))error.value='任务状态无法识别，请检查服务状态。';
}
async function poll(version:number,siteId:number,id:string){
  try{const snapshot=await domainAgentApi.get(siteId,id);if(version!==generation)return;receive(snapshot);if(running.value)timer=setTimeout(()=>void poll(version,siteId,id),1200);}
  catch{if(version===generation){running.value=false;error.value='读取任务失败，远端状态未确认；请继续查询同一任务或取消。';}}
}
function resume(){
  if(!task.value||running.value)return;
  detach();error.value='';running.value=true;void poll(generation,props.siteId,task.value.taskId);
}
async function start(){
  if(running.value||props.disabled||unresolved.value)return;
  detach();const version=generation,siteId=props.siteId;
  running.value=true;task.value=null;result.value=null;steps.value=[];error.value='';
  try{const snapshot=await domainAgentApi.start(siteId,props.sourcePath,props.menuId??null,requestId.value ||= crypto.randomUUID());if(version!==generation)return;receive(snapshot);if(running.value)timer=setTimeout(()=>void poll(version,siteId,snapshot.taskId),1200);}
  catch{if(version===generation){running.value=false;error.value='启动响应未确认；请重试确认同一任务，将沿用原请求编号，避免重复启动。';}}
}
async function cancel(){
  if(!task.value||cancelling.value)return;
  const version=generation,siteId=props.siteId,id=task.value.taskId;cancelling.value=true;
  try{const snapshot=await domainAgentApi.cancel(siteId,id);if(version!==generation)return;if(timer)clearTimeout(timer);generation++;receive(snapshot);cancelling.value=false;if(running.value){const next=generation;timer=setTimeout(()=>void poll(next,siteId,id),1200);}}
  catch{if(version===generation)error.value='取消未确认，任务可能仍在运行。';}
  finally{if(version===generation)cancelling.value=false;}
}
</script>
<style scoped>
.domain-agent { margin-block: 16px; }
.domain-agent-actions { display: flex; flex-wrap: wrap; gap: 12px; align-items: center; margin-block: 16px; }
.domain-agent-evidence + .domain-agent-evidence { margin-top: 8px; }
.domain-agent-raw { white-space: pre-wrap; overflow-wrap: anywhere; }
.domain-agent-media { margin-block: 16px; }
</style>
