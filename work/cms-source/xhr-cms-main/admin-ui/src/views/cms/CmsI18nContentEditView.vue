<template>
  <section class="admin-page" data-testid="native-i18n-redirect">
    <el-skeleton :rows="3" animated />
  </section>
</template>

<script setup lang="ts">
import { onMounted } from 'vue';
import { useRoute, useRouter } from 'vue-router';

const route = useRoute();
const router = useRouter();

onMounted(async () => {
  const menuId = positive(route.query.menuId ?? route.query.classId);
  const recordId = positive(route.query.recordId);
  const languageId = positive(route.query.languageId);
  if (!menuId || !languageId) {
    await router.replace({ name: 'cms-content' });
    return;
  }
  const query: Record<string, string> = { languageId };
  if (recordId) query.recordId = recordId;
  else query.migrationBlocked = 'NATIVE_CONTENT_MIGRATION_MAPPING_REQUIRED';
  if (route.query.jobId) query.jobId = String(route.query.jobId);
  await router.replace({ path: `/admin/v2/cms/content/${menuId}`, query });
});

function positive(value: unknown) {
  const parsed = Number(value);
  return Number.isSafeInteger(parsed) && parsed > 0 ? String(parsed) : '';
}
</script>
