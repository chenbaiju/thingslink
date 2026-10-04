package com.things.link.enduser.infrastructure.qualification;

import com.things.link.dashboard.application.DashboardShareDataService;
import com.things.link.dashboard.application.publication.DashboardPublicationEligibilityRequirement.AdapterCapability;
import com.things.link.dashboard.application.publication.DashboardPublicationEligibilityRequirement.BindingSource;
import com.things.link.dashboard.application.publication.DashboardPublicationEligibilityRequirement.DataAdapter;
import com.things.link.dashboard.application.publication.DashboardPublicationEligibilityRequirement.HistoricalProperty;
import com.things.link.dashboard.application.publication.DashboardPublicationEligibilityRequirement.HistoryAggregation;
import com.things.link.dashboard.application.publication.DashboardPublicationEligibilityRequirement.HistoryGranularity;
import com.things.link.dashboard.application.publication.DashboardPublicationEligibilityRequirement.VariableType;
import com.things.link.device.application.ConsoleDeviceRuntimeDataService;
import com.things.link.enduser.application.WebAppRuntimeDataService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.Mockito.mock;

/** ADR0111运行能力声明反例；真正端口行为及无替身装配另由Bootstrap真实HTTP/发布验证。 */
class DashboardRuntimeCapabilityAdapterTests {
    /** 随机项目只用于声明形状，矩阵不是项目授权服务。 */
    private static final UUID PROJECT = UUID.randomUUID();
    /** 本层隔离数据库，仅验证既有组件合同与非法组合，不能冒充真实运行已实现。 */
    private final DashboardRuntimeCapabilityAdapter adapter = new DashboardRuntimeCapabilityAdapter(
            mock(WebAppRuntimeDataService.class), mock(DashboardShareDataService.class),
            mock(ConsoleDeviceRuntimeDataService.class));

    /** 十种合法用法分别来源于现有八类能力及单/多设备的目录与告警。 */
    @ParameterizedTest
    @CsvSource(value = {
            "CURRENT_VALUE,CURRENT_VALUE,DEVICE_SINGLE,temperature",
            "COMPOSITE_SNAPSHOT,CURRENT_VALUE,DEVICE_SINGLE,object_value",
            "LIST_SNAPSHOT,CURRENT_VALUE,DEVICE_SINGLE,list_value",
            "BOUNDED_DEVICE_VALUES,CURRENT_VALUE,DEVICE_MULTI,temperature",
            "HISTORY_SERIES,HISTORY_SERIES,DEVICE_SINGLE,temperature",
            "DEVICE_STATUS,DEVICE_STATUS,DEVICE_SINGLE,''",
            "BOUNDED_ALARM_PAGE,ALARM_LIST,DEVICE_SINGLE,''",
            "BOUNDED_ALARM_PAGE,ALARM_LIST,DEVICE_MULTI,''",
            "BOUNDED_DEVICE_DIRECTORY_PAGE,DEVICE_DIRECTORY,DEVICE_SINGLE,''",
            "BOUNDED_DEVICE_DIRECTORY_PAGE,DEVICE_DIRECTORY,DEVICE_MULTI,''"
    })
    void acceptsDeliveredComponentBindings(AdapterCapability capability, BindingSource source,
            VariableType type, String property) {
        assertThat(adapter.supports(PROJECT, new DataAdapter(capability, source, "selected", type,
                "sensor", property))).isTrue();
    }

    /** 单/多设备、属性存在性与来源不可因为端口存在而任意交叉拼接。 */
    @ParameterizedTest
    @CsvSource(value = {
            "CURRENT_VALUE,CURRENT_VALUE,DEVICE_MULTI,temperature",
            "BOUNDED_DEVICE_VALUES,CURRENT_VALUE,DEVICE_SINGLE,temperature",
            "COMPOSITE_SNAPSHOT,DEVICE_STATUS,DEVICE_SINGLE,value",
            "LIST_SNAPSHOT,CURRENT_VALUE,DEVICE_SINGLE,''",
            "HISTORY_SERIES,HISTORY_SERIES,DEVICE_MULTI,temperature",
            "DEVICE_STATUS,DEVICE_STATUS,DEVICE_SINGLE,value",
            "BOUNDED_ALARM_PAGE,ALARM_LIST,TEXT_ENUM,''",
            "BOUNDED_DEVICE_DIRECTORY_PAGE,DEVICE_DIRECTORY,TIME_RANGE,''",
            "BOUNDED_DEVICE_DIRECTORY_PAGE,CURRENT_VALUE,DEVICE_SINGLE,''",
            "CURRENT_VALUE,CURRENT_VALUE,DEVICE_SINGLE,nested.property"
    })
    void rejectsFalseCapabilityCombinations(AdapterCapability capability, BindingSource source,
            VariableType type, String property) {
        assertThat(adapter.supports(PROJECT, new DataAdapter(capability, source, "selected", type,
                "sensor", property))).isFalse();
    }

