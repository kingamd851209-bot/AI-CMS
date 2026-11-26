package com.xinhongru.requestDto.model;

import com.xinhongru.annotation.OptionType;

import jakarta.validation.constraints.Size;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;
import java.util.List;

/**
 * @Description @ClassName OptionDto @Author 永远的神 @Version V1.0.0 @Since 1.0 @Date 2022/4/6 22:33
 */
@Slf4j
@AllArgsConstructor
@NoArgsConstructor
@Data
@Accessors(chain = true)
public class OptionDto implements Serializable {
    /** 操作对象 */
    @Size(min = 1, message = "操作对象不能为空")
    private List<Integer> ids;

    /** 操作类型 */
    @OptionType(message = "操作类型不合法")
    private Integer optionType;
}
