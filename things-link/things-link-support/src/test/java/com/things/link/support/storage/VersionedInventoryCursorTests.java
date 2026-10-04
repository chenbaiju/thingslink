package com.things.link.support.storage;

import java.util.Base64;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 续页身份必须完整推进，不能静默跳过同键其余版本或跨范围恢复。 */
class VersionedInventoryCursorTests {
    /** Unicode及特殊字符完整往返，编码不改变供应商版本身份。 */
    @Test
    void preservesExactProviderMarkersAndScope() {
        var first = new VersionedInventoryCursor.Position(null, null);
        String cursor = VersionedInventoryCursor.next("versions", "bucket", "固件 +/", first, "固件 +/", "v+/%");
        assertThat(VersionedInventoryCursor.decode(cursor, "versions", "bucket", "固件 +/"))
                .isEqualTo(new VersionedInventoryCursor.Position("固件 +/", "v+/%"));
        rejected(() -> VersionedInventoryCursor.decode(cursor, "multipart", "bucket", "固件 +/"));
        rejected(() -> VersionedInventoryCursor.decode(cursor, "versions", "other", "固件 +/"));
        rejected(() -> VersionedInventoryCursor.decode(cursor, "versions", "bucket", "固件"));
    }

    /** 缺少同键内部位置不能跳过剩余版本，返回原位置也不能导致无限重扫。 */
    @Test
    void rejectsMissingSameKeyPositionAndNonProgress() {
        var first = new VersionedInventoryCursor.Position(null, null);
        rejected(() -> VersionedInventoryCursor.next("versions", "bucket", "key", first, "key", null));
        rejected(() -> VersionedInventoryCursor.next("multipart", "bucket", "key", first, "key", ""));
        rejected(() -> VersionedInventoryCursor.next("versions", "bucket", "key",
                new VersionedInventoryCursor.Position("key", "id"), "key", "id"));
        rejected(() -> VersionedInventoryCursor.next("versions", "bucket", "key", first, "other", "id"));
    }

    /** 前缀相邻键页仍可推进，不能把已过滤空页错误折叠为完成。 */
    @Test
    void preservesNeighborPageContinuation() {
        var first = new VersionedInventoryCursor.Position(null, null);
        String cursor = VersionedInventoryCursor.next("versions", "bucket", "key", first, "key-neighbor", null);
        assertThat(VersionedInventoryCursor.decode(cursor, "versions", "bucket", "key"))
                .isEqualTo(new VersionedInventoryCursor.Position("key-neighbor", null));
    }

    /** 已验证供应商可用空键与有效上传ID推进，不得脑补键或丢弃上传位置。 */
    @Test
    void preservesEmptyProviderKeyWithUploadPosition() {
        String cursor = VersionedInventoryCursor.next("multipart", "bucket", "key",
                new VersionedInventoryCursor.Position(null, null), "", "upload-id");
        assertThat(VersionedInventoryCursor.decode(cursor, "multipart", "bucket", "key"))
                .isEqualTo(new VersionedInventoryCursor.Position("", "upload-id"));
        rejected(() -> VersionedInventoryCursor.next("multipart", "bucket", "key",
                new VersionedInventoryCursor.Position(null, null), "", null));
    }

    /** 游标畸形、尾随数据及超预算文本必须拒绝，不进行不完整解码。 */
    @Test
    void rejectsMalformedTrailingAndOversizedInput() {
        rejected(() -> VersionedInventoryCursor.decode("!", "versions", "bucket", "key"));
        rejected(() -> VersionedInventoryCursor.decode("A".repeat(16_385), "versions", "bucket", "key"));
        String cursor = VersionedInventoryCursor.next("versions", "bucket", "key",
                new VersionedInventoryCursor.Position(null, null), "key", "id");
        byte[] bytes = Base64.getUrlDecoder().decode(cursor);
        String trailing = Base64.getUrlEncoder().withoutPadding().encodeToString(java.util.Arrays.copyOf(bytes, bytes.length + 1));
        rejected(() -> VersionedInventoryCursor.decode(trailing, "versions", "bucket", "key"));
    }

    /** 内部安全异常不暴露原始供应商内容。 */
    private static void rejected(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOf(VersionedStorageException.class);
    }
}
