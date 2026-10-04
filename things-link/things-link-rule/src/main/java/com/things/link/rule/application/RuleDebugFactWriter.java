package com.things.link.rule.application;
import com.things.link.rule.domain.RuleDebugEvent;
import com.things.link.rule.domain.RuleDebugEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
/** Worker完成后短事务复验当前权限和生命周期，再保存真实调试事实。 */
@Service
public class RuleDebugFactWriter {
    /** 持久调试事实端口。 */
    private final RuleDebugEventRepository events;
    /** 持续写许可与角色复核。 */
    private final RuleManagementAccess access;
    /** 装配事实仓储和管理许可。 */
    public RuleDebugFactWriter(RuleDebugEventRepository events, RuleManagementAccess access) { this.events = events; this.access = access; }
    /** 失败回滚，不把无事实的输出返回为成功。 */
    @Transactional
    public void save(RuleDebugEvent event) { access.write(event.projectId(), false); events.save(event); }
}
