package com.things.link.issuer.application;

import com.things.link.issuer.infrastructure.keys.IssuerPrivateFiles;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.Console;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.UUID;

/** 本机发行操作入口。账号与密钥口令仅从交互式终端输入，不提供 HTTP 或环境变量回退。 */
public final class ControlledGrantIssueCommand {
    private ControlledGrantIssueCommand() { }

    public static void main(String[] args) throws Exception {
        boolean issue = args.length == 7 && "issue".equals(args[0]);
        boolean recover = args.length == 6 && "recover".equals(args[0]);
        if (!issue && !recover) {
            throw new IllegalArgumentException("用法：issue <本机JDBC URL> <受控发行账号> "
                    + "<申请UUID> <授权UUID> <密钥目录> <输出文件>；"
                    + "或 recover <本机JDBC URL> <受控发行账号> <申请UUID> <授权UUID> <输出文件>");
        }
        String url = args[1];
        if (!url.matches("jdbc:postgresql://(?:127\\.0\\.0\\.1|localhost):[0-9]{1,5}/[A-Za-z_][A-Za-z0-9_-]{0,62}")) {
            throw new IllegalArgumentException("首版本机签发仅允许不带额外参数的回环 PostgreSQL URL");
        }
        String user = args[2];
        if (!user.matches("[A-Za-z_][A-Za-z0-9_]{0,62}")) {
            throw new IllegalArgumentException("受控数据库账号名称无效");
        }
        UUID request = UUID.fromString(args[3]);
        UUID grant = UUID.fromString(args[4]);
        Console console = System.console();
        if (console == null) throw new IllegalStateException("正式签发必须使用交互式终端");
        char[] databasePassword = console.readPassword("发行数据库口令：");
        char[] keyPassword = issue ? console.readPassword("签发密钥口令：") : null;
        try {
            if (databasePassword == null || databasePassword.length == 0
                    || (issue && (keyPassword == null || keyPassword.length == 0))) {
                throw new IllegalArgumentException("受控操作口令不能为空");
            }
            // JDBC 驱动需要 String 口令；进程一次执行后退出，且不将口令写入配置、参数或日志。
            var dataSource = new DriverManagerDataSource(url, user, new String(databasePassword));
            var issuer = new ControlledGrantIssuance(dataSource);
            ControlledGrantIssuance.Issued result = issue
                    ? issuer.issue(request, grant, Path.of(args[5]), keyPassword)
                    : issuer.recover(request, grant);
            if (result == null) throw new IllegalStateException("不存在已提交的签发事实");
            writeGrant(Path.of(args[issue ? 6 : 5]), result.envelope());
            System.out.println("grantId=" + result.grantId());
            System.out.println("requestId=" + result.requestId());
            System.out.println("deploymentId=" + result.deploymentId());
            System.out.println("businessTenantId=" + result.tenantId());
            System.out.println("tier=" + result.tier());
            System.out.println("sequence=" + result.sequence());
            System.out.println("envelopeSha256=" + result.envelopeSha256Hex());
        } finally {
            if (databasePassword != null) Arrays.fill(databasePassword, '\0');
            if (keyPassword != null) Arrays.fill(keyPassword, '\0');
        }
    }

    static void writeGrant(Path destination, byte[] envelope) throws Exception {
        Path target = destination.toAbsolutePath().normalize();
        if (!target.getFileName().toString().endsWith(".tcshgrant")) {
            throw new IllegalArgumentException("正式授权输出必须使用 .tcshgrant 扩展名");
        }
        if (Files.isSymbolicLink(target)) throw new IllegalArgumentException("授权目标不能是符号链接");
        Path directory = target.getParent();
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("授权输出目录不存在");
        }
        try { IssuerPrivateFiles.requirePrivate(directory, true); }
        catch (java.io.IOException exception) {
            throw new IllegalArgumentException("授权输出目录必须为所有者私有目录", exception);
        }
        for (Path at = directory.toRealPath(); at != null; at = at.getParent()) {
            if (Files.exists(at.resolve(".git"), LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("授权文件不得输出到 Git 工作区内");
            }
        }
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            try { IssuerPrivateFiles.requirePrivate(target, false); }
            catch (java.io.IOException exception) {
                throw new IllegalArgumentException("已存在的授权文件与原签发事实不一致", exception);
            }
            if (!Arrays.equals(Files.readAllBytes(target), envelope)) {
                throw new IllegalArgumentException("已存在的授权文件与原签发事实不一致");
            }
            return;
        }
        Path temporary = IssuerPrivateFiles.createTempFile(directory, ".tcshgrant-", ".tmp");
        try {
            Files.write(temporary, envelope, StandardOpenOption.TRUNCATE_EXISTING);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            Files.createLink(target, temporary);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
