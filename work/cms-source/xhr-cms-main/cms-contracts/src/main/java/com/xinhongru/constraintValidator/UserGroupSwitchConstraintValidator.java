package com.xinhongru.constraintValidator;

import com.xinhongru.annotation.MenuSwitch;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import lombok.extern.slf4j.Slf4j;

/**
 * @Description @ClassName MenuSwitchConstraintValidator @Author 永远的神 @Version V1.0.0 @Since
 * 1.0 @Date 2022/3/24 20:29
 */
@Slf4j
public class UserGroupSwitchConstraintValidator implements ConstraintValidator<MenuSwitch, String> {
    @Override
    public boolean isValid(String value, ConstraintValidatorContext constraintValidatorContext) {
        if (value == null) {
            return true;
        } else {
            switch (value) {
                case "Enabled":
                    return true;
                default:
                    return false;
            }
        }
    }
}
