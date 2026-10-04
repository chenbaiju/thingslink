package com.things.link.enduser.application;

import com.things.link.enduser.application.AppBrowserCookieBinding.Result;
import com.things.link.enduser.application.AppBrowserCookieBinding.Status;
import com.things.link.enduser.infrastructure.security.NimbusAppBrowserRefreshCookieCodec;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S12-3a设计模型：真实完整性封装与纯代次判定，配合可控Cookie到达顺序及页面代次模拟器。
 * 不执行HTTP/浏览器/数据库事务，不把模型副作用计数冒称已通过真实认证适配或WebLocks资格。
 */
class AppBrowserCookieInterleavingTests {
    /** 使用确定时钟精确区分已过期Cookie和新登录，避免sleep掩盖顺序错误。 */
    private static final Instant NOW = Instant.parse("2026-09-07T12:00:00Z");
    /** 旧登录代次，16字节规范随机标记的确定测试替身，不含账号或凭据。 */
    private static final String EPOCH_A = epoch(1);
    /** 新登录使用不同代次，不把refresh轮换误当成身份切换。 */
    private static final String EPOCH_B = epoch(2);
    /** 只用于测试的32字节密钥，不读取任何部署密钥。 */
    private static final byte[] KEY = bytes(32, 7);
    /** 被测真实Nimbus封装，固定origin提供跨宿主隔离。 */
    private final AppBrowserRefreshCookieCodec codec = new NimbusAppBrowserRefreshCookieCodec(
            "test-active", Map.of("test-active", KEY), "https://app.example.test", false);
    /** 被测真实代次比较，模型不得自己重新实现密码学校验。 */
    private final AppBrowserCookieBinding binding = new AppBrowserCookieBinding(codec);

    /** A的旧Set-Cookie晚于B到达不能让B页面刷新A；移除epoch门禁时真实decode会接受旧凭据。 */
    @Test void lateOldLoginCookieCannotRollNewPageBackToOldIdentity() {
        Model model = new Model();
        model.login(EPOCH_A, cookie(EPOCH_A, "refresh-a", NOW.plusSeconds(600)));
        String delayedA = model.cookie;
        model.login(EPOCH_B, cookie(EPOCH_B, "refresh-b", NOW.plusSeconds(600)));
        model.receiveCookie(delayedA);

        Result guarded = model.refresh(model.pageEpoch);

        assertThat(guarded.status()).isEqualTo(Status.EPOCH_MISMATCH);
        assertThat(guarded.cookie()).isNull();
        model.assertNoSideEffects();
        assertThat(model.pageEpoch).isEqualTo(EPOCH_B);
        // 可执行错误路径：只验封装/到期而不验期望epoch，会把A的有效raw交给轮换服务。
        var unsafe = codec.decode(model.cookie);
        assertThat(NOW.isBefore(unsafe.expiresAt())).isTrue();
        assertThat(unsafe.refreshToken()).isEqualTo(raw("refresh-a"));
        assertThat(unsafe.browserEpoch()).isNotEqualTo(model.pageEpoch);
    }

    /** 正常同族刷新保留browserEpoch，仅替换真实内部refresh值，不新建身份代次。 */
    @Test void normalRotationRetainsEpochAndReplacesOnlyRefreshCredential() {
        Model model = new Model();
        model.login(EPOCH_A, cookie(EPOCH_A, "refresh-a", NOW.plusSeconds(600)));
        assertThat(model.refresh(EPOCH_A).status()).isEqualTo(Status.VALID);
        assertThat(model.rotations).isEqualTo(1);
        assertThat(model.revocations).isZero();
        assertThat(model.clears).isZero();
        assertThat(codec.decode(model.cookie).browserEpoch()).isEqualTo(EPOCH_A);
        assertThat(codec.decode(model.cookie).refreshToken()).isEqualTo(raw("rotated-1"));
        assertThat(model.pageEpoch).isEqualTo(EPOCH_A);
    }

