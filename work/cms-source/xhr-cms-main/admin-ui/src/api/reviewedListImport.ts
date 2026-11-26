import { auditContentV2, listNativeLocales, loadNativeEditor, saveNativeDraft, updateNativeContentSort } from './cms';
import { http } from './http';
import { planFields, planListQueries, planRecords, type ImportField, type ImportRecord, type ImportSection, type ReviewedListImport } from '@/utils/reviewedListImport';

interface FieldWorkspace {
  menu: { id: number; parentId: number; siteProjectId: number; pageType: number; className: string };
  configDigest: string;
  fieldOptions: ImportField[];
  catalog: ImportField[];
  fieldListDisplay: ImportField[];
  fieldSearch: ImportField[];
}

interface Row {
  id: number;
  title: string;
  status: number;
  sort: number;
  values: Record<string, unknown>;
}

type ScopeGuard = () => void;

const fieldsUrl = (menuId: number) => '/api/admin/v2/cms/fields/menus/' + menuId;
const contentUrl = (menuId: number) => '/api/admin/v2/cms/content/' + menuId;

const readFields = (menuId: number, type: number) => http.get<FieldWorkspace>(fieldsUrl(menuId), { params: { type } });

async function readRows(menuId: number, guard: ScopeGuard) {
  const rows: Row[] = [];
  for (let page = 1; ; page++) {
    guard();
    const current = await http.get<{ rows: Row[]; total: number; page: number }>('/api/admin/v2/cms/content', {
      params: { classId: menuId, page, limit: 100 }
    });
    rows.push(...current.rows);
    if (rows.length >= current.total) return rows;
    if (current.page !== page || !current.rows.length) throw new Error('未能读全栏目现有记录');
  }
}

function verifyWorkspace(workspace: FieldWorkspace, siteId: number, menuId: number, type: number, parentId?: number) {
  const {menu} = workspace;
  if (menu.id !== menuId || Number(menu.siteProjectId) !== siteId || menu.pageType !== type ||
      (parentId !== undefined && menu.parentId !== parentId) || !workspace.configDigest)
    throw new Error('当前站点、真实栏目类型或父子关系与导入文件不符');
}

