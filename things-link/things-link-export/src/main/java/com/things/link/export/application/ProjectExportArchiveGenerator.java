package com.things.link.export.application;

import com.things.link.alarm.application.AlarmExportSource;
import com.things.link.device.application.DeviceExportSource;
import com.things.link.export.domain.ProjectExportClaim;
import com.things.link.project.application.ProjectExportSource;
import com.things.link.support.audit.AuditExportSource;
import com.things.link.support.storage.ObjectUploadControl;
import com.things.link.telemetry.application.PropertyPointExportSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** 在单一PostgreSQL快照中生成ADR0075版本化白名单ZIP。 */
@Service
public class ProjectExportArchiveGenerator {

    /** 全部数据文件未压缩总量硬上限1GiB。 */
    static final long MAX_UNCOMPRESSED_BYTES = 1_073_741_824L;
    /** 最终ZIP硬上限512MiB。 */
    static final long MAX_ARCHIVE_BYTES = 536_870_912L;
    /** 单次尝试十五分钟墙钟预算。 */
    static final Duration MAX_DURATION = Duration.ofMinutes(15);
    /** JSON行使用单个LF，与平台无关。 */
    private static final byte[] NEWLINE = {'\n'};

    /** project域项目与成员源。 */
    private final ProjectExportSource projectSource;
    /** device域设备源。 */
    private final DeviceExportSource deviceSource;
    /** telemetry域原始属性点源。 */
    private final PropertyPointExportSource propertyPointSource;
    /** alarm域实例与事件源。 */
    private final AlarmExportSource alarmSource;
    /** support域审计源。 */
    private final AuditExportSource auditSource;
    /** 统一JSON序列化配置。 */
    private final ObjectMapper objectMapper;
    /** 临时文件根目录。 */
    private final Path temporaryRoot;

    /**
     * 创建归档生成器。
     * @param projectSource 项目与成员事实源
     * @param deviceSource 设备事实源
     * @param propertyPointSource 属性点事实源
     * @param alarmSource 告警事实源
     * @param auditSource 审计事实源
     * @param objectMapper JSON映射器
     * @param temporaryRoot 临时文件根目录
     */
    public ProjectExportArchiveGenerator(
            ProjectExportSource projectSource,
            DeviceExportSource deviceSource,
            PropertyPointExportSource propertyPointSource,
            AlarmExportSource alarmSource,
            AuditExportSource auditSource,
            ObjectMapper objectMapper,
            @Value("${things-link.export.temporary-directory:${java.io.tmpdir}/things-link-export}")
            String temporaryRoot) {
        this.projectSource = projectSource;
        this.deviceSource = deviceSource;
        this.propertyPointSource = propertyPointSource;
        this.alarmSource = alarmSource;
        this.auditSource = auditSource;
        this.objectMapper = objectMapper;
        this.temporaryRoot = Path.of(temporaryRoot).toAbsolutePath().normalize();
    }

