<script setup lang="ts">
import {onMounted,reactive,ref} from 'vue';
import {ElMessageBox} from 'element-plus';
import {readAiGenerationSettings,saveAiGenerationSettings,testAiGenerationSettings,type AiGenerationSettings} from '@/api/aiGenerationSettings';
defineProps<{modelValue:boolean}>();const emit=defineEmits<{'update:modelValue':[value:boolean]}>();
const current=ref<AiGenerationSettings|null>(null);const form=reactive({enabled:false,baseUrl:'',modelName:'',apiKey:'',clearApiKey:false,timeoutMs:120000,maxRetries:0});const busy=ref(false),error=ref(''),feedback=ref('');
function message(cause:unknown){return cause instanceof Error?cause.message:'AI配置操作失败';}
function apply(value:AiGenerationSettings){current.value=value;Object.assign(form,{enabled:value.enabled,baseUrl:value.baseUrl,modelName:value.modelName,apiKey:'',clearApiKey:false,timeoutMs:value.timeoutMs,maxRetries:value.maxRetries});}
async function load(){busy.value=true;error.value='';try{apply(await readAiGenerationSettings());}catch(cause){error.value=message(cause);}finally{busy.value=false;}}
async function save(){if(busy.value)return;if(form.clearApiKey){try{await ElMessageBox.confirm('清除后新AI调用将无法使用此系统配置，不会自动回退环境密钥。','确认清除API Key',{type:'warning',confirmButtonText:'确认清除',cancelButtonText:'取消'});}catch{return;}}busy.value=true;error.value='';feedback.value='';try{apply(await saveAiGenerationSettings({...form}));feedback.value='系统配置已保存，新调用立即生效；已运行任务保持原配置。';}catch(cause){error.value=message(cause);}finally{form.apiKey='';busy.value=false;}}
async function test(){if(busy.value)return;busy.value=true;error.value='';feedback.value='';try{const result=await testAiGenerationSettings();if(result.success)feedback.value=result.message+'（'+result.durationMs+'ms）';else error.value=result.message;}catch(cause){error.value=message(cause);}finally{busy.value=false;}}
onMounted(load);
</script>
<template>
 <el-dialog :model-value="modelValue" title="系统AI配置" width="620px" :close-on-click-modal="false" :close-on-press-escape="!busy" :show-close="!busy" @update:model-value="emit('update:modelValue',$event)">
  <p>用于当前CMS普通模型调用，包括识别、生成与翻译。密钥仅在后端加密保存；不会写入前端或改写环境YAML。</p>
  <el-alert v-if="error" :title="error" type="error" :closable="false"/><el-alert v-if="feedback" :title="feedback" type="success" :closable="false"/>
  <p v-if="current">当前来源：{{current.source==='SYSTEM'?'系统维护':'初始环境配置'}} · {{current.configured?'已配置':'未启用或不完整'}}。测试只使用已保存的系统配置，编辑后请先保存。</p>
  <el-form label-position="top" @submit.prevent>
   <el-form-item label="启用"><el-switch v-model="form.enabled" :disabled="busy||!current" aria-label="启用系统AI"/></el-form-item>
   <el-form-item label="AI URL"><el-input v-model="form.baseUrl" :maxlength="512" :disabled="busy||!current" placeholder="https://gateway.example/v1 或完整chat/completions地址" aria-label="AI URL"/></el-form-item>
   <el-form-item label="模型ID"><el-input v-model="form.modelName" :disabled="busy||!current" :maxlength="128" aria-label="模型ID"/></el-form-item>
   <el-form-item label="API Key"><el-input v-model="form.apiKey" type="password" autocomplete="new-password" :disabled="busy||!current||form.clearApiKey" :maxlength="4096" placeholder="留空保留已保存密钥，不回显原密钥" aria-label="API Key"/><el-checkbox v-model="form.clearApiKey" :disabled="busy||!current" aria-label="显式清除已保存密钥">显式清除已保存密钥（保存时再次确认）</el-checkbox></el-form-item>
   <div class="limits"><el-form-item label="调用超时（毫秒）"><el-input-number v-model="form.timeoutMs" :min="1000" :max="300000" :step="1000" :disabled="busy||!current" aria-label="AI调用超时"/></el-form-item><el-form-item label="失败重试"><el-input-number v-model="form.maxRetries" :min="0" :max="2" :disabled="busy||!current" aria-label="AI失败重试"/></el-form-item></div>
  </el-form>
  <template #footer><el-button :disabled="busy" @click="emit('update:modelValue',false)">关闭</el-button><el-button :disabled="busy||!current||current.source!=='SYSTEM'" @click="test">测试已保存连接</el-button><el-button type="primary" :disabled="busy||!current||!form.baseUrl||!form.modelName" :loading="busy" @click="save">保存系统AI配置</el-button></template>
 </el-dialog>
</template>
<style scoped>p{line-height:1.65;overflow-wrap:anywhere}.limits{display:flex;gap:24px;flex-wrap:wrap}.el-alert{margin-bottom:12px}</style>
