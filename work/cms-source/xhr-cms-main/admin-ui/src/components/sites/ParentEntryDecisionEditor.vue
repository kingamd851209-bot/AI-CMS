<template>
  <section class="entry-editor" aria-label="入口职责">
    <p v-if="node.sourcePath" class="entry-source">{{ node.sourcePath }} · 栏目 #{{ node.menuId }}</p>
    <p>保存审核决定后，清单会更新下一步。栏目和公开路由需分别处理。</p>
    <p v-if="savedEntry" aria-live="polite">
      已保存入口职责 · {{ roles[savedEntry.role].label }}<template v-if="savedEntry.targetNodeKey"> · {{ targetLabel(savedEntry.targetNodeKey) }}</template>
    </p>
    <el-alert v-if="savedEntry?.valid===false" :title="savedEntry.reason || '原决定已不适用，请重新审核。'" type="warning" :closable="false" />
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <p v-if="reading" role="status">正在读取入口职责。</p>
    <div v-if="decisions" class="entry-roles" role="radiogroup" aria-label="入口职责">
      <label v-for="(option, value) in roles" :key="value" class="entry-option" :class="{'is-selected':role===value}">
        <input v-model="role" type="radio" :name="groupId" :value="value" :aria-label="option.label" :aria-describedby="groupId+'-'+value"
          :disabled="disabled || reading || saving || (value==='INDEPENDENT_OVERVIEW' && !node.sourcePath)" />
        <span>
          <span class="entry-label">{{ option.label }}</span>
          <span :id="groupId+'-'+value" class="entry-consequence">{{ option.description }}</span>
        </span>
      </label>
    </div>
    <el-select v-if="decisions && role==='REDIRECT'" v-model="target" filterable aria-label="导向目标" placeholder="选择导向栏目"
      :disabled="disabled || reading || saving">
      <el-option v-for="destination in targets" :key="destination.key" :label="targetLabel(destination.key)" :value="destination.key" />
    </el-select>
    <div class="entry-actions">
      <el-button v-if="decisions" type="primary" :loading="saving" :disabled="!canSave" @click="save">保存入口职责</el-button>
      <el-button v-if="error" :disabled="saving || reading" @click="reload">重新读取决定</el-button>
      <slot name="actions" />
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref, useId, watch } from 'vue';
import { ElMessageBox } from 'element-plus';
import { siteModelApi, type ParentEntryDecision, type ParentEntryDecisions, type ParentEntryRole, type SiteModelPlan,
  type SiteNavigation, type SiteNavigationNode } from '@/api/siteModel';

const props = defineProps<{ siteId: number; plan: SiteModelPlan; navigation: SiteNavigation; node: SiteNavigationNode; disabled?: boolean }>();
const emit = defineEmits<{ busy: [boolean]; decision: [ParentEntryDecision | undefined]; saved: [ParentEntryDecisions]; }>();
const groupId=useId();
const roles: Record<ParentEntryRole,{label:string; description:string}> = {
  DIRECTORY:{label:'作为目录',description:'按层级组织下级栏目，继续核对子入口和公共导航。'},
  REDIRECT:{label:'导向其他栏目',description:'选定一个已对应的栏目，待目标具备正式地址后核对导航链接。'},
  INDEPENDENT_OVERVIEW:{label:'独立概览',description:'保留独立页面入口，先核对分类卡片及各子栏目的正式地址。'},
};
const decisions=ref<ParentEntryDecisions>();
const role=ref<ParentEntryRole|null>(null);
const target=ref<string|null>(null);
const reading=ref(false), saving=ref(false), error=ref('');
let pending: AbortController | undefined;
let mounted=true;
const scope=computed(()=>JSON.stringify([props.siteId,props.plan.id,props.plan.packageVersion,props.plan.revision,props.navigation.revision,props.node.key]));
const savedEntry=computed(()=>decisions.value?.items.find(item=>item.nodeKey===props.node.key));
const targets=computed(()=>props.navigation.nodes.filter(node=>node.key!==props.node.key && node.kind!=='DIRECTORY' &&
  node.menuId && node.sourcePath && !node.drift &&
  !decisions.value?.items.some(item=>item.nodeKey===node.key && item.valid && item.role==='DIRECTORY')));
