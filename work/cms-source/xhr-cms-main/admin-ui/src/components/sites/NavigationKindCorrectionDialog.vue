<template>
  <el-dialog :model-value="true" title="复核栏目类型" append-to-body width="min(640px, 95vw)"
    :show-close="!writing" :close-on-click-modal="false" :close-on-press-escape="!writing" @close="close">
    <p>影响栏目 · {{ node.cmsName || node.name }} · #{{ node.menuId }}</p>
    <p>栏目类型 · 单页 → 列表</p>
    <p>来源页面 · {{ node.sourcePath || '缺少独立来源页' }}</p>
    <p v-if="reading || writing" role="status">{{ writing ? '正在修正栏目类型并读取新结构。' : '正在核对原页的列表证据。' }}</p>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <template v-if="preview && !applied">
      <el-alert :title="preview.message || (preview.eligible ? '该栏目可修正为列表。' : '当前栏目无法修正。')"
        :type="preview.eligible ? 'info' : 'warning'" :closable="false" show-icon />
      <dl v-if="preview.listEvidence" class="kind-evidence" aria-label="列表源证据">
        <dt>列表位置</dt><dd>{{ preview.listEvidence.listSelector }}</dd>
        <dt>列表卡片</dt><dd>{{ preview.listEvidence.cardCount }} 条，{{ preview.listEvidence.matchingCards }} 条匹配详情证据</dd>
        <dt>详情样例</dt><dd>{{ preview.listEvidence.detailHeading }} · {{ preview.listEvidence.detailPath }}</dd>
      </dl>
      <el-button v-if="node.sourcePath" link type="primary" :disabled="writing" @click="emit('source',node.sourcePath)">打开列表原页</el-button>
      <el-button v-if="preview.listEvidence?.detailPath" link type="primary" :disabled="writing"
        @click="emit('source',preview.listEvidence.detailPath)">打开详情样例</el-button>
      <p v-if="preview.eligible">确认后先核对同栏目的详情字段计划，再继续列表卡片、详情与查询。</p>
    </template>
    <p v-if="applied" role="status">栏目已修正为列表，可重新读取修正后的结构。</p>
    <template #footer>
      <el-button :disabled="writing" @click="close">关闭</el-button>
      <el-button v-if="applied" type="primary" :loading="writing" :disabled="disabled" @click="readApplied">读取新结构</el-button>
      <el-button v-else-if="error && !attempted" :loading="reading" :disabled="disabled" @click="readPreview">重新预览</el-button>
      <el-button v-if="!applied && preview?.eligible && preview.previewToken && preview.listEvidence" type="primary"
        :loading="writing" :disabled="disabled || reading" @click="apply">{{ attempted ? '按原确认重试' : '确认将单页改为列表' }}</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue';
import { siteModelApi, type NavigationKindCorrectionPreview, type SiteModelPlan, type SiteNavigation, type SiteNavigationNode } from '@/api/siteModel';

const props=defineProps<{siteId:number;plan:SiteModelPlan;navigation:SiteNavigation;node:SiteNavigationNode;disabled:boolean}>();
const emit=defineEmits<{
  busy:[boolean]; close:[boolean]; source:[string]; completed:[SiteModelPlan,SiteNavigation,string,string|undefined];
}>();
const scope={packageVersion:props.plan.packageVersion,planRevision:props.plan.revision,navigationRevision:props.navigation.revision,nodeKey:props.node.key};
const preview=ref<NavigationKindCorrectionPreview>(), applied=ref<{planId:string;navigation:SiteNavigation}>();
const reading=ref(false), writing=ref(false), attempted=ref(false), error=ref('');
let mounted=true;

function close(){if(!writing.value)emit('close',attempted.value);}

async function readPreview(){
  if(reading.value || writing.value || attempted.value || props.disabled)return;
  reading.value=true;error.value='';
  try {
    const result=await siteModelApi.previewNavigationKindCorrection(props.siteId,props.plan,scope);
    if(!mounted)return;
    if(result.nodeKey!==scope.nodeKey || (result.eligible && (result.menuId!==props.node.menuId || result.sourcePath!==props.node.sourcePath ||
      result.fromKind!=='SINGLE' || result.toKind!=='LIST' || !result.previewToken || !result.listEvidence)))
      throw new Error('预览与当前栏目或来源不一致，请刷新导航后再核对。');
    preview.value=result;
  } catch(cause){if(mounted)error.value=cause instanceof Error ? cause.message : '无法读取类型修正证据。';}
  finally{if(mounted)reading.value=false;}
}

async function finish(){
  const result=applied.value;
  if(!result)return;
  const next=await siteModelApi.get(props.siteId,result.planId);
  if(!mounted)return;
  if(next.id!==result.planId || next.siteId!==props.siteId || next.packageVersion!==scope.packageVersion ||
    next.status!=='CONFIRMED' || next.revision!==result.navigation.planRevision)
    throw new Error('修正回执与新结构不一致，请重新读取整站结构。');
  emit('busy',false);
  emit('completed',next,result.navigation,scope.nodeKey,preview.value?.listEvidence?.detailPath);
}

async function apply(){
  const approved=preview.value;
  if(writing.value || reading.value || props.disabled || applied.value || !approved?.eligible || !approved.previewToken || !approved.listEvidence)return;
  attempted.value=true;writing.value=true;error.value='';emit('busy',true);
  try {
    const result=await siteModelApi.applyNavigationKindCorrection(props.siteId,props.plan,{...scope,previewToken:approved.previewToken,confirm:true});
    if(!mounted)return;
    const corrected=result.navigation.nodes.find(node=>node.key===scope.nodeKey);
    if(result.navigation.status!=='APPLIED' || corrected?.menuId!==approved.menuId || corrected.kind!=='LIST' || corrected.sourcePath!==approved.sourcePath)
      throw new Error('尚未收到此栏目改为列表的回执，请刷新结构后核对实际结果。');
    applied.value=result;
    await finish();
  } catch(cause){if(mounted)error.value=cause instanceof Error ? cause.message : '类型修正结果暂未确认。';}
  finally{if(mounted){writing.value=false;emit('busy',false);}}
}

async function readApplied(){
  if(writing.value || props.disabled || !applied.value)return;
  writing.value=true;error.value='';emit('busy',true);
  try {await finish();}
  catch(cause){if(mounted)error.value=cause instanceof Error ? cause.message : '新结构读取失败。';}
  finally{if(mounted){writing.value=false;emit('busy',false);}}
}

onMounted(readPreview);
onBeforeUnmount(()=>{mounted=false;if(writing.value)emit('busy',false);});
</script>

<style scoped>
.kind-evidence { display:grid; grid-template-columns:max-content 1fr; gap:6px 12px; }
.kind-evidence dt { font-weight:600; }
.kind-evidence dd { margin:0; overflow-wrap:anywhere; }
</style>
