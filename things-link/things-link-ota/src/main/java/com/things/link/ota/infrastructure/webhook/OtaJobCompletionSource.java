package com.things.link.ota.infrastructure.webhook;

import com.things.link.ota.domain.OtaJobCompletion;
import com.things.link.ota.domain.OtaJobCompletionCapture;
import com.things.link.ota.domain.OtaJobCompletionRepository;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.shared.message.PublicWebhookEvent;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import com.things.link.support.trace.TraceContext;
import com.things.link.support.webhook.PublicWebhookSourceWriter;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

/** ADR0204：领域窄端口到中性Outbox的同连接、提交前适配，不扫描历史或异步补造。 */
@Component
public class OtaJobCompletionSource implements OtaJobCompletionCapture {
    private final JdbcTemplate jdbc;
    private final ProjectLifecycleAccessService projects;
    private final OtaJobCompletionRepository completions;
    private final PublicWebhookSourceWriter source;
    private final TransactionLocalRlsScope rls;
    private final ObjectMapper json;

    /** 只依赖项目公开许可及support交接端口，禁止引用集成领域。 */
    public OtaJobCompletionSource(JdbcTemplate jdbc, ProjectLifecycleAccessService projects,
            OtaJobCompletionRepository completions, PublicWebhookSourceWriter source,
            TransactionLocalRlsScope rls, ObjectMapper json) {
        this.jdbc=jdbc; this.projects=projects; this.completions=completions;
        this.source=source; this.rls=rls; this.json=json;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    @Transactional(propagation=Propagation.MANDATORY)
    public void capture(UUID tenant, UUID project) {
        if (!source.enabled()) return;
        requireTransaction();
        String transaction=scopeTransaction(tenant,project);
        // 仅确认既有实际范围，不能由来源组件替调用者建立新的授权身份。
        rls.establish(tenant,project);
        Object connection=TransactionSynchronizationManager.getResource(jdbc.getDataSource());
        for (var sync:TransactionSynchronizationManager.getSynchronizations()) {
            if (sync instanceof CompletionSynchronization registered && registered.connection==connection) {
                if (!registered.tenant.equals(tenant) || !registered.project.equals(project)
                        || !registered.transaction.equals(transaction)) throw invalid();
                registered.mark();
                return;
            }
        }
        var generation=projects.lockReadableGeneration(tenant,project);
        if (generation.isEmpty() || !projects.snapshot(tenant,project).writeAllowed()) return;
        var sync=new CompletionSynchronization(connection,tenant,project,transaction,generation.getAsLong(),TraceContext.current());
        sync.mark();
        TransactionSynchronizationManager.registerSynchronization(sync);
    }

    /** 拒绝非事务/只读/无同步入口；不能让SET LOCAL在自动提交下短暂生效。 */
    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) throw invalid();
    }

    /** 不覆盖错scope，提交前再次核验实际连接与原xid。 */
    private String scopeTransaction(UUID tenant, UUID project) {
        if (tenant==null || project==null) throw invalid();
        return jdbc.queryForObject("""
                SELECT current_setting('app.tenant_id',true) AS tenant,
                    current_setting('app.project_id',true) AS project,pg_current_xact_id()::text AS xid
                """,(rs,row)->{
            if (!tenant.toString().equals(rs.getString("tenant")) || !project.toString().equals(rs.getString("project")))
                throw invalid();
            return rs.getString("xid");
        });
    }

    /** 内部一致性失败回滚，不能伪装为设备业务拒绝。 */
    private static IllegalStateException invalid() { return new IllegalStateException("OTA完成来源事务或范围不一致"); }

    /** 同步集合跟随Spring实际事务挂起/恢复，独立事务及线程不继承外层同步器。 */
    private final class CompletionSynchronization implements TransactionSynchronization {
        private final Object connection;
        private final UUID tenant,project;
        private final String transaction,trace;
        private final long generation;
        private CompletionSynchronization(Object connection, UUID tenant, UUID project, String transaction, long generation, String trace) {
            this.connection=connection; this.tenant=tenant; this.project=project;
            this.transaction=transaction; this.generation=generation; this.trace=trace;
        }
        /** 每次同事务重入重新确认局部标志，亦兼容已回滚保存点中的首次登记。 */
        private void mark() {
            var marker=json.createObjectNode();
            marker.put("tenantId",tenant.toString()); marker.put("projectId",project.toString());
            marker.put("transaction",transaction); marker.put("generation",Long.toString(generation)); marker.put("traceId",trace);
            jdbc.queryForObject("SELECT set_config('app.ota_completion_capture',?,true)",String.class,marker.toString());
        }
        /** 全部完成事实按原事务查询；超界或任一Outbox失败撤销整个业务事务。 */
        @Override public void beforeCommit(boolean readOnly) {
            if (readOnly || TransactionSynchronizationManager.getResource(jdbc.getDataSource())!=connection
                    || !transaction.equals(scopeTransaction(tenant,project))) throw invalid();
            UUID cursor=null; int total=0;
            while (true) {
                var page=completions.page(tenant,project,transaction,cursor,100);
                total+=page.size();
                if (total>1000) throw new IllegalStateException("OTA完成来源超过单事务1000目标上界");
                for (var completion:page) {
                    if (!tenant.equals(completion.tenantId()) || !project.equals(completion.projectId())
                            || !transaction.equals(completion.originTransaction()) || generation!=completion.projectGeneration()) throw invalid();
                    append(completion);
                }
                if (page.size()<100) return;
                UUID next=page.getLast().jobId();
                if (next.equals(cursor)) throw invalid();
                cursor=next;
            }
        }
    }

    /** 精确冻结原值，数字使用十进制字符串，缺失值显式null，无下载秘密。 */
    private void append(OtaJobCompletion row) {
        var payload=json.createObjectNode();
        payload.put("jobId",row.jobId().toString()); payload.put("campaignId",row.campaignId().toString());
        payload.put("firmwareId",row.firmwareId().toString()); payload.put("manifestSha256",row.manifestSha256());
        payload.put("fromStatus",row.fromStatus()); payload.put("status",row.status());
        payload.put("stateVersion",Long.toString(row.stateVersion())); payload.put("attemptNo",Integer.toString(row.attemptNo()));
        payload.put("failureCode",row.failureCode()); payload.put("completedAt",row.completedAt().toString());
        source.append(new PublicWebhookEvent(row.jobId(),"ota.job.completed",row.tenantId(),row.projectId(),
                row.projectGeneration(),"ota_job",row.jobId(),row.deviceId(),row.completedAt(),row.completedAt(),row.traceId(),payload.toString()));
    }
}
