<template>
  <section v-if="videos.length || error" class="source-videos" aria-label="原页视频">
    <div v-if="videos.length" class="source-video-actions">
      <strong>原页视频</strong>
      <el-button v-for="video in videos" :key="video.path" size="small" plain
        :aria-expanded="selected?.path===video.path" @click="toggle(video)">
        {{ selected?.path===video.path ? '关闭视频' : '预览视频' }} · {{ video.path }}
      </el-button>
    </div>
    <template v-if="selected && videoUrl">
      <video :key="videoUrl" :src="videoUrl" controls playsinline preload="metadata"
        :aria-label="'原页视频 '+selected.path" @error="playbackError=true" />
      <p v-if="playbackError" role="status">视频无法播放。请关闭后重试，或核对当前浏览器支持的格式。</p>
    </template>
    <div v-if="error" class="source-video-actions">
      <p role="status">原页视频清单读取失败。</p>
      <el-button size="small" @click="load">重试视频清单</el-button>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { http } from '@/api/http';

interface SourceVideo { path: string; contentType: string; size: number }
interface Inventory { sourcePath: string; packageVersion: string; videos: SourceVideo[] }
const props=defineProps<{siteId:number;sourcePath:string;packageVersion:string}>();
const videos=ref<SourceVideo[]>([]);
const selected=ref<SourceVideo>();
const error=ref(false), playbackError=ref(false);
const scope=computed(()=>JSON.stringify([props.siteId,props.sourcePath,props.packageVersion]));
const apiPath=computed(()=>`/api/admin/v2/sites/${props.siteId}/template-seed`);
let pending:AbortController|undefined;

const videoUrl=computed(()=>{
  if(!selected.value)return '';
  const query=new URLSearchParams({sourcePath:props.sourcePath,packageVersion:props.packageVersion,path:selected.value.path});
  return `${apiPath.value}/preview-video?${query}`;
});

function toggle(video:SourceVideo){
  playbackError.value=false;
  selected.value=selected.value?.path===video.path ? undefined : video;
}

async function load(){
  pending?.abort();
  selected.value=undefined;
  videos.value=[];
  error.value=false;
  playbackError.value=false;
  const {siteId,sourcePath,packageVersion}=props;
  if(!siteId || !sourcePath || !packageVersion)return;
  const key=scope.value;
  const request=new AbortController();
  pending=request;
  const current=()=>pending===request && scope.value===key;
  try {
    const inventory=await http.get<Inventory>(`${apiPath.value}/preview-videos`,{
      params:{sourcePath,packageVersion},timeout:30000,signal:request.signal,
    });
    if(!current())return;
    if(inventory.sourcePath!==sourcePath || inventory.packageVersion!==packageVersion)throw new Error('原页范围已变化');
    videos.value=inventory.videos;
  } catch {
    if(current())error.value=true;
  } finally {
    if(current())pending=undefined;
  }
}

watch(scope,load,{immediate:true,flush:'sync'});
onBeforeUnmount(()=>{pending?.abort();pending=undefined;});
</script>

<style scoped>
.source-videos { display:grid; gap:8px; min-width:0; padding-block:8px; }
.source-video-actions { display:flex; align-items:center; flex-wrap:wrap; gap:8px; min-width:0; }
.source-video-actions :deep(.el-button) { margin:0; max-width:100%; white-space:normal; height:auto; min-height:24px; overflow-wrap:anywhere; }
.source-video-actions :deep(.el-button span) { min-width:0; display:block; }
.source-videos video { display:block; width:100%; max-height: min(50dvh, 460px); background:#111; }
.source-videos p { margin:0; color:var(--el-text-color-secondary); }
</style>
