package com.zpocket.app

import com.google.zxing.BarcodeFormat
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.qrcode.QRCodeWriter
import com.zpocket.app.ui.QrDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class QrDecoderTest {

    private val qrUrl =
        "https://zcode.z.ai/remote/v3?sid=d_UD2bAxg9WYPYkNBj2Wpc5Y&mid=41051792034139348&name=MyMac&app_version=1.0.0"

    /** 用 ZXing 生成二维码点阵，转成亮度字节数组（true=黑=0，false=白=255）。 */
    private fun luminanceOf(text: String, invertColors: Boolean): Triple<ByteArray, Int, Int> {
        val matrix = QRCodeWriter().encode(
            text, BarcodeFormat.QR_CODE, 210, 210,
            mapOf(com.google.zxing.EncodeHintType.MARGIN to 4),
        )
        val w = matrix.width
        val h = matrix.height
        val lum = ByteArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val black = matrix[x, y]
                lum[y * w + x] = when {
                    black && !invertColors -> 0
                    !black && invertColors -> 0
                    else -> 255.toByte()
                }
            }
        }
        return Triple(lum, w, h)
    }

    private fun decode(lum: ByteArray, w: Int, h: Int): String? =
        QrDecoder.decode(PlanarYUVLuminanceSource(lum, w, h, 0, 0, w, h, false))

    @Test
    fun decodesNormalQr() {
        val (lum, w, h) = luminanceOf(qrUrl, invertColors = false)
        assertEquals(qrUrl, decode(lum, w, h))
    }

    @Test
    fun decodesInvertedQr_darkThemeQrCode() {
        // 桌面端深色主题：白码黑底
        val (lum, w, h) = luminanceOf(qrUrl, invertColors = true)
        assertEquals(qrUrl, decode(lum, w, h))
    }

    @Test
    fun decodesRotatedQr() {
        // 模拟相机方向不符：把图像旋转 90°（宽高互换）后应仍能解出
        val (lum, w, h) = luminanceOf(qrUrl, invertColors = false)
        val rotated = ByteArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                // 顺时针旋转 90°：new(x', y') = (h-1-y, x)
                rotated[x * h + (h - 1 - y)] = lum[y * w + x]
            }
        }
        assertEquals(qrUrl, decode(rotated, h, w))
    }

    @Test
    fun decodesWithPreRotation() {
        // 已知帧方向时先预旋转（3 次逆时针 == 1 次顺时针），首次尝试即应解出
        val (lum, w, h) = luminanceOf(qrUrl, invertColors = false)
        val rotated = ByteArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                rotated[x * h + (h - 1 - y)] = lum[y * w + x]
            }
        }
        val source = PlanarYUVLuminanceSource(rotated, h, w, 0, 0, h, w, false)
        assertEquals(qrUrl, QrDecoder.decode(source, preRotations = 3))
    }

    @Test
    fun decodesInvertedWithPreRotation() {
        // 反色 + 方向不符的组合也要能解出
        val (lum, w, h) = luminanceOf(qrUrl, invertColors = true)
        val rotated = ByteArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                rotated[x * h + (h - 1 - y)] = lum[y * w + x]
            }
        }
        val source = PlanarYUVLuminanceSource(rotated, h, w, 0, 0, h, w, false)
        assertEquals(qrUrl, QrDecoder.decode(source, preRotations = 3))
    }

    @Test
    fun returnsNullForBlankImage() {
        val lum = ByteArray(100 * 100) { 255.toByte() }
        assertNull(decode(lum, 100, 100))
    }

    @Test
    fun decodesShortText() {
        val (lum, w, h) = luminanceOf("hello-zpocket", invertColors = false)
        assertNotNull(decode(lum, w, h))
    }
}
