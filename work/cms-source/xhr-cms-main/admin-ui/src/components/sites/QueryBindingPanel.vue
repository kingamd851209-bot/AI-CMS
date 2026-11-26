<script setup lang="ts">
import { computed,onBeforeUnmount,ref,watch } from 'vue';
import {useSiteContextStore} from '@/stores/siteContext';
import PublicListApplicationPanel from '@/components/sites/PublicListApplicationPanel.vue';
import QueryPresentationPanel from '@/components/sites/QueryPresentationPanel.vue';
import type { PairingRequest } from '@/api/templateListPairing';
import type { QueryFeaturedCarousel, QueryPresentationRequest } from '@/api/templateQueryBinding';
import { ElMessageBox } from 'element-plus';
import { recentFieldPlans,type FieldPlanSummary } from '@/api/templateFieldPlan';
import { queryCandidates,queryFields,confirmQueryBindings,readQueryBindings,compileQueryBindings,type QueryCandidates,type QueryFields,type QueryMapping,type QuerySnapshot,type CompiledQuery } from '@/api/templateQueryBinding';
const props=defineProps<{siteId:number;menuId:number;sourcePath:string;detailPath:string|null;disabled:boolean;pairing?:PairingRequest;cardId?:string;preferredPlanId?:string;navigationPlanId?:string}>();
const emit=defineEmits<{busy:[value:boolean];'public-list-changed':[]}>();
const siteContext=useSiteContextStore();const memoryNotice=ref('');
const selector=ref('');const sourcePlanId=ref('');const savedPlans=ref<FieldPlanSummary[]>([]);const knownId=ref('');
const candidates=ref<QueryCandidates|null>(null);const fields=ref<QueryFields|null>(null);const snapshot=ref<QuerySnapshot|null>(null);const compiled=ref<CompiledQuery|null>(null);
const includePresentation=ref(true);const presentation=ref<QueryPresentationRequest|null>(null);const presentationBusy=ref(false);const pageSize=ref(10);
const publicBusy=ref(false);
const featuredDraft=ref<QueryFeaturedCarousel>();
const featuredDialog=ref(false);
const queryMode=ref<'controls'|'plain'>('controls');
const noQueryControls=ref(false);
const plainPagination=computed(()=>queryMode.value==='plain');
const pageOnly=computed(()=>plainPagination.value||noQueryControls.value);
const plainWarnings=computed(()=>plainPagination.value ? candidates.value?.warnings.filter(message=>message.startsWith('整页无筛选分页时')) || [] : []);
const error=ref('');const loading=ref(false);let generation=0;
interface Row {enabled:boolean;role:'FILTER'|'SORT'|'SUBMIT';fieldName:string;operator:string;values:Record<string,string>}
const rows=ref<Record<string,Row>>({});const blocked=computed(()=>props.disabled||loading.value||presentationBusy.value||publicBusy.value);
const filters=computed(()=>fields.value?.filters.filter(field=>!field.select&&!field.inputMultiple)||[]);
const sorting=computed(()=>fields.value?.sorting.filter(field=>field.fieldName.startsWith('ext_'))||[]);
const chosen=computed(()=>candidates.value?.controls.filter(control=>rows.value[control.id]?.enabled)||[]);
const fixedListSaved=computed(()=>{
 const request=snapshot.value?.presentation?.request;
 return !!request && 'unpaged' in request && request.unpaged;
});
const savedPresentation=computed(()=>{
 const saved=snapshot.value;
 const request=saved?.presentation?.request;
 return saved&&!saved.sharedLayout&&request&&'pairing' in request ? request : undefined;
});
const canSaveFeatured=computed(()=>!!(savedPresentation.value&&featuredDraft.value?.selector.trim()
 && featuredDraft.value.imageField&&featuredDraft.value.titleField));
