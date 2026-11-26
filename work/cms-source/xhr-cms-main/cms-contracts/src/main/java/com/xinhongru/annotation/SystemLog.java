package com.xinhongru.annotation;

import java.lang.annotation.*;

/** 系统日志注解 */
@Target({ElementType.PARAMETER, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface SystemLog {
    String value();

    /**
     * 栏目Id
     *
     * @return
     */
    int classId() default 0;

    /**
     * 允许操作的栏目Id集合
     *
     * @return
     */
    int[] classIds() default {};

    /**
     * classId字段参数名
     *
     * @return
     */
    String classIdParamName() default "";
}
