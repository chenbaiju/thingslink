package com.things.link.ingestion.infrastructure.protocol.coap;

import com.things.link.ingestion.application.access.coap.DeviceAccessCoapResponse;
import com.things.link.ingestion.application.access.coap.DeviceAccessCoapResponse.CoapCode;
import org.eclipse.californium.core.coap.CoAP;
import org.eclipse.californium.core.network.Exchange;
import org.eclipse.californium.core.server.resources.CoapExchange;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CoAP 应答策略：空 ACK 只表示传输确认，业务结果必须由独立响应承载（接入合同 §3.4）。
 *
 * <p>顺序本身就是契约，因此用 {@link InOrder} 断言「先 accept 再 respond」，而不是只看"两个方法都被调用过"——
 * 反过来（先回业务响应再补空 ACK）在设备侧会把业务结果和传输确认混成一件事。</p>
 */
class DeviceAccessCoapResponderTests {

    /** 分离响应模式（默认）：可确认请求先回空 ACK，再单独回业务响应。 */
    @Test
    void separateModeSendsEmptyAckBeforeBusinessResponse() {
        CoapExchange exchange = exchange(true);

        new DeviceAccessCoapResponder(true).answer(exchange,
                new DeviceAccessCoapResponse(CoapCode.CHANGED, "{}"));

        InOrder order = inOrder(exchange);
        order.verify(exchange).accept();
        order.verify(exchange).respond(CoAP.ResponseCode.CHANGED, "{}");
    }

    /** 关闭分离响应时退化为捎带响应：不得再单独发空 ACK。 */
    @Test
    void piggybackModeNeverSendsEmptyAck() {
        CoapExchange exchange = exchange(true);

        new DeviceAccessCoapResponder(false).answer(exchange,
                new DeviceAccessCoapResponse(CoapCode.CONTENT, "{\"commands\":[]}"));

        verify(exchange, never()).accept();
        verify(exchange).respond(CoAP.ResponseCode.CONTENT, "{\"commands\":[]}");
    }

    /** 非确认请求本来就没有 ACK 可发：分离模式下也不得调用 accept。 */
    @Test
    void nonConfirmableRequestGetsNoEmptyAck() {
        CoapExchange exchange = exchange(false);

        new DeviceAccessCoapResponder(true).answer(exchange,
                new DeviceAccessCoapResponse(CoapCode.NOT_FOUND, ""));

        verify(exchange, never()).accept();
        verify(exchange).respond(CoAP.ResponseCode.NOT_FOUND, "");
    }

    /** 冻结响应码到 Californium 响应码的映射逐项一致（错误码选错是契约缺陷）。 */
    @Test
    void frozenCodesMapToCaliforniumCodes() {
        assertThat(DeviceAccessCoapResponder.toCoapCode(CoapCode.CHANGED)).isEqualTo(CoAP.ResponseCode.CHANGED);
        assertThat(DeviceAccessCoapResponder.toCoapCode(CoapCode.CONTENT)).isEqualTo(CoAP.ResponseCode.CONTENT);
        assertThat(DeviceAccessCoapResponder.toCoapCode(CoapCode.BAD_REQUEST))
                .isEqualTo(CoAP.ResponseCode.BAD_REQUEST);
        assertThat(DeviceAccessCoapResponder.toCoapCode(CoapCode.UNAUTHORIZED))
                .isEqualTo(CoAP.ResponseCode.UNAUTHORIZED);
        assertThat(DeviceAccessCoapResponder.toCoapCode(CoapCode.FORBIDDEN)).isEqualTo(CoAP.ResponseCode.FORBIDDEN);
        assertThat(DeviceAccessCoapResponder.toCoapCode(CoapCode.NOT_FOUND)).isEqualTo(CoAP.ResponseCode.NOT_FOUND);
        assertThat(DeviceAccessCoapResponder.toCoapCode(CoapCode.IDEMPOTENCY_CONFLICT))
                .isEqualTo(CoAP.ResponseCode.CONFLICT);
        assertThat(DeviceAccessCoapResponder.toCoapCode(CoapCode.REQUEST_ENTITY_TOO_LARGE))
                .isEqualTo(CoAP.ResponseCode.REQUEST_ENTITY_TOO_LARGE);
        assertThat(DeviceAccessCoapResponder.toCoapCode(CoapCode.UNSUPPORTED_CONTENT_FORMAT))
                .isEqualTo(CoAP.ResponseCode.UNSUPPORTED_CONTENT_FORMAT);
        assertThat(DeviceAccessCoapResponder.toCoapCode(CoapCode.TOO_MANY_REQUESTS))
                .isEqualTo(CoAP.ResponseCode.TOO_MANY_REQUESTS);
        assertThat(DeviceAccessCoapResponder.toCoapCode(CoapCode.NOT_IMPLEMENTED))
                .isEqualTo(CoAP.ResponseCode.NOT_IMPLEMENTED);
        assertThat(DeviceAccessCoapResponder.toCoapCode(CoapCode.SERVICE_UNAVAILABLE))
                .isEqualTo(CoAP.ResponseCode.SERVICE_UNAVAILABLE);
    }

    /**
     * @param confirmable 请求是否可确认
     * @return 带请求上下文的交换替身
     */
    private static CoapExchange exchange(boolean confirmable) {
        CoapExchange exchange = mock(CoapExchange.class);
        Exchange advanced = mock(Exchange.class);
        org.eclipse.californium.core.coap.Request request = mock(
                org.eclipse.californium.core.coap.Request.class);
        when(exchange.advanced()).thenReturn(advanced);
        when(advanced.getRequest()).thenReturn(request);
        when(request.isConfirmable()).thenReturn(confirmable);
        return exchange;
    }
}
