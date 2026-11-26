package com.xinhongru.api.admin.v2;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xinhongru.api.admin.AdminApiResponse;
import com.xinhongru.entity.MenuEntity;
import com.xinhongru.permission.AdminPermissionService;
import com.xinhongru.permission.AdminPermissionSnapshot;
import com.xinhongru.requestDto.menu.MenuSortDto;
import com.xinhongru.service.menu.SystemMenuService;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** 系统菜单管理 API */
@Api(tags = "系统菜单管理")
@RestController
@RequestMapping("/api/admin/v2/menus")
@RequiredArgsConstructor
public class MenuAdminController {
    private final SystemMenuService menuService;

    private AdminPermissionService permissionService;

    @Autowired
    public void setPermissionService(AdminPermissionService permissionService) {
        this.permissionService = permissionService;
    }

    @ApiOperation("获取菜单树")
    @GetMapping
    public AdminApiResponse<List<MenuEntity>> getMenuTree(HttpServletRequest request) {
        List<MenuEntity> all = menuService.getMenuTree();
        AdminPermissionSnapshot snapshot = permissionService.snapshot(request);
        if (snapshot.isAll()) {
            return AdminApiResponse.success(all);
        }
        Set<Integer> allowed = snapshot.systemMenuIds();
        // 组未配置 menu_setting → 严格隐藏（非超管只显示工作台等，由前端回退基础菜单）
        if (allowed == null || allowed.isEmpty()) {
            return AdminApiResponse.success(List.of());
        }
        Map<Long, MenuEntity> byId =
                all.stream()
                        .collect(Collectors.toMap(MenuEntity::getId, menu -> menu, (a, b) -> a));
        Set<Long> keep = new LinkedHashSet<>();
        for (MenuEntity menu : all) {
            if (menu.getId() != null && allowed.contains(menu.getId().intValue())) {
                keep.add(menu.getId());
            }
        }
        // 补全祖先链：选中子菜单时其父级必须一并显示
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Long id : new ArrayList<>(keep)) {
                MenuEntity menu = byId.get(id);
                if (menu != null
                        && menu.getParentId() != null
                        && menu.getParentId() > 0
                        && !keep.contains(menu.getParentId())) {
                    keep.add(menu.getParentId());
                    changed = true;
                }
            }
        }
        return AdminApiResponse.success(
                all.stream()
                        .filter(menu -> menu.getId() != null && keep.contains(menu.getId()))
                        .toList());
    }

    @ApiOperation("按父菜单获取直属子节点")
    @GetMapping("/children")
    public AdminApiResponse<List<MenuEntity>> getMenuChildren(
            @RequestParam(defaultValue = "0") Long parentId) {
        return AdminApiResponse.success(menuService.getMenuChildren(parentId));
    }

    @ApiOperation("搜索菜单并补全祖先链")
    @GetMapping("/search")
    public AdminApiResponse<List<MenuEntity>> searchMenus(
            @RequestParam(required = false) String title,
            @RequestParam(required = false) String menuKey) {
        return AdminApiResponse.success(menuService.searchMenusWithAncestors(title, menuKey));
    }

    @ApiOperation("分页查询菜单（含上级菜单）")
    @GetMapping("/page")
    public AdminApiResponse<Map<String, Object>> pageMenus(
            @RequestParam(required = false) String title,
            @RequestParam(required = false) String menuKey,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        Page<MenuEntity> result = menuService.pageMenus(title, menuKey, page, size);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("list", result.getRecords());
        body.put("total", result.getTotal());
        body.put("page", page);
        body.put("size", size);
        return AdminApiResponse.success(body);
    }

    @ApiOperation("获取单个菜单")
    @GetMapping("/{id}")
    public AdminApiResponse<MenuEntity> getMenuById(@PathVariable Long id) {
        MenuEntity menu = menuService.getMenuById(id);
        if (menu == null) {
            throw new IllegalArgumentException("菜单不存在");
        }
        return AdminApiResponse.success(menu);
    }

    @ApiOperation("创建菜单")
    @PostMapping
    public AdminApiResponse<MenuEntity> createMenu(@Valid @RequestBody MenuEntity menu) {
        MenuEntity created = menuService.createMenu(menu);
        return AdminApiResponse.success(created);
    }

    @ApiOperation("更新菜单")
    @PutMapping("/{id}")
    public AdminApiResponse<MenuEntity> updateMenu(
            @PathVariable Long id, @Valid @RequestBody MenuEntity menu) {
        MenuEntity updated = menuService.updateMenu(id, menu);
        if (updated == null) {
            throw new IllegalArgumentException("菜单不存在");
        }
        return AdminApiResponse.success(updated);
    }

    @ApiOperation("删除菜单")
    @DeleteMapping("/{id}")
    public AdminApiResponse<Boolean> deleteMenu(@PathVariable Long id) {
        boolean result = menuService.deleteMenu(id);
        return AdminApiResponse.success(result);
    }

    @ApiOperation("批量排序")
    @PostMapping("/sort")
    public AdminApiResponse<Boolean> batchSort(@Valid @RequestBody List<MenuSortDto> sortList) {
        boolean result = menuService.batchSort(sortList);
        return AdminApiResponse.success(result);
    }
}
