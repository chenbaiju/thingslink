package com.things.link.issuer.infrastructure.keys;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.EnumSet;
import java.util.List;

/** 发行文件只接受所有者独占的 POSIX 权限或 Windows ACL，不回退到宽松默认权限。 */
public final class IssuerPrivateFiles {
    private IssuerPrivateFiles() { }

    public static Path createDirectory(Path path) throws IOException {
        UserPrincipal owner = currentOwner(path);
        Files.createDirectory(path, privateAttribute(path, true, owner));
        try {
            Files.setOwner(path, owner);
            requirePrivate(path, true);
            return path;
        } catch (IOException | RuntimeException exception) {
            Files.deleteIfExists(path);
            throw exception;
        }
    }

    public static Path createTempFile(Path directory, String prefix, String suffix) throws IOException {
        UserPrincipal owner = currentOwner(directory);
        Path file = Files.createTempFile(directory, prefix, suffix,
                privateAttribute(directory, false, owner));
        try {
            Files.setOwner(file, owner);
            requirePrivate(file, false);
            return file;
        } catch (IOException | RuntimeException exception) {
            Files.deleteIfExists(file);
            throw exception;
        }
    }

    public static void requirePrivate(Path path, boolean directory) throws IOException {
        UserPrincipal owner = currentOwner(path);
        if (!(directory ? Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                : Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                || !Files.getOwner(path, LinkOption.NOFOLLOW_LINKS).equals(owner)
                || !hasPrivatePermissions(path, directory, owner)) {
            throw new IOException("签发身份权限必须仅允许文件所有者访问");
        }
    }

    private static UserPrincipal currentOwner(Path path) throws IOException {
        return path.getFileSystem().getUserPrincipalLookupService()
                .lookupPrincipalByName(System.getProperty("user.name"));
    }

    private static FileAttribute<?> privateAttribute(Path path, boolean directory, UserPrincipal owner)
            throws IOException {
        if (path.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            return PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(
                    directory ? "rwx------" : "rw-------"));
        }
        if (!path.getFileSystem().supportedFileAttributeViews().contains("acl")) {
            throw new IOException("签发文件系统必须支持 POSIX 权限或 ACL");
        }
        List<AclEntry> entries = List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW)
                .setPrincipal(owner).setPermissions(EnumSet.allOf(AclEntryPermission.class)).build());
        return new FileAttribute<List<AclEntry>>() {
            @Override public String name() { return "acl:acl"; }
            @Override public List<AclEntry> value() { return entries; }
        };
    }

    private static boolean hasPrivatePermissions(Path path, boolean directory, UserPrincipal owner)
            throws IOException {
        if (path.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            return Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS).equals(
                    PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"));
        }
        AclFileAttributeView view = Files.getFileAttributeView(path, AclFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS);
        if (view == null) return false;
        List<AclEntry> entries = view.getAcl();
        return entries.size() == 1 && entries.getFirst().type() == AclEntryType.ALLOW
                && entries.getFirst().principal().equals(owner) && entries.getFirst().flags().isEmpty()
                && entries.getFirst().permissions().equals(EnumSet.allOf(AclEntryPermission.class));
    }
}
