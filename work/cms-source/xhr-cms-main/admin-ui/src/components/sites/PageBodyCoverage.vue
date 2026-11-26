<template>
  <details class="coverage-panel" aria-label="整页正文覆盖">
    <summary>整页正文覆盖<template v-if="result"> · {{ counts.get('UNBOUND') || 0 }} 项未形成内容单元</template></summary>
    <div class="coverage-workspace">
      <p>根据已保存的来源修订核对正文，加入本地草稿的证据会同步反映在下方。</p>
      <p v-if="loading" role="status">正在核对本页正文覆盖。</p>
      <template v-else-if="error">
        <el-alert :title="error" type="warning" :closable="false" />
        <el-button size="small" @click="read">重新读取覆盖</el-button>
      </template>
      <template v-else-if="result">
        <div class="coverage-controls">
          <el-select v-model="filter" aria-label="正文覆盖状态" size="small">
            <el-option value="all" :label="'全部来源（'+items.length+'）'" />
            <el-option v-for="(label,state) in labels" :key="state" :value="state" :label="label+'（'+(counts.get(state) || 0)+'）'" />
          </el-select>
          <el-input v-model="search" clearable size="small" aria-label="搜索正文来源" placeholder="搜索原文、定位或来源类型" />
        </div>
        <div v-if="batchCandidates.length || (filter==='IGNORED' && recoverable.length)" class="coverage-controls">
          <el-button v-if="batchCandidates.length" size="small" type="primary" plain :disabled="disabled" @click="addBatch">
            批量加入本地草稿（{{ batchCandidates.length }}）
          </el-button>
          <template v-if="filter==='IGNORED' && recoverable.length">
            <el-checkbox :model-value="chosenRecoverable.length===recoverable.length" :indeterminate="chosenRecoverable.length>0 && chosenRecoverable.length<recoverable.length"
              :disabled="disabled" @change="toggleAll($event===true)">勾选全部可恢复（{{ recoverable.length }}）</el-checkbox>
            <el-button size="small" :disabled="disabled || !chosenRecoverable.length" @click="restoreBatch">恢复选中项（{{ chosenRecoverable.length }}）</el-button>
          </template>
        </div>
        <p v-if="filter==='IGNORED' && counts.get('IGNORED')">{{ recoverable.length
          ? '仅可勾选来源完整、未被其他内容单元或正文区域覆盖的项目。其他项目可逐项复核。'
          : '当前没有符合批量恢复条件的项目，可逐项复核原判断。' }}</p>
        <ul v-if="visibleItems.length" class="coverage-items" aria-label="正文来源">
          <li v-for="item in visibleItems" :key="item.source.id">
            <el-checkbox v-if="filter==='IGNORED' && item.unitId && recoverableIds.has(item.unitId)" :model-value="restoreIds.has(item.unitId)"
              :disabled="disabled" :aria-label="'勾选恢复 '+sourceLabel(item)" @change="toggleRestore(item.unitId!,$event===true)" />
            <button type="button" :aria-current="selected?.source.id===item.source.id ? 'true' : undefined" @click="selectedId=item.source.id">
              <strong>{{ kindLabel(item.source.kind) }}</strong> · {{ sourceLabel(item) }}
            </button>
          </li>
        </ul>
        <p v-else>没有符合当前条件的来源。</p>
        <el-pagination v-if="filtered.length>pageSize" v-model:current-page="page" :page-size="pageSize" :pager-count="5"
          :total="filtered.length" layout="prev, pager, next" />
        <div v-if="selected" class="coverage-detail">
          <p><strong>{{ labels[selected.state] }}</strong> · {{ selected.reason }}</p>
          <code>{{ selected.source.selector }}</code>
          <p v-if="selected.source.repeatGroup">重复区域候选 <code>{{ selected.source.repeatGroup }}</code>。逐项核对独立记录与公开应用中的集合维护入口。</p>
          <pre>{{ selected.source.rawValue }}</pre>
          <p v-if="selected.uncoveredRanges?.length">待核对原文区间 {{ selected.uncoveredRanges.map(range=>'['+range.start+', '+range.end+')').join('、') }}</p>
          <p v-if="selected.state==='UNBOUND' && selected.bindable!==true">此来源需核对样式或适用的字段类型。</p>
          <div class="coverage-controls">
            <el-button v-if="canLocate(selected.source.id)" size="small" @click="emit('locate',selected.source.id)">定位原页</el-button>
            <el-button v-else-if="selected.relatedSourceId && canLocate(selected.relatedSourceId)" size="small" @click="emit('locate',selected.relatedSourceId)">定位覆盖区域</el-button>
            <el-button v-if="selected.unitId && units.some(unit=>unit.id===selected?.unitId)" size="small" @click="emit('review-unit',selected.unitId)">复核现有单元</el-button>
            <el-button v-if="canAdd(selected)" size="small" type="primary" :disabled="disabled"
              @click="applySource(false)">加入本地草稿</el-button>
            <el-button v-if="canAdd(selected,true)" size="small" :disabled="disabled" @click="applySource(true)">标记暂不绑定</el-button>
          </div>
        </div>
        <p v-if="notice" role="status">{{ notice }}</p>
        <details v-if="result.warnings.length">
          <summary>查看覆盖取证范围与限制</summary>
          <p v-for="(warning,index) in result.warnings" :key="index">{{ warning }}</p>
        </details>
      </template>
    </div>
  </details>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { http } from '@/api/http';
