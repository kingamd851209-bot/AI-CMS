package com.xinhongru.annotation;

import java.lang.annotation.*;

/**
 * @Description 自定义实体类所需要的bean(Excel属性标题、位置等) @ClassName LowercaseAlphanumericUnderlineCheck @Author
 * 永远的神 @Version V1.0.0 @Since 1.0 @Date 2022/2/17 15:12
 */
@Target({ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ExcelColumnAnnotation {
    /**
     * Excel标题
     *
     * @return
     * @author Lynch
     */
    String value() default "";

    /**
     * Excel从左往右排列位置
     *
     * @return
     * @author Lynch
     */
    int col() default 0;
}
