package com.xinhongru.annotation;

import com.xinhongru.constraintValidator.OptionTypeConstraintValidator;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.*;

/**
 * @Description 按钮位置注解 @ClassName LowercaseAlphanumericUnderlineCheck @Author 永远的神 @Version
 * V1.0.0 @Since 1.0 @Date 2022/2/17 15:12
 */
@Documented
@Constraint(validatedBy = {OptionTypeConstraintValidator.class})
@Target({
    ElementType.METHOD,
    ElementType.FIELD,
    ElementType.ANNOTATION_TYPE,
    ElementType.CONSTRUCTOR,
    ElementType.PARAMETER,
    ElementType.TYPE_USE
})
@Retention(RetentionPolicy.RUNTIME)
@Repeatable(OptionType.List.class)
public @interface OptionType {
    String message() default "{jakarta.validation.constraints.OptionType.message}";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    @Target({
        ElementType.METHOD,
        ElementType.FIELD,
        ElementType.ANNOTATION_TYPE,
        ElementType.CONSTRUCTOR,
        ElementType.PARAMETER,
        ElementType.TYPE_USE
    })
    @Retention(RetentionPolicy.RUNTIME)
    @Documented
    @interface List {
        OptionType[] value();
    }
}
