package com.xinhongru.utils;

import lombok.extern.slf4j.Slf4j;

import java.util.*;

/**
 * @Description @ClassName FieldHtmlUtils @Author 永远的神 @Version V1.0.0 @Since 1.0 @Date 2022/3/30
 * 13:23
 */
@Slf4j
public class IconClassUtils {
    private static final List<String> iconClass = new ArrayList<>();

    /** 静态代码块 添加所有icon图标集合 */
    static {
        iconClass.add("fa-flask");
        iconClass.add("fa-flag-checkered");
        iconClass.add("fa-bolt");
        iconClass.add("fa-futbol-o");
        iconClass.add("fa-gavel");
        iconClass.add("fa-glass");
        iconClass.add("fa-handshake-o");
        iconClass.add("fa-hdd-o");
        iconClass.add("fa-map");
        iconClass.add("fa-newspaper-o");
        iconClass.add("fa-paper-plane");
        iconClass.add("fa-recycle");
        iconClass.add("fa-road");
        iconClass.add("fa-sliders");
        iconClass.add("fa-snowflake-o");
        iconClass.add("fa-space-shuttle");
        iconClass.add("fa-suitcase");
        iconClass.add("fa-sun-o");
        iconClass.add("fa-tree");
        iconClass.add("fa-trophy");
        iconClass.add("fa-umbrella");
        iconClass.add("fa-universal-access");
        iconClass.add("fa-rocket");
        iconClass.add("fa-anchor");
        iconClass.add("fa-archive");
        iconClass.add("fa-balance-scale");
        iconClass.add("fa-beer");
    }

    public static String getIconClass() {
        Random random = new Random();
        int randomNumber = random.nextInt(iconClass.size());
        return iconClass.get(randomNumber);
    }
}
