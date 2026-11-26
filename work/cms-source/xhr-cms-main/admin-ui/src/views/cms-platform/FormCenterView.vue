<script setup lang="ts">
import CenterView from './CenterView.vue';
import { formCategoryApi, formCenterApi } from '@/api/cmsPlatform';
</script>

<template>
  <CenterView
    :category-api="formCategoryApi"
    :center-api="formCenterApi"
    module-title="表单内容"
    module-description="按当前站点隔离的表单内容，随所选表单分类过滤。"
    type="form"
  />
</template>