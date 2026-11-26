<template>
  <el-dialog :model-value="true" :title="`${title} · ${node.name}`" width="min(900px, 96vw)" append-to-body
    :close-on-click-modal="false" :before-close="beforeClose">
    <div class="model-draft-dialog">
      <p><strong>{{ node.name }}</strong> · {{ node.sourcePath }}</p>
      <p v-if="loading" role="status">正在读取此原页及已保存的 Agent 字段建议…</p>
      <div v-if="error" role="alert">
        <el-alert :title="error" type="error" :closable="false" />
        <el-button size="small" @click="reload">重读页面建议</el-button>
      </div>
      <template v-if="preview">
        <el-alert v-if="preview.blocked" :title="preview.blocked" type="warning" :closable="false" show-icon />
        <el-button v-if="preview.blocked && !preview.landingModel" size="small" @click="emit('source',preview.sourcePath)">到原页核对 Agent 建议</el-button>
        <details v-if="preview.existingModel">
          <summary>原模型「{{ preview.existingModel.name }}」 · {{ preview.existingModel.fields.length }} 个既有字段</summary>
          <ul><li v-for="field in preview.existingModel.fields" :key="field.key">{{ field.name }} · {{ fieldTypes[field.type] || field.type }}</li></ul>
        </details>
        <template v-if="preview.landingModel">
          <el-radio-group :model-value="selection" aria-label="页面建议用途" :disabled="saving" @change="changeSelection">
            <el-radio-button value="main" :disabled="!preview.model?.fields.length || !!preview.blocked">补充列表记录字段</el-radio-button>
            <el-radio-button value="landing">建立独立落地页模型</el-radio-button>
          </el-radio-group>
          <p v-if="selection==='landing'">落地页保留横幅、栏目说明等独立内容。导航原有的列表模型继续维护各条记录。</p>
        </template>
        <template v-if="model && !selectedBlocked">
          <div class="model-draft-name">
            <label v-if="canRename">模型名称
              <el-input v-model="model.name" maxlength="100" aria-label="新模型名称" :disabled="saving" />
            </label>
            <p v-else>补充到「{{ model.name }}」。现有字段和栏目的内容保留。</p>
            <span>栏目类型 · {{ model.kind==='LIST' ? '列表' : '单页' }} · 采用 {{ keptCount }} 项</span>
          </div>
          <ul class="model-draft-fields" aria-label="页面建议字段">
            <li v-for="(field,index) in fields" :key="field.key" class="model-draft-field">
              <el-checkbox v-model="field.keep" :disabled="saving" :aria-label="`采用字段 ${index+1}`">采用</el-checkbox>
              <label>字段业务名<el-input v-model="field.name" maxlength="100" :disabled="saving || !field.keep" :aria-label="`字段业务名 ${index+1}`" /></label>
              <label>字段类型<el-select v-model="field.type" :disabled="saving || !field.keep" :aria-label="`字段类型 ${index+1}`">
                <el-option v-for="type in compatibleTypes(field)" :key="type" :value="type" :label="fieldTypes[type] || type" />
              </el-select></label>
              <details v-if="evidenceByKey.get(field.key)?.length" class="model-draft-evidence">
                <summary>原页证据</summary>
                <ul>
                  <li v-for="item in evidenceByKey.get(field.key)" :key="`${item.sourcePath}:${item.resultIndex}:${item.unitId}`">
                    {{ item.sourcePath }} · Agent 第 {{ item.resultIndex+1 }} 段 · {{ item.unitId }}
                    <ul>
                      <li v-for="id in item.sourceIds" :key="id">
                        <p v-if="sources.get(id)?.rawValue">{{ sources.get(id)!.rawValue.slice(0,500) }}</p>
                        <code>{{ sources.get(id)?.selector || id }}</code>
                      </li>
                    </ul>
                  </li>
                </ul>
              </details>
            </li>
          </ul>
          <p v-if="validation" role="status">{{ validation }}</p>
          <p>保存后可核对新结构计划，再确认模型与导航应用。</p>
        </template>
        <details v-if="preview.warnings.length">
          <summary>查看建议说明（{{ preview.warnings.length }}）</summary>
          <ul><li v-for="(warning,index) in preview.warnings" :key="index">{{ warning }}</li></ul>
        </details>
        <details v-if="node.sourcePath" @toggle="showOriginal=($event.currentTarget as HTMLDetailsElement).open">
          <summary>查看隔离原页</summary>
          <EntryDecisionSourcePreview v-if="showOriginal" :site-id="siteId" :package-version="plan.packageVersion" :source-path="node.sourcePath" :visible="true" />
        </details>
      </template>
    </div>
    <template #footer>
      <el-button :disabled="saving" @click="close">返回导航</el-button>
      <el-button v-if="model && !selectedBlocked" type="primary" :disabled="loading || !!validation" :loading="saving" @click="save">保存模型草稿</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { ElMessageBox } from 'element-plus';
