package com.things.link.iam.api.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 重置密码请求。
 *
 * <p>令牌走请求体而不是查询参数。这条在验证邮箱那里已经是原则，在这里更要紧：
 * 查询串会进服务端访问日志、反向代理日志与浏览器历史，而这个令牌能<b>直接改掉
 * 账号口令</b>。
 *
 * @param token       令牌明文，来自邮件链接的 {@code token} 查询参数
 * @param newPassword 新口令明文
 */
@Schema(description = "重置密码请求")
public record ResetPasswordRequest(

        @Schema(description = "邮件链接中的重置令牌")
        @NotBlank(message = "重置令牌不能为空")
        @Size(max = 128, message = "重置令牌格式不正确")
        String token,

        /*
         * 长度下限同时写在这里和 PasswordPolicy 里，看似重复，其实职责不同：
         * 这里是**参数校验**，能一次性返回逐项明细（10001）；服务层那道是**不变式**，
         * 保证任何调用路径都绕不过去。与 RegisterRequest 是同一套安排。
         */
        @Schema(description = "新口令，10-128 个字符", minLength = 10, maxLength = 128)
        @NotBlank(message = "新口令不能为空")
        @Size(min = 10, max = 128, message = "口令长度需在 10 到 128 个字符之间")
        String newPassword) {

    /**
     * 屏蔽令牌与明文口令：record 默认的 toString 会把两者都打进日志，
     * 而这个请求体里的每一个字段都足以接管账号。
     */
    @Override
    public String toString() {
        return "ResetPasswordRequest[token=***, newPassword=***]";
    }

}
