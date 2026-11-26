<template>
  <el-button type="primary" plain :disabled="disabled || !ready" @click="open()">预览并审核整页</el-button>
  <el-dialog v-model="visible" :title="title" fullscreen append-to-body class="binding-workspace"
    destroy-on-close :before-close="close" :close-on-click-modal="false" @closed="onClosed">
    <div class="binding-shell">
      <header class="binding-heading">
        <div>
          <strong>{{ sourcePath }}</strong>
          <p>来源修订 {{ revision }} · 静态预览不运行页面脚本</p>
        </div>
        <div class="binding-actions" aria-live="polite">
          <el-tag effect="plain">{{ reviewUnits.length }} 个单元</el-tag>
          <el-tag v-if="preview" type="warning" effect="plain">{{ pendingCount }} 个待处理</el-tag>
          <el-tag type="info" effect="plain">{{ ignoredCount }} 个暂不绑定</el-tag>
        </div>
      </header>
      <el-alert v-if="error" :title="error" type="warning" :closable="false" show-icon />
      <p v-else-if="loading" role="status">正在核对页面包和来源修订，读取隔离预览…</p>
      <template v-else-if="preview">
        <div class="compact-panes" aria-label="审核视图">
          <button type="button" :aria-pressed="compactPane === 'fields'" @click="compactPane='fields'">审核字段</button>
          <button type="button" :aria-pressed="compactPane === 'source'" @click="compactPane='source'">对照原页</button>
        </div>
        <div class="binding-grid" :data-pane="compactPane">
          <section class="binding-page" aria-label="原页面隔离预览">
            <div class="binding-toolbar">
              <span>桌面预览 · 选择右侧条目定位原文</span>
              <div class="binding-actions">
                <el-select v-model="viewportWidth" aria-label="桌面预览宽度" size="small" style="width: 110px">
                  <el-option :value="1440" label="1440 px" />
                  <el-option :value="1920" label="1920 px" />
                </el-select>
                <el-button size="small" :aria-pressed="actualSize" @click="actualSize = !actualSize">{{ actualSize ? '适应窗口' : `原尺寸（${Math.round(scale * 100)}%）` }}</el-button>
              </div>
            </div>
            <SourceVideoPreview v-if="visible" :site-id="siteId" :source-path="sourcePath" :package-version="preview.packageVersion" />
            <div ref="previewArea" class="binding-viewport" :class="{'actual-size':actualSize}">
              <div class="binding-frame" :style="{ width: Math.floor(viewportWidth * scale) + 'px', height: Math.floor(viewportHeight * scale) + 'px' }">
                <iframe sandbox="" referrerpolicy="no-referrer" :src="frameUrl"
                  :style="{ width: viewportWidth + 'px', height: viewportHeight + 'px', transform: `scale(${scale})` }"
                  title="静态隔离页面及来源位置" />
              </div>
            </div>
            <details v-if="preview.warnings.length" class="binding-limitations"><summary>预览限制（{{ preview.warnings.length }}）</summary><p v-for="(warning, index) in preview.warnings" :key="index">{{ warning }}</p></details>
          </section>
          <aside class="binding-review" aria-label="整页内容单元">
            <PageBodyCoverage v-if="addCoverageSource && addCoverageSources && restoreCoverageSources" :site-id="siteId" :source-path="sourcePath" :package-version="preview.packageVersion"
              :digest="preview.digest" :revision="preview.revision" :disabled="disabled || saving" :units="reviewUnits"
              :add-source="addCoverageSource" :add-sources="addCoverageSources" :restore-units="restoreCoverageUnits"
              :can-locate="canLocateCoverage" @locate="coverageSourceId=$event" @review-unit="reviewCoverageUnit" />
            <div class="binding-toolbar">
              <el-checkbox v-model="includeIgnored">显示暂不绑定（{{ ignoredCount }}）</el-checkbox>
              <el-checkbox v-model="pendingOnly">只看待处理</el-checkbox>
              <div class="binding-actions">
                <el-button size="small" :disabled="!previousUnit" @click="selectedId = previousUnit!.id">上一项</el-button>
                <el-button size="small" :disabled="!nextUnit" @click="selectedId = nextUnit!.id">下一项</el-button>
              </div>
            </div>
            <ol ref="unitList" class="binding-list" aria-label="内容单元清单">
              <li v-for="unit in filteredUnits" :key="unit.id">
                <button type="button" :aria-current="unit.id === selectedId ? 'true' : undefined" @click="selectedId = unit.id">
                  <span>{{ unitLabel(unit) }}</span>
                  <small>{{ unit.ignored ? '暂不绑定' : needsReview(unit) ? '待处理' : typeLabels[unit.fieldType] || unit.fieldType }}</small>
                </button>
              </li>
              <li v-if="!filteredUnits.length" class="binding-empty">没有待处理的单元</li>
            </ol>
            <section v-if="selected" :key="selected.id" class="binding-detail" aria-label="所选内容单元">
              <h3>{{ unitLabel(selected) }}</h3>
              <p v-if="bodyContent" class="binding-hint">正文建议值 · 来源 {{ sourcePath }} · {{ bodyContent.paragraphs }} 段、{{ bodyContent.images }} 张图片、{{ bodyContent.links }} 处链接。请在原页核对资源与顺序。</p>
              <slot name="selected-actions" :unit="selected" />
              <p v-if="needsReview(selected)" class="binding-guide">
                <template v-if="!evidence.length || evidence.some(row => !row.valid)">这项缺少可核对的原文。请修正来源，或暂不绑定。</template>
                <template v-else-if="!typeLabels[selected.fieldType]">请按页面原文选择字段类型。无法判断时，可暂不绑定并保留来源。</template>
                <template v-else-if="!selected.name.trim()">请填写维护人员能识别的业务名称，例如「首页主标题」。</template>
                <template v-else-if="unlocatedCount">有 {{ unlocatedCount }} 处来源无法在静态页面中核对位置。请查看定位细节并对照原文，无法确认时可暂不绑定。</template>
                <template v-else>这项字段映射还需处理。请核对页面原文和字段用途，再决定复用、新增或暂不绑定。</template>
              </p>
              <p v-else-if="selected.doubts && !selected.ignored" class="binding-hint">此项有原建议说明。对照原文检查名称、类型和用途；仍无法确定时可暂不绑定。</p>
              <div v-if="editUnits" class="binding-form">
                <label>业务名称<el-input :model-value="selected.name" :disabled="disabled" aria-label="业务名称" maxlength="100" @update:model-value="edit({ name: $event })" /></label>
                <label>字段类型<el-select :model-value="selected.fieldType" :disabled="disabled" aria-label="字段类型" @update:model-value="edit({ fieldType: $event })">
                  <el-option v-for="(label, value) in typeLabels" :key="value" :label="label" :value="value" />
                </el-select></label>
                <p v-if="selected.name.trim() === '待命名内容'" class="binding-hint">这是静态原文候选，业务含义和字段类型尚未确认，请先进行 AI 分析或人工核对。</p>
                <p class="binding-hint">短标题选单行文本，普通长段落选多行文本；需要保留正文排版或图文时选富文本。</p>
                <el-checkbox :model-value="selected.ignored" :disabled="disabled" @update:model-value="edit({ ignored: $event === true })">暂不绑定此单元</el-checkbox>
              </div>
              <slot name="mapping" :unit="selected">
                <p v-if="mapping"><strong>字段映射：</strong>{{ [actionLabels[mapping.action] || '待人工处理', mapping.fieldName].filter(Boolean).join(' · ') }}</p>
              </slot>
              <p v-if="mappingNotice" class="binding-hint">{{ mappingNotice }}</p>
              <div class="binding-value">
                <strong>页面原文</strong>
                <pre>{{ initialValue.trim() || '此处没有可见文字，请核对下方来源。' }}</pre>
                <p v-if="evidence.some(row => row.publicRegion)">此项含公共头尾来源，已有人工引用保留；请在整站结构阶段处理导航、版权、二维码及公共模板，不作为本页主体字段新增。</p>
                <p v-else-if="evidence.some(row => row.publicNavigation)">此单元含公共导航来源，请在「栏目与导航」中审核对应链接。</p>
                <p v-if="evidence.some(row => row.interactionPending)">此项含交互控件或占位动作地址，应核对页面功能，不把动作地址作为单行文本内容。</p>
                <p v-if="!evidence.length">没有来源。请返回来源修正，补齐后再确认。</p>
                <p v-else-if="evidence.some(row => !row.valid)">有来源区间无法读取，请返回来源修正。</p>
                <el-button v-if="editUnits && returnToSources" size="small" :disabled="disabled" @click="showSources">返回来源修正</el-button>
              </div>
              <div v-if="selected.ignored || rendered.rows.some(row=>!row.located)" class="binding-value">
                <strong>来源位置</strong>
                <div v-for="row in rendered.rows" :key="row.id"><p>{{ row.message }}</p><code>{{ row.selector || row.id }}</code><details v-if="row.snippet"><summary>查看隔离页面 HTML 片段</summary><pre>{{ row.snippet }}</pre></details><pre>{{ row.value.trim() || '空白或不可见字符' }}</pre></div>
                <p v-if="!rendered.rows.length">此项缺少可追溯的来源，请回到内容单元重新选择原文。</p>
              </div>
              <div v-if="locations.length>1" class="binding-actions"><el-button v-for="(id,i) in locations" :key="id" size="small" @click="locationIndex=i">位置 {{ i+1 }}</el-button></div>
              <details>
                <summary>{{ selected.doubts ? '查看原建议中的疑点和定位细节' : '定位细节与人工说明' }}</summary>
                <el-input v-if="editUnits" :model-value="selected.doubts" :disabled="disabled" aria-label="疑点说明" type="textarea" :rows="2" maxlength="2000" @update:model-value="edit({ doubts: $event })" />
                <p v-else>{{ selected.doubts || '没有待核对说明' }}</p>
                <p v-if="mapping?.reason">{{ mapping.reason }}</p>
                <ol class="binding-evidence">
                  <li v-for="(row, index) in rendered.rows" :key="index">
                    <strong>来源 {{ index + 1 }} · {{ row.message }}</strong>
                    <span>{{ row.id }} [{{ row.start }}, {{ row.end }})</span>
                    <code v-if="row.selector">{{ row.selector }}</code>
                    <pre>{{ row.value }}</pre>
                  </li>
                </ol>
              </details>
            </section>
          </aside>
        </div>
      </template>
      <el-alert v-if="actionError" :title="actionError" type="error" :closable="false" show-icon />
      <footer class="binding-footer">
        <div role="status">{{ feedback || (editCount ? `${editCount} 个单元有弹窗内修正，尚未应用到本地草稿。` : footerNotice) }}</div>
        <div class="binding-actions">
          <template v-if="preview && !error">
            <template v-if="editUnits">
              <el-button :disabled="disabled || !editCount" @click="applyEdits">应用整页修正</el-button>
              <el-button v-if="saveUnits" type="primary" :loading="saving" :disabled="disabled || !(editCount || draftDirty)" @click="save">保存整页确认</el-button>
            </template>
            <slot name="actions" :close="requestClose" :dirty="editCount > 0" />
          </template>
          <el-button :disabled="saving" @click="requestClose">关闭审核</el-button>
        </div>
      </footer>
    </div>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue';
