package com.xinhongru.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;

/**
 * @Description @ClassName OneshotDto @Author 永远的神 @Version V1.0.0 @Since 1.0 @Date 2022/3/24 14:42
 */
@Slf4j
@AllArgsConstructor
@NoArgsConstructor
@Data
@Accessors(chain = true)
public class OneshotDto {
    private int parentId;
}
