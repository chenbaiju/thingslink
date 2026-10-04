package com.things.link.iam.application;

import com.things.link.iam.domain.AccountRepository;
import com.things.link.iam.domain.EmailVerificationPurpose;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.id.Uuid7;
import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 邮箱验证令牌的签发与消费（S4 切片 2，ADR 0013）。
 *
 * <p>这里测的核心是<b>一次性语义</b>与<b>明文不落库</b>。两者失守的后果都不会在
 * 功能测试里表现出来：链接能用、验证成功、页面正常 —— 只是同一个链接可以用两次，
 * 或者数据库被读走后攻击者拿到的是一堆可直接使用的重置链接。
 */
@DisplayName("邮箱验证令牌（S4 切片 2）")
class EmailVerificationTests extends AbstractIntegrationTest {

    @Autowired
    private EmailVerificationService emailVerificationService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void reset() {
        // 容器在模块内所有测试类之间共享（见 AbstractIntegrationTest），
        // 别的类留下的数据会干扰这里的全表断言
        jdbcTemplate.update("DELETE FROM sys_email_verification_token");
        jdbcTemplate.update("DELETE FROM sys_project_member");
        jdbcTemplate.update("DELETE FROM sys_project");
        jdbcTemplate.update("DELETE FROM sys_refresh_token");
        jdbcTemplate.update("DELETE FROM sys_tenant_member");
        jdbcTemplate.update("DELETE FROM sys_account");
    }

    @Test
    @DisplayName("签发的令牌能验证邮箱，并记下验证时刻")
    void verifiesEmail() {
        UUID accountId = createAccount("user@example.com");

        IssuedVerification issued = emailVerificationService.issue(
                accountId, "user@example.com", EmailVerificationPurpose.REGISTER_VERIFY, "10.0.0.1");
        String verified = emailVerificationService.verifyRegistration(issued.rawToken());

        assertThat(verified).isEqualTo("user@example.com");
        assertThat(accountRepository.findById(accountId).orElseThrow().isEmailVerified()).isTrue();
    }