import type { DomainUnit } from '@/api/domainAgent';

type CoverageState='COVERED_BY_UNIT'|'IGNORED'|'OUTSIDE_BODY'|'COVERED_BY_REGION'|'UNBOUND';
type Range={start:number;end:number};
type CoverageItem={
  source:{id:string;kind:string;rawValue:string;selector:string;repeatGroup?:string | null};
  state:CoverageState;unitId?:string|null;relatedSourceId?:string|null;reason:string;uncoveredRanges?:Range[];bindable:boolean;recoveryAllowed?:boolean;
};
type Coverage={
  sourcePath:string;packageVersion:string;digest:string;revision:number;items:CoverageItem[];warnings:string[];
};
const props=defineProps<{
  siteId:number;sourcePath:string;packageVersion:string;digest:string;revision:number;
  disabled?:boolean;units:DomainUnit[];canLocate:(sourceId:string)=>boolean;
  addSource:(sourceId:string,ranges:Range[],ignored:boolean)=>string|null;
  addSources:(sources:{sourceId:string;ranges:Range[]}[])=>string[];
  restoreUnits:(unitIds:string[])=>string[];
}>();
const emit=defineEmits<{locate:[sourceId:string];'review-unit':[unitId:string]}>();
const labels:Record<CoverageState,string>={
  UNBOUND:'尚未形成内容单元', COVERED_BY_UNIT:'已有内容单元', IGNORED:'已标记暂不绑定',
  OUTSIDE_BODY:'正文之外', COVERED_BY_REGION:'由正文区域覆盖',
};
const draftKinds=new Set(['TEXT','IMAGE','LINK','HTML_REGION','HTML_REGION_PENDING','VIDEO_SRC','VIDEO_SOURCE','VIDEO_POSTER','BACKGROUND_IMAGE']);
const result=ref<Coverage>(), loading=ref(false), error=ref(''),notice=ref('');
const filter=ref<'all'|CoverageState>('UNBOUND'), search=ref(''), selectedId=ref(''), page=ref(1), pageSize=15;
const restoreIds=ref(new Set<string>());
const scope=computed(()=>JSON.stringify([props.siteId,props.sourcePath,props.packageVersion,props.digest,props.revision]));
let pending:AbortController|undefined;
const unitIndex=computed(()=>new Map(props.units.map(unit=>[unit.id,unit])));
const sourceIndex=computed(()=>{
  const sources=new Map<string,{range:Range;unit:DomainUnit}[]>();
  for(const unit of props.units)for(const range of unit.sources) {
    const existing=sources.get(range.sourceId) || [];
    existing.push({range,unit});
    sources.set(range.sourceId,existing);
  }
  return sources;
});

