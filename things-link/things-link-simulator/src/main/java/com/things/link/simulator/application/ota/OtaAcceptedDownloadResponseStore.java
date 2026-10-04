package com.things.link.simulator.application.ota;

import java.util.Optional;

/**
 * 设备本地「已接纳下载响应身份」的耐久单槽存储端口。
 *
 * <p><b>为什么需要独立端口而不是复用 {@link OtaStateJournal}：</b>追加写日志记录的是尝试的进度事实
 * （阶段、偏移、前缀摘要），而这里记录的是「设备确实接纳过哪一份下载响应」这一个身份；两者的
 * 失效语义不同——日志损坏必须让设备停在拒绝刷写，而身份记录损坏只应退化为「没有记录」并失败关闭。
 * 拆成两个文件也让 {@link OtaStateJournal} 的既有格式与追加写不变量保持不变。</p>
 *
 * <p><b>写入必须在执行之前完成：</b>设备一旦开始执行就可能取字节、写暂存区甚至打开安装窗口；
 * 只有先把身份落盘，进程在下载途中崩溃后重投递同一份响应时，设备才有依据识别「这是我自己接纳过的
 * 那一份」，而不是把它当成未申请响应拒绝。写入返回后记录必须已经耐久（落盘并 fsync）且<b>原子替换</b>，
 * 读者只应看到替换前或替换后的完整内容，绝不读到半写文件。</p>
 */
public interface OtaAcceptedDownloadResponseStore {

    /**
     * 耐久、原子地写入本次接纳的下载响应身份，覆盖上一条记录。
     *
     * <p>写入失败必须抛异常，调用方据此判定「本次接纳尚未耐久」，绝不能继续执行。</p>
     *
     * @param response 待写入的身份记录
     */
    void write(OtaAcceptedDownloadResponse response);

    /**
     * 读取当前已接纳的下载响应身份。
     *
     * <p>文件不存在、内容为空、字段损坏、被截断或版本未知时都返回 {@link Optional#empty()}：
     * 此时设备按「没有接纳记录」失败关闭，绝不会因为一条读不懂的旧记录就放行一次执行，
     * 也绝不会让读取本身抛出。</p>
     *
     * @return 已接纳身份；没有可信记录时为空
     */
    Optional<OtaAcceptedDownloadResponse> read();
}
