package com.xinhongru.content.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.io.Serializable;

/** The single field contract used by the catalog, model JSON, editor, validation and templates. */
@AllArgsConstructor(
        onConstructor_ =
                @com.fasterxml.jackson.annotation.JsonCreator(
                        mode = com.fasterxml.jackson.annotation.JsonCreator.Mode.DISABLED))
@NoArgsConstructor(onConstructor_ = @com.fasterxml.jackson.annotation.JsonCreator)
@Data
@Accessors(chain = true)
public class ContentFieldDefinition implements Serializable {
    /** Optional catalog row ID; model JSON uses fieldName as its stable identity. */
    private Integer id;

    /** Value supplied by server-rendered field adapters; not a second field schema. */
    private String value;

    public static boolean enabled(String flag) {
        return "on".equalsIgnoreCase(flag) || "true".equalsIgnoreCase(flag) || "1".equals(flag);
    }

    private String isOpen;
    private String fieldName;
    private String fromName;
    private String fieldType;

    /** 仅扩展DateTime：DAY为日期；缺失/null或SECOND保留原秒精度。 */
    private String datePrecision;

    private String isList;
    private String isColor;
    private String isCoordinate;

    /** 是否搜索 */
    private String isSearch;

    /** 明确允许公开站点搜索；独立于后台isSearch，旧模型默认关闭。 */
    private boolean publicSearch;

    /** 旧JSON缺失/null保持关闭；拒绝字符串/数字隐式转为公开许可。 */
    @com.fasterxml.jackson.annotation.JsonSetter("publicSearch")
    public ContentFieldDefinition setPublicSearch(Object value) {
        if (value != null && !(value instanceof Boolean)) {
            throw new IllegalArgumentException("publicSearch必须为布尔值");
        }
        this.publicSearch = Boolean.TRUE.equals(value);
        return this;
    }

    /** 只读 */
    private String isReadOnly;

    /** 是否必填 */
    private String isRequired;

    /** 是否联系电话 */
    private String isTel;

    /** 是否邮箱 */
    private String isEmail;

    /** 是否身份证号码 */
    private String isIdCard;

    /** 是否正整数 */
    private String isNumber;

    /** 是否网址 */
    private String isWebUrl;

    private String title;
    private String defaultValue;

    /** 输入方式：空/''=手动输入，enum=固定枚举，content=栏目内容 */
    private String inputType;

    /** 选择方式：true=多选（仅 inputType 为 enum/content 时有效），多选值以 ,a,b, 形式存文本列 */
    private Boolean inputMultiple;

    /** 允许不选：true=编辑器下拉存在一个空选项（仅 inputType 为 content/menu 时有效） */
    private Boolean allowEmpty;

    /**
     * 级联：该字段（栏目内容绑定二级栏目）的编辑器下拉随上级栏目的同名字段联动时，记录上级字段名（如 singleLine11）； 为空表示不级联。运行时按所选上级内容 id
     * 过滤本字段选项（内容 xgid = 上级内容 id）。
     */
    private String cascadeParentField;

    private String bak;
    private Integer maxLength;
    private Integer sort;
}
