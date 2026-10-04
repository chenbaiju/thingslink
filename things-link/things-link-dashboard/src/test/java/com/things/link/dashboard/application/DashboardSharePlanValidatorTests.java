package com.things.link.dashboard.application;

import com.things.link.dashboard.domain.DashboardErrorCode;
import com.things.link.dashboard.domain.DashboardShareVariableScope;
import com.things.link.device.application.RuntimeDeviceQuery;
import com.things.link.device.application.RuntimeModelReference;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 匿名分享逐变量原binding授权的正反例，不借App身份或伪造App运行Schema。 */
class DashboardSharePlanValidatorTests {
    /** 固定模型让跨变量反例不能靠模型差异被偶然挡住。 */
    private final UUID model = UUID.randomUUID();
    /** 变量a的独占候选。 */
    private final UUID first = UUID.randomUUID();
    /** 变量b的独占候选。 */
    private final UUID second = UUID.randomUUID();
    /** 两变量明确共同授权的候选。 */
    private final UUID shared = UUID.randomUUID();
    /** 与应用系统时钟无关的数据库锚点。 */
    private final Instant now = Instant.parse("2026-09-07T10:00:00Z");
    /** 纯计划验证无需Spring上下文与数据库。 */
    private final DashboardSharePlanValidator validator = new DashboardSharePlanValidator();

    /** 同模型不产生全局属性并集，合法共同候选则可同时读取各自变量声明的键。 */
    @Test
    void currentKeepsVariableOwnershipAndAllowsExplicitOverlap() {
        assertThatCode(() -> validator.requireCurrent(context(), List.of(query(shared, "temperature", "pressure"))))
                .doesNotThrowAnyException();
        forbidden(() -> validator.requireCurrent(context(), List.of(query(first, "pressure"))));
        forbidden(() -> validator.requireCurrent(context(), List.of(query(second, "temperature"))));
        forbidden(() -> validator.requireCurrent(context(), List.of(query(UUID.randomUUID(), "temperature"))));
        forbidden(() -> validator.requireCurrent(context(), List.of(new RuntimeDeviceQuery(first, UUID.randomUUID(), List.of("temperature")))));
    }

    /** SINGLE可签发多个候选；没有当前选择状态的匿名body不能被误当成宿主选中了多台。 */
    @Test
    void allSignedSingleCandidatesRemainReadable() {
        assertThatCode(() -> validator.requireCurrent(context(), List.of(query(first, "temperature"), query(shared, "temperature"))))
                .doesNotThrowAnyException();
    }

    /** 元数据保留变量身份和精确摘要，历史键可读元数据但不能伪装成当前值。 */
    @Test
    void snapshotsVerifyExactModelsAndOriginalMetadataBindings() {
        assertThatCode(() -> validator.requireSnapshots(context(), List.of(reference()), List.of(query(first, "trend"))))
                .doesNotThrowAnyException();
        forbidden(() -> validator.requireCurrent(context(), List.of(query(first, "trend"))));
        forbidden(() -> validator.requireSnapshots(context(), List.of(reference()), List.of(query(first, "pressure"))));
        forbidden(() -> validator.requireSnapshots(context(), List.of(new RuntimeModelReference(model, "PG", "wrong", "profile")), List.of(query(first))));
        invalid(() -> validator.requireSnapshots(context(), List.of(), List.of(query(first))));
    }

    /** 目录返回指定变量原scope，不能因为另一变量模型相同而借用目录声明。 */
    @Test
    void catalogReturnsOnlyDeclaredVariableCandidates() {
        assertThat(validator.requireCatalog(context(), "a").deviceIds()).containsExactly(first, shared);
        forbidden(() -> validator.requireCatalog(context(), "b"));
        invalid(() -> validator.requireCatalog(context(), "bad key"));
    }

