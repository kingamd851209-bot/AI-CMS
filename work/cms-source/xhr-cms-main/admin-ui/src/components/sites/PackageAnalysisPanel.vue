<template>
  <el-card shadow="never" aria-label="整包分析与审核">
    <template #header><h2 ref="heading" tabindex="-1">整包分析与审核</h2></template>
    <p>从当前页面包读取原页与栏目证据，逐页生成待审核字段。确认后可继续字段映射，栏目、字段和内容由后续审核决定。</p>
    <div class="package-actions">
      <el-button type="primary" :loading="writing" :disabled="actionDisabled" @click="primary">{{ actionLabel }}</el-button>
      <el-button v-if="snapshot" :disabled="writing" @click="read()">刷新进度</el-button>
      <el-button v-if="ready && snapshot?.state!=='COMPLETED'" :disabled="writing" @click="focusReview">审核已有草稿</el-button>
      <el-button link @click="$emit('structure',snapshot?.structurePlanId || null)">核对整站结构</el-button>
    </div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <p v-else-if="!snapshot" role="status">正在读取整包分析状态…</p>
    <template v-if="snapshot">
      <div class="package-progress" role="status" aria-live="polite">
        <strong>{{ phaseLabel }}<template v-if="snapshot.structureStatus !== 'RUNNING'"> · {{ settled }} / {{ snapshot.pages.length }} 页已处理</template></strong>
        <span>{{ ready }} 页有建议 · {{ confirmed }} 页已人工确认 · {{ failed }} 页待重试</span>
        <el-progress v-if="snapshot.pages.length" :percentage="Math.round(100*settled/snapshot.pages.length)" :show-text="false" />
        <span v-if="snapshot.structureMessage">{{ snapshot.structureMessage }}</span>
      </div>
      <div v-if="snapshot.pages.length" ref="reviewArea" class="package-review" tabindex="-1" aria-label="逐页审核">
        <div>
          <h3>逐页审核</h3>
          <label for="package-page-group">按栏目归属筛选</label>
          <select id="package-page-group" v-model="group">
            <option v-for="option in groups" :key="option.id" :value="option.id">{{ option.label }}（{{ option.count }}）</option>
          </select>
          <el-input v-model="search" aria-label="搜索原页或建议栏目" placeholder="搜索原页或建议栏目" clearable />
          <ul v-if="visiblePages.length" class="package-page-list">
            <li v-for="page in visiblePages" :key="page.sourcePath">
              <button type="button" :aria-current="selectedPath===page.sourcePath ? 'true' : undefined" @click="selectPage(page)">
                <strong>{{ names(page).join('、') || '归属待核对' }}</strong>
                <span>{{ page.sourcePath }}</span>
                <small>{{ pageLabels[page.state] }} · {{ reviewLabels[page.review] }}</small>
              </button>
            </li>
          </ul>
          <p v-else>当前筛选下没有原页。</p>
          <el-pagination v-if="filteredPages.length>pageSize" v-model:current-page="pageNumber" small
            layout="prev, pager, next" :total="filteredPages.length" :page-size="pageSize" :pager-count="5" />
        </div>
        <section v-if="selected" ref="detailArea" class="package-detail" aria-label="所选原页审核" tabindex="-1">
          <header>
            <h3>{{ names(selected).join('、') || '归属待核对' }}</h3>
            <code>{{ selected.sourcePath }}</code>
            <p>{{ selected.reason }}</p>
            <p v-if="selected.structureCandidateNames?.length">当前结构候选 · {{ selected.structureCandidateNames.join('、') }}</p>
            <p v-if="selected.recommendedMenuId">建议维护栏目 · {{ menuLabel(selected.recommendedMenuId) }}</p>
            <p v-if="selected.review!=='PENDING'">{{ reviewLabels[selected.review] }}<template v-if="selected.reviewMenuId"> · 人工栏目 {{ menuLabel(selected.reviewMenuId) }}</template></p>
          </header>
          <details @toggle="showOriginal=($event.currentTarget as HTMLDetailsElement).open" :key="selected.sourcePath">
            <summary>查看原页预览</summary>
            <EntryDecisionSourcePreview v-if="showOriginal" :site-id="siteId" :package-version="packageVersion" :source-path="selected.sourcePath" :visible="active" />
          </details>
          <p v-if="detailLoading" role="status">正在读取字段和原页证据…</p>
          <div v-else-if="detailError">
            <p role="alert">{{ detailError }}</p>
            <el-button size="small" @click="readDetail">重读字段与证据</el-button>
          </div>
          <template v-else-if="detail">
            <p v-if="!detail.results.length">{{ pageLabels[selected.state] }}。当前没有可审核的字段建议。</p>
            <p v-if="usingSavedReview && savedReview" role="status">确认下方维护栏目后，将使用你已保存的修订 {{ savedReview.revision }}，共 {{ savedReview.unitCount }} 项。</p>
            <p v-if="outdatedFields && !usingSavedReview" role="status">整包建议早于当前原页修订。请打开当前单页审核，核对已保存的修正并继续映射。</p>
            <details v-for="(result,index) in (usingSavedReview || outdatedFields ? [] : detail.results)" :key="index" :open="index===0" class="package-fields">
              <summary>{{ detail.results.length===1 ? '字段建议' : `第 ${index+1} 段字段建议` }} · {{ result.units.length }} 项</summary>
              <p>{{ result.summary }}</p>
              <p v-if="workspace && !matchesSource(result)" role="status">当前原页证据已更新，请在单页审核重新核对。</p>
              <ol>
                <li v-for="unit in result.units" :key="unit.id">
                  <div class="package-field-heading">
                    <strong>{{ fieldValue(index,unit).name }}</strong>
                    <span>{{ fieldTypes[fieldValue(index,unit).fieldType] || fieldValue(index,unit).fieldType }}<template v-if="fieldValue(index,unit).ignored"> · 暂不绑定</template></span>
                    <span v-if="fieldEdits.has(fieldKey(index,unit.id))" class="pending-field">未保存</span>
                    <el-button v-if="selected.state==='READY'" link type="primary" :disabled="writing || disabled" :aria-expanded="editingField===fieldKey(index,unit.id)"
                      @click="editingField=editingField===fieldKey(index,unit.id) ? '' : fieldKey(index,unit.id)">{{ editingField===fieldKey(index,unit.id) ? '收起字段编辑' : '编辑字段' }}</el-button>
                  </div>
                  <div v-if="editingField===fieldKey(index,unit.id)" class="package-field-form">
                    <label>业务名称<el-input :model-value="fieldValue(index,unit).name" maxlength="100" aria-label="字段业务名称" :disabled="writing || disabled"
                      @update:model-value="patchField(index,unit,{name:$event})" /></label>
                    <label>字段类型<el-select :model-value="fieldValue(index,unit).fieldType" aria-label="审核字段类型" :disabled="writing || disabled"
                      @update:model-value="patchField(index,unit,{fieldType:$event})">
                      <el-option v-for="(label,type) in fieldTypes" :key="type" :label="label" :value="type" />
                    </el-select></label>
                    <el-checkbox :model-value="fieldValue(index,unit).ignored" :disabled="writing || disabled" @update:model-value="patchField(index,unit,{ignored:$event===true})">暂不绑定此字段</el-checkbox>
                    <label class="package-field-note">字段审核说明<el-input type="textarea" :rows="2" :model-value="fieldValue(index,unit).doubts" maxlength="2000" aria-label="字段审核说明"
                      :disabled="writing || disabled" @update:model-value="patchField(index,unit,{doubts:$event})" /></label>
                  </div>
                  <p v-if="!fieldEdits.has(fieldKey(index,unit.id)) && mapping(result,unit.id)?.fieldName">建议映射到 {{ mapping(result,unit.id)?.fieldName }}</p>
                  <p v-if="fieldValue(index,unit).doubts && editingField!==fieldKey(index,unit.id)">{{ fieldValue(index,unit).doubts }}</p>
                  <ul v-if="matchesSource(result)">
                    <li v-for="reference in unit.sources" :key="`${reference.sourceId}:${reference.start}:${reference.end}`">
                      <p>{{ excerpt(reference) }}</p><code>{{ sources.get(reference.sourceId)?.selector || reference.sourceId }}</code>
                    </li>
                  </ul>
                </li>
              </ol>
            </details>
            <p v-if="detail.results.length>1">已分段读取建议，跨段的重复项与字段归属需人工归并。</p>
          </template>
          <template v-if="selected.state==='READY' && detail?.results.length">
            <div v-if="fieldError" role="alert">{{ fieldError }}</div>
            <el-button v-if="fieldError && fieldEdits.size" size="small" @click="readDetail()">重读已保存字段并保留当前修正</el-button>
            <div v-if="fieldEdits.size" class="package-actions">
              <el-button type="primary" plain :disabled="writing || disabled" @click="saveFields">保存本页字段修改（{{ fieldEdits.size }} 项）</el-button>
            </div>
            <label>人工确认栏目
              <el-select ref="menuChoice" v-model="reviewMenu" aria-label="人工确认栏目" filterable clearable placeholder="请选择维护本页内容的栏目" :disabled="writing">
                <el-option-group v-if="sourceMenus.length" label="使用此原页的已建栏目">
                  <el-option v-for="menu in sourceMenus" :key="Number(menu.id)" :value="Number(menu.id)" :label="menuLabel(Number(menu.id))" />
                </el-option-group>
                <el-option-group v-if="sourceMenus.length && otherMenus.length" label="其他栏目">
                  <el-option v-for="menu in otherMenus" :key="Number(menu.id)" :value="Number(menu.id)" :label="menuLabel(Number(menu.id))" />
                </el-option-group>
                <template v-if="!sourceMenus.length"><el-option v-for="menu in columns" :key="Number(menu.id)" :value="Number(menu.id)" :label="menuLabel(Number(menu.id))" /></template>
              </el-select>
            </label>
            <label>审核说明
              <el-input v-model="note" type="textarea" maxlength="1000" :rows="2" aria-label="整包页面审核说明" :disabled="writing" />
            </label>
            <div class="package-actions">
              <el-button v-if="usingSavedReview" type="primary" :disabled="disabled || writing || !reviewMenu" @click="save('CONFIRMED',false,true)">确认维护栏目并继续</el-button>
              <el-button v-else type="primary" :disabled="disabled || writing" @click="save('CONFIRMED')">确认本页审核</el-button>
              <el-button v-if="nextPending && !usingSavedReview" :disabled="disabled || writing" @click="save('CONFIRMED',true)">确认并继续下一页</el-button>
              <el-button v-if="!usingSavedReview" :disabled="disabled || writing" @click="save('NEEDS_REVIEW')">保存为待核对</el-button>
            </div>
          </template>
          <div class="package-actions">
            <el-button :disabled="disabled || writing" @click="$emit('correct', selected.sourcePath, targetMenu(selected))">{{ outdatedFields ? '打开当前单页审核' : '到单页修正' }}</el-button>
            <el-button v-if="canAdopt && !usingSavedReview" type="primary" plain :disabled="disabled || writing || fieldEdits.size>0" @click="adoptFields">用本页审核字段继续映射</el-button>
          </div>
          <label v-if="canAdopt && !usingSavedReview && detail && detail.results.length>1">选择接续映射的分段（可选）
            <el-select v-model="adoptSegments" multiple collapse-tags clearable :disabled="writing || disabled" aria-label="接续映射的分段" placeholder="留空时使用整页审核字段">
              <el-option v-for="(result,index) in detail.results" :key="index" :value="index" :label="`第 ${index+1} 段（${result.units.length} 项）`" />
            </el-select>
          </label>
          <p v-if="notice" role="status">{{ notice }}</p>
        </section>
      </div>
    </template>
  </el-card>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue';
