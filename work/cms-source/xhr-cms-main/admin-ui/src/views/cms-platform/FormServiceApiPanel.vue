<script setup lang="ts">
import { onMounted, ref, watch } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import {
  formServiceApi,
  formCategoryApi,
  siteCatalogApi,
  type FormServiceDocView,
  type FormServiceKeyView,
  type SiteCatalogOption
} from '@/api/cmsPlatform';

const sites = ref<SiteCatalogOption[]>([]);
const siteId = ref<number>();
const loading = ref(false);
const saving = ref(false);
const error = ref('');
const key = ref<FormServiceKeyView>();
const doc = ref<FormServiceDocView>();
const enabled = ref(false);
const remark = ref('');
let loadVersion = 0;
const publicCategoryId = ref<number>();
const publicSnippet = ref('');
const publicSnippetError = ref('');
const snippetLoading = ref(false);
const htmlEscapes: Record<string, string> = {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'};
const escapeHtml = (value: unknown) => String(value ?? '').replace(/[&<>"']/g, char => htmlEscapes[char] ?? char);
async function preparePublicForm() {
  publicSnippet.value = ''; publicSnippetError.value = '';
  const id = publicCategoryId.value; const selectedSite = siteId.value;
  if (!id || !selectedSite) return;
  snippetLoading.value = true;
  try {
    const category = await formCategoryApi.detail(id);
    if (siteId.value !== selectedSite || publicCategoryId.value !== id) return;
    if (category.siteProjectId !== selectedSite) throw new Error('表单分类不属于所选站点');
    if (!category.hasFrontChannel || Number(category.formCaptcha) !== 1 || !enabled.value) throw new Error('请先启用表单服务、分类前台展示和验证码');
    const fields = JSON.parse((Number(category.pageType) === 0 ? category.menuField : category.modelField) || '[]') as Array<Record<string, unknown>>;
    const controls: string[] = [];
    for (const field of fields.filter(field => field.isOpen === 'on' && field.isReadOnly !== 'on')) {
      const name = String(field.fieldName ?? ''); const type = String(field.fieldType ?? 'SingleLine');
      if (!/^[A-Za-z][A-Za-z0-9]*$/.test(name)) throw new Error('表单字段标识无效');
      if (!['SingleLine','MultiLine','Text','Textarea'].includes(type)) throw new Error('此示例只生成普通文本控件，请先为其他类型明确配置源控件映射：' + name);
      const control = ['MultiLine','Textarea'].includes(type) ? `<textarea data-cms-field="${name}"></textarea>` : `<input data-cms-field="${name}" type="text">`;
      controls.push(`<label>${escapeHtml(field.title || field.fromName || name)}${control}</label>`);
    }
    if (!controls.length) throw new Error('没有已开放的普通文本字段');
    publicSnippet.value = [`<form data-cms-form-category="${id}">`, ...controls,
      '<img data-cms-captcha-image alt="验证码">', '<input data-cms-captcha-code aria-label="验证码" autocomplete="off">',
      '<button type="button" data-cms-captcha-refresh>刷新验证码</button>', '<button type="submit">提交留言</button>',
      '<p data-cms-form-status role="status"></p>', '</form>', '<script src="/api/form/public/script.js" defer><' + '/script>'].join('\n');
  } catch (cause) { publicSnippetError.value = cause instanceof Error ? cause.message : '生成留言接入示例失败'; }
  finally { snippetLoading.value = false; }
}
watch(publicCategoryId, () => {publicSnippet.value = ''; publicSnippetError.value = '';});


async function load() {
  const version = ++loadVersion;
  const id = siteId.value;
  publicSnippet.value = ''; publicSnippetError.value = ''; publicCategoryId.value = undefined;
  key.value = undefined;
  doc.value = undefined;
  error.value = '';
  loading.value = id != null;
  if (id == null) return;
  try {
    const [keyResult, docResult] = await Promise.all([formServiceApi.getKey(id), formServiceApi.getDoc(id)]);
    if (version !== loadVersion) return;
    key.value = keyResult;
    doc.value = docResult;
    enabled.value = keyResult.enabled;
    remark.value = keyResult.remark || '';
  } catch (e) {
    if (version === loadVersion) error.value = e instanceof Error ? e.message : '加载表单服务配置失败';
  } finally {
    if (version === loadVersion) loading.value = false;
  }
}

async function rotate() {
  const id = siteId.value;
  if (id == null) return;
  try {
    await ElMessageBox.confirm('生成新密钥后，原密钥将立即失效，请同步更新调用方配置。', '生成表单服务密钥', {
      type: 'warning', confirmButtonText: '生成新密钥', cancelButtonText: '取消'
    });
  } catch {
    return;
  }
  if (id !== siteId.value) return;
  saving.value = true;
  try {
    await formServiceApi.rotateKey(id);
    await load();
    ElMessage.success('密钥已更新');
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '更新密钥失败');
  } finally {
    saving.value = false;
  }
}

async function save() {
  if (siteId.value == null || !key.value?.hasKey) return;
  saving.value = true;
  try {
    await formServiceApi.updateKey({ siteId: siteId.value, enabled: enabled.value, remark: remark.value });
    await load();
    ElMessage.success('配置已保存');
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存配置失败');
  } finally {
    saving.value = false;
  }
}

watch(siteId, () => void load());
onMounted(async () => {
  try {
    sites.value = await siteCatalogApi.sites();
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载站点列表失败';
  }
});
</script>

<template>
  <section class="form-service-panel">
    <el-form label-width="100px">
      <el-form-item label="站点">
        <el-select v-model="siteId" placeholder="请选择站点" filterable :disabled="saving" style="width: 320px">
          <el-option v-for="site in sites" :key="site.id" :label="site.name" :value="site.id" />
        </el-select>
        <el-button :disabled="siteId == null || saving" :loading="loading" @click="load">刷新</el-button>
      </el-form-item>
    </el-form>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-empty v-else-if="siteId == null" description="选择站点后查看认证配置与接口文档" />
    <div v-loading="loading">
      <el-form v-if="key" label-width="100px">
        <el-form-item label="服务密钥">
          <el-input :model-value="key.apiKey || ''" type="password" show-password readonly
            placeholder="尚未生成密钥" style="max-width: 520px" />
          <el-button type="primary" :loading="saving" @click="rotate">{{ key.hasKey ? '轮换密钥' : '生成密钥' }}</el-button>
        </el-form-item>
        <el-form-item label="启用认证">
          <el-switch v-model="enabled" :disabled="!key.hasKey || saving" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="remark" :disabled="!key.hasKey || saving" style="max-width: 520px" />
        </el-form-item>
        <el-form-item label="更新时间">{{ key.rotateTime || '—' }}</el-form-item>
        <el-form-item>
          <el-button type="primary" :disabled="!key.hasKey" :loading="saving" @click="save">保存配置</el-button>
        </el-form-item>
      </el-form>
      <template v-if="doc">
        <el-divider content-position="left">接口文档</el-divider>
        <p>服务地址：<code>{{ doc.baseUrl || '站点服务尚未配置地址' }}</code></p>
        <el-alert v-if="!doc.hasKey || !doc.keyEnabled" title="生成并启用密钥后，调用方才可访问表单服务接口。"
          type="info" :closable="false" />
        <el-table :data="doc.categories" stripe empty-text="暂无可用表单类别">
          <el-table-column prop="id" label="类别 ID" width="110" />
          <el-table-column prop="name" label="类别名称" min-width="180" />
          <el-table-column prop="fieldCount" label="字段数" width="100" />
          <el-table-column label="验证码" width="110">
            <template #default="{ row }">{{ row.captchaRequired ? '必填' : '不需要' }}</template>
          </el-table-column>
        </el-table>
        <el-divider content-position="left">公开留言表单（不含查询密钥）</el-divider>
        <p>选择本站分类生成接入示例。保留源页面布局，为原控件添加对应 data-cms-field；移除原表单 action、事件处理与第三方提交脚本后接入。只接管明确标记的表单，不会自动提交或发送通知。</p>
        <el-alert title="公开留言必须启用分类前台展示和验证码。请勿把上方 X-Form-Key 放入网页；该密钥还可查询留言。隔离预览不执行接入脚本。" type="warning" :closable="false" />
        <el-select v-model="publicCategoryId" placeholder="选择表单分类" :disabled="loading || saving || snippetLoading">
          <el-option v-for="category in doc.categories" :key="category.id" :label="category.name" :value="category.id" />
        </el-select>
        <el-button :disabled="!publicCategoryId || loading || saving" :loading="snippetLoading" @click="preparePublicForm">生成源控件映射示例</el-button>
        <el-alert v-if="publicSnippetError" :title="publicSnippetError" type="error" :closable="false" />
        <pre v-if="publicSnippet">{{ publicSnippet }}</pre>
        <el-collapse>
          <el-collapse-item v-for="endpoint in doc.endpoints" :key="endpoint.method + endpoint.path"
            :name="endpoint.method + endpoint.path" :title="`${endpoint.name} · ${endpoint.method} ${endpoint.path}`">
            <el-table :data="endpoint.params" size="small">
              <el-table-column prop="name" label="参数" min-width="150" />
              <el-table-column label="必填" width="80">
                <template #default="{ row }">{{ row.required ? '是' : '否' }}</template>
              </el-table-column>
              <el-table-column prop="desc" label="说明" min-width="260" />
            </el-table>
            <p>请求示例</p>
            <pre>{{ endpoint.sampleRequest }}</pre>
            <p>响应示例</p>
            <pre>{{ endpoint.sampleResponse }}</pre>
          </el-collapse-item>
        </el-collapse>
      </template>
    </div>
  </section>
</template>

<style scoped>
.form-service-panel { padding: 12px 0; }
.el-form-item .el-button { margin-left: 12px; }
.el-alert, .el-table { margin-bottom: 16px; }
pre { padding: 14px; overflow: auto; white-space: pre-wrap; overflow-wrap: anywhere; background: var(--el-fill-color-light); border-radius: 6px; }
</style>
