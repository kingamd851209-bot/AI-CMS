<template>
  <el-dialog :model-value="true" title="复核并追加导航" append-to-body width="min(900px, 96vw)"
    :close-on-click-modal="false" :before-close="close">
    <h3>{{ entry.name }}</h3>
    <p>挂入位置 · {{ parentName }}　原页 · {{ entry.sourcePath || '待核对' }}</p>
    <details v-if="entry.sourcePath" @toggle="showPage=($event.currentTarget as HTMLDetailsElement).open">
      <summary>查看入口的原页样式</summary>
      <EntryDecisionSourcePreview v-if="showPage" :site-id="siteId" :package-version="plan.packageVersion"
        :source-path="entry.sourcePath" :visible="true"/>
    </details>
    <details><summary>查看页头和页脚来源</summary>
      <ul><li v-for="item in entry.observations" :key="item.path + ':' + item.selector">
        {{ item.origin==='HEADER' ? '页头' : item.origin==='FOOTER' ? '页脚' : item.origin }} · {{ item.path }} · {{ item.href || '空链接' }}
      </li><li v-if="!entry.observations?.length">{{ entry.evidencePath }} · {{ entry.sourceHref || '空链接' }}</li></ul>
    </details>
    <el-alert v-if="retry && !applied" title="上次追加尚未收到完整结果。可以用同一份确认重试并读取栏目回执。" type="info" :closable="false"/>
    <template v-if="!applied && !retry">
      <div class="append-options">
        <label>栏目用途
          <el-select v-model="kind" aria-label="新增栏目的用途" clearable placeholder="采用系统建议" :disabled="locked" @change="changeKind">
            <el-option v-for="(label,key) in kinds" :key="key" :label="label" :value="key"/>
          </el-select>
        </label>
        <label v-if="kind!=='DIRECTORY'">内容模型
          <el-select v-model="modelKey" aria-label="新增栏目的内容模型" clearable filterable :placeholder="kind ? '独立配置字段' : '采用系统建议'"
            :disabled="locked" @change="changeModel">
            <el-option v-for="model in models" :key="model.key" :label="model.name" :value="model.key"/>
          </el-select>
        </label>
      </div>
      <el-checkbox v-if="reuseCandidate" v-model="reuse" :disabled="locked" @change="invalidate">
        复用「{{ reuseCandidate.name }}」的现有栏目 #{{ reuseCandidate.menuId }}
      </el-checkbox>
      <p v-if="reading" role="status">正在核对现有栏目、来源和追加范围…</p>
      <section v-if="preview" aria-label="追加影响范围" class="append-impact">
        <h4>本次涉及 {{ preview.nodes.length }} 个入口</h4>
        <ul><li v-for="node in preview.nodes" :key="node.key">
          {{ node.name }} · {{ kinds[node.kind] }} · {{ node.reuseMenuId ? '复用栏目 #'+node.reuseMenuId : '创建栏目' }}
          <span v-if="node.modelKey"> · {{ plan.proposal.models.find(m=>m.key===node.modelKey)?.name }}</span>
        </li></ul>
        <p v-if="preview.parent">原父栏目「{{ preview.parent.name }}」已有独立模板时，请在追加后核对其中的入口卡片。</p>
      </section>
    </template>
    <el-alert v-if="applied" title="导航追加已保存，可进入新结构继续配置字段和模板。" type="success" :closable="false"/>
    <el-alert v-if="error" :title="error" type="warning" show-icon :closable="false"/>
    <div v-if="error && !applied && !writing" class="append-options">
      <el-button plain @click="emit('reload-plan')">读取当前整站结构</el-button>
      <el-button v-if="entry.sourcePath" link @click="emit('source',entry.sourcePath)">在整包审核中查看此页</el-button>
    </div>
    <template #footer>
      <el-button :disabled="writing" @click="close">关闭</el-button>
      <el-button v-if="applied" type="primary" :loading="reading" @click="openResult">查看已追加的栏目</el-button>
      <template v-else-if="retry">
        <el-button :disabled="locked" @click="restartReview">重新核对来源</el-button>
        <el-button type="primary" :loading="writing" :disabled="locked" @click="apply">按上次确认重试</el-button>
      </template>
      <template v-else>
        <el-button :loading="reading" :disabled="writing || disabled" @click="readPreview">{{ preview ? '重新核对影响范围' : '核对影响范围' }}</el-button>
        <el-button type="primary" :loading="writing" :disabled="!preview || reading || disabled" @click="apply">确认追加导航</el-button>
      </template>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { siteModelApi, type SiteNavigationEvidence, type SiteNavigationNode, type SiteModelPlan, type SiteNavigation,
  type NavigationAppendPreview, type NavigationAppendScope } from '@/api/siteModel';
