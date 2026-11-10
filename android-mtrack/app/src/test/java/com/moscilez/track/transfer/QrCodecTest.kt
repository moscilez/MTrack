package com.moscilez.track.transfer

import com.moscilez.track.transfer.crypto.Checksums
import com.moscilez.track.transfer.crypto.PasswordCrypto
import com.moscilez.track.transfer.crypto.WrongPasswordException
import com.moscilez.track.transfer.qr.OfferResult
import com.moscilez.track.transfer.qr.QrAssembler
import com.moscilez.track.transfer.qr.QrCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PasswordCryptoTest {
    @Test
    fun roundTripsAndRejectsWrongPassword() {
        val salt = PasswordCrypto.randomBytes(16)
        val key = PasswordCrypto.deriveKey("correct horse".toCharArray(), salt, 10_000)
        val enc = PasswordCrypto.encrypt(key, "hello".toByteArray())
        assertEquals("hello", String(PasswordCrypto.decrypt(key, enc)))
        val wrong = PasswordCrypto.deriveKey("wrong".toCharArray(), salt, 10_000)
        assertThrows { PasswordCrypto.decrypt(wrong, enc) }
    }

    @Test
    fun checksumsAreStable() {
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", Checksums.sha256Hex("hello".toByteArray()))
        assertEquals("3610a686", Checksums.crc32Hex("hello".toByteArray()))
    }

    private fun assertThrows(block: () -> Unit) {
        try {
            block()
        } catch (e: WrongPasswordException) {
            return
        }
        throw AssertionError("expected WrongPasswordException")
    }
}

class QrCodecTest {
    private val code = "AB12CD34EF"

    @Test
    fun normalizesCodeWithConfusables() {
        assertEquals("AB12CD30EF", QrCodec.normalizeCode("ab12c-d3o ef"))
        assertEquals("1111VVVVVV", QrCodec.normalizeCode("IlI1UUUUUU"))
        assertNull(QrCodec.normalizeCode("short"))
        assertEquals("AB12C-D34EF", QrCodec.formatCode(code))
        val gen = QrCodec.generateCode()
        assertEquals(gen, QrCodec.normalizeCode(gen))
    }

    @Test
    fun packUnpackRoundTrip() {
        val plain = """{"sessions":[1,2,3]}""".repeat(50).toByteArray()
        val blob = QrCodec.pack(plain, code, iterations = 10_000)
        assertArrayEquals(plain, QrCodec.unpack(blob, code))
        var failed = false
        try {
            QrCodec.unpack(blob, "ZZZZZZZZZZ")
        } catch (e: WrongPasswordException) {
            failed = true
        }
        assertTrue(failed)
    }

    @Test
    fun framesAssembleInAnyOrderWithDuplicates() {
        val blob = PasswordCrypto.randomBytes(3000)
        val frames = QrCodec.frames(blob, chunkChars = 300)
        assertTrue(frames.size > 5)
        val asm = QrAssembler()
        frames.reversed().forEach { assertEquals(OfferResult.ACCEPTED, asm.offer(it)) }
        assertEquals(OfferResult.DUPLICATE, asm.offer(frames[0]))
        assertTrue(asm.isComplete)
        assertArrayEquals(blob, asm.assemble())
    }

    @Test
    fun rejectsCorruptForeignAndInvalidFrames() {
        val frames = QrCodec.frames(PasswordCrypto.randomBytes(2000), chunkChars = 400)
        val other = QrCodec.frames(PasswordCrypto.randomBytes(2000), chunkChars = 400)
        val asm = QrAssembler()
        asm.offer(frames[0])
        val corrupted = frames[1].dropLast(1) + (if (frames[1].last() == 'A') 'B' else 'A')
        assertEquals(OfferResult.CORRUPT, asm.offer(corrupted))
        assertEquals(OfferResult.FOREIGN, asm.offer(other[1]))
        assertEquals(OfferResult.INVALID, asm.offer("https://example.com"))
        assertFalse(asm.isComplete)
        assertEquals((1 until frames.size).toList(), asm.missing())
    }

    @Test
    fun restoreRebuildsProgressFromCache() {
        val frames = QrCodec.frames(PasswordCrypto.randomBytes(1500), chunkChars = 300)
        val first = QrAssembler()
        frames.take(3).forEach { first.offer(it) }
        val second = QrAssembler()
        second.restore(first.rawFrames)
        assertEquals(3, second.received)
        frames.drop(3).forEach { second.offer(it) }
        assertTrue(second.isComplete)
    }
}
