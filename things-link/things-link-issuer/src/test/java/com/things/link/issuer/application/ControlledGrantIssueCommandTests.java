package com.things.link.issuer.application;

import com.things.link.issuer.infrastructure.keys.IssuerPrivateFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 已提交原文件的受控输出必须可幂等恢复，不得覆盖或进入仓库。 */
class ControlledGrantIssueCommandTests {
    @TempDir Path temporary;

    @Test
    void deliveryFileIsPrivateAtomicAndNeverOverwritten() throws Exception {
        Path delivery = IssuerPrivateFiles.createDirectory(temporary.resolve("delivery"));
        Path target = delivery.resolve("first.tcshgrant");
        byte[] first = {1, 2, 3};
        ControlledGrantIssueCommand.writeGrant(target, first);
        assertThat(Files.readAllBytes(target)).containsExactly(first);
        if (target.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            assertThat(Files.getPosixFilePermissions(target)).containsExactlyInAnyOrder(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
        } else {
            var acl = Files.getFileAttributeView(target, AclFileAttributeView.class).getAcl();
            assertThat(acl).hasSize(1);
            assertThat(acl.getFirst().principal()).isEqualTo(Files.getOwner(target));
        }
        ControlledGrantIssueCommand.writeGrant(target, first);
        assertThatThrownBy(() -> ControlledGrantIssueCommand.writeGrant(target, new byte[]{9}))
                .hasMessageContaining("不一致");
        assertThat(Files.readAllBytes(target)).containsExactly(first);
        assertThatThrownBy(() -> ControlledGrantIssueCommand.writeGrant(
                delivery.resolve("wrong.txt"), first)).hasMessageContaining(".tcshgrant");

        Path checkout = IssuerPrivateFiles.createDirectory(temporary.resolve("checkout"));
        Files.createFile(checkout.resolve(".git"));
        assertThatThrownBy(() -> ControlledGrantIssueCommand.writeGrant(
                checkout.resolve("leak.tcshgrant"), first)).hasMessageContaining("Git 工作区");
    }
}
