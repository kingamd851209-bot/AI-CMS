package com.xinhongru.cms.integration;

import com.alibaba.fastjson.JSON;
import com.xinhongru.service.option.OptionService;
import com.xinhongru.site.entity.SiteProjectEntity;
import com.xinhongru.site.mapper.SiteProjectMapper;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.util.*;

/** 仅保存客服/统计的公开脚本参数，不保存后端凭据，也不执行远程探测。 */
@Service
public class SiteExternalIntegrationService {
    public record Entry(boolean enabled, String scriptUrl, Map<String, String> parameters) {}

    public record Settings(Entry chat, Entry stats) {}

    public record PublicEntry(
            String kind, String scriptUrl, Map<String, String> parameters, boolean loopbackTest) {}

    private final OptionService options;
    private final SiteProjectMapper projects;
    private final Environment environment;

    public SiteExternalIntegrationService(
            OptionService options, SiteProjectMapper projects, Environment environment) {
        this.options = options;
        this.projects = projects;
        this.environment = environment;
    }

    public String mode() {
        return environment.getProperty("cms.external-integrations.mode", "disabled");
    }

    public Settings load(Long siteId) {
        site(siteId);
        return new Settings(read(siteId, "chat"), read(siteId, "stats"));
    }

    @Transactional
    public void save(Long siteId, Settings settings) {
        site(siteId);
        if (settings == null) throw new IllegalArgumentException("接入配置不能为空");
        String chat = encode(settings.chat());
        String stats = encode(settings.stats());
        options.updateOptionByName(key(siteId, "chat"), chat);
        options.updateOptionByName(key(siteId, "stats"), stats);
    }

    public List<PublicEntry> effective(Long siteId) {
        var site = site(siteId);
        String mode = mode();
        if (!(mode.equals("production") || mode.equals("loopback-test"))
                || Boolean.TRUE.equals(site.getPreviewAccessEnabled())) return List.of();
        try {
            var settings = load(siteId);
            var result = new ArrayList<PublicEntry>();
            add(result, "chat", settings.chat(), mode);
            add(result, "stats", settings.stats(), mode);
            return List.copyOf(result);
        } catch (IllegalArgumentException invalid) {
            return List.of();
        }
    }

    public List<String> diagnostics(Long siteId) {
        site(siteId);
        var result = new ArrayList<String>();
        String mode = mode();
        if (!mode.equals("production") && !mode.equals("loopback-test"))
            result.add("当前环境禁止外部加载（默认关闭）");
        if (Boolean.TRUE.equals(site(siteId).getPreviewAccessEnabled()))
            result.add("预发布认证站点禁止接入加载");
        try {
            var settings = load(siteId);
            diagnose(result, "客服", settings.chat(), mode);
            diagnose(result, "统计", settings.stats(), mode);
        } catch (IllegalArgumentException invalid) {
            result.add("配置无效：" + invalid.getMessage());
        }
        result.add("仅支持自初始化脚本及公开data-*参数；需要复杂初始化的供应商尚待适配，保存不等于接通");
        return List.copyOf(result);
    }

    private void diagnose(List<String> result, String label, Entry entry, String mode) {
        if (!entry.enabled()) result.add(label + "：未配置或已关闭");
        else if (mode.equals("loopback-test") && !loopback(entry.scriptUrl()))
            result.add(label + "：测试模式仅允许字面loopback URL");
        else result.add(label + "：已保存，尚未进行真实供应商验证");
    }

    private void add(List<PublicEntry> target, String kind, Entry entry, String mode) {
        validate(entry);
        if (entry.enabled() && (mode.equals("production") || loopback(entry.scriptUrl())))
            target.add(
                    new PublicEntry(
                            kind,
                            entry.scriptUrl(),
                            entry.parameters(),
                            mode.equals("loopback-test")));
    }

    private SiteProjectEntity site(Long siteId) {
        if (siteId == null || siteId <= 0) throw new IllegalArgumentException("站点ID无效");
        var site = projects.selectById(siteId);
        if (site == null) throw new IllegalArgumentException("站点不存在");
        return site;
    }

    private String key(Long siteId, String kind) {
        return "site." + siteId + "." + kind;
    }

    private Entry read(Long siteId, String kind) {
        var option = options.getOptionByName(key(siteId, kind));
        if (option == null || option.getValue() == null || option.getValue().isBlank())
            return new Entry(false, "", Map.of());
        try {
            var object = JSON.parseObject(option.getValue());
            if (object == null || !(object.get("enabled") instanceof Boolean))
                throw new IllegalArgumentException("开关必须为布尔值");
            if (!(object.get("scriptUrl") instanceof String url))
                throw new IllegalArgumentException("脚本URL必须为文本");
            var params = new LinkedHashMap<String, String>();
            if (object.get("parameters") != null) {
                if (!(object.get("parameters") instanceof Map<?, ?> raw))
                    throw new IllegalArgumentException("公开参数必须为对象");
                for (var item : raw.entrySet()) {
                    if (!(item.getKey() instanceof String name)
                            || !(item.getValue() instanceof String value))
                        throw new IllegalArgumentException("公开参数必须为文本");
                    params.put(name, value);
                }
            }
            var entry = new Entry(object.getBooleanValue("enabled"), url, Map.copyOf(params));
            validate(entry);
            return entry;
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("接入配置格式无效", invalid);
        }
    }

    private String encode(Entry entry) {
        validate(entry);
        // 显式字段序列化，长度对应t_options.value的1000字上限。
        var object = new LinkedHashMap<String, Object>();
        object.put("enabled", entry.enabled());
        object.put("scriptUrl", entry.scriptUrl());
        object.put("parameters", entry.parameters());
        String value = JSON.toJSONString(object);
        if (value.length() > 1000)
            throw new IllegalArgumentException("每项序列化配置不能超过1000字，请缩短URL或公开参数");
        return value;
    }

    private void validate(Entry entry) {
        if (entry == null || entry.scriptUrl() == null || entry.parameters() == null)
            throw new IllegalArgumentException("接入配置不完整");
        if (entry.parameters().size() > 8) throw new IllegalArgumentException("最多8项公开参数");
        for (var item : entry.parameters().entrySet()) {
            if (item.getKey() == null
                    || !item.getKey().matches("data-[a-z][a-z0-9-]{0,39}")
                    || item.getValue() == null
                    || item.getValue().length() > 500
                    || item.getValue().codePoints().anyMatch(Character::isISOControl))
                throw new IllegalArgumentException("仅支持data-*文本公开参数（单项最多500字）");
            if (item.getKey().matches(".*(password|secret|token|authorization).*"))
                throw new IllegalArgumentException("不能保存后端秘密参数");
        }
        if (entry.scriptUrl().isEmpty() && !entry.enabled()) return;
        uri(entry.scriptUrl());
    }

    private URI uri(String value) {
        try {
            var uri = new URI(value);
            if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                    || uri.getHost() == null
                    || uri.getRawUserInfo() != null
                    || uri.getRawFragment() != null
                    || uri.getPort() > 65535
                    || value.codePoints().anyMatch(Character::isISOControl))
                throw new IllegalArgumentException("脚本必须是无用户信息的完整HTTP(S) URL");
            return uri;
        } catch (java.net.URISyntaxException invalid) {
            throw new IllegalArgumentException("脚本URL无效");
        }
    }

    private boolean loopback(String value) {
        try {
            var uri = uri(value);
            String host = uri.getHost();
            return host.equals("127.0.0.1") || host.equals("[::1]") || host.equals("::1");
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }
}
