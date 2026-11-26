<template>
  <section class="entry-preview" aria-label="当前栏目原页">
    <div class="entry-preview-header">
      <h4>当前栏目原页</h4>
      <el-button v-if="html" size="small" :aria-pressed="actualSize" @click="actualSize=!actualSize">
        {{ actualSize ? '适应宽度' : '原始尺寸' }}
      </el-button>
    </div>
    <p v-if="sourcePath" class="entry-preview-path">{{ sourcePath }}</p>
    <p>这是页面包的隔离静态预览。动态交互、异步片段和外部资源需按原站运行证据另行核对。</p>
    <p v-if="!sourcePath" role="status">此入口缺少原页路径。请核对它是否作为目录或导向其他栏目。</p>
    <p v-else-if="loading" role="status">正在读取当前栏目的原页。</p>
    <template v-else-if="error">
      <el-alert :title="error" type="error" :closable="false" />
      <el-button size="small" class="entry-preview-retry" @click="read">重新读取原页</el-button>
    </template>
    <SourceVideoPreview v-if="visible && html && sourcePath" :site-id="siteId" :source-path="sourcePath" :package-version="packageVersion" />
    <div v-if="html" ref="viewport" class="entry-preview-frame" tabindex="0" aria-label="原页滚动区域">
      <div :style="{width:Math.ceil(frameWidth*scale)+'px',height:frameHeight+'px'}" class="entry-preview-canvas">
        <iframe title="当前栏目原页预览" sandbox="" referrerpolicy="no-referrer" :srcdoc="html"
          :style="{width:frameWidth+'px',height:Math.ceil(frameHeight/scale)+'px',transform:'scale('+scale+')'}" />
      </div>
    </div>
    <details v-if="warnings.length">
      <summary>查看预览提示（{{ warnings.length }}）</summary>
      <ul><li v-for="(warning,index) in warnings" :key="index">{{ warning }}</li></ul>
    </details>
  </section>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { http } from '@/api/http';
import SourceVideoPreview from './SourceVideoPreview.vue';

const props=defineProps<{siteId:number;packageVersion:string;sourcePath:string|null;visible:boolean}>();
const scope=computed(()=>JSON.stringify([props.siteId,props.packageVersion,props.sourcePath,props.visible]));
const html=ref(''), warnings=ref<string[]>([]), error=ref(''), loading=ref(false);
const viewport=ref<HTMLElement>(), actualSize=ref(false);
const frameWidth=1440, frameHeight=ref(400), availableWidth=ref(0);
const scale=computed(()=>actualSize.value || !availableWidth.value ? 1 : Math.min(1,availableWidth.value/frameWidth));
let pending: AbortController|undefined;

watch(viewport,(element,_,cleanup)=>{
  if(!element)return;
  const measure=()=>{
    availableWidth.value=element.clientWidth;
    frameHeight.value=element.clientHeight;
  };
  measure();
  const observer=new ResizeObserver(measure);
  observer.observe(element);
  cleanup(()=>observer.disconnect());
});

function previewWarning(warning:string) {
  const data=warning.search(/data:[^,\s]*,/i);
  const message=data>=0 ? warning.slice(0,data)+'内嵌数据资源' : warning;
  return message.length>360 ? message.slice(0,360)+'…' : message;
}
async function read(){
  pending?.abort();
  pending=undefined;
  const {siteId,packageVersion,sourcePath,visible}=props;
  const key=scope.value;
  html.value='';
  warnings.value=[];
  error.value='';
  loading.value=false;
  if(!siteId || !packageVersion || !sourcePath || !visible)return;
  const controller=new AbortController();
  pending=controller;
  loading.value=true;
  const current=()=>pending===controller && scope.value===key;
  try{
    const preview=await http.get<{html:string;warnings:string[]}>(`/api/admin/v2/sites/${siteId}/template-seed/isolated-preview`,{
      params:{sourcePath,packageVersion},timeout:45000,signal:controller.signal,
    });
    if(!current())return;
    if(!preview.html?.trim())throw new Error('页面包未返回可显示的原页内容。');
    html.value=preview.html;
    warnings.value=preview.warnings.map(previewWarning);
  } catch(cause) {
    if(current())error.value=cause instanceof Error ? cause.message : '读取隔离原页失败。';
  } finally {
    if(current()){
      pending=undefined;
      loading.value=false;
    }
  }
}
watch(scope,()=>{actualSize.value=false;void read();},{immediate:true});
onBeforeUnmount(()=>{pending?.abort();pending=undefined;});
</script>

<style scoped>
.entry-preview { display: grid; gap: 8px; align-content: start; min-width: 0; }
.entry-preview p, .entry-preview h4 { margin: 0; overflow-wrap: anywhere; }
.entry-preview-header { display: flex; align-items: center; justify-content: space-between; gap: 8px; flex-wrap: wrap; }
.entry-preview-path, .entry-preview details { color: var(--el-text-color-secondary); }
.entry-preview details li { margin-block: 4px; overflow-wrap: anywhere; }
.entry-preview-frame { height: min(55dvh, 600px); overflow: auto; border: 1px solid var(--el-border-color); }
.entry-preview-canvas { position: relative; overflow: hidden; }
.entry-preview-canvas iframe { position: absolute; inset: 0; border: 0; transform-origin: top left; background: #fff; }
.entry-preview-retry { justify-self: start; }
@media (max-width: 800px) { .entry-preview-frame { height: 44dvh; } }
</style>
