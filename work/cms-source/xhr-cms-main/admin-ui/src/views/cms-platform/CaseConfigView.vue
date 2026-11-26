<script setup lang="ts">
import CategoryConfigView from './CategoryConfigView.vue';
import { caseCategoryApi, casePushConfigApi } from '@/api/cmsPlatform';
</script>

<template>
  <CategoryConfigView
    :category-api="caseCategoryApi"
    :push-config-api="casePushConfigApi"
    config-type="case"
    module-title="案例配置"
  />
</template>
