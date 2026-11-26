<template>
  <section class="handoff" aria-label="站点交接">
    <div class="handoff-actions">
      <strong>站点交接</strong>
      <el-button link :loading="loading" @click="refresh(true)">更新交接摘要</el-button>
      <el-button v-if="summary?.nextAction" :disabled="disabled || loading" @click="resume">继续上次工作</el-button>
    </div>
    <p v-if="summaryError" role="alert">{{ summaryError }}</p>
    <details v-if="summary"><summary>查看建站进度.md</summary><pre>{{ summary.markdown }}</pre></details>
    <details @toggle="loadWhenOpened">
      <summary>本站 Agent 长期约定</summary>
      <p>填写已经确认的项目约定。保存后，新的整站和单页分析都会读取这些内容。</p>
      <el-input v-model="draft" type="textarea" :rows="5" :disabled="!file || saving" aria-label="本站长期约定" placeholder="例如，共用模板的产品分类如何判断内容归属" />
      <div class="handoff-actions">
        <el-button :loading="saving" :disabled="disabled || !file || draft === file.content" @click="save">保存到 AGENTS.md</el-button>
        <el-button v-if="dirty" :disabled="saving || reading" @click="discardDraft">放弃本机草稿</el-button>
        <span v-if="dirty" role="status">{{ cached ? '已暂存在本标签页，尚未保存到 CMS' : '尚未保存，请在离开前保存' }}</span>
        <el-button v-if="fileError" link @click="readFile(true)">重新读取已保存版本</el-button>
        <span role="status">{{ fileError || notice }}</span>
      </div>
      <pre v-if="changedOnServer || notice.startsWith('已保留')">{{ file?.content }}</pre>
    </details>
  </section>
</template>
<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { ElMessageBox } from 'element-plus';
import { http } from '@/api/http';

interface SiteFile { content: string; digest: string; present: boolean }
interface Action { step: number; sourcePath?: string; menuId?: number }
interface Summary { markdown: string; nextAction: Action | null; packageVersion: string }
const props = defineProps<{ siteId: number; adminId: string; disabled: boolean }>();
const emit = defineEmits<{ resume: [action: Action] }>();
const url = '/api/admin/v2/sites/' + props.siteId + '/workbench-context';
const summary = ref<Summary | null>(null), file = ref<SiteFile | null>(null), draft = ref('');
const loading = ref(false), saving = ref(false), reading = ref(false);
const summaryError = ref(''), fileError = ref(''), notice = ref('');
const key = `cms-workbench:agents:${props.adminId}:${props.siteId}`;
const dirty = computed(() => !!file.value && draft.value !== file.value.content);
const cached = ref(false);
const baseDigest = ref('');
const changedOnServer = computed(() => dirty.value && !!file.value && baseDigest.value !== file.value.digest);
let installing = false;
function storeDraft() {
  try {
    if (dirty.value && file.value) sessionStorage.setItem(key, JSON.stringify({content:draft.value, digest:baseDigest.value}));
    else sessionStorage.removeItem(key);
    cached.value = dirty.value;
  } catch { cached.value = false; }
}
watch(draft, () => { if (!installing) storeDraft(); }, {flush:'sync'});
async function discardDraft() {
  try { await ElMessageBox.confirm('放弃本标签页对本站约定的修改？','放弃草稿',{confirmButtonText:'放弃草稿',cancelButtonText:'继续编辑'}); }
  catch { return; }
  if (file.value) { baseDigest.value=file.value.digest; draft.value=file.value.content; storeDraft(); notice.value=''; }
}
defineExpose({dirty, saving, cached});
const errorText = (error: unknown) => error instanceof Error ? error.message : '调用失败，请重试';
async function refresh(exportProgress: boolean) {
  if (loading.value) return;
  loading.value = true; summaryError.value = '';
  try {
    summary.value = exportProgress
      ? await http.post<Summary>(url + '/export')
      : await http.get<Summary>(url + '/handoff');
  }
  catch (error) { summary.value = null; summaryError.value = errorText(error); }
  finally { loading.value = false; }
}
function resume() { if (summary.value?.nextAction) emit('resume', summary.value.nextAction); }
async function readFile(preserveDraft = false) {
  if (reading.value || saving.value) return;
  reading.value = true;
  try {
    const wasEdited = file.value && draft.value !== file.value.content;
    const latest = await http.get<SiteFile>(url + '/agents');
    installing = true;
    file.value = latest;
    if (!preserveDraft || !wasEdited) {
      draft.value = latest.content;
      baseDigest.value = latest.digest;
      try {
        const entry = JSON.parse(sessionStorage.getItem(key) || 'null');
        if (typeof entry?.content === 'string') {
          draft.value = entry.content;
          baseDigest.value = typeof entry.digest==='string' ? entry.digest : '';
          if (entry.digest !== latest.digest && draft.value !== latest.content) notice.value='已保留本机修改。服务端约定已更新，请与下方版本核对后再保存。';
        }
      } catch { /* 存储不可用时仍可读取服务端。 */ }
    }
    installing = false;
    storeDraft();
    fileError.value = '';
    if (wasEdited && preserveDraft) notice.value = '已保留你的草稿，请与下方已保存版本核对后再保存。';
  } catch (error) { fileError.value = errorText(error); }
  finally { reading.value = false; }
}
function loadWhenOpened(event: Event) {
  if ((event.target as HTMLDetailsElement).open && !file.value) void readFile();
}
async function save() {
  if (!file.value || saving.value || reading.value) return;
  saving.value = true; fileError.value = ''; notice.value = '';
  try {
    if (changedOnServer.value) {
      try { await ElMessageBox.confirm('服务端版本已变化。已核对下方版本，并用当前草稿保存吗？','核对新版项目约定',{confirmButtonText:'已核对，保存草稿',cancelButtonText:'返回对照'}); }
      catch { return; }
    }
    file.value = await http.post<SiteFile>(url + '/agents', {content: draft.value, expectedDigest: file.value.digest});
    baseDigest.value=file.value.digest; draft.value = file.value.content; storeDraft(); notice.value = '本站约定已保存'; void refresh(true);
  } catch (error) { fileError.value = errorText(error); }
  finally { saving.value = false; }
}
onMounted(() => refresh(false));
</script>
<style scoped>
.handoff { margin: 12px 0; } .handoff-actions { display:flex; flex-wrap:wrap; align-items:center; gap:10px; }
.handoff pre { white-space:pre-wrap; overflow-wrap:anywhere; max-height:22rem; overflow:auto; font:inherit; }
.handoff summary { cursor:pointer; } .handoff .el-textarea { margin-bottom:8px; }
</style>

