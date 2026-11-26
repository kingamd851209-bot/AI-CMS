<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { ElMessageBox } from 'element-plus';
import { confirmNavigationLinks, previewNavigationLinks, readNavigationLinks,
  type NavigationLinkEvidence, type NavigationLinkRequest, type NavigationLinkReview } from '@/api/siteNavigationLinks';
import { getSiteServiceStatus } from '@/api/templateRecognition';

import type { SiteNavigationNode } from '@/api/siteModel';

const props=defineProps<{
  siteId:number; planId:string; planRevision:number; navigationRevision:number; packageVersion:string;
  sourcePath:string; sourcePaths:string[]; nodes:SiteNavigationNode[]; disabled?:boolean; focusHref?:string; home?:boolean; rootMenuId?:number; canOpenTemplate?:boolean; listSource?:boolean;
}>();
const emit=defineEmits<{busy:[boolean]; prerequisite:[node:SiteNavigationNode]; template:[]; list:[]; manageTemplates:[]; changed:[]}>();
const evidence=ref<NavigationLinkEvidence|null>(null);
const selections=ref<Record<string,string>>({});
const baseline=ref<Record<string,string>>({});
const preview=ref<{body:NavigationLinkRequest;review:NavigationLinkReview}|null>(null);
const busy=ref(false);
const error=ref('');
const needsRead=ref(false);
const showAllLinks=ref(false);
const siteAccessUrl=ref<string|null>(null);
const reuseSource=ref('');
const reuseNotice=ref('');
const reuseSources=computed(()=>[...new Set(props.sourcePaths)].filter(path=>path!==props.sourcePath));
const matchingLinks=computed(()=>(evidence.value?.anchors || []).filter(anchor=>anchor.rawHref===props.focusHref));
const visibleLinks=computed(()=>props.focusHref===undefined || showAllLinks.value
  ? evidence.value?.anchors || [] : matchingLinks.value);
const hasVisibleSelection=computed(()=>visibleLinks.value.some(anchor=>selections.value[anchor.selector]));
const blocked=computed(()=>props.disabled||busy.value);
const hasUnsavedChanges=computed(()=>JSON.stringify(selections.value)!==JSON.stringify(baseline.value));
const targetsByKey=computed(()=>new Map(evidence.value?.targets.map(target=>[target.nodeKey,target])));
type Anchor=NavigationLinkEvidence['anchors'][number];
type Target=NavigationLinkEvidence['targets'][number];
function eligibleTargets(anchor:Anchor) {
  return (evidence.value?.targets || []).filter(target=>anchor.eligibleTargetKeys
    ? anchor.eligibleTargetKeys.includes(target.nodeKey)
    : anchor.region==='BODY' ? target.nodeKey===anchor.suggestedNodeKey : !target.kind);
}
function targetLabel(target:Target) {
  const kinds={NATIVE_FORM:'原生表单',CONTENT_LIST:'栏目列表',PARENT_ENTRY:'已核对入口',FRAGMENT:'正文锚点'};
  const kind=target.kind ? kinds[target.kind] : '';
  return `${target.label}（#${target.menuId}）${kind ? ` · ${kind}` : ''} · ${target.url}`;
}
function publicRoute(path:string|undefined) {
  if(!path || !siteAccessUrl.value || !path.startsWith('/') || path.startsWith('//') || path.includes('\\'))return null;
  try{
    const base=new URL(siteAccessUrl.value);
    const address=new URL(path,base);
    return ['http:','https:'].includes(base.protocol) && address.origin===base.origin ? address.href : null;
  }catch{return null;}
}
const removedChanges=computed(()=>(evidence.value?.confirmed?.changes || []).filter(change=>
  preview.value && !preview.value.body.selections.some(selected=>selected.selector===change.selector)));