    /** 锚点允许精确60秒边界，预设、粒度、聚合必须属于同一原历史声明。 */
    @Test
    void historyUsesDatabaseClockAndExactBinding() {
        assertThat(validator.requireHistory(context(), first, model, "trend", "LAST_1_HOUR", now.minusSeconds(60), "RAW", "AVG"))
                .isEqualTo(now.minusSeconds(3660));
        assertThat(validator.requireHistory(context(), first, model, "trend", "LAST_1_HOUR", now, "RAW", "AVG"))
                .isEqualTo(now.minusSeconds(3600));
        invalid(() -> validator.requireHistory(context(), first, model, "trend", "LAST_1_HOUR", now.minusSeconds(61), "RAW", "AVG"));
        invalid(() -> validator.requireHistory(context(), first, model, "trend", "LAST_1_HOUR", now.plusNanos(1), "RAW", "AVG"));
        forbidden(() -> validator.requireHistory(context(), second, model, "trend", "LAST_1_HOUR", now, "RAW", "AVG"));
        forbidden(() -> validator.requireHistory(context(), first, model, "trend", "LAST_7_DAYS", now, "RAW", "AVG"));
        forbidden(() -> validator.requireHistory(context(), first, model, "trend", "LAST_1_HOUR", now, "ONE_HOUR", "AVG"));
        forbidden(() -> validator.requireHistory(context(), first, model, "trend", "LAST_1_HOUR", now, "RAW", "SUM"));
    }

    /** 两个原binding分别合法并不意味着其设备并集、过滤并集或交叉过滤合法。 */
    @Test
    void alarmsRequireOneWholeBindingAndItsCapacity() {
        alarms(List.of(query(first)), Set.of("ACTIVE"), Set.of("UNACKNOWLEDGED"), Set.of("MAJOR"));
        alarms(List.of(query(second), query(shared)), Set.of("CLEARED"), Set.of("ACKNOWLEDGED"), Set.of("INFO"));
        forbidden(() -> alarms(List.of(query(first), query(second)), Set.of("ACTIVE"), Set.of("UNACKNOWLEDGED"), Set.of("MAJOR")));
        forbidden(() -> alarms(List.of(query(first), query(shared)), Set.of("ACTIVE"), Set.of("UNACKNOWLEDGED"), Set.of("MAJOR")));
        forbidden(() -> alarms(List.of(query(shared)), Set.of("ACTIVE"), Set.of("ACKNOWLEDGED"), Set.of("INFO")));
        forbidden(() -> alarms(List.of(query(shared)), Set.of("ACTIVE", "CLEARED"), Set.of("ACKNOWLEDGED"), Set.of("INFO")));
        invalid(() -> alarms(List.of(query(first, "temperature")), Set.of("ACTIVE"), Set.of("UNACKNOWLEDGED"), Set.of("MAJOR")));
    }

    /** 预算错误先返回10001，不依赖范围授权失败偶然遮蔽51键、201键或21设备。 */
    @Test
    void malformedAndOversizedRequestsAreParameterErrors() {
        invalid(() -> validator.requireCurrent(context(), List.of()));
        invalid(() -> validator.requireCurrent(context(), List.of(query(first))));
        invalid(() -> validator.requireCurrent(context(), List.of(query(first, "temperature", "temperature"))));
        invalid(() -> validator.requireCurrent(context(), List.of(query(first, "bad.key"))));
        invalid(() -> validator.requireCurrent(context(), List.of(query(first, "temperature"), query(first, "temperature"))));
        invalid(() -> validator.requireCurrent(context(), List.of(new RuntimeDeviceQuery(first, model, keys(51)))));
        invalid(() -> validator.requireCurrent(context(), IntStream.range(0, 5).mapToObj(index ->
                new RuntimeDeviceQuery(new UUID(0, index + 1), model, keys(index == 4 ? 1 : 50))).toList()));
        invalid(() -> validator.requireCurrent(context(), IntStream.range(0, 21).mapToObj(index ->
                query(new UUID(0, index + 1), "temperature")).toList()));
        invalid(() -> validator.requireSnapshots(context(), List.of(reference(), reference()), List.of(query(first))));
        invalid(() -> alarms(List.of(query(first)), Set.of("UNKNOWN"), Set.of("ACKNOWLEDGED"), Set.of("INFO")));
    }

