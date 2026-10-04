package com.things.link.dashboard.application;

import com.things.link.dashboard.domain.DashboardErrorCode;
import com.things.link.dashboard.domain.DashboardShareVariableScope;
import com.things.link.shared.error.BusinessException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 分享范围数量合法但context字节超限，以及保守1KiB信封后的精确边界。 */
class DashboardShareContextBudgetTests {
    /** 所有变量复用同20台设备，始终满足候选并集20的原合同。 */
    private final List<UUID> devices = IntStream.range(0, 20).mapToObj(index -> new UUID(0, index + 1)).toList();
    /** 同一冻结模型，字节预算不将不同模型/变量当成可混合授权。 */
    private final UUID model = UUID.randomUUID();

    /** 20个64字符变量键分别重复同20个UUID时，完整context至少17895字节，签发前应拒绝。 */
    @Test
    void repeatedCandidateMappingsOverflowDespiteTwentyDeviceUnion() {
        List<DashboardShareVariableScope> scopes = IntStream.range(0, 20).mapToObj(index ->
                new DashboardShareVariableScope(key(index, 64), model, devices)).toList();
        assertThat(encodedScopes(scopes)).isEqualTo(17541);
        assertThatThrownBy(() -> DashboardShareContextBudget.requireFits(scopes))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.errorCode()).isEqualTo(DashboardErrorCode.SHARE_INVALID));
    }

    /** 精确15360字节scope仍可放行，加一字节必须失败，不用模糊字符数量替代UTF-8。 */
    @Test
    void exactReservedEnvelopeBoundaryIsInclusive() {
        List<DashboardShareVariableScope> fits = IntStream.range(0, 18).mapToObj(index ->
                new DashboardShareVariableScope(key(index, index == 0 ? 45 : 40), model, devices)).toList();
        List<DashboardShareVariableScope> overflow = IntStream.range(0, 18).mapToObj(index ->
                new DashboardShareVariableScope(key(index, index == 0 ? 46 : 40), model, devices)).toList();
        assertThat(encodedScopes(fits)).isEqualTo(15360);
        assertThat(encodedScopes(overflow)).isEqualTo(15361);
        assertThatCode(() -> DashboardShareContextBudget.requireFits(fits)).doesNotThrowAnyException();
        assertThatThrownBy(() -> DashboardShareContextBudget.requireFits(overflow))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.errorCode()).isEqualTo(DashboardErrorCode.SHARE_INVALID));
    }

    /** 静态看板空变量和普通单变量范围无额外拒绝。 */
    @Test
    void staticAndOrdinaryScopesRemainAdmissible() {
        assertThatCode(() -> DashboardShareContextBudget.requireFits(List.of())).doesNotThrowAnyException();
        assertThatCode(() -> DashboardShareContextBudget.requireFits(List.of(
                new DashboardShareVariableScope("devices", model, devices)))).doesNotThrowAnyException();
    }

    /** 独立编码公开投影用于验证算例，不引用生产预算常量掩盖边界偏移。 */
    private static int encodedScopes(List<DashboardShareVariableScope> scopes) {
        return JsonMapper.builder().build().writeValueAsBytes(scopes.stream().map(scope ->
                new DashboardShareVariableScopeView(scope.variableKey(), scope.deviceIds())).toList()).length;
    }

    /** 构造长度明确、互异且符合LocalKey的ASCII变量名。 */
    private static String key(int index, int length) {
        return "v" + String.format(java.util.Locale.ROOT, "%02d", index) + "a".repeat(length - 3);
    }
}
