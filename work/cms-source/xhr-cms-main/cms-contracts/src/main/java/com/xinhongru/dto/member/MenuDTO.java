package com.xinhongru.dto.member;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;

/**
 * @Description @Copyright (c) 1998-2022 北京新鸿儒世纪网络技术有限公司 All Rights Reserved. @Url
 * https://www.xinhongru.com @ClassName MenuDTO @Author guoyangyang <519156724@qq.com> @Version
 * V1.0.0 @Since 1.0 @Date 2023/4/15 22:03
 */
@Slf4j
@Data
public class MenuDTO implements Serializable {
    private Integer id;
    private Integer parentId;
    private String className;
}
