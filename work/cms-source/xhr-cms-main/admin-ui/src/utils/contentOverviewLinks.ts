import type { RouteListView, SiteServiceStatus } from '@/api/templateRecognition';
import type { MenuNode } from '@/api/uiPackage';

interface RankedContent {
  id: number;
  menuId?: number;
  type?: string;
  detailUrl: string | null;
}

export function contentOverviewLinks(status: SiteServiceStatus, registry: RouteListView, columns: MenuNode[]) {
  let base: URL | undefined;
  if (status.status === 'RUNNING' && status.accessUrl) {
    try {
      const candidate = new URL(status.accessUrl);
      if (/^https?:$/.test(candidate.protocol) && !candidate.username && !candidate.password) base = candidate;
    } catch { /* No visitor link when the service address is invalid. */ }
  }
  const publicIds = new Set(columns.filter(column => column.hasFrontChannel === true).map(column => Number(column.id)));
  const routes = registry.routes.filter(route => route.originalUrl && route.pattern && route.templatePath &&
    publicIds.has(route.columnId) && !registry.conflicts.some(conflict => conflict.pattern === route.pattern));

  return (row: RankedContent) => {
    if (!base) return '';
    const menu = row.type === 'menu';
    const matches = routes.filter(route => {
      if (row.menuId != null && route.columnId !== row.menuId) return false;
      if (menu) return route.type === 'list';
      if (route.type !== 'detail' || !route.originalUrl?.includes('{id}')) return false;
      return route.originalUrl.replaceAll('{id}', String(row.id)).replaceAll('{classId}', String(route.columnId)) === row.detailUrl;
    });
    if (!menu && matches.length !== 1) return '';
    const route = matches.find(route => route.originalUrl === '/') ?? matches[0];
    if (!route?.originalUrl) return '';
    const path = (menu ? route.sampleUrl ?? route.originalUrl : route.originalUrl
      .replaceAll('{id}', String(row.id))
      .replaceAll('{classId}', String(route.columnId))
      .replaceAll('{parentId}', String(route.parentId))
      .replaceAll('${className}', route.columnName)).trim();
    if (!path || /[{}\\]|^\/\/|^[a-z][a-z\d+.-]*:/i.test(path)) return '';
    const url = new URL('/' + path.replace(/^\/+/, ''), base);
    return url.origin === base.origin ? url.href : '';
  };
}
