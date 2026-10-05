package com.things.link.assistant.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** 启动时冻结的出站配置，精确绑定项目和不可变模型版本，不从设备文本推断语义。 */
public final class OutboundPropertyPolicy {
    /** 允许出站的封闭单位词表，不直接使用物模型自由文本单位。 */
    public enum Unit {
        CELSIUS, KELVIN, PERCENT, PASCAL, KILOPASCAL, CUBIC_METRE_PER_SECOND,
        LITRE_PER_MINUTE, METRE, MILLIMETRE, WATT, KILOWATT, KILOWATT_HOUR, UNITLESS
    }
    /** 封闭属性语义及对应允许单位；二元状态仅接受布尔值。 */
    public enum Semantic {
        TEMPERATURE(Unit.CELSIUS, Unit.KELVIN), RELATIVE_HUMIDITY(Unit.PERCENT),
        PRESSURE(Unit.PASCAL, Unit.KILOPASCAL),
        VOLUMETRIC_FLOW(Unit.CUBIC_METRE_PER_SECOND, Unit.LITRE_PER_MINUTE),
        LEVEL(Unit.METRE, Unit.MILLIMETRE), POWER(Unit.WATT, Unit.KILOWATT),
        ENERGY(Unit.KILOWATT_HOUR), BINARY_STATE(Unit.UNITLESS);
        private final Set<Unit> units;
        Semantic(Unit... values) { units = Set.of(values); }
        /**
         * 检查单位是否在该语义的允许集中。
         * @param unit 服务端配置的封闭单位
         * @return 当前语义允许该单位时为真
         */
        public boolean accepts(Unit unit) { return units.contains(unit); }
        /** 判断该语义是否必须使用布尔值，不将数值或文本转换为布尔值。 */
        public boolean isBoolean() { return this == BINARY_STATE; }
    }
    /**
     * 受控属性绑定，不从设备文本或用户输入推导。
     * @param projectId 精确匹配的项目标识
     * @param modelVersionId 精确匹配的不可变模型版本
     * @param propertyKey 该模型版本的属性键
     * @param semantic 封闭属性语义
     * @param unit 该语义允许的封闭单位
     */
    public record Binding(UUID projectId, UUID modelVersionId, String propertyKey, Semantic semantic, Unit unit) {
        public Binding {
            if (projectId == null || modelVersionId == null || propertyKey == null
                    || !propertyKey.matches("[A-Za-z0-9_-]{1,64}") || semantic == null || unit == null
                    || !semantic.accepts(unit)) throw new IllegalArgumentException("invalid outbound property binding");
        }
        @Override public String toString() { return "OutboundPropertyBinding[REDACTED]"; }
    }
    private record Key(UUID project, UUID model, String property) {}
    private final Map<Key, Binding> bindings;
    private final String fingerprint;

    /**
     * 冻结受控绑定，拒绝重复键，并按排序后的规范内容计算策略摘要。
     * @param configured 服务端配置的绑定列表，最多一千零二十四项
     */
    public OutboundPropertyPolicy(List<Binding> configured) {
        if (configured == null || configured.size() > 1024)
            throw new IllegalArgumentException("invalid outbound policy");
        Map<Key, Binding> indexed = new HashMap<>();
        List<String> canonical = new ArrayList<>();
        for (Binding binding : configured) {
            if (binding == null || indexed.putIfAbsent(new Key(binding.projectId(), binding.modelVersionId(), binding.propertyKey()), binding) != null)
                throw new IllegalArgumentException("duplicate or invalid outbound binding");
            // UUID、枚举名称和已校验属性键均不包含冒号或换行符。
            canonical.add(binding.projectId() + ":" + binding.modelVersionId() + ":" + binding.propertyKey()
                    + ":" + binding.semantic() + ":" + binding.unit());
        }
        Collections.sort(canonical);
        bindings = Map.copyOf(indexed);
        try {
            fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                    ("thingslink-agent-outbound-v1\n" + String.join("\n", canonical)).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException unavailable) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    /**
     * 按完整项目、模型版本及属性键查询，不匹配时禁止属性出站。
     * @param project 证据所属项目
     * @param model 证据所属不可变模型版本
     * @param property 实际属性键
     * @return 精确匹配的绑定；无配置时为空
     */
    public Optional<Binding> find(UUID project, UUID model, String property) {
        return Optional.ofNullable(bindings.get(new Key(project, model, property)));
    }
    /** 返回规范策略内容的摘要，便于将投影绑定到受控配置版本。 */
    public String fingerprint() { return fingerprint; }
    @Override public String toString() { return "OutboundPropertyPolicy[bindings=" + bindings.size() + "]"; }
}
