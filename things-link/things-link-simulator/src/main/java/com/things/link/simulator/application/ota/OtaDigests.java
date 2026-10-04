package com.things.link.simulator.application.ota;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 摘要工具：只保留本片需要的两个常量与一个快照操作。
 *
 * <p>单独抽出来是为了让 {@link OtaJournalRecord} 的紧凑构造器在校验零字节前缀时不必依赖
 * 状态机实例，同时避免各处重复写「空输入摘要」这个魔法字符串。设备侧运行时（会话包）也要用
 * 「尚未取得字节」的哨兵构造停止裁决记录，因此本类与 {@link #EMPTY_SHA256} 都是公开的；
 * 状态机本身仍在包内，不会因此把内部执行细节暴露给别的模块。</p>
 */
public final class OtaDigests {

    /** 空输入的 SHA-256，用作尚未取得任何字节时的「前缀摘要」；停止裁决记录也复用该哨兵。 */
    public static final String EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    /** 工具类不允许实例化。 */
    private OtaDigests() {
    }

    /**
     * 创建新的 SHA-256 摘要器。
     *
     * @return 空的 SHA-256 {@link MessageDigest}
     */
    static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException failure) {
            // 任何符合 Java SE 规范的运行时都必须提供 SHA-256；缺失属于环境损坏。
            throw new IllegalStateException("运行时不提供 SHA-256", failure);
        }
    }

    /**
     * 取当前摘要的快照而不重置摘要器。
     *
     * <p>{@link MessageDigest#digest()} 会重置内部状态，断点续传必须能在每个分片后记录
     * 「到目前为止的摘要」同时保留可继续更新的摘要器，所以只能克隆后取摘要。</p>
     *
     * @param digest 正在使用的摘要器
     * @return 当前前缀摘要的小写十六进制
     */
    static String snapshotHex(MessageDigest digest) {
        try {
            MessageDigest snapshot = (MessageDigest) digest.clone();
            return OtaJournalRecord.hex(snapshot.digest());
        } catch (CloneNotSupportedException failure) {
            // SHA-256 实现必须可克隆；不可克隆说明运行时不兼容，属于环境错误。
            throw new IllegalStateException("SHA-256 摘要器不支持克隆", failure);
        }
    }
}
