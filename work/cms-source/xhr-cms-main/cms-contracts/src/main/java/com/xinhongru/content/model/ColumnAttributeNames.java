package com.xinhongru.content.model;

import com.xinhongru.constant.WebConst;

/** User-facing names of built-in column flags. */
public final class ColumnAttributeNames {
    private ColumnAttributeNames() {}

    public static String getMenuAttrNameByFieldName(String fieldName) {
        switch (fieldName) {
            case WebConst.HAS_MANAGE_CHANNEL:
                return "后台显示";
            case WebConst.HAS_FRONT_CHANNEL:
                return "前台显示";
            case WebConst.IS_CAN_SHOW_HIDDEN:
                return "显隐控制";
            case WebConst.IS_CAN_ADD_SUB:
                return "允许添加";
            case WebConst.IS_CAN_EDIT:
                return "允许修改";
            case WebConst.IS_CAN_DELETED:
                return "允许删除";
            case WebConst.IS_CAN_CASCADE_DELETED:
                return "级联删除";
            case WebConst.IS_MODULE_MENU:
                return "模块栏目";
            case WebConst.IS_CAN_SORT:
                return "允许排序";
            case WebConst.IS_SUB_MENU:
                return "二级栏目";
            case WebConst.IS_SHOW_CRUMBS:
                return "当前位置";
            case WebConst.IS_LOOP:
                return "循环显示";
            case WebConst.WEBSITE_PUBLIC:
                return "整站公共";
            case WebConst.IS_FORM:
                return "表单栏目";
            default:
                return "未知" + fieldName;
        }
    }
}
