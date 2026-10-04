package com.things.link.simulator.infrastructure.ota;

import com.things.link.simulator.application.ota.OtaAcceptedDownloadResponse;
import com.things.link.simulator.application.ota.OtaAcceptedDownloadResponseStore;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 以真实小文件实现的「已接纳下载响应身份」存储。
 *
 * <p><b>为什么是临时文件 + 原子 move：</b>身份记录是单槽覆盖写，若直接原地截断重写，并发的读取方
 * （或掉电）可能看到空文件或半写内容，从而把「我接纳过」误判成「我没接纳过」。先写同目录临时文件并
 * {@link FileChannel#force(boolean)}，再原子替换目标文件，使读者只会看到完整的旧记录或完整的新记录。</p>
 *
 * <p><b>为什么读取对损坏必须宽容：</b>记录损坏时设备应当退回「没有记录」并失败关闭（拒绝未申请响应），
 * 而不是让整个进程崩溃或跳过校验继续执行。因此 {@link #read()} 把一切解析失败都折叠为
 * {@link Optional#empty()}；只有真正的 I/O 环境错误才上抛。</p>
 */
public final class FileOtaAcceptedDownloadResponseStore implements OtaAcceptedDownloadResponseStore {

    /** 目标文件路径。 */
    private final Path file;

    /** 同目录临时文件路径；与目标文件同一文件系统，保证原子替换可行。 */
    private final Path temporary;

    /**
     * 创建文件存储；不会立即创建文件，第一次 {@link #write(OtaAcceptedDownloadResponse)} 时创建目录与文件。
     *
     * @param file 记录文件路径
     */
    public FileOtaAcceptedDownloadResponseStore(Path file) {
        this.file = Objects.requireNonNull(file, "file").toAbsolutePath();
        this.temporary = this.file.resolveSibling(this.file.getFileName().toString() + ".tmp");
    }

    /**
     * @return 记录文件绝对路径
     */
    public Path file() {
        return file;
    }

    /**
     * 先写临时文件并 fsync，再原子替换目标文件。
     *
     * @param response 待写入的身份记录
     */
    @Override
    public synchronized void write(OtaAcceptedDownloadResponse response) {
        Objects.requireNonNull(response, "response");
        String line = response.toLogLine();
        // 记录各字段只有固定版本、UUID、十进制数与十六进制摘要，天然不含分隔符与换行；
        // 这里只做最后一道防线，防止未来新增自由文本字段时把格式写坏。
        if (line.indexOf('\n') >= 0 || line.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("设备本地下载响应记录不得包含换行");
        }
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            ByteBuffer buffer = StandardCharsets.UTF_8.encode(line + System.lineSeparator());
            try (FileChannel channel = FileChannel.open(temporary,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            moveIntoPlace();
        } catch (IOException failure) {
            throw new UncheckedIOException("设备本地下载响应记录写入失败", failure);
        }
    }

    /**
     * 读取单槽记录；文件不存在或内容不可信时返回空。
     *
     * @return 已接纳身份；没有可信记录时为空
     */
    @Override
    public synchronized Optional<OtaAcceptedDownloadResponse> read() {
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        String content;
        try {
            content = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException("设备本地下载响应记录读取失败", failure);
        }
        List<String> lines = content.lines().filter(line -> !line.isBlank()).toList();
        if (lines.size() != 1) {
            // 空文件、被截断成多段或意外追加出第二行：都无法证明是原记录，按「没有记录」失败关闭。
            return Optional.empty();
        }
        try {
            return Optional.of(OtaAcceptedDownloadResponse.fromLogLine(lines.getFirst()));
        } catch (RuntimeException malformed) {
            return Optional.empty();
        }
    }

    /** 原子替换目标文件；文件系统不支持原子 move 时退回普通替换。 */
    private void moveIntoPlace() throws IOException {
        try {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