function kindLabel(kind:string) {
  if(kind==='BACKGROUND_IMAGE')return '背景图';
  if(kind.startsWith('HTML_REGION'))return '正文区域';
  if(kind==='IMAGE')return '图片';
  if(kind.startsWith('VIDEO'))return '视频';
  if(kind==='LINK')return '链接';
  return '文字';
}
function rangesFor(item:CoverageItem) {
  const size=item.source.rawValue.length;
  let ranges=(item.uncoveredRanges ?? [{start:0,end:size}])
    .filter(({start,end})=>Number.isInteger(start)&&Number.isInteger(end)&&start>=0&&end<=size&&end>start);
  for(const {range:ref} of sourceIndex.value.get(item.source.id) || []) {
    ranges=ranges.flatMap(range=>{
      if(ref.start>=range.end || ref.end<=range.start)return [range];
      const remainder:Range[]=[];
      if(ref.start>range.start)remainder.push({start:range.start,end:ref.start});
      if(ref.end<range.end)remainder.push({start:ref.end,end:range.end});
      return remainder;
    });
  }
  return ranges;
}
const items=computed(()=>result.value?.items.map(item=>{
  if(item.state==='OUTSIDE_BODY'||item.state==='COVERED_BY_REGION')return item;
  const unit=item.unitId ? unitIndex.value.get(item.unitId) : undefined;
  if(unit && (item.state==='IGNORED'||item.state==='COVERED_BY_UNIT')){
    return unit.ignored===(item.state==='IGNORED') ? item : {...item,
      state:unit.ignored ? 'IGNORED' as const : 'COVERED_BY_UNIT' as const,
      reason:'当前本地判断已调整，保存整页确认后会更新服务器依据。'};
  }
  if(item.state!=='UNBOUND')return item;
  const ranges=rangesFor(item);
  const linked=sourceIndex.value.get(item.source.id);
  const current=linked?.find(({unit})=>!unit.ignored)?.unit ?? linked?.[0]?.unit;
  if(!ranges.length && current)return {...item,state:current.ignored ? 'IGNORED' as const : 'COVERED_BY_UNIT' as const,
    unitId:current.id,reason:'已核对至当前本地内容单元。'};
  return {...item,uncoveredRanges:ranges};
}) || []);
const counts=computed(()=>{
  const result=new Map<CoverageState,number>();
  for(const item of items.value)result.set(item.state,(result.get(item.state) || 0)+1);
  return result;
});
const filtered=computed(()=>{
  const term=search.value.trim().toLowerCase();
  return items.value.filter(item=>(filter.value==='all'||item.state===filter.value)
    && [item.source.rawValue,item.source.selector,kindLabel(item.source.kind)].some(value=>value.toLowerCase().includes(term)));
});
const visibleItems=computed(()=>filtered.value.slice((page.value-1)*pageSize,page.value*pageSize));
const selected=computed(()=>visibleItems.value.find(item=>item.source.id===selectedId.value) || visibleItems.value[0]);
watch([search,filter],()=>{page.value=1;selectedId.value='';});
watch(filtered,items=>{page.value=Math.min(page.value,Math.max(1,Math.ceil(items.length/pageSize)));});
function sourceLabel(item:CoverageItem) {
  const raw=item.source.rawValue.trim().replace(/\s+/g,' ');
  return (raw || item.source.selector).slice(0,65);
}
function canAdd(item:CoverageItem,ignored=false) {
  if(item.state!=='UNBOUND' || !draftKinds.has(item.source.kind)
    || (item.bindable!==true && (!ignored || item.source.kind==='BACKGROUND_IMAGE')))return false;
  const ranges=rangesFor(item);
  if(item.source.kind==='TEXT')return ranges.length>0 && ranges.length<=20;
  return ranges.length===1 && ranges[0]?.start===0 && ranges[0].end===item.source.rawValue.length;
}
const savedSourceIndex=computed(()=>new Map(result.value?.items.map(item=>[item.source.id,item.source])));
const duplicateCount=computed(()=>{
  const index=new Map<string,number>();
  for(const item of result.value?.items || []) {
    if(item.state==='OUTSIDE_BODY')continue;
    const key=duplicateKey(item.source);
    index.set(key,(index.get(key) || 0)+1);
  }
  return index;
});
function duplicateKey(source:CoverageItem['source']){return source.kind+'\n'+source.rawValue.trim();}
function isolatedSource(item:CoverageItem) {
  if(item.relatedSourceId || !item.source.selector || !item.source.rawValue.trim() || duplicateCount.value.get(duplicateKey(item.source))!==1)return false;
  return !props.units.some(unit=>!unit.ignored && unit.sources.some(ref=>{
    if(ref.sourceId===item.source.id)return true;
    const other=savedSourceIndex.value.get(ref.sourceId);
    if(!other)return unit.fieldType==='Editor';
    return other.selector===item.source.selector ||
      (unit.fieldType==='Editor' && item.source.selector.startsWith(other.selector+' > '));
  }));
}
const batchCandidates=computed(()=>items.value.flatMap(item=>{
  if(!canAdd(item) || !isolatedSource(item))return [];
  const ranges=rangesFor(item);
  return ranges.length===1 && ranges[0]?.start===0 && ranges[0].end===item.source.rawValue.length
    ? [{sourceId:item.source.id,ranges}] : [];
}));
function recoveryTypeMatches(kind:string,fieldType:string) {
  if(kind==='TEXT')return ['SingleLine','MultiLine','DateTime'].includes(fieldType);
  if(kind==='HTML_REGION')return fieldType==='Editor';
  return ['IMAGE','BACKGROUND_IMAGE'].includes(kind) && fieldType==='Image';
}
const recoverable=computed(()=>items.value.flatMap(item=>{
  if(item.state!=='IGNORED' || item.recoveryAllowed!==true || item.bindable!==true || !item.unitId || !isolatedSource(item))return [];
  const unit=unitIndex.value.get(item.unitId), ref=unit?.sources[0];
  return unit?.ignored && unit.sources.length===1 && ref?.sourceId===item.source.id &&
    ref.start===0 && ref.end===item.source.rawValue.length && recoveryTypeMatches(item.source.kind,unit.fieldType) ? [unit.id] : [];
}));
const recoverableIds=computed(()=>new Set(recoverable.value));
const chosenRecoverable=computed(()=>recoverable.value.filter(id=>restoreIds.value.has(id)));
function toggleRestore(id:string,checked:boolean) {
  if(!recoverableIds.value.has(id))return;
  const next=new Set(restoreIds.value);
  if(checked)next.add(id);else next.delete(id);
  restoreIds.value=next;
}
function toggleAll(checked:boolean){restoreIds.value=new Set(checked ? recoverable.value : []);}
function addBatch(){
  if(props.disabled || !batchCandidates.value.length)return;
  const requested=batchCandidates.value;
  const added=props.addSources(requested);
  notice.value=`已加入本地草稿 ${added.length} 项。${added.length<requested.length ? '另有 '+(requested.length-added.length)+' 项保留待核对。' : ''}`;
}
function restoreBatch() {
  if(props.disabled || !chosenRecoverable.value.length)return;
  const requested=chosenRecoverable.value;
  const restored=props.restoreUnits(requested);
  restoreIds.value=new Set();
  notice.value=`已恢复为本地待绑定草稿 ${restored.length} 项。${restored.length<requested.length ? '另有 '+(requested.length-restored.length)+' 项保留暂不绑定。' : ''}`;
}
function applySource(ignored:boolean) {
  const item=selected.value;
  if(!item || props.disabled || !canAdd(item,ignored))return;
  const id=props.addSource(item.source.id,rangesFor(item),ignored);
  notice.value=id ? (ignored ? '已在本地标记暂不绑定，仍可复核。' : '已加入本地草稿，请核对业务名称与字段类型。')
    : '此来源目前无法加入草稿，请核对现有单元或重新读取覆盖。';
  if(id)emit('review-unit',id);
}
async function read() {
  pending?.abort();
  const {siteId,sourcePath,packageVersion,digest,revision}=props;
  const key=scope.value;
  result.value=undefined;
  error.value='';
  notice.value='';
  restoreIds.value=new Set();
  loading.value=true;
  const controller=new AbortController();
  pending=controller;
  const current=()=>pending===controller && scope.value===key;
  try{
    const response=await http.get<Coverage>(`/api/admin/v2/sites/${siteId}/template-seed/coverage`,{
      params:{sourcePath,packageVersion,digest,revision},signal:controller.signal,timeout:60000,
    });
    if(!current())return;
    if(response.sourcePath!==sourcePath || response.packageVersion!==packageVersion || response.digest!==digest || response.revision!==revision)
      throw new Error('正文覆盖所属来源或修订已变化，请重新读取。');
    result.value=response;
  } catch(cause) {
    if(current())error.value=cause instanceof Error ? cause.message : '读取正文覆盖失败。';
  } finally {
    if(current()){
      pending=undefined;
      loading.value=false;
    }
  }
}
watch(scope,()=>{void read();},{immediate:true});
onBeforeUnmount(()=>{pending?.abort();pending=undefined;});
</script>

