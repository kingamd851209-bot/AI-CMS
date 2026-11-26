package com.xinhongru.web.resonseVo;

import com.xinhongru.entity.OneshotEntity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;

/**
 * @Description @ClassName FooterEntity @Author 永远的神 @Version V1.0.0 @Since 1.0 @Date 2022/4/16
 * 20:07
 */
@Slf4j
@Data
@AllArgsConstructor
@NoArgsConstructor
@Accessors(chain = true)
public class FooterEntity implements Serializable {
    private OneshotEntity common;
}
