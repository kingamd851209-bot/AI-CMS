package com.xinhongru.content.model;

/** Persisted content workflow codes shared by read and write responses. */
public enum ContentStatus {
    DRAFT(0),
    PUBLISHED(1),
    PENDING_REVIEW(2),
    REJECTED(3),
    ARCHIVED(4);
    private final int code;

    ContentStatus(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static ContentStatus fromCode(Integer code) {
        for (ContentStatus status : values())
            if (code != null && status.code == code) return status;
        return DRAFT;
    }
}
