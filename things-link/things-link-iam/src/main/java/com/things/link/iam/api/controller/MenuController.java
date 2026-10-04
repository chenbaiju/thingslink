package com.things.link.iam.api.controller;

import com.things.link.iam.api.dto.response.MenuAuthResponse;
import com.things.link.iam.api.dto.response.MenuMetaResponse;
import com.things.link.iam.api.dto.response.MenuNodeResponse;
import com.things.link.iam.api.support.JwtIdentity;
import com.things.link.iam.application.CurrentUserService;
import com.things.link.iam.application.MenuService;
import com.things.link.iam.application.SelfHostedReviewAccess;
import com.things.link.project.application.ProjectService;
import com.things.link.project.application.CommercialOperatorAccessService;
import com.things.link.shared.authz.ProjectRole;
import com.things.link.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 控制台菜单下发接口。
 *
 * <p>对应架构文档 11.2：{@code VITE_ACCESS_MODE=backend} 下，菜单树与按钮权限点由
 * 后端下发，前端只做渲染。
 *
 * <p>路径用 {@code /api/v1/...}，与全站一致。后台模板原本请求的是
 * {@code /api/v3/system/menus} —— 那是它自带 Apifox Mock 的地址，v3 是模板的版本号
 * 而不是接口版本，照抄会让人以为本项目的接口有三个版本。
 */
@RestController
@RequestMapping("/api/v1/system")
@Tag(name = "控制台", description = "菜单与权限点下发")
public class MenuController {

    private final CurrentUserService currentUserService;
    private final MenuService menuService;

    /**
     * 用于查「当前账号在当前项目里是什么角色」。
     *
     * <p>成员关系归 project 模块管，iam 只能通过它的 application 入口去问
     * （架构文档 10.4 规则 2）。iam → project 是允许的单向依赖。
     */
    private final ProjectService projectService;
    private final CommercialOperatorAccessService commercialAccess;
    private final ObjectProvider<SelfHostedReviewAccess> reviewAccess;

    public MenuController(CurrentUserService currentUserService,
                          MenuService menuService,
                          ProjectService projectService,
                          CommercialOperatorAccessService commercialAccess,
                          ObjectProvider<SelfHostedReviewAccess> reviewAccess) {
        this.currentUserService = currentUserService;
        this.menuService = menuService;
        this.projectService = projectService;
        this.commercialAccess = commercialAccess;
        this.reviewAccess = reviewAccess;
    }

    /**
     * 取当前用户可见的菜单树。
     *
     * <p>菜单按<b>当前项目里的角色</b>过滤（ADR 0012 校准）。原先按租户角色过滤，
     * 那隐含「一个人在一个租户里只有一个身份」，而同一个账号可以是项目 A 的 OWNER、
     * 项目 B 的 VIEWER。
     *
     * <p><b>角色每次回库查，不放进令牌。</b>放进去就会过期：管理员把某人降为 VIEWER
     * 之后，对方手里的令牌在剩余有效期内仍会按原角色渲染菜单与按钮 ——
     * 而项目角色恰恰是最可能被临时调整的东西。代价是一次索引命中的查询。
     *
     * <p><b>还没选项目时角色为 null</b>，权限集合为空，于是只剩不需要权限的菜单
     * （项目、异常页）。这不是退化而是刻意的：用户应当先去项目列表选一个，
     * 与 RLS 的 fail-closed 是同一个思路。
     *
     * @param jwt 已通过校验的令牌，由 Spring Security 注入
     * @return 过滤后的菜单树
     */
    @GetMapping("/menus")
    @Operation(summary = "菜单树",
            description = "返回当前用户在**当前项目**下可见的菜单树。"
                    + "服务端已按权限过滤，不下发用户无权访问的节点。"
                    + "未选择项目时返回公开菜单及按独立平台授权可见的商业运营菜单。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "成功"),
            @ApiResponse(responseCode = "401", description = "令牌无效、账号已删除或已被移出租户",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<List<MenuNodeResponse>> menus(@AuthenticationPrincipal Jwt jwt) {
        // 仍然回库复核账号状态与成员关系：令牌无法撤销，
        // 账号被停用或被移出租户只有查库才能发现
        currentUserService.resolve(JwtIdentity.accountId(jwt), JwtIdentity.tenantId(jwt));

        SelfHostedReviewAccess reviewer = reviewAccess.getIfAvailable();
        boolean canReview = reviewer != null && reviewer.isReviewer(JwtIdentity.accountId(jwt));
        return ResponseEntity.ok(menuService.menusFor(currentProjectRole(jwt),
                        commercialAccess.isOperator(JwtIdentity.accountId(jwt)), canReview).stream()
                .map(MenuNodeResponse::from)
                .toList());
    }

    /**
     * 取当前账号在当前项目中的角色。
     *
     * @param jwt 已通过校验的令牌
     * @return 项目角色；未选项目、或已不再是该项目成员时为 {@code null}
     */
    private ProjectRole currentProjectRole(Jwt jwt) {
        UUID projectId = JwtIdentity.projectId(jwt);
        if (projectId == null) {
            return null;
        }
        // 查不到 = 已被移出该项目。此时按「没有角色」处理，菜单退回未选项目的状态，
        // 而不是沿用令牌里签发时的旧信息 —— 移出项目必须立即生效
        return projectService.roleInProject(projectId).orElse(null);
    }

}