import EntryDecisionSourcePreview from './EntryDecisionSourcePreview.vue';
import { useSiteContextStore } from '@/stores/siteContext';

const props=defineProps<{siteId:number;plan:SiteModelPlan;navigation:SiteNavigation;entry:SiteNavigationEvidence;parentName:string;disabled:boolean}>();
const emit=defineEmits<{close:[];busy:[value:boolean];'reload-plan':[];source:[path:string];completed:[plan:SiteModelPlan,navigation:SiteNavigation,nodeKey:string]}>();
const kinds={DIRECTORY:'目录', SINGLE:'单页', LIST:'列表'};
const kind=ref<SiteNavigationNode['kind']>(), modelKey=ref<string>(), reuse=ref(false), showPage=ref(false);
const preview=ref<NavigationAppendPreview>(), approvedScope=ref<NavigationAppendScope>();
type ConfirmedAppend=NavigationAppendScope & {previewDigest:string;confirmed:true};
const retry=ref<ConfirmedAppend>();
const account=useSiteContextStore().currentAdmin?.id;
const retryKey=JSON.stringify(['cms-navigation-append',account,props.siteId,props.plan.packageVersion,props.plan.id,props.entry.key]);
const applied=ref<{planId:string;navigation:SiteNavigation}>(), error=ref('');
const reading=ref(false), writing=ref(false), locked=computed(()=>props.disabled || reading.value || writing.value);
const models=computed(()=>props.plan.proposal.models.filter(m=>!kind.value || m.kind===kind.value));
const reuseCandidate=computed(()=>props.plan.proposal.models.find(m=>m.menuId && m.key===modelKey.value));
let request:AbortController|undefined, mounted=true;

