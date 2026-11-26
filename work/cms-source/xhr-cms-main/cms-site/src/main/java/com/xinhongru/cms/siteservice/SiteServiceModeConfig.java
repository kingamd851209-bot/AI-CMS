package com.xinhongru.cms.siteservice;

import com.xinhongru.entity.OptionsEntity;
import com.xinhongru.service.option.OptionService;
import com.xinhongru.site.launch.ApplicationArtifact;

import lombok.extern.slf4j.Slf4j;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 站点服务进程模式配置
 *
 * <p>统一进程（unified）：站点服务由管理节点在同一 JVM 内以嵌入式 Undertow 提供（默认）。 独立进程（independent）：每个站点由管理节点拉起一个独立子
 * JVM（同一个 jar）， 子进程不开放管理端口，只启动指定站点的服务端口。
 *
 * <p>配置与启动参数存放于 t_options：全局键 + 每站点覆盖（单行 JSON）。
 */
@Slf4j
@Service
public class SiteServiceModeConfig {
    public static final String MODE_UNIFIED = "unified";
    public static final String MODE_INDEPENDENT = "independent";

    private static final String KEY_MODE = "siteService.mode";
    private static final String KEY_JAVA_PATH = "siteService.javaPath";
    private static final String KEY_JAR_PATH = "siteService.jarPath";
    private static final String KEY_WORK_DIR = "siteService.workDir";
    private static final String KEY_JVM_ARGS = "siteService.jvmArgs";
    private static final String KEY_PROGRAM_ARGS = "siteService.programArgs";
    private static final String KEY_CONTROL_TOKEN = "siteService.controlToken";

    /** 每站点覆盖以单行 JSON 保存在 siteService.site.{siteId}，避免超出 t_options.name 的 varchar(32) 限制。 */
    private static final String SITE_OVERRIDE_PREFIX = "siteService.site.";

    private static final String[] PARAM_FIELDS = {
        "javaPath", "jarPath", "workDir", "jvmArgs", "programArgs"
    };

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final OptionService optionService;
    private final ApplicationArtifact artifact;

    public SiteServiceModeConfig(OptionService optionService, ApplicationArtifact artifact) {
        this.artifact = artifact;
        this.optionService = optionService;
    }

    public String getMode() {
        String mode = readValue(KEY_MODE);
        return MODE_INDEPENDENT.equalsIgnoreCase(mode) ? MODE_INDEPENDENT : MODE_UNIFIED;
    }

    public void setMode(String mode) {
        if (mode == null
                || (!MODE_UNIFIED.equalsIgnoreCase(mode)
                        && !MODE_INDEPENDENT.equalsIgnoreCase(mode))) {
            throw new IllegalArgumentException("未知的站点服务模式: " + mode);
        }
        writeValue(KEY_MODE, mode.toLowerCase());
    }

    public boolean isIndependent() {
        return MODE_INDEPENDENT.equals(getMode());
    }

    /** 全局启动参数（未设置时为 null，实际生效值由 {@link #resolveLaunchParams} 计算） */
    public GlobalParams getGlobalParams() {
        return new GlobalParams(
                blankToNull(readValue(KEY_JAVA_PATH)),
                blankToNull(readValue(KEY_JAR_PATH)),
                blankToNull(readValue(KEY_WORK_DIR)),
                nullToEmpty(readValue(KEY_JVM_ARGS)),
                nullToEmpty(readValue(KEY_PROGRAM_ARGS)));
    }

    public void saveGlobalParams(GlobalParams params) {
        writeValue(KEY_JAVA_PATH, nullToEmpty(params.javaPath()));
        writeValue(KEY_JAR_PATH, nullToEmpty(params.jarPath()));
        writeValue(KEY_WORK_DIR, nullToEmpty(params.workDir()));
        writeValue(KEY_JVM_ARGS, nullToEmpty(params.jvmArgs()));
        writeValue(KEY_PROGRAM_ARGS, nullToEmpty(params.programArgs()));
    }

    /** 未配置时实际会使用的默认值，供前端回显。 */
    public Map<String, String> effectiveDefaults() {
        String jarPath = detectCurrentJar();
        Map<String, String> defaults = new LinkedHashMap<>();
        defaults.put("javaPath", defaultJavaExecutable());
        defaults.put("jarPath", jarPath);
        defaults.put("workDir", parentDir(jarPath));
        return defaults;
    }

