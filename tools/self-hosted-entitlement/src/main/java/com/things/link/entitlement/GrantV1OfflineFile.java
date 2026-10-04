package com.things.link.entitlement;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** 有界、只读的离线介质输入；不导入或激活授权。 */
public final class GrantV1OfflineFile {
    private static final int MAX_ENVELOPE = GrantV1.MAGIC.length + Integer.BYTES
            + GrantV1.MAX_BODY + GrantV1.SIGNATURE_BYTES;

    private GrantV1OfflineFile() { }

    public static GrantV1 verify(Path file, Map<String, PublicKey> trustedKeys,
                                 String expectedIssuer, UUID expectedDeployment, UUID expectedTenant)
            throws IOException, GeneralSecurityException {
        Objects.requireNonNull(file, "file");
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                || Files.size(file) > MAX_ENVELOPE) {
            throw new GeneralSecurityException("invalid SHC grant file");
        }
        byte[] bytes;
        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            // 即使文件大小在检查元数据与打开文件之间变化，也限制实际读取的字节数。
            bytes = input.readNBytes(MAX_ENVELOPE + 1);
        }
        if (bytes.length > MAX_ENVELOPE) {
            throw new GeneralSecurityException("invalid SHC grant file");
        }
        return GrantV1Verifier.verify(bytes, trustedKeys, expectedIssuer,
                expectedDeployment, expectedTenant);
    }
}
