<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { ElMessageBox } from 'element-plus';
import { useRouter } from 'vue-router';
import { getPairedOptions, createPairedPlan, initializePairedBatch, previewPairedList, readPairedListWorkspace, type PairingInspection, type PairingRequest, type PairedOptions, type PairedBatchResult, type PairedUnit, type PairingItem } from '@/api/templateListPairing';
import { readFieldPlan, recentFieldPlans, confirmFieldPlan, type FieldPlan, type FieldPlanSummary } from '@/api/templateFieldPlan';
import { getBindingRecovery, recoverTextBinding, previewTextBindingDraft, type BindingRecovery, type RecoveryAction, type TextBindingResult } from '@/api/templateTextBinding';
import { reuseSharedDetailMapping } from '@/utils/sharedDetailMapping';
const props = defineProps<{siteId: number; menuId: number; inspection: PairingInspection; cardId: string; reviewedDetailPlanId?: string; disabled: boolean}>();
const emit = defineEmits<{busy: [value: boolean]}>();
const router = useRouter();
const options = ref<PairedOptions | null>(null);
const plan = ref<FieldPlan | null>(null);
const recent = ref<FieldPlanSummary[]>([]);
const knownId = ref('');
const listChoices = ref<Record<string,string>>({});
const detailChoices = ref<Record<string,string>>({});
const lastReviewed = ref<FieldPlan | null>(null);
const reuseNotice = ref('');
const recovery = ref<BindingRecovery | null>(null);
const error = ref('');
const loading = ref(false);
const queued = ref<FieldPlan[]>([]);
const batch = ref<PairedBatchResult | null>(null);
const previewHtml = ref('');
const previewWarnings = ref<string[]>([]);
const domChanges = ref<NonNullable<TextBindingResult['domChanges']>>([]);
let generation = 0;
const item = computed(() => props.inspection.items.find(row => row.id === props.cardId));
const decision = computed(() => props.inspection.review?.decisions.find(row => row.cardId === props.cardId));
const sharedDetail = computed(() => decision.value?.ownership === 'SHARED_DETAIL');
const currentCardLabel = computed(() => cardLabel(item.value));
const detailPath = computed(() => {
  return decision.value?.ownership === 'OTHER_DETAIL' ? decision.value.detailPath : item.value?.detail?.sourcePath;
});
const listOrigin = (value: FieldPlan) => value.origins?.find(origin => origin.role === 'LIST');
function cardLabel(row: typeof item.value) {
 return row?.comparisons?.find(fact=>fact.name==='标题')?.list?.value
  || row?.list.evidence.find(evidence=>evidence.kind==='TEXT')?.preview || row?.selector;
}
function queuedCardLabel(value: FieldPlan) {
 const cardId=listOrigin(value)?.pairingReview?.cardId;
 return cardLabel(props.inspection.items.find(item=>item.id===cardId));
}
const blocked = computed(() => props.disabled || loading.value);
const pairing = (): PairingRequest => ({menuId: props.menuId, listSourcePath: props.inspection.listSourcePath, listDigest: props.inspection.listDigest, containerId: props.inspection.container.id, detailPlanId:props.reviewedDetailPlanId});
const sides = computed(() => options.value ? [{role: 'LIST', title: '列表卡片', page: options.value.list, choices: listChoices.value}, {role: 'DETAIL', title: '详情正文', page: options.value.detail, choices: detailChoices.value}] : []);
const selectedCount = computed(() => Object.values(listChoices.value).filter(Boolean).length + Object.values(detailChoices.value).filter(Boolean).length);
const canPlan = computed(() => {
 const list=Object.values(listChoices.value).filter(Boolean), detail=Object.values(detailChoices.value).filter(Boolean);
 return !!options.value && list.length>0 && detail.length>0 && selectedCount.value<=40 && list.length<=20 && detail.length<=20
  && (!sharedDetail.value || (list.includes('title') && detail.length===1));
});
const canInitialize = computed(() => plan.value?.status==='CONFIRMED' && !!recovery.value && ['NONE','INITIALIZED'].includes(recovery.value.phase) && (!recovery.value.activePlanId || recovery.value.activePlanId===plan.value.id));
const labels: Record<RecoveryAction,string> = {KEEP_SINGLE_CURRENT:'保留当前值确认',RESUME_EXISTING_DETAIL:'保留已有详情确认',RETRY_MISSING_DETAIL:'确认缺失后重试'};
function message(cause: unknown) { return cause instanceof Error ? cause.message : String(cause); }
function reset() { generation++; options.value=null;plan.value=null;recent.value=[];knownId.value='';listChoices.value={};detailChoices.value={};reuseNotice.value='';recovery.value=null;error.value='';previewHtml.value='';previewWarnings.value=[];domChanges.value=[]; }
watch(() => [props.cardId, props.siteId, props.menuId, props.inspection.listDigest], reset);
watch([listChoices,detailChoices], () => { plan.value=null; recovery.value=null; previewHtml.value='';domChanges.value=[]; }, {deep:true});
watch(loading, value => emit('busy',value));
onBeforeUnmount(() => { generation++; if (loading.value) emit('busy', false); });
async function run(action: (version: number) => Promise<void>) {
 if (blocked.value) return; const version=generation;loading.value=true;error.value='';
 try { await action(version); } catch(cause) { if(version===generation)error.value=message(cause); } finally { loading.value=false; }
}
async function load() { await run(async version => {
 options.value=null;plan.value=null;recovery.value=null;listChoices.value={};detailChoices.value={};reuseNotice.value='';previewHtml.value='';
 if(!detailPath.value)throw new Error('该卡片尚未唯一配对');
 const saved=await recentFieldPlans(props.siteId,detailPath.value,props.menuId);
 if(version!==generation)return;recent.value=saved.plans;
 if(saved.truncated || saved.warnings.length)error.value='既有计划列表不完整，可输入已知计划ID读取；不据此认定未应用。';
 const data=await getPairedOptions(props.siteId,pairing(),props.cardId);
 if(version===generation)options.value=data;
 }); }