import { ElMessageBox } from 'element-plus';
import { http } from '@/api/http';
import type { DomainUnit } from '@/api/domainAgent';
import PageBodyCoverage from './PageBodyCoverage.vue';
import SourceVideoPreview from './SourceVideoPreview.vue';

interface Source { id: string; kind: string; rawValue: string; selector: string; publicNavigation?: boolean | null; publicRegion?: 'HEADER'|'FOOTER'|null; interactionPending?: boolean|null }
interface Mapping { unitId: string; fieldName: string | null; action: string; reason: string }
interface Preview { html: string; warnings: string[]; digest: string; revision: number; packageVersion: string }
const props = defineProps<{
  siteId: number; sourcePath: string; packageVersion?: string | null; digest?: string; revision?: number;
  title: string; units: DomainUnit[]; sources?: Source[]; mappings?: Mapping[]; mappingNotice?: string; disabled?: boolean;
  editUnits?: (units: DomainUnit[]) => boolean; draftDirty?: boolean;
  saveUnits?: () => Promise<string | null>; returnToSources?: (unitId: string) => void;
  addCoverageSource?: (sourceId: string, ranges: {start:number;end:number}[], ignored: boolean) => string | null;
  addCoverageSources?: (sources: {sourceId:string;ranges:{start:number;end:number}[]}[]) => string[];
  restoreCoverageSources?: (unitIds:string[]) => string[];
  footerNotice?: string; actionError?: string; nextStepNotice?: string;
}>();
const visible = ref(false), loading = ref(false), error = ref(''), selectedId = ref('');
const coverageSourceId=ref('');
const saving = ref(false), feedback = ref(''), pendingOnly = ref(false);
const edits = ref(new Map<string, DomainUnit>());
const previewArea = ref<HTMLElement>();
const unitList = ref<HTMLOListElement>();
watch([selectedId, unitList], () => {
  const list = unitList.value, button = list?.querySelector<HTMLButtonElement>('[aria-current]');
  if (!list || !button) return;
  if (button.offsetTop < list.scrollTop) list.scrollTop = button.offsetTop;
  else if (button.offsetTop + button.offsetHeight > list.scrollTop + list.clientHeight) {
    list.scrollTop = button.offsetTop + button.offsetHeight - list.clientHeight;
  }
}, { flush: 'post' });
const viewportWidth = ref(1440), actualSize = ref(false), frameWidth = ref(0), frameHeight = ref(0);
const scale = computed(() => actualSize.value || !frameWidth.value ? 1 : Math.min(1, frameWidth.value / viewportWidth.value));
const viewportHeight = computed(() => Math.max(240, Math.floor(frameHeight.value / scale.value)));
watch(previewArea, (area, _, onCleanup) => {
  if (!area) return;
  const resize = () => { frameWidth.value = area.clientWidth; frameHeight.value = area.clientHeight; };
  const observer = new ResizeObserver(resize);
  resize();
  observer.observe(area);
  onCleanup(() => observer.disconnect());
});
const preview = ref<Preview>();
const previewDocument = computed(() => preview.value?.html
  ? new DOMParser().parseFromString(preview.value.html, 'text/html') : undefined);
