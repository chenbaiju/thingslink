package com.things.link.support.notification.delivery;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.UUID;

/** Only the wire primitive is shared; each caller retains its separately derived purpose key. */
public final class WebhookSignatures {
    private WebhookSignatures() {}
    public static String sign(byte[] derivedKey, long timestamp, String nonce, UUID deliveryId, byte[] body) {
        try {
            Mac mac=Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(derivedKey,"HmacSHA256"));
            mac.update((timestamp+"\n"+nonce+"\n"+deliveryId+"\n").getBytes(StandardCharsets.UTF_8));
            return "v1="+HexFormat.of().formatHex(mac.doFinal(body));
        } catch(java.security.GeneralSecurityException impossible) {
            throw new IllegalStateException("Webhook signature unavailable");
        }
    }
}
