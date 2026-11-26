package com.xinhongru.security.preview;

import java.util.Map;

/** 两类 HTTP 服务共用的预发布门禁响应。 */
public record PreviewAccessResponse(int status, Map<String, String> headers, String body) {}
