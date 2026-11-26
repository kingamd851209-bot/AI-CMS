package com.xinhongru.requestDto.menu;

import jakarta.validation.constraints.NotNull;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 菜单排序 DTO */
@AllArgsConstructor
@NoArgsConstructor
@Data
public class MenuSortDto {
    @NotNull(message = "菜单 ID 不能为空")
    private Long id;

    @NotNull(message = "排序号不能为空")
    private Integer sortOrder;

    private Long parentId;
}
