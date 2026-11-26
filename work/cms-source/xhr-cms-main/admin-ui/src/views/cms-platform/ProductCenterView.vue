<script setup lang="ts">
import CenterView from './CenterView.vue';
import { productCategoryApi, productCenterApi, productPushConfigApi } from '@/api/cmsPlatform';
</script>

<template>
  <CenterView
    :category-api="productCategoryApi"
    :center-api="productCenterApi"
    :push-config-api="productPushConfigApi"
    module-title="产品中心"
    type="product"
  />
</template>