import { http } from '@/api/http';
import { siteModelApi, type NavigationModelDraft, type SiteContentModel, type SiteModelField, type SiteModelPlan, type SiteNavigationNode } from '@/api/siteModel';
import EntryDecisionSourcePreview from './EntryDecisionSourcePreview.vue';

type EditableField=SiteModelField & {keep:boolean};
type EvidenceSource={id:string;selector:string;rawValue:string};
const props=defineProps<{siteId:number;plan:SiteModelPlan;navigationRevision:number;node:SiteNavigationNode}>();
const emit=defineEmits<{close:[];busy:[value:boolean];source:[path:string];saved:[plan:SiteModelPlan,modelKey:string,nodeKey:string]} >();
const fieldTypes:Record<string,string>={SingleLine:'单行文字／链接',MultiLine:'多行文字',Editor:'富文本',Image:'图片',Video:'视频',File:'文件',DateTime:'日期时间'};
const sourceTypes:Record<string,string[]>={SingleLine:['TEXT'],MultiLine:['TEXT'],DateTime:['TEXT'],Editor:['HTML_REGION'],Image:['IMAGE','BACKGROUND_IMAGE','VIDEO_POSTER'],Video:['VIDEO_SRC','VIDEO_SOURCE'],File:['LINK']};
const preview=ref<NavigationModelDraft>(), model=ref<SiteContentModel>(), fields=ref<EditableField[]>([]);
const sources=ref(new Map<string,EvidenceSource>()), error=ref('');
const selection=ref<'main'|'landing'>('main');
const loading=ref(false), saving=ref(false), showOriginal=ref(false);
let pending:AbortController|undefined, before='';
const scope=computed(()=>JSON.stringify([props.siteId,props.plan.id,props.plan.revision,props.plan.packageVersion,props.navigationRevision,props.node.key]));
const title=computed(()=>props.node.modelKey ? '从页面建议补充字段' : '从页面建议建立模型');
const keptCount=computed(()=>fields.value.filter(field=>field.keep).length);
const selectedBlocked=computed(()=>selection.value==='main' ? preview.value?.blocked : null);
const canRename=computed(()=>selection.value==='landing' || preview.value?.mode==='CREATE');
const evidenceByKey=computed(()=>new Map((selection.value==='landing' ? preview.value?.landingFields : preview.value?.fields)?.map(field=>[field.key,field.evidence])));
const optionsByKey=computed(()=>new Map((selection.value==='landing' ? preview.value?.landingFields : preview.value?.fields)?.map(field=>[field.key,field])));
function compatibleTypes(field:EditableField){
  const candidate=optionsByKey.value.get(field.key);
  if(!candidate)return [field.type];
  const kinds=candidate.evidence.flatMap(evidence=>evidence.sourceKinds || []);
  const types=Object.keys(fieldTypes).filter(type=>type===candidate.type || (kinds.length && kinds.every(kind=>sourceTypes[type].includes(kind)) &&
    (type!=='Editor' || candidate.evidence.every(row=>row.sourceIds.length===1))));
  return types.length ? types : [candidate.type];
}
const validation=computed(()=>{
  if(!model.value?.name.trim())return '请输入模型名称。';
  const kept=fields.value.filter(field=>field.keep);
  if(!kept.length)return '至少采用一项页面建议字段。';
  if(kept.some(field=>!field.name.trim() || !fieldTypes[field.type]))return '请为采用的字段填写名称并选择字段类型。';
  const names=kept.map(field=>field.name.trim().toLowerCase());
  if(new Set(names).size!==names.length)return '请区分采用字段的业务名称。';
  return '';
});
const values=()=>JSON.stringify([model.value?.name,fields.value]);
function hasUnsavedChanges(){return !!model.value && before!==values();}
const failure=(cause:unknown,fallback:string)=>cause instanceof Error ? cause.message : fallback;
function installSelection(next:'main'|'landing'){
  selection.value=next;
  const option=next==='landing' ? preview.value?.landingModel : preview.value?.model;
  model.value=option ? {...option,fields:[]} : undefined;
  fields.value=option?.fields.map(field=>({...field,keep:true})) || [];
  before=values();
}
async function changeSelection(value:unknown){
  if(saving.value || (value!=='main' && value!=='landing') || value===selection.value)return;
  if(hasUnsavedChanges()){
    try{await ElMessageBox.confirm('切换用途会放弃当前未保存的字段修正。','切换用途',{type:'warning',confirmButtonText:'放弃并切换',cancelButtonText:'继续编辑'});}
    catch{return;}
  }
  installSelection(value);
}