<style scoped>
.coverage-panel { flex-shrink: 0; max-height: min(42dvh, 340px); overflow: auto; border: 1px solid var(--el-border-color-light); border-radius: var(--el-border-radius-base); padding: 8px 10px; }
.coverage-panel > summary { cursor: pointer; font-weight: 600; }
.coverage-workspace { display: grid; gap: 8px; padding-top: 8px; }
.coverage-workspace p { margin: 0; overflow-wrap: anywhere; }
.coverage-controls { display: flex; flex-wrap: wrap; gap: 6px; }
.coverage-controls .el-select, .coverage-controls .el-input { flex: 1 1 170px; }
.coverage-controls .el-button { margin: 0; }
.coverage-items { max-height: 116px; overflow: auto; margin: 0; padding: 0; list-style: none; }
.coverage-items li { display: flex; gap: 6px; align-items: center; }
.coverage-items .el-checkbox { margin: 0; }
.coverage-items button { width: 100%; border: 0; background: none; text-align: left; padding: 5px; color: inherit; cursor: pointer; overflow-wrap: anywhere; }
.coverage-items button[aria-current] { background: var(--el-color-primary-light-9); color: var(--el-color-primary); }
.coverage-detail code { display: block; overflow-wrap: anywhere; }
.coverage-detail pre { max-height: 96px; overflow: auto; white-space: pre-wrap; overflow-wrap: anywhere; }
</style>
