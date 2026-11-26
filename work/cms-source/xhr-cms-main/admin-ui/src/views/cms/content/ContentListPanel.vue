<template>
  <section class="admin-compact-list" data-testid="content-list-panel">
    <div class="cms-content__table-scroll admin-table-shell">
      <el-table element-loading-text="正在加载数据…" v-loading="loading" :data="rows" stripe height="100%" @selection-change="onSelectionChange">
        <template #empty><slot name="empty">暂无内容</slot></template>
        <el-table-column type="selection" width="50" fixed="left" />
        <el-table-column prop="recordId" label="ID" width="80" fixed="left" />
        <el-table-column v-if="hasTitleField" prop="title" :label="titleColumnLabel" min-width="200" fixed="left">
          <template #default="{ row }">
            <div class="cms-content__title-cell">
              <span class="cms-content__title">{{ row.title || '-' }}</span>
              <div v-if="row.indexTop || row.indexElite || row.indexHot || row.channelTop || row.channelElite || row.channelHot || row.menuTop || row.menuElite || row.menuHot || row.navElite || row.rightElite || row.leftElite" class="cms-content__title-tags">
                <el-tag v-if="row.indexTop" size="small" type="danger">首页置顶</el-tag>
                <el-tag v-if="row.indexElite" size="small" type="warning">首页推荐</el-tag>
                <el-tag v-if="row.indexHot" size="small" type="warning">首页热门</el-tag>
                <el-tag v-if="row.channelTop" size="small" type="danger">频道置顶</el-tag>
                <el-tag v-if="row.channelElite" size="small" type="success">频道推荐</el-tag>
                <el-tag v-if="row.channelHot" size="small" type="warning">频道热门</el-tag>
                <el-tag v-if="row.menuTop" size="small" type="primary">栏目置顶</el-tag>
                <el-tag v-if="row.menuElite" size="small" type="success">栏目推荐</el-tag>
                <el-tag v-if="row.menuHot" size="small" type="warning">栏目热门</el-tag>
                <el-tag v-if="row.navElite" size="small" type="success">导航推荐</el-tag>
                <el-tag v-if="row.rightElite" size="small" type="success">右侧推荐</el-tag>
                <el-tag v-if="row.leftElite" size="small" type="success">左侧推荐</el-tag>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column v-if="importedRecordIds?.size" label="导入批次" width="104">
          <template #default="{ row }">
            <el-tag v-if="importedRecordIds?.has(row.recordId)" size="small" type="info">本次导入</el-tag>
          </template>
        </el-table-column>
        <!-- 动态列表字段（过滤掉 title，因为已有固定标题列） -->
        <el-table-column
          v-for="field in filteredListFields"
          :key="field.fieldName"
          :prop="field.fieldName"
          :label="field.title"
          :min-width="getColumnWidth(field.fieldType)"
        >
          <template #default="{ row }">
            <!-- 图片类型显示缩略图 -->
            <template v-if="isImageField(field.fieldType)">
              <img
                v-if="getFieldValue(row, field.fieldName)"
                :src="getFieldValue(row, field.fieldName)"
                class="cms-content__thumbnail"
                @dblclick="openImagePreview(getFieldValue(row, field.fieldName))"
              />
              <span v-else class="cms-content__empty">-</span>
            </template>
            <!-- 视频类型显示预览 -->
            <template v-else-if="isVideoField(field.fieldType)">
              <video
                v-if="getFieldValue(row, field.fieldName)"
                :src="getFieldValue(row, field.fieldName)"
                muted
                playsinline
                class="cms-content__video-thumbnail"
                @dblclick="openVideoPreview(getFieldValue(row, field.fieldName))"
              />
              <span v-else class="cms-content__empty">-</span>
            </template>
            <!-- 日期时间类型格式化显示 -->
            <template v-else-if="isDateTimeField(field.fieldType, field.fieldName)">
              {{ formatDateTime(getFieldValue(row, field.fieldName)) }}
            </template>
            <!-- 文件类型显示下载链接 -->
            <template v-else-if="isFileField(field.fieldType)">
              <a
                v-if="getFieldValue(row, field.fieldName)"
                :href="getFieldValue(row, field.fieldName)"
                target="_blank"
                class="cms-content__file-link"
              >
                <el-icon><Document /></el-icon>
                <span>下载</span>
              </a>
              <span v-else class="cms-content__empty">-</span>
            </template>
            <!-- 富文本去除 HTML 标签显示纯文本 -->
            <template v-else-if="isRichTextField(field.fieldType)">
              <el-tooltip
                v-if="stripHtml(getFieldValue(row, field.fieldName))"
                :content="stripHtml(getFieldValue(row, field.fieldName))"
                placement="top"
                :show-after="500"
              >
                <span class="cms-content__multiline">{{ stripHtml(getFieldValue(row, field.fieldName)) }}</span>
              </el-tooltip>
              <span v-else class="cms-content__empty">-</span>
            </template>
            <!-- 多行文本截断显示 -->
            <template v-else-if="isMultilineField(field.fieldType)">
              <el-tooltip
                v-if="getFieldValue(row, field.fieldName)"
                :content="getFieldValue(row, field.fieldName)"
                placement="top"
                :show-after="500"
              >
                <span class="cms-content__multiline">{{ getFieldValue(row, field.fieldName) }}</span>
              </el-tooltip>
              <span v-else class="cms-content__empty">-</span>
            </template>
            <!-- 默认文本显示 -->
            <template v-else>
              {{ displayFieldValue(row, field) || '-' }}
            </template>
          </template>
        </el-table-column>
        <el-table-column label="浏览量" width="90" align="center">
          <template #default="{ row }">{{ row.hits ?? 0 }}</template>
        </el-table-column>
        <el-table-column label="顺序号" width="90" align="center">
          <template #default="{ row }">
            <el-input
              v-if="editingSortId === row.recordId"
              v-model="editingSortValue"
              size="small"
              class="cms-content__sort-input"
              @keyup.enter="commitSort(row)"
              @blur="commitSort(row)"
            />
            <span v-else class="cms-content__sort" title="点击修改顺序号" @click="startEditSort(row)">{{ row.sort ?? 0 }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="statusType(row.workflowStatus)">{{ statusText(row.workflowStatus) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="更新时间" width="160">
          <template #default="{ row }">
            {{ formatTime(row.updatedAt) }}
          </template>
        </el-table-column>
        <el-table-column v-for="sub in jumpCols" :key="sub.id" :label="sub.className" width="110">
          <template #default="{ row }">
            <el-button size="small" type="primary" plain @click="emit('navigate', { sub, row })">进入</el-button>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <div class="cms-content__row-actions">
              <el-button v-if="operate(2)" size="small" type="primary" :data-testid="`content-edit-${row.recordId}`" @click="emit('edit', row)">
                {{ editButtonText(row) }}
              </el-button>
              <el-button v-if="operate(3)" size="small" type="danger" plain :data-testid="`content-delete-${row.recordId}`"
                         @click="emit('delete', row)">删除
              </el-button>
              <br>
              <el-button v-if="operate(7)" size="small" :data-testid="`content-copy-${row.recordId}`" @click="emit('copy', row)">复制</el-button>
              <el-dropdown size="small" trigger="click" @command="(cmd: string) => handleMoreAction(row, cmd)">
                <el-button size="small">
                  更多<el-icon class="el-icon--right"><ArrowDown /></el-icon>
                </el-button>
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item v-if="operate(2)" command="translate" :data-testid="`content-translate-${row.recordId}`">内容翻译</el-dropdown-item>
                    <el-dropdown-item v-if="operate(2)" command="seo">SEO设置</el-dropdown-item>
                    <el-dropdown-item v-if="operate(2)" command="seo">GEO设置</el-dropdown-item>
                    <el-dropdown-item v-if="operate(5)" command="preview">预览页面</el-dropdown-item>
                    <el-dropdown-item v-if="operate(23)" command="audit">审核信息</el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
              <br>
              <el-button v-if="operate(4) && row.variantExists && row.workflowStatus === 'DRAFT'" size="small"
                         :data-testid="`content-submit-${row.recordId}`" @click="emit('workflow', row, 'submit')">提交
              </el-button>
              <el-button v-if="operate(23) && row.variantExists && row.workflowStatus === 'PENDING_REVIEW'" size="small" type="success"
                         :data-testid="`content-approve-${row.recordId}`" @click="emit('workflow', row, 'approve')">批准
              </el-button>
              <el-button v-if="operate(23) && row.variantExists && row.workflowStatus === 'PENDING_REVIEW'" size="small" type="danger" plain
                         :data-testid="`content-reject-${row.recordId}`" @click="emit('workflow', row, 'reject')">驳回
              </el-button>
              <el-button v-if="operate(4) && row.variantExists && row.workflowStatus === 'APPROVED'" size="small" type="primary"
                         :data-testid="`content-publish-${row.recordId}`" @click="emit('workflow', row, 'publish')">发布
              </el-button>
              <el-button v-if="operate(5) && row.variantExists && row.workflowStatus === 'PUBLISHED'" size="small"
                         :data-testid="`content-history-${row.recordId}`" @click="emit('history', row)">历史
              </el-button>
            </div>
          </template>
        </el-table-column>
      </el-table>
    </div>
    <div class="cms-content__pager">
      <div class="cms-content__review-summary">
        <span>当前 {{ rows.length }} 条，</span>
        <span>待审核 {{ pendingReviewCount }} 条 /</span>
        <!-- <span v-if="selectedRows.length > 0">，已选 {{ selectedRows.length }} 条</span> -->
      </div>
      <el-pagination v-if="!loading" :current-page="page" :page-size="pageSize" :total="total" layout="total, prev, pager, next" @update:current-page="emit('update:page', $event)" @change="emit('reload')" />
      <el-select
        v-if="pageSizes?.length"
        class="cms-content__page-size"
        size="small"
        :model-value="pageSize"
        :disabled="loading"
        aria-label="每页条数"
        @update:model-value="emit('update:page-size', $event)"
      >
        <el-option v-for="size in pageSizes" :key="size" :value="size" :label="`每页${size}条`" />
      </el-select>
    </div>

    <!-- 图片预览对话框 -->
    <el-dialog
      v-model="imagePreviewVisible"
      title="图片预览"
      width="fit-content"
      destroy-on-close
      class="cms-content__preview-dialog"
    >
      <img
        v-if="imagePreviewUrl"
        :src="imagePreviewUrl"
        class="cms-content__image-preview"
      />
      <template #footer>
        <el-button @click="imagePreviewVisible = false">关闭</el-button>
      </template>
    </el-dialog>

    <!-- 视频预览对话框 -->
    <el-dialog
      v-model="videoPreviewVisible"
      title="视频预览"
      width="800px"
      destroy-on-close
    >
      <video
        v-if="videoPreviewUrl"
        :src="videoPreviewUrl"
        controls
        autoplay
        class="cms-content__video-preview"
      />
      <template #footer>
        <el-button @click="videoPreviewVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<script setup lang="ts">
import { useResourceReferences } from '@/api/resourceReferences';
import { computed, nextTick, ref, watch } from 'vue';
import { ArrowDown, Document } from '@element-plus/icons-vue';
import type { NativeContentRow as Row, ListFieldConfig } from '@/api/cms';
import { fetchNativeSelectOptions, type SelectOptionItem } from '@/api/cms';
import { getUiPackageAccessUrl, startUiPackage } from '@/api/uiPackage';
import { useSiteContextStore } from '@/stores/siteContext';
import { unpackMultiValue } from '@/utils/multiValue';

const { resolve: resolveResourceReference } = useResourceReferences();
const siteContext = useSiteContextStore();

interface JumpColumn {
  id: number;
  className: string;
}

const props = defineProps<{
  rows: Row[];
  loading: boolean;
  total: number;
  page: number;
  pageSize: number;
  pageSizes?: number[];
  pendingReviewCount: number;
  listFields: ListFieldConfig[];
  menuId: number;
  jumpColumns?: JumpColumn[];
  importedRecordIds?: ReadonlySet<number>;
}>();

/** 父栏目跳转列（未配置时为空） */
const jumpCols = computed(() => props.jumpColumns ?? []);

// 过滤掉 title 字段，避免与固定标题列重复
const filteredListFields = computed(() =>
  props.listFields.filter(f => f.fieldName.toLowerCase() !== 'title')
);

/** 当前栏目可执行某操作（optionId 对应 other_setting；超管恒 true） */
function operate(optionId: number): boolean {
  return siteContext.canOperate(props.menuId, optionId);
}

// 检查 listFields 中是否包含 title 字段
const hasTitleField = computed(() =>
  props.listFields.some(f => f.fieldName.toLowerCase() === 'title')
);

// 固定标题列名称：优先使用字段配置的业务名称(title)，否则回退「标题」
const titleColumnLabel = computed(() => {
  const field = props.listFields.find(f => f.fieldName.toLowerCase() === 'title');
  return field?.title || '标题';
});

// 栏目内容字段（inputType=content 或动态下拉），列表存 id，需反查显示 title
const contentFields = computed(() =>
  props.listFields.filter(f =>
    f.inputType === 'content' || (f.selectQuery && f.selectQuery.startsWith('{$'))
  )
);

// 字段名 -> { id: title } 映射，用于栏目内容列显示
const contentLabelMaps = ref<Record<string, Record<string, string>>>({});

watch(
  () => [props.menuId, contentFields.value] as const,
  async ([menuId, fields]) => {
    if (!menuId) return;
    for (const field of fields) {
      if (!field.selectQuery) continue;
      try {
        const options: SelectOptionItem[] =
          await fetchNativeSelectOptions(menuId, field.selectQuery);
        const map: Record<string, string> = {};
        for (const opt of options ?? []) {
          map[opt.value] = opt.label;
        }
        contentLabelMaps.value[field.fieldName] = map;
      } catch {
        contentLabelMaps.value[field.fieldName] = {};
      }
    }
  },
  { immediate: true }
);

function displayFieldValue(row: Row, field: ListFieldConfig): string {
  const raw = getFieldValue(row, field.fieldName);
  if (!raw) return raw;
  const isContent = field.inputType === 'content' || Boolean(field.selectQuery && field.selectQuery.startsWith('{$'));
  if (!field.inputMultiple) {
    if (isContent) {
      const map = contentLabelMaps.value[field.fieldName];
      if (map && map[raw] != null) return map[raw];
    }
    return raw;
  }
  // 多选字段存 ,a,b,：逐项映射后用「、」连接
  const parts = unpackMultiValue(raw);
  if (isContent) {
    const map = contentLabelMaps.value[field.fieldName] ?? {};
    return parts.map(v => map[v] ?? v).join('、');
  }
  return parts.join('、');
}

const emit = defineEmits<{
  edit: [row: Row];
  copy: [row: Row];
  translate: [row: Row];
  workflow: [row: Row, action: 'submit' | 'approve' | 'reject' | 'publish'];
  history: [row: Row];
  delete: [row: Row];
  'update:page': [page: number];
  'update:page-size': [size: number];
  reload: [];
  'recommend-change': [row: Row, field: string, value: boolean];
  'selection-change': [rows: Row[]];
  seo: [row: Row];
  preview: [row: Row];
  audit: [row: Row];
  navigate: [payload: { sub: JumpColumn; row: Row }];
  'sort-change': [row: Row, sort: number];
}>();

const selectedRows = ref<Row[]>([]);

/** 排序号行内编辑：当前正在编辑的行 id 与输入值 */
const editingSortId = ref<number | null>(null);
const editingSortValue = ref('');

function startEditSort(row: Row) {
  editingSortId.value = row.recordId;
  editingSortValue.value = String(row.sort ?? 0);
  // el-table 单元格内的模板 ref 会被收集为数组，故用 DOM 查询聚焦当前唯一在编辑的输入框
  void nextTick(() => {
    document.querySelector<HTMLInputElement>('.cms-content__sort-input input')?.focus();
  });
}

function commitSort(row: Row) {
  // enter 会先触发一次、随后的 blur 再触发一次；首次提交后 editingSortId 置空，第二次直接返回
  if (editingSortId.value !== row.recordId) return;
  editingSortId.value = null;
  const raw = editingSortValue.value.trim();
  const parsed = /^-?\d+$/.test(raw) ? Number(raw) : 0;
  if (parsed === (row.sort ?? 0)) return;
  emit('sort-change', row, parsed);
}

// 图片预览
const imagePreviewVisible = ref(false);
const imagePreviewUrl = ref('');

function openImagePreview(url: string) {
  if (!url) return;
  imagePreviewUrl.value = url;
  imagePreviewVisible.value = true;
}

// 视频预览
const videoPreviewVisible = ref(false);
const videoPreviewUrl = ref('');

function openVideoPreview(url: string) {
  if (!url) return;
  videoPreviewUrl.value = url;
  videoPreviewVisible.value = true;
}

function onSelectionChange(rows: Row[]) {
  selectedRows.value = rows;
  emit('selection-change', rows);
}

function handleMoreAction(row: Row, command: string) {
  if (command === 'delete') {
    emit('delete', row);
  } else if (command === 'seo') {
    emit('seo', row);
  } else if (command === 'preview') {
    emit('preview', row);
  } else if (command === 'audit') {
    emit('audit', row);
  } else if (command === 'translate') {
    emit('translate', row);
  }
}

function statusText(status?: string | null) {
  return ({ DRAFT: '草稿', PENDING_REVIEW: '待审', APPROVED: '已批准', REJECTED: '已驳回', PUBLISHED: '已发布', ARCHIVED: '作废' } as Record<string, string>)[status ?? ''] ?? '未创建';
}

function statusType(status?: string | null) {
  return ({ PUBLISHED: 'success', APPROVED: 'primary', PENDING_REVIEW: 'warning', REJECTED: 'danger', ARCHIVED: 'info' } as Record<string, string>)[status ?? ''] ?? 'info';
}

function getColumnWidth(fieldType: string): number {
  switch (fieldType?.toLowerCase()) {
    case 'datetime':
    case 'date':
      return 160;
    case 'editor':
    case 'richtext':
    case 'multiline':
    case 'textarea':
      return 200;
    case 'image':
    case 'photo':
      return 80;
    case 'video':
    case 'movie':
    case 'media':
      return 100;
    case 'file':
    case 'attachment':
      return 80;
    default:
      return 120;
  }
}

function isImageField(fieldType: string): boolean {
  const type = fieldType?.toLowerCase() ?? '';
  return ['image', 'photo', 'picture'].includes(type);
}

function isDateTimeField(fieldType: string, fieldName?: string): boolean {
  const type = fieldType?.toLowerCase() ?? '';
  // 根据字段类型判断
  if (['datetime', 'date', 'time'].includes(type)) return true;
  // 根据字段名判断（以 Time 结尾或包含特定关键词）
  if (fieldName) {
    const name = fieldName.toLowerCase();
    if (name.endsWith('time') || name.endsWith('date')) return true;
    if (['createtime', 'begintime', 'endtime', 'entryintoforcetime', 'expirationtime'].includes(name)) return true;
  }
  return false;
}

function isFileField(fieldType: string): boolean {
  const type = fieldType?.toLowerCase() ?? '';
  return ['file', 'attachment', 'download'].includes(type);
}

function isVideoField(fieldType: string): boolean {
  const type = fieldType?.toLowerCase() ?? '';
  return ['video', 'movie', 'media'].includes(type);
}

function isRichTextField(fieldType: string): boolean {
  const type = fieldType?.toLowerCase() ?? '';
  return ['editor', 'richtext'].includes(type);
}

function isMultilineField(fieldType: string): boolean {
  const type = fieldType?.toLowerCase() ?? '';
  return ['multiline', 'textarea'].includes(type);
}

/** 富文本在列表中展示纯文本：去掉 HTML 标签与实体，保留可读内容。 */
function stripHtml(html: string): string {
  return html
    .replace(/<[^>]*>/g, ' ')
    .replace(/&nbsp;/g, ' ')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&amp;/g, '&')
    .replace(/\s+/g, ' ')
    .trim();
}

function getFieldValue(row: Row, fieldName: string): string {
  const value = row.fieldValues?.[fieldName] ?? row.sharedValues?.[fieldName];
  if (value == null) return '';
  let strValue: string;
  if (typeof value === 'string') {
    strValue = value;
  } else if (typeof value === 'number' || typeof value === 'boolean') {
    strValue = String(value);
  } else if (typeof value === 'object') {
    // 如果是数组，取第一个元素
    if (Array.isArray(value)) {
      strValue = value.length > 0 ? String(value[0]) : '';
    } else {
      // 如果是对象，尝试转换为 JSON 字符串
      try {
        strValue = JSON.stringify(value);
      } catch {
        strValue = String(value);
      }
    }
  } else {
    strValue = String(value);
  }
  return resolveResourceReference(strValue);
}

function formatTime(time?: string | null): string {
  if (!time) return '-';
  return time.replace('T', ' ').substring(0, 16);
}

function formatDateTime(value: string): string {
  if (!value) return '-';
  // 如果已经是格式化的日期时间，直接返回
  if (value.includes(' ') || value.includes('T')) {
    return value.replace('T', ' ').substring(0, 16);
  }
  return value;
}

function editButtonText(row: Row): string {
  // 与老系统一致：始终显示"修改"按钮文本
  // 对于新CMS数据（有变体）显示"修改"，对于老CMS数据（无变体）也显示"修改"
  if (row.modelKey === 'legacy' || !row.variantExists) {
    return '修改';
  }
  return row.workflowStatus === 'PUBLISHED' ? '修改' : '编辑';
}
</script>

<style scoped>
.cms-content__title {
  font-weight: 500;
}
.cms-content__title-cell {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.cms-content__title-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 2px;
}
.cms-content__row-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}
.cms-content__thumbnail {
  width: 60px;
  height: 60px;
  border-radius: 4px;
  cursor: zoom-in;
  object-fit: cover;
}
.cms-content__video-thumbnail {
  width: 80px;
  max-height: 60px;
  border-radius: 4px;
  background: #000;
  cursor: pointer;
}
.cms-content__image-preview {
  max-width: 80vw;
  max-height: 70vh;
  border-radius: 4px;
}
.cms-content__video-preview {
  width: 100%;
  max-height: 70vh;
  background: #000;
}
.cms-content__preview-dialog :deep(.el-dialog__body) {
  padding: 10px;
  display: flex;
  justify-content: center;
}
.cms-content__file-link {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: var(--el-color-primary);
  text-decoration: none;
}
.cms-content__file-link:hover {
  text-decoration: underline;
}
.cms-content__multiline {
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 200px;
  white-space: pre-wrap;
  word-break: break-all;
}
.cms-content__empty {
  color: var(--el-text-color-placeholder);
}
.cms-content__sort {
  cursor: pointer;
  color: var(--el-color-primary);
  border-bottom: 1px dashed var(--el-color-primary);
}
.admin-compact-list {
  display: flex;
  flex-direction: column;
  gap: 0;
  flex: 1;
  min-height: 0;
}
.cms-content__table-scroll.admin-table-shell {
  flex: 1;
  overflow: hidden;
  min-height: 0;
}
.cms-content__pager {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  padding-top: 0;
  flex-shrink: 0;
}
.cms-content__page-size {
  width: 112px;
}
</style>