    /** 缺少模型/变量关联不能默认为全项目或无模型查询。 */
    @Test void missingIdentityAndInvalidKeysFailClosed() {
        var valid = new DataAdapter(AdapterCapability.CURRENT_VALUE, BindingSource.CURRENT_VALUE,
                "selected", VariableType.DEVICE_SINGLE, "sensor", "temperature");
        assertThat(adapter.supports(null, valid)).isFalse();
        assertThat(adapter.supports(PROJECT, null)).isFalse();
        for (String key : new String[] { "", "SELECTED", "sensor.name", "a".repeat(65) }) {
            assertThat(adapter.supports(PROJECT, new DataAdapter(valid.capability(), valid.source(), key,
                    valid.variableType(), valid.modelKey(), valid.propertyKey()))).isFalse();
            assertThat(adapter.supports(PROJECT, new DataAdapter(valid.capability(), valid.source(),
                    valid.variableKey(), valid.variableType(), key, valid.propertyKey()))).isFalse();
        }
    }

    /** 四粒度与五聚合为既有端口合同，枚举扩充时须重新核验实现而非自动接受任意字符串。 */
    @Test void allTwentyDeliveredHistoryCombinationsHaveExplicitSupport() {
        int accepted = 0;
        for (HistoryGranularity granularity : HistoryGranularity.values()) {
            for (HistoryAggregation aggregation : HistoryAggregation.values()) {
                assertThat(adapter.supportsHistory(PROJECT, new HistoricalProperty("selected", "sensor",
                        "temperature", "period", granularity, aggregation))).isTrue();
                accepted++;
            }
        }
        assertThat(accepted).isEqualTo(20);
    }

    /** 历史必须指向独立时间变量和顶层属性，不靠支持聚合掩盖缺失关联。 */
    @Test void incompleteHistoryIsNotQualified() {
        var valid = new HistoricalProperty("selected", "sensor", "temperature", "period",
                HistoryGranularity.RAW, HistoryAggregation.AVG);
        assertThat(adapter.supportsHistory(null, valid)).isFalse();
        assertThat(adapter.supportsHistory(PROJECT, null)).isFalse();
        assertThat(adapter.supportsHistory(PROJECT, new HistoricalProperty("selected", "sensor", "temperature",
                "selected", HistoryGranularity.RAW, HistoryAggregation.AVG))).isFalse();
        assertThat(adapter.supportsHistory(PROJECT, new HistoricalProperty("selected", "", "temperature",
                "period", HistoryGranularity.ONE_MINUTE, HistoryAggregation.SUM))).isFalse();
        assertThat(adapter.supportsHistory(PROJECT, new HistoricalProperty("selected", "sensor", "nested.value",
                "period", HistoryGranularity.ONE_DAY, HistoryAggregation.COUNT))).isFalse();
    }

    /** 装配缺项不能变成一个虚假的全能力适配器；生产不启用循环依赖或可选降级。 */
    @Test void requiresEachDeliveredRuntime() {
        var app = mock(WebAppRuntimeDataService.class);
        var share = mock(DashboardShareDataService.class);
        var console = mock(ConsoleDeviceRuntimeDataService.class);
        assertThatNullPointerException().isThrownBy(() -> new DashboardRuntimeCapabilityAdapter(null, share, console));
        assertThatNullPointerException().isThrownBy(() -> new DashboardRuntimeCapabilityAdapter(app, null, console));
        assertThatNullPointerException().isThrownBy(() -> new DashboardRuntimeCapabilityAdapter(app, share, null));
    }
}
