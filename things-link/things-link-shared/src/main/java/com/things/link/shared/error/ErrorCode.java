package com.things.link.shared.error;

/**
 * 业务错误码契约。
 *
 * <p>架构文档 11.1 要求错误响应同时携带 HTTP 状态码与业务错误码：
 * <b>HTTP 状态码表达语义类别，业务错误码表达具体原因，二者都要有。</b>
 * 只有 HTTP 码时客户端无法区分「参数错误」的十几种具体情形；只有业务码时
 * 通用 HTTP 中间件（网关、监控、重试策略）无法工作。
 *
 * <h2>分段规则</h2>
 * <pre>
 * 1xxxx  通用与参数校验
 * 2xxxx  认证与授权
 * 3xxxx  设备与物模型
 * 4xxxx  规则与告警
 * 5xxxx  配额与计量
 * 9xxxx  内部错误
 * </pre>
 *
 * <p><b>新增错误码必须先登记进 {@code docs/ERROR_CODES.md} 再使用。</b>
 * 不登记的后果不是混乱，而是<b>重复</b> —— 三个月后会出现两个含义不同的 30104，
 * 而客户端已经按其中一个写了处理逻辑。
 *
 * <p>各业务模块定义自己的枚举实现本接口，通用码见 {@link CommonErrorCode}。
 */
public interface ErrorCode {

    /**
     * 业务错误码，遵循上述分段规则。
     *
     * @return 五位业务错误码
     */
    int code();

    /**
     * 默认错误消息。可被 {@link BusinessException} 覆盖为更具体的描述。
     *
     * @return 面向调用方的中文消息
     */
    String defaultMessage();

    /**
     * 对应的 HTTP 状态码。
     *
     * <p>用 {@code int} 而不是 Spring 的 {@code HttpStatus}：shared 模块不得依赖
     * Spring（架构文档 10.2），该约束由 pom 的 banned-dependencies 强制。
     * 转换成 Spring 类型的工作在 support 模块的异常处理器里完成。
     *
     * @return HTTP 状态码
     */
    int httpStatus();

}