watch(()=>loading.value||presentationBusy.value||publicBusy.value,value=>emit('busy',value),{flush:'sync'});
onBeforeUnmount(()=>{generation++;if(loading.value||presentationBusy.value||publicBusy.value)emit('busy',false);});
watch([presentation,includePresentation,pageSize,noQueryControls],()=>{snapshot.value=null;compiled.value=null;},{deep:true});
function reset(){memoryNotice.value='';generation++;loading.value=false;presentationBusy.value=false;publicBusy.value=false;candidates.value=null;fields.value=null;rows.value={};snapshot.value=null;compiled.value=null;error.value='';knownId.value='';sourcePlanId.value=props.preferredPlanId || '';savedPlans.value=[];presentation.value=null;noQueryControls.value=false;}
function memoryKey(){const admin=siteContext.currentAdmin?.id;return admin&&props.siteId&&props.menuId&&props.sourcePath?'cms.workbench.query-id.v1:'+JSON.stringify([admin,props.siteId,props.sourcePath,props.menuId]):null;}
function restoreQueryId(){const key=memoryKey();if(!key)return;try{const id=localStorage.getItem(key);if(id&&/^[A-Za-z0-9-]{1,64}$/.test(id)){knownId.value=id;memoryNotice.value='已恢复本页上次成功的查询计划ID；点击重读仅由服务器复核版本与摘要，不会保存或应用。';}else if(id)memoryNotice.value='本地记忆无效，请重新填写已知查询计划ID。';}catch{memoryNotice.value='浏览器记忆不可用，可正常手动填写并重读查询计划。';}}
function rememberQuery(id:string){const key=memoryKey();if(!key)return;try{localStorage.setItem(key,id);}catch{memoryNotice.value='浏览器记忆不可用，本次读取或保存已完成；下次可手动填写计划ID。';}}
watch(()=>[props.siteId,props.menuId,props.sourcePath,props.detailPath,props.preferredPlanId,siteContext.currentAdmin?.id],()=>{reset();restoreQueryId();},{immediate:true});
watch([selector,queryMode],()=>{candidates.value=null;rows.value={};snapshot.value=null;compiled.value=null;noQueryControls.value=false;if(plainPagination.value)includePresentation.value=true;});
watch(sourcePlanId,()=>{snapshot.value=null;compiled.value=null;});
watch(rows,()=>{snapshot.value=null;compiled.value=null;},{deep:true});
function message(cause:unknown){return cause instanceof Error?cause.message:String(cause);}
async function run(action:(version:number)=>Promise<void>){if(blocked.value)return;const version=generation;loading.value=true;error.value='';try{await action(version);}catch(cause){if(version===generation)error.value=message(cause);}finally{if(version===generation)loading.value=false;}}
async function load(){await run(async version=>{
 snapshot.value=null;compiled.value=null;
 const [data,model]=await Promise.all([queryCandidates(props.siteId,props.menuId,props.sourcePath,plainPagination.value?'body':selector.value),queryFields(props.siteId,props.menuId)]);
 if(version!==generation)return;
 candidates.value=data;fields.value=model;rows.value=Object.fromEntries(data.controls.map(control=>[control.id,{enabled:false,role:control.kind==='SUBMIT'?'SUBMIT':'FILTER',fieldName:'',operator:'contains',values:{}}]));
 if(props.detailPath){const saved=await recentFieldPlans(props.siteId,props.detailPath,props.menuId);if(version===generation){savedPlans.value=saved.plans.filter(plan=>plan.status==='CONFIRMED');if(saved.truncated||saved.warnings.length)error.value='既有内容计划范围不完整，可输入明确计划ID，不据此认定没有计划';}}
 });}
function changeField(id:string){const row=rows.value[id];if(!row)return;const field=filters.value.find(field=>field.fieldName===row.fieldName);row.operator=field?.fieldType==='text'?'contains':'eq';row.values={};}
function operators(id:string){const row=rows.value[id];const field=filters.value.find(field=>field.fieldName===row?.fieldName);if(!field)return[];if(!field.fieldName.startsWith('ext_'))return['contains'];return field.fieldType==='number'?['eq','ge','le','gt','lt']:field.fieldType==='datetime'?['eq','ge','le']:field.fieldType==='enum'?['eq']:['contains','eq'];}
function targetOptions(id:string){const row=rows.value[id];return filters.value.find(field=>field.fieldName===row?.fieldName)?.options||[];}
const canSave=computed(()=>!!candidates.value && !!sourcePlanId.value.trim() && !blocked.value && chosen.value.length<=20
 && (!includePresentation.value || !!presentation.value)
 && (!plainPagination.value || includePresentation.value && !!presentation.value?.pagination && candidates.value.containerSelector==='body')
 && (!presentation.value?.unpaged || noQueryControls.value && candidates.value.containerSelector==='body')
 && (pageOnly.value ? !!includePresentation.value && !!presentation.value : !!chosen.value.length));
