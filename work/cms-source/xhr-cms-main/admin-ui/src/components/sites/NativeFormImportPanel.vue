<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { ElMessageBox } from 'element-plus';
import { inspectNativeForms, importNativeForm, checkNativeFormTarget, applyNativeFormTarget, type NativeFormInspection, type NativeFormReceipt } from '@/api/nativeFormImport';
import { reloadSiteRoutes } from '@/api/templateRecognition';
import type { QueryFormActionTargetResult } from '@/api/templateQueryBinding';

const props = defineProps<{siteId:number;menuId:number;sourcePath:string;menuLabel?:string;disabled?:boolean;standalone?:boolean;canReviewNavigation?:boolean;prefillInputId?:string}>();
const emit = defineEmits<{ receipt:[value:NativeFormReceipt|null]; busy:[value:boolean]; reviewNavigation:[]; changed:[] }>();
const inspection = ref<NativeFormInspection|null>(null);
const receipt = ref<NativeFormReceipt|null>(null);
const selector = ref('');
const name = ref('');
const fieldKeys = ref<string[]>([]);
const loading = ref(false);
const error = ref('');
const target = ref<QueryFormActionTargetResult|null>(null);
const routeNotice = ref('');
let generation = 0;

const selectedForm = computed(() => inspection.value?.forms.find(form => form.selector === selector.value));
const supported = computed(() => selectedForm.value?.fields.filter(field => field.status === 'CANDIDATE') || []);
const missingRequired = computed(() => supported.value.filter(field => (field.required || field.sourceKey === props.prefillInputId)
  && !fieldKeys.value.includes(field.sourceKey)));
const unsupportedRequired = computed(() => selectedForm.value?.fields.filter(field => field.required && field.status !== 'CANDIDATE') || []);
const missingPrefillInput = computed(() => !!props.prefillInputId && !supported.value.some(field => field.sourceKey === props.prefillInputId));
const blocked = computed(() => props.disabled || loading.value);
const canImport = computed(() => !blocked.value && !!selectedForm.value && !!name.value.trim() && fieldKeys.value.length > 0
  && !missingRequired.value.length && !unsupportedRequired.value.length && !missingPrefillInput.value);
const destination = computed(() => `${props.menuLabel || '栏目'}（${props.menuId}）`);
const formCategories = { SAMPLE:'申请样品', INQUIRY:'询价', MESSAGE:'留言', FORM:'通用表单' };
const fieldTypes: Record<string,string> = {
  TEXT:'文本', TEXTAREA:'多行文本', EMAIL:'邮箱', TEL:'电话', SELECT:'下拉选择',
  RADIO:'单选', CHECKBOX:'复选', text:'文本', textarea:'多行文本', email:'邮箱', tel:'电话',
  select:'下拉选择', radio:'单选', checkbox:'复选',
};

function setReceipt(value: NativeFormReceipt|null) {
  receipt.value = value;
  target.value = null;
  routeNotice.value = '';
  emit('receipt', value);
}
watch(() => [props.siteId, props.menuId, props.sourcePath], () => {
  generation++;
  loading.value = false;
  inspection.value = null;
  selector.value = '';
  error.value = '';
  setReceipt(null);
}, {flush:'sync'});
watch(selector, () => {
  name.value = selectedForm.value?.name || '';
  fieldKeys.value = [];
  setReceipt(null);
});
watch(loading, value => emit('busy', value), {flush:'sync'});
onBeforeUnmount(() => {
  generation++;
  if (loading.value) emit('busy', false);
});

async function inspect() {
  if (blocked.value) return;
  const version = generation;
  const {siteId, menuId, sourcePath} = props;
  loading.value = true;
  error.value = '';
  try {
    const result = await inspectNativeForms(siteId, menuId, sourcePath);
    if (version !== generation) return;
    inspection.value = result;
    selector.value = result.forms.length === 1 ? result.forms[0]!.selector : '';
    name.value = selectedForm.value?.name || '';
    fieldKeys.value = [];
    setReceipt(null);
  } catch (cause) {
    if (version === generation) error.value = cause instanceof Error ? cause.message : '无法读取原页表单';
  } finally {
    if (version === generation) loading.value = false;
  }
}

