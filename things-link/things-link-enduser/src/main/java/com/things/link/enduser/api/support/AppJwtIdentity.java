package com.things.link.enduser.api.support;

import com.things.link.enduser.application.AppTokenIssuer;
import com.things.link.enduser.domain.EndUserErrorCode;
import com.things.link.shared.error.BusinessException;
import org.springframework.security.oauth2.jwt.Jwt;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 从已通过校验的 App 令牌中取出身份标识。
 *
 * <p>与控制台 {@code JwtIdentity} 同一职责、第二种身份：subject 是 {@code app_user_id}
 * 而非 accountId。抽出来是为了让「subject 是终端用户 ID」「租户 ID 在哪个声明里」只有
 * 一处定义 —— 各 Controller 各写一遍的话，改声明名时漏掉一处不会编译失败，只会在那一个
 * 接口上表现为「登录了却操作不到自己的数据」。
 *
 * <p>调用前提是令牌<b>已经过签名与有效期校验</b>（由 App 安全链完成）。本类不做校验，
 * 只做解析。
 */
public final class AppJwtIdentity {

    /** @param jwt 已验签JWT @return 当前刷新族；缺失或格式错误统一401，正文不可补充 */
    public static UUID sessionId(Jwt jwt) {
        try { return UUID.fromString(jwt.getClaimAsString("sid")); }
        catch (IllegalArgumentException | NullPointerException e) { throw invalidProjectGeneration(); }
    }

    private AppJwtIdentity() {
    }

    /**
     * 取终端用户 ID（应用 JWT 主体）。
     *
     * @param jwt 已通过校验的 App 令牌
     * @return 终端用户 ID
     */
    public static UUID appUserId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    /**
     * 取租户 ID。
     *
     * <p>App 令牌是单项目令牌，登录时已由 projectKey 唯一确定，因此该声明必带。
     *
     * @param jwt 已通过校验的 App 令牌
     * @return 租户 ID
     */
    public static UUID tenantId(Jwt jwt) {
        return UUID.fromString(jwt.getClaimAsString(AppTokenIssuer.CLAIM_TENANT_ID));
    }

    /**
     * 取项目 ID。
     *
     * <p>App 令牌是单项目令牌（ADR 0036），{@code pid} 必带；它是两层授权与 RLS 的项目隔离轴。
     * 数据面端点一律从令牌取项目，不接受客户端传 projectId。
     *
     * @param jwt 已通过校验的 App 令牌
     * @return 项目 ID
     */
    public static UUID projectId(Jwt jwt) {
        return UUID.fromString(jwt.getClaimAsString(AppTokenIssuer.CLAIM_PROJECT_ID));
    }

    /**
     * 取项目生命周期代次。
     *
     * <p>ADR0073允许滚动升级期间的旧令牌缺少{@code pgv}，此时按初始代次0处理；存在的声明
     * 必须是可无损转换为非负Long的数值，避免浮点截断让失效令牌命中另一个代次。</p>
     *
     * @param jwt 已通过签名与有效期校验的App令牌
     * @return 项目生命周期代次；旧令牌缺少声明时为0
     * @throws BusinessException 声明不是可无损表达的非负整数Long时按60009拒绝身份
     */
    public static long projectGeneration(Jwt jwt) {
        Object claim = jwt.getClaim(AppTokenIssuer.CLAIM_PROJECT_GENERATION);
        if (claim == null) {
            return 0L;
        }
        if (!(claim instanceof Number number)) {
            throw invalidProjectGeneration();
        }
        try {
            // 经十进制文本做精确转换，避免Double在2^63附近先钳位Long再因浮点舍入伪装相等。
            long generation = new BigDecimal(number.toString()).longValueExact();
            if (generation < 0) {
                throw invalidProjectGeneration();
            }
            return generation;
        } catch (NumberFormatException | ArithmeticException exception) {
            throw invalidProjectGeneration();
        }
    }

    /** 非法代次属于已验签但身份声明失效，不能作为控制器内部500暴露。 */
    private static BusinessException invalidProjectGeneration() {
        return new BusinessException(EndUserErrorCode.END_USER_ACCESS_INVALID);
    }

}