    /**
     * 生成本地ZIP。调用方必须在上传完成或失败后关闭返回值。
     *
     * <p>ADR0075文案中的“只读”指本方法不写业务表。PostgreSQL READ ONLY事务禁止
     * {@code FOR SHARE}，因此这里使用非readOnly的REPEATABLE READ；首条业务SQL仍是项目锁。</p>
     *
     * @param claim 当前任务领取身份
     * @param cancelled 租约丢失或worker关闭信号
     * @return 已校验大小和摘要的本地归档
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ, timeout = 900)
    public GeneratedProjectExport generate(ProjectExportClaim claim, BooleanSupplier cancelled) {
        long deadlineNanos = System.nanoTime() + MAX_DURATION.toNanos();
        // 必须先建立项目行锁和MVCC快照；任何领域读取都不得跑在它之前。
        ProjectExportSource.ProjectExportProject project = projectSource.lockSnapshot(
                claim.tenantId(), claim.projectId(), claim.projectGeneration());
        checkBudget(deadlineNanos, cancelled);

        Path directory = null;
        try {
            Files.createDirectories(temporaryRoot);
            directory = Files.createTempDirectory(temporaryRoot, claim.jobId() + "-");
            registerRollbackCleanup(directory);
            List<DataFile> files = new ArrayList<>();
            long[] totalBytes = {0L};

            files.add(writeRows(directory, "project.json", false, sink -> sink.write(new ProjectJson(
                    project.id(), project.name(), project.region(), project.timezone(), project.status(),
                    project.createdAt(), project.updatedAt(), project.deletedAt())),
                    totalBytes, deadlineNanos, cancelled));
            files.add(writeRows(directory, "members.jsonl", true, sink -> projectSource.streamMembers(
                    claim.tenantId(), claim.projectId(), sink::write), totalBytes, deadlineNanos, cancelled));
            files.add(writeRows(directory, "devices.jsonl", true, sink -> deviceSource.streamDevices(
                    claim.tenantId(), claim.projectId(), sink::write), totalBytes, deadlineNanos, cancelled));
            files.add(writeRows(directory, "property-points.jsonl", true,
                    sink -> propertyPointSource.streamPropertyPoints(
                            claim.tenantId(), claim.projectId(), sink::write),
                    totalBytes, deadlineNanos, cancelled));
            files.add(writeRows(directory, "alarm-instances.jsonl", true,
                    sink -> alarmSource.streamInstances(claim.tenantId(), claim.projectId(), sink::write),
                    totalBytes, deadlineNanos, cancelled));
            files.add(writeRows(directory, "alarm-events.jsonl", true,
                    sink -> alarmSource.streamEvents(claim.tenantId(), claim.projectId(), sink::write),
                    totalBytes, deadlineNanos, cancelled));
            files.add(writeRows(directory, "audit-logs.jsonl", true,
                    sink -> auditSource.streamAuditLogs(claim.tenantId(), claim.projectId(), sink::write),
                    totalBytes, deadlineNanos, cancelled));

            Path archive = directory.resolve("project-export-v1.zip");
            writeArchive(archive, claim, project.snapshotAt(), files, totalBytes[0], deadlineNanos, cancelled);
            long archiveSize = Files.size(archive);
            if (archiveSize > MAX_ARCHIVE_BYTES) {
                throw new ProjectExportLimitException("项目导出ZIP超过512MiB上限");
            }
            return new GeneratedProjectExport(directory, archive, project.snapshotAt(), archiveSize, sha256(archive));
        } catch (IOException exception) {
            deleteQuietly(directory);
            throw new IllegalStateException("项目导出本地归档失败", exception);
        } catch (RuntimeException exception) {
            deleteQuietly(directory);
            throw exception;
        }
    }

    /** 写单个JSON或JSONL数据文件并计算逐文件摘要。 */
    private DataFile writeRows(Path directory, String name, boolean jsonLines, RowProducer producer,
                               long[] totalBytes, long deadlineNanos, BooleanSupplier cancelled)
            throws IOException {
        Path target = directory.resolve(name);
        MessageDigest digest = digest();
        try (OutputStream raw = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW);
             DigestCountingOutputStream output = new DigestCountingOutputStream(
                     new BufferedOutputStream(raw), digest, totalBytes, MAX_UNCOMPRESSED_BYTES)) {
            CountingJsonSink sink = new CountingJsonSink(output, jsonLines, deadlineNanos, cancelled);
            producer.produce(sink);
            output.flush();
            return new DataFile(name, target, sink.rowCount(), output.fileBytes(), hex(digest.digest()));
        }
    }

    /** 将manifest和七个数据文件按固定顺序写入最终ZIP。 */
    private void writeArchive(Path archive, ProjectExportClaim claim, Instant snapshotAt,
                              List<DataFile> files, long totalBytes, long deadlineNanos,
                              BooleanSupplier cancelled) throws IOException {
        List<FileManifest> manifests = files.stream()
                .map(file -> new FileManifest(file.path(), file.rowCount(), file.uncompressedBytes(), file.sha256()))
                .toList();
        byte[] manifest = objectMapper.writeValueAsBytes(new ExportManifest(
                1, claim.jobId(), claim.projectId(), claim.projectGeneration(), snapshotAt,
                manifests, totalBytes));
        try (OutputStream raw = Files.newOutputStream(archive, StandardOpenOption.CREATE_NEW);
             BoundedOutputStream bounded = new BoundedOutputStream(
                     new BufferedOutputStream(raw), MAX_ARCHIVE_BYTES);
             ZipOutputStream zip = new ZipOutputStream(bounded, java.nio.charset.StandardCharsets.UTF_8)) {
            writeEntry(zip, "manifest.json", manifest, snapshotAt, deadlineNanos, cancelled);
            for (DataFile file : files) {
                checkBudget(deadlineNanos, cancelled);
                ZipEntry entry = new ZipEntry(file.path());
                entry.setTime(snapshotAt.toEpochMilli());
                zip.putNextEntry(entry);
                try (var input = Files.newInputStream(file.file())) {
                    byte[] buffer = new byte[64 * 1024];
                    int read;
                    while ((read = input.read(buffer)) >= 0) {
                        checkBudget(deadlineNanos, cancelled);
                        zip.write(buffer, 0, read);
                    }
                }
                zip.closeEntry();
            }
        }
    }

    /** 写内存中的manifest条目。 */
    private static void writeEntry(ZipOutputStream zip, String name, byte[] value, Instant snapshotAt,
                                   long deadlineNanos, BooleanSupplier cancelled) throws IOException {
        checkBudget(deadlineNanos, cancelled);
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(snapshotAt.toEpochMilli());
        zip.putNextEntry(entry);
        zip.write(value);
        zip.closeEntry();
    }

    /** 检查十五分钟预算与租约失权信号。 */
    private static void checkBudget(long deadlineNanos, BooleanSupplier cancelled) {
        if (System.nanoTime() > deadlineNanos) {
            throw new ProjectExportLimitException("项目导出超过15分钟生成预算");
        }
        if (cancelled instanceof ObjectUploadControl control && control.timedOut()) {
            throw new ProjectExportLimitException("项目导出超过15分钟尝试预算");
        }
        if (cancelled.getAsBoolean()) {
            throw new IllegalStateException("项目导出租约已失效");
        }
    }

    /**
     * 方法返回后事务代理仍可能在提交阶段失败；回滚回调负责删除尚未交给worker的目录。
     * 成功提交时文件必须保留，随后由worker上传并通过GeneratedProjectExport关闭。
     * @param directory 本次尝试独占临时目录
     */
    private static void registerRollbackCleanup(Path directory) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            /** 回滚或提交失败时，调用方不会收到返回值，必须在此收束本地目录。 */
            @Override
            public void afterCompletion(int status) {
                if (status != TransactionSynchronization.STATUS_COMMITTED) {
                    deleteQuietly(directory);
                }
            }
        });
    }

    /** 计算本地ZIP摘要。 */
    private static String sha256(Path path) throws IOException {
        MessageDigest digest = digest();
        try (var input = Files.newInputStream(path)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        return hex(digest.digest());
    }

    /** 创建SHA-256；JDK必须提供该标准算法。 */
    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK缺少SHA-256", exception);
        }
    }

    /** 小写十六进制摘要。 */
    private static String hex(byte[] value) {
        return HexFormat.of().formatHex(value);
    }

    /** 异常路径尽力清理；原异常优先，残留目录由启动清理继续处理。 */
    private static void deleteQuietly(Path directory) {
        if (directory == null || !Files.exists(directory)) return;
        try (var paths = Files.walk(directory)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (IOException ignored) { /* 保留原失败。 */ }
            });
        } catch (IOException ignored) {
            // 原失败仍是任务分类依据；不能用清理异常覆盖数据库或大小上限异常。
        }
    }

    /** 只包含ADR0075允许进入project.json的字段。 */
    private record ProjectJson(UUID id, String name, String region, String timezone, String status,
                               Instant createdAt, Instant updatedAt, Instant deletedAt) {
    }

    /** 单个数据文件生成结果。 */
    private record DataFile(String path, Path file, long rowCount, long uncompressedBytes, String sha256) {
    }

    /** manifest中的逐文件事实。 */
    private record FileManifest(String path, long rowCount, long uncompressedBytes, String sha256) {
    }

    /** ADR0075 formatVersion=1的manifest。 */
    private record ExportManifest(int formatVersion, UUID exportId, UUID projectId, long projectGeneration,
                                  Instant snapshotAt, List<FileManifest> files,
                                  long totalUncompressedBytes) {
    }

    /** 由领域端口同步流出全部行。 */
    @FunctionalInterface
    private interface RowProducer {
        /** @param sink JSON行写入器 */
        void produce(CountingJsonSink sink);
    }

    /** 序列化单行并在每次写前检查预算。 */
    private final class CountingJsonSink {
        /** 目标文件。 */ private final OutputStream output;
        /** 是否每条记录后写LF。 */ private final boolean jsonLines;
        /** 单次尝试截止。 */ private final long deadlineNanos;
        /** 租约失权信号。 */ private final BooleanSupplier cancelled;
        /** 实际写出的记录数。 */ private long rowCount;

        /** 创建逐行写入器。 */
        private CountingJsonSink(OutputStream output, boolean jsonLines, long deadlineNanos,
                                 BooleanSupplier cancelled) {
            this.output = output;
            this.jsonLines = jsonLines;
            this.deadlineNanos = deadlineNanos;
            this.cancelled = cancelled;
        }

        /** @param value 单条白名单事实 */
        private void write(Object value) {
            checkBudget(deadlineNanos, cancelled);
            try {
                byte[] json = objectMapper.writeValueAsBytes(value);
                output.write(json);
                if (jsonLines) output.write(NEWLINE);
                rowCount++;
            } catch (IOException exception) {
                throw new IllegalStateException("项目导出数据文件写入失败", exception);
            }
        }

        /** @return 实际写出行数 */
        private long rowCount() {
            return rowCount;
        }
    }

    /** 同时统计单文件、总未压缩字节并更新摘要。 */
    private static final class DigestCountingOutputStream extends FilterOutputStream {
        /** 当前文件摘要。 */ private final MessageDigest digest;
        /** 七文件共享总字节数。 */ private final long[] total;
        /** ADR硬上限。 */ private final long maximum;
        /** 当前文件字节数。 */ private long fileBytes;

        /** 创建计数摘要流。 */
        private DigestCountingOutputStream(OutputStream output, MessageDigest digest,
                                           long[] total, long maximum) {
            super(output);
            this.digest = digest;
            this.total = total;
            this.maximum = maximum;
        }

        /** 沿用接口定义的契约。{@inheritDoc} */
        @Override
        public void write(int value) throws IOException {
            byte[] one = {(byte) value};
            write(one, 0, 1);
        }

        /** 沿用接口定义的契约。{@inheritDoc} */
        @Override
        public void write(byte[] value, int offset, int length) throws IOException {
            if (total[0] + length > maximum) {
                throw new ProjectExportLimitException("项目导出未压缩数据超过1GiB上限");
            }
            out.write(value, offset, length);
            digest.update(value, offset, length);
            fileBytes += length;
            total[0] += length;
        }

        /** @return 当前文件准确字节数 */
        private long fileBytes() { return fileBytes; }
    }

    /** 对最终ZIP做写时硬限制，不能先占满磁盘再检查。 */
    private static final class BoundedOutputStream extends FilterOutputStream {
        /** 最大允许字节数。 */ private final long maximum;
        /** 已写字节数。 */ private long count;

        /** 创建有界输出流。 */
        private BoundedOutputStream(OutputStream output, long maximum) {
            super(output);
            this.maximum = maximum;
        }

        /** 沿用接口定义的契约。{@inheritDoc} */
        @Override public void write(int value) throws IOException {
            byte[] one = {(byte) value};
            write(one, 0, 1);
        }

        /** 沿用接口定义的契约。{@inheritDoc} */
        @Override public void write(byte[] value, int offset, int length) throws IOException {
            if (count + length > maximum) {
                throw new ProjectExportLimitException("项目导出ZIP超过512MiB上限");
            }
            out.write(value, offset, length);
            count += length;
        }
    }
}
