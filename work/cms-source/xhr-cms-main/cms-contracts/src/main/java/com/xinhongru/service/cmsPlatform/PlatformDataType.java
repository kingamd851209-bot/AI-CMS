package com.xinhongru.service.cmsPlatform;

import com.xinhongru.permission.PlatformPermission;

/** 平台数据类型：t_platform_contents / t_platform_oneshot 通过 data_type 区分不同类别的配置与内容。 */
public enum PlatformDataType {
    PRODUCT(1, "产品", PlatformPermission.TYPE_PRODUCT),
    NEWS(2, "新闻", PlatformPermission.TYPE_NEWS),
    CASE(3, "案例", PlatformPermission.TYPE_CASE),
    SOLUTION(4, "解决方案", PlatformPermission.TYPE_SOLUTION);

    private final int code;
    private final String label;
    private final String permissionType;

    PlatformDataType(int code, String label, String permissionType) {
        this.code = code;
        this.label = label;
        this.permissionType = permissionType;
    }

    public int code() {
        return code;
    }

    public String label() {
        return label;
    }

    /** 对应的数据中台权限模块类型（PlatformPermission.TYPE_*） */
    public String permissionType() {
        return permissionType;
    }

    /** 由 URL 模块段（product/news/case/solution）解析数据类型 */
    public static PlatformDataType ofModule(String module) {
        for (PlatformDataType type : values()) {
            if (type.permissionType.equals(module)) {
                return type;
            }
        }
        throw new IllegalArgumentException("未知的数据类型: " + module);
    }

    /** 由 data_type 数值解析数据类型，未匹配返回 null */
    public static PlatformDataType ofCode(Integer code) {
        if (code == null) {
            return null;
        }
        for (PlatformDataType type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        return null;
    }
}
