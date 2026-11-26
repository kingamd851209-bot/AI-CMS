package com.xinhongru.responseVo.logVo;

import com.xinhongru.dto.LogDto;
import com.xinhongru.entity.LogEntity;
import com.xinhongru.entity.UserEntity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;
import java.util.List;

/**
 * @Description @ClassName LogHtmlVo @Author 永远的神 @Version V1.0.0 @Since 1.0 @Date 2022/6/4 8:58
 */
@Slf4j
@AllArgsConstructor
@NoArgsConstructor
@Data
@Accessors(chain = true)
public class LogHtmlVo implements Serializable {
    private List<UserEntity> userEntities;
    private List<LogEntity> logEntities;
    private long total;
    private String pageHtml;
    private LogDto logDto;
}
