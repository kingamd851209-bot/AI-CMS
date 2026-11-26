<script setup lang="ts">
import CenterView from './CenterView.vue';
import { caseCategoryApi, caseCenterApi, casePushConfigApi } from '@/api/cmsPlatform';
</script>

<template>
  <CenterView
    :category-api="caseCategoryApi"
    :center-api="caseCenterApi"
    :push-config-api="casePushConfigApi"
    module-title="案例中心"
    type="case"
  />
</template>
