package com.things.link.simulator.infrastructure.ota;

import com.things.link.simulator.application.ota.OtaJournalRecord;
import com.things.link.simulator.application.ota.OtaStateJournal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 以真实文件实现的追加写 OTA 日志。
 *
 * <p><b>为什么必须 fsync：</b>断电场景下，写进页缓存的数据和磁盘上的数据不是一回事。若
 * {@link #append(OtaJournalRecord)} 只完成 {@code write} 就返回，状态机就会把「已确认偏移」建立在
 * 可能丢失的字节上，重启后要么从头再来、要么把损坏的前缀当成可信。这里每次追加后调用
 * {@link FileChannel#force(boolean)}，让「日志说确认了多少」与「磁盘上真的有多少」保持一致。</p>
 *
 * <p>格式是一行一条记录的定宽分隔文本（字段本身只有枚举名、十进制数、十六进制摘要和纪元毫秒，
 * 不含分隔符），因此无需引入任何序列化依赖。读取遇到格式非法行会直接失败：宁可让设备停在
 * 「日志损坏、拒绝刷写」，也不能跳过坏行继续。</p>
 */
public final class FileOtaStateJournal implements OtaStateJournal {

    /** 日志文件路径。 */
    private final Path file;

    /**
     * 创建文件日志；不会立即创建文件，第一次 {@link #append(OtaJournalRecord)} 时创建目录与文件。
     *
     * @param file 日志文件路径
     */
    public FileOtaStateJournal(Path file) {
        this.file = Objects.requireNonNull(file, "file").toAbsolutePath();
    }

    /**
     * @return 日志文件绝对路径
     */
    public Path file() {
        return file;
    }

    /**
     * 追加一条记录并 fsync。
     *
     * @param record 待追加记录
     */
    @Override
    public synchronized void append(OtaJournalRecord record) {
        Objects.requireNonNull(record, "record");
        String line = record.toLogLine();
        // 记录各字段只有枚举名、十进制数、十六进制摘要与纪元毫秒，天然不含分隔符与换行；
        // 这里只做最后一道防线，防止未来新增自由文本字段时把日志格式写坏。
        if (line.indexOf('\n') >= 0 || line.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("OTA 日志行不得包含换行");
        }
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            ByteBuffer buffer = StandardCharsets.UTF_8.encode(line + System.lineSeparator());
            try (FileChannel channel = FileChannel.open(file,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
        } catch (IOException failure) {
            throw new UncheckedIOException("OTA 日志追加失败", failure);
        }
    }

    /**
     * 读取全部记录；文件不存在时返回空列表，视为第一次运行。
     *
     * @return 不可变记录列表
     */
    @Override
    public synchronized List<OtaJournalRecord> read() {
        if (!Files.exists(file)) {
            return List.of();
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException("OTA 日志读取失败", failure);
        }
        List<OtaJournalRecord> records = new ArrayList<>(lines.size());
        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            records.add(OtaJournalRecord.fromLogLine(line));
        }
        return List.copyOf(records);
    }
}
