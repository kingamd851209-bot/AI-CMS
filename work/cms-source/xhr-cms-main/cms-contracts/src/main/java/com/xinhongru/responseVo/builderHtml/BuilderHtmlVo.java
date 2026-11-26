package com.xinhongru.responseVo.builderHtml;

import jakarta.validation.constraints.Size;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;
import java.util.List;

/**
 * @Description @ClassName BuilderHtmlVo @Author 永远的神 @Version V1.0.0 @Since 1.0 @Date 2022/9/3
 * 22:24
 */
@Slf4j
@AllArgsConstructor
@NoArgsConstructor
@Data
@Accessors(chain = true)
public class BuilderHtmlVo implements Serializable {
    @Size(min = 1, message = "请至少选择一个需要发布的栏目")
    private List<Integer> menuIds;
}
