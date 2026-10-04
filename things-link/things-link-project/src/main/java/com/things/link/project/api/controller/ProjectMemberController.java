package com.things.link.project.api.controller;

import com.things.link.project.api.dto.request.InviteMemberRequest;
import com.things.link.project.api.dto.request.UpdateMemberRoleRequest;
import com.things.link.project.api.dto.response.ProjectMemberResponse;
import com.things.link.project.application.ProjectMemberService;
import com.things.link.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 项目成员管理接口（S1 切片 5d）。
 *
 * <h2>项目 ID 走路径，不取令牌里的「当前项目」</h2>
 * 令牌里确实有 {@code pid}，但用它会让「管理项目 B 的成员」必须先切到项目 B ——
 * 而切换项目会整页重载，等于每次改一个成员都要重来一遍。
 *
 * <p>这样做<b>不会放松任何约束</b>：授权判定是
 * {@code findRole(路径里的 projectId, 当前账号)}，与令牌里写的是什么无关。
 * 令牌只证明「你是谁」，能不能进这个项目由数据库里的成员关系当场回答。
 * 反过来说，若把 pid 当成隐含参数，反倒容易让人误以为它起到了隔离作用。
 *
 * <h2>非成员一律 404</h2>
 * 不是 403。403 等于确认「这个项目存在，只是你进不去」，攻击者拿一份 UUID 逐个试
 * 就能枚举出平台上有哪些项目。判定顺序也因此固定：先查是不是成员，再查角色够不够。
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/members")
@Tag(name = "项目成员", description = "项目成员的查看、邀请、改角色与移除")
public class ProjectMemberController {

    private final ProjectMemberService memberService;

    public ProjectMemberController(ProjectMemberService memberService) {
        this.memberService = memberService;
    }

