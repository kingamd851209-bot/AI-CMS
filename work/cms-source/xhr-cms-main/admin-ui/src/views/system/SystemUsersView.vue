<template>
  <section class="work-page admin-responsive-page">
    <el-result title="用户权限已迁移到管理员管理" sub-title="账号、管理员组与权限现在统一在管理员管理和管理员组管理中维护。">
      <template #extra>
        <el-button type="primary" @click="goAdminUsers">进入管理员管理</el-button>
      </template>
    </el-result>
  </section>
</template>

<script setup lang="ts">
import { onMounted } from 'vue';
import { useRouter } from 'vue-router';

const router = useRouter();

function goAdminUsers() {
  return router.push('/admin/v2/system/admin-users');
}

onMounted(() => {
  void router.replace('/admin/v2/system/admin-users');
});
</script>