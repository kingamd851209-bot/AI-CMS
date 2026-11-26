package com.xinhongru.cms.siteservice;

import com.alibaba.fastjson.annotation.JSONField;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.xinhongru.properties.ResourceProperties;
import com.xinhongru.site.config.ServicePortRange;
import com.xinhongru.site.entity.SiteProjectEntity;
import com.xinhongru.site.mapper.SiteProjectMapper;

import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.time.LocalDateTime;
import java.util.Objects;

/** 保存单站点服务设置；认证实时读取，网络入口变化时才重启服务。 */
@Service
@RequiredArgsConstructor
public class SiteServiceSettingsService {
    private final SiteProjectMapper mapper;
    private final SiteHttpServerManager servers;
    private final StandaloneSiteProcessManager processes;
    private final SiteServiceModeConfig mode;

    public Settings get(Long siteId) {
        SiteProjectEntity site = requireSite(siteId);
        return new Settings(
                site.getSiteDomain(),
                site.getSiteServiceIp(),
                site.getSiteServicePort(),
                Boolean.TRUE.equals(site.getPreviewAccessEnabled()),
                site.getPreviewAccessPassword());
    }

    public synchronized SiteServiceStatus save(Long siteId, Update request) {
        SiteProjectEntity site = requireSite(siteId);
        if (!ServicePortRange.isValid(request.siteServicePort())) {
            throw badRequest(ServicePortRange.MESSAGE);
        }
        if (request.previewAccessEnabled() == null) throw badRequest("请选择是否认证");
        String domain = normalizeDomain(request.siteDomain());
        String resourceDomain;
        try {
            resourceDomain =
                    ResourceProperties.normalizeSiteDomain(
                            request.siteServiceIp() == null
                                    ? site.getSiteServiceIp()
                                    : request.siteServiceIp(),
                            site.getSiteServiceIp());
        } catch (IllegalArgumentException invalid) {
            throw badRequest(invalid.getMessage());
        }
        String password = request.password();
        if (request.previewAccessEnabled()) {
            if (password == null
                    || password.isBlank()
                    || password.codePoints().anyMatch(Character::isISOControl)
                    || password.length() > 255) {
                throw badRequest("开启认证时密码必填，不能包含控制字符，且不能超过 255 个字符");
            }
        }
        if (password != null
                && (password.length() > 255
                        || password.codePoints().anyMatch(Character::isISOControl))) {
            throw badRequest("认证密码不能包含控制字符，且不能超过 255 个字符");
        }
        int port = request.siteServicePort();
        if (mapper.selectCount(
                        Wrappers.<SiteProjectEntity>lambdaQuery()
                                .eq(SiteProjectEntity::getSiteServicePort, port)
                                .ne(SiteProjectEntity::getId, siteId))
                > 0) {
            throw badRequest("服务端口已被其他站点使用");
        }
        boolean portChanged = !Objects.equals(site.getSiteServicePort(), port);
        if (portChanged) {
            try (ServerSocket socket = new ServerSocket(port)) {
                // 在停止旧服务之前检查新端口，实际启动仍由服务管理器处理竞争占用。
            } catch (IOException occupied) {
                throw badRequest("服务端口已被占用");
            }
        }
        boolean independent = mode.isIndependent();
        SiteServiceStatus current =
                independent ? processes.getStatus(site) : servers.getStatus(site);
        boolean restart =
                "RUNNING".equals(current.status())
                        && (portChanged || !Objects.equals(site.getSiteDomain(), domain));
        if (restart) {
            if (independent) processes.stop(siteId);
            else servers.stopSite(siteId);
        }
        // 显式 set 可清空域名和密码，且不会覆盖其他编辑弹窗的站点字段。
        int updated =
                mapper.update(
                        null,
                        Wrappers.<SiteProjectEntity>lambdaUpdate()
                                .eq(SiteProjectEntity::getId, siteId)
                                .set(SiteProjectEntity::getSiteDomain, domain)
                                .set(SiteProjectEntity::getSiteServiceIp, resourceDomain)
                                .set(SiteProjectEntity::getSiteServicePort, port)
                                .set(
                                        SiteProjectEntity::getPreviewAccessEnabled,
                                        request.previewAccessEnabled())
                                .set(SiteProjectEntity::getPreviewAccessPassword, password)
                                .set(SiteProjectEntity::getUpdateTime, LocalDateTime.now()));
        if (updated != 1) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "站点不存在");
        SiteProjectEntity saved = requireSite(siteId);
        if (restart) {
            SiteServiceStatus result =
                    independent ? processes.start(saved) : servers.startSite(siteId);
            if (result.errorMessage() != null) {
                return new SiteServiceStatus(
                        result.status(),
                        result.port(),
                        result.accessUrl(),
                        "设置已保存，但服务重启失败：" + result.errorMessage());
            }
            return result;
        }
        return independent ? processes.getStatus(saved) : servers.getStatus(saved);
    }

    private String normalizeDomain(String input) {
        if (input == null || input.isBlank()) return null;
        String domain = input.trim();
        try {
            URI uri = URI.create("http://" + domain);
            if (domain.length() > 253
                    || uri.getHost() == null
                    || uri.getPort() != -1
                    || uri.getRawUserInfo() != null
                    || !uri.getRawPath().isEmpty()
                    || uri.getRawQuery() != null
                    || uri.getRawFragment() != null) {
                throw badRequest("访问域名请填写域名或 IP，不包含协议、端口和路径");
            }
        } catch (IllegalArgumentException invalid) {
            throw badRequest("访问域名格式无效");
        }
        return domain;
    }

    private SiteProjectEntity requireSite(Long siteId) {
        SiteProjectEntity site = mapper.selectById(siteId);
        if (site == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "站点不存在");
        return site;
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    public record Settings(
            String siteDomain,
            String siteServiceIp,
            Integer siteServicePort,
            boolean previewAccessEnabled,
            String password) {
        @Override
        public String toString() {
            return "SiteServiceSettings[password=REDACTED]";
        }
    }

    public record Update(
            String siteDomain,
            String siteServiceIp,
            Integer siteServicePort,
            Boolean previewAccessEnabled,
            @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) @JSONField(serialize = false)
                    String password) {
        @Override
        public String toString() {
            return "SiteServiceSettingsUpdate[password=REDACTED]";
        }
    }
}
