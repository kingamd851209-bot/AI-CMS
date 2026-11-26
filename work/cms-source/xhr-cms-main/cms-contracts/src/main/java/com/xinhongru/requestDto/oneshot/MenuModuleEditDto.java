package com.xinhongru.requestDto.oneshot;

import com.xinhongru.entity.BusinessEntity;
import com.xinhongru.entity.ModuleEntity;

import io.swagger.annotations.ApiModelProperty;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;
import java.util.List;

/**
 * @Description 栏目添加、修改模块请求对象 @ClassName EditOneshotDto @Author 永远的神 @Version V1.0.0 @Since
 * 1.0 @Date 2022/3/26 11:46
 */
@Slf4j
@AllArgsConstructor
@NoArgsConstructor
@Data
@Accessors(chain = true)
public class MenuModuleEditDto implements Serializable {
    /** 目标父级栏目ID */
    @ApiModelProperty(value = "目标父级栏目ID")
    @NotNull(message = "不合法的请求，系统已拒绝")
    private Integer parentId;

    /** 栏目名称 */
    @ApiModelProperty(value = "栏目名称")
    @NotBlank(message = "栏目名称不能为空")
    private String className;

    /** 栏目英文名称 */
    @ApiModelProperty(value = "栏目英文名称")
    private String classEnName;

    /** 父级ID */
    @ApiModelProperty(value = "模块ID")
    @NotNull(message = "请选择模块")
    @Min(value = 0, message = "模块ID不能小于0")
    private Integer moduleId;

    /** 模块对象集合 */
    private List<ModuleEntity> moduleEntities;

    /** 专题栏目显示位置 */
    private String showPosition;

    //    @ApiModelProperty(value = "子公司域名")
    //    @NotBlank(message = "子公司域名不能为空")
    //    private String siteDomain;
    //
    //    @ApiModelProperty(value = "业务分类Id")
    //    @NotNull(message = "请选择业务分类")
    //    @Min(value = 0, message = "业务分类ID不能小于0")
    //    private String businessId;

    /** 业务分类对象集合 */
    private List<BusinessEntity> businessEntities;
}
