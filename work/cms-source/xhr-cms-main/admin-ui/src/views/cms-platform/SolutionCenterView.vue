<script setup lang="ts">
import CenterView from './CenterView.vue';
import { solutionCategoryApi, solutionCenterApi, solutionPushConfigApi } from '@/api/cmsPlatform';
</script>

<template>
  <CenterView
    :category-api="solutionCategoryApi"
    :center-api="solutionCenterApi"
    :push-config-api="solutionPushConfigApi"
    module-title="解决方案"
    type="solution"
  />
</template>
