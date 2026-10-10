package com.things.link.enduser.application;

import com.things.link.enduser.domain.*;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import com.things.link.shared.id.Uuid7;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import static com.things.link.enduser.domain.EndUserErrorCode.*;

/** 安装关联只消费服务端认证族；沿项目、用户锁与CAS形成原子资格。 */
@Service
public class PushInstallationService {
    private final PushInstallationRepository repository;
    private final AppUserRepository users;
    private final AppAccountService accounts;
    private final AppProjectWriteGuard guard;
    private final AppProjectNavigationService navigation;
    private final PushInstallationConfiguration configuration;
    private final PushTokenCipher cipher;
    private final org.springframework.core.env.Environment environment;
    public PushInstallationService(PushInstallationRepository repository, AppUserRepository users, AppAccountService accounts,
            AppProjectWriteGuard guard, AppProjectNavigationService navigation, PushInstallationConfiguration configuration, PushTokenCipher cipher, org.springframework.core.env.Environment environment) {
        this.environment=environment;this.repository=repository;this.users=users;this.accounts=accounts;this.guard=guard;this.navigation=navigation;this.configuration=configuration;this.cipher=cipher;
    }
    /** 已验证当前族才能读本人摘要；无事实404，无token回显。 */
    @Transactional
    public Summary read(UUID tenant, UUID project, UUID user, UUID sid, UUID installation) {
        lockIdentity(tenant,project,user,sid);
        accounts.read(tenant,project,user);
        return summarize(tenant,user,repository.latest(tenant,user,installation).orElseThrow(()->error(PUSH_INSTALLATION_NOT_FOUND)));
    }
    /** 正常注册必须ACTIVE项目和有效账号角色；新版本替换旧版本，并单独保存密文。 */
    @Transactional
    public Summary register(UUID tenant, UUID project, UUID user, UUID sid, UUID installation, String channelId, String token, String expectedRevision, UUID registrationId) {
        guard.requireWritable(tenant,project);
        UUID group=lockIdentity(tenant,project,user,sid);
        accounts.read(tenant,project,user);
        navigation.requireBackendId();
        var channel=configuration.requireChannel(channelId);
        if(channel.provider()==AppPushToken.Provider.MOCK && (!environment.matchesProfiles("test", "development") || environment.matchesProfiles("prod", "production")))
            throw error(PUSH_INSTALLATION_UNAVAILABLE);
        if(registrationId==null||installation==null||token==null||token.isBlank()||token.length()>4096)throw error(CommonErrorCode.INVALID_PARAMETER);
        long expected=revision(expectedRevision);
        var old=repository.latest(tenant,user,installation).orElse(null);
        if((old==null?0:old.revision())!=expected||expected==Long.MAX_VALUE)throw error(PUSH_INSTALLATION_CONFLICT);
        var now=Instant.now();
        // 新登录可以显式接替自己的旧绑定，但旧会话不得覆盖当前仍有效的新登录绑定。
        if(old!=null&&!old.sessionGroupId().equals(group)&&repository.eligible(tenant,user,old.id()))throw error(PUSH_INSTALLATION_CONFLICT);
        UUID binding=old!=null&&old.sessionGroupId().equals(group)&&old.status().equals("ACTIVE")?old.bindingId():Uuid7.generate();
        var t=new AppPushToken(Uuid7.generate(),tenant,user,installation,channel.provider(),AppPushToken.Status.ACTIVE,now,now,null);
        var next=new PushInstallationRepository.Binding(t.id(),installation,binding,group,expected+1,registrationId,channel.fingerprint(),channelId,now.plus(Duration.ofDays(30)),now,"ACTIVE");
        var encrypted=cipher.encrypt(t,token);
        byte[] digest=digest(channel,token);
        if(old!=null)repository.revoke(tenant,user,old.id(),now);
        repository.replace(next,t,encrypted,digest);
        return summarize(tenant,user,next);
    }
    /** 安全撤销不要求项目可写或角色仍有效，但必须验签当前族并精确匹配本人组与版本。 */
    @Transactional
    public void revoke(UUID tenant, UUID project, UUID user, UUID sid, UUID installation, String expectedRevision) {
        UUID group=lockIdentity(tenant,project,user,sid);
        long expected=revision(expectedRevision);
        var old=repository.latest(tenant,user,installation).orElse(null);
        if(old==null) { if(expected!=0)throw error(PUSH_INSTALLATION_CONFLICT); return; }
        if(old.revision()!=expected||!old.sessionGroupId().equals(group))throw error(PUSH_INSTALLATION_CONFLICT);
        repository.revoke(tenant,user,old.id(),Instant.now());
    }
    /** 与退出、改密、换签使用同一用户锁；缺sid或已撤销族统一401。 */
    private UUID lockIdentity(UUID tenant, UUID project, UUID user, UUID sid) {
        if(sid==null)throw error(END_USER_ACCESS_INVALID);
        users.lockByIdAndTenant(tenant,user).orElseThrow(()->error(END_USER_ACCESS_INVALID));
        return repository.currentGroup(tenant,user,project,sid).orElseThrow(()->error(END_USER_ACCESS_INVALID));
    }
    private Summary summarize(UUID tenant, UUID user, PushInstallationRepository.Binding b) {
        boolean active=repository.eligible(tenant,user,b.id());
        try { if(!configuration.requireChannel(b.channelConfigurationId()).fingerprint().equals(b.channelIdentity()))active=false; } catch(BusinessException unavailable) { active=false; }
        return new Summary(b.installationId(),b.bindingId(),b.sessionGroupId(),Long.toString(b.revision()),b.registrationId(),b.channelConfigurationId(),active?"ACTIVE":"REVOKED",b.leaseExpiresAt(),b.updatedAt());
    }
    /** 固定平台密钥及明确应用/环境命名空间防跨租户重复；不输出明文或摘要。 */
    private byte[] digest(PushInstallationConfiguration.Channel channel, String token) {
        try {
            byte[] key=Base64.getDecoder().decode(configuration.digestKey()==null?"":configuration.digestKey());
            if(key.length!=32)throw error(PUSH_INSTALLATION_UNAVAILABLE);
            var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));
            String namespace=channel.provider()+"\0"+channel.applicationId()+"\0"+channel.environment()+"\0";
            return mac.doFinal((namespace+token).getBytes(StandardCharsets.UTF_8));
        } catch(java.security.GeneralSecurityException|IllegalArgumentException e) { throw error(PUSH_INSTALLATION_UNAVAILABLE); }
    }
    /** 非负十进制字符串，拒绝空白、符号、小数及溢出。 */
    public static long revision(String value) {
        if(value==null||!value.matches("0|[1-9][0-9]{0,18}"))throw error(CommonErrorCode.INVALID_PARAMETER);
        try{return Long.parseLong(value);}catch(NumberFormatException e){throw error(CommonErrorCode.INVALID_PARAMETER);}
    }
    private static BusinessException error(com.things.link.shared.error.ErrorCode code) { return new BusinessException(code); }
    /** 不含token、密文或摘要的本人绑定摘要。 */
    @io.swagger.v3.oas.annotations.media.Schema(name="AppPushInstallationSummary",description="本人推送安装关联摘要，不包含任何token")
    public record Summary(UUID installationId, UUID bindingId, UUID sessionGroupId, String revision, UUID registrationId,
                          String channelConfigurationId, String status, Instant leaseExpiresAt, Instant updatedAt) {}
}
