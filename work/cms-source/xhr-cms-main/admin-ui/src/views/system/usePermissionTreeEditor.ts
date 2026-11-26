import { nextTick, ref } from 'vue';

export interface OperationOption {
  id: number;
  title: string;
}

export interface PermissionTreeNode {
  id?: number;
  parentId?: number;
  key?: string;
  label?: string;
  className?: string;
  scope?: string;
  children?: PermissionTreeNode[];
  operationOptions?: OperationOption[];
}

export interface PermissionTreeRef {
  getCheckedKeys: () => Array<string | number>;
  setCheckedKeys: (keys: Array<string | number>) => void;
  setChecked: (key: string | number, checked: boolean, deep?: boolean) => void;
  getNode?: (key: string | number) => { checked: boolean; indeterminate: boolean } | undefined;
}

/**
 * 管理员组「栏目权限树」编辑器：负责站点栏目 el-tree 的勾选同步与 groupSetting 合并。
 * 逻辑从 UserGroupPermissionPanel 提取，供管理员组管理页复用。
 */
export function usePermissionTreeEditor(treeData: () => PermissionTreeNode[], showDescendantSelection = false) {
  const treeRef = ref<PermissionTreeRef | null>(null);

  function syncIndeterminate() {
    const tree = treeRef.value;
    if (!showDescendantSelection || !tree?.getNode) return;
    function visit(nodes: PermissionTreeNode[]): boolean {
      let anySelected = false;
      for (const data of nodes) {
        const descendantSelected = visit(data.children ?? []);
        const node = data.id == null ? undefined : tree!.getNode!(data.id);
        if (node) node.indeterminate = !node.checked && descendantSelected;
        anySelected = anySelected || Boolean(node?.checked) || descendantSelected;
      }
      return anySelected;
    }
    visit(treeData());
  }

  async function syncCheckedKeys(groupSetting?: string) {
    await nextTick();
    const data = treeData();
    if (data.length && treeRef.value) {
      treeRef.value.setCheckedKeys(settingToKeys(groupSetting));
      syncIndeterminate();
    }
  }

  /** 合并 el-tree 勾选 + 菜单/全局权限 + 历史未知权限，生成新的 groupSetting 串 */
  function checkedGroupSetting(groupSetting: string, menuKeys: string[], globalKeys: string[]): string {
    const existingKeys = stringSettingKeys(groupSetting);
    const data = treeData();
    if (!data.length || !treeRef.value) {
      return uniqueKeys([...existingKeys, ...menuKeys, ...globalKeys]).join(',') || '0';
    }
    const currentTreeKeys = new Set(flattenPermissionIds(data).map(String));
    const selectedKeys = (treeRef.value.getCheckedKeys() ?? []).map(String).filter((key) => currentTreeKeys.has(key));
    const selectedSet = new Set(selectedKeys);
    const existingSet = new Set(existingKeys);
    const merged = [
      ...existingKeys.filter((key) => !currentTreeKeys.has(key) || selectedSet.has(key)),
      ...selectedKeys.filter((key) => !existingSet.has(key)),
      ...menuKeys,
      ...globalKeys
    ];
    return uniqueKeys(merged).join(',') || '0';
  }

  function permissionLabel(key: string): string {
    const data = treeData();
    return findNodeLabel(data, Number(key)) ?? `栏目 #${key}`;
  }

  return { treeRef, syncCheckedKeys, syncIndeterminate, checkedGroupSetting, permissionLabel };
}

export function settingToKeys(value?: string): number[] {
  return stringSettingKeys(value)
    .map((item) => Number(item))
    .filter((item) => Number.isFinite(item) && item > 0);
}

export function stringSettingKeys(value?: string): string[] {
  return String(value || '')
    .split(/[\n,]/)
    .map((item) => item.trim())
    .filter((item) => item && item !== '0');
}

export function uniqueKeys(keys: string[]): string[] {
  return Array.from(new Set(keys.filter(Boolean)));
}

export function flattenPermissionIds(items: PermissionTreeNode[]): number[] {
  return items.flatMap((item) =>
    (item.id == null ? [] : [item.id, ...flattenPermissionIds(item.children ?? [])]).filter((id): id is number => typeof id === 'number')
  );
}

export function isUnknownPermissionKey(key: string, knownKeys: Set<string>): boolean {
  return !knownKeys.has(key) && !(Number.isInteger(Number(key)) && Number(key) > 0);
}

export function otherSettingByKey(groupSetting?: string, otherSetting?: string): Map<string, string> {
  const keys = stringSettingKeys(groupSetting);
  const segments = String(otherSetting || '').split(';');
  return new Map(keys.map((key, index) => [key, segments[index] ?? '']));
}

/** 将 otherSetting 解析为「栏目id → 已勾选操作权限id」映射（用于回显） */
export function otherSettingOptions(groupSetting?: string, otherSetting?: string): Map<number, number[]> {
  const keys = stringSettingKeys(groupSetting);
  const segments = String(otherSetting || '').split(';');
  const result = new Map<number, number[]>();
  keys.forEach((key, index) => {
    const num = Number(key);
    if (!Number.isInteger(num)) {
      return;
    }
    const segment = segments[index] ?? '';
    result.set(num, segment.split(',').map((item) => item.trim()).filter((item) => item !== '').map((item) => Number(item)));
  });
  return result;
}

function findNodeLabel(items: PermissionTreeNode[], id: number): string | undefined {
  for (const item of items) {
    if (item.id === id) {
      return item.label;
    }
    const child = findNodeLabel(item.children ?? [], id);
    if (child) {
      return child;
    }
  }
  return undefined;
}