import { ElMessageBox } from 'element-plus';
import { http } from '@/api/http';
import { packageAnalysisApi, type PackageAnalysis, type PackageAnalysisDetail, type PackageAnalysisPage, type PackageFieldEdit, type PackageReview } from '@/api/packageAnalysis';
import type { DomainAgentResult, DomainUnit } from '@/api/domainAgent';
import type { MenuNode } from '@/api/uiPackage';
import type { SiteModelPlan, SiteNavigation } from '@/api/siteModel';
import EntryDecisionSourcePreview from './EntryDecisionSourcePreview.vue';

type Workspace = {sourcePath:string;digest:string;revision:number;sources:{id:string;rawValue:string;selector:string}[]};
const props=defineProps<{siteId:number;packageVersion:string;columns:MenuNode[];plan:SiteModelPlan|null;appliedNavigation?:SiteNavigation|null;active:boolean;openReview:boolean;savedReview?:{sourcePath:string;revision:number;digest:string;unitCount:number};disabled:boolean}>();
const emit=defineEmits<{ continueReview:[path:string,menuId:number]; structure: [planId:string|null]; correct:[path:string,menuId:number|null]; adopt:[result:DomainAgentResult] }>();
const runLabels={IDLE:'尚未分析',RUNNING:'正在分析',PAUSED:'已暂停',COMPLETED:'分析完成',ATTENTION:'部分页面需处理'};
const pageLabels={PENDING:'排队中',RUNNING:'分析中',READY:'可审核',FAILED:'分析失败',SKIPPED:'仅登记职责'};
const reviewLabels={PENDING:'未审核',CONFIRMED:'已确认审核',NEEDS_REVIEW:'待人工核对'};
const fieldTypes:Record<string,string>={SingleLine:'单行文本',MultiLine:'多行文本',Editor:'富文本',Image:'图片',Video:'视频',File:'文件',DateTime:'日期时间'};
const heading=ref<HTMLElement>(), reviewArea=ref<HTMLElement>(), detailArea=ref<HTMLElement>();
const snapshot=ref<PackageAnalysis>(), detail=ref<PackageAnalysisDetail>(), workspace=ref<Workspace>();
const error=ref(''), detailError=ref(''), notice=ref('');
const writing=ref(false), detailLoading=ref(false), showOriginal=ref(false);
const selectedPath=ref(''), search=ref(''), group=ref('all'), pageNumber=ref(1);
const reviewMenu=ref<number>(), note=ref('');
const editingField=ref(''), fieldError=ref(''), adoptSegments=ref<number[]>([]);
const fieldEdits=ref(new Map<string,PackageFieldEdit>());
let savedMenu:number|null=null, savedNote='';
let mainRequest:AbortController|undefined, detailRequest:AbortController|undefined, fieldRequest:AbortController|undefined;
let timer:number|undefined;
const scope=computed(()=>`${props.siteId}:${props.packageVersion}`);
const positionKey=computed(()=>`cms-workbench-package-review:${scope.value}`);
const pages=computed(()=>snapshot.value?.pages || []);
const selected=computed(()=>pages.value.find(page=>page.sourcePath===selectedPath.value));
const structurePlanId=computed(()=>snapshot.value?.structurePlanId || null);
const structurePlanRevision=computed(()=>snapshot.value?.structurePlanRevision ?? null);
const settled=computed(()=>pages.value.filter(page=>!['PENDING','RUNNING'].includes(page.state)).length);
const ready=computed(()=>pages.value.filter(page=>page.state==='READY').length);
const failed=computed(()=>pages.value.filter(page=>page.state==='FAILED').length);
const confirmed=computed(()=>pages.value.filter(page=>page.review==='CONFIRMED').length);
const stageEvidence=computed(()=>{
  const value=snapshot.value;
  const position={siteId:props.siteId,packageVersion:props.packageVersion};
  if(error.value) return {...position,state:'error' as const};
  if(!value || value.packageVersion!==position.packageVersion) return {...position,state:'loading' as const};
  return {...position,state:'ready' as const,value};
});
const pageSize=15;

