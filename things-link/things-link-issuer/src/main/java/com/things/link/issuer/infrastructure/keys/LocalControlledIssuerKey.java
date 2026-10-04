package com.things.link.issuer.infrastructure.keys;

import com.things.link.entitlement.GrantV1;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.Console;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.channels.FileChannel;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Set;

/** 单机受控签发密钥容器。没有网络或 Spring 入口；正式解锁仅由交互式命令完成。 */
public final class LocalControlledIssuerKey {
    private static final byte[] MAGIC = "TCSHC-ISSUER-KEY-1\0".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    private static final String FILE = "issuer-key-v1.bin";
    private static final int ITERATIONS = 600_000;
    private static final int SALT_BYTES = 32;
    private static final int NONCE_BYTES = 12;
    private static final int MAX_BYTES = 4096;

    private LocalControlledIssuerKey() { }

    public record Identity(String issuerId, String keyId, byte[] publicKeySpki, byte[] publicKeySha256) {
        public Identity {
            publicKeySpki = publicKeySpki.clone();
            publicKeySha256 = publicKeySha256.clone();
        }
        @Override public byte[] publicKeySpki() { return publicKeySpki.clone(); }
        @Override public byte[] publicKeySha256() { return publicKeySha256.clone(); }
    }

    public static Identity initialize(Path directory, char[] password) throws Exception {
        requirePassword(password);
        Path root = checkedRoot(directory, true);
        Path target = root.resolve(FILE);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("签发密钥已存在，不能生成并覆盖");
        }
        var keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] publicSpki = keys.getPublic().getEncoded();
        byte[] privatePkcs8 = keys.getPrivate().getEncoded();
        byte[] salt = new byte[SALT_BYTES];
        byte[] nonce = new byte[NONCE_BYTES];
        var random = new SecureRandom();
        random.nextBytes(salt);
        random.nextBytes(nonce);
        byte[] header = header(publicSpki, salt, nonce);
        byte[] encrypted;
        try {
            encrypted = crypt(Cipher.ENCRYPT_MODE, password, salt, nonce, header, privatePkcs8);
        } finally {
            Arrays.fill(privatePkcs8, (byte) 0);
        }
        var bytes = new ByteArrayOutputStream();
        bytes.write(header);
        try (var out = new DataOutputStream(bytes)) {
            out.writeInt(encrypted.length);
            out.write(encrypted);
        }
        Path temporary = IssuerPrivateFiles.createTempFile(root, ".issuer-key-", ".tmp");
        try {
            Files.write(temporary, bytes.toByteArray(), StandardOpenOption.TRUNCATE_EXISTING);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            // 硬链接创建目标是原子的 CREATE_NEW；并发初始化绝不能覆盖现有正式密钥。
            Files.createLink(target, temporary);
            return identity(publicSpki);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /** 每次签名重新读取加密文件；调用方不得缓存解锁私钥或打印口令。 */
    public static byte[] sign(Path directory, char[] password, GrantV1 grant) throws Exception {
        requirePassword(password);
        if (grant == null) throw new IllegalArgumentException("授权不能为空");
        KeyFile file = read(directory);
        Identity identity = identity(file.publicSpki());
        if (!identity.issuerId().equals(grant.issuerId())
                || !identity.keyId().equals(grant.keyId())) {
            throw new GeneralSecurityException("签发密钥身份与授权不符");
        }
        byte[] clear;
        try {
            clear = crypt(Cipher.DECRYPT_MODE, password, file.salt(), file.nonce(),
                    file.header(), file.encrypted());
        } catch (AEADBadTagException invalid) {
            throw new GeneralSecurityException("签发密钥口令或文件无效", invalid);
        }
        try {
            PrivateKey privateKey = KeyFactory.getInstance("Ed25519")
                    .generatePrivate(new PKCS8EncodedKeySpec(clear));
            byte[] envelope = grant.sign(privateKey);
            PublicKey publicKey = KeyFactory.getInstance("Ed25519")
                    .generatePublic(new X509EncodedKeySpec(file.publicSpki()));
            if (!GrantV1.verifySignatureOnly(envelope, publicKey)) {
                throw new GeneralSecurityException("签发私钥与受信公钥不匹配");
            }
            return envelope;
        } finally {
            Arrays.fill(clear, (byte) 0);
        }
    }

    /** 只读导出公开信任锚；密钥容器本身不得交给客户。 */
    public static Identity publicIdentity(Path directory, char[] password) throws Exception {
        requirePassword(password);
        KeyFile file = read(directory);
        byte[] clear = decrypt(file, password);
        try {
            PrivateKey privateKey = KeyFactory.getInstance("Ed25519")
                    .generatePrivate(new PKCS8EncodedKeySpec(clear));
            PublicKey publicKey = KeyFactory.getInstance("Ed25519")
                    .generatePublic(new X509EncodedKeySpec(file.publicSpki()));
            Signature proof = Signature.getInstance("Ed25519");
            proof.initSign(privateKey);
            proof.update(MAGIC);
            byte[] signed = proof.sign();
            proof.initVerify(publicKey);
            proof.update(MAGIC);
            if (!proof.verify(signed)) throw new GeneralSecurityException("签发公钥与私钥不匹配");
            return identity(file.publicSpki());
        } finally {
            Arrays.fill(clear, (byte) 0);
        }
    }

    private static byte[] decrypt(KeyFile file, char[] password) throws GeneralSecurityException {
        try {
            return crypt(Cipher.DECRYPT_MODE, password, file.salt(), file.nonce(),
                    file.header(), file.encrypted());
        } catch (AEADBadTagException invalid) {
            throw new GeneralSecurityException("签发密钥口令或文件无效", invalid);
        }
    }

    private static Identity identity(byte[] publicSpki) throws GeneralSecurityException {
        PublicKey key = KeyFactory.getInstance("Ed25519")
                .generatePublic(new X509EncodedKeySpec(publicSpki));
        if (!MessageDigest.isEqual(key.getEncoded(), publicSpki)) {
            throw new GeneralSecurityException("签发公钥编码非规范形式");
        }
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(publicSpki);
        return new Identity("thingslink", "thingslink.v1." + HexFormat.of().formatHex(digest, 0, 8),
                publicSpki, digest);
    }

    private static byte[] header(byte[] publicSpki, byte[] salt, byte[] nonce) throws IOException {
        if (publicSpki.length < 1 || publicSpki.length > 128) throw new IOException("公钥长度无效");
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            out.write(MAGIC);
            out.writeByte(publicSpki.length);
            out.write(publicSpki);
            out.write(salt);
            out.write(nonce);
        }
        return bytes.toByteArray();
    }

    private static KeyFile read(Path directory) throws Exception {
        Path root = checkedRoot(directory, false);
        Path target = root.resolve(FILE);
        IssuerPrivateFiles.requirePrivate(target, false);
        if (Files.size(target) > MAX_BYTES) throw new IOException("签发密钥文件过长");
        byte[] bytes = Files.readAllBytes(target);
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (!Arrays.equals(MAGIC, in.readNBytes(MAGIC.length))) throw new IOException("密钥格式无效");
            int length = in.readUnsignedByte();
            if (length < 1 || length > 128) throw new IOException("签发公钥长度无效");
            byte[] spki = in.readNBytes(length);
            byte[] salt = in.readNBytes(SALT_BYTES);
            byte[] nonce = in.readNBytes(NONCE_BYTES);
            if (spki.length != length || salt.length != SALT_BYTES || nonce.length != NONCE_BYTES) {
                throw new IOException("签发密钥头被截断");
            }
            int headerLength = bytes.length - in.available();
            int cipherLength = in.readInt();
            if (cipherLength < 16 || cipherLength > 1024 || cipherLength != in.available()) {
                throw new IOException("签发密钥密文长度无效");
            }
            byte[] encrypted = in.readNBytes(cipherLength);
            return new KeyFile(spki, salt, nonce, Arrays.copyOf(bytes, headerLength), encrypted);
        }
    }

    private static byte[] crypt(int mode, char[] password, byte[] salt, byte[] nonce,
                                byte[] header, byte[] input) throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(password, salt, ITERATIONS, 256);
        byte[] derived;
        try {
            derived = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec).getEncoded();
        } finally {
            spec.clearPassword();
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, new SecretKeySpec(derived, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(header);
            return cipher.doFinal(input);
        } finally {
            Arrays.fill(derived, (byte) 0);
        }
    }

    private static Path checkedRoot(Path directory, boolean create) throws IOException {
        if (directory == null) throw new IOException("必须指定独立签发目录");
        Path root = directory.toAbsolutePath().normalize();
        rejectGitAncestors(root);
        if (Files.isSymbolicLink(root)) throw new IOException("签发目录不能是符号链接");
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            if (!create) throw new IOException("签发目录不存在");
            IssuerPrivateFiles.createDirectory(root);
        }
        IssuerPrivateFiles.requirePrivate(root, true);
        Path realRoot = root.toRealPath();
        rejectGitAncestors(realRoot);
        return realRoot;
    }

    private static void rejectGitAncestors(Path root) throws IOException {
        for (Path at = root; at != null; at = at.getParent()) {
            if (Files.exists(at.resolve(".git"), LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("签发密钥目录不得位于 Git 工作区内");
            }
        }
    }

    private static void requirePassword(char[] password) {
        if (password == null || password.length < 16) {
            throw new IllegalArgumentException("签发密钥口令至少需要 16 个字符");
        }
    }

    private record KeyFile(byte[] publicSpki, byte[] salt, byte[] nonce,
                           byte[] header, byte[] encrypted) { }

    /** 只提供密钥生成和公钥查看；实际签发必须另经审核与数据库事务。 */
    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !Set.of("init", "public").contains(args[0])) {
            throw new IllegalArgumentException("用法：init|public <仓库外受限签发目录>");
        }
        Console console = System.console();
        if (console == null) throw new IllegalStateException("必须从交互式终端操作签发密钥");
        char[] first = console.readPassword("签发密钥口令：");
        char[] second = "init".equals(args[0]) ? console.readPassword("再次输入口令：") : null;
        Identity identity;
        try {
            if (second != null && !Arrays.equals(first, second)) {
                throw new IllegalArgumentException("两次口令不一致");
            }
            identity = "init".equals(args[0])
                    ? initialize(Path.of(args[1]), first)
                    : publicIdentity(Path.of(args[1]), first);
        } finally {
            if (first != null) Arrays.fill(first, '\0');
            if (second != null) Arrays.fill(second, '\0');
        }
        System.out.println("issuerId=" + identity.issuerId());
        System.out.println("keyId=" + identity.keyId());
        System.out.println("publicKeySha256=" + HexFormat.of().formatHex(identity.publicKeySha256()));
        System.out.println("publicKeySpkiBase64="
                + java.util.Base64.getEncoder().encodeToString(identity.publicKeySpki()));
    }
}
