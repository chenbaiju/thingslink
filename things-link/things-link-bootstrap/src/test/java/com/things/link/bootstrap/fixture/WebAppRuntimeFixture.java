package com.things.link.bootstrap.fixture;

import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * WebApp 运行读取 HTTP 验收共用的真实 PostgreSQL 夹具。
 *
 * <p>S12-2a4b 从普通生产迁移表建立完整应用、看板版本、快照摘要和授权关系，避免调用尚受
 * D-145 阻塞的生产发布资格。夹具只负责写入确定的权威事实，不替代被测运行解析与授权逻辑。</p>
 */
public final class WebAppRuntimeFixture {

    /** 仅用于生成符合生产合同的不可变 JSON；摘要仍交给真实 PostgreSQL 规范化后计算。 */
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** 工具类只提供静态夹具入口，不允许实例化后携带跨测试状态。 */
    private WebAppRuntimeFixture() {
    }

    /**
     * 建立一个当前应用及两个有序看板，只向观察者授予第二个看板的 READ 权限。
     *
     * @param owner 具备生产迁移表写权限的测试 owner 访问器
     * @return 可供 current 与后续精确 Schema HTTP 验收复用的全部稳定身份
     */
    public static Fixture seed(JdbcTemplate owner) {
        Objects.requireNonNull(owner, "owner");
        UUID tenantId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID appUserId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID applicationId = UUID.randomUUID();
        String appKey = "app_" + compact(UUID.randomUUID());

        owner.update("INSERT INTO sys_tenant(id,name) VALUES (?,'WebApp运行租户')", tenantId);
        // 历史查询要求有效策略；固定在 S14 前的迁移夹具使用当时的 FREE，最新库优先 R1。
        owner.update("""
                UPDATE sys_tenant SET quota_policy_id=COALESCE(
                    (SELECT id FROM sys_quota_policy WHERE code='PLAN_R1_FREE'),
                    (SELECT id FROM sys_quota_policy WHERE code='FREE'))
                 WHERE id=?
                """, tenantId);
        owner.update("""
                INSERT INTO sys_project(id,tenant_id,name,project_key,status)
                VALUES (?,?,'WebApp运行项目',?,'ACTIVE')
                """, projectId, tenantId, compact(projectId));
        owner.update("""
                INSERT INTO sys_account(id,email,password_hash,display_name)
                VALUES (?,?,'test-only','WebApp版本夹具')
                """, actorId, actorId + "@example.com");
        owner.update("""
                INSERT INTO app_user(id,tenant_id,username,password_hash,status)
                VALUES (?,?,?,'test-only','ACTIVE')
                """, appUserId, tenantId, compact(appUserId));
        owner.update("""
                INSERT INTO app_user_role(id,tenant_id,project_id,app_user_id,role,status)
                VALUES (?,?,?,?,'OBSERVER','ACTIVE')
                """, UUID.randomUUID(), tenantId, projectId, appUserId);

        Board entry = createBoard(owner, tenantId, projectId, actorId, "入口看板");
        Board authorized = createBoard(owner, tenantId, projectId, actorId, "授权看板");
        owner.update("""
                INSERT INTO app_application(
                    id,tenant_id,project_id,app_key,management_name,created_by,updated_by)
                VALUES (?,?,?,?,'WebApp运行应用',?,?)
                """, applicationId, tenantId, projectId, appKey, actorId, actorId);
        UUID applicationVersionId = createApplicationVersion(owner, tenantId, projectId, actorId,
                applicationId, List.of(entry, authorized), "WebApp运行应用");
        Fixture fixture = new Fixture(tenantId, projectId, appUserId, actorId, applicationId,
                applicationVersionId, appKey, entry, authorized);
        owner.update("""
                UPDATE app_application
                   SET current_version_id=?, publication_revision=1
                 WHERE id=?
                """, applicationVersionId, applicationId);
        grant(owner, fixture, authorized);
        return fixture;
    }

