<script setup lang="ts">
import { computed } from 'vue';
import { useRoute } from 'vue-router';
import CategoryConfigView from './CategoryConfigView.vue';
import { formCategoryApi, formPushConfigApi } from '@/api/cmsPlatform';

const route = useRoute();
const categoryId = computed(() => {
  const raw = route.query.menuId;
  const id = typeof raw === 'string' ? Number(raw) : 0;
  return Number.isSafeInteger(id) && id > 0 ? id : undefined;
});
</script>

<template>
  <CategoryConfigView
    :category-api="formCategoryApi"
    :push-config-api="formPushConfigApi"
    :initial-category-id="categoryId"
    config-type="form"
    module-title="表单配置"
  />
</template>
