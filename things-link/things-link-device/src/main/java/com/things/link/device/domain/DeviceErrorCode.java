package com.things.link.device.domain;

import com.things.link.shared.error.ErrorCode;

/** 设备与物模型错误码，占用 {@code 3xxxx} 段；新增前必须登记到 ERROR_CODES.md。 */
public enum DeviceErrorCode implements ErrorCode {
    /** 当前点必须是有限WGS84经纬度对或同时为空。 */
    LOCATION_POINT_INVALID(30068, "设备坐标不合法", 400),
    /** 独立坐标版本冲突或已经耗尽，禁止覆盖并发更新。 */
    LOCATION_POINT_VERSION_CONFLICT(30069, "设备坐标版本冲突，请重新加载", 409),
    /** 设备类型不存在或不属于当前项目。 */
    DEVICE_TYPE_NOT_FOUND(30001, "设备类型不存在", 404),
    /** 当前项目角色不能创建设备类型。 */
    DEVICE_TYPE_CREATE_FORBIDDEN(30002, "当前角色无权创建设备类型", 403),
    /** 同一项目内设备类型标识符重复。 */
    DEVICE_TYPE_KEY_CONFLICT(30003, "设备类型标识符已存在", 409),
    /** 当前项目角色不能修改或删除设备类型。 */
    DEVICE_TYPE_WRITE_FORBIDDEN(30004, "当前角色无权修改设备类型", 403),
    /** 已发布版本是设备数据契约，必须通过新版本演进。 */
    DEVICE_TYPE_PUBLISHED_IMMUTABLE(30005, "已发布设备类型不可修改或删除", 409),
    /** 设备分类与报文协议不在允许矩阵中。 */
    DEVICE_TYPE_PROTOCOL_INCOMPATIBLE(30006, "设备分类与报文协议不匹配", 400),
    /** 自定义数据流不存在或不属于当前设备类型。V1 控制面已下线（PS-039），码位保留；S15 仅在错误语义完全一致时可重新激活，否则用新码位。 */
    DATA_STREAM_NOT_FOUND(30007, "自定义数据流不存在", 404),
    /** 同一设备类型内数据流标识符重复。V1 控制面已下线（PS-039），码位保留；S15 仅在错误语义完全一致时可重新激活，否则用新码位。 */
    DATA_STREAM_KEY_CONFLICT(30008, "自定义数据流标识符已存在", 409),
    /** 高级 MQTT Topic 配置不完整或与模式冲突。V1 控制面已下线（PS-039），码位保留；S15 仅在错误语义完全一致时可重新激活，否则用新码位。 */
    DATA_STREAM_TOPIC_INVALID(30010, "自定义 MQTT Topic 配置不合法", 400),
    /** 属性定义不存在或不属于当前设备类型。 */
    PROPERTY_DEFINITION_NOT_FOUND(30011, "属性定义不存在", 404),
    /** 同一设备类型内属性标识符重复。 */
    PROPERTY_DEFINITION_KEY_CONFLICT(30012, "属性标识符已存在", 409),
    /** 数据类型专属约束不完整或互相冲突。 */
    PROPERTY_DEFINITION_CONSTRAINT_INVALID(30013, "属性数据约束不合法", 400),
    /** 事件定义不存在或不属于当前设备类型。 */
    EVENT_DEFINITION_NOT_FOUND(30014, "事件定义不存在", 404),
    /** 同一设备类型内事件标识符重复。 */
    EVENT_DEFINITION_KEY_CONFLICT(30015, "事件标识符已存在", 409),
    /** 事件参数键重复或枚举参数没有合法选项。 */
    EVENT_DEFINITION_PARAMETER_INVALID(30016, "事件参数定义不合法", 400),
    /** 命令定义不存在或不属于当前设备类型。 */
    COMMAND_DEFINITION_NOT_FOUND(30017, "命令定义不存在", 404),
    /** 同一设备类型内命令标识符重复。 */
    COMMAND_DEFINITION_KEY_CONFLICT(30018, "命令标识符已存在", 409),
    /** 输入或输出 JSON Schema 语法不合法。 */
    COMMAND_DEFINITION_SCHEMA_INVALID(30019, "命令 Schema 定义不合法", 400),
    /** 设备不存在或不属于当前项目。 */
    DEVICE_NOT_FOUND(30020, "设备不存在", 404),
    /** 同一项目内设备标识符重复。 */
    DEVICE_KEY_CONFLICT(30021, "设备标识符已存在", 409),
    /** 凭据不存在或不属于当前设备。 */
    CREDENTIAL_NOT_FOUND(30022, "设备凭据不存在", 404),
    /** desired 乐观锁版本冲突，调用方应重新读取影子后重试。 */
    SHADOW_VERSION_CONFLICT(30023, "影子版本冲突，请刷新后重试", 409),
    /** 当前项目角色不能修改设备、凭据或影子。 */
    DEVICE_WRITE_FORBIDDEN(30024, "当前角色无权修改设备", 403),
    /** 影子内容必须是合法的 JSON 对象。 */
    SHADOW_JSON_INVALID(30025, "影子内容必须是合法的 JSON 对象", 400),
    /** 项目、产品或产品密钥任一不匹配；统一响应避免枚举有效产品。 */
    DEVICE_REGISTRATION_DENIED(30026, "设备注册凭据无效", 403),
    /** 只有已发布设备类型才能启用一型一密。 */
    PRODUCT_CREDENTIAL_STATE_INVALID(30027, "仅已发布设备类型可生成产品凭据", 409),
    /** device 域在可信路由解析时执行输入 Schema 校验，因此必须在本模块保留该业务码。 */
    COMMAND_REQUEST_INVALID(30030, "命令请求参数不符合定义", 400),
    /** 设备组不存在、已删除或不属于当前项目时统一返回，避免跨项目枚举。 */
    DEVICE_GROUP_NOT_FOUND(30032, "设备组不存在", 404),
    /** 项目内有效设备组名称必须唯一。 */
    DEVICE_GROUP_NAME_CONFLICT(30033, "设备组名称已存在", 409),
    /** 动态组只能使用类型、状态和键值标签白名单，且至少包含一个条件。 */
    DEVICE_GROUP_RULE_INVALID(30034, "动态设备组规则不合法", 400),
    /** 租户共享设备存量已达到套餐硬上限，新设备必须拒绝但既有设备保持在线。 */
    DEVICE_QUOTA_EXCEEDED(30035, "租户共享设备额度已用尽", 429),
    /** 网关设备不存在、已删除或设备类型不是网关。 */
    DEVICE_TOPOLOGY_GATEWAY_INVALID(30036, "网关设备不存在或不是网关类型", 400),
    /** 子设备不存在、已删除或设备类型不是子设备。 */
    DEVICE_TOPOLOGY_SUB_DEVICE_INVALID(30037, "子设备不存在或不是子设备类型", 400),
    /** 网关不能绑定自己。 */
    DEVICE_TOPOLOGY_SELF_BIND(30038, "网关不能绑定自己", 400),
    /** 同一子设备被并发绑定到不同网关，由部分唯一索引仲裁。 */
    DEVICE_TOPOLOGY_CONFLICT(30039, "拓扑绑定冲突，请刷新后重试", 409),
    /** 子设备通过网关间接接入，不支持独立接入凭据或一型一密。 */
    SUB_DEVICE_CREDENTIAL_FORBIDDEN(30040, "子设备不支持独立接入凭据", 409),
    /** 子设备未绑定网关，无法解析实际连接设备进行下行。 */
    SUB_DEVICE_UNBOUND(30041, "子设备未绑定网关，无法下发", 409),
    /** 子设备绑定的网关当前离线，无法下行。 */
    GATEWAY_OFFLINE(30049, "网关离线，无法下发", 409),
    /** 网关设备类型不是 STANDARD_GATEWAY / MODBUS_RTU_CLOUD_GATEWAY，禁止创建点位映射。 */
    MODBUS_POINT_DEVICE_TYPE_INVALID(30042, "网关设备类型不支持 Modbus 点位映射", 400),
    /** 点位绑定的子设备不存在、不是子设备类型或未绑定到该网关。 */
    MODBUS_POINT_SUB_DEVICE_INVALID(30043, "子设备不存在或未绑定到该网关", 400),
    /** 点位绑定的属性不存在、不可上报或类型与 Modbus 数据类型不匹配。 */
    MODBUS_POINT_PROPERTY_INVALID(30044, "子设备属性不存在或类型不匹配", 400),
    /** 同一网关下两个点位的寄存器区间重叠。 */
    MODBUS_POINT_REGISTER_OVERLAP(30045, "Modbus 寄存器点位重叠", 409),
    /** 从站地址、功能码与数据类型组合非法。 */
    MODBUS_POINT_CONSTRAINT_INVALID(30046, "Modbus 点位约束不合法", 400),
    /** 点位不存在或不属于当前项目。 */
    MODBUS_POINT_NOT_FOUND(30047, "Modbus 点位不存在", 404),
    /** 已发布点位不可修改，需在草稿上编辑后重新发布。 */
    MODBUS_POINT_PUBLISHED_IMMUTABLE(30048, "已发布点位不可修改", 409),
    /** 旧数组列表超过兼容窗口，必须改用键集分页入口。 */
    LEGACY_LIST_LIMIT_EXCEEDED(30050, "数据量超过旧列表接口上限，请使用分页查询", 409),
    /** OBJECT/LIST Schema 不符合 TC_PROPERTY_COMPOSITE_V1 或标量携带了第二份 Schema。 */
    PROPERTY_DEFINITION_SCHEMA_INVALID(30051, "复合属性 Schema 不合法", 400),
    /** 声明版本不存在、未发布或不属于设备类型。 */
    THING_MODEL_VERSION_NOT_FOUND(30052, "物模型版本不存在", 404),
    /** 绑定当前指针已变化或目标等于当前版本。 */
    THING_MODEL_BINDING_CONFLICT(30053, "物模型绑定已变化，请刷新后重试", 409),
    /** Object/List 从第一天强制声明；标量兼容窗口由 B-X1a 统一收口。 */
    THING_MODEL_VERSION_REQUIRED(30054, "设备报文必须声明有效 modelVersion", 400),
    /** 旧版本未曾直接绑定或已超过服务端接收窗口。 */
    THING_MODEL_VERSION_HISTORY_EXPIRED(30055, "旧物模型版本已超过历史接收窗口", 409),
    /** 相同转换幂等键不能指向不同目标。 */
    THING_MODEL_BINDING_IDEMPOTENCY_CONFLICT(30056, "物模型绑定幂等键冲突", 409),
    /** 版本号、变化级别、完整快照或相邻版本线路不符合冻结合同。 */
    THING_MODEL_VERSION_LINE_INVALID(30057, "物模型版本线路不合法", 409),
    /** ADR0057：类型变更/缺失与有效拓扑角色冲突，拒绝整个操作以保留关系事实。 */
    DEVICE_TOPOLOGY_ROLE_CONFLICT(30060, "设备类型与有效拓扑角色冲突，请先调整绑定关系", 409),
    /** ADR0057：类型共享锁无法即时取得，事务回滚后调用方可重试。 */
    DEVICE_TYPE_BUSY(30061, "设备类型正在变更，请稍后重试", 409),
    /** ADR0058：数据库角色守卫无法即时取得设备或类型共享锁，全部回滚后可重试。 */
    DEVICE_TOPOLOGY_BUSY(30062, "拓扑正在变更，请稍后重试", 409),
    /** ADR0059：已有绑定不得换型；首次选型缺少有效版本时也须拒绝全部字段变更。 */
    DEVICE_TYPE_VERSION_CONFLICT(30063, "设备类型变更与物模型版本绑定冲突", 409),
    /** ADR0110：完整命令目录超出数量或字节上限，不得静默截断。 */
    COMMAND_CATALOG_UNAVAILABLE(30064, "命令目录暂时不可用", 503),
    /** ADR0193：配置已变化，须读取最新版本后决定是否重试。 */
    ACCESS_CONFIG_CONFLICT(30065, "接入配置版本冲突", 409),
    /** ADR0193：设备类型或载荷协议不支持选择的接入平面。 */
    ACCESS_PROTOCOL_UNSUPPORTED(30066, "接入协议不适配设备", 409),
    /** ADR0193：版本达到bigint上界，禁止溢出或重置。 */
    ACCESS_CONFIG_EXHAUSTED(30067, "接入配置版本耗尽", 409);

    /** 业务码。 */ private final int code;
    /** 对外中文消息。 */ private final String defaultMessage;
    /** HTTP 状态。 */ private final int httpStatus;

    DeviceErrorCode(int code, String defaultMessage, int httpStatus) {
        this.code = code; this.defaultMessage = defaultMessage; this.httpStatus = httpStatus;
    }
    /** 沿用接口定义的契约。{@inheritDoc} */ @Override public int code() { return code; }
    /** 沿用接口定义的契约。{@inheritDoc} */ @Override public String defaultMessage() { return defaultMessage; }
    /** 沿用接口定义的契约。{@inheritDoc} */ @Override public int httpStatus() { return httpStatus; }
}
