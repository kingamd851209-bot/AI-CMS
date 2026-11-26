<template>
  <section v-if="children.length || models.length" class="entry-evidence" aria-label="栏目与原页的关系">
    <strong>这张原页与栏目的关系</strong>
    <p v-if="children.length">「{{ node.name }}」有 {{ children.length }} 个直接子栏目。<template v-if="sharedCount">其中 {{ sharedCount }} 个也使用这张原页。</template></p>
    <p v-if="models.length">已审核的页面内容对应{{ models.slice(0, 2).join('、') }}<template v-if="models.length > 2">等 {{ models.length }} 个模型</template>。</p>
    <details v-if="children.length || models.length > 2">
      <summary>查看栏目对应</summary>
      <p v-if="children.length">直接子栏目：{{ children.map(child => child.name).join('、') }}</p>
      <p v-if="models.length > 2">内容模型：{{ models.join('、') }}</p>
    </details>
  </section>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import type { SiteModelPlan, SiteNavigation, SiteNavigationNode } from '@/api/siteModel';

const props = defineProps<{ node: SiteNavigationNode; navigation: SiteNavigation; plan: SiteModelPlan }>();
const children = computed(() => props.navigation.nodes.filter(child => child.parentKey === props.node.key));
const sharedCount = computed(() => props.node.sourcePath
  ? children.value.filter(child => child.sourcePath === props.node.sourcePath).length : 0);
const models = computed(() => {
  if (props.plan.status !== 'CONFIRMED' || !props.node.sourcePath) return [];
  const keys = new Set(props.plan.proposal.regions
    .filter(region => region.sourcePath === props.node.sourcePath)
    .map(region => region.modelKey));
  return props.plan.proposal.models.filter(model => keys.has(model.key)).map(model =>
    model.menuId ? '「' + model.name + '」（栏目 #' + model.menuId + '）' : '「' + model.name + '」');
});
</script>

<style scoped>
.entry-evidence { padding: 10px 12px; background: var(--el-fill-color-light); border-radius: 4px; font-size: 13px; line-height: 1.5; }
.entry-evidence p { margin: 4px 0; }
.entry-evidence summary { cursor: pointer; color: var(--el-text-color-regular); }
</style>
