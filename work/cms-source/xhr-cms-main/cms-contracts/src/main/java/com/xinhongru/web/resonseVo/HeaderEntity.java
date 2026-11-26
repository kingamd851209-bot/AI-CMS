package com.xinhongru.web.resonseVo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;

/**
 * @Description @ClassName HeaderEntity @Author 永远的神 @Version V1.0.0 @Since 1.0 @Date 2022/4/16
 * 20:07
 */
@Slf4j
@Data
@AllArgsConstructor
@NoArgsConstructor
@Accessors(chain = true)
public class HeaderEntity {
    private String logo1;
    private String logo2;
    private IdEntity idEntity;
}