async function confirmImport() {
  if (!canImport.value || !inspection.value) return;
  const version = generation;
  const {siteId, menuId, sourcePath} = props;
  const body = {
    menuId, sourcePath, sourceDigest:inspection.value.sourceDigest, formSelector:selector.value,
    name:name.value.trim(), fieldKeys:[...fieldKeys.value], confirmed:true,
  };
  loading.value = true;
  error.value = '';
  try {
    try {
      await ElMessageBox.confirm(
        `将原页 ${sourcePath} 中的表单 ${body.formSelector} 接到站点 ${siteId} 的 ${destination.value}。将在表单设置中创建「${body.name}」及 ${body.fieldKeys.length} 个选定字段，开启分类前台入口和验证码。同源同配置将复用已有分类，配置有变化时会要求重新核对。`,
        '确认原生表单配置', { type:'warning', confirmButtonText:'确认配置', cancelButtonText:'取消' },
      );
    } catch { return; }
    if (version !== generation) return;
    const result = await importNativeForm(siteId, body);
    if (version === generation) {
      setReceipt(result);
      emit('changed');
    }
  } catch (cause) {
    if (version === generation) error.value = cause instanceof Error ? cause.message : '原生表单配置失败';
  } finally {
    if (version === generation) loading.value = false;
  }
}

async function configureTarget(apply:boolean) {
  const category = receipt.value;
  const previous = target.value;
  if (blocked.value || !category || apply && (!previous || previous.applied)) return;
  const {siteId, menuId} = props;
  const version = generation;
  loading.value = true;
  error.value = '';
  routeNotice.value = '';
  try {
    if (apply) {
      try {
        const impact = previous?.previousTemplate ? `替换当前模板 ${previous.previousTemplate}` : '设置当前单页的首个模板';
        await ElMessageBox.confirm(
          `将原页 ${category.sourcePath} 的表单分类 ${category.categoryId} 接到 ${destination.value}，公开路径 ${previous?.publicUrl}。将${impact}，启用原生验证码和提交。`,
          '确认应用表单模板',{type:'warning'},
        );
      } catch { return; }
      if (version !== generation || previous !== target.value || category !== receipt.value) return;
    }
    const body = {menuId, formCategoryId:category.categoryId};
    const result = apply && previous
      ? await applyNativeFormTarget(siteId, {...body, expectedTemplate:previous.previousTemplate, confirmed:true})
      : await checkNativeFormTarget(siteId, body);
    if (version !== generation) return;
    target.value = result;
    if (apply && result.applied) {
      try {
        const reloaded = await reloadSiteRoutes(siteId);
        if (version === generation) routeNotice.value = reloaded ? '路由已重载，待前台提交验收。' : '请在站点服务重载路由，再检查前台。';
      } finally {
        if (version === generation) emit('changed');
      }
    }
  } catch (cause) {
    if (version === generation) { target.value = null; error.value = cause instanceof Error ? cause.message : '表单提交模板检查失败'; }
  } finally {
    if (version === generation) loading.value = false;
  }
}
</script>

