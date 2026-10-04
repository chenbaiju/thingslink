package com.things.link.ingestion.infrastructure.protocol.http;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import com.things.link.support.tenant.DatabaseWorkload;
import com.things.link.support.tenant.DatabaseWorkloadContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 设备面 HTTP 请求的数据面路由：在安全链与任何业务事务之前建立 DATA 工作负载范围。
 *
 * <p>ADR 0045 的口径是「数据面入口走数据池」——MQTT 回调与 Kafka 消费早已如此，而新增三协议此前没有入口标签，
 * 设备流量因此占用控制池（生产默认 6 条、等待 500ms）。后果在 AX-6d 全量验证里被实测到：控制池被占满时设备面
 * 上报会先以 401 失败（认证拿不到连接），并发回复被 500ms 超时打成 500，等于设备流量能把管理面和新协议一起拖垮。</p>
 *
 * <p><b>为什么放在过滤器而不是服务层注解</b>：服务层注解会在「调用方已经开了控制面事务」时触发
 * 「事务已在 CONTROL 连接上开始，禁止途中切换到 DATA」的守卫（AX-6d 实测），因为这时的连接已经绑定在线程上。
 * 过滤器位于所有业务事务之外，是唯一能干净建立范围的位置。TCP 与 CoAP 没有过滤器链，各自的连接／请求处理线程
 * 就是入口，同样在进入业务前建立范围。</p>
 */
@ConditionalOnProperty(name = "things-link.deployment.role",
        havingValue = "device-access", matchIfMissing = true)
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class DeviceAccessHttpDataPlaneFilter extends OncePerRequestFilter {

    /** 设备面路径前缀；与安全链的 {@code DEVICE_ACCESS_ROOT} 同源，但不带 Ant 通配符。 */
    private static final String DEVICE_ACCESS_PREFIX = "/device-access/";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!request.getRequestURI().startsWith(DEVICE_ACCESS_PREFIX)) {
            // 管理面与控制台请求保持既有控制池路由，不因本过滤器改变行为。
            chain.doFilter(request, response);
            return;
        }
        try (DatabaseWorkloadContext.Scope ignored = DatabaseWorkloadContext.enter(DatabaseWorkload.DATA)) {
            chain.doFilter(request, response);
        }
    }
}
