package com.xinhongru.responseVo;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.xinhongru.enums.ErrorCodeEnum;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;

/**
 * @Description 全局统一返回结果 @Author 永远的神 @Version V1.0.0 @Since 1.0 @Date 2021/12/3
 */
@Slf4j
// get set
@Data
// 全参构造函数
@AllArgsConstructor
// 无参构造函数
@NoArgsConstructor
// 链式
@Accessors(chain = true)
// 不返回为null的对象
@JsonInclude(JsonInclude.Include.NON_NULL)
@ApiModel
public class ResultVO<T> implements Serializable {
    private static final long serialVersionUID = 6008938560349793255L;

    /** 成功状态 */
    @ApiModelProperty(value = "请求成功状态,根据此字段来判断操作是否成功")
    private Boolean success;

    /** 错误代码 */
    @ApiModelProperty(value = "请求响应状态码")
    private Integer code;

    /** 错误说明 */
    @ApiModelProperty(value = "请求响应说明")
    private String msg;

    /** 泛型对象 */
    @ApiModelProperty(value = "请求响应结果")
    private T data;

    /**
     * 只返成功状态 不返回具体数据
     *
     * @param <T>
     * @return
     */
    public static <T> ResultVO<T> success() {
        return success(null);
    }

    /**
     * 返回成功状态 并返回具体数据
     *
     * @param t
     * @param <T>
     * @return
     */
    public static <T> ResultVO<T> success(T t) {
        ResultVO<T> result = new ResultVO<>();
        result.setSuccess(true)
                .setCode(ErrorCodeEnum.SUCCESSFUL.getCode())
                .setMsg(ErrorCodeEnum.SUCCESSFUL.getMsg());
        if (null != t) {
            if (t instanceof ErrorCodeEnum) {
                ErrorCodeEnum errorCodeEnum = (ErrorCodeEnum) t;
                result.setMsg(errorCodeEnum.getMsg());
            } else {
                result.setData(t);
            }
        }
        return result;
    }

    public static <T> ResultVO<T> saveSuccess(T t) {
        ResultVO<T> result = new ResultVO<>();
        result.setSuccess(true)
                .setCode(ErrorCodeEnum.SAVE_SUCCESS.getCode())
                .setMsg(ErrorCodeEnum.SAVE_SUCCESS.getMsg());
        if (null != t) {
            if (t instanceof ErrorCodeEnum) {
                ErrorCodeEnum errorCodeEnum = (ErrorCodeEnum) t;
                result.setMsg(errorCodeEnum.getMsg());
            } else {
                result.setData(t);
            }
        }
        return result;
    }

    /**
     * 只返回参数异常错误 不返回具体数据
     *
     * @return
     * @param s
     * @param toJSONString
     */
    public static ResultVO fail(String s, String toJSONString) {
        return fail(ErrorCodeEnum.PARAM_ERROR, null);
    }

    public static ResultVO fail(Integer code, String msg) {
        return new ResultVO(false, code, msg, null);
    }

    /**
     * 返回具体的错误类型
     *
     * @param errorCode
     * @return
     */
    public static ResultVO fail(ErrorCodeEnum errorCode) {
        return fail(errorCode, null);
    }

    public static <T> ResultVO fail(T t) {
        return fail(ErrorCodeEnum.PARAM_ERROR, t);
    }

    /**
     * 返回具体的错误类型 和错误的数据对象
     *
     * @param errorCode
     * @param t
     * @param <T>
     * @return
     */
    public static <T> ResultVO<T> fail(ErrorCodeEnum errorCode, T t) {
        ResultVO<T> result = new ResultVO<>();
        result.setSuccess(false).setData(t).setCode(errorCode.getCode()).setMsg(errorCode.getMsg());
        if (t instanceof ErrorCodeEnum) {
            ErrorCodeEnum errorCodeEnum = (ErrorCodeEnum) t;
            if (!errorCode.getCode().equals(errorCode.getCode())) {
                result.setCode(errorCodeEnum.getCode());
                result.setMsg(errorCodeEnum.getMsg());
            }
        } else if (t instanceof String) {
            result.setMsg(String.valueOf(t));
        }
        return result;
    }
}
