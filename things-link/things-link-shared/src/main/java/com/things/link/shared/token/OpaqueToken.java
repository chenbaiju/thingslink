package com.things.link.shared.token;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 不透明令牌的生成与摘要。刷新令牌与邮箱验证令牌共用。
 *
 * <h2>为什么两处必须用同一套实现</h2>
 * 这两类令牌的安全性<b>完全来自明文本身不可猜</b> —— 没有用户名之类的第二维度
 * 可以依赖。抄一份到第二处，就意味着将来只改了一处（比如把随机源换掉、把长度调大）
 * 而另一处悄悄留在旧实现上，且不会有任何测试发现。
 *
 * <p>控制台刷新令牌（{@code sys_refresh_token}）与邮箱验证令牌在 iam，App 刷新令牌
 * （{@code app_refresh_token}）在 enduser —— 两端都只能依赖 shared，于是本类落在
 * shared（纯 JDK，符合 shared「不依赖 Spring」的约束），避免各自复制一份安全关键代码。
 *
 * <h2>为什么摘要是 SHA-256 而不是 bcrypt</h2>
 * 见迁移 {@code V20260801_1120} 的文件注释。要点：令牌是 256 位密码学随机值，
 * 没有可猜结构，bcrypt 的工作因子毫无意义；而且 bcrypt 每行带独立盐，
 * 无法由明文算出摘要去命中索引，只能全表扫描。
 */
public final class OpaqueToken {

    /**
     * 令牌明文的随机字节数。
     *
     * <p>32 字节 = 256 位，这个量级下穷举不可行。低于 16 字节就应当认为是可爆破的。
     */
    private static final int TOKEN_BYTES = 32;

    /** 必须是 {@link SecureRandom}；{@code Random} 的输出可被预测，等于令牌可被伪造。 */
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * URL 安全且不带填充。
     *
     * <p>刷新令牌进 Cookie、验证令牌进邮件里的 URL 查询串，
     * {@code +} {@code /} {@code =} 在这两个位置都需要转义 ——
     * 而转义一旦在某一环漏掉，表现是「链接点开提示无效」，排查方向还会被引到令牌逻辑上。
     */
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private OpaqueToken() {
    }

    /**
     * 生成令牌明文。
     *
     * @return Base64URL 编码的 256 位随机串
     */
    public static String generate() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return ENCODER.encodeToString(bytes);
    }

    /**
     * 计算令牌明文的 SHA-256。
     *
     * @param rawToken 明文
     * @return 32 字节摘要
     */
    public static byte[] hash(String rawToken) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(rawToken.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 规范要求必须提供的算法，走不到这里
            throw new IllegalStateException("JVM 不支持 SHA-256", e);
        }
    }

}
