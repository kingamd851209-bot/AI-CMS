package com.xinhongru.cms.siteservice;

import lombok.extern.slf4j.Slf4j;

import tools.jackson.databind.ObjectMapper;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 站点访问日志记录器
 *
 * <p>按天滚动写入站点 logs 目录： - access-yyyy-MM-dd.log：普通访问日志 - error-yyyy-MM-dd.log：异常日志（含堆栈）
 */
@Slf4j
public class SiteRequestLogger implements AutoCloseable {
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DATETIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path logDir;
    private final Object lock = new Object();

    private BufferedWriter accessWriter;
    private BufferedWriter errorWriter;
    private String currentDate;

    public SiteRequestLogger(Path logDir) {
        this.logDir = logDir.toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.logDir);
        } catch (IOException e) {
            throw new IllegalStateException("站点日志目录创建失败: " + this.logDir, e);
        }
    }

    /** 记录一条访问日志。 */
    public void logAccess(
            LocalDateTime time,
            String category,
            Long columnId,
            String method,
            String url,
            String query,
            int status,
            long durationMs,
            String clientIp) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("time", format(time));
        entry.put("category", category);
        if (columnId != null) {
            entry.put("columnId", columnId);
        }
        entry.put("method", method);
        entry.put("url", url);
        if (query != null && !query.isBlank()) {
            entry.put("query", query);
        }
        entry.put("status", status);
        entry.put("durationMs", durationMs);
        if (clientIp != null && !clientIp.isBlank()) {
            entry.put("clientIp", clientIp);
        }
        writeLine(AccessFile.ACCESS, entry);
    }

    /** 记录一条异常日志，含异常堆栈。 */
    public void logError(
            LocalDateTime time,
            String category,
            Long columnId,
            String method,
            String url,
            String query,
            int status,
            long durationMs,
            String clientIp,
            Throwable error) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("time", format(time));
        entry.put("category", category);
        if (columnId != null) {
            entry.put("columnId", columnId);
        }
        entry.put("method", method);
        entry.put("url", url);
        if (query != null && !query.isBlank()) {
            entry.put("query", query);
        }
        entry.put("status", status);
        entry.put("durationMs", durationMs);
        if (clientIp != null && !clientIp.isBlank()) {
            entry.put("clientIp", clientIp);
        }
        if (error != null) {
            entry.put("errorType", error.getClass().getName());
            entry.put("errorMessage", error.getMessage() == null ? "" : error.getMessage());
            entry.put("stackTrace", toStackTrace(error));
        }
        writeLine(AccessFile.ERROR, entry);
    }

    @Override
    public void close() {
        synchronized (lock) {
            closeQuietly(accessWriter);
            accessWriter = null;
            closeQuietly(errorWriter);
            errorWriter = null;
            currentDate = null;
        }
    }

    private void writeLine(AccessFile file, Object entry) {
        try {
            String line = MAPPER.writeValueAsString(entry);
            synchronized (lock) {
                ensureWriter(file);
                BufferedWriter writer = file == AccessFile.ACCESS ? accessWriter : errorWriter;
                writer.write(line);
                writer.newLine();
                writer.flush();
            }
        } catch (IOException e) {
            log.warn("写入站点访问日志失败: dir={}, file={}", logDir, file.name(), e);
        }
    }

    private void ensureWriter(AccessFile file) throws IOException {
        String today = LocalDate.now().format(DATE_FORMAT);
        if (currentDate == null || !today.equals(currentDate)) {
            closeQuietly(accessWriter);
            closeQuietly(errorWriter);
            accessWriter = null;
            errorWriter = null;
            currentDate = today;
        }
        if (file == AccessFile.ACCESS && accessWriter == null) {
            accessWriter = openWriter("access-" + today + ".log");
        } else if (file == AccessFile.ERROR && errorWriter == null) {
            errorWriter = openWriter("error-" + today + ".log");
        }
    }

    private BufferedWriter openWriter(String fileName) throws IOException {
        Path file = logDir.resolve(fileName);
        return Files.newBufferedWriter(
                file, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static String format(LocalDateTime time) {
        return time == null ? "" : DATETIME_FORMAT.format(time);
    }

    private static String toStackTrace(Throwable error) {
        StringWriter sw = new StringWriter();
        error.printStackTrace(new PrintWriter(sw));
        return sw.toString().replace("\r\n", "\n").replace("\t", "    ");
    }

    private static void closeQuietly(BufferedWriter writer) {
        if (writer == null) return;
        try {
            writer.close();
        } catch (IOException ignored) {
            // 忽略
        }
    }

    private enum AccessFile {
        ACCESS,
        ERROR
    }
}