const dirty=computed(()=>!!decisions.value && (role.value!==(savedEntry.value?.role ?? null) ||
  (role.value==='REDIRECT' && target.value!==(savedEntry.value?.targetNodeKey ?? null))));
const canSave=computed(()=>!!(decisions.value && role.value && (dirty.value || savedEntry.value?.valid===false) &&
  !props.disabled && !reading.value && !saving.value && (role.value!=='INDEPENDENT_OVERVIEW' || props.node.sourcePath) &&
  (role.value!=='REDIRECT' || targets.value.some(node=>node.key===target.value))));

function targetLabel(key:string) {
  const node=props.navigation.nodes.find(item=>item.key===key);
  if(!node)return '原目标待核对';
  const parent=node.parentKey && props.navigation.nodes.find(item=>item.key===node.parentKey)?.name;
  const name=node.cmsName || node.name;
  return `${parent ? parent+' / ' : ''}${name}${node.menuId ? '（#'+node.menuId+'）' : ''}`;
}
function receive(result: ParentEntryDecisions) {
  decisions.value=result;
  role.value=savedEntry.value?.role ?? null;
  target.value=savedEntry.value?.targetNodeKey ?? null;
  emit('decision',savedEntry.value);
}
async function read() {
  pending?.abort();
  const key=scope.value;
  decisions.value=undefined;
  emit('decision',undefined);
  role.value=null;
  target.value=null;
  error.value='';
  reading.value=true;
  const controller=new AbortController();
  pending=controller;
  const current=()=>pending===controller && scope.value===key;
  try{
    const result=await siteModelApi.entryDecisions(props.siteId,props.plan,props.navigation.revision,controller.signal);
    if(current())receive(result);
  } catch(cause){
    if(current())error.value=cause instanceof Error ? cause.message : '读取入口职责失败。';
  } finally {
    if(current()){
      pending=undefined;
      reading.value=false;
    }
  }
}
async function reload() {
  if(dirty.value) {
    try { await ElMessageBox.confirm('重新读取将放弃尚未保存的入口职责。','重新读取入口职责',{type:'warning'}); }
    catch { return; }
  }
  await read();
}
async function save() {
  const store=decisions.value, selected=role.value, key=scope.value;
  if(!canSave.value || !store || !selected)return;
  saving.value=true;
  error.value='';
  emit('busy',true);
  try{
    const result=await siteModelApi.saveEntryDecision(props.siteId,props.plan,props.navigation.revision,store.revision,{
      nodeKey:props.node.key,role:selected,targetNodeKey:selected==='REDIRECT' ? target.value : null,
    });
    if(mounted && scope.value===key) {
      receive(result);
      emit('saved',result);
    }
  } catch(cause) {
    if(mounted && scope.value===key)error.value=cause instanceof Error ? cause.message : '保存入口职责失败，请重新读取后再试。';
  } finally {
    saving.value=false;
    if(mounted)emit('busy',false);
  }
}
watch(scope,()=>{void read();},{immediate:true});
onBeforeUnmount(()=>{mounted=false;pending?.abort();pending=undefined;if(saving.value)emit('busy',false);});
defineExpose({hasUnsavedChanges:()=>dirty.value,isSaving:()=>saving.value});
</script>

<style scoped>
.entry-editor { display: grid; gap: 12px; }
.entry-editor p { margin: 0; overflow-wrap: anywhere; }
.entry-source, .entry-consequence { color: var(--el-text-color-secondary); }
.entry-roles { display: grid; gap: 8px; }
.entry-option {
  display: flex; gap: 8px; padding: 10px 12px; align-items: flex-start;
  border: 1px solid var(--el-border-color); border-radius: var(--el-border-radius-base);
}
.entry-option.is-selected { border-color: var(--el-color-primary); background: var(--el-color-primary-light-9); }
.entry-option:focus-within { outline: 2px solid var(--el-color-primary); outline-offset: 2px; }
.entry-option input { flex: none; margin: 3px 0 0; accent-color: var(--el-color-primary); }
.entry-option > span { min-width: 0; }
.entry-label, .entry-consequence { display: block; }
.entry-label { font-weight: 600; }
.entry-consequence { margin-top: 4px; font-size: var(--el-font-size-small); }
.entry-actions { display: flex; flex-wrap: wrap; gap: 8px; }
.entry-actions .el-button { margin: 0; }
</style>
