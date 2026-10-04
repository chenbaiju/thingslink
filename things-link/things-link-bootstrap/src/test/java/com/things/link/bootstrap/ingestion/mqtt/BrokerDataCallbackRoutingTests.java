package com.things.link.bootstrap.ingestion.mqtt;

import com.things.link.device.api.controller.EmqxEventController;
import com.things.link.device.infrastructure.emqx.EmqxConnectionEventService;
import com.things.link.ingestion.api.controller.EmqxCommandReplyController;
import com.things.link.ingestion.api.controller.EmqxUplinkController;
import com.things.link.ingestion.api.dto.request.EmqxMessagePublishedRequest;
import com.things.link.ingestion.application.CommandReplyIngestionService;
import com.things.link.ingestion.application.RawUplinkIngestionService;
import com.things.link.support.tenant.DatabaseWorkload;
import com.things.link.support.tenant.DatabaseWorkloadAspect;
import com.things.link.support.tenant.DatabaseWorkloadContext;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** ADR 0045：Broker 消息与生命周期 Controller 必须在真实 Spring AOP 调用链中建立 DATA 路由。 */
class BrokerDataCallbackRoutingTests {

    /** 四个事件方法均须在服务调用前进入 DATA，并在 Controller 返回后恢复默认 CONTROL。 */
    @Test
    void brokerDataCallbacksEnterAndLeaveDataRoute() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(TestConfiguration.class)) {
            AtomicInteger observations = context.getBean(AtomicInteger.class);
            EmqxMessagePublishedRequest request = new EmqxMessagePublishedRequest(
                    "project/device", "tc/v1/project/device/up/property/report", "e30=", 1, false,
                    "device", 1L);

            context.getBean(EmqxUplinkController.class).messagePublished(request);
            assertThat(DatabaseWorkloadContext.current()).isEqualTo(DatabaseWorkload.CONTROL);
            context.getBean(EmqxCommandReplyController.class).commandReply(request);
            assertThat(DatabaseWorkloadContext.current()).isEqualTo(DatabaseWorkload.CONTROL);
            context.getBean(EmqxEventController.class).connected(Map.of(
                    "username", "project/device", "clientid", "device"));
            assertThat(DatabaseWorkloadContext.current()).isEqualTo(DatabaseWorkload.CONTROL);
            context.getBean(EmqxEventController.class).disconnected(Map.of(
                    "username", "project/device", "clientid", "device"));

            assertThat(observations).hasValue(4);
            assertThat(DatabaseWorkloadContext.current()).isEqualTo(DatabaseWorkload.CONTROL);
        }
    }

    /** 只装配路由切面和三个生产 Controller，服务 mock 在真正的 advice 内观察线程路由。 */
    @Configuration(proxyBeanMethods = false)
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    static class TestConfiguration {
        /** @return 路由观察次数 */
        @Bean
        AtomicInteger observations() {
            return new AtomicInteger();
        }

        /** @return 生产 DATA 路由切面 */
        @Bean
        DatabaseWorkloadAspect databaseWorkloadAspect() {
            return new DatabaseWorkloadAspect();
        }

        /** @return 原始上行服务替身 */
        @Bean
        RawUplinkIngestionService rawUplinkIngestionService(AtomicInteger observations) {
            RawUplinkIngestionService service = mock(RawUplinkIngestionService.class);
            when(service.ingest(any())).thenAnswer(invocation -> observeData(observations));
            return service;
        }

        /** @return 命令回复服务替身 */
        @Bean
        CommandReplyIngestionService commandReplyIngestionService(AtomicInteger observations) {
            CommandReplyIngestionService service = mock(CommandReplyIngestionService.class);
            when(service.ingest(any())).thenAnswer(invocation -> observeData(observations));
            return service;
        }

        /** @return 连接事件服务替身 */
        @Bean
        EmqxConnectionEventService emqxConnectionEventService(AtomicInteger observations) {
            EmqxConnectionEventService service = mock(EmqxConnectionEventService.class);
            doAnswer(invocation -> observeData(observations)).when(service)
                    .connected(anyString(), anyString(), any(), any(), any(), any());
            doAnswer(invocation -> observeData(observations)).when(service)
                    .disconnected(anyString(), anyString(), any(), any(), any());
            return service;
        }

        /** @return 生产原始上行 Controller */
        @Bean
        EmqxUplinkController emqxUplinkController(RawUplinkIngestionService service) {
            return new EmqxUplinkController(service);
        }

        /** @return 生产命令回复 Controller */
        @Bean
        EmqxCommandReplyController emqxCommandReplyController(CommandReplyIngestionService service) {
            return new EmqxCommandReplyController(service);
        }

        /** @return 生产连接事件 Controller */
        @Bean
        EmqxEventController emqxEventController(EmqxConnectionEventService service) {
            return new EmqxEventController(service);
        }

        /** 在服务边界断言路由；若 Controller 未被切面代理，本测试会直接指出 CONTROL。 */
        private static boolean observeData(AtomicInteger observations) {
            assertThat(DatabaseWorkloadContext.current()).isEqualTo(DatabaseWorkload.DATA);
            observations.incrementAndGet();
            return true;
        }
    }
}