    /** 站点覆盖参数（仅包含非空字段） */
    public Map<String, String> getSiteOverride(Long siteId) {
        String json = readValue(SITE_OVERRIDE_PREFIX + siteId);
        if (StringUtils.isBlank(json)) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, String> parsed =
                    MAPPER.readValue(json, new TypeReference<LinkedHashMap<String, String>>() {});
            return parsed == null ? new LinkedHashMap<>() : parsed;
        } catch (Exception e) {
            log.warn("解析站点启动参数覆盖失败: siteId={}", siteId, e);
            return new LinkedHashMap<>();
        }
    }

    public void saveSiteOverride(Long siteId, Map<String, String> override) {
        Map<String, String> cleaned = new LinkedHashMap<>();
        if (override != null) {
            for (String field : PARAM_FIELDS) {
                String value = override.get(field);
                if (StringUtils.isNotBlank(value)) {
                    cleaned.put(field, value.trim());
                }
            }
        }
        try {
            writeValue(SITE_OVERRIDE_PREFIX + siteId, MAPPER.writeValueAsString(cleaned));
        } catch (Exception e) {
            throw new IllegalStateException("保存站点启动参数失败: " + e.getMessage(), e);
        }
    }

    /** 合并全局默认与站点覆盖，得到该站点独立进程的实际启动参数。 */
    public LaunchParams resolveLaunchParams(Long siteId) {
        Map<String, String> override = getSiteOverride(siteId);
        GlobalParams global = getGlobalParams();

        String jarPath = pick(override.get("jarPath"), global.jarPath(), detectCurrentJar());
        String javaPath =
                pick(override.get("javaPath"), global.javaPath(), defaultJavaExecutable());
        String workDir = pick(override.get("workDir"), global.workDir(), parentDir(jarPath));
        String jvmArgs = pick(override.get("jvmArgs"), global.jvmArgs(), "");
        String programArgs = pick(override.get("programArgs"), global.programArgs(), "");

        return new LaunchParams(
                javaPath, jarPath, workDir, jvmArgs, programArgs, getOrCreateControlToken());
    }

    public String getOrCreateControlToken() {
        String token = readValue(KEY_CONTROL_TOKEN);
        if (StringUtils.isBlank(token)) {
            token = UUID.randomUUID().toString().replace("-", "");
            writeValue(KEY_CONTROL_TOKEN, token);
        }
        return token;
    }

    // ==================== 内部工具 ====================

    private String readValue(String name) {
        OptionsEntity option = optionService.getOptionByName(name);
        return option == null ? null : option.getValue();
    }

    private void writeValue(String name, String value) {
        optionService.updateOptionByName(name, value == null ? "" : value);
    }

    private static String blankToNull(String value) {
        return StringUtils.isBlank(value) ? null : value.trim();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String pick(String override, String global, String fallback) {
        if (StringUtils.isNotBlank(override)) {
            return override.trim();
        }
        if (StringUtils.isNotBlank(global)) {
            return global.trim();
        }
        return fallback;
    }

    private static String parentDir(String filePath) {
        try {
            Path parent = Paths.get(filePath).toAbsolutePath().getParent();
            return parent == null
                    ? Paths.get(System.getProperty("user.dir")).toAbsolutePath().toString()
                    : parent.toString();
        } catch (Exception e) {
            return Paths.get(System.getProperty("user.dir")).toAbsolutePath().toString();
        }
    }

    private static String defaultJavaExecutable() {
        String javaHome = System.getProperty("java.home");
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("windows");
        if (StringUtils.isNotBlank(javaHome)) {
            return Paths.get(javaHome, "bin", windows ? "java.exe" : "java").toString();
        }
        return "java";
    }

    /** 定位当前运行的可执行 jar（同级目录启动独立进程时使用）。 */
    private String detectCurrentJar() {
        return artifact.currentJar();
    }

    public record GlobalParams(
            String javaPath, String jarPath, String workDir, String jvmArgs, String programArgs) {}

    public record LaunchParams(
            String javaPath,
            String jarPath,
            String workDir,
            String jvmArgs,
            String programArgs,
            String controlToken) {}
}
