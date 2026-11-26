<script setup lang="ts">
import { computed,ref,watch,nextTick,onBeforeUnmount } from 'vue';
import { ElMessageBox } from 'element-plus';
import { inspectPublicList,applyPublicList,withdrawPublicList,type PublicListResult } from '@/api/templatePublicList';
import { reloadSiteRoutes } from '@/api/templateRecognition';
const props=defineProps<{siteId:number;queryId:string;packageVersion:string;complete:boolean;disabled:boolean;hasFormActions:boolean;menuId?:number;groupedMenuId?:number;fixedList?:boolean}>();
const emit=defineEmits<{busy:[value:boolean];changed:[]}>();
const result=ref<PublicListResult|null>(null),busy=ref(false),error=ref(''),notice=ref('');let revision=0;
const publishMenuId=computed(()=>props.groupedMenuId ?? props.menuId);
function release(){if(busy.value){busy.value=false;emit('busy',false);}}
watch(()=>[props.siteId,props.queryId,props.packageVersion,publishMenuId.value],()=>{revision++;release();result.value=null;error.value='';notice.value='';});
onBeforeUnmount(()=>{revision++;release();});
async function run(action:()=>Promise<PublicListResult>){if(busy.value||props.disabled)return;const token=revision;busy.value=true;emit('busy',true);error.value='';try{const value=await action();if(token===revision)result.value=value;}catch(cause){if(token===revision)error.value=cause instanceof Error?cause.message:String(cause);}finally{if(token===revision)release();}}
async function inspect(){const {siteId,queryId}=props;await run(()=>inspectPublicList(siteId,queryId));}
async function change(withdraw:boolean){if(busy.value||props.disabled||!withdraw&&!props.complete)return;
 const message=withdraw?'恢复原列表模板指针，保留内容、字段与详情模板。':props.groupedMenuId
   ? '将原页分组样例替换为本栏目当前已发布记录，按审核后的字段和顺序展示。'
   : props.fixedList ? '将原卡片组替换为本栏目已发布记录，使用已审核的固定路由。'
   : '将整组静态卡片替换为当前已发布内容，并接通已确认的GET筛选与分页；只改变列表模板指针。';
 const token=revision;try{await ElMessageBox.confirm(message,'确认公开列表',{type:'warning'});}catch{return;}if(token!==revision||busy.value||props.disabled)return;
 const {siteId,queryId,packageVersion}=props;await run(()=>withdraw?withdrawPublicList(siteId,queryId,packageVersion):applyPublicList(siteId,queryId));
 if(token!==revision||error.value)return;await nextTick();await reload();if(token===revision)emit('changed');}
async function reload(){if(busy.value||props.disabled)return;const token=revision,site=props.siteId;busy.value=true;emit('busy',true);try{const ok=await reloadSiteRoutes(site);if(token===revision)notice.value=ok?'路由已重载；请访问公开路径验收。':'设置已保存，但路由未重载；请检查已有站点服务状态。';}catch(cause){if(token===revision)error.value=cause instanceof Error?cause.message:String(cause);}finally{if(token===revision)release();}}
</script>
<template>
 <section class="public-list">
  <h4>公开应用列表模板</h4>
  <p>{{groupedMenuId?'应用前核对栏目路由并编译分组列表。公开列表读取当前已发布的记录。':fixedList?'应用前核对固定路由和完整卡片。公开列表读取本栏目已发布的记录。':'应用前核对栏目路由并编译完整卡片与分页。列表读取当前已发布的内容。'}}</p>
  <el-alert v-if="error" :title="error" type="error" :closable="false"/>
  <p v-if="result"><strong>{{result.applied?'列表模板已应用 · 待前台验证':'当前列表模板尚未应用此计划'}}</strong><br/>{{result.message}}<br/>公开路径：{{result.publicUrl}}</p><p v-if="notice">{{notice}}</p>
  <p v-if="result?.applied">{{groupedMenuId?'从站点服务打开真实列表，核对分组导航、顺序和各组记录。':fixedList?'从站点服务打开真实列表，核对本栏目记录和详情链接。':'从站点服务打开真实列表，核对记录和分页，再点击详情链接检查内容。'}}</p>
  <p v-if="result?.applied && hasFormActions">从公开列表点击表单动作，核对传入参数和预填值，完成验证码与提交，再在后台「表单内容」检查新记录及字段值。</p>
  <el-button :disabled="busy||disabled" @click="inspect">读取当前应用状态</el-button>
  <el-button type="primary" :disabled="busy||disabled||!complete" @click="change(false)">确认完整列表并公开应用</el-button>
  <el-button :disabled="busy||disabled||!result?.applied" @click="change(true)">明确撤回列表应用</el-button>
  <el-button :disabled="busy||disabled||!result" @click="reload">重新加载已有站点路由</el-button>
  <el-button v-if="result?.applied" link type="primary" @click="$router.push('/admin/v2/site-service')">打开站点服务</el-button>
  <el-button v-if="result?.applied && publishMenuId" link type="primary" :disabled="busy||disabled"
    @click="$router.push({path:'/admin/v2/cms/static-publish',query:{siteId:String(siteId),menuId:String(publishMenuId)}})">打开此栏目的静态发布</el-button>
 </section>
</template>
<style scoped>.public-list{margin-top:18px;padding-top:14px;border-top:1px solid var(--el-border-color)}p{overflow-wrap:anywhere;white-space:pre-wrap}.el-button{margin:6px 8px 6px 0}</style>
