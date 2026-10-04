package com.things.link.device.domain;

import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;

import java.util.Set;
import java.util.UUID;

/**
 * 项目内设备高级筛选条件。
 *
 * <p>S5-2 只开放固定字段白名单：关键词、设备类型、状态、设备组和一个精确标签键值。
 * 各维度之间固定使用 AND，类型和状态集合内部使用 OR；客户端不能传字段名、运算符或排序表达式，
 * 否则查询接口会演变成难以约束的通用 DSL。</p>
 *
 * @param projectId 项目 ID，也是查询和 RLS 的隔离轴
 * @param keyword 可选设备名称、设备标识或位置关键词
 * @param deviceTypeIds 可选设备类型集合，集合内部为 OR
 * @param statuses 可选设备状态集合，集合内部为 OR
 * @param groupId 可选静态或动态设备组 ID
 * @param tagKey 可选精确标签键，必须与 tagValue 同时出现
 * @param tagValue 可选精确标签值，必须与 tagKey 同时出现
 * @param cursor 可选不透明键集游标
 * @param limit 单页数量
 */
public record DeviceSearchQuery(UUID projectId, String keyword, Set<UUID> deviceTypeIds,
                                Set<Device.Status> statuses, UUID groupId, String tagKey,
                                String tagValue, String cursor, int limit) {
    /**
     * 规整集合和文本，并在应用服务入口再次校验 HTTP 层已经声明的约束。
     *
     * <p>应用服务可能被批处理或测试直接调用，不能把参数安全只寄托在 Controller 的 Bean Validation 上。</p>
     */
    public DeviceSearchQuery {
        deviceTypeIds = deviceTypeIds == null ? Set.of() : Set.copyOf(deviceTypeIds);
        statuses = statuses == null ? Set.of() : Set.copyOf(statuses);
        keyword = blankToNull(keyword);
        tagKey = blankToNull(tagKey);
        tagValue = blankToNull(tagValue);
        cursor = blankToNull(cursor);
        if (projectId == null || limit < 1 || limit > 200 || keyword != null && keyword.length() > 128
                || tagKey != null && tagKey.length() > 64 || tagValue != null && tagValue.length() > 128
                || (tagKey == null) != (tagValue == null)) {
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER, "设备筛选条件不合法");
        }
    }

    /** @param value 可空文本 @return 去空白后的值，空文本转 null */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