async function load(){
  pending?.abort();
  const controller=new AbortController(), key=scope.value;
  pending=controller;loading.value=true;error.value='';preview.value=undefined;model.value=undefined;
  fields.value=[];sources.value.clear();showOriginal.value=false;before='';
  const {siteId,plan,navigationRevision,node}=props;
  const current=()=>pending===controller && key===scope.value;
  try {
    const result=await siteModelApi.previewNavigationModel(siteId,plan,navigationRevision,node.key,controller.signal);
    if(!current())return;
    if(result.sourcePath!==node.sourcePath)throw new Error('页面来源已变化，请重新核对导航。');
    preview.value=result;
    installSelection(result.landingModel && (!result.model?.fields.length || !!result.blocked) ? 'landing' : 'main');
    if(result.fields.length || result.landingFields?.length){
      const proof=await http.get<{sourcePath:string;sources:EvidenceSource[]}>(`/api/admin/v2/sites/${siteId}/template-seed/content-units`,{
        params:{sourcePath:result.sourcePath},timeout:30000,signal:controller.signal,
      });
      if(current() && proof.sourcePath===result.sourcePath)sources.value=new Map(proof.sources.map(source=>[source.id,source]));
    }
  } catch(cause){if(current())error.value=failure(cause,'无法读取页面字段建议。');}
  finally{if(current()){loading.value=false;pending=undefined;}}
}

async function save(){
  if(saving.value || loading.value || validation.value || !model.value || !preview.value || selectedBlocked.value)return;
  const {siteId,plan,navigationRevision,node}=props;
  const fieldRevision=preview.value.fieldRevision;
  const candidate={...model.value,name:model.value.name.trim(),fields:fields.value.filter(field=>field.keep)
    .map(({key,name,type,fieldName})=>({key,name:name.trim(),type,fieldName}))};
  const controller=new AbortController(), key=scope.value;
  pending=controller;saving.value=true;error.value='';emit('busy',true);
  const current=()=>pending===controller && key===scope.value;
  let saved:SiteModelPlan|undefined;
  try {
    const result=await siteModelApi.saveNavigationModel(siteId,plan,navigationRevision,node.key,fieldRevision,candidate,controller.signal);
    if(!current())return;
    if(result.siteId!==siteId || result.packageVersion!==plan.packageVersion || result.status!=='DRAFT' ||
      !result.proposal.models.some(item=>item.key===candidate.key))throw new Error('收到的草稿范围已变化，请重新核对。');
    saved=result;before=values();
  } catch(cause){if(current())error.value=failure(cause,'无法保存模型草稿。');}
  finally{if(current()){saving.value=false;pending=undefined;emit('busy',false);}}
  if(saved && key===scope.value)emit('saved',saved,candidate.key,node.key);
}

async function allowClose(){
  if(saving.value)return false;
  if(!hasUnsavedChanges())return true;
  try {await ElMessageBox.confirm('模型草稿有未保存修正。','返回导航',{type:'warning',confirmButtonText:'放弃修改并返回',cancelButtonText:'继续编辑'});return true;}
  catch{return false;}
}
async function close(){if(await allowClose())emit('close');}
async function reload(){if(await allowClose())await load();}
async function beforeClose(done:()=>void){if(await allowClose()){done();emit('close');}}
watch(scope,load,{immediate:true});
onBeforeUnmount(()=>{pending?.abort();pending=undefined;if(saving.value)emit('busy',false);});
defineExpose({hasUnsavedChanges,isSaving:()=>saving.value});
</script>

<style scoped>
.model-draft-dialog { display:grid; gap:10px; min-width:0; }
.model-draft-dialog p,.model-draft-fields { margin:0; }
.model-draft-name,.model-draft-field label { display:grid; gap:5px; min-width:0; }
.model-draft-fields { list-style:none; padding:0; display:grid; gap:7px; }
.model-draft-field { display:grid; grid-template-columns:auto minmax(120px,1fr) minmax(120px,.65fr); gap:8px; align-items:center; padding:8px; border:1px solid var(--el-border-color); border-radius:4px; }
.model-draft-evidence { grid-column:1/-1; overflow-wrap:anywhere; }
.model-draft-evidence p { white-space:pre-wrap; }
summary { cursor:pointer; }
@media(max-width:580px){.model-draft-field{grid-template-columns:1fr;}}
</style>
