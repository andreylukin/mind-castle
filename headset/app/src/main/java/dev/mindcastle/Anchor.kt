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
