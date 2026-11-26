export interface NavigationMenu {
  id: number;
  parentId?: number;
  isSubMenu?: boolean;
  hasChildren?: boolean;
}

export function buildAuthorizedNavigation<T extends NavigationMenu>(
  menus: T[],
  hasPermission: (id: number) => boolean,
  rootId?: number
) {
  const byId = new Map(menus.map(menu => [menu.id, menu]));
  const childrenById = new Map<number, T[]>();
  const roots: T[] = [];
  for (const menu of menus) {
    if (menu.id === rootId || !byId.has(menu.parentId ?? 0)) {
      roots.push(menu);
    } else {
      const siblings = childrenById.get(menu.parentId!) ?? [];
      siblings.push(menu);
      childrenById.set(menu.parentId!, siblings);
    }
  }
  const visibleChildren = new Map<number, T[]>();
  let count = 0;
  let firstAuthorized: T | undefined;
  function prune(nodes: T[]): T[] {
    const result: T[] = [];
    for (const menu of nodes) {
      if (menu.isSubMenu) continue;
      const selfAllowed = hasPermission(menu.id);
      if (selfAllowed && menu.id !== rootId && !firstAuthorized) firstAuthorized = menu;
      const children = prune(childrenById.get(menu.id) ?? []);
      if (!selfAllowed && !children.length) continue;
      visibleChildren.set(menu.id, children);
      result.push({ ...menu, children: undefined, hasChildren: children.length > 0 });
      count++;
    }
    return result;
  }
  const visibleRoots = prune(rootId && byId.has(rootId) ? [byId.get(rootId)!] : roots);
  return { roots: visibleRoots, childrenById: visibleChildren, count, firstAuthorized };
}
