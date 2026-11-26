package com.xinhongru.responseVo.builderHtml;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;

/**
 * @Description @ClassName NodeVo @Author 永远的神 @Version V1.0.0 @Since 1.0 @Date 2022/9/1 23:02
 */
@Slf4j
@AllArgsConstructor
@NoArgsConstructor
@Data
@Accessors(chain = true)
public class NodeVo implements Serializable {
    /** 栏目Id */
    private Integer id;

    /** 父栏目Id */
    private Integer pId;

    /** 栏目名称 */
    private String name;

    /** 是否展开 */
    private Boolean open;

    /** 是否禁用 */
    private Boolean chkDisabled;

    /** 无选项 */
    private Boolean nocheck;
}