    /**
     * 列出项目成员。四个角色都能看。
     *
     * <p>暂不分页，理由与项目列表相同：单个项目的成员是个位数到几十的量级。
     * 范围条件见 ARCHITECTURE_GAPS.md GAP-TODO-08： 若真要加，按开发手册的约定用游标分页。
     *
     * @param projectId 项目 ID
     * @return 成员列表，按加入时间正序
     */
    @GetMapping
    @Operation(summary = "成员列表",
            description = "列出该项目的全部成员及其角色。项目内任何角色都可查看。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "成功"),
            @ApiResponse(responseCode = "401", description = "未认证",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "项目不存在，或你不是它的成员",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<List<ProjectMemberResponse>> list(
            @Parameter(description = "项目 ID") @PathVariable UUID projectId) {

        return ResponseEntity.ok(memberService.list(projectId).stream()
                .map(ProjectMemberResponse::from)
                .toList());
    }

    /**
     * 按邮箱邀请一个已注册账号加入项目。仅 OWNER / ADMIN。
     *
     * <p>目前是<b>直接加入</b>，没有「待接受」状态 —— 被邀请人下次刷新项目列表
     * 就能看到。邀请确认合同待冻结，见 ARCHITECTURE_GAPS.md GAP-TODO-06。
     *
     * @param projectId 项目 ID
     * @param request   邮箱与角色
     * @return 新成员
     */
    @PostMapping
    @Operation(summary = "邀请成员",
            description = "按邮箱把一个**已注册**账号加入项目并分配角色。"
                    + "仅项目 OWNER 与 ADMIN 可调用。角色不能是 OWNER。"
                    + "被邀请人立即成为成员，无需确认。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "邀请成功"),
            @ApiResponse(responseCode = "400", description = "参数不合法，或试图分配 OWNER，或邀请自己",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "403", description = "当前角色无权管理成员",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "项目不存在、你不是成员，或该邮箱尚未注册",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "503", description = "所属租户套餐额度暂不可用（50048）",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "409", description = "已是成员（50011）、外部席位已满（50049）或目标账号外部项目已达50个（50050）",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<ProjectMemberResponse> invite(
            @Parameter(description = "项目 ID") @PathVariable UUID projectId,
            @Valid @RequestBody InviteMemberRequest request) {

        return ResponseEntity.ok(ProjectMemberResponse.from(
                memberService.invite(projectId, request.email(), request.role())));
    }

    /**
     * 修改成员角色。仅 OWNER / ADMIN。
     *
     * <p>用 PATCH 而不是 PUT：请求体只带 {@code role} 一个字段，是局部更新。
     * 用 PUT 会隐含「整条成员记录被这个请求体替换」，而加入时刻、成员 ID
     * 显然不该由调用方决定。
     *
     * @param projectId 项目 ID
     * @param accountId 目标账号 ID
     * @param request   新角色
     * @return 204
     */
    @PatchMapping("/{accountId}")
    @Operation(summary = "修改成员角色",
            description = "仅项目 OWNER 与 ADMIN 可调用。"
                    + "不能改 OWNER 的角色，也不能把成员改为 OWNER，更不能改自己。")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "修改成功"),
            @ApiResponse(responseCode = "400", description = "参数不合法，或试图设为 OWNER，或目标是自己",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "403", description = "无权管理成员，或目标是项目所有者",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "项目不存在、你不是成员，或目标不是成员",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<Void> updateRole(
            @Parameter(description = "项目 ID") @PathVariable UUID projectId,
            @Parameter(description = "目标账号 ID") @PathVariable UUID accountId,
            @Valid @RequestBody UpdateMemberRoleRequest request) {

        memberService.updateRole(projectId, accountId, request.role());
        return ResponseEntity.noContent().build();
    }

    /**
     * 转让项目所有权。仅当前 OWNER 可调用。
     *
     * <p>转让后目标账号成为 OWNER，原 OWNER 降为 ADMIN。这个动作风险高于普通改角色，
     * 因此不复用 {@link #updateRole(UUID, UUID, UpdateMemberRoleRequest)}。
     *
     * @param projectId 项目 ID
     * @param accountId 新 OWNER 账号 ID
     * @return 204
     */
    @PostMapping("/{accountId}/transfer-owner")
    @Operation(summary = "转让项目所有权",
            description = "仅当前项目 OWNER 可调用。目标必须是本项目内的其他成员；"
                    + "转让后目标成为 OWNER，原 OWNER 自动降为 ADMIN。")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "转让成功"),
            @ApiResponse(responseCode = "400", description = "目标是自己",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "403", description = "当前账号不是项目所有者",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "项目不存在、你不是成员，或目标不是成员",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<Void> transferOwnership(
            @Parameter(description = "项目 ID") @PathVariable UUID projectId,
            @Parameter(description = "新 OWNER 账号 ID") @PathVariable UUID accountId) {

        memberService.transferOwnership(projectId, accountId);
        return ResponseEntity.noContent().build();
    }

    /**
     * 当前账号主动退出项目。
     *
     * <p>非 OWNER 成员都可以退出。OWNER 不能退出，因为项目必须始终保留一个所有者；
     * 需要先转让所有权，或在项目只剩自己时删除项目。
     *
     * @param projectId 项目 ID
     * @return 204
     */
    @DeleteMapping("/me")
    @Operation(summary = "退出项目",
            description = "当前登录账号主动退出项目，只解除自己的项目成员绑定。"
                    + "OWNER 不能退出，需要先转让所有权或删除项目。")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "退出成功"),
            @ApiResponse(responseCode = "400", description = "项目所有者不能退出项目",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "项目不存在，或你不是它的成员",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<Void> leave(
            @Parameter(description = "项目 ID") @PathVariable UUID projectId) {

        memberService.leave(projectId);
        return ResponseEntity.noContent().build();
    }

    /**
     * 把某个成员移出项目。仅 OWNER / ADMIN。
     *
     * <p><b>只解除关联，账号本身不受任何影响</b>：他还能登录，还能看到自己参与的
     * 其他项目，只是这个项目从他的列表里消失。
     *
     * @param projectId 项目 ID
     * @param accountId 目标账号 ID
     * @return 204
     */
    @DeleteMapping("/{accountId}")
    @Operation(summary = "移除成员",
            description = "把该账号移出项目。**只解除与项目的关联，账号本身不会被删除**。"
                    + "仅项目 OWNER 与 ADMIN 可调用；不能移除 OWNER，也不能移除自己。")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "移除成功"),
            @ApiResponse(responseCode = "400", description = "目标是自己",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "403", description = "无权管理成员，或目标是项目所有者",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "项目不存在、你不是成员，或目标不是成员",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<Void> remove(
            @Parameter(description = "项目 ID") @PathVariable UUID projectId,
            @Parameter(description = "目标账号 ID") @PathVariable UUID accountId) {

        memberService.remove(projectId, accountId);
        return ResponseEntity.noContent().build();
    }

}
