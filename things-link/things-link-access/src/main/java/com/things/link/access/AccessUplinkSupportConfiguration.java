package com.things.link.access;

import com.things.link.ingestion.application.DevicePropertyReportReader;
import com.things.link.ingestion.application.DownlinkPreprocessingChain;
import com.things.link.ingestion.application.DownlinkPreprocessor;
import com.things.link.ingestion.application.UplinkTimestampPolicy;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/** 接入进程复用原消息读取与时间策略，平台原始 Kafka 消费者不在此装配。 */
@Configuration(proxyBeanMethods = false)
public class AccessUplinkSupportConfiguration {

    @Bean
    DevicePropertyReportReader devicePropertyReportReader(ObjectMapper objectMapper) {
        return new DevicePropertyReportReader(objectMapper);
    }

    @Bean
    UplinkTimestampPolicy uplinkTimestampPolicy(
            @Value("${things-link.ingestion.max-future-skew:5m}") Duration maxFutureSkew) {
        return new UplinkTimestampPolicy(maxFutureSkew);
    }

    @Bean
    DownlinkPreprocessingChain downlinkPreprocessingChain(List<DownlinkPreprocessor> preprocessors) {
        return new DownlinkPreprocessingChain(preprocessors);
    }
}