// 隔离文档不能执行脚本或读取 iframe DOM。从包内样式保守识别需要交互才显示的区域。
const styleHidden = computed(() => {
  const hidden = new Set<Element>();
  const doc = previewDocument.value;
  if (!doc) return hidden;
  const selectors = new Set<string>();
  function collect(rules:CSSRuleList) {
    for (const rule of Array.from(rules)) {
      if (rule instanceof CSSMediaRule && !window.matchMedia(rule.conditionText).matches) continue;
      if (rule instanceof CSSStyleRule) {
        const style=rule.style;
        if (style.display==='none' || ['hidden','collapse'].includes(style.visibility) ||
            style.contentVisibility==='hidden' || style.opacity==='0') selectors.add(rule.selectorText);
      } else if ('cssRules' in rule) collect((rule as CSSGroupingRule).cssRules);
    }
  }
  for(const style of doc.querySelectorAll('style')){
    try { const sheet=new CSSStyleSheet(); sheet.replaceSync(style.textContent || ''); collect(sheet.cssRules); }
    catch { /* 外部或不可解析的样式继续按静态 DOM 判断。 */ }
  }
  for(const selector of selectors){
    try { for(const el of doc.querySelectorAll(selector)) hidden.add(el); } catch { /* 伪元素等没有 DOM 目标。 */ }
  }
  return hidden;
});
const sourceStatuses = computed(() => previewDocument.value && new Map(
  [...indexPositions(previewDocument.value)].map(([id, matches]) => {
    const state=positionStatus(matches);
    if(state==='located') for(let el:Element|null=matches[0]!; el; el=el.parentElement)
      if(styleHidden.value.has(el)) return [id,'hidden'] as const;
    return [id,state] as const;
  }),
));
let pending: AbortController | undefined;
let returnSourceId = '';
const ready = computed(() => !!(props.siteId && props.sourcePath && props.packageVersion && props.digest
  && props.revision !== undefined && props.sources && (props.units.length || (props.addCoverageSource && props.sources.length))));
