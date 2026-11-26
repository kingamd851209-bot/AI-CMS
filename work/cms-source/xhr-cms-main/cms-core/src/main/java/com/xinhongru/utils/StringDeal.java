package com.xinhongru.utils;

import com.xinhongru.common.reflect.EntityFields;
import com.xinhongru.constant.WebConst;
import com.xinhongru.content.model.ContentFieldDefinition;
import com.xinhongru.dto.LogTableDto;

import jakarta.servlet.http.HttpServletRequest;

import lombok.extern.slf4j.Slf4j;

import org.apache.commons.collections4.CollectionUtils;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * @Description @ClassName StringDeal @Author 永远的神 @Version V1.0.0 @Since 1.0 @Date 2022/4/23 10:18
 */
@Slf4j
public final class StringDeal {
    /**
     * 格式化日期
     *
     * @param date
     * @param pattern
     * @return
     */
    public static String getDateString(Date date, String pattern) {
        SimpleDateFormat simpleDateFormat = new SimpleDateFormat(pattern);
        return simpleDateFormat.format(date);
    }

    public static String getDateString(LocalDateTime date, String pattern) {
        log.debug("StringDeal - getDateString - date:{}", date);
        log.debug("StringDeal - getDateString - pattern:{}", pattern);
        if (date == null || pattern == null) {
            return "";
        }
        return date.format(DateTimeFormatter.ofPattern(pattern));
    }

    public static String getDateTimeString(Date date, String pattern) {
        return getDateString(date, pattern);
    }

    public static String getDateTimeString(Date date) {
        return getDateTimeString(date, "yyyy-MM-dd HH:mm:ss");
    }

    /**
     * 格式化日期
     *
     * @param date
     * @return
     */
    public static String getDateString(Date date) {
        return getDateString(date, "yyyy-MM-dd");
    }

    public static String getDateString(LocalDateTime date) {
        return getDateString(date, "yyyy-MM-dd");
    }

    /**
     * @return 返回当前时间的 yyyyMMddHHmmss_SSSS
     */
    public static String getFileName() {
        return getDateString(new Date(), "yyyyMMddHHmmss_SSSS");
    }

    /**
     * double 类型格式化
     *
     * @param value
     * @param scale
     * @param roundingMode
     * @return
     */
    public static double formatDouble(double value, int scale, RoundingMode roundingMode) {
        return new BigDecimal(value).setScale(scale, roundingMode).doubleValue();
    }

    /**
     * double 类型四舍五入
     *
     * @param value
     * @param scale
     * @return
     */
    public static double formatDouble(double value, int scale) {
        return formatDouble(value, scale, RoundingMode.HALF_UP);
    }

    /**
     * double 类型四舍五入 保留一位小数
     *
     * @param value
     * @return
     */
    public static double formatDouble(double value) {
        return formatDouble(value, 1);
    }

    /**
     * 判断两个对象中的值哪些修改了
     *
     * @param object1
     * @param object2
     * @param fieldSettingEntityList 字段信息
     * @param <T>
     * @return
     */
    public static <T> Queue<LogTableDto> compareDifferences(
            T object1, T object2, List<ContentFieldDefinition> fieldSettingEntityList) {
        Queue<LogTableDto> logTableDtoList = new LinkedList<>();

        Map<String, ContentFieldDefinition> fieldSettingEntityMap = new HashMap<>();
        if (CollectionUtils.isNotEmpty(fieldSettingEntityList)) {
            fieldSettingEntityMap =
                    fieldSettingEntityList.stream()
                            .collect(Collectors.toMap(s -> s.getFieldName(), s -> s));
        }

        // 原始数据为空
        if (object1 == null) {
            Class c2 = object2.getClass();
            // 获取该类中的全部属性
            Field[] fields = EntityFields.all(c2);
            for (Field field : fields) {
                field.setAccessible(true);
                ContentFieldDefinition fieldSettingEntity =
                        fieldSettingEntityMap.get(field.getName());
                if (fieldSettingEntity == null) {
                    continue;
                }
                try {
                    // 获取修改后对象属性的值
                    Object val = field.get(object2);
                    logTableDtoList.add(
                            new LogTableDto()
                                    .setFieldName(fieldSettingEntity.getTitle())
                                    .setUpdateBefore("新增")
                                    .setUpdateAfter(val));
                } catch (IllegalAccessException e) {
                    e.printStackTrace();
                }
            }
            return logTableDtoList;
        }

        // 获取对象的class
        Class c1 = object1.getClass();
        // 获取该类中的全部属性
        Field[] fields1 = EntityFields.all(c1);
        Object tempValue1, tempValue2;
        int i;
        for (i = 0; i < fields1.length; i++) {
            fields1[i].setAccessible(true);
            try {
                // 获取两个对象该属性的值
                tempValue1 = fields1[i].get(object1);
                tempValue2 = fields1[i].get(object2);
                LogTableDto logTableDto = new LogTableDto(); // 对比信息
                if (tempValue2 != null && tempValue1 != null && !tempValue1.equals(tempValue2)) {
                    logTableDto.setUpdateBefore(tempValue1);
                    logTableDto.setUpdateAfter(tempValue2);
                } else {
                    logTableDto.setUpdateBefore(tempValue1);
                    logTableDto.setUpdateAfter("无变更");
                }
                ContentFieldDefinition fieldSettingEntity =
                        fieldSettingEntityMap.get(fields1[i].getName());
                if (fieldSettingEntity == null) {
                    continue;
                }
                logTableDto.setFieldName(fieldSettingEntity.getTitle());
                logTableDtoList.add(logTableDto);
            } catch (IllegalAccessException e) {
                e.printStackTrace();
            }
        }
        if (object1 instanceof com.xinhongru.entity.OneshotEntity before
                && object2 instanceof com.xinhongru.entity.OneshotEntity after) {
            for (ContentFieldDefinition definition : fieldSettingEntityList) {
                String name = definition.getFieldName();
                if (!com.xinhongru.content.model.ContentExtensionFields.isName(name)) continue;
                Object oldValue = before.getExtensionValues().get(name);
                Object newValue = after.getExtensionValues().get(name);
                if (!Objects.equals(oldValue, newValue))
                    logTableDtoList.add(
                            new LogTableDto()
                                    .setFieldKey(name)
                                    .setFieldName(definition.getTitle())
                                    .setUpdateBefore(oldValue)
                                    .setUpdateAfter(newValue));
            }
        }
        return logTableDtoList;
    }

