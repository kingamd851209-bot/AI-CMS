package com.xinhongru.web.resonseVo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;

/**
 * @Description @ClassName SeoEntity @Author 永远的神 @Version V1.0.0 @Since 1.0 @Date 2022/4/16 19:45
 */
@Slf4j
@Data
@AllArgsConstructor
@NoArgsConstructor
@Accessors(chain = true)
public class SeoEntity implements Serializable {
    /** SEO标题 */
    private String title;

    /** SEO关键词 */
    private String keywords;

    /** SEO描述 */
    private String description;

    /** 站点名称 */
    private String siteName;
}
