package com.xinhongru.constraintValidator;

import com.xinhongru.annotation.OptionType;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import lombok.extern.slf4j.Slf4j;

/**
 * @Description @ClassName MenuSwitchConstraintValidator @Author 永远的神 @Version V1.0.0 @Since
 * 1.0 @Date 2022/3/24 20:29
 */
@Slf4j
public class OptionTypeConstraintValidator implements ConstraintValidator<OptionType, Integer> {
    @Override
    public boolean isValid(Integer value, ConstraintValidatorContext constraintValidatorContext) {
        return value == null || (value >= 1 && value <= 27);
    }
}
