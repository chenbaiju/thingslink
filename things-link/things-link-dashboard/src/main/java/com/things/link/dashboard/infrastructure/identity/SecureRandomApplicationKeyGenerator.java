package com.things.link.dashboard.infrastructure.identity;

import com.things.link.dashboard.application.ApplicationKeyGenerator;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.HexFormat;

/** 使用JDK密码学安全随机源生成应用公开定位符。 */
@Component
public final class SecureRandomApplicationKeyGenerator implements ApplicationKeyGenerator {

    /** ADR0096冻结的随机部分长度，16字节即128 bit。 */
    private static final int RANDOM_BYTES = 16;

    /** 进程内可复用的密码学安全随机源。 */
    private final SecureRandom secureRandom;

    /** 使用运行环境提供的默认强随机实现创建生成器。 */
    public SecureRandomApplicationKeyGenerator() {
        this(new SecureRandom());
    }

    /**
     * 创建可注入随机源的生成器，供同包测试验证精确字节编码。
     *
     * @param secureRandom 密码学安全随机源
     */
    SecureRandomApplicationKeyGenerator(SecureRandom secureRandom) {
        this.secureRandom = java.util.Objects.requireNonNull(secureRandom, "secureRandom");
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public String generate() {
        byte[] random = new byte[RANDOM_BYTES];
        secureRandom.nextBytes(random);
        return "app_" + HexFormat.of().formatHex(random);
    }
}
