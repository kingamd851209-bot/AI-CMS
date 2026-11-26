package com.xinhongru.api.admin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xinhongru.i18n.entity.LanguageEntity;
import com.xinhongru.i18n.mapper.LanguageMapper;
import com.xinhongru.i18n.service.LanguageService;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin/i18n/languages")
public class LanguageAdminApiController {
    private final LanguageMapper languageMapper;
    private final LanguageService languageService;

    public LanguageAdminApiController(
            LanguageMapper languageMapper, LanguageService languageService) {
        this.languageMapper = languageMapper;
        this.languageService = languageService;
    }

    @GetMapping
    public AdminApiResponse<List<LanguageEntity>> list() {
        LambdaQueryWrapper<LanguageEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(LanguageEntity::getDeleted, 0)
                .orderByAsc(LanguageEntity::getSort)
                .orderByAsc(LanguageEntity::getId);
        return AdminApiResponse.success(languageMapper.selectList(wrapper));
    }

    @PostMapping
    public AdminApiResponse<LanguageEntity> create(@RequestBody LanguageCreateRequest request) {
        LanguageEntity language =
                languageService.createLanguage(
                        request.languageCode(),
                        request.languageName(),
                        request.nativeName(),
                        request.locale(),
                        request.sort(),
                        request.enabled() == null || request.enabled(),
                        Boolean.TRUE.equals(request.defaultLanguage()));
        return AdminApiResponse.success(language);
    }

    @PutMapping("/{languageId}")
    public AdminApiResponse<LanguageEntity> update(
            @PathVariable Long languageId, @RequestBody LanguageUpdateRequest request) {
        languageService.updateLanguage(
                languageId,
                request.languageName(),
                request.nativeName(),
                request.locale(),
                request.sort(),
                request.enabled());
        return AdminApiResponse.success(languageMapper.selectById(languageId));
    }

    @PostMapping("/{languageId}/default")
    public AdminApiResponse<Void> setDefault(@PathVariable Long languageId) {
        languageService.setDefaultLanguage(languageId);
        return AdminApiResponse.success();
    }

    @PostMapping("/{languageId}/enable")
    public AdminApiResponse<Void> enable(@PathVariable Long languageId) {
        languageService.enableLanguage(languageId);
        return AdminApiResponse.success();
    }

    @PostMapping("/{languageId}/disable")
    public AdminApiResponse<Void> disable(@PathVariable Long languageId) {
        languageService.disableLanguage(languageId);
        return AdminApiResponse.success();
    }

    @DeleteMapping("/{languageId}")
    public AdminApiResponse<Void> delete(@PathVariable Long languageId) {
        languageService.deleteLanguage(languageId);
        return AdminApiResponse.success();
    }
}