    /**
     * 两个数相除 取百分比
     *
     * @param value 除数
     * @param count 被除数
     * @param digits 保留小数位数
     * @return 百分比值 不含百分号
     */
    public static String getPercent(int value, int count, int digits) {
        if (count <= 0) {
            throw new RuntimeException("被除数不能小于等于0");
        }
        if (value < 0) {
            throw new RuntimeException("除数不能小于0");
        }
        if (digits < 0) {
            throw new RuntimeException("保留小数位数不能小于0");
        }
        return numberFormat((float) value / (float) count * 100, 2);
    }

    public static String numberFormat(double value, int digits) {
        // 创建一个数值格式化对象
        NumberFormat numberFormat = NumberFormat.getInstance();
        // 设置精确到小数点后2位
        numberFormat.setMaximumFractionDigits(digits);
        return numberFormat.format(value);
    }

    /**
     * 毫秒时间转换为 时分秒
     *
     * @param millis
     * @return
     */
    public static String getTimeDurationString(long millis) {
        Duration duration = Duration.ofMillis(millis);
        long seconds = duration.getSeconds();
        long hours = seconds / WebConst.SECONDS_PER_HOUR;
        int minutes = (int) ((seconds % WebConst.SECONDS_PER_HOUR) / WebConst.SECONDS_PER_MINUTE);
        int secs = (int) (seconds % WebConst.SECONDS_PER_MINUTE);
        StringBuilder buf = new StringBuilder();
        if (hours != 0) {
            buf.append(hours).append("小时");
        }
        if (minutes != 0) {
            buf.append(minutes).append("分");
        }
        buf.append(secs).append("秒");
        return buf.toString();
    }

    /**
     * 获取当前请求的域名
     *
     * @param httpServletRequest
     * @return https://www.xinhognru.com 最后不带/
     */
    public static String getDomain(HttpServletRequest httpServletRequest) {
        String domain = httpServletRequest.getScheme() + "://" + httpServletRequest.getServerName();
        int serverPort = httpServletRequest.getServerPort();
        if (serverPort != WebConst.HTTP_PORT && serverPort != WebConst.HTTPS_PORT) {
            domain += ":" + serverPort;
        }
        return domain;
    }

    /**
     * 驼峰转下划线分隔
     *
     * @param camelCaseString
     * @return
     */
    public static String convertToUnderscore(String camelCaseString) {
        if (camelCaseString == null || camelCaseString.isEmpty()) {
            return camelCaseString;
        }

        StringBuilder underscoreString = new StringBuilder();
        for (int i = 0; i < camelCaseString.length(); i++) {
            char currentChar = camelCaseString.charAt(i);
            // 在大写字母前面添加下划线，但不在字符串的第一个字符前添加
            if (i > 0 && Character.isUpperCase(currentChar)) {
                underscoreString.append('_');
            }
            // 将字符转换为小写（如果需要的话）
            // 如果不需要全部小写，可以使用：underscoreString.append(currentChar);
            underscoreString.append(Character.toLowerCase(currentChar));
        }

        return underscoreString.toString();
    }
}
