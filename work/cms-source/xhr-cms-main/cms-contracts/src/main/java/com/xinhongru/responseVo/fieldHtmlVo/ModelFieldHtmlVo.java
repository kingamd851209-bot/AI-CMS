package com.xinhongru.responseVo.fieldHtmlVo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;

/**
 * @Description @ClassName FieldHtmlVo @Author 永远的神 @Version V1.0.0 @Since 1.0 @Date 2022/3/30 20:54
 */
@Slf4j
@AllArgsConstructor
@NoArgsConstructor
@Data
@Accessors(chain = true)
public class ModelFieldHtmlVo extends FieldHtmlVo {
    /** 栏目ID */
    private Integer classid;

    /** 关联数据id */
    private Integer xgid;

    /** 父级ID */
    private Integer parentId;

    /** 父级相关ID */
    private Integer pXgid;
}
