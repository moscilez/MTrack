package com.moscilez.track.transfer.crypto

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.zip.CRC32
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class WrongPasswordException : Exception("Wrong password or corrupted data")

/**
 * Password-based AES-256-GCM. Keys are derived per export with PBKDF2-HMAC-SHA256 and a
 * random salt; nothing is hard-coded. GCM's tag authenticates every encrypted entry.
 */
object PasswordCrypto {
    const val ITERATIONS = 120_000
    const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private val random = SecureRandom()

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { random.nextBytes(it) }

    fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int = ITERATIONS): SecretKey {
        val spec = PBEKeySpec(password, salt, iterations, 256)
        try {
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return SecretKeySpec(bytes, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    /** Output layout: iv(12) || ciphertext+tag. */
    fun encrypt(key: SecretKey, plain: ByteArray, aad: ByteArray? = null): ByteArray {
        val iv = randomBytes(IV_BYTES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        aad?.let { cipher.updateAAD(it) }
        return iv + cipher.doFinal(plain)
    }

    fun decrypt(key: SecretKey, data: ByteArray, aad: ByteArray? = null): ByteArray {
        if (data.size < IV_BYTES + TAG_BITS / 8) throw WrongPasswordException()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, data, 0, IV_BYTES))
        aad?.let { cipher.updateAAD(it) }
        return try {
            cipher.doFinal(data, IV_BYTES, data.size - IV_BYTES)
        } catch (e: AEADBadTagException) {
            throw WrongPasswordException()
        }
    }
}

object Checksums {
    fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    fun sha256Hex(bytes: ByteArray): String = sha256(bytes).toHex()

    fun crc32Hex(bytes: ByteArray): String = CRC32().apply { update(bytes) }.value.toString(16).padStart(8, '0')

    fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
