package com.moscilez.track.transfer.qr

import com.moscilez.track.transfer.crypto.Checksums
import com.moscilez.track.transfer.crypto.PasswordCrypto
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

enum class OfferResult { ACCEPTED, DUPLICATE, FOREIGN, CORRUPT, INVALID }

/**
 * QR transfer protocol (pure JVM, unit-tested).
 *
 * Blob  = "MTQ" | version(1) | salt(16) | iterations(4) | iv‖ciphertext (AES-256-GCM over gzip(JSON)).
 * Frame = "MTQR1|<tid>|<index>|<total>|<crc32>|<base64 chunk>", where tid is the first 16 hex chars
 * of SHA-256(blob). Integrity: CRC32 per frame, SHA-256 prefix per package, GCM tag on content.
 */
object QrCodec {
    const val PREFIX = "MTQR1"
    const val CHUNK_CHARS = 720
    const val MAX_FRAMES = 400
    private val MAGIC = byteArrayOf('M'.code.toByte(), 'T'.code.toByte(), 'Q'.code.toByte())
    private const val BLOB_VERSION: Byte = 1
    private const val CODE_ITERATIONS = 150_000
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    const val CODE_LENGTH = 10
    private val random = SecureRandom()

    /** One-time transfer code (50 bits, Crockford base32). Shown on the sender, typed on the receiver. */
    fun generateCode(): String = buildString { repeat(CODE_LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) } }

    fun formatCode(code: String): String = if (code.length == CODE_LENGTH) code.substring(0, 5) + "-" + code.substring(5) else code

    /** Accepts lowercase, dashes, spaces and confusable letters. Returns null when invalid. */
    fun normalizeCode(input: String): String? {
        val c = input.uppercase().filter { !it.isWhitespace() && it != '-' }
            .map { when (it) { 'O' -> '0'; 'I', 'L' -> '1'; 'U' -> 'V'; else -> it } }.joinToString("")
        return c.takeIf { it.length == CODE_LENGTH && it.all { ch -> ch in ALPHABET } }
    }

    fun gzip(data: ByteArray): ByteArray = ByteArrayOutputStream().also { bos -> GZIPOutputStream(bos).use { it.write(data) } }.toByteArray()

    fun gunzip(data: ByteArray, limit: Int = 64 * 1024 * 1024): ByteArray {
        GZIPInputStream(ByteArrayInputStream(data)).use { input ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > limit) throw IllegalStateException("payload too large")
            }
            return out.toByteArray()
        }
    }

    fun pack(plain: ByteArray, code: String, iterations: Int = CODE_ITERATIONS): ByteArray {
        val salt = PasswordCrypto.randomBytes(16)
        val key = PasswordCrypto.deriveKey(code.toCharArray(), salt, iterations)
        val enc = PasswordCrypto.encrypt(key, gzip(plain), MAGIC)
        return ByteBuffer.allocate(3 + 1 + 16 + 4 + enc.size)
            .put(MAGIC).put(BLOB_VERSION).put(salt).putInt(iterations).put(enc).array()
    }

    /** @throws com.moscilez.track.transfer.crypto.WrongPasswordException on a wrong code. */
    fun unpack(blob: ByteArray, code: String): ByteArray {
        require(blob.size > 24 && blob.copyOfRange(0, 3).contentEquals(MAGIC)) { "not an MTrack QR package" }
        require(blob[3] == BLOB_VERSION) { "unsupported QR version" }
        val buf = ByteBuffer.wrap(blob)
        buf.position(4)
        val salt = ByteArray(16).also { buf.get(it) }
        val iterations = buf.int
        require(iterations in 10_000..2_000_000) { "bad iterations" }
        val enc = ByteArray(buf.remaining()).also { buf.get(it) }
        val key = PasswordCrypto.deriveKey(code.toCharArray(), salt, iterations)
        return gunzip(PasswordCrypto.decrypt(key, enc, MAGIC))
    }

    fun transferId(blob: ByteArray): String = Checksums.sha256Hex(blob).take(16)

    fun frames(blob: ByteArray, chunkChars: Int = CHUNK_CHARS): List<String> {
        val tid = transferId(blob)
        val b64 = Base64.getEncoder().encodeToString(blob)
        val chunks = b64.chunked(chunkChars)
        return chunks.mapIndexed { i, chunk ->
            "$PREFIX|$tid|$i|${chunks.size}|${Checksums.crc32Hex(chunk.toByteArray())}|$chunk"
        }
    }

    data class Frame(val tid: String, val index: Int, val total: Int, val data: String)

    /** Parses and validates one scanned frame; null when it is not a well-formed MTrack frame. */
    fun parseFrame(text: String): Pair<Frame?, OfferResult> {
        val parts = text.split('|')
        if (parts.size != 6 || parts[0] != PREFIX) return null to OfferResult.INVALID
        val tid = parts[1]
        val index = parts[2].toIntOrNull()
        val total = parts[3].toIntOrNull()
        if (tid.length != 16 || index == null || total == null || total !in 1..10_000 || index !in 0 until total) {
            return null to OfferResult.INVALID
        }
        if (Checksums.crc32Hex(parts[5].toByteArray()) != parts[4]) return null to OfferResult.CORRUPT
        return Frame(tid, index, total, parts[5]) to OfferResult.ACCEPTED
    }
}

/** Collects frames in any order, tolerates duplicates and rejects frames of other transfers. */
class QrAssembler {
    var transferId: String? = null
        private set
    var total: Int = 0
        private set
    private var parts: Array<String?> = emptyArray()
    private val raw = ArrayList<String>()

    val received: Int get() = parts.count { it != null }
    val isComplete: Boolean get() = total > 0 && received == total
    val progress: Float get() = if (total == 0) 0f else received.toFloat() / total
    val rawFrames: List<String> get() = raw.toList()

    fun missing(): List<Int> = parts.indices.filter { parts[it] == null }

    fun offer(text: String): OfferResult {
        val (frame, status) = QrCodec.parseFrame(text)
        if (frame == null) return status
        val tid = transferId
        if (tid == null) {
            transferId = frame.tid
            total = frame.total
            parts = arrayOfNulls(frame.total)
        } else if (tid != frame.tid || total != frame.total) {
            return OfferResult.FOREIGN
        }
        if (parts[frame.index] != null) return OfferResult.DUPLICATE
        parts[frame.index] = frame.data
        raw += text
        return OfferResult.ACCEPTED
    }

    fun restore(frames: List<String>) = frames.forEach { offer(it) }

    fun reset() {
        transferId = null; total = 0; parts = emptyArray(); raw.clear()
    }

    /** Joins chunks and verifies the package checksum. */
    fun assemble(): ByteArray {
        check(isComplete) { "incomplete" }
        val blob = Base64.getDecoder().decode(parts.joinToString("") { it!! })
        if (QrCodec.transferId(blob) != transferId) throw IllegalStateException("checksum mismatch")
        return blob
    }
}
