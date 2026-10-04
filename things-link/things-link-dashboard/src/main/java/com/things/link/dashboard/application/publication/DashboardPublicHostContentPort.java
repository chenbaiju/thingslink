package com.things.link.dashboard.application.publication;

import java.util.Optional;

/** 只返回同次完整校验取得的公开字节；实现不得校验后再打开外部路径。 */
public interface DashboardPublicHostContentPort {
    /** @param path 固定公开文件名 @return 当前选择内容；坏资格抛不可用 */
    Optional<byte[]> readCurrent(String path);
    /** @param digest 精确制品摘要 @param path 固定公开文件名 @return 不可变历史内容 */
    Optional<byte[]> readRelease(String digest, String path);
    /** @param path assets公开路径 @return 各登记同名同字节才可交付的内容 */
    Optional<byte[]> readAsset(String path);
}
