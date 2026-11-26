package com.xinhongru.requestDto.fieldHtmlDto;

import com.xinhongru.content.model.ContentFieldDefinition;
import com.xinhongru.entity.OneshotEntity;

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
public class FieldHtmlDto extends ContentFieldDefinition {
    /** 字段值 */
    private String fieldValue;

    /** 栏目ID */
    private Integer classId;

    /** 是否是超级管理员 此字段是专门为了个超级管理员账号快速开发站点使用的，请勿修改 */
    private Boolean isSuperAdmin;

    /** 栏目对象 此字段是专门为了个超级管理员账号快速开发站点使用的，请勿修改 */
    private OneshotEntity oneshotEntity;
}
