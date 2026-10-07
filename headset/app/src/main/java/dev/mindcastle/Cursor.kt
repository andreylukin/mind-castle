package dev.mindcastle

import java.nio.ByteBuffer

/** PROTOCOL v3 CURSOR header: hotspot in image pixels, logical size in points. PNG bytes follow at [SIZE]. */
data class CursorHeader(val hotX: Int, val hotY: Int, val ptsW: Int, val ptsH: Int) {
    companion object {
        const val SIZE = 8
        fun parse(p: ByteArray): CursorHeader = ByteBuffer.wrap(p, 0, SIZE).run {
            CursorHeader(short.toInt() and 0xFFFF, short.toInt() and 0xFFFF, short.toInt() and 0xFFFF, short.toInt() and 0xFFFF)
        }
    }

    /**
     * Size (dp) of the cursor image drawn at [dpPerPoint], and the offset (plane coords, v up) of its
     * center from the pointer so the hotspot lands on the pointer. Image is [imgW]×[imgH] pixels.
     */
    fun placement(imgW: Int, imgH: Int, dpPerPoint: Float): Placement {
        val w = ptsW * dpPerPoint; val h = ptsH * dpPerPoint
        val hx = hotX.toFloat() / imgW * w; val hy = hotY.toFloat() / imgH * h
        return Placement(w / 2 - hx, -(h / 2 - hy), w, h)
    }
}

data class Placement(val du: Float, val dv: Float, val w: Float, val h: Float)
