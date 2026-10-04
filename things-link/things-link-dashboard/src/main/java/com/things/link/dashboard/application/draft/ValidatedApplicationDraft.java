package com.things.link.dashboard.application.draft;

import tools.jackson.databind.JsonNode;

import java.util.Objects;

/**
 * 已通过应用草稿原文与结构合同的保存输入。
 *
 * @param expectedRevision 调用方读取的草稿revision
 * @param content 完整tc.application/v1内容
 */
public record ValidatedApplicationDraft(long expectedRevision, JsonNode content) {

    /** 冻结可变JSON树，保证同一校验结果不会被调用方事后改写。 */
    public ValidatedApplicationDraft {
        if (expectedRevision < 0) {
            throw new IllegalArgumentException("expectedRevision不得为负数");
        }
        content = Objects.requireNonNull(content, "content").deepCopy();
    }

    /**
     * 返回与内部校验事实隔离的内容副本。
     *
     * @return 调用方可安全读取或修改的JSON树
     */
    @Override
    public JsonNode content() {
        return content.deepCopy();
    }
}
