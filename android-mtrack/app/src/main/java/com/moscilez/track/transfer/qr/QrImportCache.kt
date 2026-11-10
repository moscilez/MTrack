package com.moscilez.track.transfer.qr

import android.content.Context
import com.moscilez.track.transfer.crypto.KeystoreCipher
import java.io.File

/**
 * Persists already-scanned frames so an interrupted transfer can continue. Stored encrypted
 * with a device-bound Keystore key; removed after import or after 7 days by the maintenance job.
 */
class QrImportCache(context: Context) {
    private val dir = File(context.noBackupFilesDir, "qr")
    private val file = File(dir, "pending.bin")
    private val cipher = KeystoreCipher()

    fun save(frames: List<String>) {
        runCatching {
            dir.mkdirs()
            val tmp = File(dir, "pending.tmp")
            tmp.writeBytes(cipher.encrypt(frames.joinToString("\n").toByteArray()))
            tmp.renameTo(file)
        }
    }

    fun load(): List<String> = runCatching {
        if (!file.exists()) emptyList() else String(cipher.decrypt(file.readBytes())).split('\n').filter { it.isNotBlank() }
    }.getOrElse { clear(); emptyList() }

    fun clear() {
        file.delete()
    }

    fun purgeIfOlderThan(cutoff: Long) {
        if (file.exists() && file.lastModified() < cutoff) clear()
    }
}