function originalNames(page:PackageAnalysisPage){return page.candidateNames?.length ? page.candidateNames : page.candidateName ? [page.candidateName] : [];}
function names(page:PackageAnalysisPage){return [...new Set([...(page.structureCandidateNames || []),...originalNames(page)])];}
function targetMenu(page:PackageAnalysisPage){return page.review==='PENDING' ? page.recommendedMenuId ?? page.menuId : page.reviewMenuId ?? null;}
function menuLabel(id:number){const item=props.columns.find(menu=>Number(menu.id)===id);return `${item?.className || item?.name || '栏目'}（#${id}）`;}
const menuChoice=ref<{focus:()=>void}>();
const menuFocusPath=ref('');
const sourceMenus=computed(()=>{
  const nav=props.appliedNavigation;
  if(nav?.status!=='APPLIED' || !selected.value)return [];
  const ids=new Set(nav.nodes.filter(node=>!node.drift && node.sourcePath===selected.value!.sourcePath).map(node=>node.menuId));
  return props.columns.filter(menu=>ids.has(Number(menu.id)));
});
const otherMenus=computed(()=>props.columns.filter(menu=>!sourceMenus.value.includes(menu)));
watch([menuChoice,menuFocusPath,()=>props.active],()=>{
  if(!props.active){menuFocusPath.value='';return;}
  if(menuFocusPath.value && menuFocusPath.value===selected.value?.sourcePath && menuChoice.value){
    menuChoice.value.focus();
    menuFocusPath.value='';
  }
},{flush:'post'});
function groupOf(page:PackageAnalysisPage){
  if(page.state==='SKIPPED')return 'support';
  if(page.review==='NEEDS_REVIEW' || page.state==='FAILED' || !names(page).length)return 'unresolved';
  if((page.structureCandidateNames?.length || originalNames(page).length)>1)return 'shared';
  return 'column';
}
const groups=computed(()=>[
  {id:'all',label:'全部原页',count:pages.value.length},
  ...[{id:'column',label:'建议栏目'},{id:'shared',label:'共用原页'},{id:'unresolved',label:'待核对'},{id:'support',label:'公共或工具页'}]
    .map(item=>({...item,count:pages.value.filter(page=>groupOf(page)===item.id).length})),
]);
const filteredPages=computed(()=>{
  const query=search.value.trim().toLowerCase();
  return pages.value.filter(page=>(group.value==='all' || groupOf(page)===group.value)
    && (!query || [page.sourcePath,...names(page)].join(' ').toLowerCase().includes(query)));
});
const visiblePages=computed(()=>filteredPages.value.slice((pageNumber.value-1)*pageSize,pageNumber.value*pageSize));
watch([group,search],()=>{pageNumber.value=1;});
const running=computed(()=>snapshot.value?.state==='RUNNING');
const phaseLabel=computed(()=>{
  const run=snapshot.value;
  if (!run) return '';
  if (!running.value) return runLabels[run.state];
  if (run.structureStatus==='RUNNING') return '正在分析整站结构，完成后逐页生成草稿';
  const current=run.pages.find(page=>page.state==='RUNNING');
  return current ? `正在分析原页 · ${current.sourcePath}` : '正在准备逐页分析';
});
const actionLabel=computed(()=>{
  if(error.value)return '重读整包分析状态';
  if(!snapshot.value)return '读取整包分析状态';
  if(snapshot.value.state==='ATTENTION' && failed.value && pages.value.every(p=>!['PENDING','RUNNING'].includes(p.state)))return `重试失败的 ${failed.value} 页`;
  return {IDLE:'开始整包分析',RUNNING:'查看整包分析进度',PAUSED:'继续整包分析',COMPLETED:'继续审核整包草稿',ATTENTION:'继续整包分析'}[snapshot.value.state];
});
const actionDisabled=computed(()=>props.disabled || writing.value || (!snapshot.value && !error.value));
const sources=computed(()=>new Map(workspace.value?.sources.map(source=>[source.id,source])));
function matchesSource(result:DomainAgentResult){return workspace.value?.digest===result.digest && workspace.value.revision===result.revision;}
const mapping=(result:DomainAgentResult,id:string)=>result.mappings.find(row=>row.unitId===id);
function excerpt(reference:{sourceId:string;start:number;end:number}){
  const value=sources.value.get(reference.sourceId)?.rawValue.slice(reference.start,reference.end) || '';
  return value.length>600 ? `${value.slice(0,600)}…` : value;
}
const usingSavedReview=computed(()=>!!props.savedReview && props.savedReview.sourcePath===selectedPath.value);
const outdatedFields=computed(()=>!!workspace.value && !!detail.value?.results.some(result=>!matchesSource(result)));
const canAdopt=computed(()=>!!workspace.value && !outdatedFields.value && !!selected.value && selected.value.review==='CONFIRMED'
  && !!targetMenu(selected.value) && !!detail.value?.results.some(result=>result.units.length));
