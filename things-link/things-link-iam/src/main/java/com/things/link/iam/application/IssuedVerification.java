package com.things.link.iam.application;

import java.time.Instant;

/**
 * 一次签发出来的邮箱验证令牌。
 *
 * <p><b>不要把它放进任何 HTTP 响应体。</b>令牌的整个安全模型建立在
 * 「只有能读到那个邮箱的人才拿得到它」之上，一旦经由接口返回，任何知道邮箱的人
 * 都能直接验证甚至重置别人的账号。它只能被拼进邮件正文。
 *
 * <p>内部签发结果不得作为 HTTP 响应；当前 OpenAPI 不含本类型。
 * 修改认证接口时须继续核对该边界，不能把令牌明文暴露到响应。
 *
 * @param rawToken  令牌明文，Base64URL 编码。<b>服务端此后不再持有它</b>（库里只有 SHA-256），
 *                  这次不用就永远拿不回来了
 * @param expiresAt 过期时刻，用于在邮件里告诉用户「链接 24 小时内有效」
 */
public record IssuedVerification(String rawToken, Instant expiresAt) {
}
