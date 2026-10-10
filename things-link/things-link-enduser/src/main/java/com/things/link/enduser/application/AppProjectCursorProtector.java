package com.things.link.enduser.application;

import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/** 加密扫描游标，避免公开无权项目的扫描锚点；内部仍沿公共签名游标校验用途和身份。 */
@Component
public final class AppProjectCursorProtector {
    private final SecretKeySpec key;
    private final SecureRandom random=new SecureRandom();
    /** @param secret 既有服务端签名密钥，仅以独立用途派生游标加密密钥 */
    public AppProjectCursorProtector(@Value("${things-link.security.jwt.secret}") String secret) {
        try {
            var mac=Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
            key=new SecretKeySpec(mac.doFinal("things-link/app-project-cursor/v1".getBytes(StandardCharsets.UTF_8)),"AES");
        }catch(GeneralSecurityException e){throw new IllegalStateException("项目游标密钥初始化失败",e);}
    }
    /** @param value 内部签名游标 @param binding 可信身份绑定 @return 不泄漏扫描ID的密文 */
    public String seal(String value,String binding) {
        try {
            byte[] nonce=new byte[12];random.nextBytes(nonce);
            byte[] encrypted=cipher(Cipher.ENCRYPT_MODE,nonce,binding).doFinal(value.getBytes(StandardCharsets.UTF_8));
            byte[] result=Arrays.copyOf(nonce,nonce.length+encrypted.length);
            System.arraycopy(encrypted,0,result,nonce.length,encrypted.length);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(result);
        }catch(GeneralSecurityException e){throw new IllegalStateException("项目游标加密失败",e);}
    }
    /** @param value 外部游标，首页可空 @param binding 当前可信身份 @return 验证通过的内部游标 */
    public String open(String value,String binding) {
        if(value==null)return null;
        try {
            if(value.isEmpty()||value.length()>2048||!value.matches("[A-Za-z0-9_-]+"))throw new IllegalArgumentException();
            byte[] bytes=Base64.getUrlDecoder().decode(value);
            if(bytes.length<29||!Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(value))throw new IllegalArgumentException();
            return new String(cipher(Cipher.DECRYPT_MODE,Arrays.copyOf(bytes,12),binding)
                    .doFinal(Arrays.copyOfRange(bytes,12,bytes.length)),StandardCharsets.UTF_8);
        }catch(GeneralSecurityException|IllegalArgumentException e){throw new BusinessException(CommonErrorCode.INVALID_PARAMETER);}
    }
    /** @param mode 加解密模式 @param nonce 单次随机数 @param binding 身份附加认证数据 @return 单次独立密码实例 */
    private Cipher cipher(int mode,byte[] nonce,String binding)throws GeneralSecurityException {
        var cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode,key,new GCMParameterSpec(128,nonce));cipher.updateAAD(binding.getBytes(StandardCharsets.UTF_8));return cipher;
    }
}
