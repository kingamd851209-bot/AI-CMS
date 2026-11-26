<template>
  <el-select :model-value="modelValue" @update:model-value="$emit('update:modelValue', $event || '')"
    :aria-label="row.name + '字段对应'" :disabled="disabled" placeholder="选择数据来源" filterable clearable :value-on-clear="''">
    <el-option-group v-if="menus.length" label="引用直属子栏目名称">
      <el-option v-for="menu in menus" :key="menu.id" :value="'MENU_NAME:' + menu.id"
        :label="menu.name + ' · 栏目名称 #' + menu.id + (menu.recommended ? '（原文匹配）' : '')" />
    </el-option-group>
    <el-option-group label="内容字段">
      <el-option v-for="field in fields" :key="field.fieldName" :value="field.fieldName" :label="field.name + ' · ' + field.fieldName" />
      <el-option value="$new" label="提出新增字段" />
    </el-option-group>
  </el-select>
</template>
<script setup lang="ts">
import type { FieldPlanBinding, FieldPlanField } from '@/api/templateFieldPlan';

defineProps<{
  modelValue?: string;
  row: FieldPlanBinding;
  menus: {id:number; name:string; recommended:boolean}[];
  fields: FieldPlanField[];
  disabled: boolean;
}>();
defineEmits<{'update:modelValue':[value:string]}>();
</script>