async function confirm(){const data=candidates.value;if(!data||!canSave.value)return;
 if(includePresentation.value&&!presentation.value)return;
 const selected=includePresentation.value?presentation.value:null;
 const impact=includePresentation.value?`动态替换整组卡片，确认${presentation.value!.bindings.length}处字段或详情链接、${Object.keys(presentation.value!.sharedStatic).length}处共用内容、${presentation.value!.actions?.length || 0}个表单预填链接。${presentation.value!.unpaged?'固定路由，无分页。':`每页${pageSize.value}条。`}`:'仅保存查询字段接线。';
 const deferred=selected?.deferredRegion;
 const regionImpact=deferred?`公开列表将暂缓发布区域「${deferred.selector}」，原因「${deferred.reason}」。恢复需要重新审核。`:'';
 const carouselImpact=(selected?.carouselCounter?'轮播序号和总数将随本页卡片更新。':'')
  +(selected?.featuredCarousel?`将用已发布的置顶记录生成「${selected.featuredCarousel.selector}」轮播，使用记录自己的详情、封面与标题。`:'');
 const queryImpact=plainPagination.value?['按无筛选普通分页确认，保留卡片和分页。',...plainWarnings.value].join(' '):noQueryControls.value?'所选查询区的原控件将隐藏，本次只接通动态卡片与分页。':'';
 try{await ElMessageBox.confirm(impact+queryImpact+regionImpact+carouselImpact,'确认列表与查询方案',{type:'warning',confirmButtonText:'确认保存',cancelButtonText:'取消'});}catch{return;}
 if(data!==candidates.value||!canSave.value||selected!==(includePresentation.value?presentation.value:null))return;
 await run(async version=>{
 const mappings:QueryMapping[]=pageOnly.value?[]:chosen.value.map(control=>{const row=rows.value[control.id]!;return{controlId:control.id,role:row.role,fieldName:row.role==='FILTER'?row.fieldName:null,operator:row.role==='FILTER'?row.operator:null,values:row.role==='SUBMIT'?{}:row.values};});
 const saved=await confirmQueryBindings(props.siteId,{sourcePlanId:sourcePlanId.value.trim(),sourcePath:data.sourcePath,digest:data.digest,menuId:props.menuId,containerSelector:data.containerSelector,fieldConfigDigest:data.fieldConfigDigest,routeDigest:data.routeDigest,mappings,confirmed:true,presentation:selected,pageSize:pageSize.value});
 if(version===generation){snapshot.value=saved;knownId.value=saved.id;rememberQuery(saved.id);}
 });}
