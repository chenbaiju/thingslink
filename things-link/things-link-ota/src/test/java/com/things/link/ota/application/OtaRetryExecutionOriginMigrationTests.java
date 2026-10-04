package com.things.link.ota.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * D-160平台侧契约：真实重试必须为新的尝试留下执行来源，且来源守卫仍拒绝虚构或错配来源。
 *
 * <p><b>本模块为什么只能做结构证据：</b>{@code things-link-ota}没有任何数据库测试基础设施
 * （测试依赖只有{@code spring-boot-starter-test}，不引入Testcontainers），因此这里无法执行PL/pgSQL。
 * 断言对象是从测试类路径读到的追加迁移原文：它钉住“重试函数在同一事务内按本作业既有来源插入新尝试来源”、
 * “来源绑定新尝试号与本次派发修订”、“无既有来源时fail-closed”、“守卫仍以同一错误码拒绝不匹配来源”，
 * 以及“停止先赢的取消证据绑定作业当前尝试而非首次尝试”。真实PostgreSQL上的行为证据（重试后来源存在并匹配
 * 当前尝试、守卫拒绝伪造行、重试作业经真实停止收敛为CANCELLED）由bootstrap集成测试覆盖。</p>
 */
class OtaRetryExecutionOriginMigrationTests {
    /** 追加迁移原文；数据库行为不在本模块可执行范围内。 */
    private static final String MIGRATION = readMigration();

    /** 重试函数必须前移本作业既有来源，而不是从可被覆盖的当前报告回填历史。 */
    @Test void retryDispatchCarriesForwardExistingOriginForNewAttempt() {
        String retry = functionBody("public.ota_job_dispatch_retry");
        assertThat(retry)
                .as("重试必须把既有来源行读入source变量")
                .contains("source public.ota_job_execution_origin%ROWTYPE")
                .contains("SELECT * INTO source FROM public.ota_job_execution_origin o"
                        + " WHERE o.job_id=j.id AND o.attempt_no=j.attempt_no")
                .as("来源缺失必须fail-closed，不得留下无来源的新尝试")
                .contains("IF NOT FOUND THEN RETURN false; END IF;")
                .as("新尝试来源必须在派发事务内插入，并绑定新尝试号与本次派发时钟")
                .contains("INSERT INTO public.ota_job_execution_origin(")
                .contains("source.broker_received_at,source.accepted_at,at_time,j.state_version+1)")
                .as("报告的当前值已被后续设备上行覆盖，重试路径绝不从ota_device_report回填")
                .doesNotContain("FROM public.ota_device_report")
                .doesNotContain("JOIN public.ota_device_report");
    }

    /** 既定尝试只由受限函数创建，签名、安全设置、注释与授权不得在修复中被改动。 */
    @Test void retryDispatchKeepsFrozenSignatureSecurityAndGrants() {
        assertThat(MIGRATION)
                .contains("CREATE OR REPLACE FUNCTION public.ota_job_dispatch_retry(p_job uuid,p_reason varchar)"
                        + " RETURNS boolean")
                .contains("LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public"
                        + " SET row_security=off AS $$")
                .contains("COMMENT ON FUNCTION public.ota_job_dispatch_retry(uuid,varchar) IS"
                        + " '同一jobId与manifest下创建新尝试派发意图；仅在RUNNING且无停止围栏时成立'")
                .contains("REVOKE ALL ON FUNCTION public.ota_job_dispatch_retry(uuid,varchar) FROM PUBLIC")
                .contains("GRANT EXECUTE ON FUNCTION public.ota_job_dispatch_retry(uuid,varchar)"
                        + " TO thingslink_app");
    }

    /** 守卫仍以同一错误码拒绝虚构来源，且只接受当前报告或同作业既有来源的逐字节复制。 */
    @Test void originGuardStillRejectsForgedOrMismatchedOrigin() {
        String guard = functionBody("public.ota_job_origin_guard");
        assertThat(guard)
                .as("来源必须绑定到当前尝试的已派发作业时钟与修订")
                .contains("j.attempt_no=NEW.attempt_no")
                .contains("IS NOT DISTINCT FROM ROW(NEW.tenant_id,NEW.project_id,NEW.campaign_id,NEW.device_id,"
                        + "NEW.credential_version,NEW.report_revision,NEW.report_hash,NEW.captured_at,"
                        + "NEW.dispatched_revision)")
                .as("首次派发仍必须逐字节匹配当前认证报告")
                .contains("JOIN public.ota_device_report r")
                .as("重试追加只接受对同一作业更早尝试来源的逐字节复制")
                .contains("o.job_id=NEW.job_id AND o.attempt_no<NEW.attempt_no")
                .as("不匹配任何合法来源时必须以原错误码拒绝")
                .endsWith("RAISE EXCEPTION 'execution origin must match original current admission'"
                        + " USING ERRCODE='23514';\nEND;\n$$;");
        assertThat(guard)
                .as("守卫不得保留任何无条件放行")
                .doesNotContain("IF true THEN RETURN NEW; END IF;");
    }

    /** 停止先赢的CANCELLED证据必须绑定作业当前尝试，而不是硬编码的首次尝试。 */
    @Test void cancellationConsistencyBindsStopEvidenceToCurrentAttempt() {
        String cancellation = functionBody("public.ota_campaign_runtime_cancellation_consistency");
        assertThat(cancellation)
                .as("停止先赢分支必须覆盖全部已派发尝试")
                .contains("final_job.attempt_no>=1")
                .as("报告必须绑定当前尝试号")
                .contains("r.attempt_no=final_job.attempt_no")
                .contains("r.disposition='CANCELLED'")
                .as("不得再把停止先赢硬编码到首次尝试")
                .doesNotContain("final_job.attempt_no=1");
    }

    /** 从迁移原文中截取目标函数的定义体，避免断言越过函数边界。 */
    private static String functionBody(String name) {
        String marker = "FUNCTION " + name;
        int start = MIGRATION.indexOf(marker);
        assertThat(start).as("迁移必须重定义函数" + name).isGreaterThanOrEqualTo(0);
        int end = MIGRATION.indexOf("$$;", start);
        assertThat(end).as("函数" + name + "必须有完整定义体").isGreaterThan(start);
        return MIGRATION.substring(start, end + 3).replace("\r\n", "\n");
    }

    /** 迁移在main资源目录，随测试类路径可读。 */
    private static String readMigration() {
        String path = "/db/migration/ota/V20260913_1010__ota_retry_execution_origin.sql";
        try (var input = OtaRetryExecutionOriginMigrationTests.class.getResourceAsStream(path)) {
            assertThat(input).as("追加迁移必须存在：" + path).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("读取追加迁移失败", failure);
        }
    }
}