function approvedPredecessor(saved: FieldPlan, current: PairingItem, data: PairedOptions) {
 const list=listOrigin(saved), origin=list?.pairingReview, review=props.inspection.review;
 const detail=saved.origins?.find(source=>source.role==='DETAIL');
 if(saved.status!=='CONFIRMED' || saved.siteId!==props.siteId || saved.menuId!==props.menuId
  || saved.packageVersion!==props.inspection.packageVersion || !origin?.sharedDetail || origin.cardId===current.id
  || !review || origin.evidenceDigest!==review.evidenceDigest || origin.pairing?.containerId!==props.inspection.container.id
  || list?.sourcePath!==data.list.sourcePath || list.digest!==data.list.digest || list.unitsRevision!==data.list.revision
  || detail?.sourcePath!==data.detail.sourcePath || detail.digest!==data.detail.digest || detail.unitsRevision!==data.detail.revision
  || saved.afterFieldConfigDigest!==data.fieldConfigDigest
  || !review.decisions.some(row=>row.cardId===origin.cardId && row.ownership==='SHARED_DETAIL'))return;
 return props.inspection.items.find(row=>row.id===origin.cardId && row.status==='SHARED_DETAIL'
  && row.detail?.sourcePath===current.detail?.sourcePath && row.detail?.digest===current.detail?.digest
  && row.detailBodyHash===current.detailBodyHash);
}
async function reusePrevious() { await run(async version=>{
 const current=item.value, data=options.value;
 if(!sharedDetail.value || current?.status!=='SHARED_DETAIL' || !current.detailBodyHash || !current.detail
  || !data || data.cardId!==current.id || data.detail.sourcePath!==current.detail.sourcePath || data.detail.digest!==current.detail.digest)return;
 reuseNotice.value='';
 let saved=lastReviewed.value;
 let previous=saved ? approvedPredecessor(saved,current,data) : undefined;
 if(!previous) {
  for(const summary of recent.value) {
   if(summary.status!=='CONFIRMED' || summary.digest!==data.detail.digest || summary.unitsRevision!==data.detail.revision)continue;
   const candidate=await readFieldPlan(props.siteId,summary.id);
   if(version!==generation)return;
   previous=approvedPredecessor(candidate,current,data);
   if(previous){saved=candidate;break;}
  }
 }
 if(!saved || !previous){reuseNotice.value='尚无与当前卡片证据一致的已审核映射，请手动选择。';return;}
 const workspace=await readPairedListWorkspace(props.siteId,data.list.sourcePath);
 if(version!==generation)return;
 if(workspace.sourcePath!==data.list.sourcePath || workspace.digest!==data.list.digest || workspace.revision!==data.list.revision) {
  reuseNotice.value='列表来源或人工修订已变化，请重新读取本卡片单元。';return;
 }
 const reused=reuseSharedDetailMapping(saved,previous,current,data,workspace,listChoices.value,detailChoices.value);
 if(reused.added){listChoices.value=reused.list;detailChoices.value=reused.detail;}
 reuseNotice.value=reused.added
  ? `已沿用「${cardLabel(previous)}」的 ${reused.added} 个字段选择。请核对本卡片原值和其余待选项。`
  : '当前选择已保留，未找到可唯一匹配并填入的空项。';
}); }
function compatible(unit: PairedUnit) { return options.value?.fields.filter(field=>field.writable && field.fieldType===unit.fieldType && field.capacity>=unit.valueLength) || []; }
function chosen(map:Record<string,string>) { return Object.fromEntries(Object.entries(map).filter(([,field])=>!!field)); }
async function create() { await run(async version=> {
 const data=options.value;if(!data || !canPlan.value)return;
 const saved=await createPairedPlan(props.siteId,{pairing:pairing(),cardId:props.cardId,detailDigest:data.detail.digest,listRevision:data.list.revision,detailRevision:data.detail.revision,fieldConfigDigest:data.fieldConfigDigest,listChoices:chosen(listChoices.value),detailChoices:chosen(detailChoices.value)});
 if(version===generation){plan.value=saved;knownId.value=saved.id;recovery.value=await getBindingRecovery(props.siteId,saved.id);}
 }); }
