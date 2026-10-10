package com.things.link.enduser.application;

import com.things.link.enduser.domain.EndUserErrorCode;
import com.things.link.project.application.ProjectService;
import com.things.link.shared.error.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

/** 同一外层事务内完成换签及可信入口投影，先验证凭据再查询目标元数据。 */
@Service
public class AppProjectSwitchService {
    private final AppSessionService sessions;
    private final ProjectService projects;
    private final AppProjectNavigationService navigation;
    /** @param sessions 会话互斥和签发 @param projects 项目元数据公开端口 @param navigation 配置平台身份 */
    public AppProjectSwitchService(AppSessionService sessions, ProjectService projects, AppProjectNavigationService navigation) {
        this.sessions=sessions;this.projects=projects;this.navigation=navigation;
    }
    /** @param rawToken 当前刷新凭据 @param tenant 已验签租户 @param source 源项目 @param user 已验签本人 @param target 目标定位 @return 原子换签结果；元数据失败也回滚签发 */
    @Transactional
    public Result switchProject(String rawToken,UUID tenant,UUID source,UUID user,UUID target) {
        UUID backend=navigation.requireBackendId();
        var issued=sessions.switchProject(rawToken,tenant,source,user,target);
        var project=projects.findAppCandidate(tenant,target)
                .orElseThrow(()->new BusinessException(EndUserErrorCode.NAVIGATION_PROJECT_UNAVAILABLE));
        return new Result(backend,tenant,user,project,issued);
    }
    /** @param backend 平台身份 @param tenant 租户 @param user 本人 @param project 目标投影 @param session 凭据，禁止日志 */
    public record Result(UUID backend,UUID tenant,UUID user,ProjectService.AppProjectCandidate project,AppIssuedSession session) {
        /** @return 不包含身份或凭据的标识 */
        @Override public String toString(){return "AppProjectSwitchResult[redacted]";}
    }
}
