package com.things.link.enduser.infrastructure.qualification;

import com.things.link.dashboard.application.DashboardShareDataService;
import com.things.link.dashboard.application.publication.DashboardDataAdapterQualificationPort;
import com.things.link.dashboard.application.publication.DashboardPublicationEligibilityRequirement;
import com.things.link.dashboard.application.publication.DashboardPublicationEligibilityRequirement.BindingSource;
import com.things.link.dashboard.application.publication.DashboardPublicationEligibilityRequirement.VariableType;
import com.things.link.device.application.ConsoleDeviceRuntimeDataService;
import com.things.link.enduser.application.WebAppRuntimeDataService;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * ADR0111：当前完整运行实现的发布能力矩阵，不是项目授权或数据库健康检查。
 *
 * <p>App、匿名分享及Console三个真实编排必须同时装配；其构造依赖继续要求实际设备、历史和告警端口。
 * 本适配器位于enduser，保持enduser到dashboard的既有依赖方向，不令dashboard反向依赖App。</p>
 */
@Component
public class DashboardRuntimeCapabilityAdapter implements DashboardDataAdapterQualificationPort {
    /** 与Schema合同LocalKey一致，不接受任意路径或表达式。 */
    private static final Pattern LOCAL_KEY = Pattern.compile("[a-z][a-z0-9_]{0,63}");
    /** 顶层属性键沿物模型既有语法，不能以能力声明放宽嵌套查询。 */
    private static final Pattern PROPERTY_KEY = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    /**
     * 明确绑定已交付编排；缺少任一真实实现让Spring装配失败，不静默登记部分能力。
     *
     * @param app App当前值、快照、目录、历史和告警的授权编排
     * @param share 匿名精确scope下的完整运行编排
     * @param console Console成员身份下的设备运行编排
     */
    public DashboardRuntimeCapabilityAdapter(WebAppRuntimeDataService app, DashboardShareDataService share,
            ConsoleDeviceRuntimeDataService console) {
        Objects.requireNonNull(app, "App完整运行编排");
        Objects.requireNonNull(share, "匿名分享完整运行编排");
        Objects.requireNonNull(console, "Console运行编排");
    }

    /**
     * 按Schema组件真实消费方式核验八种能力，拒绝来源、变量和属性形状不一致的声明。
     * 模型真实性、属性类型及页面预算仍由同事务发布资格内核检查，不能把本矩阵当授权租约。
     */
    @Override
    public boolean supports(UUID projectId, DashboardPublicationEligibilityRequirement.DataAdapter requirement) {
        if (projectId == null || requirement == null || !localKey(requirement.variableKey())
                || !localKey(requirement.modelKey())) return false;
        boolean single = requirement.variableType() == VariableType.DEVICE_SINGLE;
        boolean multi = requirement.variableType() == VariableType.DEVICE_MULTI;
        if (!single && !multi) return false;
        boolean property = PROPERTY_KEY.matcher(requirement.propertyKey()).matches();
        boolean noProperty = requirement.propertyKey().isEmpty();
        return switch (requirement.capability()) {
            case CURRENT_VALUE, COMPOSITE_SNAPSHOT, LIST_SNAPSHOT -> single && property
                    && requirement.source() == BindingSource.CURRENT_VALUE;
            case BOUNDED_DEVICE_VALUES -> multi && property
                    && requirement.source() == BindingSource.CURRENT_VALUE;
            case HISTORY_SERIES -> single && property && requirement.source() == BindingSource.HISTORY_SERIES;
            case DEVICE_STATUS -> single && noProperty && requirement.source() == BindingSource.DEVICE_STATUS;
            case BOUNDED_ALARM_PAGE -> noProperty && requirement.source() == BindingSource.ALARM_LIST;
            case BOUNDED_DEVICE_DIRECTORY_PAGE -> noProperty && requirement.source() == BindingSource.DEVICE_DIRECTORY;
        };
    }

    /**
     * 现有版本化历史端口接受四种粒度与五种聚合；RAW保留请求聚合合同，实际点解释沿原历史服务。
     * 本方法不查询历史，也不承诺所请求窗口一定有点或不超限，运行端仍执行2000点及保留期边界。
     */
    @Override
    public boolean supportsHistory(UUID projectId,
            DashboardPublicationEligibilityRequirement.HistoricalProperty requirement) {
        return projectId != null && requirement != null && localKey(requirement.variableKey())
                && localKey(requirement.modelKey()) && localKey(requirement.timeRangeVariableKey())
                && !requirement.variableKey().equals(requirement.timeRangeVariableKey())
                && PROPERTY_KEY.matcher(requirement.propertyKey()).matches()
                && switch (requirement.granularity()) {
                    case RAW, ONE_MINUTE, ONE_HOUR, ONE_DAY -> switch (requirement.aggregation()) {
                        case AVG, MIN, MAX, SUM, COUNT -> true;
                    };
                };
    }

    /** 只校验声明形状；同名模型和变量分属独立命名空间，不在此错误去重。 */
    private static boolean localKey(String value) {
        return value != null && LOCAL_KEY.matcher(value).matches();
    }
}
