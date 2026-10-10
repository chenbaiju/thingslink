package com.things.link.enduser.application;

import com.things.link.enduser.domain.AppUserRole;
import com.things.link.enduser.domain.AppUserRoleRepository;
import com.things.link.enduser.domain.EndUserErrorCode;
import com.things.link.project.application.ProjectService;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import com.things.link.support.query.SignedQueryCursorCodec;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 本人项目发现仅在可信租户内有界扫描，逐项目独立事务复验角色，不绕过RLS。 */
@Service
public class AppProjectNavigationService {
    private final ProjectService projects;
    private final AppUserRoleRepository roles;
    private final AppAccountService accounts;
    private final TransactionLocalRlsScope scope;
    private final SignedQueryCursorCodec cursors;
    private final TransactionTemplate read;
    private final UUID backendId;
    private final AppProjectCursorProtector protector;

    /** @param projects 项目候选公开端口 @param roles App角色 @param accounts 本人资格复验 @param scope 事务RLS @param cursors 签名游标 @param protector 扫描锚点保密 @param manager 事务管理器 @param instance 配置的平台实例UUID，空值禁用导航 */
    public AppProjectNavigationService(ProjectService projects, AppUserRoleRepository roles,
            AppAccountService accounts, TransactionLocalRlsScope scope, SignedQueryCursorCodec cursors,
            AppProjectCursorProtector protector, PlatformTransactionManager manager, @Value("${things-link.app-navigation.backend-instance-id:}") String instance) {
        this.protector=protector;this.projects=projects;this.roles=roles;this.accounts=accounts;this.scope=scope;this.cursors=cursors;
        backendId=instance.isBlank()?null:UUID.fromString(instance);
        read=new TransactionTemplate(manager);read.setReadOnly(true);
        read.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** @return 配置的稳定平台实例；缺失明确503，不回落共享默认ID */
    public UUID requireBackendId() {
        if(backendId==null)throw new BusinessException(EndUserErrorCode.NAVIGATION_UNAVAILABLE);
        return backendId;
    }

    /** @param tenant 已认证租户 @param source 已认证源项目 @param user 已认证本人 @param cursor 签名扫描游标 @param limit 返回条数上限 @return 有界项目页，空页仍可能有下一扫描游标 */
    public Page discover(UUID tenant, UUID source, UUID user, String cursor, int limit) {
        UUID backend=requireBackendId();
        if(limit<1||limit>100)throw new BusinessException(CommonErrorCode.INVALID_PARAMETER);
        read.executeWithoutResult(tx->{scope.establish(tenant,source);accounts.read(tenant,source,user);});
        String binding=backend+":"+tenant+":"+source+":"+user+":"+limit;
        UUID after=cursors.decode(protector.open(cursor,binding),"APP_PROJECT_DISCOVERY",binding).map(SignedQueryCursorCodec.Anchor::sortId).orElse(null);
        var candidates=projects.scanAppCandidates(tenant,after,101);
        var items=new ArrayList<Item>();
        int scanned=0;
        for(var candidate:candidates) {
            if(scanned==100||items.size()==limit)break;
            scanned++;
            var role=read.execute(tx->{
                scope.establish(tenant,candidate.id());
                return roles.findByProjectAndUser(candidate.id(),user)
                        .filter(r->r.status()==AppUserRole.Status.ACTIVE).orElse(null);
            });
            if(role!=null)items.add(new Item(candidate.id(),candidate.projectKey(),candidate.name(),role.role().name()));
        }
        // 扫描期间源身份失效时整页拒绝，候选中的每项仍在换签时再次复核。
        read.executeWithoutResult(tx->{scope.establish(tenant,source);accounts.read(tenant,source,user);});
        String next=scanned<candidates.size()?cursors.encode("APP_PROJECT_DISCOVERY",binding,Instant.EPOCH,candidates.get(scanned-1).id()):null;
        return new Page(backend,tenant,user,source,List.copyOf(items),next==null?null:protector.seal(next,binding));
    }

    /** @param id 项目ID @param projectKey 入口键 @param name 名称 @param role 本人角色展示，不授予签发资格 */
    @Schema(name="AppAuthorizedProject",description="本人当前有效授权的项目候选")
    public record Item(
            @Schema(description="项目标识",requiredMode=Schema.RequiredMode.REQUIRED) UUID id,
            @Schema(description="可信项目入口键",requiredMode=Schema.RequiredMode.REQUIRED) String projectKey,
            @Schema(description="项目名称",requiredMode=Schema.RequiredMode.REQUIRED) String name,
            @Schema(description="本人当前角色，仅用于展示",requiredMode=Schema.RequiredMode.REQUIRED) String role) {}
    /** @param backendInstanceId 平台实例 @param tenantId 租户 @param appUserId 本人 @param projectId 当前项目 @param items 授权候选 @param nextCursor 最后扫描位置 */
    @Schema(name="AppAuthorizedProjectPage",description="本人项目有界扫描页及可信身份")
    public record Page(
            @Schema(description="稳定平台实例",requiredMode=Schema.RequiredMode.REQUIRED) UUID backendInstanceId,
            @Schema(description="已认证租户",requiredMode=Schema.RequiredMode.REQUIRED) UUID tenantId,
            @Schema(description="已认证本人",requiredMode=Schema.RequiredMode.REQUIRED) UUID appUserId,
            @Schema(description="当前源项目",requiredMode=Schema.RequiredMode.REQUIRED) UUID projectId,
            @Schema(description="本页授权候选，可为空",requiredMode=Schema.RequiredMode.REQUIRED) List<Item> items,
            @Schema(description="下一扫描位置，末页为空",types={"string","null"}) String nextCursor) {}
}
