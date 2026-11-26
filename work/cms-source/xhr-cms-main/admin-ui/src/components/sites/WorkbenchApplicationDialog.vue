<template>
  <el-dialog :model-value="true" title="确认栏目与字段应用" width="min(880px, 96vw)" append-to-body
    :close-on-click-modal="false" :before-close="beforeClose">
    <div class="application-content">
      <p v-if="loading" role="status">正在核对整包审核、导航和栏目配置…</p>
      <p v-if="applying" role="status">正在应用已确认的栏目与字段。完成后会显示真实栏目。</p>
      <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
      <section v-if="preview && !result" aria-label="栏目应用范围">
        <h3>影响范围</h3>
        <p>{{ preview.nodeCount }} 个导航入口 · {{ preview.models.length }} 个字段模型</p>
        <p v-if="canCloseStale">请核对下列已有栏目。结束旧确认后，可按当前修订重新预览。</p>
        <p v-else>应用后可继续审核初值、模板和公开范围。</p>
        <ul v-if="preview.models.length" class="application-models">
          <li v-for="model in preview.models" :key="model.modelKey">
            <strong>{{ model.name }}</strong>
            <p v-if="model.menuId">{{ preview.pendingRequestId ? '已存在的' : '复用' }} CMS 栏目 #{{ model.menuId }}</p>
            <p v-else-if="model.ownerNodeKey">拟由「{{ ownerName(model) }}」作为维护归属。</p>
            <p v-else>维护归属待核对。</p>
            <details v-if="fieldsFor(model.modelKey).length"><summary>查看字段（{{ fieldsFor(model.modelKey).length }}）</summary>
              <ul><li v-for="field in fieldsFor(model.modelKey)" :key="field.key">{{ field.name }} · {{ fieldLabels[field.type] || field.type }}</li></ul>
            </details>
            <details v-if="model.candidates.length"><summary>查看关联入口（{{ model.candidates.length }}）</summary>
              <ul><li v-for="candidate in model.candidates" :key="candidate.nodeKey">
                {{ candidate.name }}<template v-if="candidate.sourcePath"> · {{ candidate.sourcePath }}</template>
              </li></ul>
            </details>
            <label v-if="model.candidates.length>1 && (!model.ownerNodeKey || reuseOwners[model.modelKey])">
              维护归属
              <el-select :model-value="reuseOwners[model.modelKey]" :aria-label="model.name+'的维护归属'" placeholder="选择要复用字段模型的入口" :disabled="loading || applying" clearable
                @update:model-value="selectOwner(model.modelKey,$event)">
                <el-option v-for="candidate in model.candidates" :key="candidate.nodeKey" :value="candidate.nodeKey"
                  :label="candidate.sourcePath ? `${candidate.name} · ${candidate.sourcePath}` : candidate.name" />
              </el-select>
            </label>
          </li>
        </ul>
      </section>
      <section v-if="issues.length" aria-label="应用待核对项">
        <h3>待核对项</h3>
        <ul><li v-for="(issue,index) in issues" :key="`${issue.code}:${index}`">{{ issue.message }}
          <span v-if="scopeLabel(issue)"> · {{ scopeLabel(issue) }}</span>
        </li></ul>
      </section>
      <section v-if="result" aria-label="应用结果">
        <h3>{{ result.status==='APPLIED' ? '栏目与字段已应用' : result.status==='PARTIAL' ? '应用尚未完成' : '应用需要重新核对' }}</h3>
        <p v-if="result.status==='APPLIED'">已读取 {{ appliedNodes.length }} 个实际栏目。可按栏目继续审核字段。</p>
        <ul v-if="appliedNodes.length" class="application-models">
          <li v-for="node in appliedNodes" :key="node.key">
            <div class="application-row"><strong>{{ node.name }} · #{{ node.menuId }}</strong>
              <el-button v-if="result.status==='APPLIED' && node.kind!=='DIRECTORY' && node.sourcePath && !node.drift" size="small" type="primary" plain
                @click="emit('fields',node)">审核字段</el-button>
            </div>
            <span v-if="node.sourcePath">{{ node.sourcePath }}</span>
          </li>
        </ul>
      </section>
      <p v-if="savedRequest && !applying && !canCloseStale" role="status">可沿用上次确认恢复应用结果。</p>
    </div>
    <template #footer>
      <el-button :disabled="applying" @click="emit('close')">{{ result?.status==='APPLIED' ? '完成' : '返回' }}</el-button>
      <el-button v-if="result?.status!=='APPLIED' && !savedRequest" :disabled="loading || applying" @click="readPreview(true)">{{ changedOwners ? '更新影响预览' : '重新读取影响预览' }}</el-button>
      <el-button v-if="savedRequest && (error || result?.status==='PARTIAL')" :disabled="loading || applying" @click="readPreview(true,true)">核对当前应用状态</el-button>
      <el-button v-if="canCloseStale" type="warning" :disabled="loading" :loading="applying" @click="closeStale">确认结束旧申请</el-button>
      <el-button v-else-if="savedRequest" type="primary" :disabled="loading" :loading="applying" @click="apply(savedRequest)">{{ error || result?.status==='PARTIAL' ? '按同一申请重试' : '继续已确认应用' }}</el-button>
      <el-button v-else-if="!result" type="primary" :disabled="!canApply" :loading="applying" @click="confirmApply">确认并应用</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { packageAnalysisApi } from '@/api/packageAnalysis';
