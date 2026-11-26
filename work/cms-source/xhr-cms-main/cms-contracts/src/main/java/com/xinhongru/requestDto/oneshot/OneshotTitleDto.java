package com.xinhongru.requestDto.oneshot;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;

/**
 * @Description 单篇开关类型对象 @ClassName OneshotDto @Author 永远的神 @Version V1.0.0 @Since 1.0 @Date
 * 2022/3/24 14:42
 */
@Slf4j
@AllArgsConstructor
@NoArgsConstructor
@Data
@Accessors(chain = true)
public class OneshotTitleDto implements Serializable {
    @NotNull(message = "id不能为空")
    @Min(value = 1, message = "id不能小于1")
    private Integer id;

    @NotBlank(message = "栏目名称不能为空")
    private String className;
}