// 原包路径和导航名称须同时命中；共享原页的重名栏目留给人工区分。
const unavailableTargets=computed(()=>{
  const matches=new Map<string,{node:SiteNavigationNode;count:number}>();
  for(const anchor of visibleLinks.value){
    if(selections.value[anchor.selector])continue;
    const href=anchor.rawHref.trim();
    if(!href || href.startsWith('#') || href.startsWith('?') || href.startsWith('//') || /^[a-z][\w+.-]*:/i.test(href))continue;
    let path:string;
    try{path=anchor.repair?.sourcePath ?? decodeURIComponent(new URL(href,new URL(props.sourcePath,'https://cms.invalid/')).pathname).slice(1);}catch{continue;}
    const nodes=props.nodes.filter(node=>!node.drift && node.menuId && node.kind!=='DIRECTORY' && node.sourcePath===path &&
      [node.name,node.cmsName].some(name=>name?.trim()===anchor.label.trim()));
    const node=nodes[0];
    if(nodes.length!==1 || !node || targetsByKey.value.has(node.key))continue;
    const entry=matches.get(node.key);
    if(entry)entry.count++;else matches.set(node.key,{node,count:1});
  }
  return [...matches.values()];
});
const suggestions=computed(()=>visibleLinks.value.flatMap(anchor=>{
  const key=anchor.suggestedNodeKey;
  return !anchor.repair && anchor.region !== 'BODY' && key && !selections.value[anchor.selector] && eligibleTargets(anchor).some(target=>target.nodeKey===key && (!target.kind || target.kind==='PARENT_ENTRY' || target.kind==='FRAGMENT'))
    ? [{selector:anchor.selector,nodeKey:key}] : [];
}));
const canPreview=computed(()=>!!evidence.value && !blocked.value && !needsRead.value &&
  (!!Object.keys(selections.value).length || !!evidence.value.confirmed?.changes.length && hasUnsavedChanges.value));