    /** 使用完整上下文传入纯投影，Schema内容仅保留被测计划需要的字段。 */
    private DashboardShareReadContext context() {
        var schema = JsonMapper.builder().build().readTree("""
                {"models":[{"key":"m","versionId":"%s","digestAlgorithm":"PG","digest":"digest","profile":"profile"}],
                 "variables":[{"key":"a","type":"DEVICE_SINGLE","modelKey":"m"},
                  {"key":"b","type":"DEVICE_MULTI","modelKey":"m","maxItems":2},
                  {"key":"time","type":"TIME_RANGE","allowedPresets":["LAST_1_HOUR"]}],
                 "pages":[{"components":[{"bindings":{
                  "values":[{"source":"CURRENT_VALUE","device":{"variableKey":"a"},"propertyKey":"temperature"},
                            {"source":"CURRENT_VALUE","device":{"variableKey":"b"},"propertyKey":"pressure"}],
                  "directory":{"source":"DEVICE_DIRECTORY","variableKey":"a"},
                  "history":{"source":"HISTORY_SERIES","device":{"variableKey":"a"},"propertyKey":"trend",
                    "timeRangeVariableKey":"time","granularity":"RAW","aggregation":"AVG"},
                  "alarms":[{"source":"ALARM_LIST","devices":{"variableKey":"a"},"conditionStates":["ACTIVE"],
                    "ackStates":["UNACKNOWLEDGED"],"severities":["MAJOR"]},
                    {"source":"ALARM_LIST","devices":{"variableKey":"b"},"conditionStates":["CLEARED"],
                    "ackStates":["ACKNOWLEDGED"],"severities":["INFO"]}]}}]}]}
                """.formatted(model));
        UUID dashboard = UUID.randomUUID();
        UUID version = UUID.randomUUID();
        var principal = new DashboardSharePrincipal(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                dashboard, version, 1, now.plusSeconds(3600), "ANY", "a".repeat(64));
        return new DashboardShareReadContext(principal, new DashboardShareSchema(dashboard, version, 1,
                "1", "PG", "digest", List.of(), List.of(), schema), List.of(
                new DashboardShareVariableScope("a", model, List.of(first, shared)),
                new DashboardShareVariableScope("b", model, List.of(second, shared))), now);
    }

    /** 单次告警调用保持三个独立过滤参数可审阅。 */
    private void alarms(List<RuntimeDeviceQuery> devices, Set<String> conditions, Set<String> ack, Set<String> severity) {
        validator.requireAlarms(context(), devices, conditions, ack, severity);
    }
    /** 统一精确模型，反例只改变需要隔离的业务维度。 */
    private RuntimeDeviceQuery query(UUID device, String... keys) { return new RuntimeDeviceQuery(device, model, List.of(keys)); }
    /** 精确模型摘要正例。 */
    private RuntimeModelReference reference() { return new RuntimeModelReference(model, "PG", "digest", "profile"); }
    /** 构造合法互异属性，预算反例不被非法键抢先截断。 */
    private static List<String> keys(int count) { return IntStream.range(0, count).mapToObj(index -> "p" + index).toList(); }
    /** 范围拒绝必须明确60054，不能依赖普通参数校验偶然通过。 */
    private static void forbidden(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                failure -> assertThat(failure.errorCode()).isEqualTo(DashboardErrorCode.SHARE_SCOPE_FORBIDDEN));
    }
    /** 语法和预算明确10001，不允许混同失效分享。 */
    private static void invalid(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                failure -> assertThat(failure.errorCode()).isEqualTo(CommonErrorCode.INVALID_PARAMETER));
    }
}
