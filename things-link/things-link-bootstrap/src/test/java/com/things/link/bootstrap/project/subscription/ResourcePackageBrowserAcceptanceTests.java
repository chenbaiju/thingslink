package com.things.link.bootstrap.project.subscription;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.things.link.iam.application.AuthRateLimiter;
import com.things.link.project.application.TenantRefundService;
import com.things.link.project.application.TenantResourcePackageService;
import com.things.link.project.domain.TenantOrder;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * R4-4a: real PostgreSQL, application HTTP and built Console purchase/refund acceptance.
 * Only this opt-in test exposes a loopback fixture control; there is no production trading API.
 * Requires a built Console, Node, Playwright Chromium and the normal integration dependencies.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@EnabledIfSystemProperty(named = "thingslink.test.resource-package-browser", matches = "true")
class ResourcePackageBrowserAcceptanceTests extends AbstractIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PASSWORD = "resource-package-test-123";
    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AuthRateLimiter limiter;
    @Autowired private TenantResourcePackageService packages;
    @Autowired private TenantRefundService refunds;
    @Value("${local.server.port}") private int port;
    private UUID accountId;
    private UUID tenantId;
    private UUID projectId;

    @Test
    void purchasedCapacityAndRefundAgreeWithBrowserAndDeviceAdmission() throws Exception {
        String nonce = UUID.randomUUID().toString();
        String email = "package-browser-" + nonce + "@example.com";
        String projectName = "资源包浏览器-" + nonce;
        limiter.clear();
        var registration = mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("email", email, "password", PASSWORD))))
                .andReturn();
        assertThat(registration.getResponse().getStatus()).isBetween(200, 299);
        accountId = jdbc.queryForObject("SELECT id FROM sys_account WHERE email = ?", UUID.class, email);
        tenantId = jdbc.queryForObject("SELECT tenant_id FROM sys_tenant_member WHERE account_id = ?",
                UUID.class, accountId);
        jdbc.update("UPDATE sys_account SET email_verified_at = now() WHERE id = ?", accountId);
        var login = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("email", email, "password", PASSWORD))))
                .andExpect(status().isOk()).andReturn();
        String token = JSON.readTree(login.getResponse().getContentAsString()).get("accessToken").asString();
        var project = mockMvc.perform(post("/api/v1/projects").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("name", projectName, "region", "sh-1"))))
                .andReturn();
        // Retain this owned identity before checking the response contract so cleanup can run on failure.
        var createdProject = JSON.readTree(project.getResponse().getContentAsString());
        if (createdProject.has("id")) projectId = UUID.fromString(createdProject.get("id").asString());
        assertThat(project.getResponse().getStatus()).as("Existing project creation HTTP contract").isEqualTo(200);
        assertThat(projectId).isNotNull();
        assertThat(limit()).isEqualTo(3);

        Path workspace = Path.of(System.getProperty("thingslink.test.workspace", "../..")).toAbsolutePath().normalize();
        Path console = workspace.resolve("things-link-console");
        assertThat(console.resolve("dist/index.html")).as("Build the current Console candidate first").exists();
        Path evidence = workspace.resolve("logs/verification/r4-resource-package-browser/" + nonce);
        Files.createDirectories(evidence);
        String credential = UUID.randomUUID() + "-" + UUID.randomUUID();
        var fixture = new PackageControl(credential, nonce);
        var control = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        control.createContext("/", fixture::handle);
        control.start();
        Process process = null;
        try {
            var builder = new ProcessBuilder("node", console.resolve("node_modules/tsx/dist/cli.mjs").toString(),
                    console.resolve("scripts/run-plan-resource-package-e2e.ts").toString());
            builder.directory(console.toFile()).redirectErrorStream(true).redirectOutput(evidence.resolve("browser.log").toFile());
            builder.environment().putAll(Map.of(
                    "E2E_RESOURCE_PACKAGE_BACKEND", "http://127.0.0.1:" + port,
                    "E2E_RESOURCE_PACKAGE_CONTROL", "http://127.0.0.1:" + control.getAddress().getPort(),
                    "E2E_RESOURCE_PACKAGE_CREDENTIAL", credential,
                    "E2E_RESOURCE_PACKAGE_EMAIL", email,
                    "E2E_RESOURCE_PACKAGE_PASSWORD", PASSWORD,
                    "E2E_RESOURCE_PACKAGE_PROJECT_ID", projectId.toString(),
                    "E2E_RESOURCE_PACKAGE_PROJECT_NAME", projectName,
                    "E2E_RUN_DIR", evidence.toString()));
            process = builder.start();
            assertThat(process.waitFor(180, TimeUnit.SECONDS)).as("Browser deadline; evidence %s", evidence).isTrue();
            assertThat(process.exitValue()).as("Browser result; evidence %s", evidence).isZero();
            assertThat(fixture.purchaseCalls).isEqualTo(2);
            assertThat(fixture.refundCalls).isEqualTo(2);
            assertThat(limit()).isEqualTo(3);
            var previousScope = TenantContext.current();
            TenantContext.set(new TenantScope(tenantId, projectId, accountId));
            try {
                assertThat(jdbc.queryForObject("SELECT count(*) FROM dev_device WHERE project_id = ?", Long.class, projectId))
                        .isEqualTo(5);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_tenant_resource_package WHERE tenant_id = ? AND status = 'REFUNDED'",
                        Long.class, tenantId)).isEqualTo(1);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_tenant_refund WHERE tenant_id = ?", Long.class, tenantId))
                        .isEqualTo(1);
            } finally {
                if (previousScope.isPresent()) TenantContext.set(previousScope.get());
                else TenantContext.clear();
            }
        } finally {
            try {
                if (process != null && process.isAlive()) {
                    // Capture only this browser driver's descendants before terminating their parent.
                    var children = process.toHandle().descendants().toList();
                    children.forEach(ProcessHandle::destroy);
                    process.destroy();
                    if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly();
                    children.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
                    assertThat(process.waitFor(5, TimeUnit.SECONDS)).as("Browser driver reaped before fixture cleanup").isTrue();
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                    for (ProcessHandle child : children) {
                        if (child.isAlive()) {
                            child.onExit().get(Math.max(1L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                        }
                    }
                }
            } finally {
                control.stop(0);
            }
        }
    }

    /** Fixed own-tenant transitions, no caller-controlled identity, dimensions, amounts or SQL. */
    private final class PackageControl {
        private final byte[] credential;
        private final String event;
        private TenantOrder order;
        private UUID packageId;
        private volatile int purchaseCalls;
        private volatile int refundCalls;

        private PackageControl(String credential, String event) {
            this.credential = ("Bearer " + credential).getBytes(StandardCharsets.UTF_8);
            this.event = event;
        }

        private synchronized void handle(HttpExchange exchange) throws IOException {
            try {
                String header = exchange.getRequestHeaders().getFirst("Authorization");
                if (header == null || !MessageDigest.isEqual(credential, header.getBytes(StandardCharsets.UTF_8))) {
                    respond(exchange, 403, Map.of("error", "Forbidden"));
                    return;
                }
                if (!"POST".equals(exchange.getRequestMethod())) {
                    respond(exchange, 405, Map.of("error", "POST required"));
                    return;
                }
                // No request payload is accepted; even authenticated callers cannot select another subject.
                if (exchange.getRequestURI().getRawQuery() != null
                        || exchange.getRequestHeaders().containsKey("Transfer-Encoding")
                        || !"0".equals(exchange.getRequestHeaders().getFirst("Content-Length"))) {
                    respond(exchange, 400, Map.of("error", "Empty request required"));
                    return;
                }
                if ("/purchase".equals(exchange.getRequestURI().getPath())) {
                    if (refundCalls != 0) {
                        respond(exchange, 409, Map.of("error", "Purchase stage ended"));
                        return;
                    }
                    if (order == null) order = packages.createSimulatedPackageOrder(tenantId, "DEVICES_MAX", 2, null);
                    var activated = packages.applySimulatedPackagePaymentSucceeded(order.id(), "pkg-browser-pay-" + event);
                    packageId = activated.packageId();
                    purchaseCalls++;
                    respond(exchange, 200, Map.of("orderId", order.id(), "packageId", packageId,
                            "status", activated.status().name(), "activated", activated.activated(), "limit", limit()));
                } else if ("/refund".equals(exchange.getRequestURI().getPath()) && packageId != null) {
                    var refund = refunds.refundPackageOrder(tenantId, order.id(), order.amountCents(),
                            "R4 isolated browser fixture full refund", "pkg-browser-refund-" + event);
                    refundCalls++;
                    String packageStatus = jdbc.queryForObject("SELECT status FROM sys_tenant_resource_package WHERE id = ? AND tenant_id = ?",
                            String.class, packageId, tenantId);
                    respond(exchange, 200, Map.of("orderId", order.id(), "packageId", packageId,
                            "refundId", refund.id(), "status", packageStatus, "limit", limit()));
                } else {
                    respond(exchange, 404, Map.of("error", "Unknown transition"));
                }
            } catch (Exception error) {
                // Stack trace remains in the private backend test log; no credentials or arbitrary facts are returned.
                error.printStackTrace();
                respond(exchange, 500, Map.of("error", "Fixture transition failed"));
            } finally {
                TenantContext.clear();
                exchange.close();
            }
        }
    }

    private static void respond(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private Long limit() {
        return jdbc.queryForObject("SELECT device_limit_value FROM device_project_quota_policy(?, ?)",
                Long.class, tenantId, projectId);
    }

    /** Only this test's registered identities are removed; immutable audit facts remain. */
    @AfterEach
    void cleanUp() {
        var previousScope = TenantContext.current();
        if (tenantId != null) TenantContext.set(new TenantScope(tenantId, projectId, accountId));
        try {
            if (projectId != null) {
                jdbc.update("DELETE FROM dev_device WHERE project_id = ?", projectId);
                jdbc.update("DELETE FROM sys_project_member WHERE project_id = ?", projectId);
            }
            if (tenantId != null) {
                for (String table : new String[]{"sys_tenant_order_payment_failure", "sys_tenant_refund",
                        "sys_tenant_resource_package", "sys_tenant_subscription_notification_intent",
                        "sys_project_commercial_restriction", "sys_tenant_subscription_pending_change",
                        "sys_tenant_subscription", "sys_tenant_order", "sys_tenant_member"}) {
                    jdbc.update("DELETE FROM " + table + " WHERE tenant_id = ?", tenantId);
                }
            }
            if (projectId != null) jdbc.update("DELETE FROM sys_project WHERE id = ?", projectId);
            if (accountId != null) jdbc.update("DELETE FROM sys_account WHERE id = ?", accountId);
            if (tenantId != null) jdbc.update("DELETE FROM sys_tenant WHERE id = ?", tenantId);
        } finally {
            if (previousScope.isPresent()) TenantContext.set(previousScope.get());
            else TenantContext.clear();
        }
    }
}