    /** logout发出前捕获A，新登录B的Cookie已经到达时旧退出不能撤销或清除B。 */
    @Test void capturedOldLogoutEpochCannotRevokeOrClearNewCookie() {
        Model model = new Model();
        model.login(EPOCH_A, cookie(EPOCH_A, "refresh-a", NOW.plusSeconds(600)));
        String captured = model.pageEpoch;
        model.logoutLocally();
        model.login(EPOCH_B, cookie(EPOCH_B, "refresh-b", NOW.plusSeconds(600)));

        Result result = model.logoutRequest(captured);

        assertThat(result.status()).isEqualTo(Status.EPOCH_MISMATCH);
        assertThat(result.cookie()).isNull();
        model.assertNoSideEffects();
        assertThat(codec.decode(model.cookie).refreshToken()).isEqualTo(raw("refresh-b"));
        assertThat(model.pageEpoch).isEqualTo(EPOCH_B);
    }

    /** 必须先检查代次再检查过期，否则旧A过期会错误触发B页面清Cookie副作用。 */
    @Test void oldExpiredCookieIsMismatchBeforeExpirationAndHasNoSideEffects() {
        Model model = new Model();
        model.login(EPOCH_B, cookie(EPOCH_B, "refresh-b", NOW.plusSeconds(600)));
        model.receiveCookie(cookie(EPOCH_A, "expired-a", NOW.minusSeconds(1)));

        Result result = model.refresh(EPOCH_B);

        assertThat(result.status()).isEqualTo(Status.EPOCH_MISMATCH);
        assertThat(result.cookie()).isNull();
        model.assertNoSideEffects();
    }

    /** 同epoch的过期事实可安全触发该请求清Cookie意图，但不再轮换或假造数据库族撤销。 */
    @Test void matchingExpiredCookieMayBeClearedWithoutRotation() {
        Model model = new Model();
        model.login(EPOCH_A, cookie(EPOCH_A, "expired-a", NOW.minusSeconds(1)));
        Result result = model.refresh(EPOCH_A);
        assertThat(result.status()).isEqualTo(Status.EXPIRED);
        assertThat(result.cookie().browserEpoch()).isEqualTo(EPOCH_A);
        assertThat(model.clears).isEqualTo(1);
        assertThat(model.cookie).isNull();
        assertThat(model.rotations).isZero();
        assertThat(model.revocations).isZero();
    }

    /** 坏封装不产生轮换/撤销/删除；同名重复Cookie的HTTP解析仍属于后续适配片验收。 */
    @Test void invalidEnvelopeAndMissingEpochNeverProduceSessionSideEffects() {
        Model model = new Model();
        model.login(EPOCH_B, "not-an-authenticated-envelope");
        Result malformed = model.refresh(EPOCH_B);
        assertThat(malformed.status()).isEqualTo(Status.INVALID_COOKIE);
        assertThat(malformed.cookie()).isNull();
        Result missingEpoch = model.refresh(null);
        assertThat(missingEpoch.status()).isEqualTo(Status.INVALID_EPOCH);
        assertThat(missingEpoch.cookie()).isNull();
        model.assertNoSideEffects();
    }

    /** 退出先使本地页面轮次失效；已经发出的login可改浏览器Cookie，但其迟到响应不能复活页面。 */
    @Test void localLogoutInvalidatesPendingLoginResponseWithoutPretendingToUndoSetCookie() {
        Model model = new Model();
        long attempt = model.beginLogin(EPOCH_A);
        model.logoutLocally();
        model.receiveCookie(cookie(EPOCH_A, "late-success", NOW.plusSeconds(600)));

        assertThat(model.acceptLoginResult(attempt, EPOCH_A)).isFalse();
        assertThat(model.pageEpoch).isNull();
        assertThat(codec.decode(model.cookie).browserEpoch()).isEqualTo(EPOCH_A);
        assertThat(model.refresh(model.pageEpoch).status()).isEqualTo(Status.INVALID_EPOCH);
        model.assertNoSideEffects();
    }

    /** 静态Cookie删除响应无法CAS：有效旧logout晚到仍删B，只能失败为MISSING，绝不回退A身份。 */
    @Test void lateValidLogoutDeletionCanRemoveNewCookieButCannotRestoreOldIdentity() {
        Model model = new Model();
        model.login(EPOCH_A, cookie(EPOCH_A, "refresh-a", NOW.plusSeconds(600)));
        assertThat(model.logoutRequest(EPOCH_A).status()).isEqualTo(Status.VALID);
        assertThat(model.revocations).isEqualTo(1);
        // A的服务端退出成功已产生删除指令，但浏览器尚未收到响应；随后B登录完成。
        model.login(EPOCH_B, cookie(EPOCH_B, "refresh-b", NOW.plusSeconds(600)));
        model.receiveClearCookie();
        Result result = model.refresh(EPOCH_B);
        assertThat(result.status()).isEqualTo(Status.MISSING);
        assertThat(result.cookie()).isNull();
        assertThat(model.cookie).isNull();
        assertThat(model.pageEpoch).isEqualTo(EPOCH_B);
        assertThat(model.rotations).isZero();
        assertThat(model.revocations).isEqualTo(1);
        assertThat(model.clears).isEqualTo(1);
    }

