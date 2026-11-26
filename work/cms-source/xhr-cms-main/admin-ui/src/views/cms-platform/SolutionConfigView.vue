<script setup lang="ts">
import CategoryConfigView from './CategoryConfigView.vue';
import { solutionCategoryApi, solutionPushConfigApi } from '@/api/cmsPlatform';
</script>

<template>
  <CategoryConfigView
    :category-api="solutionCategoryApi"
    :push-config-api="solutionPushConfigApi"
    config-type="solution"
    module-title="解决方案配置"
  />
</template>
