package dev.mindcastle

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.tan

/**
 * Opaque black shell around the user (passthrough stays on underneath), with a window left open
 * where the laptop is: below the user's start forward direction. Angles in degrees.
 */
object Shell {
    const val RADIUS_M = 3f
    const val STRIPS = 36 // 10° each
    const val OVERLAP = 1f // one mesh: neighbouring flat strips share their edges exactly (overlap would double alpha when dimmed)
    const val PITCH_BOTTOM = -70f
    const val PITCH_TOP = 60f
    const val WINDOW_HALF_YAW = 25f // laptop window spans forward ± this...
    const val WINDOW_PITCH_TOP = -15f // ...from PITCH_BOTTOM up to here
    const val CAP_MARGIN = 1.1f // caps overhang the cylinder by this factor

    private fun rad(deg: Float) = (deg * PI / 180).toFloat()

    /** A horizontal cap at height [y] dp (relative to center), [side] dp square; [up] = faces up. */
    data class Cap(val y: Float, val side: Float, val up: Boolean)

    /**
     * Vertical strips on the cylinder, as Panels (theta/y/r/w/h, facing the axis). The laptop cutout
     * (forward ± [cutHalfYaw]) is tiled with its own whole strips that start at [cutTop] instead of
     * PITCH_BOTTOM; the rest of the ring is tiled with strips of at most 360/STRIPS degrees.
     */
    fun strips(
        dpPerMeter: Float, forwardTheta: Float,
        cutHalfYaw: Float = WINDOW_HALF_YAW, cutTop: Float = WINDOW_PITCH_TOP,
    ): List<Panel> {
        val r = RADIUS_M * dpPerMeter
        val maxStep = 360f / STRIPS
        val top = r * tan(rad(PITCH_TOP))
        fun span(from: Float, width: Float, fromPitch: Float): List<Panel> {
            if (width <= 0f) return emptyList()
            val n = ceil(width / maxStep - 1e-4f).toInt()
            val step = width / n
            val w = 2 * r * tan(rad(step / 2)) * OVERLAP
            val bottom = r * tan(rad(fromPitch))
            return (0 until n).map { i -> Panel(forwardTheta + rad(from + step * (i + 0.5f)), (bottom + top) / 2, r, w, top - bottom) }
        }
        return span(-cutHalfYaw, 2 * cutHalfYaw, cutTop) + span(cutHalfYaw, 360f - 2 * cutHalfYaw, PITCH_BOTTOM)
    }

    /** Everything the shell's geometry depends on (rebuild the mesh when it changes; alpha is separate). */
    data class Geometry(val dpPerMeter: Float, val center: Vec3, val forward: Float, val cutHalfYaw: Float, val cutTop: Float)

    /** Triangle mesh in meters, in the Subspace-origin frame: positions xyz…, 32-bit indices (the material is double-sided). */
    class Mesh(val positions: FloatArray, val indices: IntArray) {
        val min get() = FloatArray(3) { a -> (a until positions.size step 3).minOf { positions[it] } }
        val max get() = FloatArray(3) { a -> (a until positions.size step 3).maxOf { positions[it] } }
    }

    /**
     * The whole shell (strips + caps) as one mesh, so it costs one entity instead of ~38 panels — panels
     * each get a system input channel that the platform never frees.
     */
    fun mesh(g: Geometry): Mesh {
        val pos = ArrayList<Float>(); val idx = ArrayList<Int>()
        val k = 1f / g.dpPerMeter
        fun quad(a: Vec3, b: Vec3, c: Vec3, d: Vec3) {
            val base = pos.size / 3
            for (v in listOf(a, b, c, d)) { val m = (g.center + v) * k; pos += m.x; pos += m.y; pos += m.z }
            idx += listOf(base, base + 1, base + 2, base, base + 2, base + 3)
        }
        for (s in strips(g.dpPerMeter, g.forward, g.cutHalfYaw, g.cutTop)) {
            val c = Vec3(s.r * kotlin.math.sin(s.theta), s.y, -s.r * kotlin.math.cos(s.theta))
            val rx = s.right * (s.w / 2); val uy = Vec3(0f, s.h / 2, 0f)
            quad(c - rx - uy, c + rx - uy, c + rx + uy, c - rx + uy)
        }
        for (cap in caps(g.dpPerMeter)) {
            val h = cap.side / 2
            quad(Vec3(-h, cap.y, -h), Vec3(h, cap.y, -h), Vec3(h, cap.y, h), Vec3(-h, cap.y, h))
        }
        return Mesh(pos.toFloatArray(), idx.toIntArray())
    }

    /** Whether direction [dir] (from the user, layout frame) looks through the laptop cutout. */
    fun inCutout(dir: Vec3, forward: Float, cutHalfYaw: Float, cutTop: Float): Boolean {
        val yaw = Math.toDegrees(wrapAngle(kotlin.math.atan2(dir.x, -dir.z) - forward).toDouble())
        val elev = Math.toDegrees(kotlin.math.atan2(dir.y, kotlin.math.hypot(dir.x, dir.z)).toDouble())
        return kotlin.math.abs(yaw) < cutHalfYaw && elev < cutTop
    }

    fun caps(dpPerMeter: Float): List<Cap> {
        val r = RADIUS_M * dpPerMeter
        val side = 2 * r * CAP_MARGIN
        return listOf(Cap(r * tan(rad(PITCH_TOP)), side, up = false), Cap(r * tan(rad(PITCH_BOTTOM)), side, up = true))
    }
}