const original = computed(() => props.units.find(unit => unit.id === selectedId.value));
const reviewUnits = computed(() => props.units.map(unit => edits.value.get(unit.id) ?? unit));
const selected = computed(() => reviewUnits.value.find(unit => unit.id === selectedId.value));
const mappingsById = computed(() => new Map(props.mappings?.map(item => [item.unitId, item])));
const mapping = computed(() => mappingsById.value.get(selectedId.value));
const sourceById = computed(() => new Map(props.sources?.map(source => [source.id, source])));
const typeLabels: Record<string, string> = { SingleLine: '单行文本', MultiLine: '多行文本', Editor: '富文本', Image: '图片', Video: '视频', File: '文件', DateTime: '日期时间' };
const actionLabels: Record<string, string> = { MENU_NAME: '引用栏目名称', REUSE: '建议复用', CREATE: '建议新增', PENDING: '待人工处理', IGNORED: '已忽略', PENDING_TYPE: '类型待确认', CONFLICT: '映射有冲突' };
const editCount = computed(() => edits.value.size);
function needsReview(unit: DomainUnit) {
  return !unit.ignored && (!unit.name.trim() || unit.name.trim() === '待命名内容' || !typeLabels[unit.fieldType]
    || !unit.sources.length || unit.sources.some(({ sourceId, start, end }) => {
      const source = sourceById.value.get(sourceId);
      return !source || !Number.isInteger(start) || !Number.isInteger(end) || start < 0 || end <= start || end > source.rawValue.length
        || (!!sourceStatuses.value && sourceStatuses.value.get(sourceId) !== 'located');
    }) || ['PENDING', 'PENDING_TYPE', 'CONFLICT'].includes(mappingsById.value.get(unit.id)?.action || ''));
}
const pendingCount = computed(() => reviewUnits.value.filter(needsReview).length);
const ignoredCount = computed(() => reviewUnits.value.filter(unit => unit.ignored).length);
const includeIgnored = ref(false);
function unitLabel(unit:DomainUnit) {
  if (unit.name?.trim() && unit.name.trim()!=='待命名内容') return unit.name;
  const ref=unit.sources[0], source=ref && sourceById.value.get(ref.sourceId);
  if (!source) return '来源待补充';
  const text=source.rawValue.slice(ref.start,ref.end).trim().replace(/\s+/g,' ');
  return text.slice(0,45) || ('空白文本 · '+source.selector.split('>').pop()?.trim());
}
const filteredUnits = computed(() => reviewUnits.value.filter(unit=>(includeIgnored.value || !unit.ignored) && (!pendingOnly.value || needsReview(unit))));
watch(filteredUnits, units=>{if(!units.some(unit=>unit.id===selectedId.value)) selectedId.value=units[0]?.id || '';});

