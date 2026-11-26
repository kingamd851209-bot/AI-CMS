<template>
  <article :class="['admin-metric-card', toneClass]" :data-tone="tone">
    <div class="admin-metric-copy">
      <span class="admin-metric-label">{{ label }}</span>
      <strong class="admin-metric-value">{{ value }}</strong>
      <span v-if="helper" class="admin-metric-helper">{{ helper }}</span>
    </div>
    <RouterLink
      v-if="actionLabel && actionTo"
      class="admin-metric-action"
      :to="actionTo"
    >
      {{ actionLabel }}
    </RouterLink>
  </article>
</template>

<script setup lang="ts">
import { computed } from 'vue';

const props = withDefaults(defineProps<{
  label: string;
  value: string | number;
  helper?: string;
  tone?: 'primary' | 'success' | 'warning' | 'danger' | 'neutral';
  actionLabel?: string;
  actionTo?: string;
}>(), {
  helper: '',
  tone: 'neutral',
  actionLabel: '',
  actionTo: ''
});

const toneClass = computed(() => props.tone === 'neutral' ? '' : `admin-metric-${props.tone}`);
</script>
