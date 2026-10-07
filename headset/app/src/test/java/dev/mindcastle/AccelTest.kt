package dev.mindcastle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccelTest {
    @Test fun curve() {
        assertEquals(1f, Accel.factor(0f), 0f)
        assertEquals(1f, Accel.factor(Accel.SLOW), 0f)
        assertEquals((1f + Accel.MAX) / 2, Accel.factor((Accel.SLOW + Accel.FAST) / 2), 1e-4f)
        assertEquals(Accel.MAX, Accel.factor(Accel.FAST), 0f)
        assertEquals(Accel.MAX, Accel.factor(10_000f), 0f)
        var last = 0f
        for (s in 0..4000 step 50) { val f = Accel.factor(s.toFloat()); assertTrue(f >= last); last = f }
    }

    private fun desk(): Desk = Desk(1000f).apply {
        toggle(7, 1000, 500) // 400 dp wide, 500-point window: native 0.8 dp/pt
        acceleration = true
        control = true
    }

    /** Move by (dx, dy) Mac points as [n] equal messages at 120 Hz starting at [t0]. Returns the end time. */
    private fun Desk.swipe(dx: Float, dy: Float, n: Int, t0: Long): Long {
        var t = t0
        repeat(n) { t += 8; pointer(Pointer(dx = dx / n, dy = dy / n), nowMs = t) }
        return t
    }

    @Test fun slowMoveOverContentKeepsNativePrecision() {
        val d = desk(); val p = d.panels.getValue(7)
        d.pointer(Pointer(dx = (p.theta - d.cursorTheta) * p.r), nowMs = 1) // onto the window
        d.pointer(Pointer(), nowMs = 1_000)
        val u0 = d.planeCoords(p)!!.first
        d.swipe(20f, 0f, 10, 2_000) // 20 pt over 80 ms = 250 pt/s
        // Native 0.8 dp/pt (within the plane-vs-arc projection, ~1% this far off center).
        assertEquals(20f * 0.8f, d.planeCoords(p)!!.first - u0, 0.3f)
    }

    @Test fun fastFlickCrossesTheLayout() {
        val slow = desk(); val fast = desk()
        slow.swipe(600f, 0f, 60, 1_000) // 600 pt in 480 ms = 1250 pt/s
        fast.swipe(600f, 0f, 15, 1_000) // 600 pt in 120 ms = 5000 pt/s
        assertTrue("fast ${fast.cursorTheta} vs slow ${slow.cursorTheta}", fast.cursorTheta > slow.cursorTheta * 1.5f)
        // At full acceleration 600 pt cover 600·MAX dp of arc.
        assertTrue(fast.cursorTheta > 600f * Accel.MAX * 0.8f / 1200f)
    }

    @Test fun gapsAreCrossedAtLeastFasterThanBase() {
        val d = desk()
        d.pointer(Pointer(dy = -400f), nowMs = 1) // up into empty space
        d.pointer(Pointer(), nowMs = 1_000)
        assertEquals(null, d.hover)
        val t0 = d.cursorTheta
        d.swipe(10f, 0f, 10, 2_000) // 125 pt/s: slow
        assertEquals(10f * Accel.GAP_MIN / 1200f, d.cursorTheta - t0, 1e-4f)
    }

    @Test fun capturedWindowStaysNative() {
        val d = desk(); val p = d.panels.getValue(7)
        d.pointer(Pointer(dx = (p.theta - d.cursorTheta) * p.r), nowMs = 1)
        d.pointer(Pointer(buttons = 1), nowMs = 1_000); d.pointer(Pointer(buttons = 0), nowMs = 1_010)
        assertEquals(7, d.captured)
        val u0 = d.planeCoords(p)!!.first
        d.swipe(100f, 0f, 4, 2_000) // a fast flick inside the captured window
        assertEquals(100f * 0.8f, d.planeCoords(p)!!.first - u0, 0.05f)
    }

    @Test fun gestureLogSummarisesTravel() {
        val d = desk(); val log = mutableListOf<String>()
        d.onGesture = { log += it }
        d.swipe(600f, 0f, 15, 1_000)
        d.swipe(10f, 0f, 2, 2_000) // after a pause: the first gesture is reported
        assertEquals(1, log.size)
        assertTrue(log[0], log[0].startsWith("travel: θ") && "peak" in log[0])
    }
}
