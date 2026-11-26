package com.xinhongru.responseVo.searchHtml;

import com.xinhongru.dto.cond.ContentCond;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;
import java.util.Map;

/**
 * @Description @ClassName FieldHtmlVo @Author 永远的神 @Version V1.0.0 @Since 1.0 @Date 2022/3/30 20:54
 */
@Slf4j
@AllArgsConstructor
@NoArgsConstructor
@Data
@Accessors(chain = true)
public class SearchHtmlVo implements Serializable {
    /** 返回拼接的搜索条件 */
    private ContentCond contentCond;

    /** 返回搜索条件的健值对 */
    private Map map;

    /** 返回带条件的分页参数 */
    private String pageUrlCondition;
}