<template>
  <section aria-label="原生表单审核">
    <h4>建立可提交的站点表单</h4>
    <p>来源 {{ sourcePath }} · 目标单页 {{ menuLabel || '栏目' }}（{{ menuId }}）。核对表单用途、联系方式和必填字段后，保存到站点内容的表单设置。</p>
    <el-alert type="info" :closable="false" show-icon :title="!inspection ? '下一步：读取原页中的输入框，确认表单用途与要维护的字段。' : !receipt ? '下一步：核对并勾选字段，确认后建立后台表单；这一步不会发布页面。' : !standalone ? '下一步：后台表单已建立，继续核对上方按钮的产品参数映射与目标页面，再检查并应用提交模板。' : !target?.applied ? '下一步：后台表单已建立，继续配置目标栏目路由并检查提交模板。' : '下一步：打开前台核对字段、验证码与提交结果，再到表单内容核对记录。'" />
    <el-button :loading="loading" :disabled="blocked" @click="inspect">读取原页表单</el-button>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <template v-if="receipt && error.includes('公共导航')">
      <p>保留当前表单配置。核对本页最新的公共链接映射后，返回这里再检查提交模板。</p>
      <el-button v-if="canReviewNavigation" :disabled="blocked" @click="emit('reviewNavigation')">核对本页公共链接</el-button>
    </template>
    <template v-if="inspection">
      <p v-for="warning in inspection.warnings" :key="warning">{{ warning }}</p>
      <p v-if="!inspection.forms.length">当前来源未找到可审核的表单。</p>
      <el-select v-else v-model="selector" :disabled="blocked" placeholder="选择要接通的表单" aria-label="原页表单">
        <el-option v-for="form in inspection.forms" :key="form.selector" :value="form.selector"
          :label="inspection.forms.length > 1 ? `${form.name} · ${form.selector}` : form.name" />
      </el-select>
      <template v-if="selectedForm">
        <p>识别类型 {{ formCategories[selectedForm.category] || formCategories.FORM }}</p>
        <p>原页容器 <code>{{ selectedForm.selector }}</code></p>
        <label>表单名称 <el-input v-model="name" :disabled="blocked || !!receipt" aria-label="原生表单名称" maxlength="100" /></label>
        <el-button :disabled="blocked || !!receipt || !supported.length" link type="primary" @click="fieldKeys=supported.map(field => field.sourceKey)">选中可导入字段</el-button>
        <el-checkbox-group v-model="fieldKeys" :disabled="blocked || !!receipt">
          <el-table :data="selectedForm.fields" row-key="sourceKey" size="small">
            <el-table-column label="采用" width="80"><template #default="{row}"><el-checkbox :value="row.sourceKey" :disabled="row.status!=='CANDIDATE'" :aria-label="`采用 ${row.label}`" /></template></el-table-column>
            <el-table-column prop="label" label="原页字段" min-width="100" />
            <el-table-column label="字段类型" min-width="100"><template #default="{row}">{{ fieldTypes[row.kind] || row.kind }}</template></el-table-column>
            <el-table-column label="原页要求" min-width="110"><template #default="{row}">{{ row.required ? '必填' : '选填' }}<template v-if="row.maxLength"> · {{ row.maxLength }} 字以内</template></template></el-table-column>
            <el-table-column label="选项" min-width="110"><template #default="{row}">{{ row.options?.join('、') || '—' }}</template></el-table-column>
            <el-table-column label="字段证据" min-width="140"><template #default="{row}">{{ row.reason || row.selector }}</template></el-table-column>
          </el-table>
        </el-checkbox-group>
        <el-alert v-if="unsupportedRequired.length" type="warning" :closable="false" :title="`暂无法导入的必填项：${unsupportedRequired.map(field=>field.label).join('、')}`" />
        <el-alert v-if="missingRequired.length" type="warning" :closable="false" :title="`请选择必填或预填字段：${missingRequired.map(field=>field.label).join('、')}`" />
        <el-alert v-if="missingPrefillInput" type="warning" :closable="false" :title="`请选择包含预填输入框 ${prefillInputId} 的表单区域。`" />
        <el-button v-if="!receipt" type="primary" :disabled="!canImport" @click="confirmImport">确认创建或复用原生表单</el-button>
      </template>
    </template>
    <template v-if="receipt">
      <p>后台表单已建立 · 分类 {{ receipt.categoryId }} · {{ receipt.fields.length }} 个字段。以下是已经保存的字段对应关系，前台模板和实际提交需分别验证。</p>
      <el-table :data="receipt.fields" size="small">
        <el-table-column prop="label" label="业务字段" />
        <el-table-column prop="fieldName" label="CMS 字段槽位" />
        <el-table-column label="输入内容"><template #default="{row}">{{ fieldTypes[row.kind] || row.kind }}{{ row.required ? ' · 必填' : ' · 选填' }}</template></el-table-column>
      </el-table>
      <el-button link type="primary" @click="$router.push({path:'/admin/v2/cms/form-config',query:{menuId:String(receipt.categoryId)}})">打开表单设置</el-button>
      <el-button link type="primary" @click="$router.push({path:'/admin/v2/cms/form-center',query:{menuId:String(receipt.categoryId)}})">查看表单内容</el-button>
      <template v-if="standalone">
        <p>在工作台的模板与发布步骤配置此单页的前台路由；表单设置用于维护字段、验证码及表单服务。</p>
        <el-button :disabled="blocked" @click="configureTarget(false)">检查原生提交模板</el-button>
        <el-button v-if="target && !target.applied" type="primary" :disabled="blocked" @click="configureTarget(true)">确认应用提交模板</el-button>
        <template v-if="target">
          <p>{{ target.applied && target.submissionReady ? '已应用原生提交模板 · 待前台验收' : target.message }} · {{ target.publicUrl }}</p>
          <p v-if="!target.applied">当前模板 {{ target.previousTemplate || '未配置' }} · 待应用模板 {{ target.templatePath }}</p>
        </template>
        <p v-if="routeNotice">{{ routeNotice }}</p>
        <p v-if="target?.applied && target.submissionReady">打开前台核对表单字段与验证码，提交后在「表单内容」检查新记录及其字段值。</p>
        <el-button v-if="target?.applied" link type="primary" @click="$router.push('/admin/v2/site-service')">打开站点服务验收提交</el-button>
      </template>
    </template>
  </section>
</template>

<style scoped>
.el-select { max-width: 420px; margin: 8px 0; }
label { display: block; max-width: 420px; }
.el-table { margin: 8px 0; }
.el-alert { margin-top: 8px; }
</style>
