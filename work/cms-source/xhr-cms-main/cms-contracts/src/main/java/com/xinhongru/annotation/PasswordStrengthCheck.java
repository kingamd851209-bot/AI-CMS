package com.xinhongru.annotation;

import com.xinhongru.constraintValidator.PasswordStrengthCheckConstraintValidator;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.*;

/**
 * @Description 密码强度验证注解 @ClassName PasswordStrengthCheck @Author 永远的神 @Version V1.0.0 @Since
 * 1.0 @Date 2022/2/17 15:12
 */
@Documented
@Constraint(validatedBy = {PasswordStrengthCheckConstraintValidator.class})
@Target({
    ElementType.METHOD,
    ElementType.FIELD,
    ElementType.ANNOTATION_TYPE,
    ElementType.CONSTRUCTOR,
    ElementType.PARAMETER,
    ElementType.TYPE_USE
})
@Retention(RetentionPolicy.RUNTIME)
@Repeatable(PasswordStrengthCheck.List.class)
public @interface PasswordStrengthCheck {
    String message() default "{jakarta.validation.constraints.PasswordStrengthCheck.message}";

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
        PasswordStrengthCheck[] value();
    }
}
