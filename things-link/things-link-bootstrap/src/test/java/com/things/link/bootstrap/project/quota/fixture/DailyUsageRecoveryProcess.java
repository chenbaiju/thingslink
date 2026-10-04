package com.things.link.bootstrap.project.quota.fixture;

import com.things.link.project.application.DailyUsageReconciliationScanner;
import com.things.link.project.application.DailyUsageReconciliationService;
import com.things.link.project.application.ProjectUsageFactContributor;
import com.things.link.project.domain.DailyUsageReconciliationRepository;
import com.things.link.project.domain.DailyUsageScope;
import com.things.link.project.domain.DailyUsageValue;
import com.things.link.project.infrastructure.persistence.JdbcDailyUsageReconciliationRepository;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Private child JVM for one disposable test DB. Not an HTTP server or production entry point. */
public final class DailyUsageRecoveryProcess {
    private DailyUsageRecoveryProcess() { }
    private static final java.util.Properties CONFIG = new java.util.Properties();
    private static String stage = "private-configuration";

    public static void main(String[] ignored) throws Exception {
        try {
            configureLoopbackProxyBoundary();
            CONFIG.load(System.in);
            run();
        } catch (Throwable failure) {
            // JDBC exceptions and process arguments must not expose fixture credentials.
            System.err.println("Owned daily-usage child failed at " + stage);
            var seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable, Boolean>());
            for (Throwable cause = failure; cause != null && seen.size() < 8 && seen.add(cause); cause = cause.getCause()) {
                String sqlState = cause instanceof java.sql.SQLException sql ? sql.getSQLState() : null;
                System.err.println("cause=" + cause.getClass().getSimpleName()
                        + ";sqlState=" + (sqlState != null && sqlState.matches("[A-Z0-9]{5}") ? sqlState : "none"));
            }
            System.exit(1);
        }
    }

    private static void run() throws Exception {
        var directory = Path.of(required("R4_USAGE_DIRECTORY"));
        var scope = new DailyUsageScope(UUID.fromString(required("R4_USAGE_TENANT")),
                UUID.fromString(required("R4_USAGE_PROJECT")));
        boolean hold = "claim-hold".equals(required("R4_USAGE_MODE"));
        if (!hold && !"scan".equals(required("R4_USAGE_MODE"))) throw new IllegalArgumentException("mode");
        var datasource = new DriverManagerDataSource(loopbackJdbcUrl(required("R4_USAGE_URL")),
                required("R4_USAGE_USER"), required("R4_USAGE_PASSWORD"));
        stage = "driver-load";
        // A slim JVM has no Boot JDBC auto-configuration; load the actual driver explicitly.
        datasource.setDriverClassName("org.postgresql.Driver");
        java.sql.DriverManager.setLoginTimeout(5);
        var jdbc = new JdbcTemplate(datasource);
        stage = "ordinary-app-database-identity";
        if (!required("R4_USAGE_USER").equals(jdbc.queryForObject("SELECT current_user", String.class))
                || !"usage_process_recovery".equals(jdbc.queryForObject("SELECT current_database()", String.class))
                || !Boolean.TRUE.equals(jdbc.queryForObject("SELECT NOT rolsuper AND NOT rolbypassrls FROM pg_roles WHERE rolname=current_user", Boolean.class))
                || jdbc.queryForObject("SELECT count(*) FROM sys_project", Long.class) != 1L) {
            throw new IllegalStateException("requires owned DB and ordinary APP role");
        }
        stage = "production-repository-and-transaction-proxy";
        var repository = new JdbcDailyUsageReconciliationRepository(jdbc);
        DailyUsageReconciliationRepository guarded = new DailyUsageReconciliationRepository() {
            @Override public List<DailyUsageScope> claimDueScopes(int maximum) {
                var result = repository.claimDueScopes(maximum);
                if (result.stream().anyMatch(value -> !scope.equals(value)))
                    throw new IllegalStateException("unowned claim");
                if (!result.isEmpty()) {
                    try {
                        // delegate query has returned and auto-committed the production two-minute lease.
                        Files.writeString(directory.resolve("claimed"), "committed");
                        if (hold && !new CountDownLatch(1).await(180, TimeUnit.SECONDS))
                            throw new IllegalStateException("claim hold exceeded kill deadline");
                    } catch (Exception failure) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("claim marker or hold failed");
                    }
                }
                return result;
            }
            @Override public void lockScope(DailyUsageScope value) { repository.lockScope(value); }
            @Override public void mergeAbsolute(DailyUsageScope value, LocalDate day, DailyUsageValue usage) {
                repository.mergeAbsolute(value, day, usage);
            }
            @Override public void complete(DailyUsageScope value) { repository.complete(value); }
        };
        var service = new DailyUsageReconciliationService(guarded,
                List.of(new ProjectUsageFactContributor(jdbc)), new TransactionLocalRlsScope(jdbc));
        // Real annotation-based transaction advice wraps the production service. This slim child
        // does not boot the complete HTTP server or an annotation scheduler; its loop calls scan().
        var proxy = new ProxyFactory(service);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(datasource),
                new AnnotationTransactionAttributeSource()));
        var scanner = new DailyUsageReconciliationScanner(guarded,
                (DailyUsageReconciliationService) proxy.getProxy());
        Files.writeString(directory.resolve("ready"), "ordinary-app-role");
        stage = "production-scan";
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(160);
        do {
            scanner.scan();
            // Marker follows the returned transaction, never a pre-commit repository callback.
            if (Files.exists(directory.resolve("claimed"))) {
                var lease = jdbc.queryForObject("SELECT usage_reconcile_lease_until IS NULL FROM sys_project WHERE id=? AND tenant_id=?",
                        Boolean.class, scope.projectId(), scope.tenantId());
                if (Boolean.TRUE.equals(lease)) {
                    Files.writeString(directory.resolve("completed"), "committed");
                    return;
                }
            }
            Thread.sleep(10_000); // Same cadence as the production scanner; never shorten its lease.
        } while (System.nanoTime() < deadline);
        throw new IllegalStateException("natural lease recovery deadline");
    }

    /** Same local-process boundary as the application/other JVM fixtures; never changes host settings. */
    public static void configureLoopbackProxyBoundary() {
        System.setProperty("socksProxyHost", "");
        System.setProperty("http.proxyHost", "");
        System.setProperty("socksNonProxyHosts", "localhost|127.0.0.1|[::1]");
        System.setProperty("http.nonProxyHosts", "localhost|127.0.0.1|[::1]");
    }

    /** Testcontainers exposes this private DB on loopback; do not depend on child-JVM DNS. */
    public static String loopbackJdbcUrl(String value) {
        try {
            if (!value.startsWith("jdbc:postgresql://")) throw new IllegalArgumentException();
            var uri = java.net.URI.create(value.substring("jdbc:".length()));
            if (!List.of("localhost", "127.0.0.1").contains(uri.getHost())
                    || uri.getPort() < 1 || uri.getPort() > 65535 || uri.getUserInfo() != null
                    || !"/usage_process_recovery".equals(uri.getRawPath()) || uri.getFragment() != null
                    || (uri.getRawQuery() != null && !"loggerLevel=OFF".equals(uri.getRawQuery()))) {
                throw new IllegalArgumentException();
            }
            return "jdbc:postgresql://127.0.0.1:" + uri.getPort()
                    + "/usage_process_recovery" + (uri.getRawQuery() == null ? "" : "?loggerLevel=OFF");
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("Owned usage JDBC endpoint must be the exact loopback test database");
        }
    }

    private static String required(String key) {
        String value = CONFIG.getProperty(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("missing private fixture setting");
        return value;
    }
}
