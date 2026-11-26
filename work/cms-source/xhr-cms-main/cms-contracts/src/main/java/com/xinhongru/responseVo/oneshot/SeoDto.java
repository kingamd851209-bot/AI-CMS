package com.xinhongru.responseVo.oneshot;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;

/**
 * @Description SEO设置响应对象 @ClassName SeoDto @Author 永远的神 @Version V1.0.0 @Since 1.0 @Date 2022/3/25
 * 16:52
 */
@Slf4j
@AllArgsConstructor
@NoArgsConstructor
@Data
@Accessors(chain = true)
public class SeoDto implements Serializable {
    /** 栏目ID */
    private Integer id;

    /** SEO标题 */
    private String seoTitle;

    /** SEO关键词 */
    private String seoKeywords;

    /** SEO描述 */
    private String seoDescription;
}
