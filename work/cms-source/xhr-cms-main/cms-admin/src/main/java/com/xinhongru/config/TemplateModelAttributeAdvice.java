package com.xinhongru.config;

import com.xinhongru.ai.admin.AdminTemplateSupport;
import com.xinhongru.web.utils.CommonUtils;

import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.time.Year;

@ControllerAdvice
public class TemplateModelAttributeAdvice {
    private final CommonUtils commonUtils;
    private final AdminTemplateSupport adminTemplateSupport;

    public TemplateModelAttributeAdvice(
            CommonUtils commonUtils, AdminTemplateSupport adminTemplateSupport) {
        this.commonUtils = commonUtils;
        this.adminTemplateSupport = adminTemplateSupport;
    }

    @ModelAttribute("commonUtils")
    public CommonUtils commonUtils() {
        return commonUtils;
    }

    @ModelAttribute("adminTemplate")
    public AdminTemplateSupport adminTemplateSupport() {
        return adminTemplateSupport;
    }

    @ModelAttribute("currentYear")
    public int currentYear() {
        return Year.now().getValue();
    }
}