function adjacentUnit(offset: number) {
  const order = reviewUnits.value.findIndex(unit => unit.id === selectedId.value);
  return (offset > 0 ? filteredUnits.value : [...filteredUnits.value].reverse()).find(unit =>
    offset * (reviewUnits.value.findIndex(candidate => candidate.id === unit.id) - order) > 0);
}
const previousUnit = computed(() => adjacentUnit(-1));
const nextUnit = computed(() => adjacentUnit(1));
watch(pendingOnly, value => {
  if (value && selected.value && !needsReview(selected.value) && filteredUnits.value.length) selectedId.value = filteredUnits.value[0].id;
});

const evidence = computed(() => (original.value?.sources ?? []).map(reference => {
  const source = sourceById.value.get(reference.sourceId);
  const { start, end } = reference;
  const valid = !!source && Number.isInteger(start) && Number.isInteger(end)
    && start >= 0 && end > start && end <= source.rawValue.length;
  return { id: reference.sourceId, start, end, selector: source?.selector, kind: source?.kind,
    publicNavigation: source?.publicNavigation === true,
    publicRegion: source?.publicRegion,
    interactionPending: source?.interactionPending === true,
    value: valid ? source.rawValue.slice(start, end) : '', valid };
}));
const compactPane = ref<'fields'|'source'>('fields');
const bodyContent = computed(() => {
  if (selected.value?.fieldType !== 'Editor') return;
  const html = evidence.value.filter(row => row.valid && row.kind === 'HTML_REGION').map(row => row.value).join('');
  if (!html) return;
  const template = document.createElement('template');
  template.innerHTML = html;
  return {
    paragraphs: template.content.querySelectorAll('p').length,
    images: template.content.querySelectorAll('img').length,
    links: template.content.querySelectorAll('a[href]').length,
  };
});
const initialValue = computed(() => evidence.value.map((row, index, rows) =>
  (index && !(rows[index - 1].id === row.id && rows[index - 1].end === row.start) ? '\n' : '') + row.value).join(''));

function clear() {
  pending?.abort();
  pending = undefined;
  preview.value = undefined;
  loading.value = false;
  error.value = '';
  selectedId.value = '';
  coverageSourceId.value='';
  feedback.value = '';
  edits.value.clear();
  pendingOnly.value = false;
}
watch(visible, value => { if (!value) clear(); }, { flush: 'sync' });
watch([() => props.siteId, () => props.sourcePath, () => props.packageVersion, () => props.digest, () => props.revision],
  (current, previous) => {
    if (!visible.value) return;
    pending?.abort();
    pending = undefined;
    preview.value = undefined;
    loading.value = false;
    if (saving.value && current.slice(0, 4).every((value, index) => value === previous[index])) {
      void loadPreview();
      return;
    }
    error.value = '页面包或来源修订已变化，当前审核无法继续。请关闭后重新读取页面。';
  });
watch(() => props.units, units => {
  if (visible.value && !units.some(unit => unit.id === selectedId.value)) selectedId.value = units[0]?.id ?? '';
});
onBeforeUnmount(clear);

