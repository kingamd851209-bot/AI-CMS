<script setup lang="ts">
import CategoryConfigView from './CategoryConfigView.vue';
import { productCategoryApi, productPushConfigApi } from '@/api/cmsPlatform';
</script>

<template>
  <CategoryConfigView
    :category-api="productCategoryApi"
    :push-config-api="productPushConfigApi"
    config-type="product"
    module-title="产品配置"
  />
</template>