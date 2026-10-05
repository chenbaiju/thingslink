package com.things.link.assistant.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * 管理员批准的不可变项目知识版本；元数据查询不装载正文。
 * @param id 不复用的版本标识
 * @param sourceKey 受控来源标识
 * @param versionNumber 该来源当前保留期内的版本序号
 * @param createdAt 数据库发布时间
 * @param contentSha256 规范正文摘要
 * @param content 规范正文，元数据查询时为空
 */
public record KnowledgeDocument(UUID id, String sourceKey, int versionNumber, Instant createdAt,
        String contentSha256, String content) {
    /** @return 隐藏知识原文的诊断文本 */
    @Override public String toString() { return "KnowledgeDocument[id=" + id + ",version=" + versionNumber + ",content=已隐藏]"; }
}