    /**
     * 一次性语义。邮件客户端的链接预取、用户手抖双击都会造成同一个链接被点两次，
     * 这在真实环境里是常态而不是意外。
     */
    @Test
    @DisplayName("同一个令牌不能用第二次")
    void tokenIsSingleUse() {
        UUID accountId = createAccount("user@example.com");
        IssuedVerification issued = emailVerificationService.issue(
                accountId, "user@example.com", EmailVerificationPurpose.REGISTER_VERIFY, null);

        emailVerificationService.verifyRegistration(issued.rawToken());

        assertThatThrownBy(() -> emailVerificationService.verifyRegistration(issued.rawToken()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("验证链接无效或已过期");
    }

    /**
     * 并发消费同一个令牌，必须只有一个成功。
     *
     * <p>这条是「一次性由数据库仲裁」这个设计的实际证明。改成「先查是否可用、
     * 再 UPDATE」的话，上面那条串行测试照样通过，只有这一条会失败 ——
     * 而生产环境里失败的方式是同一个重置链接被用了两次。
     */
    @Test
    @DisplayName("并发消费同一个令牌只有一次成功")
    void concurrentConsumeAllowsOnlyOne() throws Exception {
        UUID accountId = createAccount("user@example.com");
        IssuedVerification issued = emailVerificationService.issue(
                accountId, "user@example.com", EmailVerificationPurpose.REGISTER_VERIFY, null);

        Callable<Boolean> attempt = () -> {
            try {
                emailVerificationService.verifyRegistration(issued.rawToken());
                return true;
            } catch (BusinessException e) {
                return false;
            }
        };

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Boolean>> results = pool.invokeAll(List.of(attempt, attempt));
            long succeeded = results.stream().filter(f -> {
                try {
                    return f.get();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }).count();

            assertThat(succeeded)
                    .as("一次性语义必须由数据库仲裁，不能靠应用层先查后写")
                    .isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * 用途隔离。不校验的话，一封「验证你的邮箱」的链接就能拿去重置密码 ——
     * 而那封信的有效期是 24 小时、还可能被用户转发过。
     */
    @Test
    @DisplayName("其他用途的令牌不能用于注册验证")
    void rejectsTokenIssuedForAnotherPurpose() {
        UUID accountId = createAccount("user@example.com");
        IssuedVerification issued = emailVerificationService.issue(
                accountId, "user@example.com", EmailVerificationPurpose.RESET_PASSWORD, null);

        assertThatThrownBy(() -> emailVerificationService.verifyRegistration(issued.rawToken()))
                .isInstanceOf(BusinessException.class);
        assertThat(accountRepository.findById(accountId).orElseThrow().isEmailVerified()).isFalse();
    }

    @Test
    @DisplayName("过期令牌不能使用")
    void rejectsExpiredToken() {
        UUID accountId = createAccount("user@example.com");
        IssuedVerification issued = emailVerificationService.issue(
                accountId, "user@example.com", EmailVerificationPurpose.REGISTER_VERIFY, null);

        // 不能靠 Thread.sleep 等 24 小时，直接把过期时刻拨到过去
        jdbcTemplate.update("UPDATE sys_email_verification_token SET expires_at = ?",
                Timestamp.from(Instant.now().minus(1, ChronoUnit.MINUTES)));

        assertThatThrownBy(() -> emailVerificationService.verifyRegistration(issued.rawToken()))
                .isInstanceOf(BusinessException.class);
        assertThat(accountRepository.findById(accountId).orElseThrow().isEmailVerified()).isFalse();
    }

    @Test
    @DisplayName("空令牌与伪造令牌都报同一个错，不泄露令牌是否存在")
    void rejectsMissingAndForgedTokens() {
        assertThatThrownBy(() -> emailVerificationService.verifyRegistration(null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("验证链接无效或已过期");
        assertThatThrownBy(() -> emailVerificationService.verifyRegistration("not-a-real-token"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("验证链接无效或已过期");
    }

    /**
     * 明文不得落库。库被读走时，这决定了攻击者拿到的是一堆无用的摘要，
     * 还是一堆可以直接点开的重置链接。
     */
    @Test
    @DisplayName("库里只有 SHA-256，任何列都不含令牌明文")
    void storesOnlyHash() {
        UUID accountId = createAccount("user@example.com");
        IssuedVerification issued = emailVerificationService.issue(
                accountId, "user@example.com", EmailVerificationPurpose.REGISTER_VERIFY, null);

        byte[] storedHash = jdbcTemplate.queryForObject(
                "SELECT token_hash FROM sys_email_verification_token", byte[].class);
        assertThat(storedHash).isEqualTo(sha256(issued.rawToken()));

        // 把整行转成文本再找明文：漏掉某一列的可能性比写全每一列的断言小
        String wholeRow = jdbcTemplate.queryForObject(
                "SELECT sys_email_verification_token::text FROM sys_email_verification_token", String.class);
        assertThat(wholeRow).doesNotContain(issued.rawToken());
    }

    @Test
    @DisplayName("目标邮箱按小写归一化存储")
    void normalizesEmail() {
        UUID accountId = createAccount("user@example.com");
        emailVerificationService.issue(
                accountId, "  User@Example.COM  ", EmailVerificationPurpose.REGISTER_VERIFY, null);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT email FROM sys_email_verification_token", String.class))
                .isEqualTo("user@example.com");
    }

    /**
     * 令牌签发后账号邮箱又被改掉：旧链接不该把新邮箱标记为已验证。
     * 否则「申请改成 A、又改申请成 B、点了 A 的链接」会把 B 证实成 A 的验证结果。
     */
    @Test
    @DisplayName("账号邮箱在等待期间变更后，旧令牌不再标记验证")
    void doesNotVerifyAfterEmailChanged() {
        UUID accountId = createAccount("old@example.com");
        IssuedVerification issued = emailVerificationService.issue(
                accountId, "old@example.com", EmailVerificationPurpose.REGISTER_VERIFY, null);

        jdbcTemplate.update("UPDATE sys_account SET email = ? WHERE id = ?", "new@example.com", accountId);

        // 令牌本身是有效的，所以不抛异常 —— 它确实被消费掉了
        emailVerificationService.verifyRegistration(issued.rawToken());

        assertThat(accountRepository.findById(accountId).orElseThrow().isEmailVerified())
                .as("旧链接证实的是旧地址，不能因此认定新地址已验证")
                .isFalse();
    }

    /**
     * 重复验证不刷新时刻。刷新了的话，事后排查「这个邮箱是什么时候验证的」
     * 会得到最后一次点击的时间，而不是真正证实它的那一次。
     */
    @Test
    @DisplayName("第二个令牌不会刷新已有的验证时刻")
    void keepsFirstVerificationTimestamp() {
        UUID accountId = createAccount("user@example.com");
        IssuedVerification first = emailVerificationService.issue(
                accountId, "user@example.com", EmailVerificationPurpose.REGISTER_VERIFY, null);
        IssuedVerification second = emailVerificationService.issue(
                accountId, "user@example.com", EmailVerificationPurpose.REGISTER_VERIFY, null);

        emailVerificationService.verifyRegistration(first.rawToken());
        Instant firstVerifiedAt = accountRepository.findById(accountId).orElseThrow().emailVerifiedAt();

        emailVerificationService.verifyRegistration(second.rawToken());

        assertThat(accountRepository.findById(accountId).orElseThrow().emailVerifiedAt())
                .isEqualTo(firstVerifiedAt);
    }

    private UUID createAccount(String email) {
        UUID id = Uuid7.generate();
        jdbcTemplate.update("""
                INSERT INTO sys_account (id, email, password_hash, display_name)
                VALUES (?, ?, '{bcrypt}$2a$10$notarealhash', 'Tester')
                """, id, email);
        return id;
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

}
