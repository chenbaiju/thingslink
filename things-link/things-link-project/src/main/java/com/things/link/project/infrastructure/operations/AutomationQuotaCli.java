package com.things.link.project.infrastructure.operations;

import com.things.link.support.cache.CacheInvalidationPublisher;
import com.things.link.support.cache.CacheInvalidationEvent;
import com.things.link.support.cache.CacheInvalidationMetrics;
import com.things.link.support.cache.CacheResource;
import com.things.link.support.cache.CacheInvalidationOperation;
import com.things.link.shared.id.Uuid7;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.json.JsonMapper;
import java.io.PrintStream;
import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

/** ADR0153独立运营进程；只调用受限SQL函数，不装配应用、HTTP、调度或Flyway。 */
public final class AutomationQuotaCli {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private AutomationQuotaCli() { }

    /** 凭据只从环境读取，stdout仅一个不含秘密的JSON回执。 */
    public static void main(String[] args) {
        System.setProperty("socksProxyHost", "");
        var logging = (ch.qos.logback.classic.LoggerContext) org.slf4j.LoggerFactory.getILoggerFactory();
        logging.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).setLevel(ch.qos.logback.classic.Level.OFF);
        System.exit(run(args, System.getenv(), System.out, AutomationQuotaCli::connect, AutomationQuotaCli::invalidate));
    }

    /** 显式依赖入口用于真实连接及提交确认丢失测试；不是Spring组件或Web入口。 */
    public static int run(String[] args, Map<String,String> environment, PrintStream output,
                          Connector connector, Invalidator invalidator) {
        Connection connection = null;
        boolean attemptedCommit = false, committed = false;
        Command command = null;
        try {
            command = Command.parse(args);
            validateEnvironment(environment);
            connection = connector.open(environment);
            connection.setAutoCommit(false);
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            Map<String,Object> receipt = execute(connection, command);
            attemptedCommit = true;
            connection.commit(); committed = true;
            if (receipt.containsKey("resultVersion")) {
                boolean broadcast;
                try { broadcast = invalidator.publish(environment, (UUID) receipt.get("policyId"),
                        (Long) receipt.get("resultVersion")); } catch (RuntimeException ignored) { broadcast = false; }
                receipt.put("cacheInvalidation", broadcast ? "PUBLISHED" : "TTL_FALLBACK");
            }
            output.println(JSON.writeValueAsString(receipt));
            return 0;
        } catch (Exception failure) {
            if (attemptedCommit && !committed && command != null && command.action().equals("set")) {
                output.println(JSON.writeValueAsString(Map.of("result", "UNKNOWN", "operationId", command.id(),
                        "next", "QUERY_STATUS_BEFORE_RETRY")));
                return 75;
            }
            if (connection != null && !committed) try { connection.rollback(); } catch (SQLException ignored) { }
            String code = failure instanceof IllegalArgumentException ? "INPUT_INVALID" : "DEPENDENCY_UNAVAILABLE";
            int exit = failure instanceof IllegalArgumentException ? 2 : 4;
            if (failure instanceof SQLException sql) {
                code = switch (String.valueOf(sql.getSQLState())) {
                    case "42501" -> "OPERATOR_REQUIRED";
                    case "40001" -> "VERSION_CONFLICT";
                    case "23505" -> "OPERATION_CONFLICT";
                    case "22023" -> "INPUT_INVALID";
                    default -> "DEPENDENCY_UNAVAILABLE";
                };
                if (!code.equals("DEPENDENCY_UNAVAILABLE")) exit = 3;
            }
            output.println(JSON.writeValueAsString(Map.of("result", "REJECTED", "code", code)));
            return exit;
        } finally {
            if (connection != null) try { connection.close(); } catch (SQLException ignored) { }
        }
    }

    private static Map<String,Object> execute(Connection connection, Command command) throws SQLException {
        String sql = switch (command.action()) {
            case "set" -> "SELECT * FROM public.automation_quota_set(?,?,?,?)";
            case "status" -> "SELECT * FROM public.automation_quota_status(?)";
            default -> "SELECT * FROM public.automation_quota_inspect(?)";
        };
        try (var query = connection.prepareStatement(sql)) {
            query.setQueryTimeout(10); query.setObject(1, command.id());
            if (command.action().equals("set")) {
                query.setObject(2, command.policyId()); query.setLong(3, command.expected()); query.setLong(4, command.limit());
            }
            try (var rows = query.executeQuery()) {
                var result = new LinkedHashMap<String,Object>();
                if (!rows.next()) { result.put("result", "NOT_FOUND"); return result; }
                result.put("result", command.action().equals("inspect") ? "INSPECTED" : "COMMITTED");
                result.put("policyId", rows.getObject("policy_id", UUID.class));
                result.put("dailyLimit", rows.getLong("daily_limit"));
                if (command.action().equals("inspect")) {
                    result.put("policyCode", rows.getString("policy_code"));
                    result.put("policyVersion", rows.getLong("policy_version"));
                    result.put("affectedTenants", rows.getLong("affected_tenants"));
                } else {
                    result.put("operationId", rows.getObject("operation_id", UUID.class));
                    result.put("resultVersion", rows.getLong("result_version"));
                }
                return result;
            }
        }
    }
    private static Connection connect(Map<String,String> env) throws SQLException {
        Properties properties = new Properties();
        properties.setProperty("user", env.get("TC_QUOTA_OPERATOR_USER"));
        properties.setProperty("password", env.get("TC_QUOTA_OPERATOR_PASSWORD"));
        properties.setProperty("connectTimeout", "5"); properties.setProperty("socketTimeout", "15");
        properties.setProperty("sslmode", env.getOrDefault("TC_QUOTA_OPERATOR_SSLMODE", "verify-full"));
        if (env.containsKey("TC_QUOTA_OPERATOR_SSLROOTCERT"))
            properties.setProperty("sslrootcert", env.get("TC_QUOTA_OPERATOR_SSLROOTCERT"));
        return DriverManager.getConnection(env.get("TC_QUOTA_OPERATOR_JDBC_URL"), properties);
    }
    private static void validateEnvironment(Map<String,String> env) {
        for (String key : new String[]{"TC_QUOTA_OPERATOR_JDBC_URL", "TC_QUOTA_OPERATOR_USER", "TC_QUOTA_OPERATOR_PASSWORD"})
            if (env.get(key) == null || env.get(key).isBlank()) throw new IllegalArgumentException();
        if (!java.util.Set.of("disable", "require", "verify-ca", "verify-full")
                .contains(env.getOrDefault("TC_QUOTA_OPERATOR_SSLMODE", "verify-full"))) throw new IllegalArgumentException();
        String url = env.get("TC_QUOTA_OPERATOR_JDBC_URL");
        if (!url.startsWith("jdbc:postgresql://")) throw new IllegalArgumentException();
        URI uri = URI.create(url.substring(5));
        // 封闭URL防止凭据/驱动工厂经参数注入；TLS以专用环境项交给驱动。
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getRawQuery() != null
                || uri.getFragment() != null || !uri.getPath().matches("/[A-Za-z0-9_-]+")) throw new IllegalArgumentException();
    }
    private static boolean invalidate(Map<String,String> env, UUID policy, long version) {
        if (env.getOrDefault("TC_QUOTA_REDIS_HOST", "").isBlank()) return false;
        var config = new RedisStandaloneConfiguration(env.get("TC_QUOTA_REDIS_HOST"),
                Integer.parseInt(env.getOrDefault("TC_QUOTA_REDIS_PORT", "6379")));
        if (env.containsKey("TC_QUOTA_REDIS_PASSWORD")) config.setPassword(env.get("TC_QUOTA_REDIS_PASSWORD"));
        var client = LettuceClientConfiguration.builder().commandTimeout(Duration.ofSeconds(3))
                .shutdownTimeout(Duration.ofMillis(100));
        if (Boolean.parseBoolean(env.getOrDefault("TC_QUOTA_REDIS_SSL", "false"))) client.useSsl();
        var factory = new LettuceConnectionFactory(config, client.build());
        var metrics = new SimpleMeterRegistry();
        try {
            factory.afterPropertiesSet(); factory.start();
            var template = new StringRedisTemplate(factory); template.afterPropertiesSet();
            var publisher = new CacheInvalidationPublisher(template, JSON, new CacheInvalidationMetrics(metrics));
            publisher.publish(new CacheInvalidationEvent(Uuid7.generate(), CacheResource.QUOTA_POLICY,
                    CacheInvalidationOperation.UPDATE, policy, null, version, 0, Instant.now()));
            var failed = metrics.find("thingslink.cache.invalidation").tag("result", "failure").counter();
            return failed == null || failed.count() == 0;
        } finally { factory.destroy(); metrics.close(); }
    }
    /** 实际JDBC连接，测试可包裹commit模拟确认丢失。 */
    @FunctionalInterface public interface Connector { Connection open(Map<String,String> env) throws SQLException; }
    /** 已确认提交后才调用；测试可检查调用顺序，生产复用统一缓存发布器。 */
    @FunctionalInterface public interface Invalidator { boolean publish(Map<String,String> env, UUID policy, long version); }
    /** 命令中不允许密码或任意SQL；参数数量封闭。 */
    record Command(String action, UUID id, UUID policyId, long expected, long limit) {
        static Command parse(String[] args) {
            if (args.length == 2 && (args[0].equals("inspect") || args[0].equals("status")))
                return new Command(args[0], UUID.fromString(args[1]), null, 0, 0);
            if (args.length != 5 || !args[0].equals("set")) throw new IllegalArgumentException();
            UUID id = UUID.fromString(args[1]); long expected = Long.parseLong(args[3]), limit = Long.parseLong(args[4]);
            if (id.version() != 7 || expected < 1 || limit < 0) throw new IllegalArgumentException();
            return new Command("set", id, UUID.fromString(args[2]), expected, limit);
        }
    }
}
