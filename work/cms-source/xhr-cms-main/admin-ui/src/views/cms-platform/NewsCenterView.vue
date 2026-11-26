<script setup lang="ts">
import CenterView from './CenterView.vue';
import { newsCategoryApi, newsCenterApi, newsPushConfigApi } from '@/api/cmsPlatform';
</script>

<template>
  <CenterView
    :category-api="newsCategoryApi"
    :center-api="newsCenterApi"
    :push-config-api="newsPushConfigApi"
    module-title="新闻中心"
    type="news"
  />
</template>