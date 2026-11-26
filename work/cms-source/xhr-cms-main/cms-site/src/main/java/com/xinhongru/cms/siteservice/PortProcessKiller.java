package com.xinhongru.cms.siteservice;

import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 按端口终止进程的跨平台工具。
 *
 * <p>独立进程站点服务在管理节点重启后不再持有子进程句柄，停止时需回退到按端口杀进程。 逻辑与 OpenCodeService 保持一致：Windows 走 netstat +
 * taskkill，Linux 依次尝试 fuser/lsof/ss/netstat。
 */
@Slf4j
final class PortProcessKiller {
    private PortProcessKiller() {}

    static void killByPort(int port) {
        try {
            if (isWindows()) {
                killByPortWindows(port);
            } else {
                killByPortLinux(port);
            }
        } catch (Exception e) {
            log.warn("按端口终止进程失败: port={}, error={}", port, e.getMessage());
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("windows");
    }

    private static void killByPortWindows(int port) throws Exception {
        ProcessBuilder findPb = new ProcessBuilder("cmd.exe", "/c", "netstat -ano");
        findPb.redirectErrorStream(true);
        Process findProcess = findPb.start();

        Set<String> pids = new LinkedHashSet<>();
        Pattern pattern =
                Pattern.compile("^\\s*\\S+\\s+\\S+:" + port + "\\s+\\S+\\s+(\\S+)\\s+(\\d+)\\s*$");
        try (BufferedReader reader =
                new BufferedReader(
                        new InputStreamReader(
                                findProcess.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                Matcher matcher = pattern.matcher(line);
                if (matcher.find()) {
                    String pid = matcher.group(2);
                    if (!"0".equals(pid)) {
                        pids.add(pid);
                    }
                }
            }
        }
        findProcess.waitFor(5, TimeUnit.SECONDS);

        for (String pid : pids) {
            log.info("终止占用端口 {} 的进程 PID: {}", port, pid);
            Process killProcess =
                    new ProcessBuilder("cmd.exe", "/c", "taskkill /F /PID " + pid).start();
            killProcess.waitFor(5, TimeUnit.SECONDS);
        }
    }

    private static void killByPortLinux(int port) {
        if (tryKillWithFuser(port)) {
            return;
        }
        Set<String> pids = findPidsByPortLinux(port);
        if (pids.isEmpty()) {
            log.warn("未找到占用端口 {} 的进程（可能缺少 fuser/lsof/ss/netstat 或需管理员权限）", port);
            return;
        }
        for (String pid : pids) {
            log.info("终止占用端口 {} 的进程 PID: {}", port, pid);
            try {
                Process killProcess = new ProcessBuilder("kill", "-9", pid).start();
                killProcess.waitFor(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warn("终止进程 {} 失败: {}", pid, e.getMessage());
            }
        }
    }

    private static boolean tryKillWithFuser(int port) {
        try {
            Process process = new ProcessBuilder("fuser", "-k", port + "/tcp").start();
            if (process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0) {
                log.info("已通过 fuser 终止占用端口 {} 的进程", port);
                return true;
            }
        } catch (Exception e) {
            log.debug("fuser 不可用: {}", e.getMessage());
        }
        return false;
    }

    private static Set<String> findPidsByPortLinux(int port) {
        Set<String> pids = new LinkedHashSet<>();
        Pattern portPattern = Pattern.compile("[:.]" + port + "\\s");

        for (String line : runAndRead("lsof", "-ti", ":" + port)) {
            line = line.trim();
            if (line.matches("\\d+")) {
                pids.add(line);
            }
        }
        if (!pids.isEmpty()) {
            return pids;
        }

        Pattern ssPidPattern = Pattern.compile("pid=(\\d+)");
        for (String line : runAndRead("ss", "-lptn")) {
            if (portPattern.matcher(line).find()) {
                Matcher m = ssPidPattern.matcher(line);
                while (m.find()) {
                    pids.add(m.group(1));
                }
            }
        }
        if (!pids.isEmpty()) {
            return pids;
        }

        Pattern netstatPidPattern = Pattern.compile("(\\d+)/\\S+\\s*$");
        for (String line : runAndRead("netstat", "-lptn")) {
            if (portPattern.matcher(line).find()) {
                Matcher m = netstatPidPattern.matcher(line);
                if (m.find()) {
                    pids.add(m.group(1));
                }
            }
        }
        return pids;
    }

    private static List<String> runAndRead(String... command) {
        List<String> lines = new ArrayList<>();
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            try (BufferedReader reader =
                    new BufferedReader(
                            new InputStreamReader(
                                    process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    lines.add(line);
                }
            }
            process.waitFor(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.debug("命令 {} 执行失败: {}", String.join(" ", command), e.getMessage());
        }
        return lines;
    }
}
