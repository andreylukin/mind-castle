package dev.mindcastle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan

/** The device pointer model: 2D on panels (native window mapping), 3D angular in space, detents between. */
class AccelTest {
    private fun rad(d: Float) = Math.toRadians(d.toDouble()).toFloat()
    private val yaw = rad(Accel.YAW_DEG_PER_PT * Accel.SENSITIVITY)
    private val elev = rad(Accel.ELEV_DEG_PER_PT * Accel.SENSITIVITY)

    /** Window 7: 400 dp wide for a 500-point window, so native = 0.8 dp/pt. Cursor starts in space below it. */
    private fun desk(): Desk = Desk(1000f).apply {
        toggle(7, 1000, 500)
        acceleration = true
        control = true
    }

    private fun Desk.swipe(dx: Float, dy: Float, n: Int, t0: Long, dtMs: Long = 9): Long {
        var t = t0
        repeat(n) { t += dtMs; pointer(Pointer(dx = dx / n, dy = dy / n), nowMs = t); frame(t) }
        return t
    }

    /** Park the cursor in empty space well above everything. */
    private fun Desk.toSpace(): Long { var t = 1_000L; repeat(20) { t += 9; pointer(Pointer(dy = -30f), nowMs = t); frame(t) }; return t }

    @Test fun inSpaceMotionIsAngularWithNoAcceleration() {
        val d = desk(); var t = d.toSpace()
        assertFalse(d.onPanel)
        val th0 = d.cursorTheta
        t = d.swipe(50f, 0f, 25, t + 300) // slow
        val slow = d.cursorTheta - th0
        assertEquals(50 * yaw, slow, 1e-5f)
        val th1 = d.cursorTheta
        d.swipe(-50f, 0f, 2, t + 300) // the same distance, ~10× faster: same angle (no headset acceleration)
        assertEquals(-50 * yaw, d.cursorTheta - th1, 1e-5f)
    }

    @Test fun onPanelMotionIsTheWindowsOwnPoints() {
        val d = desk(); val p = d.panels.getValue(7)
        // Into the window (through the entry detent), then measure.
        var t = d.swipe((p.theta - d.cursorTheta) / yaw, 0f, 20, 1_000)
        assertTrue(d.onPanel); assertEquals(7, d.hover?.id)
        val (u0, v0) = d.planeCoords(p)!!
        d.swipe(40f, 25f, 10, t + 300)
        val (u1, v1) = d.planeCoords(p)!!
        assertEquals("1 Mac pt = 1 window pt = 0.8 dp", 40 * 0.8f, u1 - u0, 0.05f)
        assertEquals(-25 * 0.8f, v1 - v0, 0.05f)
    }

    @Test fun leavingAPanelTakesTheDetent() {
        val d = desk(); val p = d.panels.getValue(7)
        var t = d.swipe((p.theta - d.cursorTheta) / yaw, 0f, 20, 1_000)
        // To the right edge of the hit region (content + corner band)...
        val edge = p.w / 2 + Desk.CORNER_OUT
        val toEdge = (edge - d.planeCoords(p)!!.first) / 0.8f
        t = d.swipe(toEdge, 0f, 10, t + 300)
        assertTrue(d.onPanel)
        // ...a push smaller than the detent stays on the panel (none with DETENT_DP = 0)...
        if (Accel.DETENT_DP > 0f) {
            t = d.swipe(Accel.DETENT_DP * 0.6f / 0.8f, 0f, 5, t + 300)
            assertTrue("held by the detent", d.onPanel)
        }
        // ...more goes through, out into space.
        d.swipe((Accel.DETENT_DP + 5f) / 0.8f, 0f, 5, t + 300)
        assertFalse(d.onPanel)
    }

    @Test fun enteringAPanelTakesTheDetent() {
        val d = desk()
        d.moveTo(7, d.position(d.panels.getValue(7).copy(theta = 0.8f))) // window well to the right; cursor at θ 0 is in space
        val p = d.panels.getValue(7)
        assertFalse(d.onPanel)
        // Creep right 2 pt per frame. Once the next step would land on the window, the detent holds the
        // cursor outside until 15 dp of push has built up.
        var t = 1_000L; var held = 0; var steps = 0
        while (!d.onPanel && steps++ < 2000) {
            val wouldHit = d.hit(d.cursorTheta + 2 * yaw, d.cursorT) != null
            val before = d.cursorTheta
            t += 9; d.pointer(Pointer(dx = 2f), nowMs = t); d.frame(t)
            if (wouldHit && d.cursorTheta == before) held++
        }
        assertTrue(d.onPanel)
        assertEquals("held for ceil(DETENT_DP / 2 pt) - 1 frames", maxOf(0, kotlin.math.ceil(Accel.DETENT_DP / 2).toInt() - 1), held)
        assertTrue(d.hover?.id == p.let { 7 })
    }

    @Test fun motionIsAppliedOncePerFrameAndClicksFlushFirst() {
        val d = desk(); val p = d.panels.getValue(7)
        val th0 = d.cursorTheta
        d.pointer(Pointer(dx = 1f), nowMs = 1_000); d.pointer(Pointer(dx = 1f), nowMs = 1_008)
        assertEquals("nothing moves between frames", th0, d.cursorTheta, 0f)
        d.frame(1_010)
        assertTrue(d.cursorTheta != th0)
        // Collect motion onto the window and press within the same frame interval: the press lands on it.
        var t = 2_000L
        val dx = (p.theta - d.cursorTheta) / yaw
        repeat(10) { t += 9; d.pointer(Pointer(dx = dx / 10), nowMs = t); d.frame(t) }
        d.pointer(Pointer(dx = 30f), nowMs = t + 5) // pending, then the press flushes it
        val acts = d.pointer(Pointer(buttons = 1), nowMs = t + 7)
        assertTrue(acts.any { it is Action.Mouse && it.kind == "down" && it.id == 7 })
    }

