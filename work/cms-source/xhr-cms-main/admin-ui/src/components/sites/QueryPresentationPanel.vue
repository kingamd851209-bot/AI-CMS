<script setup lang="ts">
import {computed,onBeforeUnmount,ref,watch} from 'vue';
import {ElMessageBox} from 'element-plus';
import NativeFormImportPanel from '@/components/sites/NativeFormImportPanel.vue';
import type {NativeFormReceipt} from '@/api/nativeFormImport';
import type {PairingRequest} from '@/api/templateListPairing';
import {applyQueryFormTarget,previewQueryFormTarget,queryPresentationEvidence,type QueryPresentationEvidence,type QueryPresentationRequest,type QueryDisplaySource,type QueryPagination,type QueryCounter,type QueryFormAction,type QueryFormActionTargetResult} from '@/api/templateQueryBinding';
const props=defineProps<{siteId:number;sourcePlanId:string;pairing:PairingRequest;cardId:string;disabled:boolean;navigationPlanId?:string}>();
const emit=defineEmits<{change:[value:QueryPresentationRequest|null];busy:[value:boolean]}>();
const evidence=ref<QueryPresentationEvidence|null>(null);const loading=ref(false);const error=ref('');let generation=0;
const choices=ref<Record<string,string>>({});const reasons=ref<Record<string,string>>({});const replaceConfirmed=ref(false);
const carouselChoice=ref<'dynamic'|'original'|''>('');
const carouselEvidence=computed(()=>evidence.value?.carouselCounter);
const carouselSourceIds=computed(()=>new Set(carouselEvidence.value?.sourceIds));
const carouselKeepReason='已核对轮播序号与本页总数，保留原页数字。';
const featuredEnabled=ref(false);
const featuredFields=ref({selector:'',imageField:'',titleField:'',summaryField:''});
const featuredOptions=computed(()=>[
 {key:'imageField' as const,label:'轮播封面字段',types:['Image']},
 {key:'titleField' as const,label:'轮播标题字段',types:['SingleLine']},
 {key:'summaryField' as const,label:'轮播摘要字段（可选）',types:['SingleLine','MultiLine']},
]);
const featuredCarousel=computed(()=>{
 const {selector,imageField,titleField,summaryField}=featuredFields.value;
 return featuredEnabled.value&&selector.trim()&&imageField&&titleField
  ? {selector:selector.trim(),imageField,titleField,...(summaryField?{summaryField}:{})} : undefined;
});
const actionChoices=ref<Record<string,{fieldName:string;inputId:string;targetMenuId:number|undefined}>>({});
const targetPreview=ref<{sourceId:string;result:QueryFormActionTargetResult}|null>(null);
const targetError=ref<{sourceId:string;message:string}|null>(null);
const formReceipts=ref<Record<string,{scope:string;receipt:NativeFormReceipt}>>({});
const readyForms=ref<Record<string,number>>({});
const formImportBusy=ref(false);
const pagingEnabled=ref(false);const pagerScope=ref('');const numberSelector=ref('');const activeClass=ref('');const disabledClass=ref('');
const linkSelectors=ref<Record<string,string>>({prev:'',next:'',first:'',last:''});
const counterRows=ref<Record<string,{selector:string;start:number;end:number}>>({pageCount:{selector:'',start:0,end:1},total:{selector:'',start:0,end:1},page:{selector:'',start:0,end:1}});
const ignoredRows=ref<{selector:string;reason:string}[]>([]);
const sidebarEnabled=ref(false), sidebarSelector=ref('');
const deferRegion=ref(false), deferredSelector=ref(''), deferredReason=ref('');
const deferredRegion=computed(()=>{
 const selector=deferredSelector.value.trim(), reason=deferredReason.value.trim();
 return deferRegion.value && selector.length<=200 && /^[.#][A-Za-z_][A-Za-z0-9_-]*(?: *> *(?:ul|ol))?$/.test(selector) && reason && reason.length<=300
  ? {selector,reason} : undefined;
});
const blocked=computed(()=>props.disabled||loading.value||formImportBusy.value);
const detailLinks=computed(()=>new Set(evidence.value?.detailLinkSourceIds || []));
const actionsById=computed(()=>Object.fromEntries((evidence.value?.actions || []).map(action=>[action.sourceId,action])));
const actionFields=computed(()=>evidence.value?.fields.filter(field=>field.fieldType==='SingleLine') || []);
const visibleSources=computed(()=>evidence.value?.sources.filter(source=>source.kind!=='HTML_REGION_PENDING'&&!carouselSourceIds.value.has(source.id))||[]);
const required=computed(()=>visibleSources.value.filter(source=>source.kind!=='HTML_REGION'));
const missing=computed(()=>required.value.filter(source=>{
 const choice=choices.value[source.id];
 if(choice==='$action')return !submittedForm(source.id);
 return !choice || choice==='$shared'&&!reasons.value[source.id]?.trim();
}).length);
const labels:Record<string,string>={TEXT:'文字',ATTRIBUTE_ALT:'图片替代文字',ATTRIBUTE_TITLE:'提示文字',LINK:'链接',IMAGE:'图片',VIDEO_SRC:'视频源',VIDEO_SOURCE:'视频源',VIDEO_POSTER:'视频封面',HTML_REGION:'正文区域',SCRIPT_PENDING:'脚本来源待核对'};
const counterLabels:Record<string,string>={pageCount:'总页数',total:'总条数',page:'当前页'};
const linkLabels:Record<string,string>={prev:'上一页',next:'下一页',first:'首页（可选）',last:'末页（可选）'};
function resetSidebar(){sidebarEnabled.value=false;sidebarSelector.value='';}
function prefillPagination(draft:QueryPagination|null|undefined){
 if(!draft || pagingEnabled.value || [pagerScope.value,numberSelector.value,activeClass.value,disabledClass.value,...Object.values(linkSelectors.value)].some(value=>value.trim())
   || ignoredRows.value.length || Object.values(counterRows.value).some(row=>row.selector.trim()))return;
 pagerScope.value=draft.containerSelector;
 numberSelector.value=draft.numberSelector;
 activeClass.value=draft.activeClass;
 disabledClass.value=draft.disabledClass;
 linkSelectors.value={prev:'',next:'',first:'',last:'',...draft.links};
 ignoredRows.value=Object.entries(draft.ignoredControls).map(([selector,reason])=>({selector,reason}));
}
function reset(){generation++;loading.value=false;evidence.value=null;choices.value={};reasons.value={};actionChoices.value={};formReceipts.value={};readyForms.value={};formImportBusy.value=false;replaceConfirmed.value=false;pagingEnabled.value=false;pagerScope.value='';numberSelector.value='';activeClass.value='';disabledClass.value='';linkSelectors.value={prev:'',next:'',first:'',last:''};counterRows.value={pageCount:{selector:'',start:0,end:1},total:{selector:'',start:0,end:1},page:{selector:'',start:0,end:1}};ignoredRows.value=[];resetSidebar();deferRegion.value=false;deferredSelector.value='';deferredReason.value='';error.value='';emit('change',null);}
watch(()=>[props.siteId,props.sourcePlanId,props.pairing.listDigest,props.pairing.containerId,props.cardId],reset);
watch(()=>[props.siteId,props.sourcePlanId,props.pairing.listDigest,props.pairing.containerId,props.cardId],()=>{
 featuredEnabled.value=false;
 featuredFields.value={selector:'',imageField:'',titleField:'',summaryField:''};
});
watch(()=>props.navigationPlanId,resetSidebar);
watch(()=>loading.value||formImportBusy.value,value=>emit('busy',value),{flush:'sync'});
onBeforeUnmount(()=>{generation++;if(loading.value||formImportBusy.value)emit('busy',false);});
watch([choices,actionChoices],()=>{
 for(const [sourceId,value] of Object.entries(formReceipts.value)){
  if(value.scope!==formScope(sourceId)){delete formReceipts.value[sourceId];delete readyForms.value[sourceId];}
 }
},{deep:true,flush:'sync'});
watch([choices,actionChoices,formReceipts],()=>{targetPreview.value=null;targetError.value=null;},{deep:true});
function formScope(sourceId:string) {
 const action=formAction(sourceId);
 return choices.value[sourceId]==='$action' && action ? JSON.stringify(action) : '';
}
function setLinkedForm(sourceId:string,receipt:NativeFormReceipt|null) {
 const scope=formScope(sourceId);
 delete readyForms.value[sourceId];
 if(receipt && scope)formReceipts.value[sourceId]={scope,receipt};
 else delete formReceipts.value[sourceId];
}
function linkedForm(sourceId:string) {
 const saved=formReceipts.value[sourceId];
 const action=formAction(sourceId);
 if(!saved||!action||saved.scope!==formScope(sourceId))return null;
 const {receipt}=saved;
 return receipt.menuId===action.targetMenuId && receipt.sourcePath===actionsById.value[sourceId]?.targetPath
  && receipt.fields.some(field=>field.sourceKey===action.inputId) ? receipt : null;
}
function submittedForm(sourceId:string) {
 const receipt=linkedForm(sourceId);
 return receipt && readyForms.value[sourceId]===receipt.categoryId ? receipt : null;
}
function formAction(sourceId:string):QueryFormAction|null {
 const action=actionChoices.value[sourceId];
 if(!action?.targetMenuId||!action.fieldName||!action.inputId)return null;
 return{sourceId,fieldName:action.fieldName,inputId:action.inputId,targetMenuId:action.targetMenuId};
}
async function prepareTarget(sourceId:string,apply:boolean) {
 const action=formAction(sourceId);
 const previous=targetPreview.value;
 if(blocked.value||!action||apply&&(previous?.sourceId!==sourceId||previous.result.applied))return;
 const version=generation;
 const {siteId,sourcePlanId,pairing,cardId}=props;
 const request={sourcePlanId:sourcePlanId.trim(),pairing,cardId,action,formCategoryId:linkedForm(sourceId)?.categoryId};
 if(apply&&!request.formCategoryId)return;
 loading.value=true;targetError.value=null;
 try {
  if(apply&&previous){
   const label=evidence.value?.targetMenus?.find(menu=>menu.id===action.targetMenuId)?.name||'栏目';
   const impact=previous.result.previousTemplate ? `替换当前模板 ${previous.result.previousTemplate}` : '设置目标单页的首个模板';
   const message=`将列表 ${pairing.listSourcePath} 的「${actionsById.value[sourceId]?.label||'表单链接'}」接到 ${label}（${action.targetMenuId}），来源 ${actionsById.value[sourceId]?.targetPath}，公开路径 ${previous.result.publicUrl}。记录字段 ${action.fieldName} 将传到输入框 ${action.inputId}。将${impact}，启用原生表单分类 ${request.formCategoryId} 的验证码和提交。`;
   try{await ElMessageBox.confirm(message,'确认应用目标表单模板',{type:'warning'});}catch{return;}
   if(version!==generation||previous!==targetPreview.value)return;
  }
  const result=apply&&previous
   ? await applyQueryFormTarget(siteId,{...request,expectedTemplate:previous.result.previousTemplate,confirmed:true})
   : await previewQueryFormTarget(siteId,request);
  if(version===generation){
   targetPreview.value={sourceId,result};
   if(result.applied&&result.submissionReady&&result.formCategoryId===request.formCategoryId&&request.formCategoryId)
    readyForms.value[sourceId]=request.formCategoryId;
   else delete readyForms.value[sourceId];
  }
 }catch(cause){
  if(version===generation){delete readyForms.value[sourceId];targetError.value={sourceId,message:cause instanceof Error ? cause.message : String(cause)};}
 }finally{if(version===generation)loading.value=false;}
}
async function load(){if(blocked.value||!props.sourcePlanId.trim())return;const version=++generation;loading.value=true;error.value='';evidence.value=null;replaceConfirmed.value=false;carouselChoice.value='';
 try{const data=await queryPresentationEvidence(props.siteId,props.sourcePlanId.trim(),props.pairing,props.cardId);if(version!==generation)return;evidence.value=data;choices.value=Object.fromEntries((data.detailLinkSourceIds || []).map(id=>[id,'$detail']));reasons.value={};
  actionChoices.value=Object.fromEntries((data.actions || []).map(action=>[action.sourceId,{fieldName:'',inputId:action.suggestedInputId || '',targetMenuId:undefined}]));
  prefillPagination(data.paginationDraft);
  formReceipts.value={};readyForms.value={};
 }
 catch(cause){if(version===generation)error.value=cause instanceof Error?cause.message:String(cause);}finally{if(version===generation)loading.value=false;}}
function compatible(source:QueryDisplaySource){const types=source.kind==='TEXT'?['SingleLine','MultiLine','DateTime']:['ATTRIBUTE_ALT','ATTRIBUTE_TITLE'].includes(source.kind)?['SingleLine']:source.kind==='HTML_REGION'?['Editor']:['IMAGE','VIDEO_POSTER'].includes(source.kind)?['Image']:['VIDEO_SRC','VIDEO_SOURCE'].includes(source.kind)?['Video']:[];return evidence.value?.fields.filter(field=>types.includes(field.fieldType))||[];}
const pagination=computed<QueryPagination|null>(()=>{
 if(!pagingEnabled.value)return null;
 if(!pagerScope.value.trim()||!numberSelector.value.trim()||!activeClass.value.trim()||!disabledClass.value.trim()||!linkSelectors.value.prev?.trim()||!linkSelectors.value.next?.trim())return null;
 const links=Object.fromEntries(Object.entries(linkSelectors.value).filter(([,value])=>value.trim()).map(([key,value])=>[key,value.trim()]));
 const counters:Record<string,QueryCounter>={};for(const [role,value]of Object.entries(counterRows.value)){if(!value.selector.trim())continue;if(value.start<0||value.end<=value.start)return null;counters[role]={selector:value.selector.trim(),start:value.start,end:value.end};}
 const ignored:Record<string,string>={};for(const row of ignoredRows.value){if(!row.selector.trim()||!row.reason.trim())return null;if(ignored[row.selector.trim()])return null;ignored[row.selector.trim()]=row.reason.trim();}
 return{containerSelector:pagerScope.value.trim(),numberSelector:numberSelector.value.trim(),activeClass:activeClass.value.trim(),disabledClass:disabledClass.value.trim(),links,counters,ignoredControls:ignored};
});
const unpaged=ref(false);
const currentMenuHeading=ref(false);
watch(()=>[props.siteId,props.sourcePlanId,props.pairing.menuId,props.pairing.listDigest,props.cardId,props.navigationPlanId],()=>{unpaged.value=false;currentMenuHeading.value=false;});
const value=computed<QueryPresentationRequest|null>(()=>{
 if(!evidence.value||!replaceConfirmed.value||missing.value||!unpaged.value&&!pagination.value||deferRegion.value&&(!deferredRegion.value||unpaged.value))return null;
 if(carouselEvidence.value&&!carouselChoice.value)return null;
 if(featuredEnabled.value&&!featuredCarousel.value)return null;
 const categorySidebar=sidebarEnabled.value && props.navigationPlanId && sidebarSelector.value.trim()
  ? {selector:sidebarSelector.value.trim(),navigationPlanId:props.navigationPlanId} : undefined;
 if(sidebarEnabled.value && !categorySidebar)return null;
 const bindings:{sourceId:string;fieldName:string}[]=[];const sharedStatic:Record<string,string>={};const actions:QueryFormAction[]=[];
 for(const source of evidence.value.sources){
  if(carouselSourceIds.value.has(source.id)){
   if(carouselChoice.value==='original')sharedStatic[source.id]=carouselKeepReason;
   continue;
  }
  const chosen=choices.value[source.id];if(!chosen)continue;
  if(chosen==='$shared'){if(!reasons.value[source.id]?.trim())return null;sharedStatic[source.id]=reasons.value[source.id]!.trim();}
  else if(chosen==='$action'){
   const action=formAction(source.id);
   const form=submittedForm(source.id);
   if(!action||!form)return null;
   actions.push({...action,formCategoryId:form.categoryId});
  }else bindings.push({sourceId:source.id,fieldName:chosen});
 }
 if(!bindings.some(binding=>binding.fieldName==='$detail')||!bindings.some(binding=>binding.fieldName!=='$detail'))return null;
 return{pairing:props.pairing,cardId:props.cardId,bindings,sharedStatic,replaceGroupConfirmed:true,pagination:unpaged.value ? null : pagination.value,...(unpaged.value ? {unpaged:true as const, fixedHeadingPlanId:currentMenuHeading.value ? props.navigationPlanId : undefined} : {}),...(actions.length ? {actions} : {}),...(categorySidebar ? {categorySidebar} : {}),...(deferredRegion.value ? {deferredRegion:deferredRegion.value} : {}),...(carouselChoice.value==='dynamic'&&carouselEvidence.value ? {carouselCounter:true as const} : {}),...(featuredCarousel.value ? {featuredCarousel:featuredCarousel.value} : {})};
});
watch(value,result=>emit('change',result),{deep:true});
</script>
<template>
 <section class="query-presentation" aria-label="查询列表与分页确认">
  <h4>确认动态卡片与分页</h4>
  <p>沿用上方已确认容器和所选原型。每处业务文字、图片和链接都要选择正式字段或明确声明共用固定装饰；不能把原静态产品名、图和详情复制到所有行。</p>
  <p>当前列表：{{pairing.listSourcePath}}；原型：{{cardId}}；关联内容计划：{{sourcePlanId||'请先选择已确认计划'}}。</p>
  <el-button :disabled="blocked||!sourcePlanId" :loading="loading" @click="load">读取原型显示来源</el-button>
  <el-alert v-if="error" type="error" :title="error" :closable="false"/>
  <template v-if="evidence">
   <p>列表容器 {{evidence.containerSelector}} · 模板卡片 {{evidence.cardSelector}}。原{{evidence.originalCards}}张静态卡片将由当前栏目的分页数据替换。<span v-if="evidence.detailSourcePath">已核对详情来源 {{evidence.detailSourcePath}}。</span></p>
   <p>尚有{{missing}}处业务来源未明确。日期只接入原页的文字位置，沿用已确认字段的精度。图片与视频使用已支持的扩展资源，未知脚本、关联显示及复杂样式仍需核对。</p>
   <template v-if="carouselEvidence">
    <h5>审核轮播序号与本页总数</h5>
    <ul>
     <li>原页序号 {{carouselEvidence.currentSample}} · <code>{{carouselEvidence.currentSelector}}</code></li>
     <li>原页总数 {{carouselEvidence.totalSample}} · <code>{{carouselEvidence.totalSelector}}</code></li>
    </ul>
    <el-radio-group v-model="carouselChoice" :disabled="blocked" aria-label="轮播序号与本页总数">
     <el-radio value="dynamic">随本页卡片更新数字</el-radio>
     <el-radio value="original">保留原页数字</el-radio>
    </el-radio-group>
    <p v-if="carouselChoice==='dynamic'">每页从 1 开始，按本页实际展示的卡片数计算总数。</p>
    <p v-if="carouselChoice==='original'">所有卡片共用原型上的数字。</p>
   </template>
   <el-table :data="visibleSources" row-key="id">
    <el-table-column label="原来源" min-width="220"><template #default="{row}"><strong>{{labels[row.kind]||row.kind}}</strong><p>{{row.selector}}</p><p>{{row.rawValue.slice(0,200)}}{{row.rawValue.length>200?'（截取，完整原值服务器保留）':''}}</p></template></el-table-column>
    <el-table-column label="此处每行显示什么" min-width="270">
     <template #default="{row}">
      <span v-if="detailLinks.has(row.id)">当前记录的真实详情链接</span>
      <el-select v-else v-model="choices[row.id]" clearable placeholder="明确选择此处用途" :disabled="blocked" :aria-label="`卡片来源 ${row.id} 对应内容`">
       <el-option v-if="row.kind==='LINK' && !detailLinks.size" label="本站真实详情链接" value="$detail"/>
       <el-option v-if="actionsById[row.id]" label="用当前记录预填目标表单" value="$action"/>
       <el-option v-for="field in compatible(row)" :key="field.fieldName" :value="field.fieldName" :label="`${field.name||field.fieldName} · ${field.fieldType} · ${field.fieldName}`"/>
       <el-option :label="row.kind==='LINK'?'每行保留同一固定链接（须说明）':'每行共用的固定内容（须说明）'" value="$shared"/>
      </el-select>
      <el-input v-if="choices[row.id]==='$shared'" v-model="reasons[row.id]" :disabled="blocked" :maxlength="300" placeholder="说明为何各条记录共用此值" :aria-label="`来源 ${row.id} 共用说明`"/>
      <template v-if="actionsById[row.id] && !detailLinks.has(row.id)">
       <p>{{actionsById[row.id]?.label || '链接'}} → {{actionsById[row.id]?.targetTitle || '表单'}} · {{actionsById[row.id]?.targetPath}}</p>
       <template v-if="choices[row.id]==='$action' && actionChoices[row.id]">
        <el-select v-model="actionChoices[row.id]!.fieldName" :disabled="blocked" placeholder="选择当前记录的值" :aria-label="`链接 ${row.id} 的记录字段`">
         <el-option v-for="field in actionFields" :key="field.fieldName" :value="field.fieldName" :label="field.name||field.fieldName"/>
        </el-select>
        <el-select v-model="actionChoices[row.id]!.inputId" :disabled="blocked" placeholder="选择接收值的表单输入框" :aria-label="`链接 ${row.id} 的目标输入框`">
         <el-option v-for="field in actionsById[row.id]?.fields" :key="field.inputId" :value="field.inputId" :label="`${field.label || field.inputId}（${field.parameterName}）`"/>
        </el-select>
        <el-select v-model="actionChoices[row.id]!.targetMenuId" :disabled="blocked" placeholder="选择同站目标表单栏目" :aria-label="`链接 ${row.id} 的目标栏目`">
         <el-option v-for="menu in evidence.targetMenus" :key="menu.id" :value="menu.id" :label="`${menu.name}（ID ${menu.id}）`"/>
        </el-select>
        <p>在栏目管理核对目标单页的前台路由和发布状态。下方可审核原生表单字段。</p>
        <p v-if="!evidence.targetMenus?.length">配置目标单页栏目后，重新读取原型显示来源。</p>
        <el-alert v-if="targetError?.sourceId===row.id" :title="targetError?.message" type="error" :closable="false"/>
        <template v-if="targetPreview && targetPreview.sourceId===row.id">
         <p>{{targetPreview.result.applied && targetPreview.result.submissionReady ? '已应用预填和原生提交模板 · 待前台验收' : targetPreview.result.message}} · {{targetPreview.result.publicUrl}}</p>
         <p v-if="!targetPreview.result.applied">当前模板 {{targetPreview.result.previousTemplate || '未配置'}} · 待应用模板 {{targetPreview.result.templatePath}}</p>
        </template>
        <div class="controls">
         <el-button :disabled="blocked||!formAction(row.id)" @click="prepareTarget(row.id,false)">{{ linkedForm(row.id) ? '检查预填和提交模板' : '检查预填模板' }}</el-button>
         <el-button v-if="linkedForm(row.id) && targetPreview?.sourceId===row.id && !targetPreview?.result.applied" type="primary" :disabled="blocked" @click="prepareTarget(row.id,true)">确认应用预填和提交模板</el-button>
        </div>
        <p v-if="submittedForm(row.id)">原生提交模板已应用。请从公开列表核对预填值、验证码和后台提交记录。</p>
        <p v-else-if="linkedForm(row.id)">原生表单已审核。应用预填和提交模板后，可保存列表。</p>
        <p v-else>提交接口待配置。审核包含所选输入框的原生表单后，可接通预填和提交。</p>
       </template>
      </template>
     </template>
    </el-table-column>
   </el-table>
   <template v-for="action in evidence.actions" :key="action.sourceId">
    <NativeFormImportPanel v-if="formScope(action.sourceId)" :key="formScope(action.sourceId)" :site-id="siteId" :source-path="action.targetPath" :menu-id="actionChoices[action.sourceId]!.targetMenuId!" :menu-label="evidence.targetMenus?.find(menu=>menu.id===actionChoices[action.sourceId]?.targetMenuId)?.name" :prefill-input-id="actionChoices[action.sourceId]?.inputId" :disabled="blocked" @busy="formImportBusy=$event" @receipt="setLinkedForm(action.sourceId,$event)" />
   </template>
   <el-checkbox v-model="replaceConfirmed" :disabled="blocked" aria-label="确认替换整组静态卡片">确认：动态替换上述{{evidence.originalCards}}张原卡片，保留固定外层和已明确的共用装饰，不修改产品数据或原始包。</el-checkbox>
   <h5>置顶轮播</h5>
   <el-checkbox v-model="featuredEnabled" :disabled="blocked">用已发布的置顶记录生成轮播</el-checkbox>
   <template v-if="featuredEnabled">
    <label>原页轮播容器
     <el-input v-model="featuredFields.selector" :disabled="blocked" aria-label="原页置顶轮播容器" placeholder="填写唯一轮播容器的 CSS 选择器" />
    </label>
    <label v-for="option in featuredOptions" :key="option.key">{{option.label}}
     <el-select v-model="featuredFields[option.key]" clearable :disabled="blocked" :aria-label="option.label">
      <el-option v-for="field in evidence.fields.filter(item=>option.types.includes(item.fieldType))" :key="field.fieldName" :value="field.fieldName" :label="field.name||field.fieldName" />
     </el-select>
    </label>
   </template>
   <h5>审核分类侧栏</h5>
   <el-checkbox v-model="sidebarEnabled" :disabled="blocked || !navigationPlanId">接入已核对的分类侧栏</el-checkbox>
   <p v-if="!navigationPlanId">需要时，请先在整站结构选择并应用导航计划。</p>
   <template v-if="sidebarEnabled">
    <p>导航计划 {{navigationPlanId}}。保留原有分类与顺序，标记当前栏目。未接入正式路由的分类仅显示标签。</p>
    <label>原页侧栏容器
     <el-input v-model="sidebarSelector" :disabled="blocked" aria-label="分类侧栏原页位置" placeholder="填写唯一侧栏容器的 CSS 选择器" />
    </label>
    <p v-if="!sidebarSelector.trim()" role="status">请核对原页并填写侧栏位置，再保存和检查编译结果。</p>
   </template>
   <el-checkbox v-model="deferRegion" :disabled="blocked">暂不发布一个已核对的原页区域</el-checkbox>
   <template v-if="deferRegion">
    <div class="controls">
     <label>原页区域位置
      <el-input v-model="deferredSelector" :disabled="blocked" :maxlength="200" aria-label="暂缓发布区域的原页位置" placeholder="例如 .cards 或 .cards > ul" />
     </label>
     <label>暂缓原因
      <el-input v-model="deferredReason" :disabled="blocked" :maxlength="300" aria-label="暂缓发布区域的原因" placeholder="说明该区域暂不公开的原因" />
     </label>
    </div>
    <p>公开列表将省略该区域。恢复时须再次核对原页、保存新的列表方案并应用模板。</p>
    <p v-if="!deferredRegion" role="status">请填写能唯一定位的原页 ID、类选择器或其直接子列表（ul 或 ol），并说明暂缓原因。</p>
   </template>
   <h5>列表浏览方式</h5>
   <el-checkbox v-model="unpaged" :disabled="blocked">原页无筛选及分页，使用固定路由</el-checkbox>
   <p v-if="unpaged">先在上方用 body 检查本页，再选择只接通动态卡片。固定路由最多展示 100 条本栏目已发布记录。</p>
   <el-checkbox v-if="unpaged && navigationPlanId" v-model="currentMenuHeading" :disabled="blocked">主体标题使用当前栏目名称</el-checkbox>
   <template v-else>
   <p>只支持原生占位锚点。原JS事件/混合query不推断；数字页模板将生成当前范围的真实页码，保留选中样式及前后页图标。</p>
   <p v-if="evidence.paginationDraft" role="status">已从原页找到唯一可核对的分页草稿。勾选后可编辑数字样式和前后页位置。原省略号占位如有识别，会列入待确认移除的控件。</p>
   <el-checkbox v-model="pagingEnabled" :disabled="blocked" aria-label="明确接通已支持分页">接通已支持分页（完整列表必选）</el-checkbox>
   <template v-if="pagingEnabled">
    <div class="controls"><el-input v-model="pagerScope" :disabled="blocked" placeholder="分页容器位置" aria-label="分页容器位置"/><el-input v-model="numberSelector" :disabled="blocked" placeholder="容器内数字页模板位置" aria-label="数字页模板位置"/><el-input v-model="activeClass" :disabled="blocked" placeholder="按原页确认活动态class" aria-label="分页活动样式"/><el-input v-model="disabledClass" :disabled="blocked" placeholder="按原页确认禁用态class" aria-label="分页禁用样式"/></div>
    <div class="controls"><el-input v-for="role in ['prev','next','first','last']" :key="role" v-model="linkSelectors[role]" :disabled="blocked" :placeholder="`${linkLabels[role]}锚点位置`" :aria-label="`${linkLabels[role]}锚点位置`"/></div>
    <el-collapse><el-collapse-item title="计数数字与尚不支持的分页控件" name="counters">
     <p>只选择原纯文本中的数字区间（起始含、结束不含），保留“第/共/页/条”等原文。位置不确定可不接该计数；不能把旧数字当真实结果。</p>
     <div v-for="role in ['pageCount','total','page']" :key="role" class="controls"><span>{{counterLabels[role]}}</span><el-input v-model="counterRows[role]!.selector" :disabled="blocked" :placeholder="`${counterLabels[role]}原文字位置（可选）`" :aria-label="`${counterLabels[role]}原文字位置`"/><el-input-number v-model="counterRows[role]!.start" :disabled="blocked" :min="0" :max="10000" :aria-label="`${counterLabels[role]}数字起始`"/><el-input-number v-model="counterRows[role]!.end" :disabled="blocked" :min="1" :max="10000" :aria-label="`${counterLabels[role]}数字结束`"/></div>
     <p>原跳页输入、GO或省略装饰尚未接通时，须逐项确认移除及影响；未知控件不能静默丢弃。</p>
     <div v-for="(row,index) in ignoredRows" :key="index" class="controls"><el-input v-model="row.selector" :disabled="blocked" placeholder="需移除的控件位置" :aria-label="`忽略分页控件${index+1}位置`"/><el-input v-model="row.reason" :disabled="blocked" placeholder="明确说明原功能不再可用的影响" :aria-label="`忽略分页控件${index+1}原因`"/><el-button :disabled="blocked" @click="ignoredRows.splice(index,1)">撤销忽略</el-button></div><el-button :disabled="blocked||ignoredRows.length>=20" @click="ignoredRows.push({selector:'',reason:''})">明确忽略一个未支持控件</el-button>
    </el-collapse-item></el-collapse>
   </template>
   </template>
   <el-alert :type="value?'success':'warning'" :closable="false" :title="value?'列表显示已确认，可以核对并保存。':carouselEvidence&&!carouselChoice?'请选择轮播数字随本页卡片更新或保留原页数字。':'还需明确业务来源、真实详情链接、整组替换影响及列表浏览方式。'"/>
  </template>
 </section>
</template>
<style scoped>
.query-presentation{margin-top:16px;border-top:1px solid var(--el-border-color);padding-top:12px}.controls{display:flex;flex-wrap:wrap;gap:12px;align-items:center;margin:12px 0}.controls .el-input{width:min(340px,100%)}p{overflow-wrap:anywhere;white-space:pre-wrap}.query-presentation :deep(.cell){overflow-wrap:anywhere}.el-select{width:100%;margin:4px 0}
</style>
