package com.xinhongru.service.cmsPlatform.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.xinhongru.entity.OneshotEntity;
import com.xinhongru.entity.SiteSettingsEntity;
import com.xinhongru.mapper.OneShotMapper;
import com.xinhongru.mapper.SiteSettingsMapper;
import com.xinhongru.service.cmsPlatform.SiteSettingsService;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Service
public class SiteSettingsServiceImpl implements SiteSettingsService {
    @Resource private SiteSettingsMapper settingsMapper;
    @Resource private OneShotMapper oneShotMapper;

    private static final String[] HEADER = {
        "栏目id", "栏目名称", "类型(list/detail)", "页面地址", "301页", "302配置页", "多语言回退页"
    };

    @Override
    public List<ColumnConfigDto> childrenConfigs(Long siteId, Integer parentColumnId) {
        List<ColumnConfigDto> result = new ArrayList<>();
        List<OneshotEntity> all = oneShotMapper.selectBySiteProjectId(siteId);
        if (all == null) {
            return result;
        }
        all.stream()
                .filter(m -> Integer.valueOf(m.getParentId()).equals(parentColumnId))
                .sorted((a, b) -> Integer.compare(a.getSort(), b.getSort()))
                .forEach(
                        m -> {
                            SettingDto list = compose(siteId, m.getId(), "list", m);
                            SettingDto detail = compose(siteId, m.getId(), "detail", m);
                            result.add(
                                    new ColumnConfigDto(m.getId(), m.getClassName(), list, detail));
                        });
        return result;
    }

    private SettingDto load(Long siteId, Integer columnId, String kind) {
        return compose(siteId, columnId, kind, oneShotMapper.getModelById(columnId));
    }

    private SettingDto compose(Long siteId, Integer columnId, String kind, OneshotEntity menu) {
        SiteSettingsEntity e = findBy(siteId, columnId, kind);
        String pageUrl = e != null ? e.getPageUrl() : null;
        if (menu != null && (pageUrl == null || workbenchRoute(menu, kind))) {
            pageUrl = "list".equals(kind) ? menu.getListPageUrl() : menu.getDetailPageUrl();
        }
        return e == null
                ? new SettingDto(pageUrl, null, null, null, null)
                : new SettingDto(
                        pageUrl,
                        e.getRedirect301(),
                        e.getRedirect302(),
                        e.getFallbackLang(),
                        e.getCrossMappingJson());
    }

    @Override
    public SettingDto get(Long siteId, Integer columnId, String kind) {
        return load(siteId, columnId, kind);
    }

    private SiteSettingsEntity findBy(Long siteId, Integer columnId, String kind) {
        return settingsMapper.selectOne(
                new QueryWrapper<SiteSettingsEntity>()
                        .eq("site_id", siteId)
                        .eq("column_id", columnId)
                        .eq("kind", kind)
                        .eq("deleted", 0));
    }

    @Override
    @Transactional
    public boolean save(
            Long siteId,
            Integer columnId,
            String columnName,
            Integer parentId,
            String kind,
            String pageUrl,
            String redirect301,
            String redirect302,
            String fallbackLang,
            String crossMappingJson) {
        if (columnId != null) {
            var menu = oneShotMapper.lockFieldConfiguration(siteId, columnId.longValue());
            if (menu == null) throw new IllegalArgumentException("栏目不存在或不属于当前站点");
            String current = "list".equals(kind) ? menu.getListPageUrl() : menu.getDetailPageUrl();
            if (workbenchRoute(menu, kind) && !Objects.equals(current, pageUrl))
                throw new IllegalArgumentException("此栏目使用工作台公开应用，请在工作台预览并切换正式地址");
        }
        SiteSettingsEntity e = findBy(siteId, columnId, kind);
        if (e == null) {
            e =
                    new SiteSettingsEntity()
                            .setSiteId(siteId)
                            .setColumnId(columnId)
                            .setColumnName(columnName)
                            .setParentId(parentId)
                            .setKind(kind)
                            .setDeleted(0)
                            .setCreateTime(LocalDateTime.now());
        }
        e.setPageUrl(pageUrl)
                .setRedirect301(redirect301)
                .setRedirect302(redirect302)
                .setFallbackLang(fallbackLang)
                .setCrossMappingJson(crossMappingJson)
                .setUpdateTime(LocalDateTime.now());
        boolean saved =
                e.getId() == null
                        ? settingsMapper.insert(e) == 1
                        : settingsMapper.updateById(e) == 1;
        if (saved && columnId != null) {
            oneShotMapper.updatePageUrl(columnId, kind, pageUrl);
        }
        return saved;
    }

