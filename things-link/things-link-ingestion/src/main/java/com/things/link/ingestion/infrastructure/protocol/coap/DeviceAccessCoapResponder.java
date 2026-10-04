package com.things.link.ingestion.infrastructure.protocol.coap;

import com.things.link.ingestion.application.access.coap.DeviceAccessCoapResponse;
import org.eclipse.californium.core.coap.CoAP;
import org.eclipse.californium.core.server.resources.CoapExchange;

/**
 * 把决策层结果写回 CoAP 的应答策略（接入合同 §3.4 的「空 ACK 只表示传输确认」）。
 *
 * <p>默认采用**分离响应**：先对可确认请求回一帧空 ACK（0.00，只表示传输已确认），业务结果随后由独立响应承载。
 * 这样做的理由有二：① 让「空 ACK ≠ 业务结果」成为**结构上**成立的事实，而不是依赖处理速度的偶然结果——设备只要
 * 看到空 ACK 就知道还没拿到业务结论，必须继续等独立响应；② 业务侧要查库、写 Outbox、等消息总线接管，若把传输 ACK
 * 压到业务完成之后才发，慢请求会让设备按 RFC 7252 默认重传（同一 MID 重发），把平台延迟放大成重复请求。</p>
 *
 * <p>需要"少一条消息"的部署可以把 {@code things-link.access.coap.separate-response} 关掉，退化为捎带响应；
 * 两种模式下客户端的业务结果都不变（集成用例断言分离模式下响应类型是独立消息而不是 ACK）。</p>
 */
final class DeviceAccessCoapResponder {

    /** 是否先回空 ACK 再单独回业务响应。 */
    private final boolean separateResponse;

    /**
     * @param separateResponse 是否采用分离响应
     */
    DeviceAccessCoapResponder(boolean separateResponse) {
        this.separateResponse = separateResponse;
    }

    /**
     * 按策略应答一次请求。
     *
     * @param exchange Californium 交换
     * @param response 决策层结果
     */
    void answer(CoapExchange exchange, DeviceAccessCoapResponse response) {
        if (separateResponse && exchange.advanced() != null && exchange.advanced().getRequest() != null
                && exchange.advanced().getRequest().isConfirmable()) {
            // 请求体与资源判定在 accept() 之后进行：空 ACK 只确认传输，不承诺任何业务结论。
            exchange.accept();
        }
        exchange.respond(toCoapCode(response.code()), response.body());
    }

    /**
     * 冻结响应码映射到 Californium 的 CoAP 响应码。
     *
     * <p>写成显式 switch：新增冻结码时编译器会强制在这里表态，而不是在运行时靠遍历枚举悄悄兜底。</p>
     *
     * @param code 冻结响应码
     * @return Californium 响应码
     */
    static CoAP.ResponseCode toCoapCode(DeviceAccessCoapResponse.CoapCode code) {
        return switch (code) {
            case CHANGED -> CoAP.ResponseCode.CHANGED;
            case CONTENT -> CoAP.ResponseCode.CONTENT;
            case BAD_REQUEST -> CoAP.ResponseCode.BAD_REQUEST;
            case UNAUTHORIZED -> CoAP.ResponseCode.UNAUTHORIZED;
            case FORBIDDEN -> CoAP.ResponseCode.FORBIDDEN;
            case NOT_FOUND -> CoAP.ResponseCode.NOT_FOUND;
            case IDEMPOTENCY_CONFLICT -> CoAP.ResponseCode.CONFLICT;
            case REQUEST_ENTITY_TOO_LARGE -> CoAP.ResponseCode.REQUEST_ENTITY_TOO_LARGE;
            case UNSUPPORTED_CONTENT_FORMAT -> CoAP.ResponseCode.UNSUPPORTED_CONTENT_FORMAT;
            case TOO_MANY_REQUESTS -> CoAP.ResponseCode.TOO_MANY_REQUESTS;
            case NOT_IMPLEMENTED -> CoAP.ResponseCode.NOT_IMPLEMENTED;
            case SERVICE_UNAVAILABLE -> CoAP.ResponseCode.SERVICE_UNAVAILABLE;
        };
    }
}
