package com.xinhongru.service.menu;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xinhongru.entity.MenuEntity;
import com.xinhongru.requestDto.menu.MenuSortDto;

import java.util.List;

/** 系统菜单服务接口 */
public interface SystemMenuService {
    /**
     * 获取菜单树（按 sort_order 排序）
     *
     * @return 菜单树
     */
    List<MenuEntity> getMenuTree();

    /**
     * 查询指定父菜单的直属子菜单，并标记每个节点是否还有子节点。
     *
     * @param parentId 父菜单 ID，0 表示顶级菜单
     * @return 直属子菜单
     */
    List<MenuEntity> getMenuChildren(Long parentId);

    /**
     * 按名称或标识搜索菜单，结果包含命中节点的完整祖先链。
     *
     * @param title 菜单名称（模糊，可空）
     * @param menuKey 菜单标识（模糊，可空）
     * @return 按树顺序排列的扁平节点集合
     */
    List<MenuEntity> searchMenusWithAncestors(String title, String menuKey);

    /**
     * 分页查询菜单（可按菜单名称、菜单标识模糊过滤），并回填上级菜单名称
     *
     * @param title 菜单名称（模糊，可空）
     * @param menuKey 菜单标识（模糊，可空）
     * @param page 页码，从 1 开始
     * @param size 每页条数
     * @return 分页结果
     */
    Page<MenuEntity> pageMenus(String title, String menuKey, int page, int size);

    /**
     * 根据 ID 获取菜单
     *
     * @param id 菜单 ID
     * @return 菜单实体
     */
    MenuEntity getMenuById(Long id);

    /**
     * 创建菜单
     *
     * @param menu 菜单实体
     * @return 创建后的菜单
     */
    MenuEntity createMenu(MenuEntity menu);

    /**
     * 更新菜单
     *
     * @param id 菜单 ID
     * @param menu 菜单实体
     * @return 更新后的菜单
     */
    MenuEntity updateMenu(Long id, MenuEntity menu);

    /**
     * 删除菜单（逻辑删除）
     *
     * @param id 菜单 ID
     * @return 是否成功
     */
    boolean deleteMenu(Long id);

    /**
     * 批量排序
     *
     * @param sortList 排序列表
     * @return 是否成功
     */
    boolean batchSort(List<MenuSortDto> sortList);
}
