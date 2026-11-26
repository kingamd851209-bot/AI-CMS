package com.xinhongru.cms.formservice;

/**
 * 表单服务接口业务异常：携带对外错误码与可选 data（如字段级校验明细）。
 *
 * <p>错误码约定：401 key 缺失/无效/停用；404 分类不存在或不属于本站点；422 校验失败； 429 提交限流；500 未预期异常。
 */
public class FormApiException extends RuntimeException {
    private final int code;
    private final transient Object data;

    public FormApiException(int code, String message) {
        this(code, message, null);
    }

    public FormApiException(int code, String message, Object data) {
        super(message);
        this.code = code;
        this.data = data;
    }

    public int code() {
        return code;
    }

    public Object data() {
        return data;
    }
}
