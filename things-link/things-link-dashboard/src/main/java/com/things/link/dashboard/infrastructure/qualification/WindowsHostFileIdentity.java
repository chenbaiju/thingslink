package com.things.link.dashboard.infrastructure.qualification;

import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinNT;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HexFormat;

/** Windows受管文件的卷号和128位文件ID；无法读取时拒绝宿主资格。 */
final class WindowsHostFileIdentity {
    private WindowsHostFileIdentity() { }

    static Identity read(Path path) throws IOException {
        try {
            WinNT.HANDLE handle = Kernel32.INSTANCE.CreateFile(path.toString(), WinNT.FILE_READ_ATTRIBUTES,
                    WinNT.FILE_SHARE_READ | WinNT.FILE_SHARE_WRITE | WinNT.FILE_SHARE_DELETE,
                    null, WinNT.OPEN_EXISTING,
                    WinNT.FILE_FLAG_OPEN_REPARSE_POINT | WinNT.FILE_FLAG_BACKUP_SEMANTICS, null);
            if (handle == null || WinBase.INVALID_HANDLE_VALUE.equals(handle)) throw new IOException("Windows file identity unavailable");
            try {
                WinBase.FILE_ID_INFO info = new WinBase.FILE_ID_INFO();
                if (!Kernel32.INSTANCE.GetFileInformationByHandleEx(handle, WinBase.FileIdInfo,
                        info.getPointer(), new WinDef.DWORD(info.size()))) {
                    throw new IOException("Windows file identity unavailable");
                }
                info.read();
                byte[] id = new byte[info.FileId.Identifier.length];
                for (int index = 0; index < id.length; index++) id[index] = info.FileId.Identifier[index].byteValue();
                return new Identity(info.VolumeSerialNumber, HexFormat.of().formatHex(id));
            } finally {
                Kernel32.INSTANCE.CloseHandle(handle);
            }
        } catch (LinkageError failure) {
            throw new IOException("Windows file identity provider unavailable", failure);
        }
    }

    record Identity(long volumeSerialNumber, String fileId) { }
}
