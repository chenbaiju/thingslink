package com.things.link.iam.api.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 邮箱验证结果。
 *
 * <p>只回一个邮箱地址，让结果页能显示「xxx@example.com 已验证」而不是干巴巴的
 * 「验证成功」——后者在用户有多个邮箱时说明不了任何事。
 *
 * <p>能拿到这个响应的人必然持有邮件里的令牌，也就必然能读到该邮箱，
 * 因此回显它不构成信息泄露。
 *
 * @param email 已被证实的邮箱地址
 */
@Schema(description = "邮箱验证结果")
public record VerifyEmailResponse(

        @Schema(description = "已验证的邮箱", example = "owner@example.com")
        String email) {
}
