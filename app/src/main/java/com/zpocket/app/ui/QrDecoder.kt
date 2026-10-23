package com.zpocket.app.ui

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.InvertedLuminanceSource
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer

/** 纯解码逻辑（不依赖 Android），方便 JVM 单元测试。 */
object QrDecoder {

    private val hints = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.TRY_HARDER to true,
    )
    private val reader = MultiFormatReader()

    /**
     * 依次尝试四个方向解码；全部失败再用反色重试一遍四个方向
     * （桌面端深色主题下二维码可能是白码黑底的反色码）。
     * 解码成功返回内容，失败返回 null。
     *
     * 注意：旋转必须作用在 LuminanceSource 上再二值化 ——
     * HybridBinarizer 生成的 BinaryBitmap 不支持旋转（会抛 UnsupportedOperationException）。
     */
    fun decode(source: LuminanceSource): String? {
        var result = tryAllOrientations(source)
        if (result == null) {
            result = tryAllOrientations(InvertedLuminanceSource(source))
        }
        return result
    }

    private fun tryAllOrientations(source: LuminanceSource): String? {
        var s = source
        repeat(4) {
            try {
                return reader.decode(BinaryBitmap(HybridBinarizer(s)), hints).text
            } catch (_: Exception) {
                s = rotateCounterClockwise(s)
            }
        }
        return null
    }

    /** ZXing 多数亮度源不支持旋转（会抛 UnsupportedOperationException），这里手动转 90°。 */
    private fun rotateCounterClockwise(source: LuminanceSource): LuminanceSource {
        if (source.isRotateSupported) {
            return source.rotateCounterClockwise()
        }
        val w = source.width
        val h = source.height
        val rotated = ByteArray(w * h)
        val row = ByteArray(w)
        for (y in 0 until h) {
            source.getRow(y, row)
            for (x in 0 until w) {
                // 逆时针 90°：(x, y) -> (y, w-1-x)，新图宽 h 高 w
                rotated[(w - 1 - x) * h + y] = row[x]
            }
        }
        return PlanarYUVLuminanceSource(rotated, h, w, 0, 0, h, w, false)
    }
}
