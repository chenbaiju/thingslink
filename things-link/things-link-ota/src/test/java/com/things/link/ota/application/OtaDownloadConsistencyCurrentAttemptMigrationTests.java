package com.things.link.ota.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * D-162平台侧契约：{@code ota_download_consistency}在作业事件上必须按<b>当前尝试</b>定位下载授权，
 * 而不是对作业的全部历史授权做标量子查询。
 *
 * <p><b>本模块为什么只能做结构证据：</b>{@code things-link-ota}没有数据库测试基础设施
 * （测试依赖只有{@code spring-boot-starter-test}，不引入Testcontainers），因此这里无法执行PL/pgSQL。
 * 断言对象是从测试类路径读到的追加迁移原文：它钉住“共享身份连接请求行并按{@code r.attempt_no}收窄”、
 * “DELETE/UPDATE两侧都不得保留按job_id取全部授权的标量子查询”、“未封存当前尝试仍以原错误码拒绝推进”、
 * 以及签名与函数注释逐字保留。真实PostgreSQL上的行为证据（两条授权并存时作业仍可更新、SQLSTATE 21000
 * 不再出现、未封存当前尝试仍被拒绝、重试作业停止命令只携带当前尝试授权）由bootstrap集成测试覆盖。</p>
 */
class OtaDownloadConsistencyCurrentAttemptMigrationTests {
    /** 追加迁移原文；数据库行为不在本模块可执行范围内。 */
    private static final String MIGRATION = readMigration();

    /** 作业事件必须按共享身份连接请求行，并把当前尝试作为唯一判据。 */
    @Test void jobEventLooksUpAuthorizationOfCurrentAttempt() {
        String function = functionBody();
        assertThat(function)
                .as("作业侧必须连接ota_download_request，而不是只用job_id取全部授权")
                .contains("JOIN public.ota_download_request req ON req.tenant_id=auth.tenant_id")
                .contains("AND req.campaign_id=auth.campaign_id AND req.job_id=auth.job_id AND req.id=auth.id")
                .as("UPDATE与DELETE都必须绑定各自行的当前尝试号")
                .contains("WHERE auth.job_id=NEW.id AND req.attempt_no=NEW.attempt_no")
                .contains("WHERE auth.job_id=OLD.id AND req.attempt_no=OLD.attempt_no")
                .as("别名不得取PL/pgSQL记录变量名a/r，否则列引用歧义（真实执行过的回归）")
                .doesNotContain("JOIN public.ota_download_request r ON")
                .doesNotContain("SELECT a.id FROM public.ota_download_authorization a")
                .as("不得再保留按job_id取全部授权的标量子查询")
                .doesNotContain("(SELECT id FROM public.ota_download_authorization WHERE job_id=NEW.id)")
                .doesNotContain("(SELECT id FROM public.ota_download_authorization WHERE job_id=OLD.id)");
    }

    /** 收窄只是定位方式的修复：未封存当前尝试仍必须拒绝作业推进，封存分支与错误码逐字保留。 */
    @Test void unsealedCurrentAttemptStillRejectsJobAdvance() {
        String function = functionBody();
        assertThat(function)
                .as("未封存的当前尝试授权仍以原错误码拒绝推进")
                .contains("ELSIF EXISTS(SELECT 1 FROM public.ota_download_authorization_outbox"
                        + " WHERE authorization_id=a.id) OR j.status NOT IN"
                        + " ('DISPATCHED','RECOVERY_REQUIRED','CANCELLED') THEN")
                .contains("RAISE EXCEPTION 'download unsealed authorization cannot advance job'"
                        + " USING ERRCODE='23514'")
                .as("封存分支仍要求DOWNLOADING、封存时钟与DISPATCHED→DOWNLOADING出站证据")
                .contains("IF a.sealed_at IS NOT NULL THEN")
                .contains("RAISE EXCEPTION 'download sealed job graph incomplete' USING ERRCODE='23514'")
                .as("授权与申请图、传输图、接受图的原有校验不得被删除")
                .contains("RAISE EXCEPTION 'download authorization request graph incomplete'"
                        + " USING ERRCODE='23514'")
                .contains("RAISE EXCEPTION 'download transport graph incomplete' USING ERRCODE='23514'")
                .contains("RAISE EXCEPTION 'download acceptance graph incomplete' USING ERRCODE='23514'");
    }

    /** 活动级带宽预算仍按全部授权强制，修复只改作业侧的定位，不动预算分支。 */
    @Test void campaignBandwidthBranchStillSeesAllAuthorizations() {
        String function = functionBody();
        assertThat(function)
                .as("带宽预留计数仍按活动全部授权比对")
                .contains("IF coalesce(budget.revision,0)<>(SELECT count(*) FROM public.ota_download_authorization")
                .contains("WHERE campaign_id=cid AND signing_lease_token IS NOT NULL) THEN")
                .contains("RAISE EXCEPTION 'download bandwidth reservation count mismatch'"
                        + " USING ERRCODE='23514'");
    }

    /** CREATE OR REPLACE不得改动签名、语言、权限设置或函数注释。 */
    @Test void frozenSignatureCommentAndSecurityArePreserved() {
        assertThat(MIGRATION)
                .contains("CREATE OR REPLACE FUNCTION public.ota_download_consistency()"
                        + " RETURNS trigger LANGUAGE plpgsql AS $$")
                .contains("COMMENT ON FUNCTION public.ota_download_consistency() IS"
                        + " '事务末拒绝半封存、丢失一次预算、错误身份和缺少传输事实'");
        assertThat(functionBody())
                .as("冻结定义没有SECURITY DEFINER、search_path或row_security，修复不得新增")
                .doesNotContain("SECURITY DEFINER")
                .doesNotContain("SET search_path")
                .doesNotContain("SET row_security");
    }

    /** 从迁移原文中截取目标函数的定义体，避免断言越过函数边界。 */
    private static String functionBody() {
        String marker = "FUNCTION public.ota_download_consistency";
        int start = MIGRATION.indexOf(marker);
        assertThat(start).as("迁移必须重定义ota_download_consistency").isGreaterThanOrEqualTo(0);
        int end = MIGRATION.indexOf("$$;", start);
        assertThat(end).as("函数必须有完整定义体").isGreaterThan(start);
        return MIGRATION.substring(start, end + 3).replace("\r\n", "\n");
    }

    /** 迁移在main资源目录，随测试类路径可读。 */
    private static String readMigration() {
        String path = "/db/migration/ota/"
                + "V20260913_1020__ota_download_consistency_current_attempt_authorization.sql";
        try (var input = OtaDownloadConsistencyCurrentAttemptMigrationTests.class.getResourceAsStream(path)) {
            assertThat(input).as("追加迁移必须存在：" + path).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("读取追加迁移失败", failure);
        }
    }
}
