<template>
  <section class="content-side-panel" data-testid="content-seo-panel">
    <header class="content-side-panel__header">
      <h3>SEO 与预览</h3>
      <p>统一维护 SEO、静态路径和预览入口。</p>
    </header>
    <el-form-item label="SEO 标题">
      <el-input v-model="contentForm.seoTitle" data-testid="seo-title-input" />
    </el-form-item>
    <el-form-item label="SEO 关键词">
      <el-input v-model="contentForm.seoKeywords" data-testid="seo-keywords-input" />
    </el-form-item>
    <el-form-item label="SEO 描述">
      <el-input v-model="contentForm.seoDescription" type="textarea" :rows="3" data-testid="seo-description-input" />
    </el-form-item>
    <el-form-item label="静态路径">
      <el-input v-model="contentForm.detailPageUrl" :disabled="seoOnly" placeholder="/news/{id}.html" data-testid="seo-detail-path-input" />
    </el-form-item>
    <p v-if="seoOnly" class="content-side-panel__hint">静态路径仍沿用完整编辑保存，SEO 快捷保存只提交标题、关键词和描述。</p>
    <el-form-item label="预览地址">
      <div class="content-side-panel__preview">
        <el-input :model-value="previewUrl || previewUrlTemplate || ''" readonly />
        <el-button v-if="previewUrl" tag="a" :href="previewUrl" target="_blank" rel="noopener noreferrer">预览</el-button>
      </div>
    </el-form-item>
  </section>
</template>

<script setup lang="ts">
defineProps<{
  contentForm: {
    seoTitle: string;
    seoKeywords: string;
    seoDescription: string;
    detailPageUrl: string;
  };
  previewUrl?: string;
  previewUrlTemplate?: string;
  seoOnly?: boolean;
}>();
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

.content-side-panel__header p {
  color: var(--admin-text-muted);
  line-height: 1.6;
}

.content-side-panel__hint {
  margin: -4px 0 0;
  color: var(--admin-text-muted);
  font-size: 12px;
}

.content-side-panel__preview {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 8px;
}
</style>
