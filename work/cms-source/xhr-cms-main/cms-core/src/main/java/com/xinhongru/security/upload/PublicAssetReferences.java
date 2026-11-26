package com.xinhongru.security.upload;

import com.xinhongru.cms.template.TemplateStorageService;
import com.xinhongru.cms.uipackage.UiPackageStorageService;
import com.xinhongru.config.PublicStorageRoots;
import com.xinhongru.resource.AssetReferenceCheck.Usage;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/** 跨站点检查公开 URL，避免内容分发后从来源站点删除共享文件。失败时阻止治理操作。 */
@Service
public class PublicAssetReferences {
    private static final int MAX_REFERENCES = 100;
    private final JdbcTemplate jdbc;
    private final PublicStorageRoots roots;
    private final TemplateStorageService templates;
    private final UiPackageStorageService packages;

    public PublicAssetReferences(
            JdbcTemplate jdbc,
            PublicStorageRoots roots,
            TemplateStorageService templates,
            UiPackageStorageService packages) {
        this.jdbc = jdbc;
        this.roots = roots;
        this.templates = templates;
        this.packages = packages;
    }

    public List<Usage> find(Path file) {
        String url = roots.reference(file);
        List<Usage> found = new ArrayList<>();
        try {
            for (String table : List.of("t_contents", "t_oneshot", "t_options")) {
                scanTable(table, url, found);
            }
            scanFiles(templates.root(), url, found);
            scanFiles(packages.root(), url, found);
            return List.copyOf(found);
        } catch (Exception failure) {
            throw new IllegalStateException("公开资源引用检查失败，已阻止删除或重命名", failure);
        }
    }

    private void scanTable(String table, String url, List<Usage> found) {
        List<String> columns =
                jdbc.query(
                        "SELECT * FROM " + table + " WHERE 1=0",
                        (ResultSetExtractor<List<String>>)
                                rs -> {
                                    List<String> text = new ArrayList<>();
                                    var meta = rs.getMetaData();
                                    for (int i = 1; i <= meta.getColumnCount(); i++) {
                                        int type = meta.getColumnType(i);
                                        String name = meta.getColumnName(i);
                                        if (Set.of(
                                                                Types.VARCHAR,
                                                                Types.CHAR,
                                                                Types.LONGVARCHAR,
                                                                Types.CLOB,
                                                                Types.NVARCHAR,
                                                                Types.LONGNVARCHAR)
                                                        .contains(type)
                                                && name.matches("[A-Za-z0-9_]+")) text.add(name);
                                    }
                                    return text;
                                });
        if (columns == null || columns.isEmpty()) return;
        String pattern = "%" + url.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        String where =
                String.join(
                        " OR ",
                        columns.stream().map(column -> column + " LIKE ? ESCAPE '!' ").toList());
        jdbc.query(
                "SELECT * FROM " + table + " WHERE " + where,
                (RowCallbackHandler)
                        rs -> {
                            if (found.size() >= MAX_REFERENCES) return;
                            for (String column : columns) {
                                String value = rs.getString(column);
                                if (value != null && value.contains(url)) {
                                    found.add(new Usage("数据库", table, column, url));
                                    break;
                                }
                            }
                        },
                Collections.nCopies(columns.size(), pattern).toArray());
    }

    private void scanFiles(Path root, String url, List<Usage> found) throws IOException {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return;
        try (var files = Files.walk(root)) {
            var iterator =
                    files.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                            .filter(
                                    path ->
                                            Set.of("html", "htm", "css", "js", "json", "xml", "txt")
                                                    .contains(
                                                            UploadDelivery.extension(
                                                                    path.toString())))
                            .iterator();
            while (iterator.hasNext() && found.size() < MAX_REFERENCES) {
                Path path = iterator.next();
                if (Files.size(path) <= 20 * 1024 * 1024 && Files.readString(path).contains(url)) {
                    found.add(new Usage("模板/UI包", root.relativize(path).toString(), "文件内容", url));
                }
            }
        }
    }
}