import { siteModelApi, type SiteModelPlan, type SiteNavigation, type SiteNavigationNode,
  type WorkbenchApplyIssue, type WorkbenchApplyPreview, type WorkbenchApplyRequest, type WorkbenchApplyResult, type WorkbenchApplyScope } from '@/api/siteModel';

const props=defineProps<{plan:SiteModelPlan;navigation:SiteNavigation}>();
const emit=defineEmits<{close:[];busy:[value:boolean];applied:[result:WorkbenchApplyResult];fields:[node:SiteNavigationNode]} >();
const preview=ref<WorkbenchApplyPreview>(), result=ref<WorkbenchApplyResult>();
const previewPlan=ref<SiteModelPlan>();
const previewScope=ref<WorkbenchApplyScope>(), savedRequest=ref<WorkbenchApplyRequest>();
const reuseOwners=ref<Record<string,string>>({});
const loading=ref(false), applying=ref(false), error=ref('');
const storageKey=computed(()=>JSON.stringify(['workbench-application',props.plan.siteId,props.plan.packageVersion,props.plan.id]));
let pending:AbortController|undefined;
const appliedNodes=computed(()=>result.value?.nodes.filter(node=>node.menuId) || []);
const issues=computed(()=>result.value?.issues || preview.value?.issues || []);
const fieldLabels:Record<string,string>={SingleLine:'单行文字',MultiLine:'多行文字',Editor:'富文本',Image:'图片',Video:'视频',File:'文件',DateTime:'日期时间',Sort:'排序'};
const fieldsFor=(modelKey:string)=>previewPlan.value?.proposal.models.find(model=>model.key===modelKey)?.fields || [];
const normalized=(value?:Record<string,string>)=>JSON.stringify(Object.entries(value || {}).sort(([a],[b])=>a.localeCompare(b)));
const changedOwners=computed(()=>!!previewScope.value && normalized(reuseOwners.value)!==normalized(previewScope.value.reuseOwners));
const canApply=computed(()=>!!preview.value?.ready && !!preview.value.token && !!previewScope.value && !changedOwners.value && !loading.value && !applying.value);
const canCloseStale=computed(()=>!!savedRequest.value && preview.value?.issues.some(issue=>issue.code==='CLOSE_STALE_INTENT'));

function ownerName(model:WorkbenchApplyPreview['models'][number]){
  return model.candidates.find(item=>item.nodeKey===model.ownerNodeKey)?.name || props.navigation.nodes.find(item=>item.key===model.ownerNodeKey)?.name || '已审核入口';
}
function scopeLabel(issue:WorkbenchApplyIssue){
  return props.navigation.nodes.find(node=>node.key===issue.nodeKey)?.name || preview.value?.models.find(model=>model.modelKey===issue.modelKey)?.name || '';
}
function selectOwner(modelKey:string,nodeKey?:string){
  if(nodeKey)reuseOwners.value[modelKey]=nodeKey;
  else delete reuseOwners.value[modelKey];
}
function remember(request?:WorkbenchApplyRequest){
  savedRequest.value=request;
  try{if(request)sessionStorage.setItem(storageKey.value,JSON.stringify(request));else sessionStorage.removeItem(storageKey.value);}catch{/* 当前弹窗仍可恢复原确认。 */}
}
function restore(){
  try{
    const raw=sessionStorage.getItem(storageKey.value);
    if(!raw)return;
    const stored=JSON.parse(raw) as WorkbenchApplyRequest;
    if(stored.packageVersion===props.plan.packageVersion && stored.confirmed===true && typeof stored.requestId==='string' &&
        typeof stored.token==='string' && Number.isSafeInteger(stored.planRevision) && Number.isSafeInteger(stored.navigationRevision) && Number.isSafeInteger(stored.packageRevision)){
      savedRequest.value=stored;reuseOwners.value={...stored.reuseOwners};
    }
  }catch{/* 无恢复记录时读取当前影响范围。 */}
}