    /** 真实密码学封装，不自行拼装已验证对象模拟有效Cookie。 */
    private String cookie(String epoch, String raw, Instant expiry) { return codec.encode(epoch, raw(raw), expiry); }

    /** 生产refresh为规范32字节Base64URL；标签仅用于生成不同的确定测试凭据。 */
    private static String raw(String label) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes(32, label.hashCode()));
    }

    /** 规范16字节标记，仅模拟不同随机值，不模拟真实浏览器随机源质量。 */
    private static String epoch(int value) {
        return "be_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes(16, value));
    }

    /** 固定测试字节不作为生产密钥或浏览器代次来源。 */
    private static byte[] bytes(int length, int value) {
        byte[] bytes = new byte[length]; Arrays.fill(bytes, (byte) value); return bytes;
    }

    /** 最小交错模型只记录副作用意图，不实现数据库认证、Cookie属性或浏览器锁。 */
    private final class Model {
        /** 浏览器同名静态Cookie，服务器响应到达顺序可以覆盖或删除它。 */
        private String cookie;
        /** 页面当前非凭据身份代次；本地退出立即清空。 */
        private String pageEpoch;
        /** 页面在途操作栅栏，迟到响应只能被丢弃。 */
        private long generation;
        /** 模拟认证服务被调用轮换的次数。 */
        private int rotations;
        /** 模拟族撤销副作用的次数。 */
        private int revocations;
        /** 模拟浏览器实际执行静态Cookie删除的次数。 */
        private int clears;

        /** 开始登录前发布新代次；返回请求捕获的本地轮次。 */
        private long beginLogin(String epoch) { pageEpoch = epoch; return ++generation; }
        /** 只有当前请求轮次及非凭据代次一致时才可恢复业务页面。 */
        private boolean acceptLoginResult(long attempt, String epoch) {
            return generation == attempt && epoch.equals(pageEpoch);
        }
        /** 顺序登录的快捷夹具；晚响应测试自行拆开到达顺序。 */
        private void login(String epoch, String envelope) {
            long attempt = beginLogin(epoch); receiveCookie(envelope);
            assertThat(acceptLoginResult(attempt, epoch)).isTrue();
        }
        /** JavaScript无法阻止浏览器处理已收到的Set-Cookie，模型不伪造这种能力。 */
        private void receiveCookie(String envelope) { cookie = envelope; }
        /** 静态名称删除没有epoch比较，晚到响应同样生效。 */
        private void receiveClearCookie() { cookie = null; clears++; }
        /** 本地退出不声称已取消网络请求或服务端事务。 */
        private void logoutLocally() { generation++; pageEpoch = null; }
        /** 完整绑定结果之后才允许轮换；过期仅能对匹配代次表达清理意图。 */
        private Result refresh(String expectedEpoch) {
            Result result = binding.verify(expectedEpoch, cookie, NOW);
            if (result.status() == Status.VALID) {
                rotations++;
                cookie = codec.encode(result.cookie().browserEpoch(), raw("rotated-" + rotations), NOW.plusSeconds(600));
            } else if (result.status() == Status.EXPIRED) receiveClearCookie();
            return result;
        }
        /** 匹配的退出可以产生撤销及稍后清Cookie的响应，不在此伪装响应已送达。 */
        private Result logoutRequest(String expectedEpoch) {
            Result result = binding.verify(expectedEpoch, cookie, NOW);
            if (result.status() == Status.VALID) revocations++;
            return result;
        }
        /** 拒绝分支必须保持三种会话副作用计数均为零。 */
        private void assertNoSideEffects() {
            assertThat(rotations).isZero(); assertThat(revocations).isZero(); assertThat(clears).isZero();
        }
    }
}
