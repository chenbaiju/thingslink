package com.things.link.shared.error;

/**
 * 通用错误码：{@code 1xxxx} 参数与请求校验，{@code 9xxxx} 内部错误。
 *
 * <p>业务领域的错误码由各模块自行定义（例如 {@code DeviceErrorCode} 用 3xxxx），
 * 不要往这里堆 —— 这个枚举一旦开始承载业务含义，就会变成又一个垃圾桶。
 *
 * <p>与 {@code docs/ERROR_CODES.md} 保持同步：改动本枚举时必须同时改文档。
 */
public enum CommonErrorCode implements ErrorCode {

    /** 请求参数不合法（Bean Validation 失败、类型转换失败等）。 */
    INVALID_PARAMETER(10001, "请求参数不合法", 400),

    /** 请求体缺失或无法解析。 */
    MALFORMED_REQUEST(10002, "请求体格式错误", 400),

    /** 请求的资源不存在。 */
    RESOURCE_NOT_FOUND(10004, "请求的资源不存在", 404),

    /** 资源当前状态不允许该操作（例如已发布的设备类型不可改标识符）。 */
    RESOURCE_STATE_CONFLICT(10009, "资源当前状态不允许该操作", 409),

    /**
     * 同一 Idempotency-Key 的请求正在处理中。
     *
     * <p>本码用于首次请求尚未完成时的并发重试（架构文档 11.1）。
     */
    IDEMPOTENCY_IN_PROGRESS(10010, "相同幂等键的请求正在处理中，请稍后重试", 409),

    /** 请求体超过平台写接口上限，必须在进入 JSON 解析和幂等缓存前拒绝。 */
    PAYLOAD_TOO_LARGE(10013, "请求体超过允许的大小", 413),

    /** 公共幂等墓碑确认请求已完成，但旧响应因授权与凭据安全不可重放。 */
    IDEMPOTENCY_RESULT_NOT_REPLAYABLE(10014, "相同幂等键的请求已经完成，请查询业务结果", 409),

    /**
     * 触发限流（架构文档 7.3）。
     *
     * <p>放在通用段而不是各领域自己定义：登录、注册、设备指令、数据导出都要限流，
     * 每个领域各定一个码的话，客户端就得为「请求过于频繁」写好几套处理分支，
     * 而它们的处置完全一样 —— 退避后重试。
     *
     * <p><b>消息刻意不含任何与账号有关的信息。</b>「该账号已被锁定」「该邮箱试错
     * 次数过多」之类的提示会让限流本身变成账号枚举通道：攻击者用任意口令试探，
     * 凡是能触发这类提示的邮箱就是已注册账号。
     */
    TOO_MANY_REQUESTS(10029, "请求过于频繁，请稍后重试", 429),

    /**
     * 未归类的内部错误。
     *
     * <p>返回给调用方的消息刻意保持模糊 —— 内部异常信息可能包含表名、SQL 片段
     * 或文件路径，泄露给外部会成为攻击面。真实原因只进服务端日志，客户端凭
     * traceId 找运维定位。
     */
    INTERNAL_ERROR(90000, "服务内部错误", 500);

    private final int code;
    private final String defaultMessage;
    private final int httpStatus;

    CommonErrorCode(int code, String defaultMessage, int httpStatus) {
        this.code = code;
        this.defaultMessage = defaultMessage;
        this.httpStatus = httpStatus;
    }

    @Override
    public int code() {
        return code;
    }

    @Override
    public String defaultMessage() {
        return defaultMessage;
    }

    @Override
    public int httpStatus() {
        return httpStatus;
    }

}
