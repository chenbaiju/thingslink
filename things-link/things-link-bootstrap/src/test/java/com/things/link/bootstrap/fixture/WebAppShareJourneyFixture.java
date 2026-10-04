package com.things.link.bootstrap.fixture;

import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/** 真实封存匿名capability；复用合法历史而不宣称D-145生产Host发布资格。 */
public final class WebAppShareJourneyFixture {
    /** 不保存跨测试可变状态。 */
    private WebAppShareJourneyFixture() { }

    /** 先写固定变量scope及精确模型FK，最后创建结果封存，secret从不写数据库。 */
    public static ShareFixture seed(JdbcTemplate owner, String passwordHash) throws Exception {
        var data = WebAppInteractiveCanvasFixture.seed(owner, passwordHash);
        return issue(owner, data);
    }

    /** 相同合法历史上的独立凭据用于取消反例；原凭据撤销不影响此能力。 */
    public static ShareFixture issue(JdbcTemplate owner, WebAppInteractiveCanvasFixture.DataFixture data) throws Exception {
        return issue(owner, data, 3600);
    }

    /** 模拟合法签发300秒后仅剩短窗口的历史，不绕过最小TTL约束。 */
    public static ShareFixture issue(JdbcTemplate owner, WebAppInteractiveCanvasFixture.DataFixture data, int remainingSeconds) throws Exception {
        var f = data.runtime();
        UUID share = UUID.randomUUID();
        byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes);
        String secret = "sh_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8)));
        owner.update("""
                INSERT INTO dash_share_token(id,tenant_id,project_id,dashboard_id,dashboard_version_id,project_generation,
                    secret_hash,host_compatibility,referer_policy,created_at,expires_at,creator_account_id)
                VALUES (?,?,?,?,?,0,?,'{"minInclusive":"1.0.0","maxExclusive":"1.0.1"}'::jsonb,
                    'HOST_ORIGIN',clock_timestamp()-(? * interval '1 second'),clock_timestamp()+(? * interval '1 second'),?)
                """, share, f.tenantId(), f.projectId(), f.authorized().dashboardId(), f.authorized().dashboardVersionId(), hash, remainingSeconds < 300 ? 300 : 0, remainingSeconds, f.actorId());
        owner.update("""
                INSERT INTO dash_share_scope(tenant_id,project_id,dashboard_id,dashboard_version_id,share_id,
                    variable_key,position,thing_model_version_id) VALUES (?,?,?,?,?,'sensor',0,?)
                """, f.tenantId(), f.projectId(), f.authorized().dashboardId(), f.authorized().dashboardVersionId(), share, data.model());
        var devices = List.of(data.first(), data.second());
        for (int index = 0; index < devices.size(); index++) owner.update("""
                INSERT INTO dash_share_scope_device(tenant_id,project_id,dashboard_id,dashboard_version_id,share_id,
                    variable_key,device_id,thing_model_version_id,position) VALUES (?,?,?,?,?,'sensor',?,?,?)
                """, f.tenantId(), f.projectId(), f.authorized().dashboardId(), f.authorized().dashboardVersionId(),
                share, devices.get(index), data.model(), index);
        owner.update("""
                INSERT INTO dash_share_creation_result(tenant_id,project_id,dashboard_id,account_id,idempotency_key_digest,
                    request_digest,share_id,created_at)
                SELECT tenant_id,project_id,dashboard_id,creator_account_id,encode(digest(id::text,'sha256'),'hex'),
                    repeat('b',64),id,created_at FROM dash_share_token WHERE id=?
                """, share);
        return new ShareFixture(data, share, secret);
    }
    /** 明文仅本测试内存与Node环境；不得打印record或凭据URL。 */
    public record ShareFixture(WebAppInteractiveCanvasFixture.DataFixture data, UUID shareId, String secret) { }
}