async function open(unitId?: string) {
  if (props.disabled || !ready.value) return;
  clear();
  const target=props.units.find(unit=>unit.id===unitId) || props.units.find(unit=>!unit.ignored) || props.units[0];
  includeIgnored.value=!!target?.ignored;
  selectedId.value=target?.id || '';
  visible.value = true;
  await loadPreview();
}
async function loadPreview() {
  loading.value = true;
  const { siteId, sourcePath, digest, revision, packageVersion } = props;
  const controller = new AbortController();
  pending = controller;
  try {
    const response = await http.get<Preview>(`/api/admin/v2/sites/${siteId}/template-seed/binding-preview`, {
      params: { sourcePath, digest, revision }, signal: controller.signal,
    });
    if (controller.signal.aborted) return;
    if (response.digest !== digest || response.revision !== revision || response.packageVersion !== packageVersion) {
      error.value = '页面包或来源修订已变化，请重新读取当前页面后再审核。';
      return;
    }
    preview.value = response;
  } catch {
    if (!controller.signal.aborted) error.value = '无法读取与当前修订匹配的隔离预览，请重新读取当前页面后重试。';
  } finally {
    if (pending === controller) { pending = undefined; loading.value = false; }
  }
}
function edit(change: Partial<Pick<DomainUnit, 'name' | 'fieldType' | 'ignored' | 'doubts'>>) {
  if (!original.value || !selected.value || props.disabled || error.value) return;
  const unit = { ...selected.value, ...change };
  if (unit.name === original.value.name && unit.fieldType === original.value.fieldType
    && unit.ignored === original.value.ignored && unit.doubts === original.value.doubts) edits.value.delete(unit.id);
  else edits.value.set(unit.id, unit);
  feedback.value = '';
}
function applyEdits() {
  if (!editCount.value) return true;
  if (error.value || !props.editUnits?.([...edits.value.values()])) {
    feedback.value = '工作区已变化，无法应用本次修正。请重新读取当前页面。';
    return false;
  }
  edits.value.clear();
  feedback.value = '整页修正已应用到本地草稿，尚未保存。';
  return true;
}
async function save() {
  if (props.disabled || saving.value || !props.saveUnits || !applyEdits()) return;
  saving.value = true;
  feedback.value = '正在验证并保存整页内容单元…';
  try {
    const failure = await props.saveUnits();
    feedback.value = failure || `整页内容单元已保存，未写入业务内容。${props.nextStepNotice || ''}`;
  } finally { saving.value = false; }
}
async function close(done: () => void) {
  if (saving.value) return;
  if (editCount.value) {
    try {
      await ElMessageBox.confirm('弹窗里还有未应用的修正。关闭后将丢弃这些修正，已应用到本地草稿的修改仍会保留。',
        '关闭整页审核', { type: 'warning', confirmButtonText: '丢弃并关闭', cancelButtonText: '继续审核' });
    } catch { return; }
  }
  done();
}
function requestClose() { return close(() => { visible.value = false; }); }
function showSources() {
  if (!props.returnToSources || !selected.value || !applyEdits()) return;
  returnSourceId = selected.value.id;
  visible.value = false;
}
function onClosed() {
  if (!returnSourceId) return;
  const id = returnSourceId;
  returnSourceId = '';
  props.returnToSources?.(id);
}
defineExpose({ open, hasUnappliedChanges: () => editCount.value > 0 });

function explicitlyHidden(element: HTMLElement): boolean {
  for (let node: HTMLElement | null = element; node; node = node.parentElement) {
    if (node.matches('[hidden],[aria-hidden="true"],dialog:not([open])') || node.style.display === 'none'
      || ['hidden', 'collapse'].includes(node.style.visibility) || node.style.opacity === '0'
      || node.style.contentVisibility === 'hidden') return true;
    if (node.matches('details:not([open])') && !Array.from(node.children).find(child => child.localName === 'summary')?.contains(element)) return true;
  }
  return false;
}

function indexPositions(doc: Document) {
  const positions = new Map<string, HTMLElement[]>();
  for (const element of doc.querySelectorAll<HTMLElement>('[data-cms-binding-sources]')) {
    if (!(element instanceof HTMLElement) || !doc.body.contains(element)) continue;
    for (const id of new Set((element.dataset.cmsBindingSources || '').split(/\s+/).filter(Boolean))) {
      const existing = positions.get(id) || [];
      existing.push(element);
      positions.set(id, existing);
    }
  }
  return positions;
}

function positionStatus(matches: HTMLElement[] = []) {
  if (!matches.length) return 'missing';
  if (matches.length > 1) return 'ambiguous';
  return explicitlyHidden(matches[0]) ? 'hidden' : 'located';
}

