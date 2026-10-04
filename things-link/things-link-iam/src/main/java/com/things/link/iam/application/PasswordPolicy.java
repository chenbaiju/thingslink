package com.things.link.iam.application;

import com.things.link.iam.domain.IamErrorCode;
import com.things.link.shared.error.BusinessException;
import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;

/**
 * 口令强度规则。注册与重置密码共用。
 *
 * <h2>为什么抽出来</h2>
 * 两个入口各写一份的话，将来加弱口令字典时几乎必然只加在其中一个上 ——
 * 而漏掉的那个恰恰是「忘记密码」，也就是账号被接管后攻击者最可能用来设新口令的地方。
 * 与 {@link com.things.link.shared.token.OpaqueToken} 是同一个理由。
 *
 * <h2>长度与整词拒绝表，不做组合规则</h2>
 * NIST SP 800-63B 明确建议取消「必须含大小写与符号」那类规则：它们把用户推向
 * {@code Password1!} 这种可预测模式，实际熵反而更低，而长度才是真正起作用的因素。
 */
final class PasswordPolicy {

    /** ADR0213：项目维护的预期默认口令拒绝表，不声称是泄露密码全集或强度估算器。 */
    static final String BLOCKLIST_VERSION = "tc-default-passwords-v1";
    private static final Set<String> BLOCKLIST = Set.of(
            "1234567890", "12345678901", "123456789012", "0123456789", "0987654321",
            "password123", "password123!", "qwertyuiop", "qwerty12345", "admin123456",
            "administrator", "changeme123", "welcome123", "welcome123!", "letmein123456",
            "thingslink", "thingslink123", "thingslink123!");

    /** 既有长度边界保持兼容；不据此宣称完整NIST认证。 */
    static final int MIN_LENGTH = 10;

    /** 口令长度上限。防的是超长输入把 bcrypt 变成一个 CPU 消耗攻击面。 */
    static final int MAX_LENGTH = 128;

    private PasswordPolicy() {
    }

    /**
     * 校验口令强度。
     *
     * <p>放在服务端而不是只靠前端：前端校验是给用户的即时反馈，不是防线 ——
     * 直接调接口就绕过去了。
     *
     * @param password 明文口令
     * @throws BusinessException 长度不合规或命中整词拒绝表（20011）
     */
    static void assertAcceptable(String password) {
        if (password == null || password.length() < MIN_LENGTH || password.length() > MAX_LENGTH) {
            throw new BusinessException(IamErrorCode.WEAK_PASSWORD);
        }
        // 归一化仅用于拒绝表比较；调用方仍对原始口令编码，不更改登录语义。
        String comparison = Normalizer.normalize(password, Normalizer.Form.NFKC).strip().toLowerCase(Locale.ROOT);
        if (BLOCKLIST.contains(comparison)) throw new BusinessException(IamErrorCode.WEAK_PASSWORD);
    }

}
