package tech.tamerin.contextfriendpro

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CameraQrTest {
    @Test fun copiesATightPlane() {
        val raw = ByteArray(6) { it.toByte() }
        assertArrayEquals(raw, lumaPlane(raw, width = 3, height = 2, rowStride = 3, pixelStride = 1))
    }

    @Test fun dropsRowPaddingAndPixelGaps() {
        // 2×2 image, pixelStride 2, rowStride 5 (one byte of padding per row); the last row may be short.
        val raw = byteArrayOf(1, 0, 2, 0, 9, 3, 0, 4)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), lumaPlane(raw, width = 2, height = 2, rowStride = 5, pixelStride = 2))
    }

    @Test fun skipsFramesWhoseStridesDoNotFitTheBuffer() {
        val raw = ByteArray(640 * 480)
        assertNull(lumaPlane(raw, 640, 480, rowStride = 704, pixelStride = 1))   // reads past the end
        assertNull(lumaPlane(raw, 640, 480, rowStride = -229, pixelStride = 1))  // negative stride
        assertNull(lumaPlane(raw, 640, 480, rowStride = 640, pixelStride = 0))
        assertNull(lumaPlane(raw, 0, 480, rowStride = 640, pixelStride = 1))
        assertNull(lumaPlane(raw, 640, 480, rowStride = Int.MAX_VALUE, pixelStride = 1)) // no Int overflow
    }
}
