package com.xinhongru.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;

/**
 * @author
 * @version V1.0.0
 * @description
 * @className LogTableDto
 * @date 2022/11/11 17:49
 * @since 1.0
 */
@Slf4j
@Data
@AllArgsConstructor
@NoArgsConstructor
@Accessors(chain = true)
public class LogTableDto implements Serializable {
    /** 变更字段属性名 */
    private String fieldKey;

    /** 变更字段名 */
    private String fieldName;

    /** 变更前内容 */
    private Object updateBefore;

    /** 变更后内容 */
    private Object updateAfter;
}