// 每次读取来源只创建一个隔离文档。切换来源仅变更片段，不重新加载页面或运行原页脚本。
const frameDocument = ref(''), anchors = ref(new Map<string,string>());
watch(previewDocument, (input, _, cleanup) => {
  frameDocument.value=''; anchors.value=new Map();
  if (!input) return;
  const doc=input.cloneNode(true) as Document;
  const style=doc.createElement('style');
  style.textContent='[data-cms-preview-anchor]:target{outline:3px solid #2563eb!important;outline-offset:2px;scroll-margin-top:80px;opacity:1!important;visibility:visible!important}html{scroll-behavior:auto!important}';
  doc.head.append(style);
  const map=new Map<string,string>();
  const ids=new Map<string,number>(); for(const el of doc.querySelectorAll('[id]'))ids.set(el.id,(ids.get(el.id)||0)+1);
  let seq=0;
  for(const [key, matches] of indexPositions(doc)){
    if(sourceStatuses.value?.get(key)!=='located')continue;
    const el=matches[0]!;
    if(el===doc.body || el.matches('title,script,style,meta,link') || (el.id && ids.get(el.id)!==1)) continue;
    if(!el.id){let id; do{id='cms-preview-'+(++seq);}while(doc.getElementById(id)); el.id=id;}
    el.setAttribute('data-cms-preview-anchor',''); map.set(key,el.id);
  }
  anchors.value=map;
  const url=URL.createObjectURL(new Blob(['<!doctype html>',doc.documentElement.outerHTML],{type:'text/html;charset=utf-8'}));
  frameDocument.value=url;
  cleanup(()=>URL.revokeObjectURL(url));
});
const rendered=computed(()=>({rows:evidence.value.map(row=>{
  const located=!!row.valid && !!row.value.trim() && anchors.value.has(row.id);
  const status=sourceStatuses.value?.get(row.id);
  const message=located?'已定位到原页':!row.valid?'来源区间已失效':!row.value.trim()?'空白文本没有可见位置':status==='hidden'?'原页折叠或隐藏此处，请核对选择器和原文':status==='ambiguous'?'来源命中多处，请核对选择器':'隔离预览没有可见位置，请核对选择器和原文';
  const matched=previewDocument.value?.querySelectorAll('[data-cms-binding-sources~="'+CSS.escape(row.id)+'"]');
  const node=matched?.length===1 ? matched[0] : undefined;
  const snippet=node && node!==previewDocument.value?.body ? node.cloneNode(false) as Element : null;
  if(snippet) for(const attr of [...snippet.attributes]) if(attr.value.length>140)snippet.setAttribute(attr.name,attr.value.slice(0,140)+'…');
  return {...row,located,message,snippet:snippet?.outerHTML};
})}));
const locations=computed(()=>[...new Set(rendered.value.rows.filter(row=>row.located).map(row=>anchors.value.get(row.id)!))]);
const locationIndex=ref(0);
function canLocateCoverage(sourceId:string){return anchors.value.has(sourceId);}
function restoreCoverageUnits(unitIds:string[]) {
  const restored=props.restoreCoverageSources?.(unitIds) ?? [];
  for(const id of restored) {
    const edit=edits.value.get(id);
    if(edit)edits.value.set(id,{...edit,ignored:false});
  }
  return restored;
}
async function reviewCoverageUnit(unitId:string){
  await nextTick();
  const unit=reviewUnits.value.find(item=>item.id===unitId);
  if(!unit)return;
  if(unit.ignored)includeIgnored.value=true;
  pendingOnly.value=false;
  selectedId.value=unit.id;
  coverageSourceId.value='';
}
watch(selectedId,()=>{locationIndex.value=0;coverageSourceId.value='';},{flush:'sync'});
watch(locationIndex,()=>{coverageSourceId.value='';},{flush:'sync'});
const frameUrl=computed(()=>{
  const position=coverageSourceId.value ? anchors.value.get(coverageSourceId.value) : locations.value[locationIndex.value];
  return frameDocument.value+(position?'#'+encodeURIComponent(position):'');
});
const unlocatedCount=computed(()=>rendered.value.rows.filter(row=>!row.located).length);
</script>

