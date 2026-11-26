package com.xinhongru.constraintValidator;

import com.xinhongru.annotation.MenuSwitch;
import com.xinhongru.constant.WebConst;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import lombok.extern.slf4j.Slf4j;

/**
 * @Description @ClassName MenuSwitchConstraintValidator @Author 永远的神 @Version V1.0.0 @Since
 * 1.0 @Date 2022/3/24 20:29
 */
@Slf4j
public class MenuSwitchConstraintValidator implements ConstraintValidator<MenuSwitch, String> {
    @Override
    public boolean isValid(String value, ConstraintValidatorContext constraintValidatorContext) {
        if (value == null) {
            return true;
        } else {
            switch (value) {
                case WebConst.HAS_MANAGE_CHANNEL:
                case WebConst.HAS_FRONT_CHANNEL:
                case WebConst.IS_CAN_SHOW_HIDDEN:
                case WebConst.IS_CAN_ADD_SUB:
                case WebConst.IS_CAN_EDIT:
                case WebConst.IS_CAN_DELETED:
                case WebConst.IS_CAN_CASCADE_DELETED:
                case WebConst.IS_MODULE_MENU:
                case WebConst.IS_CAN_SORT:
                case WebConst.IS_SUB_MENU:
                case WebConst.IS_SHOW_CRUMBS:
                case WebConst.IS_LOOP:
                case WebConst.WEBSITE_PUBLIC:
                case WebConst.IS_ADD_ARTICLE:
                case WebConst.IS_CAN_ADD_ARTICLE:
                case WebConst.IS_FORM:
                    return true;
                default:
                    return false;
            }
        }
    }
}
