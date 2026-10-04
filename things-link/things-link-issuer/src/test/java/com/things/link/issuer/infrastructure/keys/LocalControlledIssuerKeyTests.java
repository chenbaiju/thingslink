package com.things.link.issuer.infrastructure.keys;

import com.things.link.entitlement.GrantV1;
import com.things.link.entitlement.application.ApprovedSelfHostedRevision;
import com.things.link.entitlement.application.GrantV1Verification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.security.KeyFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 本机受控密钥的加密、严格权限及获批 V1 签名负例。 */
class LocalControlledIssuerKeyTests {
    @TempDir Path temporary;

    @Test
    void encryptedKeySignsOnlyMatchingApprovedGrantAndCannotBeOverwritten() throws Exception {
        Path root = temporary.resolve("controlled-issuer");
        char[] password = "test-only-long-password".toCharArray();
        try {
            var identity = LocalControlledIssuerKey.initialize(root, password);
            Path file = root.resolve("issuer-key-v1.bin");
            if (root.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                assertThat(Files.getPosixFilePermissions(root)).containsExactlyInAnyOrder(
                        PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE);
                assertThat(Files.getPosixFilePermissions(file)).containsExactlyInAnyOrder(
                        PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            } else {
                for (Path path : List.of(root, file)) {
                    var acl = Files.getFileAttributeView(path, AclFileAttributeView.class).getAcl();
                    assertThat(acl).hasSize(1);
                    assertThat(acl.getFirst().principal()).isEqualTo(Files.getOwner(path));
                }
            }
            assertThatThrownBy(() -> KeyFactory.getInstance("Ed25519")
                    .generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(file))))
                    .isInstanceOf(java.security.spec.InvalidKeySpecException.class);
            var reread = LocalControlledIssuerKey.publicIdentity(root, password);
            assertThat(reread.keyId()).isEqualTo(identity.keyId());
            assertThat(reread.publicKeySpki()).containsExactly(identity.publicKeySpki());
            assertThatThrownBy(() -> LocalControlledIssuerKey.initialize(root, password))
                    .hasMessageContaining("已存在");

            var revision = ApprovedSelfHostedRevision.loadApproved();
            var standard = revision.tier("STANDARD");
            Instant now = Instant.parse("2026-09-28T00:00:00Z");
            UUID deployment = UUID.randomUUID(), tenant = UUID.randomUUID(), grantId = UUID.randomUUID();
            GrantV1 grant = new GrantV1(identity.issuerId(), grantId, deployment, tenant,
                    "STANDARD", revision.revisionId(), revision.sha256(), 1,
                    now, now, now.atOffset(ZoneOffset.UTC).plusYears(1).toInstant(),
                    identity.keyId(), standard.quotas(), standard.capabilities());
            byte[] envelope = LocalControlledIssuerKey.sign(root, password, grant);
            var publicKey = KeyFactory.getInstance("Ed25519")
                    .generatePublic(new X509EncodedKeySpec(identity.publicKeySpki()));
            var verified = GrantV1Verification.verify(envelope,
                    Map.of(identity.keyId(), publicKey), identity.issuerId(), deployment, tenant);
            revision.requireMatches(verified);
            assertThat(verified.grantId()).isEqualTo(grantId);
            assertThatThrownBy(() -> GrantV1Verification.verify(envelope,
                    Map.of(identity.keyId(), publicKey), identity.issuerId(), deployment,
                    UUID.randomUUID())).hasMessageContaining("invalid SHC grant");
            assertThatThrownBy(() -> LocalControlledIssuerKey.sign(root, password,
                    new GrantV1(identity.issuerId(), UUID.randomUUID(), deployment, tenant,
                            "STANDARD", revision.revisionId(), revision.sha256(), 1,
                            now, now, now.atOffset(ZoneOffset.UTC).plusYears(1).toInstant(),
                            "other-key", standard.quotas(), standard.capabilities())))
                    .hasMessageContaining("身份");
            assertThatThrownBy(() -> LocalControlledIssuerKey.sign(root,
                    "wrong-long-password".toCharArray(), grant)).hasMessageContaining("口令或文件无效");
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    @Test
    void tamperAndLoosePermissionsFailClosed() throws Exception {
        Path root = temporary.resolve("tamper-issuer");
        char[] password = "test-only-long-password".toCharArray();
        try {
            LocalControlledIssuerKey.initialize(root, password);
            Path file = root.resolve("issuer-key-v1.bin");
            List<AclEntry> originalAcl = null;
            if (file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                Files.setPosixFilePermissions(file, Set.of(PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE, PosixFilePermission.GROUP_READ));
            } else {
                var view = Files.getFileAttributeView(file, AclFileAttributeView.class);
                originalAcl = view.getAcl();
                var extra = AclEntry.newBuilder().setType(AclEntryType.ALLOW)
                        .setPrincipal(Files.getOwner(file)).setPermissions(AclEntryPermission.READ_DATA).build();
                view.setAcl(List.of(originalAcl.getFirst(), extra));
            }
            assertThatThrownBy(() -> LocalControlledIssuerKey.publicIdentity(root, password))
                    .hasMessageContaining("仅允许文件所有者");
            if (originalAcl == null) {
                Files.setPosixFilePermissions(file, Set.of(PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE));
            } else {
                Files.getFileAttributeView(file, AclFileAttributeView.class).setAcl(originalAcl);
            }
            byte[] bytes = Files.readAllBytes(file);
            bytes[bytes.length - 1] ^= 1;
            Files.write(file, bytes);
            assertThatThrownBy(() -> LocalControlledIssuerKey.publicIdentity(root, password))
                    .hasMessageContaining("口令或文件无效");
        } finally {
            Arrays.fill(password, '\0');
        }
    }
}
