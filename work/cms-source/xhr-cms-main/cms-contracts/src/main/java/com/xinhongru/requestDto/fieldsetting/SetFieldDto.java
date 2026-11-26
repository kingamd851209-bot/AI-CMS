package com.xinhongru.requestDto.fieldsetting;

import com.xinhongru.content.model.ContentFieldDefinition;

import io.swagger.annotations.ApiModelProperty;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * @Description 单篇开关类型对象 @ClassName OneshotDto @Author 永远的神 @Version V1.0.0 @Since 1.0 @Date
 * 2022/3/24 14:42
 */
@Slf4j
@AllArgsConstructor
@NoArgsConstructor
@Data
@Accessors(chain = true)
public class SetFieldDto {
    /** 栏目ID */
    @NotNull(message = "id不能为空")
    @Min(value = 1, message = "id不能小于1")
    private Integer id;

    /** 栏目类型,0为列表，1为单篇 */
    @NotNull(message = "栏目类型不能为空")
    private Integer type;

    /** 所有勾选的字段，允许为空以清空该栏目的字段配置 */
    private List<ContentFieldDefinition> fieldOptions;

    @ApiModelProperty(value = "列表显示字段json", hidden = true)
    private String fieldOptionsJson;

    /** 列表显示字段 */
    private List<ContentFieldDefinition> fieldListDisplay;

    @ApiModelProperty(value = "列表显示字段json", hidden = true)
    private String fieldListDisplayJson;

    /** 搜索显示字段 */
    private List<ContentFieldDefinition> fieldSearch;

    @ApiModelProperty(value = "搜索显示字段json", hidden = true)
    private String fieldSearchJson;
}
