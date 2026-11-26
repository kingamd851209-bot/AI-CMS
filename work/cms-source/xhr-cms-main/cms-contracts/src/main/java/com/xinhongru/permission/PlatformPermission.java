package com.xinhongru.permission;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 数据中台权限模型常量（平台组 platform_setting / platform_other_setting 的键语义）。
 *
 * <p>权限配到「类别」级别：键为 {@code 类型:类别id}（如 {@code product:12}），操作 id 为该键段内取值。
 */
public final class PlatformPermission {
    private PlatformPermission() {}

    /** 模块类型：产品中心 */
    public static final String TYPE_PRODUCT = "product";

    /** 模块类型：新闻中心 */
    public static final String TYPE_NEWS = "news";

    /** 模块类型：解决方案 */
    public static final String TYPE_SOLUTION = "solution";

    /** 模块类型：案例中心 */
    public static final String TYPE_CASE = "case";

    /** 操作 id：新增 */
    public static final int OP_ADD = 1;

    /** 操作 id：编辑 */
    public static final int OP_EDIT = 2;

    /** 操作 id：删除 */
    public static final int OP_DELETE = 3;

    /** 操作 id：查看 */
    public static final int OP_VIEW = 4;

    /** 操作 id：存为草稿 */
    public static final int OP_DRAFT = 5;

    /** 操作 id：存为发布 */
    public static final int OP_PUBLISH = 6;

    /** 操作 id：提交审核 */
    public static final int OP_SUBMIT_AUDIT = 7;

    /** 操作 id：审批通过（含旧「审核通过」） */
    public static final int OP_APPROVE = 8;

    /** 操作 id：置顶 */
    public static final int OP_TOP = 9;

    /** 操作 id：推荐 */
    public static final int OP_RECOMMEND = 10;

    /** 操作 id：一键分发 */
    public static final int OP_PUSH = 11;

    /** 模块类型（与 cms-platform 各中心路由一致） */
    public static final String[] TYPES = {TYPE_PRODUCT, TYPE_NEWS, TYPE_SOLUTION, TYPE_CASE};

    /** 模块标题（与 TYPES 同序） */
    public static final String[] TYPE_TITLES = {"产品中心", "新闻中心", "解决方案", "案例中心"};

    /** 操作标题（下标+1 = 操作 id），与中心页顶部按钮一一对应 */
    public static final String[] OP_TITLES = {
        "新增", "编辑", "删除", "查看", "存为草稿", "存为发布", "提交审核", "审批通过", "置顶", "推荐", "一键分发"
    };

    /** 权限键：类型:类别id */
    public static String key(String type, Integer categoryId) {
        return type + ":" + categoryId;
    }

    /** configType（product/news/solution/case/form）→ 模块类型；不在范围返回 null */
    public static String typeOfConfigType(String configType) {
        if (configType == null) {
            return null;
        }
        String type = configType.trim().toLowerCase();
        return switch (type) {
            case TYPE_PRODUCT, TYPE_NEWS, TYPE_SOLUTION, TYPE_CASE -> type;
            default -> null;
        };
    }

    /** batch-option 的 option 取值 → 操作 id；未知返回 null */
    public static Integer optionIdOfBatchOption(String option) {
        if (option == null) {
            return null;
        }
        return switch (option.trim().toLowerCase()) {
            case "delete" -> OP_DELETE;
            case "draft" -> OP_DRAFT;
            case "publish" -> OP_PUBLISH;
            case "audit" -> OP_SUBMIT_AUDIT;
            case "approve" -> OP_APPROVE;
            case "top" -> OP_TOP;
            case "recommend" -> OP_RECOMMEND;
            default -> null;
        };
    }

    /**
     * 解析平台组数据中台权限：platform_setting 逗号分隔键（{@code 类型:类别id}）， platform_other_setting
     * 分号分段、与键逐位对齐、段内逗号分隔操作 id。
     */
    public static Map<String, Set<Integer>> parsePlatformSetting(
            String platformSetting, String platformOtherSetting) {
        Map<String, Set<Integer>> result = new LinkedHashMap<>();
        if (platformSetting == null || platformSetting.isBlank()) {
            return result;
        }
        String[] keys = platformSetting.split(",", -1);
        String[] segments =
                platformOtherSetting == null ? new String[0] : platformOtherSetting.split(";", -1);
        for (int i = 0; i < keys.length; i++) {
            String key = keys[i].trim();
            if (key.isEmpty() || "0".equals(key)) {
                continue;
            }
            Set<Integer> options = new LinkedHashSet<>();
            String segment = i < segments.length ? segments[i] : "";
            if (!segment.isBlank()) {
                for (String raw : segment.split(",")) {
                    try {
                        options.add(Integer.parseInt(raw.trim()));
                    } catch (NumberFormatException ignored) {
                        // 忽略非数字操作 id
                    }
                }
            }
            result.put(key, options);
        }
        return result;
    }
}