async function read(id=knownId.value) { await run(async version=> {
 options.value=null;listChoices.value={};detailChoices.value={};plan.value=null;recovery.value=null;previewHtml.value='';
 const saved=await readFieldPlan(props.siteId,id.trim());
 const origin=listOrigin(saved), review=origin?.pairingReview;
 if(saved.menuId!==props.menuId || saved.sourcePath!==detailPath.value || origin?.sourcePath!==props.inspection.listSourcePath
  || (review && review.cardId!==props.cardId) || (sharedDetail.value && !review?.sharedDetail))throw new Error('计划不属于当前卡片配对，请按原计划对应的卡片和栏目核对');
 const state=await getBindingRecovery(props.siteId,saved.id);
 if(version===generation){plan.value=saved;knownId.value=saved.id;recovery.value=state;previewHtml.value='';if(saved.status==='CONFIRMED')lastReviewed.value=saved;}
 }); }
async function confirm() {
 const saved=plan.value;if(!saved || blocked.value)return;
 try{await ElMessageBox.confirm('确认两份原文分别映射到表中正式字段。此步只确认计划；不会初始化或发布内容。','确认双来源字段映射',{type:'warning'});}catch{return;}
 await run(async version=>{const value=await confirmFieldPlan(props.siteId,saved.id);if(version===generation){plan.value=value;if(value.status==='CONFIRMED')lastReviewed.value=value;recovery.value=await getBindingRecovery(props.siteId,value.id);}});
}
function queue() {
 const value=plan.value;if(!value || !canInitialize.value){error.value='当前应用需先检查并明确恢复，不能加入新初始化队列';return;}
 if(recovery.value?.activePlanId && recovery.value.activePlanId!==value.id){error.value='已有应用需读取原计划恢复，不能加入新初始化队列';return;}
 const current=listOrigin(value)?.pairingReview;
 if(queued.value.some(saved=>{
  const previous=listOrigin(saved)?.pairingReview;
  return saved.id===value.id || (current && previous?.cardId===current.cardId)
   || (saved.sourcePath===value.sourcePath && !(current?.sharedDetail && previous?.sharedDetail));
 })){error.value='此卡片或独立详情已在队列，请核对已有计划';return;}
 if(queued.value.length>=20){error.value='每批最多20条，请完成后分批处理';return;}
 queued.value.push(value);
}
async function initialize(ids: string[]) {
 if(blocked.value || !ids.length)return;
 try{await ElMessageBox.confirm(`将逐条初始化${ids.length}个已确认私有详情草稿。每条独立事务；已有应用保留人工值，失败项需明确恢复；不会发布或修改公开模板。`,'确认初始化',{type:'warning',confirmButtonText:'确认初始化'});}catch{return;}
 await run(async version=>{const result=await initializePairedBatch(props.siteId,props.menuId,ids);if(version===generation){batch.value=result;if(plan.value && ids.includes(plan.value.id))recovery.value=await getBindingRecovery(props.siteId,plan.value.id);}});
}
async function recover(action: RecoveryAction) {
 const value=plan.value,state=recovery.value;if(!value || !state || blocked.value)return;
 try{await ElMessageBox.confirm(`${state.message}。记录${state.recordId ?? '未分配'}。服务器会重新校验原值和状态；不覆盖人工修改。`,labels[action],{type:'warning'});}catch{return;}
 await run(async version=>{await recoverTextBinding(props.siteId,value.id,state,action);const result=await getBindingRecovery(props.siteId,value.id);if(version===generation)recovery.value=result;});
}
async function preview(role: 'LIST'|'DETAIL') { const value=plan.value;if(!value)return;await run(async version=>{
 const result=role==='LIST'?await previewPairedList(props.siteId,value.id):await previewTextBindingDraft(props.siteId,value.id);
 if(version===generation){previewHtml.value=result.preview.html;previewWarnings.value=[...new Set(result.preview.warnings)];domChanges.value=result.binding.domChanges || [];}
 }); }