async function read(){await run(async version=>{snapshot.value=null;compiled.value=null;const saved=await readQueryBindings(props.siteId,knownId.value.trim());if(saved.siteId!==props.siteId||saved.menuId!==props.menuId||saved.sourcePath!==props.sourcePath)throw new Error('查询计划不属于当前来源/栏目');if(version===generation){snapshot.value=saved;compiled.value=null;rememberQuery(saved.id);}});}
function editFeatured() {
 if(blocked.value||!savedPresentation.value)return;
 featuredDraft.value={selector:'',imageField:'',titleField:'',...savedPresentation.value.featuredCarousel};
 error.value='';
 featuredDialog.value=true;
}
async function saveFeatured() {
 const saved=snapshot.value;
 const request=savedPresentation.value;
 const draft=featuredDraft.value;
 if(!saved||!request||!draft||!canSaveFeatured.value)return;
 const {sourcePlanId,sourcePath,digest,menuId,containerSelector,fieldConfigDigest,routeDigest,mappings,pageSize}=saved;
 const {selector,imageField,titleField,summaryField}=draft;
 await run(async version=>{
  const next=await confirmQueryBindings(props.siteId,{
   sourcePlanId,sourcePath,digest,menuId,containerSelector,fieldConfigDigest,routeDigest,mappings,pageSize,
   confirmed:true,presentation:{...request,featuredCarousel:{selector:selector.trim(),imageField,titleField,...(summaryField?{summaryField}:{})}},
  });
  if(version!==generation||snapshot.value!==saved)return;
  snapshot.value=next;compiled.value=null;knownId.value=next.id;rememberQuery(next.id);featuredDialog.value=false;
 });
}
watch(savedPresentation, value=>{if(!value)featuredDialog.value=false;});
async function inspectCompiled(){const saved=snapshot.value;if(!saved)return;await run(async version=>{const result=await compileQueryBindings(props.siteId,saved.id);if(version===generation)compiled.value=result;});}
</script>
<template>
 <el-card shadow="never" class="query-panel">
  <template #header><strong>筛选、排序与分页接线</strong></template>
  <el-radio-group v-model="queryMode" aria-label="列表查询方式" :disabled="blocked">
   <el-radio-button value="controls">审核筛选或排序控件</el-radio-button>
   <el-radio-button value="plain">普通分页（无筛选）</el-radio-button>
  </el-radio-group>
  <p v-if="plainPagination">用于原页没有独立筛选或排序区的列表。请审核卡片、详情链接和分页。</p>
  <p v-else>先确认业务控件容器，再明确映射到当前栏目的正式字段。普通导航、轮播、空链接及未知JS不自动当筛选；名称、牌号、CAS共用一个输入的OR语义仍待明确。</p>
  <div class="controls">
   <el-input v-if="!plainPagination" v-model="selector" aria-label="查询控件容器位置" placeholder="填写已确认的简单ID/class容器位置" :disabled="blocked"/>
   <el-button :disabled="blocked||(!plainPagination&&!selector)" :loading="loading" @click="load">{{plainPagination?'核对普通分页条件':'检查控件与有效字段'}}</el-button>
  </div>
  <el-alert v-if="error" type="error" :title="error" :closable="false"/>
  <template v-if="candidates">
   <p v-if="plainPagination">来源 {{candidates.sourcePath}} · 已读取原页与栏目字段，继续核对详情计划、卡片和分页。</p>
   <p v-else>来源 {{candidates.sourcePath}} · {{candidates.controls.length}}个候选<span v-if="candidates.truncated">，检查范围不足，尚有未覆盖内容</span>。未确认映射不会写入业务数据。</p>
   <el-alert v-for="warning in plainWarnings" :key="warning" type="warning" :title="warning" :closable="false" show-icon />
   <el-collapse><el-collapse-item title="识别范围与待处理说明" name="coverage"><p v-for="warning in candidates.warnings" :key="warning">{{warning}}</p><p>字段/路由摘要会在确认和重读时复核。</p></el-collapse-item></el-collapse>
   <div class="controls"><el-select v-if="savedPlans.length" v-model="sourcePlanId" placeholder="选择同栏目已确认的详情计划" :disabled="blocked"><el-option v-for="plan in savedPlans" :key="plan.id" :value="plan.id" :label="plan.id"/></el-select><el-input v-model="sourcePlanId" aria-label="查询关联内容计划ID" placeholder="填写同栏目的已确认详情计划 ID" :disabled="blocked"/></div>
   <el-checkbox v-if="includePresentation && !plainPagination" v-model="noQueryControls" :disabled="blocked">本次只接通卡片与分页，隐藏所选查询区的原控件</el-checkbox>
   <el-table v-if="!pageOnly" :data="candidates.controls" row-key="id">
    <el-table-column label="明确选择" width="110"><template #default="{row}"><el-checkbox v-model="rows[row.id]!.enabled" :disabled="blocked||row.status!=='CANDIDATE'" :aria-label="`选择查询控件 ${row.selector}`"/></template></el-table-column>
    <el-table-column label="原控件证据" min-width="210"><template #default="{row}"><strong>{{row.label||row.name||row.kind}}</strong><p>{{row.selector}} · {{row.kind}} · {{row.name||'无参数名'}}</p><p v-if="row.status==='PENDING'">待处理：{{row.reason}}</p></template></el-table-column>
    <el-table-column label="明确角色与字段" min-width="250"><template #default="{row}"><template v-if="rows[row.id]!.enabled"><el-select v-model="rows[row.id]!.role" :disabled="blocked"><el-option v-if="row.kind!=='SUBMIT'" label="筛选字段" value="FILTER"/><el-option v-if="row.kind==='SELECT'" label="固定排序选项" value="SORT"/><el-option v-if="row.kind==='SUBMIT'" label="GET提交" value="SUBMIT"/></el-select><template v-if="rows[row.id]!.role==='FILTER'"><el-select v-model="rows[row.id]!.fieldName" placeholder="选择明确业务字段" :disabled="blocked" :aria-label="`控件 ${row.selector} 映射字段`" @change="changeField(row.id)"><el-option v-for="field in filters" :key="field.fieldName" :label="`${field.title} · ${field.fieldType} · ${field.fieldName}`" :value="field.fieldName"/></el-select><el-select v-model="rows[row.id]!.operator" :disabled="blocked"><el-option v-for="op in operators(row.id)" :key="op" :label="({eq:'等于',contains:'包含',ge:'大于等于',le:'小于等于',gt:'大于',lt:'小于'} as Record<string,string>)[op]" :value="op"/></el-select></template></template></template></el-table-column>
    <el-table-column label="原选项逐一映射" min-width="260"><template #default="{row}"><template v-if="rows[row.id]!.enabled&&row.kind==='SELECT'"><div v-for="option in row.options.filter((value:any)=>value.value!=='')" :key="option.value"><span>{{option.label}}（{{option.value}}）→</span><el-select v-if="rows[row.id]!.role==='SORT'" v-model="rows[row.id]!.values[option.value]" placeholder="明确字段与方向" :disabled="blocked"><template v-for="field in sorting" :key="field.fieldName"><el-option :value="field.fieldName+'|asc'" :label="`${field.title} · 升序`"/><el-option :value="field.fieldName+'|desc'" :label="`${field.title} · 降序`"/></template></el-select><el-select v-else-if="targetOptions(row.id).length" v-model="rows[row.id]!.values[option.value]" :disabled="blocked" placeholder="明确字段真实值"><el-option v-for="value in targetOptions(row.id)" :key="value" :value="value" :label="value"/></el-select><el-input v-else v-model="rows[row.id]!.values[option.value]" :disabled="blocked" placeholder="明确单字段字面值"/></div></template></template></el-table-column>
   </el-table>
   <el-checkbox v-if="!plainPagination" v-model="includePresentation" :disabled="blocked">同时确认完整动态卡片与分页（取消仅保存字段接线，不能完整应用列表）</el-checkbox>
   <template v-if="includePresentation">
    <QueryPresentationPanel v-if="pairing&&cardId" :site-id="siteId" :source-plan-id="sourcePlanId" :pairing="pairing" :card-id="cardId" :navigation-plan-id="navigationPlanId" :disabled="disabled||loading" @change="presentation=$event" @busy="presentationBusy=$event"/>
    <p v-else>请先在上方确认列表容器并选择原型卡片。</p>
    <div v-if="!presentation?.unpaged" class="controls"><span>每页条数</span><el-input-number v-model="pageSize" :min="1" :max="100" :disabled="blocked" aria-label="列表每页条数"/></div>
   </template>
   <el-button :disabled="!canSave" @click="confirm">确认并保存列表与查询方案</el-button>
  </template>
  <p v-if="memoryNotice" role="status">{{memoryNotice}}</p>
  <div class="controls"><el-input v-model="knownId" aria-label="已知查询计划ID" placeholder="已保存查询计划ID" :disabled="blocked"/><el-button :disabled="blocked||!knownId" @click="read">重读查询计划</el-button></div>
  <template v-if="snapshot"><p>查询计划 {{snapshot.id}} · 关联内容计划 {{snapshot.sourcePlanId}} · 栏目{{snapshot.menuId}}。{{snapshot.presentation?.request.pagination?'已确认完整卡片与分页':fixedListSaved?'已确认固定路由列表':'仅字段接线，不是完整列表'}}。尚未自动发布。</p><p>固定筛选字段{{snapshot.allowedFilterKeys.join('、')||'无'}}；固定排序字段{{snapshot.allowedSortKeys.join('、')||'无'}}；新条件提交首页{{snapshot.firstUrl}}。</p><el-button :disabled="blocked" @click="inspectCompiled">检查受控GET编译结果</el-button></template>
  <template v-if="snapshot?.presentation?.categorySidebar">
   <p>已审核分类侧栏 <code>{{snapshot.presentation.categorySidebar.selector}}</code> · 导航修订 {{snapshot.presentation.categorySidebar.navigationRevision}}</p>
   <ul><li v-for="entry in snapshot.presentation.categorySidebar.entries" :key="entry.menuId">
    {{entry.name}} · #{{entry.menuId}}<span v-if="entry.menuId===snapshot.presentation.categorySidebar.currentMenuId"> · 当前栏目</span>
   </li></ul>
  </template>
  <template v-if="snapshot?.presentation?.deferredRegion">
   <p>已审核暂不发布区域 <code>{{snapshot.presentation.deferredRegion.selector}}</code> · {{snapshot.presentation.deferredRegion.reason}}。恢复需要重新审核并应用新的列表方案。</p>
   <details><summary>查看原页和区域摘要</summary>
    <p>原页 <code>{{snapshot.presentation.deferredRegion.sourceDigest}}</code></p>
    <p>区域 <code>{{snapshot.presentation.deferredRegion.regionDigest}}</code></p>
   </details>
  </template>
  <p v-if="snapshot?.presentation?.carouselCounter">已审核轮播数字。原页示例 {{snapshot.presentation.carouselCounter.currentSample}} / {{snapshot.presentation.carouselCounter.totalSample}}，按本页实际卡片更新。</p>
  <template v-if="snapshot?.presentation?.request && 'featuredCarousel' in snapshot.presentation.request && snapshot.presentation.request.featuredCarousel">
   <p>已审核置顶轮播 <code>{{snapshot.presentation.request.featuredCarousel.selector}}</code> · 封面 {{snapshot.presentation.request.featuredCarousel.imageField}} · 标题 {{snapshot.presentation.request.featuredCarousel.titleField}}<span v-if="snapshot.presentation.request.featuredCarousel.summaryField"> · 摘要 {{snapshot.presentation.request.featuredCarousel.summaryField}}</span></p>
  </template>
  <el-button v-if="savedPresentation" :disabled="blocked" @click="editFeatured">沿用计划审核置顶轮播</el-button>
  <el-dialog v-if="featuredDraft" v-model="featuredDialog" title="审核置顶轮播" width="min(570px, 95vw)"
    :close-on-click-modal="false" :close-on-press-escape="!blocked" :show-close="!blocked">
   <p>以当前查询计划为基础，沿用卡片、分页和筛选配置。保存后仍需核对编译结果并应用发布。</p>
   <el-form label-position="top" :disabled="blocked" @submit.prevent>
    <el-form-item label="原页轮播容器"><el-input v-model="featuredDraft.selector" maxlength="200" aria-label="原页轮播容器" /></el-form-item>
    <el-form-item label="轮播封面字段"><el-select v-model="featuredDraft.imageField" aria-label="轮播封面字段">
     <el-option v-for="field in snapshot?.presentation?.fields.filter(item=>item.fieldType==='Image')" :key="field.fieldName" :value="field.fieldName" :label="field.name||field.fieldName" />
    </el-select></el-form-item>
    <el-form-item label="轮播标题字段"><el-select v-model="featuredDraft.titleField" aria-label="轮播标题字段">
     <el-option v-for="field in snapshot?.presentation?.fields.filter(item=>item.fieldType==='SingleLine')" :key="field.fieldName" :value="field.fieldName" :label="field.name||field.fieldName" />
    </el-select></el-form-item>
    <el-form-item label="轮播摘要字段（可选）"><el-select v-model="featuredDraft.summaryField" aria-label="轮播摘要字段" clearable>
     <el-option v-for="field in snapshot?.presentation?.fields.filter(item=>['SingleLine','MultiLine'].includes(item.fieldType))" :key="field.fieldName" :value="field.fieldName" :label="field.name||field.fieldName" />
    </el-select></el-form-item>
   </el-form>
   <el-alert v-if="error" :title="error" type="error" :closable="false" />
   <template #footer><el-button :disabled="blocked" @click="featuredDialog=false">取消</el-button>
    <el-button type="primary" :loading="loading" :disabled="blocked||!canSaveFeatured" @click="saveFeatured">保存轮播方案</el-button></template>
  </el-dialog>
  <template v-if="compiled"><p>{{compiled.completeList?'已生成受控GET、动态卡片与分页':'仅生成受控GET字段接线'}}；此处不执行原脚本，不把静态列表当查询结果。实际列表与分页需经公开模板应用和真实路由验收。</p><p v-for="warning in compiled.warnings" :key="warning">{{warning}}</p><el-input :model-value="compiled.html" type="textarea" :rows="10" readonly aria-label="受控查询模板检查结果"/></template>
  <PublicListApplicationPanel v-if="snapshot" :site-id="siteId" :query-id="snapshot.id" :package-version="snapshot.packageVersion" :menu-id="snapshot.menuId" :complete="!!compiled?.completeList" :disabled="blocked" :has-form-actions="!!snapshot.presentation?.request.actions?.length" :fixed-list="!!fixedListSaved" @busy="publicBusy=$event" @changed="emit('public-list-changed')"/>
 </el-card>
</template>
<style scoped>
.query-panel{margin-top:16px}.controls{display:flex;flex-wrap:wrap;align-items:center;gap:12px;margin:12px 0}.controls .el-input,.controls .el-select{width:min(460px,100%)}p{overflow-wrap:anywhere;white-space:pre-wrap}.el-select{margin:4px 0}.query-panel :deep(.cell){overflow-wrap:anywhere}
</style>
