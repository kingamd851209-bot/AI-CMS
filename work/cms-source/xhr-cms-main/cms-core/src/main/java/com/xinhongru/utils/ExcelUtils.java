package com.xinhongru.utils;

import com.xinhongru.annotation.ExcelColumnAnnotation;
import com.xinhongru.common.reflect.EntityFields;
import com.xinhongru.content.model.ContentFieldDefinition;
import com.xinhongru.entity.ContentEntity;

import jakarta.servlet.http.HttpServletResponse;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.CharUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.apache.poi.hssf.usermodel.HSSFCellStyle;
import org.apache.poi.hssf.usermodel.HSSFDateUtil;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ExcelUtils {
    private static final Logger log = LoggerFactory.getLogger(ExcelUtils.class);
    private static final String EXCEL2003 = "xls";
    private static final String EXCEL2007 = "xlsx";

    public static <T> List<T> readExcel(String path, Class<T> cls, MultipartFile file) {
        String fileName = file.getOriginalFilename();
        if (!fileName.matches("^.+\\.(?i)(xls)$") && !fileName.matches("^.+\\.(?i)(xlsx)$")) {
            log.error("上传文件格式不正确");
        }
        List<T> dataList = new ArrayList<>();
        Workbook workbook = null;
        try {
            InputStream is = file.getInputStream();
            if (fileName.endsWith(EXCEL2007)) {
                //                FileInputStream is = new FileInputStream(new File(path));
                workbook = new XSSFWorkbook(is);
            }
            if (fileName.endsWith(EXCEL2003)) {
                //                FileInputStream is = new FileInputStream(new File(path));
                workbook = new HSSFWorkbook(is);
            }
            if (workbook != null) {
                // 类映射  注解 value-->bean columns
                Map<String, List<Field>> classMap = new HashMap<>();
                List<Field> fields = Stream.of(EntityFields.all(cls)).collect(Collectors.toList());
                fields.forEach(
                        field -> {
                            ExcelColumnAnnotation annotation =
                                    field.getAnnotation(ExcelColumnAnnotation.class);
                            if (annotation != null) {
                                String value = annotation.value();
                                if (StringUtils.isBlank(value)) {
                                    return; // return起到的作用和continue是相同的 语法
                                }
                                if (!classMap.containsKey(value)) {
                                    classMap.put(value, new ArrayList<>());
                                }
                                field.setAccessible(true);
                                classMap.get(value).add(field);
                            }
                        });
                // 索引-->columns
                Map<Integer, List<Field>> reflectionMap = new HashMap<>(16);
                // 默认读取第一个sheet
                Sheet sheet = workbook.getSheetAt(0);

                boolean firstRow = true;
                for (int i = sheet.getFirstRowNum(); i <= sheet.getLastRowNum(); i++) {
                    Row row = sheet.getRow(i);
                    // 首行  提取注解
                    if (firstRow) {
                        for (int j = row.getFirstCellNum(); j <= row.getLastCellNum(); j++) {
                            Cell cell = row.getCell(j);
                            String cellValue = getCellValue(cell);
                            if (classMap.containsKey(cellValue)) {
                                reflectionMap.put(j, classMap.get(cellValue));
                            }
                        }
                        firstRow = false;
                    } else {
                        // 忽略空白行
                        if (row == null) {
                            continue;
                        }
                        try {
                            T t = cls.newInstance();
                            // 判断是否为空白行
                            boolean allBlank = true;
                            for (int j = row.getFirstCellNum(); j <= row.getLastCellNum(); j++) {
                                if (reflectionMap.containsKey(j)) {
                                    Cell cell = row.getCell(j);
                                    String cellValue = getCellValue(cell);
                                    if (StringUtils.isNotBlank(cellValue)) {
                                        allBlank = false;
                                    }
                                    List<Field> fieldList = reflectionMap.get(j);
                                    fieldList.forEach(
                                            x -> {
                                                try {
                                                    handleField(t, cellValue, x);
                                                } catch (Exception e) {
                                                    log.error(
                                                            String.format(
                                                                    "reflect field:%s value:%s"
                                                                            + " exception!",
                                                                    x.getName(), cellValue),
                                                            e);
                                                }
                                            });
                                }
                            }
                            if (!allBlank) {
                                dataList.add(t);
                            } else {
                                log.warn(String.format("row:%s is blank ignore!", i));
                            }
                        } catch (Exception e) {
                            log.error(String.format("parse row:%s exception!", i), e);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error(String.format("parse excel exception!"), e);
        } finally {
            if (workbook != null) {
                try {
                    workbook.close();
                } catch (Exception e) {
                    log.error(String.format("parse excel exception!"), e);
                }
            }
        }
        return dataList;
    }

    private static <T> void handleField(T t, String value, Field field) throws Exception {
        Class<?> type = field.getType();
        if (type == null || type == void.class || StringUtils.isBlank(value)) {
            return;
        }
        if (type == Object.class) {
            field.set(t, value);
            // 数字类型
        } else if (type.getSuperclass() == null || type.getSuperclass() == Number.class) {
            if (type == int.class || type == Integer.class) {
                field.set(t, NumberUtils.toInt(value));
            } else if (type == long.class || type == Long.class) {
                field.set(t, NumberUtils.toLong(value));
            } else if (type == byte.class || type == Byte.class) {
                field.set(t, NumberUtils.toByte(value));
            } else if (type == short.class || type == Short.class) {
                field.set(t, NumberUtils.toShort(value));
            } else if (type == double.class || type == Double.class) {
                field.set(t, NumberUtils.toDouble(value));
            } else if (type == float.class || type == Float.class) {
                field.set(t, NumberUtils.toFloat(value));
            } else if (type == char.class || type == Character.class) {
                field.set(t, CharUtils.toChar(value));
            } else if (type == boolean.class) {
                field.set(t, BooleanUtils.toBoolean(value));
            } else if (type == BigDecimal.class) {
                field.set(t, new BigDecimal(value));
            }
        } else if (type == Boolean.class) {
            field.set(t, BooleanUtils.toBoolean(value));
        } else if (type == Date.class) {
            //
            field.set(t, value);
        } else if (type == String.class) {
            field.set(t, value);
        } else {
            Constructor<?> constructor = type.getConstructor(String.class);
            field.set(t, constructor.newInstance(value));
        }
    }

    private static String getCellValue(Cell cell) {
        if (cell == null) {
            return "";
        }
        if (cell.getCellType() == Cell.CELL_TYPE_NUMERIC) {
            if (HSSFDateUtil.isCellDateFormatted(cell)) {
                return HSSFDateUtil.getJavaDate(cell.getNumericCellValue()).toString();
            } else {
                return new BigDecimal(cell.getNumericCellValue()).toString();
            }
        } else if (cell.getCellType() == Cell.CELL_TYPE_STRING) {
            return StringUtils.trimToEmpty(cell.getStringCellValue());
        } else if (cell.getCellType() == Cell.CELL_TYPE_FORMULA) {
            return StringUtils.trimToEmpty(cell.getCellFormula());
        } else if (cell.getCellType() == Cell.CELL_TYPE_BLANK) {
            return "";
        } else if (cell.getCellType() == Cell.CELL_TYPE_BOOLEAN) {
            return String.valueOf(cell.getBooleanCellValue());
        } else if (cell.getCellType() == Cell.CELL_TYPE_ERROR) {
            return "ERROR";
        } else {
            return cell.toString().trim();
        }
    }

    public static <T> void writeExcel(
            HttpServletResponse response, List<T> dataList, Class<T> cls) {
        Field[] fields = EntityFields.all(cls);
        List<Field> fieldList =
                Arrays.stream(fields)
                        .filter(
                                field -> {
                                    ExcelColumnAnnotation annotation =
                                            field.getAnnotation(ExcelColumnAnnotation.class);
                                    if (annotation != null && annotation.col() > 0) {
                                        field.setAccessible(true);
                                        return true;
                                    }
                                    return false;
                                })
                        .sorted(
                                Comparator.comparing(
                                        field -> {
                                            int col = 0;
                                            ExcelColumnAnnotation annotation =
                                                    field.getAnnotation(
                                                            ExcelColumnAnnotation.class);
                                            if (annotation != null) {
                                                col = annotation.col();
                                            }
                                            return col;
                                        }))
                        .collect(Collectors.toList());

        Workbook wb = new XSSFWorkbook();
        Sheet sheet = wb.createSheet("Sheet1");
        AtomicInteger ai = new AtomicInteger();
        {
            Row row = sheet.createRow(ai.getAndIncrement());
            AtomicInteger aj = new AtomicInteger();
            // 写入头部
            fieldList.forEach(
                    field -> {
                        ExcelColumnAnnotation annotation =
                                field.getAnnotation(ExcelColumnAnnotation.class);
                        String columnName = "";
                        if (annotation != null) {
                            columnName = annotation.value();
                        }
                        Cell cell = row.createCell(aj.getAndIncrement());

                        CellStyle cellStyle = wb.createCellStyle();
                        cellStyle.setFillForegroundColor(IndexedColors.WHITE.getIndex());
                        cellStyle.setFillPattern(CellStyle.SOLID_FOREGROUND);
                        cellStyle.setAlignment(CellStyle.ALIGN_CENTER);

                        Font font = wb.createFont();
                        font.setBoldweight(Font.BOLDWEIGHT_NORMAL);
                        cellStyle.setFont(font);
                        cell.setCellStyle(cellStyle);
                        cell.setCellValue(columnName);
                    });
        }
        if (CollectionUtils.isNotEmpty(dataList)) {
            dataList.forEach(
                    t -> {
                        Row row1 = sheet.createRow(ai.getAndIncrement());
                        AtomicInteger aj = new AtomicInteger();
                        fieldList.forEach(
                                field -> {
                                    Class<?> type = field.getType();
                                    Object value = "";
                                    try {
                                        value = field.get(t);
                                    } catch (Exception e) {
                                        e.printStackTrace();
                                    }
                                    Cell cell = row1.createCell(aj.getAndIncrement());
                                    if (value != null) {
                                        if (type == Date.class) {
                                            cell.setCellValue(value.toString());
                                        } else {
                                            cell.setCellValue(value.toString());
                                        }
                                        cell.setCellValue(value.toString());
                                    }
                                });
                    });
        }
        // 冻结窗格
        wb.getSheet("Sheet1").createFreezePane(0, 1, 0, 1);
        // 浏览器下载excel
        buildExcelDocument("abbot.xlsx", wb, response);
        // 生成excel文件
        //        buildExcelFile(".\\default.xlsx",wb);
    }

    /**
     * 浏览器下载excel
     *
     * @param fileName
     * @param wb
     * @param response
     */
    private static void buildExcelDocument(
            String fileName, Workbook wb, HttpServletResponse response) {
        try {
            response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
            response.setHeader(
                    "Content-Disposition",
                    "attachment;filename=" + URLEncoder.encode(fileName, "utf-8"));
            response.flushBuffer();
            wb.write(response.getOutputStream());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * 生成excel文件
     *
     * @param path 生成excel路径
     * @param wb
     */
    private static void buildExcelFile(String path, Workbook wb) {
        File file = new File(path);
        if (file.exists()) {
            file.delete();
        }
        try {
            wb.write(new FileOutputStream(file));
            wb.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 后台通用导出Excel方法
     *
     * @param httpServletResponse
     * @param contentEntities
     * @param fieldSettingField
     */
    public static void writeExcel(
            HttpServletResponse httpServletResponse,
            List<ContentEntity> contentEntities,
            List<ContentFieldDefinition> fieldSettingField) {
        Workbook wb = new XSSFWorkbook();
        Sheet sheet = wb.createSheet("Sheet1");

        // 工作簿内共享有限样式，数量不随数据行或字段数增长。
        Font headerFont = wb.createFont();
        headerFont.setFontName("微软雅黑");
        headerFont.setBoldweight(Font.BOLDWEIGHT_BOLD);
        headerFont.setColor(IndexedColors.WHITE.getIndex());
        Font bodyFont = wb.createFont();
        bodyFont.setFontName("微软雅黑");
        bodyFont.setBoldweight(Font.BOLDWEIGHT_NORMAL);
        CellStyle[] styles = new CellStyle[3];
        for (int i = 0; i < styles.length; i++) {
            CellStyle style = wb.createCellStyle();
            style.setFillForegroundColor(
                    i == 0
                            ? IndexedColors.BLUE_GREY.getIndex()
                            : i == 2
                                    ? IndexedColors.GREY_25_PERCENT.getIndex()
                                    : IndexedColors.WHITE.getIndex());
            style.setFillPattern(CellStyle.SOLID_FOREGROUND);
            style.setAlignment(CellStyle.ALIGN_CENTER);
            style.setVerticalAlignment(CellStyle.VERTICAL_CENTER);
            style.setBorderBottom(HSSFCellStyle.BORDER_THIN);
            style.setBorderTop(HSSFCellStyle.BORDER_THIN);
            style.setBorderLeft(HSSFCellStyle.BORDER_THIN);
            style.setBorderRight(HSSFCellStyle.BORDER_THIN);
            style.setFont(i == 0 ? headerFont : bodyFont);
            style.setWrapText(i != 0);
            styles[i] = style;
        }
        Row header = sheet.createRow(0);
        header.setHeightInPoints(21.5F);
        for (int column = 0; column < fieldSettingField.size(); column++) {
            ContentFieldDefinition definition = fieldSettingField.get(column);
            Cell cell = header.createCell(column);
            cell.setCellStyle(styles[0]);
            cell.setCellValue(definition.getTitle());
            boolean resource =
                    Set.of("Image", "File", "Video")
                            .contains(StringUtils.defaultString(definition.getFieldType()));
            sheet.setColumnWidth(column, (int) (((resource ? 33 : 21) + 0.72) * 256));
        }
        if (CollectionUtils.isNotEmpty(contentEntities)) {
            for (int index = 0; index < contentEntities.size(); index++) {
                ContentEntity content = contentEntities.get(index);
                Map<String, Object> extensions = content.getExtensionValues();
                Map<String, Field> fields = new HashMap<>();
                for (Field field : EntityFields.all(content.getClass())) {
                    field.setAccessible(true);
                    fields.put(field.getName(), field);
                }
                Row row = sheet.createRow(index + 1);
                row.setHeightInPoints(21.5F);
                for (int column = 0; column < fieldSettingField.size(); column++) {
                    ContentFieldDefinition definition = fieldSettingField.get(column);
                    String key = definition.getFieldName();
                    Object value = null;
                    if (com.xinhongru.content.model.ContentExtensionFields.isName(key)) {
                        value = extensions.get(key);
                    } else if (fields.containsKey(key)) {
                        try {
                            value = fields.get(key).get(content);
                        } catch (IllegalAccessException error) {
                            throw new IllegalArgumentException(
                                    "导出字段不可读取：" + definition.getTitle(), error);
                        }
                    }
                    String text =
                            value == null
                                    ? ""
                                    : value instanceof Date date
                                            ? StringDeal.getDateTimeString(
                                                    date, "yyyy-MM-dd HH:mm:ss")
                                            : String.valueOf(value);
                    if (com.xinhongru.content.model.ContentExtensionFields.isName(key)
                            && "Editor".equals(definition.getFieldType())) {
                        var document = org.jsoup.Jsoup.parseBodyFragment(text);
                        document.select("script,style,noscript,template").remove();
                        text = document.body().text();
                    }
                    if (text.length() > 32767)
                        throw new IllegalArgumentException(
                                "导出字段超过Excel单元格32767字符限制：" + definition.getTitle());
                    Cell cell = row.createCell(column);
                    cell.setCellValue(text);
                    cell.setCellStyle(styles[index % 2 + 1]);
                }
            }
        }
        // 冻结窗格
        wb.getSheet("Sheet1").createFreezePane(0, 1, 0, 1);
        // 浏览器下载excel
        buildExcelDocument("" + StringDeal.getFileName() + ".xlsx", wb, httpServletResponse);
        //        //生成excel文件
        //        buildExcelFile(".\\default.xlsx",wb);
    }
}