function edit() { if(recovery.value?.recordId)void router.push({path:`/admin/v2/cms/content/${props.menuId}`,query:{recordId:String(recovery.value.recordId)}}); }
</script>
<template>
 <section class="paired-initialization" aria-label="双来源字段映射与私有初始化">
  <h4>双来源字段映射 · {{ detailPath }}</h4>
  <p v-if="sharedDetail">当前卡片：{{ currentCardLabel }}。请将本卡片原始标题映射到原生标题，并选择一个共享富文本正文。日期、图片、分类等字段从此列表卡片取值。</p>
  <p>列表与详情都须先在上方单页流程保存人工内容单元。此处只选择已确认单元与现有正式字段；缺字段请先去CMS字段配置新增，再重新检查。未选内容保留原静态模板，不能视为已维护。</p>
  <el-button :disabled="blocked" :loading="loading" @click="load">读取人工单元与既有计划</el-button>
  <el-alert v-if="error" type="error" :title="error" :closable="false" />
  <p v-if="loading">正在处理当前来源，请等待结果后切换站点或栏目。</p>
  <div class="controls">
   <el-select v-model="knownId" placeholder="从服务端读取已有计划" :disabled="blocked"><el-option v-for="saved in recent" :key="saved.id" :value="saved.id" :label="`${saved.status} · ${saved.id}`" /></el-select>
   <el-input v-model="knownId" aria-label="已知双来源计划ID" placeholder="或输入已知计划ID" :disabled="blocked" />
   <el-button :disabled="blocked || !knownId" @click="read()">读取计划与恢复状态</el-button>
  </div>
  <template v-if="options">
   <p v-for="warning in options.warnings" :key="warning">{{ warning }}</p>
   <div v-if="sharedDetail" class="controls">
    <el-button :disabled="blocked" @click="reusePrevious">沿用上一张已审核映射</el-button>
    <span v-if="reuseNotice" role="status">{{ reuseNotice }}</span>
   </div>
   <section v-for="side in sides" :key="side.role">
    <h5>{{ side.title }} · {{ side.page.sourcePath }} · 人工版本 {{ side.page.revision }}</h5>
    <p v-if="!side.page.units.length">本范围没有已保存、未忽略的人工单元，请先回上方确认并保存此页面。</p>
    <el-table :data="side.page.units" row-key="id">
     <el-table-column label="人工业务单元" min-width="140"><template #default="{row}">{{ row.name }} · {{ row.fieldType }}<p>{{ row.doubts }}</p></template></el-table-column>
     <el-table-column label="原值证据" min-width="170"><template #default="{row}">{{ row.valuePreview }}<p>原值 {{ row.valueLength }} 字符，长值仅截取显示</p></template></el-table-column>
     <el-table-column label="明确选择复用字段" min-width="250"><template #default="{row}"><el-select v-model="side.choices[row.id]" clearable placeholder="留空则保持静态" :disabled="blocked" :aria-label="`${side.title} ${row.name} 目标字段`"><el-option v-for="field in compatible(row)" :key="field.fieldName" :value="field.fieldName" :label="`${field.name} · ${field.fieldType} · 容量${field.capacity} · ${field.fieldName}${field.inputType === 'counterDecimal' ? ' · 统计数值约束（不接受千分位）' : field.inputType === 'counterNumber' ? ' · 统计数值约束' : ''}`" /></el-select><p v-if="!compatible(row).length">无兼容正式字段，待人工配置；不会自动占用其他用途。</p></template></el-table-column>
    </el-table>
   </section>
   <el-button :disabled="blocked || !canPlan" @click="create">生成单条双来源计划</el-button>
  </template>
  <template v-if="plan">
   <p>计划 {{ plan.id }} · {{ plan.status }} · 栏目 {{ plan.menuId }}；仅私有双页预览。</p>
   <el-table :data="plan.bindings"><el-table-column prop="name" label="单元" /><el-table-column prop="fieldName" label="正式字段" /><el-table-column prop="valuePreview" label="初始化原值（截取）" /><el-table-column prop="message" label="校验" /></el-table>
   <div class="controls"><el-button :disabled="blocked || plan.status==='CONFIRMED'" @click="confirm">确认字段映射</el-button><el-button :disabled="blocked || !canInitialize" @click="initialize([plan.id])">初始化此私有草稿</el-button><el-button :disabled="blocked || !canInitialize" @click="queue">加入本批队列</el-button></div>
  </template>
  <template v-if="recovery">
   <p>应用阶段 {{ recovery.phase }} · 原应用计划 {{ recovery.activePlanId || '尚无' }} · 记录 {{ recovery.recordId ?? '尚无' }}。{{ recovery.message }}</p>
   <el-button v-if="recovery.activePlanId && recovery.activePlanId!==plan?.id" :disabled="blocked" @click="read(recovery.activePlanId || '')">明确读取原应用计划</el-button>
   <el-button v-for="action in recovery.actions" :key="action" :disabled="blocked" @click="recover(action)">{{ labels[action] }}</el-button>
   <div v-if="recovery.phase==='INITIALIZED' && recovery.activePlanId===plan?.id" class="controls"><el-button :disabled="blocked" @click="preview('DETAIL')">重读详情私有预览</el-button><el-button :disabled="blocked" @click="preview('LIST')">重读当前卡片私有预览</el-button><el-button :disabled="blocked" @click="edit">在CMS编辑此详情</el-button></div>
  </template>
  <template v-if="queued.length"><h5>本批明确确认的计划（{{ queued.length }}/20）</h5><p v-for="saved in queued" :key="saved.id"><strong v-if="queuedCardLabel(saved)">{{ queuedCardLabel(saved) }} · </strong>{{ saved.sourcePath }} · {{ saved.id }} <el-button :disabled="blocked" link @click="queued=queued.filter(value=>value.id!==saved.id)">移出</el-button></p><el-button :disabled="blocked" @click="initialize(queued.map(value=>value.id))">确认并逐条初始化本批</el-button></template>
  <template v-if="batch"><p>本批已初始化 {{ batch.initialized }} 条，待处理 {{ batch.pending }} 条；未发布。重复确认仍读取原应用，不覆盖人工值。</p><el-table :data="batch.entries"><el-table-column prop="planId" label="计划" /><el-table-column prop="recordId" label="记录" /><el-table-column prop="status" label="结果" /><el-table-column prop="message" label="说明" /></el-table></template>
  <details v-if="domChanges.length"><summary>统计限定符 DOM 差异（须人工核对）</summary><section v-for="change in domChanges" :key="change.unitId"><p>{{ change.reason }}</p><p>调整前</p><pre>{{ change.before }}</pre><p>调整后</p><pre>{{ change.after }}</pre></section></details>
  <template v-if="previewHtml"><p>隔离预览禁用脚本、网络、表单与媒体播放；仅当前选定卡片动态，其余列表内容仍保持静态。</p><el-collapse v-if="previewWarnings.length"><el-collapse-item title="预览限制说明" name="warnings"><p v-for="warning in previewWarnings" :key="warning">{{ warning.length>360?warning.slice(0,360)+'…':warning }}</p></el-collapse-item></el-collapse><iframe title="双来源私有草稿预览" sandbox="" referrerpolicy="no-referrer" :srcdoc="previewHtml" /></template>
 </section>
</template>
<style scoped>
.paired-initialization { margin-top:16px; border-top:1px solid var(--el-border-color);padding-top:12px; }
.controls { display:flex;gap:12px;flex-wrap:wrap;align-items:center;margin:12px 0; }
.controls .el-input,.controls .el-select {width: min(380px,100%);}
p {overflow-wrap:anywhere;white-space:pre-wrap;}
iframe {width:100%;min-height:600px;border:1px solid var(--el-border-color);}
</style>
