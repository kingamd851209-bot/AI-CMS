package com.xinhongru.entity;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.xinhongru.common.entity.IntegerEntity;
import com.xinhongru.responseVo.module.ModuleMenuVo;

import io.swagger.annotations.ApiModelProperty;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;

import java.util.Date;
import java.util.List;

/**
 * @Description @ClassName ModuleEntity @Author 永远的神 @Version V1.0.0 @Since 1.0 @Date 2022/4/2 15:07
 */
@Slf4j
@AllArgsConstructor
@NoArgsConstructor
@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
public class ModuleEntity extends IntegerEntity<ModuleEntity> {
    /** 模块名称 */
    @ApiModelProperty(value = "模块名称", required = true)
    @NotBlank(message = "模块名称不能为空")
    private String title;

    /** 模块图片 */
    @ApiModelProperty(value = "模块图片")
    private String photo;

    /** 模块说明 */
    @ApiModelProperty(value = "模块说明")
    private String note;

    /** 关联栏目ID集合 */
    @ApiModelProperty(value = "关联栏目ID集合", required = true)
    @NotBlank(message = "至少需要选择一个栏目作为模板")
    private String menuIds;

    /** 是否子栏目 */
    @ApiModelProperty(value = "是否为子栏目", required = true)
    @NotNull(message = "是否为子栏目不能为空")
    private Boolean isChildren;

    /** 可以使用此模块的栏目集合 */
    @ApiModelProperty(value = "可以使用此模块的栏目集合", required = true)
    @NotBlank(message = "至少需要选择一个【设为模板栏目】的对象")
    private String canAddMenuIds;

    /** 模块创建时间 */
    @ApiModelProperty(value = "可以使用此模块的栏目集合", required = true)
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm", timezone = "GMT+8")
    private Date createTime;

    /** 排序号 */
    private Integer sort = 0;

    /** 启用/禁用 */
    private Boolean status = true;

    /** 删除状态 */
    private Boolean deleted = false;

    @ApiModelProperty(value = "模块栏目对象", hidden = true)
    private List<ModuleMenuVo> moduleMenuVos;
}
