package com.xinhongru.dto.vo;

import com.xinhongru.web.resonseVo.FooterEntity;
import com.xinhongru.web.resonseVo.HeaderEntity;
import com.xinhongru.web.resonseVo.SeoEntity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.io.Serializable;

@AllArgsConstructor
@NoArgsConstructor
@Data
@Accessors(chain = true)
public class KeyVo implements Serializable {
    private SeoEntity seoEntity;
    private HeaderEntity headerEntity;
    private FooterEntity footerEntity;
}
