package com.things.link.assistant.application;

import com.things.link.assistant.domain.KnowledgeDocument;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/**
 * 当前批准的项目知识元数据，不含作者身份、知识原文或外部出站资格。
 * @param id 版本UUID，删除重新发布不得复用
 * @param sourceKey 受控来源标识
 * @param versionNumber 当前保留版本序号
 * @param createdAt 发布时间
 * @param contentSha256 规范正文摘要
 */
@Schema(name="AssistantKnowledgeSource",additionalProperties=Schema.AdditionalPropertiesValue.FALSE,requiredProperties={"id","sourceKey","versionNumber","createdAt","contentSha256"})
public record KnowledgeSourceView(UUID id, String sourceKey, int versionNumber, Instant createdAt, String contentSha256) {
    /** @param source 自有不可变版本 @return 不含正文和作者的元数据 */
    public static KnowledgeSourceView from(KnowledgeDocument source) {
        return new KnowledgeSourceView(source.id(), source.sourceKey(), source.versionNumber(), source.createdAt(), source.contentSha256());
    }
    /** @param source 当前批准来源 @param content 规范正文，不发外部模型 */
    @Schema(name="AssistantKnowledgeDetail",additionalProperties=Schema.AdditionalPropertiesValue.FALSE,requiredProperties={"source","content"})
    public record Detail(KnowledgeSourceView source, @Schema(maxLength=16384,description="规范NFC纯文本，UTF-8最多16KiB") String content) {
        /** @return 不包含知识原文的诊断文本 */
        @Override public String toString() { return "KnowledgeDetail[source=" + source.id() + ",content=已隐藏]"; }
    }
}
