package com.xinhongru.web.resonseVo;

import com.xinhongru.dto.vo.KeyVo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

/**
 * @Description @ClassName ProductVo @Author 永远的神 @Version V1.0.0 @Since 1.0 @Date 2022/4/17 13:30
 */
@AllArgsConstructor
@NoArgsConstructor
@Data
@Accessors(chain = true)
public class CommonVo extends KeyVo {
    private IdEntity idEntity;
}
