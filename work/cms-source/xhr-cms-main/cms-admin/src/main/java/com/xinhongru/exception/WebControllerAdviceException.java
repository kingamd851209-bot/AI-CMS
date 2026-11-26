package com.xinhongru.exception;

import com.xinhongru.enums.ErrorCodeEnum;
import com.xinhongru.responseVo.ResultVO;

import jakarta.validation.ConstraintViolationException;

import lombok.extern.slf4j.Slf4j;

import org.apache.catalina.connector.ClientAbortException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * @Description @ClassName WebControllerAdviceException @Author 永远的神 @Version V1.0.0 @Since
 * 1.0 @Date 2022/3/24 20:59
 */
@Slf4j
@ResponseBody
@ControllerAdvice
public class WebControllerAdviceException {
    // 验证写在controller层上面抛出的异常为MethodArgumentNotValidException
    @ExceptionHandler
    public ResultVO exceptionHandler(MethodArgumentNotValidException e) {
        // 返回第一个错误信息
        return ResultVO.fail(
                e.getBindingResult().getFieldErrors().stream()
                        .findFirst()
                        .get()
                        .getDefaultMessage());
    }

    @ExceptionHandler
    public ResultVO exceptionHandler(ConstraintViolationException e) {
        // 返回第一个错误信息
        return ResultVO.fail(e.getConstraintViolations().stream().findFirst().get().getMessage());
    }

    @ExceptionHandler
    public ResultVO exceptionHandler(IllegalStateException e) {
        return ResultVO.fail(ErrorCodeEnum.PARAM_ERROR, e.getMessage());
    }

    /**
     * 客户端主动终止请求异常
     *
     * @param e
     * @return
     */
    @ExceptionHandler
    public ResultVO exceptionHandler(ClientAbortException e) {
        return ResultVO.fail(ErrorCodeEnum.CLIENT_ABORT_REQUEST, e.getMessage());
    }

    @ExceptionHandler
    public ResultVO exceptionHandler(CmsRuntimeException e) {
        return ResultVO.fail(e.getCode(), e.getMsg());
    }
}