const nextPending=computed(()=>{
  const after=pages.value.findIndex(page=>page.sourcePath===selectedPath.value)+1;
  const candidates=[...pages.value.slice(after),...pages.value.slice(0,after)];
  return candidates.find(page=>page.sourcePath!==selectedPath.value && page.state==='READY' && page.review==='PENDING');
});
const nextAction=computed(()=>{
  const current=selected.value || nextPending.value;
  if(!snapshot.value) return null;
  if(!current || (current.state!=='READY' && !nextPending.value))return {
    kind:'primary',label:'整包分析',button:actionLabel.value,text:'先从当前页面包取得可以核对的字段建议。',
  };
  const page=current.state==='READY' ? current : nextPending.value!;
  if(!targetMenu(page) && props.appliedNavigation?.status==='APPLIED')return {
    kind:'assign',label:'确认原页的维护栏目',button:'选择维护栏目',text:`导航已应用。请核对 ${page.sourcePath} 的内容由哪个栏目维护。`,
  };
  if(!targetMenu(page))return {
    kind:'structure',label:'先确认栏目结构',button:'去审核栏目结构',text:`${page.sourcePath} 暂无已关联的维护栏目，先核对栏目和原页的关系。`,
  };
  if(page.sourcePath===selectedPath.value && outdatedFields.value && !usingSavedReview.value && !hasUnsavedChanges())return {
    kind:'current-review',label:'继续当前原页审核',button:'打开当前单页审核',text:'已保存的原页修订发生变化，请从当前内容继续核对字段。',
  };
  if(canAdopt.value && !hasUnsavedChanges())return {
    kind:'adopt',label:'接续字段映射',button:'用审核字段继续',text:`把 ${page.sourcePath} 已保存的字段带到单页草稿，再确认映射。`,
  };
  return {
    kind:'review',label:'核对原页字段',button:'审核此原页字段',text:`核对 ${page.sourcePath} 的业务名称、字段类型和绑定范围。`,
  };
});

