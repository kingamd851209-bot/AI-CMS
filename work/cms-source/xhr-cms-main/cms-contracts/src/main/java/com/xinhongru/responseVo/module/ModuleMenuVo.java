package com.xinhongru.responseVo.module;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;

/**
 * @Description 模块栏目对象 @ClassName ModuleMenuVo @Author 永远的神 @Version V1.0.0 @Since 1.0 @Date
 * 2022/4/2 19:15
 */
@Slf4j
@AllArgsConstructor
@NoArgsConstructor
@Data
@Accessors(chain = true)
public class ModuleMenuVo implements Serializable {
    /** 栏目ID */
    private Integer id;

    /** 栏目名称 */
    private String className;

    /** 父级栏目ID 顶级为0 */
    private int parentId;

    /** 排序号 默认为0 */
    private int sort;

    /** 是否可以添加子栏目 */
    private Boolean isCanAddSub;
}