<style scoped>
.binding-shell { display: flex; flex: 1; min-height: 0; flex-direction: column; gap: 12px; }
.binding-heading, .binding-toolbar, .binding-footer, .binding-actions { display: flex; align-items: center; gap: 8px; }
.binding-heading, .binding-toolbar, .binding-footer { justify-content: space-between; flex-wrap: wrap; }
.binding-heading { overflow-wrap: anywhere; }
.binding-heading p, .binding-detail p { margin: 4px 0; }
.binding-heading p, .binding-hint, .binding-toolbar, .binding-list small, .binding-empty { color: var(--el-text-color-secondary); }
.binding-actions { flex-wrap: wrap; }
.binding-actions .el-button + .el-button { margin-left: 0; }
.compact-panes { display: none; }
.binding-grid { display: grid; grid-template-columns: minmax(0, 1fr) minmax(310px, 360px); flex: 1; gap: 12px; min-height: 0; }
.binding-page, .binding-review { display: flex; flex-direction: column; gap: 8px; min-width: 0; min-height: 0; }
.binding-viewport { flex: 1; min-height: 0; overflow: hidden; contain:size; overflow-anchor:none; background: var(--el-fill-color); border: 1px solid var(--el-border-color); }
.binding-viewport.actual-size {overflow:auto;scrollbar-gutter:stable;}
.binding-frame { position: relative; overflow: hidden; margin-inline: auto; }
.binding-frame iframe { position: absolute; inset: 0 auto auto 0; display: block; transform-origin: top left; border: 0; background: #fff; }
.binding-limitations { max-height: 88px; overflow: auto; }
.binding-review { border: 1px solid var(--el-border-color); border-radius: 8px; padding: 10px; }
.binding-list { position: relative; margin: 0; padding: 0; list-style: none; max-height: min(24vh, 220px); min-height: 36px; overflow: auto; flex-shrink: 0; border-block: 1px solid var(--el-border-color-lighter); }
.binding-list button { display: flex; justify-content: space-between; align-items: center; gap: 6px; text-align: left; width: 100%; min-height: 34px; padding: 5px 7px; border: 0; background: transparent; color: var(--el-text-color-primary); cursor: pointer; overflow-wrap: anywhere; }
.binding-list button:hover, .binding-list button:focus-visible { background: var(--el-fill-color-light); }
.binding-actions :deep(.el-tag) { --el-tag-text-color: var(--el-text-color-primary); }
.binding-list button[aria-current] small { color: var(--el-text-color-regular); }
.binding-list button[aria-current] { background: var(--el-color-primary-light-9); color: var(--el-text-color-primary); font-weight: 600; }
.binding-list small { flex-shrink: 0; font-weight: normal; }
.binding-empty { padding: 6px; }
.binding-detail { min-height: 0; overflow: auto; overflow-wrap: anywhere; padding-inline: 2px; }
.binding-detail h3 { margin: 2px 0 6px; font-size: 16px; }
.binding-guide { padding: 8px; border-radius: 6px; background: var(--el-color-warning-light-9); }
.binding-form { display: grid; gap: 6px; margin: 10px 0; }
.binding-form label:not(.el-checkbox) { display: grid; gap: 4px; }
.binding-value { margin: 10px 0; }
.binding-detail pre { margin: 4px 0 8px; padding: 8px; max-height: 110px; overflow: auto; white-space: pre-wrap; overflow-wrap: anywhere; background: var(--el-fill-color-light); }
.binding-evidence { padding-inline-start: 20px; }
.binding-evidence span, .binding-evidence code { display: block; color: var(--el-text-color-secondary); }
.binding-footer { flex-shrink: 0; padding-top: 10px; border-top: 1px solid var(--el-border-color-lighter); }
.binding-footer > div:first-child { flex: 1 1 250px; overflow-wrap: anywhere; }
@media (max-width: 780px) {
  .binding-grid { grid-template-columns: minmax(0, 1fr); grid-template-rows: minmax(0, 1fr); overflow: auto; }
  .compact-panes { display: flex; gap: 8px; }
  .compact-panes button { min-height: 44px; color: var(--el-text-color-primary); background: var(--el-bg-color); border: 1px solid var(--el-border-color); border-radius: 4px; padding-inline: 12px; }
  .compact-panes button[aria-pressed="true"] { border-color: var(--el-color-primary); font-weight: 600; }
  .binding-grid[data-pane="source"] .binding-review, .binding-grid[data-pane="fields"] .binding-page { display: none; }
  .binding-list { max-height: 90px; }
}
</style>

<style>
.el-dialog.binding-workspace { display: flex; flex-direction: column; overflow: hidden; height: 100dvh; }
.binding-workspace > .el-dialog__header { flex-shrink: 0; }
.binding-workspace > .el-dialog__body { flex: 1; display: flex; flex-direction: column; min-height: 0; }
</style>