function invalidate(){ preview.value=undefined; approvedScope.value=undefined; error.value=''; }
function changeModel(){
  const selected=props.plan.proposal.models.find(m=>m.key===modelKey.value);
  if(selected && (selected.kind==='LIST' || selected.kind==='SINGLE'))kind.value=selected.kind;
  reuse.value=false;invalidate();
}
function changeKind(){modelKey.value=undefined;changeModel();}
function close(){if(!writing.value)emit('close');}
function forgetRetry(){
  retry.value=undefined;
  if(account!=null)try{localStorage.removeItem(retryKey);}catch{/* 服务端仍会核对计划状态。 */}
  try{sessionStorage.removeItem(retryKey);}catch{/* 仍可通过服务端计划恢复。 */}
}
async function restartReview(){
  await readPreview();
  if(preview.value)forgetRetry();
}
function resume(){
  let saved:string|null=null;
  if(account!=null)try{saved=localStorage.getItem(retryKey);}catch{/* 改读当前标签页。 */}
  try{
    const value=JSON.parse(saved || sessionStorage.getItem(retryKey) || 'null') as ConfirmedAppend|null;
    if(value?.confirmed===true && /^[a-f0-9]{64}$/.test(value.previewDigest) && value.nodeKey===props.entry.key
      && value.packageVersion===props.plan.packageVersion && Number.isSafeInteger(value.navigationRevision)
      && Number.isSafeInteger(value.planRevision)){ retry.value=value;return; }
  }catch{/* 读不到本机记录时重新预览。 */}
  void readPreview();
}
async function readPreview(){
  if(locked.value || applied.value)return;
  invalidate();
  const scope:NavigationAppendScope={packageVersion:props.plan.packageVersion,planRevision:props.plan.revision,
    navigationRevision:props.navigation.revision,nodeKey:props.entry.key};
  if(kind.value)scope.kind=kind.value;
  if(modelKey.value)scope.modelKey=modelKey.value;
  if(reuse.value && reuseCandidate.value?.menuId){scope.reuseMenuId=reuseCandidate.value.menuId;scope.confirmReuse=true;}
  reading.value=true; request=new AbortController();
  try{
    const result=await siteModelApi.previewNavigationAppend(props.siteId,props.plan,scope,request.signal);
    if(!mounted)return;
    if(result.node.key!==props.entry.key || !result.previewDigest)throw new Error('入口证据已变化，请刷新导航后重新核对。');
    kind.value=result.node.kind;
    modelKey.value=result.node.modelKey || undefined;
    reuse.value=Boolean(result.node.reuseMenuId && result.node.reuseMenuId===reuseCandidate.value?.menuId);
    preview.value=result; approvedScope.value=scope;
  }catch(cause){if(mounted)error.value=cause instanceof Error ? cause.message : '影响范围读取失败，请重试。';}
  finally{if(mounted)reading.value=false;}
}
async function openResult(){
  if(!applied.value || reading.value)return;
  reading.value=true;error.value='';
  try{
    const next=await siteModelApi.get(props.siteId,applied.value.planId);
    if(!mounted)return;
    if(next.id!==applied.value.planId || next.siteId!==props.siteId || next.packageVersion!==props.plan.packageVersion || next.status!=='CONFIRMED')
      throw new Error('新结构已变化，请重新读取当前站点的整站结构。');
    forgetRetry();
    emit('completed',next,applied.value.navigation,props.entry.key);
  }catch(cause){if(mounted)error.value=cause instanceof Error ? cause.message : '新结构读取失败，可重新打开。';}
  finally{if(mounted)reading.value=false;}
}
async function apply(){
  if(locked.value || applied.value)return;
  const confirmed=retry.value || (preview.value && approvedScope.value
    ? {...approvedScope.value,previewDigest:preview.value.previewDigest,confirmed:true as const} : undefined);
  if(!confirmed)return;
  retry.value=confirmed;
  const saved=JSON.stringify(confirmed);
  if(account!=null)try{localStorage.setItem(retryKey,saved);}catch{/* 当前标签页仍可保留确认。 */}
  try{sessionStorage.setItem(retryKey,saved);}catch{/* 当前窗口仍可重试。 */}
  writing.value=true;emit('busy',true);error.value='';
  try{
    const result=await siteModelApi.applyNavigationAppend(props.siteId,props.plan,confirmed);
    if(!mounted)return;
    if(result.navigation.status!=='APPLIED' || !result.navigation.nodes.some(n=>n.key===props.entry.key && n.menuId))
      throw new Error('尚未读到新栏目的应用回执。请核对当前结构后继续。');
    applied.value=result;await openResult();
  }catch(cause){if(mounted)error.value=cause instanceof Error ? cause.message : '未收到追加结果，可按同一份预览重试。';}
  finally{if(mounted){writing.value=false;emit('busy',false);}}
}
onMounted(resume);
onBeforeUnmount(()=>{mounted=false;request?.abort();if(writing.value)emit('busy',false);});
</script>

<style scoped>
.append-options {display:flex;gap:16px;flex-wrap:wrap;margin:16px 0}
.append-options label {display:grid;gap:6px;min-width:180px}
.append-impact {margin:14px 0}
details,li {margin:8px 0}
</style>