defineExpose({hasUnsavedChanges});
let version=0;
function reset() {
  version++;
  evidence.value=null;
  selections.value={};
  baseline.value={};
  preview.value=null;
  error.value='';
  needsRead.value=false;
  busy.value=false;
  showAllLinks.value=false;
  siteAccessUrl.value=null;
  reuseNotice.value='';
}
watch(()=>[props.siteId,props.planId,props.planRevision,props.navigationRevision,props.packageVersion,props.sourcePath],reset,{flush:'sync'});
watch(()=>[props.siteId,props.planId,props.packageVersion],()=>{reuseSource.value='';},{flush:'sync'});
watch(()=>props.focusHref,()=>{
  showAllLinks.value=false;
  preview.value=null;
  install(evidence.value?.confirmed);
});
watch(busy,value=>emit('busy',value),{flush:'sync'});
onBeforeUnmount(()=>{version++;if(busy.value)emit('busy',false);});
function install(review:NavigationLinkReview|null|undefined) {
  const saved=Object.fromEntries((review?.changes || []).map(change=>[change.selector,change.nodeKey]));
  baseline.value=saved;
  selections.value={...saved};
}
async function read() {
  if(blocked.value)return;
  const token=version;
  if(hasUnsavedChanges.value){
    try{await ElMessageBox.confirm('重新读取会丢弃当前未确认的导航映射。','重新读取原文证据',{type:'warning'});}
    catch{return;}
  }
  if(token!==version||blocked.value)return;
  busy.value=true;
  error.value='';
  reuseNotice.value='';
  preview.value=null;
  try{
    const [result, service]=await Promise.all([
      readNavigationLinks(props.siteId,props.planId,props.packageVersion,props.sourcePath),
      getSiteServiceStatus(props.siteId).catch(()=>null),
    ]);
    if(token!==version)return;
    if(result.sourcePath!==props.sourcePath || result.navigationRevision!==props.navigationRevision)
      throw new Error('导航审核版本已变化，请返回整站结构刷新导航。');
    evidence.value=result;
    siteAccessUrl.value=service?.status==='RUNNING' ? service.accessUrl : null;
    install(result.confirmed);
    needsRead.value=false;
  }catch(cause){
    if(token===version){
      needsRead.value=true;
      error.value=cause instanceof Error ? cause.message : '无法读取公共链接证据';
    }
  }finally{if(token===version)busy.value=false;}
}
function change(anchor:Anchor,nodeKey:string) {
  if(blocked.value || nodeKey && !eligibleTargets(anchor).some(target=>target.nodeKey===nodeKey))return;
  preview.value=null;
  error.value='';
  if(nodeKey)selections.value[anchor.selector]=nodeKey;
  else delete selections.value[anchor.selector];
}
function adoptSuggestions() {
  if(blocked.value||!suggestions.value.length)return;
  preview.value=null;
  error.value='';
  selections.value={...selections.value,...Object.fromEntries(suggestions.value.map(item=>[item.selector,item.nodeKey]))};
}
function sharedLinkKey(anchor:Anchor, path:string) {
  if (!['HEADER','FOOTER'].includes(anchor.region) || anchor.repair || !anchor.rawHref.trim()
    || /^[#?]|^\/\/|^[a-z][\w+.-]*:|\\/i.test(anchor.rawHref)) return;
  try {
    const url=new URL(anchor.rawHref,new URL(path,'https://cms.invalid/'));
    if (url.origin==='https://cms.invalid')return JSON.stringify([anchor.region,anchor.selector,anchor.label,url.pathname,url.search,url.hash]);
  }catch{return;}
}
async function reuseReviewedLinks() {
  const current=evidence.value, from=reuseSource.value, token=version;
  if(blocked.value || !current || !reuseSources.value.includes(from))return;
  busy.value=true;
  error.value='';
  reuseNotice.value='';
  try {
    const reference=await readNavigationLinks(props.siteId,props.planId,props.packageVersion,from);
    if(token!==version)return;
    if(reference.sourcePath!==from || reference.navigationRevision!==current.navigationRevision)
      throw new Error('参考页的导航版本已变化，请重新读取原文。');
    const previous=new Map(reference.anchors.map(anchor=>[anchor.selector,anchor]));
    const saved=new Map<string,Target>();
    for(const change of reference.confirmed?.changes || []) {
      const anchor=previous.get(change.selector);
      if(!anchor || anchor.rawHref!==change.rawHref)continue;
      const key=sharedLinkKey(anchor,from);
      const target=reference.targets.find(row=>row.nodeKey===change.nodeKey && row.menuId===change.menuId && row.url===change.url);
      if(key && target && (!anchor.eligibleTargetKeys || anchor.eligibleTargetKeys.includes(target.nodeKey)))saved.set(key,target);
    }
    let count=0;
    for(const anchor of visibleLinks.value) {
      const key=sharedLinkKey(anchor,current.sourcePath);
      const target=key ? saved.get(key) : undefined;
      if(!target || selections.value[anchor.selector])continue;
      const matches=eligibleTargets(anchor).filter(row=>row.menuId===target.menuId && row.url===target.url
        && (row.kind ?? null)===(target.kind ?? null));
      if(matches.length!==1 || anchor.suggestedNodeKey && anchor.suggestedNodeKey!==matches[0].nodeKey)continue;
      selections.value[anchor.selector]=matches[0].nodeKey;
      count++;
    }
    if(count)preview.value=null;
    reuseNotice.value=count ? `已沿用 ${from} 的 ${count} 项头尾链接选择，请预览本页变更。`
      : '参考页没有可与当前头尾位置和正式目标唯一对应的已审核选择。';
  }catch(cause){if(token===version)error.value=cause instanceof Error ? cause.message : '无法读取参考页映射';}
  finally{if(token===version)busy.value=false;}
}
function clearSelections() {
  if(blocked.value)return;
  preview.value=null;
  error.value='';
  for(const anchor of visibleLinks.value)delete selections.value[anchor.selector];
}
async function check() {
  const source=evidence.value;
  if(!canPreview.value||!source)return;
  const token=version;
  const body={
    packageVersion:props.packageVersion,sourcePath:source.sourcePath,sourceDigest:source.sourceDigest,
    navigationRevision:source.navigationRevision,previousDigest:source.confirmed?.digest,
    selections:source.anchors.flatMap(anchor=>{
      const nodeKey=selections.value[anchor.selector];
      return nodeKey ? [{selector:anchor.selector,rawHref:anchor.rawHref,nodeKey}] : [];
    }),
  };
  busy.value=true;
  error.value='';
  preview.value=null;
  try{
    const review=await previewNavigationLinks(props.siteId,props.planId,body);
    if(token===version)preview.value={body,review};
  }catch(cause){
    if(token===version)error.value=cause instanceof Error ? cause.message : '无法核对链接映射';
  }finally{if(token===version)busy.value=false;}
}
async function confirm() {
  const pending=preview.value;
  const source=evidence.value;
  if(blocked.value||!pending||!source)return;
  const token=version;
  busy.value=true;
  error.value='';
  try{
    const count=pending.review.changes.length;
    const removed=removedChanges.value.length;
    try{await ElMessageBox.confirm(
      `确认页面 ${source.sourcePath} 的公共链接映射？保存 ${count} 项${removed ? `，移除 ${removed} 项` : ''}。保存后请重新应用来源模板并生成静态页。`,
      '确认公共链接映射',{type:'warning',confirmButtonText:'确认映射',cancelButtonText:'取消'},
    );}catch{return;}
    if(token!==version||preview.value!==pending||evidence.value!==source)return;
    const review=await confirmNavigationLinks(props.siteId,props.planId,pending.body,pending.review.digest);
    if(token!==version)return;
    evidence.value={...source,confirmed:review};
    install(review);
    preview.value=null;
    emit('changed');
  }catch(cause){
    if(token===version){
      preview.value=null;
      needsRead.value=true;
      error.value=cause instanceof Error ? cause.message : '无法确认公共链接映射';
    }
  }finally{if(token===version)busy.value=false;}
}
</script>

<template>
  <el-card shadow="never">
    <template #header><strong>公共导航链接审核</strong></template>
    <p>来源 {{ sourcePath }}。逐条核对原链接并选择正式目标。选项可包括已发布的本站栏目列表和原生表单。</p>
    <p v-if="focusHref!==undefined">静态核验中的原 href：<code>{{ focusHref }}</code>。</p>
    <el-button :loading="busy" :disabled="blocked" @click="read">读取当前页公共链接</el-button>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <template v-if="evidence">
      <p v-if="evidence.navigationStatus==='DRAFT' || evidence.navigationStatus==='CONFIRMED'">导航结构待应用。可先审核已发布的本站目标。</p>
      <el-button v-if="focusHref!==undefined" link type="primary" @click="showAllLinks=!showAllLinks">
        {{ showAllLinks ? '仅看匹配的原 href' : '查看全部公共链接' }}
      </el-button>
      <p v-if="focusHref!==undefined && matchingLinks.length>1" role="status">原页有 {{ matchingLinks.length }} 处相同 href。请按来源位置逐条核对。</p>
      <el-button v-if="focusHref!==undefined && !matchingLinks.length" link type="primary" :disabled="blocked" @click="emit('manageTemplates')">打开模板管理</el-button>
      <p v-if="!evidence.targets.length">暂无可选的正式目标。请先核对栏目的公开路由、模板和发布状态。</p>
      <details v-if="unavailableTargets.length" open>
        <summary>需要先准备正式路由的栏目（{{ unavailableTargets.length }}）</summary>
        <p>这些链接的原页路径和导航名称能对应到已建栏目。准备好目标页后，返回这里重新读取公共链接并确认。</p>
        <div v-for="{node,count} in unavailableTargets" :key="node.key">
          <el-button link type="primary" :disabled="blocked || hasUnsavedChanges" @click="emit('prerequisite',node)">处理「{{ node.name }}」</el-button>
          <span>{{ node.sourcePath }} · 本页 {{ count }} 处链接</span>
        </div>
        <p v-if="hasUnsavedChanges">请先确认本页待保存的链接选择。</p>
      </details>
      <el-button v-if="suggestions.length" :disabled="blocked" @click="adoptSuggestions">采用 {{ suggestions.length }} 项无歧义建议</el-button>
      <div v-if="reuseSources.length" class="actions">
        <el-select v-model="reuseSource" filterable clearable :disabled="blocked" aria-label="公共链接参考原页" placeholder="选择已审核的参考原页">
          <el-option v-for="path in reuseSources" :key="path" :label="path" :value="path" />
        </el-select>
        <el-button :disabled="blocked || !reuseSources.includes(reuseSource)" @click="reuseReviewedLinks">沿用参考页头尾选择</el-button>
        <span v-if="reuseNotice" role="status">{{ reuseNotice }}</span>
      </div>
      <el-button v-if="hasVisibleSelection" :disabled="blocked" @click="clearSelections">清空当前显示的选择</el-button>
      <el-table :data="visibleLinks" row-key="selector" size="small" max-height="400"
        :empty-text="focusHref!==undefined && !showAllLinks ? '公共链接中未找到相同的原 href，可查看全部或在模板管理中核对。' : '当前页未读取到可审核的公共本地链接'">
        <el-table-column label="原链接" min-width="190"><template #default="{row}">
          {{ row.label }} · <code>{{ row.rawHref }}</code>
          <div v-if="row.repair"><strong>地址修正建议</strong><div>{{ row.repair.reason }}</div>
            <small>包内目标 <code>{{ row.repair.sourcePath }}</code></small>
          </div>
        </template></el-table-column>
        <el-table-column label="来源证据" min-width="150" show-overflow-tooltip><template #default="{row}">{{ row.region === 'BODY' ? '正文' : row.region }} · {{ row.selector }}</template></el-table-column>
        <el-table-column label="正式目标" min-width="220"><template #default="{row}">
          <el-select :model-value="selections[row.selector]" clearable filterable placeholder="选择目标栏目" :disabled="blocked || (!eligibleTargets(row).length && !selections[row.selector])"
            :aria-label="`选择 ${row.label || row.rawHref} 的导航目标`" @change="change(row,String($event || ''))">
            <el-option v-for="target in eligibleTargets(row)" :key="target.nodeKey" :value="target.nodeKey" :label="targetLabel(target)" />
          </el-select>
          <div v-if="selections[row.selector]">
            <a v-if="publicRoute(targetsByKey.get(selections[row.selector])?.url)" :href="publicRoute(targetsByKey.get(selections[row.selector])?.url)!"
              target="_blank" rel="noopener noreferrer">打开正式路由</a>
            <small v-else-if="targetsByKey.has(selections[row.selector])">{{ targetsByKey.get(selections[row.selector])?.url }}</small>
            <small v-else>已保存的目标当前不可用，请核对原栏目发布状态或清除此项。</small>
          </div>
          <el-button v-else-if="row.repair && targetsByKey.has(row.suggestedNodeKey)" link type="primary"
            :disabled="blocked" @click="change(row,row.suggestedNodeKey)">选用建议 {{ targetsByKey.get(row.suggestedNodeKey)?.url }}</el-button>
          <small v-else-if="row.repair">待核对唯一的正式目标后，可预览并确认修改。</small>
          <small v-else-if="row.region !== 'BODY' && targetsByKey.has(row.suggestedNodeKey)">建议 {{ targetsByKey.get(row.suggestedNodeKey)?.label }}</small>
          <small v-else-if="!eligibleTargets(row).length">{{ row.problem || '此原链接暂无可选目标' }}</small>
        </template></el-table-column>
      </el-table>
      <template v-if="evidence.confirmed">
        <p role="status">服务端已保存 {{ evidence.confirmed.changes.length }} 项映射。清除已确认项后，重新应用模板时恢复原链接。</p>
        <p v-if="listSource">在「列表与查询」读取当前应用，按已确认的计划重新编译并应用，再生成此栏目静态页。</p>
        <p v-else>核对{{ home ? '首页及其维护页' : '来源页' }}当前应用，预览并确认模板变更，再生成静态页。</p>
        <el-button v-if="listSource" :disabled="blocked" @click="emit('list')">继续应用列表模板</el-button>
        <el-button v-else-if="canOpenTemplate" :disabled="blocked" @click="emit('template')">前往模板公开应用</el-button>
        <p v-else>选择此来源页对应的栏目和字段计划后，可读取其模板公开应用。</p>
        <el-button :disabled="blocked" @click="$router.push({path:'/admin/v2/cms/static-publish',query:{siteId:String(siteId),...(home && rootMenuId ? {menuId:String(rootMenuId)} : {})}})">打开原生静态发布</el-button>
      </template>
      <p v-if="needsRead" role="status">请重新读取当前页证据后再预览确认。</p>
      <p v-if="hasUnsavedChanges" role="status">有未确认的链接映射。</p>
      <el-button :disabled="!canPreview" @click="check">预览链接变更</el-button>
      <template v-if="preview">
        <p>将保存 {{ preview.review.changes.length }} 项映射，移除 {{ removedChanges.length }} 项。重新应用来源模板后生效，已生成的静态页需重新发布。</p>
        <p v-if="!preview.review.changes.length">本次将清除该页全部已确认映射。</p>
        <el-table :data="preview.review.changes" row-key="selector" size="small" max-height="280">
          <el-table-column prop="rawHref" label="原链接" min-width="150" />
          <el-table-column prop="label" label="目标栏目" min-width="125" />
          <el-table-column prop="url" label="新链接" min-width="150" />
          <el-table-column prop="selector" label="来源证据" min-width="150" />
        </el-table>
        <p v-if="removedChanges.length">将恢复原链接的来源位置</p>
        <el-table v-if="removedChanges.length" :data="removedChanges" row-key="selector" size="small" max-height="180">
          <el-table-column prop="rawHref" label="原链接" min-width="150" />
          <el-table-column prop="selector" label="来源证据" min-width="150" />
        </el-table>
        <el-button type="primary" :disabled="blocked" @click="confirm">确认公共链接映射</el-button>
      </template>
    </template>
  </el-card>
</template>
<style scoped>
.actions { display:flex; flex-wrap:wrap; align-items:center; gap:8px; margin-block:8px; }
.actions .el-select { width:min(340px, 100%); }
</style>
