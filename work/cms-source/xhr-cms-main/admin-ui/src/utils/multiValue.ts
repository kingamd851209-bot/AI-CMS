/**
 * 多选值编解码。
 *
 * 多选值以 ,a,b, 形式存入文本列（两侧也带逗号），便于用 LIKE '%,a,%' 精确匹配单个值，
 * 不会像 %a% 那样误匹配 a1、1a 等。单选值保持原样存储，不受影响。
 */

/** 把多选值序列化为 ,a,b, 形式；空数组返回 ''。 */
export function packMultiValue(values: ReadonlyArray<string | number>): string {
  const parts = values.map(v => String(v)).filter(v => v !== '');
  return parts.length ? `,${parts.join(',')},` : '';
}

/** 解析 ,a,b, 形式（亦兼容单个值 a）为数组，去空白与空项。 */
export function unpackMultiValue(value?: string | null): string[] {
  if (!value) return [];
  return value
    .split(',')
    .map(s => s.trim())
    .filter(s => s !== '');
}
