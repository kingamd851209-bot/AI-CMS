<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { ElMessage } from 'element-plus';
import { siteExternalIntegrationsApi, type IntegrationEntry, type IntegrationSettings, type IntegrationView } from '@/api/siteExternalIntegrations';
const props = defineProps<{ siteId: number | null }>();
const empty = (): IntegrationEntry => ({ enabled: false, scriptUrl: '', parameters: {} });
const settings = ref<IntegrationSettings>({ chat: empty(), stats: empty() });
const parameters = ref({ chat: '{}', stats: '{}' });
const diagnostics = ref<string[]>([]);
const mode = ref('disabled');
const loading = ref(false);
const saving = ref(false);
const error = ref('');
let revision = 0;
const kinds = [{ key: 'chat', label: '客服' }, { key: 'stats', label: '统计' }] as const;
const modeLabel = computed(() => mode.value === 'production' ? '正式运行（已显式允许）' : mode.value === 'loopback-test' ? '仅本机替身' : '禁止外部加载');
function apply(view: IntegrationView) {
  settings.value = view.settings;
  parameters.value = { chat: JSON.stringify(view.settings.chat.parameters, null, 2), stats: JSON.stringify(view.settings.stats.parameters, null, 2) };
  diagnostics.value = view.diagnostics;
  mode.value = view.mode;
}
watch(() => props.siteId, async id => {
  const current = ++revision;
  settings.value = { chat: empty(), stats: empty() };
  parameters.value = { chat: '{}', stats: '{}' };
  diagnostics.value = []; error.value = ''; mode.value = 'disabled'; loading.value = false;
  if (!id) return;
  loading.value = true;
  try { const view = await siteExternalIntegrationsApi.get(id); if (current === revision) apply(view); }
  catch (e) { if (current === revision) error.value = e instanceof Error ? e.message : '读取配置失败'; }
  finally { if (current === revision) loading.value = false; }
}, { immediate: true });
async function save() {
  const id = props.siteId; const current = revision;
  if (!id || saving.value) return;
  saving.value = true; error.value = '';
  try {
    const payload: IntegrationSettings = { chat: empty(), stats: empty() };
    for (const { key } of kinds) {
      const value: unknown = JSON.parse(parameters.value[key]);
      if (!value || typeof value !== 'object' || Array.isArray(value) || Object.values(value).some(v => typeof v !== 'string')) throw new Error('公开参数必须是字符串键值JSON对象');
      payload[key] = { ...settings.value[key], parameters: value as Record<string, string> };
      if (JSON.stringify(payload[key]).length > 1000) throw new Error('每项序列化配置最多1000字，请缩短URL或公开参数');
    }
    const view = await siteExternalIntegrationsApi.save(id, payload);
    if (current === revision) { apply(view); ElMessage.success('配置已保存，实际接通仍需核验'); }
  } catch (e) { if (current === revision) error.value = e instanceof Error ? e.message : '保存失败'; }
  finally { saving.value = false; }
}
</script>
<template>
  <el-card class="external-integrations" shadow="never" v-loading="loading">
    <template #header><strong>本站客服和统计接入</strong> <el-tag size="small">{{ modeLabel }}</el-tag></template>
    <el-alert title="仅填写公开脚本URL和公开标识，不填写密码、后端密钥或任意JS。默认关闭；本机公开站也受环境禁用策略约束。" type="info" :closable="false" />
    <el-alert v-if="error" :title="error" type="error" :closable="false" role="alert" />
    <el-form label-position="top" :disabled="!siteId || loading || saving">
      <section v-for="kind in kinds" :key="kind.key" class="integration-row">
        <el-form-item :label="kind.label + '开关'"><el-switch v-model="settings[kind.key].enabled" :aria-label="kind.label + '开关'" /></el-form-item>
        <el-form-item :label="kind.label + '公开脚本URL'"><el-input v-model="settings[kind.key].scriptUrl" :aria-label="kind.label + '公开脚本URL'" placeholder="完整HTTP(S)地址，需为可自初始化脚本" /></el-form-item>
        <el-form-item :label="kind.label + '公开参数（JSON）'"><el-input v-model="parameters[kind.key]" type="textarea" :rows="3" :aria-label="kind.label + '公开参数JSON'" placeholder='{"data-site-id":"公开标识"}' /></el-form-item>
      </section>
      <p>每项保存后的完整JSON最多1000字（服务器按实际序列化结果复核），最多8个data-*文本参数。不支持内联初始化代码；供应商需适配时会保留为待验证状态。</p>
      <el-button type="primary" :loading="saving" :disabled="!siteId" @click="save">保存本站接入</el-button>
    </el-form>
    <ul aria-label="接入诊断"><li v-for="message in diagnostics" :key="message">{{ message }}</li></ul>
    <p>分析及管理预览不注入本功能。源模板自带旧脚本仍需清理，隔离安全依赖sandbox/CSP，关闭接入不代表任意旧脚本都被阻止。</p>
  </el-card>
</template>
<style scoped>
.external-integrations { margin-bottom: 18px; }
.integration-row { margin-top: 16px; padding-top: 8px; border-top: 1px solid var(--el-border-color-lighter); }
p, li { font-size: 13px; color: var(--el-text-color-secondary); line-height: 1.6; }
</style>
