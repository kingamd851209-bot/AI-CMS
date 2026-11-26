package com.xinhongru.service.menu.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xinhongru.entity.MenuEntity;
import com.xinhongru.mapper.SystemMenuMapper;
import com.xinhongru.requestDto.menu.MenuSortDto;
import com.xinhongru.service.menu.SystemMenuService;

import lombok.RequiredArgsConstructor;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** 系统菜单服务实现 */
@Service
@RequiredArgsConstructor
public class SystemMenuServiceImpl implements SystemMenuService {
    private final SystemMenuMapper menuMapper;

    @Override
    public List<MenuEntity> getMenuTree() {
        // 查询所有启用的菜单，按 sort_order 排序
        // 返回所有菜单，由前端构建树形结构
        LambdaQueryWrapper<MenuEntity> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper
                .eq(MenuEntity::getEnabled, true)
                .orderByAsc(MenuEntity::getSortOrder)
                .orderByAsc(MenuEntity::getId);

        return menuMapper.selectList(queryWrapper);
    }

    @Override
    public List<MenuEntity> getMenuChildren(Long parentId) {
        return menuMapper.selectChildrenWithState(parentId == null ? 0L : parentId);
    }

    @Override
    public List<MenuEntity> searchMenusWithAncestors(String title, String menuKey) {
        if (StringUtils.isBlank(title) && StringUtils.isBlank(menuKey)) {
            return List.of();
        }

        LambdaQueryWrapper<MenuEntity> matchingQuery = new LambdaQueryWrapper<>();
        matchingQuery
                .like(StringUtils.isNotBlank(title), MenuEntity::getTitle, title)
                .like(StringUtils.isNotBlank(menuKey), MenuEntity::getMenuKey, menuKey);
        List<MenuEntity> matches = menuMapper.selectList(matchingQuery);
        Map<Long, MenuEntity> keptMenus = new HashMap<>();
        for (MenuEntity menu : matches) {
            if (menu.getId() != null) {
                keptMenus.put(menu.getId(), menu);
            }
        }

        Set<Long> parentIds = unresolvedParentIds(matches, keptMenus.keySet());
        while (!parentIds.isEmpty()) {
            List<MenuEntity> parents = menuMapper.selectByIds(parentIds);
            if (parents.isEmpty()) {
                break;
            }
            for (MenuEntity parent : parents) {
                if (parent.getId() != null) {
                    keptMenus.putIfAbsent(parent.getId(), parent);
                }
            }
            parentIds = unresolvedParentIds(parents, keptMenus.keySet());
        }

        Set<Long> parentsWithVisibleChildren =
                keptMenus.values().stream()
                        .map(MenuEntity::getParentId)
                        .filter(parentId -> parentId != null && parentId > 0)
                        .collect(Collectors.toSet());
        List<MenuEntity> result = new ArrayList<>(keptMenus.values());
        result.sort(
                Comparator.comparing(
                                MenuEntity::getSortOrder, Comparator.nullsLast(Integer::compareTo))
                        .thenComparing(MenuEntity::getId, Comparator.nullsLast(Long::compareTo)));
        result.forEach(
                menu -> menu.setHasChildren(parentsWithVisibleChildren.contains(menu.getId())));
        return result;
    }

    private Set<Long> unresolvedParentIds(List<MenuEntity> menus, Set<Long> alreadyResolvedIds) {
        return menus.stream()
                .map(MenuEntity::getParentId)
                .filter(parentId -> parentId != null && parentId > 0)
                .filter(parentId -> !alreadyResolvedIds.contains(parentId))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    @Override
    public Page<MenuEntity> pageMenus(String title, String menuKey, int page, int size) {
        LambdaQueryWrapper<MenuEntity> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper
                .like(StringUtils.isNotBlank(title), MenuEntity::getTitle, title)
                .like(StringUtils.isNotBlank(menuKey), MenuEntity::getMenuKey, menuKey)
                .orderByAsc(MenuEntity::getSortOrder)
                .orderByAsc(MenuEntity::getId);

        Page<MenuEntity> result =
                menuMapper.selectPage(
                        new Page<>(Math.max(page, 1), Math.max(size, 1)), queryWrapper);

        // 回填上级菜单名称（父级可能不在当前页，故单独取全量 id->title）
        Map<Long, String> titleById =
                menuMapper
                        .selectList(
                                new LambdaQueryWrapper<MenuEntity>()
                                        .select(MenuEntity::getId, MenuEntity::getTitle))
                        .stream()
                        .collect(
                                Collectors.toMap(
                                        MenuEntity::getId, MenuEntity::getTitle, (a, b) -> a));
        for (MenuEntity menu : result.getRecords()) {
            Long parentId = menu.getParentId();
            if (parentId == null || parentId == 0) {
                menu.setParentTitle("顶级菜单");
            } else {
                menu.setParentTitle(titleById.getOrDefault(parentId, String.valueOf(parentId)));
            }
        }
        return result;
    }

    @Override
    public MenuEntity getMenuById(Long id) {
        return menuMapper.selectById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MenuEntity createMenu(MenuEntity menu) {
        menuMapper.insert(menu);
        return menu;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MenuEntity updateMenu(Long id, MenuEntity menu) {
        MenuEntity existing = menuMapper.selectById(id);
        if (existing == null) {
            return null;
        }
        menu.setId(id);
        menuMapper.updateById(menu);
        return menu;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteMenu(Long id) {
        // 逻辑删除
        int rows = menuMapper.deleteById(id);
        return rows > 0;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean batchSort(List<MenuSortDto> sortList) {
        for (MenuSortDto dto : sortList) {
            LambdaUpdateWrapper<MenuEntity> updateWrapper = new LambdaUpdateWrapper<>();
            updateWrapper
                    .eq(MenuEntity::getId, dto.getId())
                    .set(MenuEntity::getSortOrder, dto.getSortOrder());

            if (dto.getParentId() != null) {
                updateWrapper.set(MenuEntity::getParentId, dto.getParentId());
            }

            menuMapper.update(null, updateWrapper);
        }
        return true;
    }
}
