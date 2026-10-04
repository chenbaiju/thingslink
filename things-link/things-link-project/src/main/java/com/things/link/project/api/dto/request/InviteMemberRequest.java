package com.things.link.project.api.dto.request;

import com.things.link.shared.authz.ProjectRole;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 邀请成员请求。
 *
 * <p>按<b>邮箱</b>而不是 accountId 邀请：邀请人知道的是同事的邮箱，
 * 不可能知道对方的 UUID。要求前端先「按邮箱搜账号」再提交 ID，等于多加一个
 * 能被用来批量枚举账号的接口。
 *
 * @param email 被邀请人的注册邮箱，大小写不敏感
 * @param role  分配的角色。<b>不能是 OWNER</b>，每个项目只有一个所有者，
 *              由创建项目产生（服务端会拒绝，返回 50012）
 */
@Schema(description = "邀请成员请求")
public record InviteMemberRequest(

        @Schema(description = "被邀请人的注册邮箱", example = "teammate@example.com")
        @NotBlank(message = "邮箱不能为空")
        @Email(message = "邮箱格式不正确")
        @Size(max = 254, message = "邮箱过长")
        String email,

        /*
         * 直接用枚举而不是 String：非法值在绑定阶段就被挡住，服务层不必再解析一次。
         *
         * 代价是错误码不同 —— 传 "SUPERUSER" 这种不存在的值，Jackson 抛的是反序列化
         * 异常，映射成 10002「请求体格式错误」而不是 10001「参数不合法」。
         * 对控制台来说这个区别无所谓（角色是下拉框选的，选不出非法值），
         * 而类型安全带来的收益是实打实的。
         */
        @Schema(description = "分配的角色", example = "OPERATOR",
                allowableValues = {"ADMIN", "OPERATOR", "VIEWER"})
        @NotNull(message = "角色不能为空")
        ProjectRole role) {

    /**
     * 规范化邮箱输入。
     *
     * <p>这一步放在 DTO 而不是只放在 service：Bean Validation 会先校验 {@link Email}，
     * 如果原始 JSON 里带了前后空格，service 还没机会 trim 就已经返回 400。
     */
    public InviteMemberRequest {
        if (email != null) {
            email = email.trim();
        }
    }
}
