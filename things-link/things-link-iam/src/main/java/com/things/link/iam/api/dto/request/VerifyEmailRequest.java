package com.things.link.iam.api.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 邮箱验证请求。
 *
 * <p><b>令牌走请求体而不是查询参数</b>，尽管邮件里的链接是带 query 的。控制台页面
 * 从地址栏取到令牌后，用 POST 提交给后端。这样做是因为查询串会被写进服务端访问日志、
 * 反向代理日志与浏览器历史，而这个令牌能改变账号状态 —— 同一个理由在「重置密码」
 * 上更要命。
 *
 * @param token 令牌明文，来自邮件链接的 {@code token} 查询参数
 */
@Schema(description = "邮箱验证请求")
public record VerifyEmailRequest(

        @Schema(description = "邮件链接中的验证令牌")
        @NotBlank(message = "验证令牌不能为空")
        @Size(max = 128, message = "验证令牌格式不正确")
        String token) {

    /**
     * 屏蔽令牌：record 默认的 toString 会把它打进日志，
     * 而日志的可见范围比收件人宽得多。
     */
    @Override
    public String toString() {
        return "VerifyEmailRequest[token=***]";
    }

}
