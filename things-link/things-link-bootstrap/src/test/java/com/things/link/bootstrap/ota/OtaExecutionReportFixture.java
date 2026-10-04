package com.things.link.bootstrap.ota;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** 仅数据库状态机夹具的合法最小报告；不声称通过完整设备资格或硬件验收。 */
final class OtaExecutionReportFixture {
    /** 最小数据库合同完整字节，生产资格专项另用真实完整协议。 */
    private static final byte[] CANONICAL = "{\"committedSecurityVersion\":0,\"reportSequence\":1}"
            .getBytes(StandardCharsets.UTF_8);
    /** 原始字节真实摘要，不使用伪造的重复字符摘要。 */
    static final String HASH = hash();
    /** 工具类不创建实例。 */
    private OtaExecutionReportFixture() { }
    /** 在当前真实事务补尚无报告设备，保留所有既有报告。 */
    static void seed(JdbcTemplate jdbc, UUID tenant, UUID project) {
        jdbc.update("""
                INSERT INTO ota_device_report(tenant_id,project_id,device_id,credential_version,report_sequence,
                    revision,committed_security_version,canonical,report_hash,broker_received_at,accepted_at)
                SELECT ?,?,id,1,1,1,0,?, ?,clock_timestamp()-interval '1 second',clock_timestamp()-interval '1 second'
                FROM dev_device WHERE project_id=?
                ON CONFLICT(tenant_id,project_id,device_id) DO NOTHING
                """, tenant, project, CANONICAL, HASH, project);
    }
    /** 标准摘要计算。 */
    private static String hash() {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(CANONICAL)); }
        catch (NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
    }
}
