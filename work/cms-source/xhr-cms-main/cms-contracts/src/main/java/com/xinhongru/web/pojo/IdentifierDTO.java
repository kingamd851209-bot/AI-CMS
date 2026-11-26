package com.xinhongru.web.pojo;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.swagger.annotations.ApiModelProperty;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;

/**
 * @author
 * @version V1.0.0
 * @description
 * @className IdentifierDTO
 * @date 2025/2/9 11:32
 * @since 1.0
 */
@Slf4j
@Data
@AllArgsConstructor
@NoArgsConstructor
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class IdentifierDTO implements Serializable {
    private static final long serialVersionUID = 1L;

    @ApiModelProperty(value = "站点Id")
    private Integer siteId;

    @ApiModelProperty(value = "栏目Id")
    private Integer classId;

    @ApiModelProperty(value = "父栏目Id")
    private Integer parentId;

    @ApiModelProperty(value = "分页页码")
    private Integer pageIndex = 1;

    @ApiModelProperty(value = "数据Id", notes = "此字段对应列表类型的id字段")
    private Integer contentId;

    @ApiModelProperty(value = "相关Id")
    private Integer xgid;
}