    private static boolean workbenchRoute(OneshotEntity menu, String kind) {
        String template = "list".equals(kind) ? menu.getListPageTemplate() : menu.getDetailPageTemplate();
        return template != null && template.startsWith("cms-applied/");
    }

    private List<OneshotEntity> childrenOf(Long siteId, Integer parentColumnId) {
        List<OneshotEntity> all = oneShotMapper.selectBySiteProjectId(siteId);
        List<OneshotEntity> result = new ArrayList<>();
        if (all == null) {
            return result;
        }
        all.stream()
                .filter(m -> Integer.valueOf(m.getParentId()).equals(parentColumnId))
                .forEach(result::add);
        return result;
    }

    private void fillRows(Sheet sheet, Long siteId, Integer parentColumnId, boolean fillValues) {
        Row head = sheet.createRow(0);
        for (int i = 0; i < HEADER.length; i++) {
            head.createCell(i).setCellValue(HEADER[i]);
        }
        int rowIdx = 1;
        for (OneshotEntity m : childrenOf(siteId, parentColumnId)) {
            for (String kind : new String[] {"list", "detail"}) {
                SettingDto d = load(siteId, m.getId(), kind);
                Row row = sheet.createRow(rowIdx++);
                row.createCell(0).setCellValue(m.getId());
                row.createCell(1).setCellValue(m.getClassName());
                row.createCell(2).setCellValue(kind);
                row.createCell(3).setCellValue(fillValues && d != null ? d.pageUrl() : "");
                row.createCell(4).setCellValue(fillValues && d != null ? d.redirect301() : "");
                row.createCell(5).setCellValue(fillValues && d != null ? d.redirect302() : "");
                row.createCell(6).setCellValue(fillValues && d != null ? d.fallbackLang() : "");
            }
        }
    }

    @Override
    public void writeTemplateXlsx(HttpServletResponse response, Long siteId, Integer parentColumnId)
            throws Exception {
        try (Workbook wb = new XSSFWorkbook()) {
            fillRows(wb.createSheet("站点配置"), siteId, parentColumnId, false);
            write(response, wb, "site-config-template.xlsx");
        }
    }

    @Override
    public void writeExportXlsx(HttpServletResponse response, Long siteId, Integer parentColumnId)
            throws Exception {
        try (Workbook wb = new XSSFWorkbook()) {
            fillRows(wb.createSheet("站点配置"), siteId, parentColumnId, true);
            write(response, wb, "site-config.xlsx");
        }
    }

    private void write(HttpServletResponse response, Workbook wb, String filename)
            throws Exception {
        response.setContentType(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader(
                "Content-Disposition",
                "attachment; filename*=UTF-8''"
                        + URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20"));
        wb.write(response.getOutputStream());
        response.getOutputStream().flush();
    }

    @Override
    @Transactional
    public int importXlsx(MultipartFile file, Long siteId) {
        int count = 0;
        try (InputStream in = file.getInputStream();
                Workbook wb = new XSSFWorkbook(in)) {
            Sheet sheet = wb.getSheetAt(0);
            for (int i = 1; i <= sheet.getPhysicalNumberOfRows(); i++) {
                Row row = sheet.getRow(i);
                if (row == null || row.getCell(0) == null) {
                    continue;
                }
                int columnId = (int) cellNum(row.getCell(0));
                String kind = cellStr(row.getCell(2));
                if (kind == null || kind.isBlank()) {
                    kind = "list";
                }
                String pageUrl = cellStr(row.getCell(3));
                String r301 = cellStr(row.getCell(4));
                String r302 = cellStr(row.getCell(5));
                String fallback = cellStr(row.getCell(6));
                String name = null;
                OneshotEntity menu = oneShotMapper.getModelById(columnId);
                if (menu != null) {
                    name = menu.getClassName();
                }
                save(siteId, columnId, name, 0, kind, pageUrl, r301, r302, fallback, null);
                count++;
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("导入失败：" + e.getMessage(), e);
        }
        return count;
    }

    private String cellStr(Cell cell) {
        if (cell == null) {
            return null;
        }
        int t = cell.getCellType();
        if (t == Cell.CELL_TYPE_NUMERIC) {
            return String.valueOf(cell.getNumericCellValue());
        }
        if (t == Cell.CELL_TYPE_BOOLEAN) {
            return String.valueOf(cell.getBooleanCellValue());
        }
        return cell.getStringCellValue();
    }

    private double cellNum(Cell cell) {
        if (cell.getCellType() == Cell.CELL_TYPE_NUMERIC) {
            return cell.getNumericCellValue();
        }
        return Double.parseDouble(cellStr(cell));
    }
}
