package com.things.link.export.application;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/**
 * 一次已经完成数据库快照、等待上传的本地ZIP。
 *
 * @param workingDirectory 本次尝试独占临时目录
 * @param archive ZIP路径
 * @param snapshotAt PostgreSQL事务快照时刻
 * @param archiveSize ZIP准确字节数
 * @param archiveSha256 ZIP小写SHA-256
 */
public record GeneratedProjectExport(Path workingDirectory, Path archive, Instant snapshotAt,
                                     long archiveSize, String archiveSha256) implements AutoCloseable {

    /** 无论成功、失败或CAS失权都递归删除本次临时目录。 */
    @Override
    public void close() {
        if (workingDirectory == null || !Files.exists(workingDirectory)) {
            return;
        }
        try (var paths = Files.walk(workingDirectory)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException exception) {
                    throw new LocalCleanupException(exception);
                }
            });
        } catch (IOException exception) {
            throw new LocalCleanupException(exception);
        }
    }

    /** 临时文件清理失败必须进入任务失败诊断，不能悄悄占满worker磁盘。 */
    private static final class LocalCleanupException extends RuntimeException {
        /** @param cause 文件系统失败 */
        private LocalCleanupException(Throwable cause) {
            super("项目导出临时文件清理失败", cause);
        }
    }
}
