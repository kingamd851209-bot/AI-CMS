<template>
  <el-image v-if="url" :src="url" fit="contain" loading="lazy" :alt="label"
    :preview-src-list="[url]" preview-teleported style="display:block;width:104px;height:76px">
    <template #error><span role="status">图片未能加载</span></template>
  </el-image>
</template>
<script setup lang="ts">
import { computed } from 'vue';
import { staticAssetApi } from '@/api/staticAssets';

const props=defineProps<{siteId:number; raw:string; sourcePath:string; label:string}>();
const url=computed(()=>{
  const path=props.raw.trim();
  const local=path.startsWith('ui:/') ? '/' + path.slice(4) : path;
  if(!local || /^(?:[a-z][a-z\d+.-]*:|\/\/)|[\\%]/i.test(local))return '';
  try{
    const href=new URL(local,new URL(props.sourcePath,'https://package.invalid/'));
    if(href.origin!=='https://package.invalid' || !/\.(png|jpe?g|gif|webp|bmp|avif)$/i.test(href.pathname))return '';
    return staticAssetApi.downloadUrl(decodeURIComponent(href.pathname.slice(1)), 'SITE_UIPACKAGE', props.siteId);
  }catch{return '';}
});
</script>
