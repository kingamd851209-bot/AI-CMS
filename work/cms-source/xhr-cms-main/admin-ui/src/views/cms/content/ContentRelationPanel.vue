<template>
  <section class="content-side-panel" data-testid="content-relation-panel">
    <header class="content-side-panel__header">
      <h3>关系与推荐</h3>
      <p>集中维护首页引用、栏目推荐和关联内容。</p>
    </header>

    <div class="content-side-panel__group" data-testid="relation-homepage-section">
      <div class="content-side-panel__group-head">
        <span class="content-side-panel__label">首页引用</span>
        <div class="content-side-panel__chips">
          <el-tag v-for="chip in homepageChips" :key="chip" size="small" effect="plain">{{ chip }}</el-tag>
          <span v-if="homepageChips.length === 0" class="content-side-panel__empty">暂无首页引用</span>
        </div>
      </div>
      <div class="content-side-panel__table">
        <div class="content-side-panel__row">
          <span>首页置顶</span>
          <el-switch v-model="contentForm.indexTop" inline-prompt active-text="启用" inactive-text="关闭" />
        </div>
        <div class="content-side-panel__row">
          <span>首页推荐</span>
          <el-switch v-model="contentForm.indexElite" inline-prompt active-text="启用" inactive-text="关闭" />
        </div>
      </div>
    </div>

    <div class="content-side-panel__group" data-testid="relation-menu-section">
      <div class="content-side-panel__group-head">
        <span class="content-side-panel__label">栏目关系</span>
        <div class="content-side-panel__chips">
          <el-tag v-for="chip in menuChips" :key="chip" size="small" effect="plain" type="success">{{ chip }}</el-tag>
          <span v-if="menuChips.length === 0" class="content-side-panel__empty">暂无栏目置顶或推荐</span>
        </div>
      </div>
      <div class="content-side-panel__table">
        <div class="content-side-panel__row">
          <span>栏目置顶</span>
          <el-switch v-model="contentForm.menuTop" inline-prompt active-text="启用" inactive-text="关闭" />
        </div>
        <div class="content-side-panel__row">
          <span>栏目推荐</span>
          <el-switch v-model="contentForm.menuElite" inline-prompt active-text="启用" inactive-text="关闭" />
        </div>
      </div>
    </div>

    <div class="content-side-panel__group" data-testid="relation-related-section">
      <div class="content-side-panel__group-head">
        <span class="content-side-panel__label">关联内容</span>
        <div class="content-side-panel__chips">
          <el-tag v-for="chip in relatedChips" :key="chip" size="small" effect="plain" type="warning">{{ chip }}</el-tag>
          <span v-if="relatedChips.length === 0" class="content-side-panel__empty">暂无关联内容</span>
        </div>
      </div>
      <el-form-item label="关联内容 ID">
        <el-input-number
          v-model="contentForm.xgid"
          :min="0"
          :max="999999999"
          controls-position="right"
          style="width: 100%"
          data-testid="relation-xgid-input"
        />
      </el-form-item>
      <p class="content-side-panel__hint">当前先按旧字段维护单个 `xgid`，保存后仍写回历史内容结构。</p>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed } from 'vue';

interface RelationSummary {
  homepageRefs?: string[];
  menuRefs?: string[];
  relatedContents?: string[];
}

const props = defineProps<{
  relationSummary?: RelationSummary | null;
  contentForm: {
    indexTop: boolean;
    indexElite: boolean;
    menuTop: boolean;
    menuElite: boolean;
    xgid: number | null;
  };
}>();

const homepageChips = computed(() => {
  const chips = [...(props.relationSummary?.homepageRefs ?? [])];
  if (props.contentForm.indexTop && !chips.includes('首页置顶')) {
    chips.push('首页置顶');
  }
  if (props.contentForm.indexElite && !chips.includes('首页推荐')) {
    chips.push('首页推荐');
  }
  return chips;
});

const menuChips = computed(() => {
  const chips = [...(props.relationSummary?.menuRefs ?? [])];
  if (props.contentForm.menuTop && !chips.includes('栏目置顶')) {
    chips.push('栏目置顶');
  }
  if (props.contentForm.menuElite && !chips.includes('栏目推荐')) {
    chips.push('栏目推荐');
  }
  return chips;
});

const relatedChips = computed(() => {
  const chips = [...(props.relationSummary?.relatedContents ?? [])];
  if (props.contentForm.xgid && !chips.includes(`关联内容 #${props.contentForm.xgid}`)) {
    chips.push(`关联内容 #${props.contentForm.xgid}`);
  }
  return chips;
});
</script>

<style scoped>
.content-side-panel {
  display: grid;
  gap: 12px;
  padding: var(--admin-card-padding-compact);
  border: var(--admin-card-border);
  border-radius: var(--admin-card-radius);
  background: var(--admin-bg-surface);
  box-shadow: var(--admin-shadow-sm);
}

.content-side-panel__header {
  display: grid;
  gap: 4px;
}

.content-side-panel__header h3,
.content-side-panel__header p {
  margin: 0;
}

.content-side-panel__header p,
.content-side-panel__empty,
.content-side-panel__label {
  color: var(--admin-text-muted);
}

.content-side-panel__group,
.content-side-panel__table {
  display: grid;
  gap: 10px;
}

.content-side-panel__group-head {
  display: grid;
  gap: 8px;
}

.content-side-panel__chips {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.content-side-panel__row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: center;
  gap: 12px;
  padding: 10px 12px;
  border: 1px solid var(--admin-border-subtle);
  border-radius: var(--admin-radius-md);
  background: var(--admin-bg-subtle);
}

.content-side-panel__hint {
  margin: -4px 0 0;
  color: var(--admin-text-muted);
  font-size: 12px;
}
</style>
