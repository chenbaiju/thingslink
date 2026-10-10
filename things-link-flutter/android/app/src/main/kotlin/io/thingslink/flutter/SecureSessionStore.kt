package io.thingslink.flutter

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** 会话快照使用系统密钥加密，文件与目录同步完成后才允许后续刷新请求。 */
class SecureSessionStore(private val context: Context) {
    companion object {
        private val lock = Any()
        private const val MAX_BYTES = 1_048_576
    }

    private fun file(key: String): File {
        require(key.matches(Regex("[a-zA-Z0-9._-]{1,80}")))
        return File(context.noBackupFilesDir, "thingsx-$key.bin")
    }

    private fun secret(key: String, create: Boolean): SecretKey {
        val alias = "${context.packageName}.session.$key"
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getKey(alias, null)
        if (existing != null) return existing as SecretKey
        check(create) { "会话密钥不可用" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build())
            generateKey()
        }
    }

    fun read(key: String): String? = synchronized(lock) {
        val target = file(key)
        if (!target.exists()) return@synchronized null
        check(target.length() in 29L..(MAX_BYTES + 29L)) { "会话快照长度无效" }
        val payload = target.readBytes()
        check(payload[0] == 1.toByte()) { "会话快照版本无效" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secret(key, false), GCMParameterSpec(128, payload.copyOfRange(1, 13)))
        cipher.updateAAD("thingsx-session-v1:$key".toByteArray(Charsets.UTF_8))
        cipher.doFinal(payload.copyOfRange(13, payload.size)).toString(Charsets.UTF_8)
    }

    fun write(key: String, value: String) = synchronized(lock) {
        val target = file(key)
        val plain = value.toByteArray(Charsets.UTF_8)
        require(plain.size <= MAX_BYTES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secret(key, true))
        cipher.updateAAD("thingsx-session-v1:$key".toByteArray(Charsets.UTF_8))
        check(cipher.iv.size == 12)
        val payload = byteArrayOf(1) + cipher.iv + cipher.doFinal(plain)
        val temporary = File(target.parentFile, "${target.name}.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(payload)
                output.fd.sync()
            }
            // 同目录原子替换：中断时只读取完整旧版或完整新版，不读取临时文件。
            Os.rename(temporary.absolutePath, target.absolutePath)
            val directory = Os.open(target.parentFile!!.absolutePath, OsConstants.O_RDONLY, 0)
            try { Os.fsync(directory) } finally { Os.close(directory) }
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }
}
