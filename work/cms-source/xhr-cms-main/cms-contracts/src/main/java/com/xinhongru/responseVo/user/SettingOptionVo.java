package com.xinhongru.responseVo.user;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;

/**
 * @Description @ClassName SettingOptionVo @Author 永远的神 @Version V1.0.0 @Since 1.0 @Date 2022/3/27
 * 11:43
 */
@Slf4j
@AllArgsConstructor
@NoArgsConstructor
@Data
@Accessors(chain = true)
public class SettingOptionVo implements Serializable {
    /** 子权限ID */
    private Integer id;

    /** 子权限名称 */
    private String title;

    /** 是否选中 */
    private Boolean checked;
}