    /**
     * 向夹具中的观察者授予另一个看板的 ACTIVE READ 权限。
     *
     * <p>该入口用于先证明应用入口因缺 grant 变为 {@code null}，再授予入口看板并验证原有
     * 应用顺序和入口选择恢复；它不模拟 grant 管理 API 的 CAS 行为。</p>
     *
     * @param owner 具备生产迁移表写权限的测试 owner 访问器
     * @param fixture 目标 WebApp 运行身份
     * @param board 要授予的同项目看板
     */
    public static void grant(JdbcTemplate owner, Fixture fixture, Board board) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(fixture, "fixture");
        Objects.requireNonNull(board, "board");
        owner.update("""
                INSERT INTO app_user_dashboard(
                    id,tenant_id,project_id,app_user_id,dashboard_id,status,revision,
                    created_at,updated_at,created_by,updated_by)
                VALUES (?,?,?,?,?,'ACTIVE',1,now(),now(),?,?)
                """, UUID.randomUUID(), fixture.tenantId(), fixture.projectId(), fixture.appUserId(),
                board.dashboardId(), fixture.actorId(), fixture.actorId());
    }

    /**
     * 创建稳定看板目录及真实不可变 V1，并把目录指针推进到该版本。
     *
     * @param owner 测试 owner 访问器
     * @param tenantId 已建立的租户身份
     * @param projectId 已建立的项目身份
     * @param actorId Console 发布身份
     * @param title 封存在版本中的导航标题
     * @return 看板目录与精确版本身份
     */
    private static Board createBoard(JdbcTemplate owner, UUID tenantId, UUID projectId,
                                     UUID actorId, String title) {
        UUID dashboardId = UUID.randomUUID();
        owner.update("""
                INSERT INTO dash_dashboard(
                    id,tenant_id,project_id,management_name,created_by,updated_by)
                VALUES (?,?,?,'WebApp看板管理名',?,?)
                """, dashboardId, tenantId, projectId, actorId, actorId);
        UUID dashboardVersionId = createDashboardVersion(
                owner, tenantId, projectId, actorId, dashboardId, title);
        owner.update("""
                UPDATE dash_dashboard
                   SET current_version_id=?, publication_revision=1
                 WHERE id=?
                """, dashboardVersionId, dashboardId);
        return new Board(dashboardId, dashboardVersionId, title);
    }

    /**
     * 写入结构完整的看板 V1，并由 PostgreSQL 对规范 JSONB 文本计算权威摘要。
     *
     * @param owner 测试 owner 访问器
     * @param tenantId 已建立的租户身份
     * @param projectId 已建立的项目身份
     * @param actorId Console 发布身份
     * @param dashboardId 所属看板目录
     * @param title 页面及应用引用标题
     * @return 新看板版本身份
     */
    private static UUID createDashboardVersion(JdbcTemplate owner, UUID tenantId, UUID projectId,
                                               UUID actorId, UUID dashboardId, String title) {
        UUID dashboardVersionId = UUID.randomUUID();
        ObjectNode schema = JSON.createObjectNode().put("schemaVersion", "tc.dashboard/v1");
        schema.putObject("presentation").put("mode", "RESPONSIVE_GRID")
                .put("theme", "LIGHT").put("columns", 24).put("rowHeight", 8).put("gap", 8);
        schema.putArray("models");
        schema.putArray("variables");
        schema.putArray("pages").addObject()
                .put("id", "main")
                .put("title", title)
                .putArray("components");
        owner.update("""
                INSERT INTO dash_dashboard_version(
                    id,tenant_id,project_id,dashboard_id,version_number,source_draft_revision,
                    schema,schema_version,schema_digest_algorithm,schema_digest,
                    required_components,required_resources,published_by_account_id,published_at)
                VALUES (?,?,?,?,1,0,?::jsonb,'tc.dashboard/v1','PG_JSONB_TEXT_V1_SHA256',
                    encode(digest(convert_to(?::jsonb::text,'UTF8'),'sha256'),'hex'),
                    '[]'::jsonb,'[]'::jsonb,?,now())
                """, dashboardVersionId, tenantId, projectId, dashboardId,
                schema.toString(), schema.toString(), actorId);
        return dashboardVersionId;
    }

    /**
     * 封存应用 V1 及两条有序精确看板关系，入口固定为第一条引用。
     *
     * @param owner 测试 owner 访问器
     * @param tenantId 已建立的租户身份
     * @param projectId 已建立的项目身份
     * @param actorId Console 发布身份
     * @param applicationId 应用目录身份
     * @param boards 按应用声明顺序排列的看板
     * @param displayName 当前不可变应用展示名
     * @return 新应用版本身份
     */
    private static UUID createApplicationVersion(JdbcTemplate owner, UUID tenantId, UUID projectId,
                                                 UUID actorId, UUID applicationId,
                                                 List<Board> boards, String displayName) {
        UUID applicationVersionId = UUID.randomUUID();
        ObjectNode snapshot = JSON.createObjectNode()
                .put("formatVersion", "tc.application/v1")
                .put("displayName", displayName);
        snapshot.putObject("hostCompatibility")
                .put("minInclusive", "1.0.0")
                .put("maxExclusive", "2.0.0");
        var references = snapshot.putArray("dashboardRefs");
        for (Board board : boards) {
            ObjectNode reference = references.addObject()
                    .put("dashboardId", board.dashboardId().toString())
                    .put("dashboardVersionId", board.dashboardVersionId().toString())
                    .put("dashboardVersionNumber", "1")
                    .put("title", board.title())
                    .put("schemaVersion", "tc.dashboard/v1")
                    .put("schemaDigestAlgorithm", "PG_JSONB_TEXT_V1_SHA256")
                    .put("schemaDigest", owner.queryForObject(
                            "SELECT schema_digest FROM dash_dashboard_version WHERE id=?",
                            String.class, board.dashboardVersionId()));
            reference.putArray("pages").addObject()
                    .put("id", "main")
                    .put("title", board.title());
        }
        snapshot.put("entryDashboardId", boards.getFirst().dashboardId().toString());
        snapshot.putArray("requiredSchemas").add("tc.dashboard/v1");
        snapshot.putArray("requiredComponents");
        snapshot.putArray("requiredResources");
        owner.update("""
                INSERT INTO app_application_version(
                    id,tenant_id,project_id,application_id,version_number,source_draft_revision,
                    snapshot,snapshot_digest_algorithm,snapshot_digest,published_by_account_id,published_at)
                VALUES (?,?,?,?,1,0,?::jsonb,'PG_JSONB_TEXT_V1_SHA256',
                    encode(digest(convert_to(?::jsonb::text,'UTF8'),'sha256'),'hex'),?,now())
                """, applicationVersionId, tenantId, projectId, applicationId,
                snapshot.toString(), snapshot.toString(), actorId);
        for (int position = 0; position < boards.size(); position++) {
            Board board = boards.get(position);
            owner.update("""
                    INSERT INTO app_application_version_dashboard_ref(
                        tenant_id,project_id,application_id,application_version_id,position,
                        dashboard_id,dashboard_version_id)
                    VALUES (?,?,?,?,?,?,?)
                    """, tenantId, projectId, applicationId,
                    applicationVersionId, position, board.dashboardId(), board.dashboardVersionId());
        }
        return applicationVersionId;
    }

    /**
     * 将随机 UUID 转为不含分隔符的规范稳定键片段。
     *
     * @param id 随机身份
     * @return 三十二位小写十六进制文本
     */
    private static String compact(UUID id) {
        return id.toString().replace("-", "");
    }

    /**
     * 看板目录与当前精确版本引用。
     *
     * @param dashboardId 看板目录身份
     * @param dashboardVersionId 不可变 V1 身份
     * @param title 应用快照封存的导航标题
     */
    public record Board(UUID dashboardId, UUID dashboardVersionId, String title) {

        /** 拒绝不完整夹具在 HTTP 断言阶段表现为误导性的空字段。 */
        public Board {
            Objects.requireNonNull(dashboardId, "dashboardId");
            Objects.requireNonNull(dashboardVersionId, "dashboardVersionId");
            Objects.requireNonNull(title, "title");
        }
    }

    /**
     * WebApp current 与后续 Schema HTTP 验收所需的完整真实身份。
     *
     * @param tenantId 可信租户身份
     * @param projectId 可信 ACTIVE 项目身份
     * @param appUserId ACTIVE OBSERVER 应用用户身份
     * @param actorId 建立不可变版本事实的 Console 账号身份
     * @param applicationId 当前应用目录身份
     * @param applicationVersionId 当前不可变应用 V1 身份
     * @param appKey 规范公开应用键
     * @param entry 应用快照声明的第一入口看板
     * @param authorized 初始唯一具有 ACTIVE READ grant 的看板
     */
    public record Fixture(UUID tenantId, UUID projectId, UUID appUserId, UUID actorId,
                          UUID applicationId, UUID applicationVersionId, String appKey,
                          Board entry, Board authorized) {

        /** 拒绝未完成播种的夹具逸出工具边界。 */
        public Fixture {
            Objects.requireNonNull(tenantId, "tenantId");
            Objects.requireNonNull(projectId, "projectId");
            Objects.requireNonNull(appUserId, "appUserId");
            Objects.requireNonNull(actorId, "actorId");
            Objects.requireNonNull(applicationId, "applicationId");
            Objects.requireNonNull(applicationVersionId, "applicationVersionId");
            Objects.requireNonNull(appKey, "appKey");
            Objects.requireNonNull(entry, "entry");
            Objects.requireNonNull(authorized, "authorized");
        }
    }
}