async function readPreview(refresh=false,verifyPending=false){
  if(applying.value || loading.value || (savedRequest.value && !verifyPending))return;
  const currentProps=props.plan, key=storageKey.value;
  const request=new AbortController();pending=request;loading.value=true;
  preview.value=undefined;previewPlan.value=undefined;result.value=undefined;previewScope.value=undefined;error.value='';
  const current=()=>pending===request && storageKey.value===key;
  try {
    const [analysis,plan]=await Promise.all([
      packageAnalysisApi.read(currentProps.siteId,currentProps.packageVersion,request.signal),
      refresh ? siteModelApi.get(currentProps.siteId,currentProps.id) : Promise.resolve(currentProps),
    ]);
    if(!current())return;
    if(plan.siteId!==currentProps.siteId || plan.packageVersion!==currentProps.packageVersion || analysis.packageVersion!==currentProps.packageVersion || plan.status!=='CONFIRMED')
      throw new Error('结构计划已变化，请回到整站结构核对。');
    const navigation=refresh ? await siteModelApi.navigation(plan.siteId,plan,request.signal) : props.navigation;
    if(!current())return;
    if(refresh)reuseOwners.value=Object.fromEntries(Object.entries(reuseOwners.value).filter(([modelKey,nodeKey])=>
      plan.proposal.models.some(model=>model.key===modelKey) && navigation.nodes.some(node=>node.key===nodeKey && node.modelKey===modelKey)));
    const payload={packageVersion:plan.packageVersion,planRevision:plan.revision,navigationRevision:navigation.revision,
      packageRevision:analysis.revision,reuseOwners:{...reuseOwners.value}};
    const next=await siteModelApi.previewWorkbenchApply(plan.siteId,plan.id,payload,request.signal);
    if(!current())return;
    if(next.planRevision!==payload.planRevision || next.navigationRevision!==payload.navigationRevision || next.packageRevision!==payload.packageRevision)
      throw new Error('确认范围已变化，请重新读取影响预览。');
    previewScope.value=payload;previewPlan.value=plan;preview.value=next;
    if(next.pendingRequestId && next.pendingToken){
      if(savedRequest.value?.requestId!==next.pendingRequestId || savedRequest.value.token!==next.pendingToken)
        remember({...payload,requestId:next.pendingRequestId,token:next.pendingToken,confirmed:true});
    } else if(verifyPending){
      const requestId=savedRequest.value?.requestId || '';
      remember();
      if(navigation.status==='APPLIED' && next.ready){
        result.value={status:'APPLIED',...payload,nodes:navigation.nodes,issues:next.issues,requestId};
        emit('applied',result.value);
      }
    }
  } catch(cause){if(current())error.value=cause instanceof Error ? cause.message : '暂时无法读取应用范围。';}
  finally{if(current()){loading.value=false;pending=undefined;}}
}

async function apply(payload:WorkbenchApplyRequest, close=false){
  if(applying.value || loading.value)return;
  const plan=props.plan,key=storageKey.value;
  const request=new AbortController();pending=request;
  remember(payload);applying.value=true;error.value='';emit('busy',true);
  const current=()=>pending===request && storageKey.value===key;
  let completed:WorkbenchApplyResult|undefined;
  try {
    const response=await (close ? siteModelApi.closeWorkbenchApply : siteModelApi.applyWorkbench)(plan.siteId,plan.id,payload,request.signal);
    if(!current())return;
    if(response.requestId!==payload.requestId)throw new Error('返回的应用记录与本次确认不同。');
    result.value=response;
    if(response.status!=='PARTIAL')remember();
    if(response.status==='APPLIED')completed=response;
  } catch(cause){if(current())error.value=cause instanceof Error ? cause.message : '暂时无法确认应用结果。';}
  finally{if(current()){applying.value=false;pending=undefined;emit('busy',false);}}
  if(completed && key===storageKey.value)emit('applied',completed);
  if(result.value?.status==='CLOSED' && key===storageKey.value)void readPreview(true);
}
function closeStale(){
  if(!canCloseStale.value || !savedRequest.value || !previewScope.value)return;
  const {requestId,token}=savedRequest.value;
  void apply({...previewScope.value,requestId,token,confirmed:true},true);
}
function confirmApply(){
  if(!canApply.value || !previewScope.value || !preview.value?.token)return;
  void apply({...previewScope.value,token:preview.value.token,requestId:crypto.randomUUID(),confirmed:true});
}
function beforeClose(done:()=>void){if(!applying.value){done();emit('close');}}
watch(storageKey,()=>{restore();void readPreview(!!savedRequest.value,!!savedRequest.value);},{immediate:true});
onBeforeUnmount(()=>{pending?.abort();pending=undefined;if(applying.value)emit('busy',false);});
</script>

<style scoped>
.application-content { display:grid; gap:12px; }
.application-content p,.application-content h3,.application-models { margin:0; }
.application-content p,.application-content li,.application-content section { overflow-wrap:anywhere; }
.application-content section { display:grid; gap:6px; }
.application-models { display:grid; gap:6px; list-style:none; padding:0; }
.application-models > li { padding:8px 10px; border:1px solid var(--el-border-color); border-radius:4px; }
.application-models details { margin-block:4px; }
.application-models summary { cursor:pointer; }
.application-models label { display:grid; gap:4px; margin-top:5px; }
.application-row { display:flex; gap:8px; align-items:center; flex-wrap:wrap; justify-content:space-between; }
</style>