function fieldKey(resultIndex:number,unitId:string){return `${resultIndex}:${unitId}`;}
function fieldValue(resultIndex:number,unit:DomainUnit){return fieldEdits.value.get(fieldKey(resultIndex,unit.id)) || unit;}
function patchField(resultIndex:number,unit:DomainUnit,change:Partial<Pick<PackageFieldEdit,'name'|'fieldType'|'ignored'|'doubts'>>){
  const key=fieldKey(resultIndex,unit.id), previous=fieldValue(resultIndex,unit);
  const next={resultIndex,unitId:unit.id,name:previous.name,fieldType:previous.fieldType,ignored:previous.ignored,doubts:previous.doubts || '',...change};
  if(next.name===unit.name && next.fieldType===unit.fieldType && next.ignored===unit.ignored && next.doubts===(unit.doubts || ''))fieldEdits.value.delete(key);
  else fieldEdits.value.set(key,next);
}
function resetFieldForm(){editingField.value='';fieldEdits.value.clear();fieldError.value='';adoptSegments.value=[];}

function syncForm(){
  const page=selected.value;
  savedMenu=page ? targetMenu(page) : null;
  savedNote=page?.reviewNote || '';
  reviewMenu.value=savedMenu ?? undefined;
  note.value=savedNote;
}
function hasUnsavedChanges(){
  return !!selected.value && (fieldEdits.value.size>0 || (reviewMenu.value ?? null)!==savedMenu || note.value!==savedNote);
}
const problem=(cause:unknown, fallback:string)=>cause instanceof Error ? cause.message : fallback;
function schedule(delay=3500){
  window.clearTimeout(timer);
  if(snapshot.value?.state==='RUNNING')timer=window.setTimeout(()=>void read(),delay);
}
function adoptSnapshot(result:PackageAnalysis){
  if(result.packageVersion!==props.packageVersion)throw new Error('页面包已变化，请重新读取整包分析。');
  if(!snapshot.value || result.revision>=snapshot.value.revision){
    const dirty=hasUnsavedChanges();
    snapshot.value=result;
    if(!dirty)syncForm();
  }
}

let pendingRead:Promise<void>|undefined;
function read(preserveError=false){
  if(writing.value)return;
  if(mainRequest)return pendingRead;
  return pendingRead=readSnapshot(preserveError);
}
async function readSnapshot(preserveError:boolean){
  const {siteId,packageVersion}=props;
  const request=new AbortController(), key=scope.value;
  mainRequest=request;
  try {
    const result=await packageAnalysisApi.read(siteId,packageVersion,request.signal);
    if(mainRequest!==request || key!==scope.value)return;
    adoptSnapshot(result);
    if(!preserveError)error.value='';
  } catch(cause){
    if(mainRequest===request && key===scope.value)error.value=problem(cause,'无法读取整包分析。');
  } finally {
    if(mainRequest===request){mainRequest=undefined;pendingRead=undefined;schedule(error.value ? 8000 : 3500);}
  }
}

