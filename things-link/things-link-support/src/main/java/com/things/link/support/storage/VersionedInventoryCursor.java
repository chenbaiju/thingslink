package com.things.link.support.storage;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.Objects;

/** 有长度帧的版本化游标，精确绑定盘点类别、桶和对象键；不是业务授权凭据。 */
final class VersionedInventoryCursor {
    /** Base64文本硬预算，限制外部输入内存和解码成本。 */
    private static final int MAX_LENGTH = 16_384;
    /** 工具类不允许实例化。 */
    private VersionedInventoryCursor() { }
    /**
     * provider的双字段恢复位置。
     * @param keyMarker provider返回的对象键位置
     * @param idMarker 精确版本或multipart位置，可为空
     */
    record Position(String keyMarker, String idMarker) { }
    /** 缺省游标表示首页，非空游标必须与本次范围精确一致。 */
    static Position decode(String cursor, String kind, String bucket, String objectKey) {
        if (cursor == null) { return new Position(null, null); }
        try {
            if (cursor.isEmpty() || cursor.length() > MAX_LENGTH) { throw new IOException(); }
            byte[] data = Base64.getUrlDecoder().decode(cursor);
            try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(data))) {
                if (input.readInt() != 1 || !kind.equals(input.readUTF()) || !bucket.equals(input.readUTF())
                        || !objectKey.equals(input.readUTF())) { throw new IOException(); }
                Position result = new Position(input.readUTF(), input.readBoolean() ? input.readUTF() : null);
                if (input.available() != 0 || !result.keyMarker().isEmpty()
                        && !result.keyMarker().startsWith(objectKey)
                        || result.keyMarker().isEmpty() && (result.idMarker() == null || result.idMarker().isBlank())
                        || objectKey.equals(result.keyMarker())
                        && (result.idMarker() == null || result.idMarker().isBlank())) { throw new IOException(); }
                return result;
            }
        } catch (IOException | IllegalArgumentException exception) {
            throw new VersionedStorageException(VersionedStorageException.Reason.CONFIGURATION, false);
        }
    }
    /** 验证provider推进后生成游标，禁止重复位置造成调用方无限空转。 */
    static String next(String kind, String bucket, String objectKey, Position previous,
                       String keyMarker, String idMarker) {
        if (keyMarker == null || !keyMarker.isEmpty() && !keyMarker.startsWith(objectKey)
                || keyMarker.isEmpty() && (idMarker == null || idMarker.isBlank())
                || objectKey.equals(keyMarker) && (idMarker == null || idMarker.isBlank())
                || Objects.equals(previous.keyMarker(), keyMarker) && Objects.equals(previous.idMarker(), idMarker)) {
            throw new VersionedStorageException(VersionedStorageException.Reason.INTEGRITY, false);
        }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeInt(1);
                output.writeUTF(kind);
                output.writeUTF(bucket);
                output.writeUTF(objectKey);
                output.writeUTF(keyMarker);
                output.writeBoolean(idMarker != null);
                if (idMarker != null) { output.writeUTF(idMarker); }
            }
            String result = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray());
            if (result.length() > MAX_LENGTH) { throw new IOException(); }
            return result;
        } catch (IOException exception) {
            throw new VersionedStorageException(VersionedStorageException.Reason.INTEGRITY, false);
        }
    }
}
