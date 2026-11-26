<template>
  <section class="work-page ai-config">
    <header class="work-hero">
      <div>
        <p class="admin-eyebrow">AI 配置</p>
        <h1>AI 模型配置</h1>
        <p>当前普通识别、生成与翻译统一使用“系统AI配置”。下方为旧独立能力的用途记录，修改它们不会切换当前普通模型调用。</p>
      </div>
      <div class="work-actions">
        <el-button v-if="AiGenerationSettingsDialog && siteContext.isPlatformAdmin" @click="aiSettingsVisible=true">系统AI配置</el-button>
        <el-button size="small" :loading="loading" @click="loadProviders">刷新</el-button>
        <el-button size="small" type="primary" @click="openProvider()">新增配置</el-button>
      </div>
    </header>

    <section class="work-card">
      <div class="work-card__header">
        <div>
          <h2>旧独立能力用途（不控制当前普通模型）</h2>
          <p>每个用途会被 AI 工作台按 usageType 精确读取。</p>
        </div>
        <span>{{ providers.length }} 项配置</span>
      </div>

      <el-table element-loading-text="正在加载数据…" v-loading="loading" :data="providers" stripe>
        <el-table-column label="模型用途" min-width="170">
          <template #default="{ row }">
            <el-tag effect="plain">{{ usageLabel(row.usageType) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="服务商" min-width="160">
          <template #default="{ row }">
            <strong>{{ row.providerName || row.providerCode }}</strong>
            <p class="table-muted">{{ row.providerCode }}</p>
          </template>
        </el-table-column>
        <el-table-column prop="modelName" label="模型" min-width="180" />
        <el-table-column prop="baseUrl" label="Base URL" min-width="260" show-overflow-tooltip />
        <el-table-column label="密钥" width="110">
          <template #default="{ row }">
            <el-tag :type="row.hasApiKey ? 'success' : 'warning'">{{ row.apiKeyMasked }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.enabled ? 'success' : 'info'">{{ row.enabled ? '启用' : '停用' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="可靠性" min-width="150">
          <template #default="{ row }">
            <span>{{ row.timeoutMs || 30000 }}ms / 重试 {{ row.maxRetries ?? 2 }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="220" fixed="right">
          <template #default="{ row }">
            <el-button size="small" @click="openProvider(row)">编辑</el-button>
            <el-button size="small" plain @click="testProvider(row)">测试</el-button>
            <el-button size="small" type="danger" plain @click="disableProvider(row)">停用</el-button>
          </template>
        </el-table-column>
      </el-table>
    </section>

    <section class="work-card">
      <div class="work-card__header">
        <div>
          <h2>旧能力用途说明</h2>
          <p>系统会优先读取启用且密钥完整的第一条同用途配置。</p>
        </div>
      </div>
      <div class="usage-grid">
        <article v-for="usage in usageOptions" :key="usage.value" class="usage-card">
          <strong>{{ usage.label }}</strong>
          <span>{{ usage.value }}</span>
          <p>{{ usage.description }}</p>
        </article>
      </div>
    </section>

    <el-drawer v-model="drawerVisible" :title="form.id ? '编辑 AI 配置' : '新增 AI 配置'" size="560px">
      <el-form label-position="top" class="work-form" @submit.prevent>
        <div class="form-grid">
          <el-form-item label="模型用途">
            <el-select v-model="form.usageType" filterable>
              <el-option
                v-for="usage in usageOptions"
                :key="usage.value"
                :label="usage.label"
                :value="usage.value"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="服务商">
            <el-select v-model="form.providerCode" filterable allow-create @change="syncProviderDefaults">
              <el-option label="OpenAI" value="openai" />
              <el-option label="OpenAI 兼容服务" value="openai-compatible" />
              <el-option label="通义千问兼容服务" value="dashscope-compatible" />
              <el-option label="智谱兼容服务" value="zhipu-compatible" />
            </el-select>
          </el-form-item>
        </div>

        <div class="form-grid">
          <el-form-item label="显示名称">
            <el-input v-model="form.providerName" />
          </el-form-item>
          <el-form-item label="模型名称">
            <el-input v-model="form.modelName" placeholder="例如 gpt-4.1-mini" />
          </el-form-item>
        </div>

        <el-form-item label="Base URL">
          <el-input v-model="form.baseUrl" placeholder="https://api.openai.com/v1" />
        </el-form-item>

        <el-form-item label="API Key">
          <el-input v-model="form.apiKey" type="password" show-password autocomplete="new-password" :placeholder="form.id ? '留空表示沿用已保存密钥' : '请输入服务商提供的 API Key'" />
        </el-form-item>

        <div class="form-grid form-grid--three">
          <el-form-item label="超时毫秒">
            <el-input-number v-model="form.timeoutMs" :min="1000" :step="1000" controls-position="right" />
          </el-form-item>
          <el-form-item label="最大重试">
            <el-input-number v-model="form.maxRetries" :min="0" :max="5" controls-position="right" />
          </el-form-item>
          <el-form-item label="启用">
            <el-switch v-model="form.enabled" active-text="启用" inactive-text="停用" />
          </el-form-item>
        </div>

        <div class="form-grid">
          <el-form-item label="日 Token 预算">
            <el-input-number v-model="form.dailyTokenBudget" :min="0" controls-position="right" />
          </el-form-item>
          <el-form-item label="月成本预算（分）">
            <el-input-number v-model="form.monthlyCostBudgetCents" :min="0" controls-position="right" />
          </el-form-item>
        </div>

        <el-form-item label="Prompt 版本">
          <el-input v-model="form.promptVersion" placeholder="cms-ai-default-v1" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="drawerVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveProvider">保存配置</el-button>
      </template>
    </el-drawer>
  <component :is="AiGenerationSettingsDialog" v-if="aiSettingsVisible && AiGenerationSettingsDialog && siteContext.isPlatformAdmin" v-model="aiSettingsVisible" />
  </section>
</template>

<script setup lang="ts">
import { createTableLoader } from '@/utils/tableLoader';
import { defineAsyncComponent, onMounted, reactive, ref } from 'vue';
import {useSiteContextStore} from '@/stores/siteContext';
const siteContext=useSiteContextStore();
const aiSettingsVisible=ref(false);
const AiGenerationSettingsDialog=import.meta.env.VITE_DEVELOPER_WORKBENCH==='true'?defineAsyncComponent(()=>import('@/components/sites/AiGenerationSettingsDialog.vue')):null;
import { ElMessage, ElMessageBox } from 'element-plus';
import { http } from '@/api/http';

interface AiProviderConfig {
  id?: number;
  providerCode: string;
  providerName: string;
  usageType: string;
  modelName: string;
  baseUrl: string;
  apiKeyMasked?: string;
  hasApiKey?: boolean;
  enabled: boolean;
  timeoutMs?: number;
  dailyBudgetCents?: number | null;
  maxRetries?: number | null;
  dailyTokenBudget?: number | null;
  monthlyCostBudgetCents?: number | null;
  promptVersion?: string;
}

interface TestResult {
  success: boolean;
  message: string;
  durationMs?: number | null;
  statusCode?: number | null;
  outputTokens?: number | null;
  outputTokensPerSecond?: number | null;
}

const usageOptions = [
  { value: 'SITE_RECOGNITION', label: '站点结构与模板识别', description: 'AI 工作台识别站点结构、页面模板和字段候选时使用。' },
  { value: 'FIELD_CANDIDATE', label: '模板字段候选', description: '用于从模板 DOM、样本文本和旧字段池中推断字段映射。' },
  { value: 'SUMMARY', label: '摘要与诊断', description: '用于任务摘要、错误解释和绑定诊断建议。' }
];

const providers = ref<AiProviderConfig[]>([]);
const loading = ref(false);
const saving = ref(false);
const drawerVisible = ref(false);

const form = reactive<AiProviderConfig & { apiKey?: string }>({
  id: undefined,
  providerCode: 'openai-compatible',
  providerName: 'OpenAI 兼容服务',
  usageType: 'SITE_RECOGNITION',
  modelName: '',
  baseUrl: '',
  apiKey: '',
  enabled: true,
  timeoutMs: 30000,
  dailyBudgetCents: null,
  maxRetries: 2,
  dailyTokenBudget: null,
  monthlyCostBudgetCents: null,
  promptVersion: 'cms-ai-default-v1'
});

onMounted(() => {
  void loadProviders();
});

const loadProvidersLoader = createTableLoader(loading);

async function loadProviders() {
  const request = loadProvidersLoader.begin(() => {
    providers.value = [];
  });
  try {
    const loadedRows = await http.get<AiProviderConfig[]>('/api/admin/v2/ai/providers');
    if (!request.isCurrent()) return;
    providers.value = loadedRows;
  } catch (error) {
    if (!request.isCurrent()) return;
    ElMessage.error(errorMessage(error, 'AI 配置加载失败'));
  } finally {
    request.finish();
  }
}

function openProvider(row?: AiProviderConfig) {
  Object.assign(form, row ? { ...row, apiKey: '' } : {
    id: undefined,
    providerCode: 'openai-compatible',
    providerName: 'OpenAI 兼容服务',
    usageType: 'SITE_RECOGNITION',
    modelName: '',
    baseUrl: '',
    apiKey: '',
    enabled: true,
    timeoutMs: 30000,
    dailyBudgetCents: null,
    maxRetries: 2,
    dailyTokenBudget: null,
    monthlyCostBudgetCents: null,
    promptVersion: 'cms-ai-default-v1'
  });
  drawerVisible.value = true;
}

function syncProviderDefaults() {
  if (form.providerCode === 'openai') {
    form.providerName = 'OpenAI';
    form.baseUrl = 'https://api.openai.com/v1';
    return;
  }
  if (!form.providerName || form.providerName === 'OpenAI') {
    form.providerName = 'OpenAI 兼容服务';
  }
}

async function saveProvider() {
  saving.value = true;
  try {
    const payload = { ...form, apiKey: form.apiKey?.trim() || null };
    await http.post<AiProviderConfig>('/api/admin/v2/ai/providers', payload);
    ElMessage.success('AI 配置已保存');
    drawerVisible.value = false;
    form.apiKey = '';
    await loadProviders();
  } catch (error) {
    ElMessage.error(errorMessage(error, 'AI 配置保存失败'));
  } finally {
    saving.value = false;
  }
}

async function testProvider(row: AiProviderConfig) {
  if (!row.id) {
    return;
  }
  try {
    const result = await http.post<TestResult>(`/api/admin/v2/ai/providers/${row.id}/test`);
    ElMessage[result.success ? 'success' : 'warning'](testResultMessage(result));
  } catch (error) {
    ElMessage.error(errorMessage(error, 'AI 连接测试失败'));
  }
}

function testResultMessage(result: TestResult) {
  const details = [
    typeof result.durationMs === 'number' ? `${result.durationMs}ms` : null,
    typeof result.statusCode === 'number' ? `HTTP ${result.statusCode}` : null,
    typeof result.outputTokens === 'number' ? `输出 ${result.outputTokens} tokens` : null,
    typeof result.outputTokensPerSecond === 'number' ? `${formatTokensPerSecond(result.outputTokensPerSecond)} tokens/s` : null
  ].filter(Boolean);
  const message = result.message || '测试完成';
  return details.length > 0 ? `${message}（${details.join('，')}）` : message;
}

function formatTokensPerSecond(value: number) {
  return Number.isInteger(value) ? `${value}` : value.toFixed(1);
}

async function disableProvider(row: AiProviderConfig) {
  if (!row.id) {
    return;
  }
  try {
    await ElMessageBox.confirm(`确认停用“${usageLabel(row.usageType)}”的 AI 配置？`, '停用 AI 配置', { type: 'warning' });
    await http.post<void>(`/api/admin/v2/ai/providers/${row.id}/disable`);
    ElMessage.success('AI 配置已停用');
    await loadProviders();
  } catch (error) {
    if (!isConfirmCancel(error)) {
      ElMessage.error(errorMessage(error, 'AI 配置停用失败'));
    }
  }
}

function usageLabel(value?: string) {
  return usageOptions.find((usage) => usage.value === value)?.label ?? value ?? '-';
}

function errorMessage(error: unknown, fallback: string) {
  return error instanceof Error ? error.message : fallback;
}

function isConfirmCancel(error: unknown) {
  return error === 'cancel' || error === 'close';
}
</script>

<style scoped>
.work-page { display: grid; gap: var(--admin-card-gap); }
.work-hero, .work-card { border: var(--admin-card-border); border-radius: var(--admin-card-radius); background: var(--admin-bg-surface); box-shadow: var(--admin-shadow-md); }
.work-hero { display: flex; justify-content: space-between; gap: 20px; padding: var(--admin-card-padding-roomy); }
.work-hero h1, .work-hero p, .work-card__header h2, .work-card__header p, .table-muted { margin: 0; }
.work-hero p, .work-card__header p, .table-muted { color: var(--admin-text-muted); }
.work-actions, .work-card__header { display: flex; align-items: center; gap: 12px; }
.work-card { padding: var(--admin-card-padding); }
.work-card__header { justify-content: space-between; margin-bottom: 18px; }
.form-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px; }
.form-grid--three { grid-template-columns: repeat(3, minmax(0, 1fr)); }
.usage-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: var(--admin-card-gap); }
.usage-card { display: grid; gap: 8px; padding: var(--admin-card-padding-compact); border: var(--admin-card-border); border-radius: var(--admin-card-radius); background: #fafafa; }
.usage-card span { font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace; color: var(--admin-accent); }
.usage-card p { margin: 0; color: var(--admin-text-muted); line-height: 1.6; }
@media (max-width: 1200px) {
  .usage-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
@media (max-width: 760px) {
  .work-hero, .work-card__header { align-items: flex-start; flex-direction: column; }
  .form-grid, .form-grid--three, .usage-grid { grid-template-columns: 1fr; }
}
</style>