async function change(send:(revision:number,signal:AbortSignal)=>Promise<PackageAnalysis>){
  if(!snapshot.value || writing.value)return false;
  const revision=snapshot.value.revision, key=scope.value;
  const request=new AbortController();
  mainRequest?.abort();mainRequest=request;
  writing.value=true;
  error.value='';notice.value='';
  let accepted=false;
  try {
    const result=await send(revision,request.signal);
    if(mainRequest!==request || key!==scope.value)return false;
    adoptSnapshot(result);accepted=true;
  } catch(cause){
    if(mainRequest===request && key===scope.value)error.value=problem(cause,'保存或启动失败。');
  } finally {
    if(mainRequest===request){writing.value=false;mainRequest=undefined;schedule();if(!accepted)void read(true);}
  }
  return accepted;
}

async function focusReview(){
  if(!selectedPath.value){
    const next=pages.value.find(page=>page.state==='READY' && page.review!=='CONFIRMED') || pages.value.find(page=>page.state==='READY');
    if(next){group.value='all';search.value='';await nextTick();pageNumber.value=Math.floor(pages.value.indexOf(next)/pageSize)+1;await selectPage(next);}
  }
  reviewArea.value?.focus();
}
async function followNext(){
  const step=nextAction.value;
  if(!step || props.disabled || writing.value)return;
  if(step.kind==='structure'){emit('structure',snapshot.value?.structurePlanId || null);return;}
  if(step.kind==='assign'){
    const page=selected.value?.state==='READY' ? selected.value : nextPending.value;
    if(page){await revealPage(page);menuFocusPath.value=page.sourcePath;}
    return;
  }
  if(step.kind==='current-review' && selected.value){emit('correct',selected.value.sourcePath,targetMenu(selected.value));return;}
  if(step.kind==='adopt'){await adoptFields();return;}
  if(step.kind==='primary'){await primary();return;}
  if(selected.value?.state!=='READY' && nextPending.value)await revealPage(nextPending.value);
  else await focusReview();
  detailArea.value?.focus();
}

async function primary(){
  await nextTick();
  if(actionDisabled.value)return;
  if(!snapshot.value || error.value){heading.value?.focus();await read();return;}
  if(snapshot.value.state==='COMPLETED'){
    await focusReview();return;
  }
  heading.value?.focus();
  if(snapshot.value.state==='RUNNING')return;
  const {siteId,packageVersion}=props;
  const plan=snapshot.value.state==='IDLE' && props.plan?.packageVersion===packageVersion ? props.plan : null;
  await change((revision,signal)=>packageAnalysisApi.run(siteId,{packageVersion,revision,
    ...(plan ? {structurePlanId:plan.id,structurePlanRevision:plan.revision} : {})},signal));
}

async function selectPage(page:PackageAnalysisPage){
  if(writing.value || selectedPath.value===page.sourcePath)return;
  const key=scope.value;
  if(hasUnsavedChanges()){
    try{await ElMessageBox.confirm('当前原页有未保存的审核意见。','切换审核原页',{confirmButtonText:'放弃意见并切换',cancelButtonText:'继续编辑',type:'warning'});}catch{return;}
  }
  if(key!==scope.value || !pages.value.some(row=>row.sourcePath===page.sourcePath))return;
  selectedPath.value=page.sourcePath;showOriginal.value=false;notice.value='';resetFieldForm();syncForm();
  try{sessionStorage.setItem(positionKey.value,page.sourcePath);}catch{/* 浏览器限制缓存时仍可继续审核。 */}
}
async function revealPage(page:PackageAnalysisPage){
  const key=scope.value;
  group.value='all';search.value='';await nextTick();
  if(key!==scope.value)return;
  pageNumber.value=Math.floor(pages.value.findIndex(row=>row.sourcePath===page.sourcePath)/pageSize)+1;
  await selectPage(page);
  await nextTick();
  if(key===scope.value)detailArea.value?.focus();
}
async function openPageForReview(path:string){
  const key=scope.value;
  if(!snapshot.value)await read();
  if(key!==scope.value)return;
  const page=pages.value.find(row=>row.sourcePath===path);
  if(page)await revealPage(page);
  else {
    if(snapshot.value)notice.value=`当前整包清单未收录 ${path}，请核对上传的页面包和分析进度。`;
    heading.value?.focus();
  }
}
function confirmedMenuFor(path:string){
  return pages.value.find(page=>page.sourcePath===path && page.state==='READY' && page.review==='CONFIRMED')?.reviewMenuId;
}
function hasConfirmedTarget(path:string,menuId:number){
  return pages.value.some(page=>page.sourcePath===path && page.state==='READY' && page.review==='CONFIRMED' && page.reviewMenuId===menuId);
}
const structureLinkError=ref('');
async function useStructure(plan:SiteModelPlan){
  const {siteId,packageVersion}=props;
  structureLinkError.value='';
  if(plan.siteId!==siteId || plan.packageVersion!==packageVersion){
    structureLinkError.value='所选结构不属于当前站点和页面包，请重新读取整站结构。';return false;
  }
  if(props.disabled || writing.value){
    structureLinkError.value='请等待当前工作台操作完成，再接续整包审核。';return false;
  }
  if(!snapshot.value)await read();
  if(!snapshot.value || snapshot.value.state==='RUNNING' || writing.value){
    structureLinkError.value=error.value || '请等待整包分析结束，再接续当前结构。';return false;
  }
  if(snapshot.value.structurePlanId===plan.id && snapshot.value.structurePlanRevision===plan.revision)return true;
  const changed=await change((revision,signal)=>packageAnalysisApi.run(siteId,{
    packageVersion,revision,structurePlanId:plan.id,structurePlanRevision:plan.revision,attachModelDraft:true,
  },signal));
  const linked=changed && snapshot.value?.structurePlanId===plan.id && snapshot.value.structurePlanRevision===plan.revision;
  if(!linked)structureLinkError.value=error.value || '整包审核未关联到所选的结构修订，请重新读取状态。';
  return linked;
}