    @Test fun elevationEdgeFallsOffOutwardOnlyAndNothingIsBanked() {
        val d = desk()
        val (_, hi) = d.elevationBand()
        var t = 1_000L
        repeat(400) { t += 9; d.pointer(Pointer(dy = -40f), nowMs = t); d.frame(t) } // shove up, far past the limit
        val top = atan(d.cursorT)
        assertTrue(top <= hi + 1e-5f)
        assertTrue("edge cue", d.edgeCueUntilMs > t - 50)
        t += 300; d.pointer(Pointer(dy = 5f), nowMs = t); d.frame(t)
        assertEquals("first move back is full gain", 5 * elev, top - atan(d.cursorT), 1e-5f)
    }

    @Test fun safetyNets() {
        val d = desk(); d.toSpace(); val th = d.cursorTheta
        d.pointer(Pointer(dx = 1745f, dy = 300f), nowMs = 5_000); d.frame(5_001)
        assertEquals("spike dropped", th, d.cursorTheta, 0f)
        d.pointer(Pointer(dx = 190f), nowMs = 5_100); d.pointer(Pointer(dx = 190f), nowMs = 5_104); d.frame(5_105)
        assertTrue("≤ 8° per update", abs(wrapAngle(d.cursorTheta - th)) <= rad(Accel.MAX_STEP_DEG) + 1e-5f)
    }

    @Test fun capturedWindowStaysNative() {
        val d = desk(); val p = d.panels.getValue(7)
        d.swipe((p.theta - d.cursorTheta) / yaw, 0f, 20, 1)
        d.pointer(Pointer(buttons = 1), nowMs = 1_000); d.pointer(Pointer(buttons = 0), nowMs = 1_010)
        assertEquals(7, d.captured)
        val u0 = d.planeCoords(p)!!.first
        d.swipe(100f, 0f, 4, 2_000)
        assertEquals(100f * 0.8f, d.planeCoords(p)!!.first - u0, 0.05f)
        assertTrue(d.onPanel)
    }

    @Test fun rayStripPointsAtTheReticleAndFacesTheEye() {
        val eye = Vec3(0f, 0f, 0f); val from = Vec3(0f, -250f, 0f); val to = Vec3(300f, 100f, -1200f)
        val (mid, len, q) = strip(from, to, eye)
        assertEquals((to - from).let { kotlin.math.sqrt(it dot it) }, len, 1e-3f)
        fun rot(v: Vec3): Vec3 { // rotate v by quaternion q = (x, y, z, w)
            val (x, y, z, w) = q.toList()
            val u = Vec3(x, y, z); val uv = Vec3(u.y * v.z - u.z * v.y, u.z * v.x - u.x * v.z, u.x * v.y - u.y * v.x)
            val uuv = Vec3(u.y * uv.z - u.z * uv.y, u.z * uv.x - u.x * uv.z, u.x * uv.y - u.y * uv.x)
            return v + uv * (2 * w) + uuv * 2f
        }
        val dir = (to - from) * (1f / len)
        val y = rot(Vec3(0f, 1f, 0f))
        assertEquals(1f, y dot dir, 1e-4f)
        val toEye = (eye - mid).let { it * (1f / kotlin.math.sqrt(it dot it)) }
        // Faces the eye as squarely as a strip along this axis can: normal = toEye minus its along-axis part.
        val best = kotlin.math.sqrt(1f - (toEye dot dir) * (toEye dot dir))
        assertEquals("faces the eye", best, rot(Vec3(0f, 0f, 1f)) dot toEye, 1e-4f)
        assertEquals(1f, q.fold(0f) { a, c -> a + c * c }, 1e-4f)
    }

    @Test fun hoverOnlyWhenTheWindowPointerMoves() {
        val d = desk(); val p = d.panels.getValue(7)
        var t = d.swipe((p.theta - d.cursorTheta) / yaw, 0f, 20, 1_000)
        fun moves(dx: Float): Int { t += 9; d.pointer(Pointer(dx = dx), nowMs = t); return d.frame(t).count { it is Action.Mouse && it.kind == "move" } }
        moves(5f) // reference
        assertEquals("0.4 pt: below one window point", 0, moves(0.4f))
        assertEquals("accumulates to ≥ 1 pt", 1, moves(0.7f))
    }

    @Test fun dragStaysOnItsWindow() {
        val d = desk(); d.toggle(8, 1000, 500); val p = d.panels.getValue(7)
        var t = d.swipe((p.theta - d.cursorTheta) / yaw, 0f, 20, 1_000)
        d.pointer(Pointer(buttons = 1), nowMs = t + 5)
        repeat(60) { t += 9; d.pointer(Pointer(dx = 40f, buttons = 1), nowMs = t)
            d.frame(t).filterIsInstance<Action.Mouse>().forEach { assertEquals("never re-targeted mid-drag", 7, it.id) } }
    }
}
