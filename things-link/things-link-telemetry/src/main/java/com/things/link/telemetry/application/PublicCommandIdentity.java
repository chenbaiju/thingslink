package com.things.link.telemetry.application;
import com.things.link.shared.error.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
/** ADR0173公开业务键的唯一规范化与摘要实现，不持久明文。 */
public final class PublicCommandIdentity {
    private PublicCommandIdentity(){}
    public static String digest(String value){
        if(value==null||value.isBlank()||value.strip().length()>128)throw new BusinessException(CommonErrorCode.INVALID_PARAMETER);
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.strip().getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    public static String internalKey(UUID keyId,String clientKey){return "open:"+Objects.requireNonNull(keyId)+":"+digest(clientKey);}
}
