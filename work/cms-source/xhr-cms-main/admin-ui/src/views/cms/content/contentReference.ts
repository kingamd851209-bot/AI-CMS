import type { SelectOptionItem } from '@/api/cms';
import { packMultiValue, unpackMultiValue } from '@/utils/multiValue';

export interface ContentReferenceField {
  fieldName: string;
  inputType?: string;
  selectQuery?: string;
  defaultValue?: string;
  inputMultiple?: boolean;
}
export interface ReferenceOption extends SelectOptionItem { unavailable: boolean }

export function isExtContentReferenceField(field: Pick<ContentReferenceField, 'fieldName' | 'inputType'>): boolean {
  return /^ext_[a-f0-9]{32}$/.test(field.fieldName) && field.inputType === 'content';
}
export function referenceIds(value: unknown, multiple: boolean): string[] {
  if (value === null || value === undefined || value === '') return [];
  const values = multiple
    ? Array.isArray(value) ? value.map(String) : unpackMultiValue(String(value))
    : [String(value)];
  return [...new Set(values.filter(value => value !== ''))];
}
export function referenceOptions(available: readonly SelectOptionItem[], selected: readonly string[], removed: ReadonlySet<string>, checking = false): ReferenceOption[] {
  const result = new Map<string, ReferenceOption>();
  for (const option of available) {
    const value = String(option.value);
    if (!/^[1-9][0-9]{0,9}$/.test(value) || Number(value) > 2147483647) continue;
    result.set(value, {...option, value, label: option.label || `未命名内容（ID ${value}）`, unavailable: false});
  }
  for (const value of selected)
    if (!result.has(value) && !removed.has(value))
      result.set(value, {value, label: `${checking ? '正在核对' : '当前不可选'}（ID ${value}）`, unavailable: true});
  return [...result.values()];
}
export function updateReferenceValue(next: unknown, current: unknown, available: readonly SelectOptionItem[], multiple: boolean, removed: ReadonlySet<string>): {value: string; removed: Set<string>} {
  const before = referenceIds(current, multiple), wanted = referenceIds(next, multiple);
  const allowed = new Set(referenceOptions(available, [], new Set()).map(option => option.value));
  const accepted = wanted.filter(value => allowed.has(value) || before.includes(value) && !removed.has(value));
  // 陈旧单选事件不能把另一个有效选择误清空。
  if (!multiple && wanted.length && !accepted.length)
    return {value: before[0] || '', removed: new Set(removed)};
  const nextRemoved = new Set(removed);
  for (const value of before)
    if (!accepted.includes(value) && !allowed.has(value)) nextRemoved.add(value);
  return {value: multiple ? packMultiValue(accepted) : accepted[0] || '', removed: nextRemoved};
}
