<script setup lang="ts">
import CategoryConfigView from './CategoryConfigView.vue';
import { newsCategoryApi, newsPushConfigApi } from '@/api/cmsPlatform';
</script>

<template>
  <CategoryConfigView
    :category-api="newsCategoryApi"
    :push-config-api="newsPushConfigApi"
    config-type="news"
    module-title="新闻配置"
  />
</template>