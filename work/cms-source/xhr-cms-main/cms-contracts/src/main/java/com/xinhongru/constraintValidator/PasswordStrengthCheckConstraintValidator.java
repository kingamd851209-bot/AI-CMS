package com.xinhongru.constraintValidator;

import com.xinhongru.annotation.PasswordStrengthCheck;
import com.xinhongru.constant.WebConst;
import com.xinhongru.utils.RsaUtils;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import lombok.extern.slf4j.Slf4j;

import org.apache.commons.lang3.StringUtils;

/**
 * @Description 密码强度验证注解实现类 @ClassName PasswordStrengthCheckConstraintValidator @Author
 * 永远的神 @Version V1.0.0 @Since 1.0 @Date 2022/2/17 15:12
 */
@Slf4j
public class PasswordStrengthCheckConstraintValidator
        implements ConstraintValidator<PasswordStrengthCheck, String> {
    @Override
    public void initialize(PasswordStrengthCheck constraintAnnotation) {
        ConstraintValidator.super.initialize(constraintAnnotation);
    }

    /**
     * 校验密码强度是否符合要求
     *
     * @param value
     * @param constraintValidatorContext
     * @return
     */
    @Override
    public boolean isValid(String value, ConstraintValidatorContext constraintValidatorContext) {
        if (StringUtils.isEmpty(value)) {
            return true;
        } else if (RsaUtils.decryptByPrivateKey(value) == null) {
            return false;
        } else {
            value = RsaUtils.decryptByPrivateKey(value);
            return value.matches(WebConst.PASSWORD_VALIDATE_PATTERN);
        }
    }
}
