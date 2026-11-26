package com.xinhongru.constant;

/** Created at 2021/8/9. */
public interface ErrorConstant {
    interface Common {
        String PARAM_IS_EMPTY = "参数为空";
        String INVALID_PARAM = "无效的参数";
        String CAN_NOT_FIND_PARAM_TO_CONTIUNE = "找不到参数继续运行";
    }

    interface Auth {
        String USERNAME_PASSWORD_IS_EMPTY = "用户名和密码不可为空";
        String PASSWORD_FORMAT_ERROR = "密码非法,密码未经过RSA非对称加密";
        String USERNAME_PASSWORD_ERROR = "用户名不存在或密码错误";
        String SUPER_ADMIN_NAME_NOT_MATCH = "用户名密码不匹配";
        String NOT_LOGIN = "用户未登录";
        String LOGIN_ERROR_TIP = "登录失败。帐号或密码输入错误";
    }
}
