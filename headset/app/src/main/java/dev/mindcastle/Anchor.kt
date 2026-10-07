package dev.mindcastle

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sign
import kotlin.math.sin

/**
 * The frame the whole layout lives in: the head's pose at the last recenter. [pos] is in Subspace dp;
 * the rotation is yaw about Y, then pitch about X, then roll about Z ([yawDeg]/[pitchDeg]/[rollDeg],
 * right-handed, degrees). Inside this frame the user sits at the origin looking down -z, so Desk's
 * cylinder math is unchanged; lying down just tilts the frame (cylinder axis = head up at recenter).
 */
data class Anchor(val pos: Vec3 = Vec3(0f, 0f, 0f), val yawDeg: Float = 0f, val pitchDeg: Float = 0f, val rollDeg: Float = 0f) {
    companion object {
        /** Ordinary sitting posture tilts the head a little: below this the world stays level. */
        const val PITCH_SNAP_DEG = 15f
        /** Roll only counts when it's large (lying on a side), not a casual head tilt. */
        const val ROLL_MIN_DEG = 45f

        /**
         * Anchor at the head: [forward]/[up] are the head's -z / +y axes in Subspace coordinates.
         * Level-ish heads (|pitch| < PITCH_SNAP_DEG) get a level frame (yaw only, roll only past
         * ROLL_MIN_DEG). A pitched head (lying down) gets its exact orientation, so the layout's forward
         * is the gaze and its up is the head's up — including when looking straight up, where a heading
         * taken from forward's horizontal part would be meaningless.
         */
        fun fromHead(pos: Vec3, forward: Vec3, up: Vec3): Anchor {
            val p = asin(forward.y.coerceIn(-1f, 1f))
            val pitch = deg(p)
            // Exact R = Ry(yaw)·Rx(pitch)·Rz(roll) decomposition; yaw from the gaze unless it's (numerically) vertical.
            val yaw = if (cos(p) > 1e-3f) deg(atan2(-forward.x, -forward.z)) + 0f // +0f: no -0.0
            else deg(atan2(up.x, up.z) * sign(forward.y)) + 0f
            val r0 = Anchor(pos, yaw, pitch, 0f)
            val roll = deg(atan2(-(up dot r0.rotate(Vec3(1f, 0f, 0f))), up dot r0.rotate(Vec3(0f, 1f, 0f))))
            if (abs(pitch) >= PITCH_SNAP_DEG) return Anchor(pos, yaw, pitch, roll)
            return Anchor(pos, yaw, 0f, if (abs(roll) < ROLL_MIN_DEG) 0f else roll)
        }

        private fun deg(r: Float) = Math.toDegrees(r.toDouble()).toFloat()
        private fun rad(d: Float) = Math.toRadians(d.toDouble()).toFloat()
        private fun rotX(v: Vec3, a: Float) = Vec3(v.x, v.y * cos(a) - v.z * sin(a), v.y * sin(a) + v.z * cos(a))
        private fun rotY(v: Vec3, a: Float) = Vec3(v.x * cos(a) + v.z * sin(a), v.y, -v.x * sin(a) + v.z * cos(a))
        private fun rotZ(v: Vec3, a: Float) = Vec3(v.x * cos(a) - v.y * sin(a), v.x * sin(a) + v.y * cos(a), v.z)
    }

    /** Rotate a local direction into Subspace coordinates. */
    fun rotate(v: Vec3): Vec3 = rotY(rotX(rotZ(v, rad(rollDeg)), rad(pitchDeg)), rad(yawDeg))

    /** Local layout point -> Subspace dp. */
    fun toWorld(v: Vec3): Vec3 = pos + rotate(v)
}

/**
 * A thin strip from [from] to [to] (Subspace dp) as a panel pose: its center, its length, and the
 * rotation (x, y, z, w) taking the panel's +y onto the strip and its face toward [eye].
 */
fun strip(from: Vec3, to: Vec3, eye: Vec3): Triple<Vec3, Float, FloatArray> {
    val d = to - from
    val len = kotlin.math.sqrt(d dot d)
    val mid = from + d * 0.5f
    if (len < 1e-3f) return Triple(mid, 0f, floatArrayOf(0f, 0f, 0f, 1f))
    val y = d * (1f / len)
    fun cross(a: Vec3, b: Vec3) = Vec3(a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x)
    fun norm(v: Vec3) = v * (1f / kotlin.math.sqrt(v dot v))
    var toEye = eye - mid
    if (abs(norm(toEye) dot y) > 0.999f) toEye = Vec3(0f, 0f, 1f)
    val x = norm(cross(y, toEye))
    val z = cross(x, y)
    // Rotation matrix with columns x, y, z -> quaternion.
    val m00 = x.x; val m11 = y.y; val m22 = z.z
    val tr = m00 + m11 + m22
    val q = if (tr > 0f) {
        val s = kotlin.math.sqrt(tr + 1f) * 2f
        floatArrayOf((y.z - z.y) / s, (z.x - x.z) / s, (x.y - y.x) / s, 0.25f * s)
    } else if (m00 > m11 && m00 > m22) {
        val s = kotlin.math.sqrt(1f + m00 - m11 - m22) * 2f
        floatArrayOf(0.25f * s, (y.x + x.y) / s, (z.x + x.z) / s, (y.z - z.y) / s)
    } else if (m11 > m22) {
        val s = kotlin.math.sqrt(1f + m11 - m00 - m22) * 2f
        floatArrayOf((y.x + x.y) / s, 0.25f * s, (z.y + y.z) / s, (z.x - x.z) / s)
    } else {
        val s = kotlin.math.sqrt(1f + m22 - m00 - m11) * 2f
        floatArrayOf((z.x + x.z) / s, (z.y + y.z) / s, 0.25f * s, (x.y - y.x) / s)
    }
    return Triple(mid, len, q)
}

/** Where to draw the "it's over there" arrow: [pos] in front of the head, facing it, glyph at [screenAngle]. */
data class OffViewArrow(val pos: Vec3, val yawDeg: Float, val pitchDeg: Float, val screenAngle: Float)

/**
 * If [target] is more than [fovHalfDeg] off the head's gaze, an arrow 1 m ahead, nudged 0.3 m toward the
 * target, whose glyph points at it (screen angle, radians: 0 = right, π/2 = up). Null when it's in view.
 */
fun offViewArrow(head: Ray, target: Vec3, dpPerMeter: Float, fovHalfDeg: Float = 35f): OffViewArrow? {
    fun cross(a: Vec3, b: Vec3) = Vec3(a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x)
    fun norm(v: Vec3) = v * (1f / kotlin.math.sqrt(v dot v))
    val f = norm(head.dir)
    val v = norm(target - head.origin)
    if ((v dot f) >= kotlin.math.cos(Math.toRadians(fovHalfDeg.toDouble())).toFloat()) return null
    val worldUp = if (abs(f.y) > 0.95f) Vec3(0f, 0f, -1f) else Vec3(0f, 1f, 0f)
    val right = norm(cross(f, worldUp)); val up = cross(right, f)
    val ang = atan2(v dot up, v dot right)
    val pos = head.origin + f * dpPerMeter + (right * kotlin.math.cos(ang) + up * kotlin.math.sin(ang)) * (0.3f * dpPerMeter)
    val toHead = head.origin - pos
    val yaw = Math.toDegrees(atan2(-toHead.x, toHead.z).toDouble()).toFloat()
    val pitch = Math.toDegrees(atan2(toHead.y, kotlin.math.hypot(toHead.x, toHead.z)).toDouble()).toFloat()
    return OffViewArrow(pos, yaw, -pitch, ang)
}
