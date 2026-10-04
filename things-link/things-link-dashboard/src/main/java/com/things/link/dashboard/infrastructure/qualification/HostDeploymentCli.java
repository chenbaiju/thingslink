package com.things.link.dashboard.infrastructure.qualification;

import tools.jackson.databind.json.JsonMapper;

import java.io.PrintStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

/** ADR0148独立平台进程入口；不装配Spring，不消费API凭据，不自动执行Flyway。 */
public final class HostDeploymentCli {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private HostDeploymentCli() { }

    /** @param args 封闭命令协议；退出码见ADR0148 */
    public static void main(String[] args) {
        // 与API入口一致：显式operator JDBC直连不能被macOS系统SOCKS代理改路由。
        System.setProperty("socksProxyHost", "");
        System.setProperty("http.proxyHost", "");
        // 独立工具的标准输出只允许单个JSON；文件核验失败的安全类别由回执统一输出。
        var logging = (ch.qos.logback.classic.LoggerContext) org.slf4j.LoggerFactory.getILoggerFactory();
        logging.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).setLevel(ch.qos.logback.classic.Level.OFF);
        System.exit(run(args, System.getenv(), System.out, HostDeploymentCli::connect));
    }

    /** 同包真实PG测试在commit确认处注入故障，不改变生产连接入口。 */
    static int run(String[] args, Map<String, String> environment, PrintStream output, Connector connector) {
        Connection connection = null;
        boolean commitAttempted = false;
        boolean committed = false;
        Command command = null;
        try {
            command = Command.parse(args);
            validateEnvironment(environment, !command.action().equals("status"));
            connection = connector.open(environment);
            connection.setAutoCommit(false);
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            connection.setReadOnly(!command.action().equals("activate"));
            var registry = new ManagedWebAppHostQualificationAdapter(environment.getOrDefault("TC_HOST_REGISTRY_DIRECTORY", ""));
            var operations = new JdbcHostActivation(registry);
            Map<String, Object> receipt = switch (command.action()) {
                case "preflight" -> operations.preflight(connection, command.version());
                case "activate" -> operations.activate(connection, command.version(), command.expected(), command.operation());
                case "status" -> operations.status(connection, command.operation());
                default -> throw new IllegalArgumentException("INPUT_INVALID");
            };
            commitAttempted = true;
            connection.commit();
            committed = true;
            output.println(JSON.writeValueAsString(receipt));
            return 0;
        } catch (Exception failure) {
            if (commitAttempted && !committed && command != null && command.action().equals("activate")) {
                output.println(JSON.writeValueAsString(Map.of("result", "UNKNOWN", "operationId", command.operation(),
                        "next", "QUERY_STATUS_BEFORE_RETRY")));
                return 75;
            }
            if (!committed && connection != null) try { connection.rollback(); } catch (SQLException ignored) { /* 仍报告依赖失败，不猜测网络状态。 */ }
            boolean operatorRejected = failure instanceof IllegalArgumentException
                    && "HOST_PREFLIGHT_OPERATOR_REQUIRED".equals(failure.getMessage());
            String code = operatorRejected ? "OPERATOR_REQUIRED" : failure instanceof JdbcHostActivation.Rejected ? failure.getMessage()
                    : failure instanceof IllegalArgumentException ? "INPUT_OR_OPERATOR_REJECTED"
                    : failure instanceof IllegalStateException ? "PREFLIGHT_BUDGET_OR_STATE_REJECTED" : "DEPENDENCY_UNAVAILABLE";
            int exit = operatorRejected ? 3 : failure instanceof JdbcHostActivation.Rejected ? (code.equals("HOST_UNAVAILABLE") ? 4 : 3)
                    : failure instanceof IllegalArgumentException ? 2 : failure instanceof IllegalStateException ? 3 : 4;
            var diagnostic = new java.util.LinkedHashMap<String, Object>();
            diagnostic.put("result", "REJECTED"); diagnostic.put("code", code);
            // 类型名不含连接地址或异常正文；便于区分独立进程的驱动/网络/权限依赖。
            if (exit == 4) {
                diagnostic.put("failureType", failure.getClass().getSimpleName());
                diagnostic.put("causeType", failure.getCause() == null ? "NONE" : failure.getCause().getClass().getSimpleName());
                if (failure instanceof SQLException sql && sql.getSQLState() != null && sql.getSQLState().matches("[0-9A-Z]{5}")) {
                    diagnostic.put("sqlState", sql.getSQLState());
                }
            }
            output.println(JSON.writeValueAsString(diagnostic));
            return exit;
        } finally {
            if (connection != null) try { connection.close(); } catch (SQLException ignored) { /* 已知提交成功不能改报回滚；未知结果保持UNKNOWN。 */ }
        }
    }

    private static Connection connect(Map<String, String> environment) throws SQLException {
        var properties = new Properties();
        properties.setProperty("user", environment.get("TC_HOST_OPERATOR_USER"));
        properties.setProperty("password", environment.get("TC_HOST_OPERATOR_PASSWORD"));
        properties.setProperty("connectTimeout", "5"); properties.setProperty("socketTimeout", "15");
        properties.setProperty("loginTimeout", "5"); properties.setProperty("cancelSignalTimeout", "5");
        return DriverManager.getConnection(environment.get("TC_HOST_OPERATOR_JDBC_URL"), properties);
    }
    private static void validateEnvironment(Map<String, String> environment, boolean requireRegistry) {
        for (String key : Set.of("TC_HOST_OPERATOR_JDBC_URL", "TC_HOST_OPERATOR_USER", "TC_HOST_OPERATOR_PASSWORD")) {
            if (environment.get(key) == null || environment.get(key).isBlank()) throw new IllegalArgumentException("INPUT_INVALID");
        }
        String url = environment.get("TC_HOST_OPERATOR_JDBC_URL");
        if (!url.startsWith("jdbc:postgresql://")) throw new IllegalArgumentException("INPUT_INVALID");
        URI uri = URI.create(url.substring(5));
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null
                || uri.getPath() == null || !uri.getPath().matches("/[A-Za-z0-9_-]+")) throw new IllegalArgumentException("INPUT_INVALID");
        if (uri.getRawQuery() != null) {
            for (String part : uri.getRawQuery().split("&", -1)) {
                String key = URLDecoder.decode(part.split("=", 2)[0], StandardCharsets.UTF_8);
                if (key.equalsIgnoreCase("user") || key.equalsIgnoreCase("password")) throw new IllegalArgumentException("INPUT_INVALID");
            }
        }
        if (requireRegistry) {
            String root = environment.get("TC_HOST_REGISTRY_DIRECTORY");
            if (root == null || root.isBlank() || !Path.of(root).isAbsolute()) throw new IllegalArgumentException("INPUT_INVALID");
        }
    }
    /** 独立进程连接工厂；不作为Spring组件暴露。 */
    @FunctionalInterface interface Connector { Connection open(Map<String, String> environment) throws SQLException; }
    private record Command(String action, String version, long expected, UUID operation) {
        static Command parse(String[] args) {
            if (args.length == 2 && args[0].equals("status")) return new Command("status", null, 0, uuid(args[1]));
            if ((args.length == 2 && args[0].equals("preflight")) || (args.length == 4 && args[0].equals("activate"))) {
                if (!Set.of("1.0.0", "1.1.0", "1.1.1").contains(args[1])) throw new IllegalArgumentException("INPUT_INVALID");
                if (args.length == 2) return new Command("preflight", args[1], 0, null);
                if (!args[2].matches("0|[1-9][0-9]{0,18}")) throw new IllegalArgumentException("INPUT_INVALID");
                long revision = Long.parseLong(args[2]);
                if (revision == Long.MAX_VALUE) throw new IllegalArgumentException("INPUT_INVALID");
                return new Command("activate", args[1], revision, uuid(args[3]));
            }
            throw new IllegalArgumentException("INPUT_INVALID");
        }
        private static UUID uuid(String value) {
            UUID parsed = UUID.fromString(value);
            if (!parsed.toString().equals(value)) throw new IllegalArgumentException("INPUT_INVALID");
            return parsed;
        }
    }
}
