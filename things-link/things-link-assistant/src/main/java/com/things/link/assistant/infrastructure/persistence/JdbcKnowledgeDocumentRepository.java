package com.things.link.assistant.infrastructure.persistence;

import com.things.link.assistant.domain.KnowledgeDocument;
import com.things.link.assistant.domain.KnowledgeDocumentRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 有界项目知识SQL，正文不可变；最新版本选择先于检索，不从外部向量库取得事实。 */
@Repository
public class JdbcKnowledgeDocumentRepository implements KnowledgeDocumentRepository {
    private static final String FIELDS = "id,source_key,version_number,created_at,content_sha256";
    private static final RowMapper<KnowledgeDocument> ROW = (r,n) -> new KnowledgeDocument(r.getObject("id", UUID.class),
            r.getString("source_key"), r.getInt("version_number"), r.getTimestamp("created_at").toInstant(),
            r.getString("content_sha256"), r.getString("content"));
    private final JdbcTemplate jdbc;
    /** @param jdbc 当前普通项目事务连接，保留真实双轴行级策略 */
    public JdbcKnowledgeDocumentRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Override public Optional<KnowledgeDocument> latest(UUID tenant, UUID project, String source) {
        return jdbc.query("SELECT " + FIELDS + ",content FROM assistant_knowledge_document WHERE tenant_id=? AND project_id=?"
                + " AND source_key=? ORDER BY version_number DESC LIMIT 1", ROW, tenant, project, source).stream().findFirst();
    }
    @Override public List<KnowledgeDocument> current(UUID tenant, UUID project, boolean content) {
        // 窗口排序避免当前TimescaleDB对常量投影DISTINCT的跳扫兼容错误，不改全局优化器。
        return jdbc.query("SELECT " + FIELDS + (content ? ",content" : ",NULL::text AS content")
                + " FROM (SELECT " + FIELDS + (content ? ",content" : "")
                + ",row_number() OVER(PARTITION BY source_key ORDER BY version_number DESC) AS current_rank"
                + " FROM assistant_knowledge_document WHERE tenant_id=? AND project_id=?) versions"
                + " WHERE current_rank=1 ORDER BY source_key LIMIT 101", ROW, tenant, project);
    }
    @Override public KnowledgeDocument insert(UUID id, UUID tenant, UUID project, UUID actor, String source, int version, String hash, String content) {
        return jdbc.queryForObject("INSERT INTO assistant_knowledge_document(id,tenant_id,project_id,created_by,source_key,version_number,content_sha256,content)"
                + " VALUES (?,?,?,?,?,?,?,?) RETURNING " + FIELDS + ",content", ROW, id, tenant, project, actor, source, version, hash, content);
    }
    @Override public int deleteSource(UUID tenant, UUID project, String source) {
        return jdbc.update("DELETE FROM assistant_knowledge_document WHERE tenant_id=? AND project_id=? AND source_key=?", tenant, project, source);
    }
}