function verifyRecordMarkup(section: ImportSection) {
  const richFields = section.listFields.filter(field => field.fieldType === 'Editor');
  if (!richFields.length) return;
  const template = document.createElement('template');
  const tags = new Set(['p', 'br', 'img', 'a', 'strong', 'b', 'em', 'i', 'u', 'span', 'div', 'figure',
    'figcaption', 'ul', 'ol', 'li', 'blockquote', 'h2', 'h3', 'h4', 'table', 'tbody', 'thead', 'tr', 'th', 'td']);
  for (const record of section.records) {
    for (const {fieldName} of richFields) {
      const value = record.values[fieldName];
      if (value == null || value === '') continue;
      if (typeof value !== 'string') throw new Error('图文须为文本：' + fieldName);
      template.innerHTML = value;
      for (const element of template.content.querySelectorAll('*')) {
        const tag = element.localName;
        const unsafe = !tags.has(tag) || element.namespaceURI !== 'http://www.w3.org/1999/xhtml' ||
          [...element.attributes].some(({name, value}) => {
            if (['alt', 'title', 'width', 'height', 'colspan', 'rowspan'].includes(name)) return false;
            const isAddress = (tag === 'img' && name === 'src') || (tag === 'a' && name === 'href');
            return !isAddress || !/^(?:https:\/\/[^\s/]+\/|(?:ui:|assets:)?\/(?!\/)|#)[^\s\\]*$/i.test(value);
          });
        if (unsafe) throw new Error(record.values.title + ' 的 ' + fieldName + ' 含不支持的图文标签或资源地址');
      }
    }
  }
}

function richTextComparison(fields: ImportField[]) {
  const keys = new Set(fields.filter(field => field.fieldType === 'Editor').map(field => field.fieldName));
  // 与 CMS 保存后的 HTML 序列化比较，保留真实文字、资源、节点和顺序的冲突检查。
  const template = document.createElement('template');
  return (key: string, value: unknown) => {
    if (!keys.has(key) || typeof value !== 'string') return value;
    template.innerHTML = value;
    return template.innerHTML;
  };
}

async function prepareSection(input: ReviewedListImport, section: ImportSection, narrow: boolean, guard: ScopeGuard) {
  guard();
  verifyRecordMarkup(section);
  const fields = await readFields(section.listMenuId, 0);
  verifyWorkspace(fields, input.siteId, section.listMenuId, 1, section.sectionMenuId);
  guard();
  const parent = await readFields(section.sectionMenuId, 1);
  // 公开案例和后台项目的直接父栏目均可由单页聚合；仅可选收敛需要父级是单页。
  if (parent.menu.id !== section.sectionMenuId || Number(parent.menu.siteProjectId) !== input.siteId ||
      (input.parentMenuId !== undefined && parent.menu.parentId !== input.parentMenuId))
    throw new Error('列表直接上级或聚合栏目与导入文件不符');
  const listPlan = planFields(fields.fieldOptions, section.listFields, fields.catalog);
  if (narrow && section.sectionFields) verifyWorkspace(parent, input.siteId, section.sectionMenuId, 0, input.parentMenuId);
  const queries = !listPlan.conflicts.length
    ? planListQueries(listPlan.next, fields.fieldListDisplay, fields.fieldSearch) : null;
  const sectionPlan = narrow && section.sectionFields
    ? planFields(parent.fieldOptions, section.sectionFields, parent.catalog, true)
    : null;
  const rows = await readRows(section.listMenuId, guard);
  const recordPlan = planRecords(section.records, rows, richTextComparison(section.listFields));
  return { section, fields, parent, listPlan, queries, sectionPlan, recordPlan };
}

export type SectionImportPlan = Awaited<ReturnType<typeof prepareSection>>;

export async function prepareReviewedListImport(input: ReviewedListImport, narrow: boolean, guard: ScopeGuard) {
  const plans: SectionImportPlan[] = [];
  for (const section of input.sections) {
    try {
      plans.push(await prepareSection(input, section, narrow, guard));
    } catch (error) {
      throw new Error('栏目 #' + section.listMenuId + '　' + (error instanceof Error ? error.message : String(error)));
    }
  }
  guard();
  return plans;
}

export function reviewedListImportFingerprint(plans: SectionImportPlan[]) {
  return JSON.stringify(plans.map(p => [
    p.fields.configDigest, p.sectionPlan ? p.parent.configDigest : null, p.listPlan.conflicts, p.sectionPlan?.conflicts,
    p.recordPlan.map(r => [r.action, r.detail, r.row?.id, r.row?.status, r.row?.sort])
  ]));
}

async function saveFields(workspace: FieldWorkspace, type: number, options: ImportField[],
  display = workspace.fieldListDisplay, search = workspace.fieldSearch) {
  await http.post(fieldsUrl(workspace.menu.id), {
    id: workspace.menu.id,
    type,
    fieldOptions: options,
    fieldListDisplay: display,
    fieldSearch: search
  }, { headers: { 'X-CMS-Field-Config-Digest': workspace.configDigest } });
}

async function readRow(menuId: number, recordId: number) {
  const response = await http.get<{ content: Row }>(contentUrl(menuId) + '/' + recordId);
  return response.content;
}

function expectedRow(record: ImportRecord, row: Row, compare: ReturnType<typeof richTextComparison>) {
  const planned = planRecords([record], [row], compare)[0];
  if (planned.action !== 'keep' && planned.action !== 'resume')
    throw new Error('原生记录回读与计划不符：' + planned.detail);
  return planned.action;
}

export async function applyReviewedListImport(input: ReviewedListImport, narrow: boolean, reviewed: SectionImportPlan[],
  guard: ScopeGuard, onProgress: (message: string) => void, onMutation?: () => void) {
  const current = await prepareReviewedListImport(input, narrow, guard);
  if (reviewedListImportFingerprint(current) !== reviewedListImportFingerprint(reviewed))
    throw new Error('栏目字段或记录已变化，请重新预览确认');
  const results: Array<{ menuId: number; message: string; failed?: boolean }> = [];
  guard();
  const language = (await listNativeLocales(input.siteId)).find(locale => locale.defaultLanguage && locale.enabled);
  if (!language) throw new Error('当前站点缺少已启用的默认语言');
  for (const plan of current) {
    guard();
    const { section, fields, parent, listPlan, queries, sectionPlan, recordPlan } = plan;
    const menuId = section.listMenuId;
    onProgress('正在导入 ' + fields.menu.className + ' #' + menuId);
    const problems = [...listPlan.conflicts, ...(sectionPlan?.conflicts || [])];
    if (problems.length) {
      results.push({menuId, message: problems.join('、'), failed: true});
      continue;
    }
    try {
      if (queries && (listPlan.enabled.length || queries.repaired)) {
        await saveFields(fields, 0, queries.fieldOptions, queries.fieldListDisplay, queries.fieldSearch);
        onMutation?.();
        results.push({menuId, message: listPlan.enabled.length ? '列表已启用字段 ' + listPlan.enabled.join('、') : '栏目后台列表与搜索字段已配置'});
      }
      guard();
      const creating = recordPlan.some(item => item.action === 'create');
      const editor = creating ? await loadNativeEditor({menuId, languageId: language.languageId}) : null;
      const definitions = new Map(editor?.fields.map(field => [field.fieldKey, field]));
      const compare = richTextComparison(section.listFields);
      let interrupted = false;
      for (const item of recordPlan) {
        if (item.action === 'keep' || item.action === 'conflict') {
          results.push({menuId, failed: item.action === 'conflict', message: item.record.values.title + '　' + item.detail});
          continue;
        }
        guard();
        try {
          let recordId = item.row?.id;
          if (!recordId) {
            const sharedPayload: Record<string, unknown> = {};
            const payload: Record<string, unknown> = {};
            for (const [key, value] of Object.entries(item.record.values)) {
              const field = definitions.get(key);
              if (!field) throw new Error('原生编辑器未启用字段 ' + key);
              (field.translatable ? payload : sharedPayload)[key] = value;
            }
            const missing = editor?.fields.find(field => field.required &&
              !String((field.translatable ? payload : sharedPayload)[field.fieldKey] ?? '').trim());
            if (missing) throw new Error('缺少原生必填字段 ' + missing.displayName);
            const draft = await saveNativeDraft({menuId, sharedPayload, payload});
            recordId = draft.recordId;
            if (recordId) onMutation?.();
            if (!recordId || draft.workflowStatus !== 'DRAFT') throw new Error('原生接口未确认新增草稿');
          }
          guard();
          const draft = await readRow(menuId, recordId);
          if (expectedRow(item.record, draft, compare) !== 'resume') throw new Error('待审记录已变化，请重新预览');
          if (draft.sort !== item.record.sort) {
            if (draft.status !== 0 || draft.sort !== 0) throw new Error('现有排序已变化，请重新预览');
            guard();
            await updateNativeContentSort(menuId, recordId, item.record.sort);
            onMutation?.();
          }
          guard();
          const reviewed = await auditContentV2(menuId, {
            ids: [recordId], checkStatus: 1, checkDescription: '导入已审列表记录'
          });
          if (!reviewed.successes.some(item => item.id === recordId))
            throw new Error(reviewed.failures[0]?.message || '原生审核未确认');
          onMutation?.();
          guard();
          if (expectedRow(item.record, await readRow(menuId, recordId), compare) !== 'keep')
            throw new Error('原生接口尚未回读到已发布记录');
          results.push({ menuId, message: item.record.values.title + '　已审核发布 #' + recordId });
        } catch (error) {
          interrupted = true;
          results.push({ menuId, failed: true, message: item.record.values.title + '　' + (error instanceof Error ? error.message : String(error)) });
        }
      }
      if (!interrupted && !recordPlan.some(item => item.action === 'conflict') && sectionPlan &&
          (sectionPlan.enabled.length || sectionPlan.disabled.length)) {
        guard();
        await saveFields(parent, 1, sectionPlan.next);
        onMutation?.();
        results.push({menuId, message: '分区启用字段已按清单收敛，原值保留'});
      }
    } catch (error) {
      results.push({ menuId, failed: true, message: error instanceof Error ? error.message : String(error) });
    }
  }
  return results;
}
