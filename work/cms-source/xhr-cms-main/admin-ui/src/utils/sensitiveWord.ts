import { http } from '@/api/http';
import { useSiteContextStore } from '@/stores/siteContext';

export type SensitiveWordAction = 'reject' | 'alert' | 'replace' | 'select_replace';

export interface SensitiveWordRule {
  id: number;
  word: string;
  action: SensitiveWordAction | string;
  replacement?: string | null;
  level?: number | null;
  status?: number | null;
  /** 作用范围：global-全局，site-指定站点 */
  scope?: 'global' | 'site' | string | null;
  /** 逗号分隔站点ID（scope=site 时有效） */
  siteIds?: string | null;
}

export interface SensitiveWordHit {
  word: string;
  action: SensitiveWordAction | string;
  replacement?: string | null;
  level?: number | null;
  count: number;
}

export interface SensitiveCheckOptions {
  /** 内容为富文本 HTML 时先去除标签再比对（content 类字段传 true） */
  html?: boolean;
  /** 校验范围：site=全局+当前站点（默认），global=仅全局规则 */
  scope?: 'global' | 'site';
}

export const SENSITIVE_WORD_ACTION_LABELS: Record<string, string> = {
  reject: '拒绝',
  alert: '告警',
  replace: '替换',
  select_replace: '选择替换'
};

export function sensitiveWordActionLabel(action: string): string {
  return SENSITIVE_WORD_ACTION_LABELS[action] ?? action;
}

/** 是否为“拒绝（不允许提交）”类命中 */
export function isRejectHit(hit: SensitiveWordHit): boolean {
  return hit.action === 'reject';
}

const HTML_BLOCK_RE = /<(script|style)\b[^>]*>[\s\S]*?<\/\1>/gi;
const HTML_TAG_RE = /<[^>]*>/g;
const HTML_ENTITIES: Record<string, string> = {
  '&nbsp;': ' ',
  '&amp;': '&',
  '&lt;': '<',
  '&gt;': '>',
  '&quot;': '"',
  '&#39;': '\'',
  '&apos;': '\''
};
const HTML_ENTITY_RE = /&(nbsp|amp|lt|gt|quot|#39|apos);/g;

/** 富文本比对前先剥离标签与实体，避免标签属性干扰匹配 */
export function stripHtmlForSensitiveCheck(html: string): string {
  if (!html) return '';
  return html
    .replace(HTML_BLOCK_RE, ' ')
    .replace(HTML_TAG_RE, ' ')
    .replace(HTML_ENTITY_RE, (match) => HTML_ENTITIES[match] ?? ' ')
    .replace(/\s+/g, ' ')
    .trim();
}

function countOccurrences(haystack: string, needle: string): number {
  if (!needle) return 0;
  const h = haystack.toLowerCase();
  const n = needle.toLowerCase();
  let index = 0;
  let count = 0;
  while ((index = h.indexOf(n, index)) !== -1) {
    count += 1;
    index += n.length;
  }
  return count;
}

/** 依据规则比对文本，返回命中的敏感词（去重、含出现次数） */
export function matchSensitiveWords(text: string, rules: SensitiveWordRule[], options: SensitiveCheckOptions = {}): SensitiveWordHit[] {
  const content = options.html ? stripHtmlForSensitiveCheck(text) : String(text ?? '');
  if (!content) return [];
  const hits = new Map<string, SensitiveWordHit>();
  for (const rule of rules) {
    if (rule.status != null && rule.status !== 1) continue;
    const word = String(rule.word ?? '').trim();
    if (!word) continue;
    const count = countOccurrences(content, word);
    if (!count) continue;
    const key = `${word}::${rule.action}`;
    const existing = hits.get(key);
    if (existing) {
      existing.count += count;
    } else {
      hits.set(key, {
        word,
        action: rule.action,
        replacement: rule.replacement ?? null,
        level: rule.level ?? null,
        count
      });
    }
  }
  return [...hits.values()];
}

let cachedRules: SensitiveWordRule[] | null = null;
let cachedPromise: Promise<SensitiveWordRule[]> | null = null;

/** 拉取启用的敏感词规则（全量，站点维度过滤在比对时按 scope 处理） */
export async function loadSensitiveWordRules(force = false): Promise<SensitiveWordRule[]> {
  if (!force && cachedRules) {
    return cachedRules;
  }
  if (!force && cachedPromise) {
    return cachedPromise;
  }
  cachedPromise = http
    .get<SensitiveWordRule[]>('/api/admin/v2/sensitive-word')
    .then((rules) => {
      cachedRules = (rules ?? []).filter((rule) => rule.status == null || rule.status === 1);
      return cachedRules;
    })
    .finally(() => {
      cachedPromise = null;
    });
  return cachedPromise;
}

export function clearSensitiveWordRuleCache(): void {
  cachedRules = null;
  cachedPromise = null;
}

/** 依据作用范围过滤规则：site=全局+当前站点，global=仅全局 */
function filterRulesForScope(rules: SensitiveWordRule[], scope: 'global' | 'site'): SensitiveWordRule[] {
  if (scope === 'global') {
    return rules.filter((rule) => rule.scope === 'global');
  }
  const siteId = useSiteContextStore().currentSite?.id;
  const siteKey = siteId == null ? null : String(siteId);
  return rules.filter((rule) => {
    if (rule.scope === 'global') {
      return true;
    }
    const ids = (rule.siteIds ?? '').split(',').map((s) => s.trim()).filter(Boolean);
    return siteKey != null && ids.includes(siteKey);
  });
}

/**
 * 公共敏感词校验方法：加载 /admin/v2/sensitive-word 配置的规则并比对给定文本，
 * 返回命中结果（敏感词、动作：替换 / 拒绝 / 告警 / 选择替换）。
 */
export async function checkSensitiveText(text: string, options: SensitiveCheckOptions = {}): Promise<SensitiveWordHit[]> {
  const rules = await loadSensitiveWordRules();
  const scoped = filterRulesForScope(rules, options.scope ?? 'site');
  return matchSensitiveWords(text, scoped, options);
}

/** 生成命中结果的展示文案，如：敏感词「xx」（拒绝，不允许提交） */
export function formatSensitiveWordHit(hit: SensitiveWordHit): string {
  const action = sensitiveWordActionLabel(hit.action);
  const suffix = hit.action === 'reject' ? '，不允许提交' : '';
  const replacement = (hit.action === 'replace' || hit.action === 'select_replace') && hit.replacement ? `，替换为「${hit.replacement}」` : '';
  return `敏感词「${hit.word}」（${action}${replacement}${suffix}）`;
}

/** 是否可对命中结果执行“确认修复”：replace 替换、reject 删除 */
export function isFixableHit(hit: SensitiveWordHit): boolean {
  return hit.action === 'replace' || hit.action === 'reject';
}

/**
 * 依据命中结果修复文本：replace 替换为 replacement（未配置 replacement 时删除），
 * reject 删除敏感词；其余动作原样返回。匹配大小写不敏感。
 */
export function applySensitiveWordFix(text: string, hit: SensitiveWordHit): string {
  const source = String(text ?? '');
  const word = String(hit.word ?? '').trim();
  if (!source || !word || !isFixableHit(hit)) return source;
  const replacement = hit.action === 'replace' ? String(hit.replacement ?? '') : '';
  const escaped = word.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  return source.replace(new RegExp(escaped, 'gi'), replacement);
}
