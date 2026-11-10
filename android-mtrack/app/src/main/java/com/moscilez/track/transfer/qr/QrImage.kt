package com.moscilez.track.transfer.qr

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

object QrImage {
    /** Renders text into a black-on-white QR bitmap (1 px per module, scale in UI with no filtering). */
    fun render(text: String): Bitmap {
        val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L, EncodeHintType.MARGIN to 2)
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
        val w = m.width
        val h = m.height
        val px = IntArray(w * h) { i -> if (m.get(i % w, i / w)) -0x1000000 else -0x1 }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    private val reader = QRCodeReader()
    private val decodeHints = mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.CHARACTER_SET to "UTF-8")

    /** Decodes a luminance plane (camera Y plane). Returns null when no QR code is found. */
    fun decode(luma: ByteArray, rowStride: Int, width: Int, height: Int): String? = try {
        val src = PlanarYUVLuminanceSource(luma, rowStride, height, 0, 0, width, height, false)
        reader.decode(BinaryBitmap(HybridBinarizer(src)), decodeHints).text
    } catch (_: NotFoundException) {
        null
    } catch (_: Exception) {
        null
    } finally {
        reader.reset()
    }
}
