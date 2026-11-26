package com.xinhongru.exception;

import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;

/**
 * @author
 * @version V1.0.0
 * @description
 * @className CmsRuntimeException
 * @date 2022/11/19 12:32
 * @since 1.0
 */
@Slf4j
@Data
@NoArgsConstructor
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CmsRuntimeException extends RuntimeException {
    private Integer code;
    private String msg;

    public CmsRuntimeException(Integer code, String msg) {
        this.code = code;
        this.msg = msg;
    }
}
