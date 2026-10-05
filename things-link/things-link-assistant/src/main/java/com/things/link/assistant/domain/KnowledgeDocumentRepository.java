package com.things.link.assistant.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 自有知识版本仓储；每个普通语句显式限定真实租户和项目，不接收身份文本或查询表达式。 */
public interface KnowledgeDocumentRepository {
    /** @param tenant 项目真实租户 @param project 项目 @param source 受控来源 @return 最新不可变正文 */
    Optional<KnowledgeDocument> latest(UUID tenant, UUID project, String source);
    /** @param tenant 项目真实租户 @param project 项目 @param content 是否仅此查询装载正文 @return 最多101个当前来源，用额外一行检测越界 */
    List<KnowledgeDocument> current(UUID tenant, UUID project, boolean content);
    /** @param id 服务端版本标识 @param tenant 真实租户 @param project 项目 @param actor 当前管理员 @param source 来源 @param version 服务端序号 @param hash 正文摘要 @param content 已批准正文 @return 已写入版本 */
    KnowledgeDocument insert(UUID id, UUID tenant, UUID project, UUID actor, String source, int version, String hash, String content);
    /** @param tenant 真实租户 @param project 项目 @param source 当前管理员批准删除的来源 @return 物理移除的版本数量，受最多20个版本约束 */
    int deleteSource(UUID tenant, UUID project, String source);
}
