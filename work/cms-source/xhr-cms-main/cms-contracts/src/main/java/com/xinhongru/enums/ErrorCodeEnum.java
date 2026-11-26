package com.xinhongru.enums;

import lombok.extern.slf4j.Slf4j;

/**
 * @Description 异常枚举 @Author 永远的神 @Version V1.0.0 @Since 1.0 @Date 2021/12/3
 */
@Slf4j
public enum ErrorCodeEnum {
    PARAM_ERROR(400001, "参数不正确"),
    SAVE_SUCCESS(400002, "数据保存成功"),
    SAVE_FAIL(400003, "数据保存失败"),
    DELETE_SUCCESS(400004, "数据删除成功"),
    DELETE_FAIL(400005, "数据删除失败"),
    DATA_NOT_FOUND(400006, "找不到相应的数据"),
    HAS_CHILD_DATA(400007, "不能直接删除此栏目，请先删除其子栏目"),
    USER_NOT_LOGIN(400008, "您还未登录,请登录后继续!"),
    UNAUTHORIZED_OPERATION(400009, "系统检测到您试图越权操作，该操作已被拒绝!"),
    SUPER_ADMIN_CANNOT_DELETE(400010, "超级管理员不能被删除!"),
    TWICE_PASSWORD_NOT_THE_SAME(400011, "两次输入的密码不一致"),
    USER_ALREADY_EXISTS(400012, "用户已经存在,请更换用户名"),
    EMAIL_ALREADY_EXISTS(400013, "邮箱已经存在,请更换邮箱"),
    OLD_PASSWORD_ERROR(400014, "旧密码错误"),
    PASSWORD_UPDATE_SUCCESS(400015, "密码修改成功"),
    PASSWORD_UPDATE_FAIL(400016, "密码修改失败"),
    OLD_PASSWORD_NEW_PASSWORD_CANNOT_SAME(400017, "新密码不能与旧密码相同"),
    PASSWORD_LENGTH_MUST_BETWEEN_8_END_24(400018, "密码长度必须介于8-24位"),
    CAN_NOT_SORT(400019, "当前栏目禁止排序"),
    CAN_NOT_EDIT(400020, "当前栏目禁止修改"),
    CAN_NOT_DELETE(400021, "当前栏目禁止删除"),
    MODULE_NOT_SET_MENU_TEMPLATE(400022, "当前模块尚未设置任何栏目模板，无法添加"),
    HAS_RELATION_DATA(400023, "不能直接删除此数据，请先删除其子关联数据"),
    IMAGE_CODE_ERROR(400024, "图形验证码输入错误"),
    IMAGE_CUT_FAIL(400025, "图片裁切出现异常，请稍后重试"),
    FORM_SUBMIT_SUCCESS(400026, "您的信息已成功提交！"),
    TOP_LEVEL_ADMINISTRATOR_GROUP_CANNOT_BE_DELETED(400027, "顶级管理员组不能删除"),
    HAS_CHILD_USER_GROUP_DATA(400028, "不能直接删除此管理员组，请先删除其子管理员组"),
    HAS_USER_IN_GROUP(400029, "不能直接删除此管理员组，请先删除或转移组内的管理员"),
    EXPIRATION_TIME_MUST_BE_GREATER_THAN_ENTRY_INTO_FORCE_TIME(400030, "过期时间必须大于生效时间"),
    OLD_PASSWORD_FORMAT_ERROR(400031, "旧密码非法，密码未经过RSA非对称加密"),
    NEW_PASSWORD_FORMAT_ERROR(400032, "新密码非法，密码未经过RSA非对称加密"),
    PASSWORD_FORMAT_ERROR(400033, "密码非法,密码未经过RSA非对称加密"),
    MENU_DATA_NOT_FOUND(400034, "栏目数据不存在"),
    CLIENT_ABORT_REQUEST(400035, "客户端主动终止请求"),
    HAS_FRONT_CHANNEL_UPDATE_FAIL(400036, "禁止修改此栏目的前台显示状态"),
    CASCADE_CAN_NOT_DELETE(400037, "当前栏目是级联删除栏目，禁止单独删除"),
    MODULE_MENU_CAN_NOT_DELETE(400038, "当前栏目是模块基础模板栏目，禁止删除"),

    REGISTER_EMAIL_IS_EXIST(400039, "此手机号已存在。请直接登录。如果您忘记了密码，请找回"),
    REGISTER_SUCCESS(400040, "祝贺您成功注册"),
    LOGIN_ERROR_MSG(400042, "您的密码已被错误输入5次以上，请在10分钟后重试"),
    MEMBER_NOT_FOUND(400043, "用户不存在"),
    REGISTER_FAIL(400041, "非常抱歉，注册失败"),
    LOGIN_SUCCESS(400050, "登录成功"),

    DISAGREE_WITH_USER_AGREEMENT(400051, "未同意用户协议和版权声明"),
    IS_ADD_ARTICLE_UPDATE_FAIL(400052, "禁止修改此栏目允许会员添加文章状态"),
    SMS_SEND_FAIL(400053, "短信发送失败"),
    SMS_SEND_SUCCESS(400054, "短信发送成功，请注意查收"),
    SMS_CODE_TIMEOUT(400055, "短信验证码已超时，请重新获取"),
    PHONE_NUMBER_MISMATCH(400056, "手机号号码与验证码不匹配"),
    SMS_CODE_ERROR(400057, "短信验证码输入错误"),
    THERE_IS_DATA_UNDER_THE_COLUMN_AND_CANNOT_BE_DELETED(400058, "此栏目下存在数据，将先将数据删除，再删除栏目"),
    EMAIL_SUBSCRIBED(400059, "此邮箱已订阅，无需重复订阅"),
    MENU_NOT_FORM(400062, "当前栏目不是表单栏目，无法提交数据"),
    SYSTEM_ERROR(500, "服务器繁忙，请稍后重试。"),
    UNAUTHORIZED_ERROR(401, "权限不足"),
    SUCCESSFUL(0, "操作成功");
    private Integer code;
    private String msg;

    ErrorCodeEnum(Integer code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    public Integer getCode() {
        return code;
    }

    public void setCode(Integer code) {
        this.code = code;
    }

    public String getMsg() {
        return msg;
    }

    public void setMsg(String msg) {
        this.msg = msg;
    }
}
