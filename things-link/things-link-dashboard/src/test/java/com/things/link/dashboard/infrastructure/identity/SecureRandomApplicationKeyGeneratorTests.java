package com.things.link.dashboard.infrastructure.identity;

import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/** 密码学随机应用定位符的字节长度、前缀及十六进制编码单测。 */
class SecureRandomApplicationKeyGeneratorTests {

    /** 16个随机字节必须逐字节编码为32位小写十六进制，不能退化为UUID或有符号整数。 */
    @Test
    void encodesExactlyOneHundredTwentyEightRandomBits() {
        SecureRandom random = mock(SecureRandom.class);
        AtomicInteger next = new AtomicInteger();
        doAnswer(invocation -> {
            byte[] target = invocation.getArgument(0);
            for (int index = 0; index < target.length; index++) {
                target[index] = (byte) next.getAndIncrement();
            }
            return null;
        }).when(random).nextBytes(org.mockito.ArgumentMatchers.any(byte[].class));

        SecureRandomApplicationKeyGenerator generator = new SecureRandomApplicationKeyGenerator(random);

        assertThat(generator.generate()).isEqualTo("app_000102030405060708090a0b0c0d0e0f");
    }
}
