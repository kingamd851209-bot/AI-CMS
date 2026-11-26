package com.xinhongru.cms.template;

public record TraditionalTemplateFile(
        String path, String contentType, long size, String checksum, boolean html) {}
