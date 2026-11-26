package com.xinhongru.requestDto.fieldDisplayDto;

import com.xinhongru.content.model.ContentFieldDefinition;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;

/**
 * @Description @ClassName FieldHtmlDto @Author 永远的神 @Version V1.0.0 @Since 1.0 @Date 2022/3/30
 * 13:24
 */
@Slf4j
@AllArgsConstructor
@NoArgsConstructor
@Data
@Accessors(chain = true)
public class FieldDisplayDto extends ContentFieldDefinition {
    private String fieldName;
}
