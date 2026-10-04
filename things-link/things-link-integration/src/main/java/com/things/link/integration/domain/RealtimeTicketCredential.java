package com.things.link.integration.domain;
import java.security.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
/** 独立于tcak1的256位秘密；只持久完整凭据的SHA256。 */
public final class RealtimeTicketCredential {
    private final UUID id;private final String value;
    private RealtimeTicketCredential(UUID id,String value){this.id=id;this.value=value;}
    public static RealtimeTicketCredential generate(UUID id){byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);return new RealtimeTicketCredential(id,"tcrt1."+id+"."+Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));}
    public static Optional<RealtimeTicketCredential> parse(String raw){
        if(raw==null||!raw.matches("tcrt1\\.[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}\\.[A-Za-z0-9_-]{43}"))return Optional.empty();
        String[] parts=raw.split("\\.");
        try{byte[] bytes=Base64.getUrlDecoder().decode(parts[2]);if(bytes.length!=32||!Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(parts[2]))return Optional.empty();return Optional.of(new RealtimeTicketCredential(UUID.fromString(parts[1]),raw));}catch(IllegalArgumentException e){return Optional.empty();}
    }
    public UUID id(){return id;} public String reveal(){return value;}
    public String digest(){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    @Override public String toString(){return "RealtimeTicketCredential[id="+id+", secret=REDACTED]";}
}
