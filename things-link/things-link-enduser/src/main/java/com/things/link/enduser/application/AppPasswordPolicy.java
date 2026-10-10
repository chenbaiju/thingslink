package com.things.link.enduser.application;

import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import java.nio.charset.StandardCharsets;

/** 新口令沿现有编码器真实可接受边界校验，不改变存量口令匹配或截断口令。 */
public final class AppPasswordPolicy {
    private AppPasswordPolicy() { }
    /**
     * 预置与修改采用同一写入边界。
     * @param password 新口令，只用于内存校验，不记录或持久化明文
     * @throws BusinessException 字符长度、空白或字节长度不符合要求
     */
    public static void validate(String password) {
        if (password == null || password.isBlank() || password.length() < 8 || password.length() > 128
                || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER,
                    "新口令需为8至128字符，UTF-8编码不超过72字节");
        }
    }
}
