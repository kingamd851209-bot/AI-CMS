<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { http } from '@/api/http';

const props = defineProps<{
  siteId: number; disabled?: boolean; canReviewSource: (path: string) => boolean;
}>();
const emit = defineEmits<{
  publish: [];
  templates: [];
  siteService: [];
  reviewSource: [path: string, href: string];
}>();

const labels = {
  MISSING_ARTIFACT: '缺少静态文件',
  PACKAGE_FALLBACK: '仍指向页面包',
  MISSING_ANCHOR: '缺少页内锚点',
  DYNAMIC: '需动态服务核对',
  EXTERNAL: '外部链接',
  UNVERIFIED: '待人工核实',
} as const;
type LinkState = keyof typeof labels;
interface StaticLinkFinding {
  state: LinkState;
  outputPath: string;
  href: string;
  targetPath: string | null;
  sourcePath: string | null;
  fragment: string | null;
  packagePath: string | null;
  reason: string;
  reviewHref?: string | null;
}
interface StaticLinkReport {
  state: 'SCANNED' | 'NO_OUTPUT' | 'UNAVAILABLE';
  scannedPages: number;
  checkedLinks: number;
  truncated: boolean;
  items: StaticLinkFinding[];
}

const report = ref<StaticLinkReport|null>(null);
const reading = ref(false);
const error = ref('');
const filter = ref<LinkState|''>('');
const page = ref(1);
const pageSize = 15;
const findings = computed(() => report.value?.items || []);
const filtered = computed(() => findings.value.filter(item => !filter.value || item.state === filter.value));
const shown = computed(() => filtered.value.slice((page.value - 1) * pageSize, page.value * pageSize));
let request: AbortController|undefined;

function reset() {
  request?.abort();
  request = undefined;
  report.value = null;
  reading.value = false;
  error.value = '';
  filter.value = '';
  page.value = 1;
}
watch(() => props.siteId, reset, { flush: 'sync' });
watch(filter, () => { page.value = 1; });
onBeforeUnmount(reset);

async function read() {
  if (reading.value || props.disabled) return;
  reset();
  const controller = new AbortController();
  request = controller;
  reading.value = true;
  try {
    const result = await http.get<StaticLinkReport>(
      `/api/admin/v2/sites/${props.siteId}/template-seed/site-model/static-link-check`,
      { signal: controller.signal, timeout: 60000 },
    );
    if (request === controller) report.value = result;
  } catch (cause) {
    if (request === controller) error.value = cause instanceof Error ? cause.message : '读取静态产物失败，请重试。';
  } finally {
    if (request === controller) {
      request = undefined;
      reading.value = false;
    }
  }
}
</script>

<template>
  <section id="workbench-static-audit" tabindex="-1" aria-labelledby="workbench-static-audit-heading">
    <el-card shadow="never">
      <template #header><strong id="workbench-static-audit-heading">静态产物核验</strong></template>
      <p>核对当前站点静态 HTML 中的超链接和图片热点，检查解析后的目标文件及页内锚点。修正模板或路由后，可在原生静态发布重新生成，再回到这里核对。</p>
      <div class="admin-table-toolbar">
        <el-button size="small" type="primary" :loading="reading" :disabled="disabled" @click="read">
          {{ report ? '重新核验静态链接' : '核验静态链接' }}
        </el-button>
        <el-button size="small" :disabled="disabled" @click="emit('publish')">打开原生静态发布</el-button>
      </div>
      <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
      <p v-else-if="reading" role="status">正在读取静态产物…</p>
      <p v-else-if="!report" role="status">静态产物未核验。</p>
      <p v-else-if="report.state==='NO_OUTPUT'" role="status">暂无可核验的静态 HTML。生成后可返回核验。</p>
      <p v-else-if="report.state==='UNAVAILABLE'" role="status">当前无法读取静态产物。请到原生静态发布核对输出配置。</p>
      <template v-if="report?.state==='SCANNED'">
        <p role="status">本次读取 {{ report.scannedPages }} 页 HTML，检查 {{ report.checkedLinks }} 条链接，列出 {{ findings.length }} 条需查看的记录。</p>
        <el-alert v-if="report.truncated" title="本次核验达到扫描或展示上限，结果仅覆盖已读取的范围。" type="warning" :closable="false" show-icon />
        <p v-if="!findings.length">已检查的链接未发现待核对项。</p>
        <template v-else>
          <div class="admin-table-toolbar static-audit-filter">
            <el-select v-model="filter" size="small" aria-label="按核验结果筛选">
              <el-option label="全部核验结果" value="" />
              <el-option v-for="(label, state) in labels" :key="state" :label="label" :value="state" />
            </el-select>
          </div>
          <el-table :data="shown" size="small" empty-text="当前筛选没有记录">
            <el-table-column label="发出链接的页面" min-width="155">
              <template #default="{ row }">
                <div>{{ row.outputPath }}</div>
                <small v-if="row.sourcePath">原包页 {{ row.sourcePath }}</small>
              </template>
            </el-table-column>
            <el-table-column label="原 href" prop="href" min-width="145" />
            <el-table-column label="实际解析路径" min-width="155">
              <template #default="{ row }">
                <div>{{ row.targetPath ? row.targetPath + (row.fragment ? '#' + row.fragment : '') : '—' }}</div>
                <small v-if="row.packagePath">原包目标 {{ row.packagePath }}</small>
              </template>
            </el-table-column>
            <el-table-column label="核验结果" min-width="180">
              <template #default="{ row }">
                <strong>{{ labels[row.state as LinkState] }}</strong>
                <div>{{ row.reason }}</div>
              </template>
            </el-table-column>
            <el-table-column label="下一步" min-width="140">
              <template #default="{ row }">
                <template v-if="row.state !== 'EXTERNAL' && row.state !== 'DYNAMIC'">
                  <el-button v-if="row.sourcePath && row.reviewHref && canReviewSource(row.sourcePath)" link type="primary" :disabled="disabled"
                    @click="emit('reviewSource', row.sourcePath, row.reviewHref)">核对原页公共链接</el-button>
                  <el-button v-else link type="primary" :disabled="disabled" @click="emit('templates')">打开模板管理</el-button>
                </template>
                <el-button v-else-if="row.state==='DYNAMIC'" link type="primary" :disabled="disabled"
                  @click="emit('siteService')">打开站点服务</el-button>
                <span v-else>人工浏览核对</span>
              </template>
            </el-table-column>
          </el-table>
          <el-pagination v-if="filtered.length > pageSize" v-model:current-page="page" size="small"
            layout="prev, pager, next" :page-size="pageSize" :total="filtered.length" />
        </template>
      </template>
    </el-card>
  </section>
</template>

<style scoped>
.static-audit-filter {
  margin-block: 12px;
}
.static-audit-filter .el-select {
  max-width: 220px;
}
</style>
