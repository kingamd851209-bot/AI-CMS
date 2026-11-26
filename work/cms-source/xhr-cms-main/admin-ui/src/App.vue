<template>
  <RouterView v-if="route.meta.layout === 'auth'" />
  <AdminShell v-else />
</template>

<script setup lang="ts">
import { useRoute } from 'vue-router';
import AdminShell from './components/layout/AdminShell.vue';

const route = useRoute();
</script>