async function readDetail(){
  detailRequest?.abort();detailRequest=undefined;
  detail.value=undefined;workspace.value=undefined;detailError.value='';detailLoading.value=false;
  const {siteId,packageVersion}=props, sourcePath=selectedPath.value, key=scope.value;
  if(!sourcePath)return;
  const request=new AbortController();detailRequest=request;detailLoading.value=true;
  const current=()=>detailRequest===request && key===scope.value && selectedPath.value===sourcePath;
  try {
    const result=await packageAnalysisApi.page(siteId,packageVersion,sourcePath,request.signal);
    if(!current())return;
    if(result.page.sourcePath!==sourcePath || result.results.some(row=>row.sourcePath!==sourcePath || row.packageVersion!==packageVersion))throw new Error('收到其他原页的结果，请重新读取。');
    detail.value=result;
    fieldError.value='';
    if(result.results.length){
      const proof=await http.get<Workspace>(`/api/admin/v2/sites/${siteId}/template-seed/content-units`,{params:{sourcePath},signal:request.signal,timeout:30000});
      if(current() && proof.sourcePath===sourcePath)workspace.value=proof;
    }
  } catch(cause){if(current())detailError.value=problem(cause,'无法读取字段与原页证据。');}
  finally{if(current()){detailRequest=undefined;detailLoading.value=false;}}
}

async function pageWrite<T>(send:(fieldRevision:number,signal:AbortSignal)=>Promise<T>){
  if(!detail.value || props.disabled || writing.value)return;
  const revision=detail.value.fieldRevision ?? 0, key=scope.value, path=selectedPath.value;
  const request=new AbortController();fieldRequest=request;
  mainRequest?.abort();mainRequest=undefined;window.clearTimeout(timer);
  writing.value=true;fieldError.value='';notice.value='';
  const current=()=>fieldRequest===request && key===scope.value && path===selectedPath.value;
  try {
    const result=await send(revision,request.signal);
    if(current())return result;
  } catch(cause){if(current())fieldError.value=problem(cause,'无法保存或接续本页审核字段。');}
  finally{if(fieldRequest===request){fieldRequest=undefined;writing.value=false;schedule();}}
}

async function saveFields(){
  if(!fieldEdits.value.size)return true;
  const sourcePath=selectedPath.value, {siteId,packageVersion}=props;
  const edits=[...fieldEdits.value.values()].map(edit=>({...edit,name:edit.name.trim(),doubts:edit.doubts.trim()}));
  if(edits.length>200){fieldError.value='一次最多保存 200 条字段修正。请减少本次修正项后重试。';return false;}
  if(edits.some(edit=>!edit.name || !fieldTypes[edit.fieldType])){
    fieldError.value='请填写各字段的业务名称并选择支持的字段类型。';return false;
  }
  const saved=await pageWrite((fieldRevision,signal)=>packageAnalysisApi.fields(siteId,{packageVersion,sourcePath,fieldRevision,edits},signal));
  if(!saved || siteId!==props.siteId || packageVersion!==props.packageVersion || sourcePath!==selectedPath.value)return false;
  if(saved.page.sourcePath!==sourcePath || saved.results.some(row=>row.sourcePath!==sourcePath || row.packageVersion!==packageVersion)){
    fieldError.value='返回的字段范围已变化，请重新核对。';return false;
  }
  detail.value=saved;fieldEdits.value.clear();notice.value='本页字段修改已保存。';
  return true;
}

async function adoptFields(){
  if(!canAdopt.value || hasUnsavedChanges()){
    fieldError.value='请先保存当前审核和字段修正，再接续映射。';return;
  }
  const sourcePath=selectedPath.value, {siteId,packageVersion}=props;
  const resultIndexes=adoptSegments.value.length ? [...adoptSegments.value].sort((a,b)=>a-b) : undefined;
  const result=await pageWrite((fieldRevision,signal)=>packageAnalysisApi.adopt(siteId,{packageVersion,sourcePath,fieldRevision,resultIndexes},signal));
  if(result?.sourcePath===sourcePath && result.packageVersion===packageVersion && siteId===props.siteId && sourcePath===selectedPath.value)emit('adopt',result);
}

