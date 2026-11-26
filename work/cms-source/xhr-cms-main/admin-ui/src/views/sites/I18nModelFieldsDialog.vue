<script setup lang="ts">
import { computed, ref } from 'vue';
import { Check } from '@element-plus/icons-vue';
import { useFieldConfig, type FieldRow } from '@/views/cms/useFieldConfig';

const props = defineProps<{
  modelValue: boolean;
  sourceLabel?: string;
  sourceMenuId?: number;
  sourcePageType?: number;
  targetLabel?: string;
  targetMenuId?: number | null;
  targetPageType?: number;
  showTarget?: boolean;
}>();

const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void;
}>();

// 字段配置接口的 type 与栏目管理的取值一致（栏目类型 0单篇 -> 1，1列表 -> 0）
function fieldConfigType(pageType?: number): number {
  return pageType === 0 ? 1 : 0;
}

/** 输入方式展示：固定枚举 / 栏目内容 / 栏目选项 / 手动输入，支持多选时追加 · 多选 */
function inputTypeLabel(row: FieldRow): string {
  const base =
    row.inputType === 'enum'
      ? '固定枚举'
      : row.inputType === 'content'
        ? '栏目内容'
        : row.inputType === 'menu'
          ? '栏目选项'
          : '手动输入';
  const multiple =
    row.inputMultiple &&
    (row.inputType === 'enum' || row.inputType === 'content' || row.inputType === 'menu');
  return multiple ? `${base} · 多选` : base;
}

const sourceMenuId = ref(0);
const sourceType = ref(1);
const targetMenuId = ref(0);
const targetType = ref(1);

const sourceCfg = useFieldConfig(sourceMenuId, sourceType);
const targetCfg = useFieldConfig(targetMenuId, targetType);

const sourceError = ref('');
const targetError = ref('');

const typeNames: Record<string, string> = {
  SingleLine: '单行文本',
  MultiLine: '多行文本',
  Text: '单行文本',
  Textarea: '多行文本',
  Image: '图片上传',
  Photo: '图片上传',
  File: '文件上传',
  Attachment: '文件上传',
  Video: '视频上传',
  Editor: '编辑器',
  RichText: '编辑器',
  DateTime: '日期时间'
};

function fieldTypeName(fieldType?: string): string {
  if (!fieldType) return '-';
  return typeNames[fieldType] ?? fieldType;
}

interface Panel {
  key: string;
  label: string;
  menuId: number;
  pageTypeLabel: string;
  loading: boolean;
  error: string;
  rows: FieldRow[];
}

const panels = computed<Panel[]>(() => {
  const list: Panel[] = [{
    key: 'source',
    label: props.sourceLabel ?? '源栏目',
    menuId: sourceMenuId.value,
    pageTypeLabel: props.sourcePageType === 1 ? '列表' : '单页',
    loading: sourceCfg.loading.value,
    error: sourceError.value,
    rows: sourceCfg.fieldRows.value.filter(r => r.enabled)
  }];
  if (props.showTarget) {
    list.push({
      key: 'target',
      label: props.targetLabel ?? '目标栏目',
      menuId: targetMenuId.value,
      pageTypeLabel: props.targetPageType === 1 ? '列表' : '单页',
      loading: targetCfg.loading.value,
      error: targetError.value,
      rows: targetCfg.fieldRows.value.filter(r => r.enabled)
    });
  }
  return list;
});

async function onOpen() {
  sourceError.value = '';
  targetError.value = '';
  sourceMenuId.value = props.sourceMenuId ?? 0;
  sourceType.value = fieldConfigType(props.sourcePageType);
  if (sourceMenuId.value) {
    try {
      // 同步后字段配置会变化，先清缓存再读，避免展示同步前数据
      sourceCfg.clearCache();
      await sourceCfg.load();
    } catch (e) {
      sourceError.value = e instanceof Error ? e.message : '字段配置加载失败';
    }
  }
  if (props.showTarget) {
    targetMenuId.value = props.targetMenuId ?? 0;
    targetType.value = fieldConfigType(props.targetPageType);
    if (targetMenuId.value) {
      try {
        targetCfg.clearCache();
        await targetCfg.load();
      } catch (e) {
        targetError.value = e instanceof Error ? e.message : '字段配置加载失败';
      }
    }
  }
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    title="数据模型字段设置"
    width="900px"
    top="8vh"
    @update:model-value="(v: boolean) => emit('update:modelValue', v)"
    @open="onOpen"
  >
    <div v-for="panel in panels" :key="panel.key" class="model-fields__panel">
      <div class="model-fields__header">
        <b>{{ panel.label }}</b>
        <el-tag size="small" type="info">栏目ID {{ panel.menuId || '-' }}</el-tag>
        <el-tag size="small">{{ panel.pageTypeLabel }}</el-tag>
        <span class="model-fields__count">已启用字段 {{ panel.rows.length }} 个</span>
      </div>
      <el-alert v-if="panel.error" :title="panel.error" type="error" :closable="false" show-icon />
      <el-table element-loading-text="正在加载数据…" v-else :data="panel.rows" v-loading="panel.loading" border stripe size="small" max-height="360">
        <el-table-column type="index" label="#" width="50" />
        <el-table-column prop="fromName" label="字段标题" min-width="140" />
        <el-table-column label="业务名称" min-width="140" show-overflow-tooltip>
          <template #default="{ row }">{{ row.title || row.fromName || '-' }}</template>
        </el-table-column>
        <el-table-column prop="fieldName" label="字段名称" width="140" />
        <el-table-column label="字段类型" width="110">
          <template #default="{ row }">{{ fieldTypeName(row.fieldType) }}</template>
        </el-table-column>
        <el-table-column label="列表显示" width="90">
          <template #default="{ row }">
            <el-icon v-if="row.isList" color="#67c23a"><Check /></el-icon>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="可搜索" width="90">
          <template #default="{ row }">
            <el-icon v-if="row.isSearch" color="#67c23a"><Check /></el-icon>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="输入方式" width="130">
          <template #default="{ row }">
            {{ inputTypeLabel(row) }}
          </template>
        </el-table-column>
        <el-table-column prop="defaultValue" label="默认值" min-width="120" show-overflow-tooltip />
        <template #empty>
          <el-empty description="未配置启用字段" :image-size="60" />
        </template>
      </el-table>
    </div>
  </el-dialog>
</template>

<style scoped>
.model-fields__panel + .model-fields__panel {
  margin-top: 16px;
}
.model-fields__header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}
.model-fields__count {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
</style>
