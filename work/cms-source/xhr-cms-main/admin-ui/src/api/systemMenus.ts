import { http } from './http';

export interface MenuEntity {
  id: number;
  parentId: number;
  menuKey: string;
  title: string;
  path: string;
  icon?: string;
  category?: string;
  sortOrder: number;
  visible: boolean;
  enabled: boolean;
  requiresSystemAdmin: boolean;
  componentPath?: string;
  createTime?: string;
  updateTime?: string;
  deleted?: boolean;
  parentTitle?: string;
  hasChildren?: boolean;
  children?: MenuEntity[];
}

export interface MenuPageResult {
  list: MenuEntity[];
  total: number;
  page: number;
  size: number;
}

export interface MenuPageQuery {
  title?: string;
  menuKey?: string;
  page?: number;
  size?: number;
}

export interface MenuSortDto {
  id: number;
  sortOrder: number;
  parentId?: number;
}

const BASE_PATH = '/api/admin/v2/menus';

export function getMenuTree(): Promise<MenuEntity[]> {
  return http.get(BASE_PATH);
}

export function getMenuChildren(parentId = 0): Promise<MenuEntity[]> {
  return http.get(`${BASE_PATH}/children`, { params: { parentId } });
}

export function searchMenus(query: Pick<MenuPageQuery, 'title' | 'menuKey'>): Promise<MenuEntity[]> {
  return http.get(`${BASE_PATH}/search`, { params: query });
}

export function getMenuPage(query: MenuPageQuery): Promise<MenuPageResult> {
  return http.get(`${BASE_PATH}/page`, { params: query });
}

export function getMenuById(id: number): Promise<MenuEntity> {
  return http.get(`${BASE_PATH}/${id}`);
}

export function createMenu(menu: Partial<MenuEntity>): Promise<MenuEntity> {
  return http.post(BASE_PATH, menu);
}

export function updateMenu(id: number, menu: Partial<MenuEntity>): Promise<MenuEntity> {
  return http.put(`${BASE_PATH}/${id}`, menu);
}

export function deleteMenu(id: number): Promise<boolean> {
  return http.delete(`${BASE_PATH}/${id}`);
}

export function batchSort(sortList: MenuSortDto[]): Promise<boolean> {
  return http.post(`${BASE_PATH}/sort`, sortList);
}
