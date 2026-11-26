package com.xinhongru.site.context;

public class SiteAccessDeniedException extends RuntimeException {
    public SiteAccessDeniedException(String message) {
        super(message);
    }
}
