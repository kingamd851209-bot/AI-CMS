package com.xinhongru.web.resonseVo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * @Description @ClassName DetailsVo @Author 永远的神 @Version V1.0.0 @Since 1.0 @Date 2022/4/17 22:00
 */
@AllArgsConstructor
@NoArgsConstructor
@Data
@Accessors(chain = true)
public class DetailsVo implements Serializable {
    private String nextTitle;
    private String nextUrl;
    private String prevTitle;
    private String prevUrl;
}
