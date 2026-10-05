package com.things.link.assistant.domain;

import java.util.List;

/** 瞬时诊断正文；结构对象本身不授予业务释放、引用语义或质量资格，禁止持久化。 */
public record AnalysisContent(String summary, List<Finding> findings, List<String> limitations) {
    /** 明确区分事实陈述、待验证推测及建议；类别不证明内容真实。 */
    public enum Kind { FACT, HYPOTHESIS, RECOMMENDATION }

    /** 瞬时诊断条目；引用必须由实际调用解码边界另验允许集。 */
    public record Finding(Kind kind, String statement, List<String> evidenceIds) {
        /**
         * 保留非空正文和不可变引用集合，不在错误或打印中回显输入。
         * @param kind 封闭诊断分类
         * @param statement 非空陈述，不代表语义已核验
         * @param evidenceIds 非空引用编号列表，所属本次证据由解码边界验证
         */
        public Finding {
            if (kind == null || !text(statement) || evidenceIds == null || evidenceIds.isEmpty()
                    || evidenceIds.stream().anyMatch(id -> !text(id))) throw invalid();
            evidenceIds = List.copyOf(evidenceIds);
        }
        @Override public String toString() { return "诊断条目[内容已隐藏]"; }
    }

    /**
     * 复制封闭结构，防止校验后被调用者修改；字节上限仍由解码边界校验。
     * @param summary 非空摘要
     * @param findings 可为空的诊断条目，不能包含空对象
     * @param limitations 可为空的限制说明列表，每项必须非空
     */
    public AnalysisContent {
        if (!text(summary) || findings == null || findings.stream().anyMatch(item -> item == null)
                || limitations == null || limitations.stream().anyMatch(item -> !text(item))) throw invalid();
        findings = List.copyOf(findings);
        limitations = List.copyOf(limitations);
    }
    @Override public String toString() { return "诊断内容[内容已隐藏]"; }

    /** 保持原跨语言合同：只要求非空，不擅自改写文本或剔除空白字符。 */
    private static boolean text(String value) { return value != null && !value.isEmpty(); }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("INVALID_ANALYSIS_CONTENT"); }
}