async function save(decision:Exclude<PackageReview,'PENDING'>, continueNext=false, continueSavedReview=false){
  const sourcePath=selectedPath.value, {siteId,packageVersion}=props;
  if(!sourcePath || props.disabled)return;
  if(fieldEdits.value.size){
    if(!await saveFields())return;
    await read();
    if(sourcePath!==selectedPath.value || siteId!==props.siteId || packageVersion!==props.packageVersion)return;
  }
  const reviewMenuId=reviewMenu.value ?? null;
  const saved=continueSavedReview ? props.savedReview : undefined;
  if(continueSavedReview && (!saved || saved.sourcePath!==sourcePath || !reviewMenuId))return;
  const accepted=await change((revision,signal)=>packageAnalysisApi.review(siteId,{packageVersion,revision,sourcePath,decision,reviewMenuId,note:note.value.trim(),...(saved ? {workspaceRevision:saved.revision,workspaceDigest:saved.digest} : {})},signal));
  if(accepted && selectedPath.value===sourcePath){
    syncForm();notice.value=decision==='CONFIRMED'?'本页审核已保存。':'已保存为待核对。';
    if(continueSavedReview && reviewMenuId && props.savedReview?.sourcePath===sourcePath && hasConfirmedTarget(sourcePath,reviewMenuId)){
      emit('continueReview',sourcePath,reviewMenuId);return;
    }
    if(continueNext && nextPending.value){await revealPage(nextPending.value);notice.value='上一页审核已保存。';}
  }
}

watch([selectedPath,()=>selected.value?.state],readDetail);
watch([()=>props.openReview,()=>props.active,ready],([open,active,count])=>{
  if(!active || !count || selectedPath.value)return;
  let previous:string|null=null;
  try{previous=sessionStorage.getItem(positionKey.value);}catch{/* 浏览器限制缓存时直接打开待审页。 */}
  const page=pages.value.find(row=>row.sourcePath===previous);
  if(page)void revealPage(page);
  else if(open)void focusReview();
});
watch(scope,()=>{
  mainRequest?.abort();mainRequest=undefined;detailRequest?.abort();detailRequest=undefined;fieldRequest?.abort();fieldRequest=undefined;window.clearTimeout(timer);
  snapshot.value=undefined;detail.value=undefined;workspace.value=undefined;writing.value=false;detailLoading.value=false;
  error.value='';detailError.value='';selectedPath.value='';group.value='all';search.value='';pageNumber.value=1;
  resetFieldForm();syncForm();notice.value='';showOriginal.value=false;void read();
},{immediate:true});
onBeforeUnmount(()=>{mainRequest?.abort();detailRequest?.abort();fieldRequest?.abort();window.clearTimeout(timer);});
defineExpose({stageEvidence,running,actionLabel,actionDisabled,nextAction,structurePlanId,structurePlanRevision,structureLinkError,primary,followNext,openPageForReview,useStructure,refresh:read,confirmedMenuFor,hasConfirmedTarget,hasUnsavedChanges,isSaving:()=>writing.value});
</script>

<style scoped>
h2,h3,p { margin:0 0 8px; }
.package-actions { display:flex; flex-wrap:wrap; align-items:center; gap:8px; margin-block:8px; }
.package-actions :deep(.el-button) { margin:0; }
.package-progress { display:grid; gap:6px; margin-block:12px; }
.package-review { display:grid; grid-template-columns:minmax(240px,.7fr) minmax(0,1.3fr); gap:16px; }
.package-review > *, .package-detail { min-width:0; }
.package-review label, .package-review select { display:block; margin-bottom:6px; }
.package-review select { width:100%; min-height:32px; border:1px solid var(--el-border-color); background:var(--el-bg-color); color:var(--el-text-color-primary); border-radius:4px; }
.package-page-list { list-style:none; padding:0; margin:8px 0; display:grid; gap:4px; }
.package-page-list button { width:100%; display:grid; gap:3px; padding:8px; border:1px solid var(--el-border-color); border-radius:4px; color:var(--el-text-color-primary); background:var(--el-bg-color); text-align:left; cursor:pointer; }
.package-page-list button[aria-current="true"] { border-color:var(--el-color-primary); background:var(--el-color-primary-light-9); }
.package-page-list small, code { color:var(--el-text-color-secondary); }
.package-page-list span, code { overflow-wrap:anywhere; }
.package-detail { display:grid; gap:8px; align-content:start; }
.package-detail label { display:grid; gap:5px; }
.package-detail summary { cursor:pointer; }
.package-fields ol,.package-fields ul { padding-inline-start:22px; }
.package-fields li { margin-bottom:8px; overflow-wrap:anywhere; }
.package-fields li p { white-space:pre-wrap; margin-block:3px; }
.package-field-heading { display:flex; align-items:center; gap:6px 12px; flex-wrap:wrap; }
.package-field-form { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:8px; margin:6px 0; padding:10px; border:1px solid var(--el-border-color); border-radius:4px; }
.package-field-note { grid-column:1/-1; }
.pending-field { color:var(--el-color-warning-dark-2); }
@media(max-width:760px){.package-review{grid-template-columns:1fr;}}
@media(max-width:520px){.package-field-form{grid-template-columns:1fr;}}
</style>
