package com.xinhongru.cms.template;

public class TraditionalTemplatePackageRejectedException extends IllegalArgumentException {
    private final String code;

    public TraditionalTemplatePackageRejectedException(String code, String message) {
        super(code + ": " + message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
