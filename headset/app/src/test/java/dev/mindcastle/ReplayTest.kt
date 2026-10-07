package dev.mindcastle

import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.atan

/**
 * Replays real trackpad gestures through the device pointer model. The data stays local: point
 * CASTLE_GESTURES at a gestures JSON ({"gestures":[{"start_us":…,"events":[[t_ms,dx,dy,buttons,sx,sy],…]}]});
 * without it the replay tests are skipped.
 */
class ReplayTest {
    private val file = System.getProperty("castle.gestures").orEmpty().let(::File)

    private fun desk(): Desk = Desk(1000f).apply {
        syncWindows(listOf(MacWindow(1, "A", "a", 3024, 1964), MacWindow(2, "B", "b", 2560, 1600), MacWindow(3, "C", "c", 1800, 1200)))
        for (id in 1..3) { val w = MacWindow(id, "", "", listOf(3024, 2560, 1800)[id - 1], listOf(1964, 1600, 1200)[id - 1]); toggle(id, w.w, w.h) }
        acceleration = true
        control = true
    }

    @Test fun realGesturesNeverJumpOrPin() {
        assumeTrue("set CASTLE_GESTURES to replay real gestures", file.isFile)
        val d = desk()
        val gs = JSONObject(file.readText()).getJSONArray("gestures")
        val t0 = gs.getJSONObject(0).getLong("start_us") / 1000
        var n = 0; var atEdge = 0; var worst = 0.0; var leftEdge = 0
        for (gi in 0 until gs.length()) {
            val g = gs.getJSONObject(gi)
            val base = g.getLong("start_us") / 1000 - t0 + 10_000
            val ev = g.getJSONArray("events")
            for (i in 0 until ev.length()) {
                val e = ev.getJSONArray(i)
                val th0 = d.cursorTheta; val el0 = atan(d.cursorT)
                val (lo0, hi0) = d.elevationBand()
                val dy = e.getDouble(2).toFloat()
                val spike = kotlin.math.hypot(e.getDouble(1), e.getDouble(2)) > Accel.SPIKE_PT // dropped on purpose
                val now = base + e.getDouble(0).toLong()
                d.pointer(Pointer(e.getDouble(1).toFloat(), e.getDouble(2).toFloat(), e.getInt(3), e.getDouble(4).toFloat(), e.getDouble(5).toFloat()),
                    nowMs = now)
                d.frame(now) // ~one display frame per message at this cadence
                n++
                if (d.captured != null) continue // inside a window the cursor is clamped to it; not the travel model
                val dTh = Math.toDegrees(abs(wrapAngle(d.cursorTheta - th0)).toDouble())
                val dEl = Math.toDegrees(abs(atan(d.cursorT) - el0).toDouble())
                worst = maxOf(worst, dTh, dEl)
                assertTrue("message $gi/$i moved the cursor ${"%.1f".format(maxOf(dTh, dEl))}°", dTh <= 10.0 && dEl <= 10.0)
                val (lo, hi) = d.elevationBand()
                val el = atan(d.cursorT)
                if (el <= lo + 0.01f || el >= hi - 0.01f) atEdge++
                assertTrue("hit the ±60° hard limit", abs(el) < Math.toRadians(59.9).toFloat())
                assertTrue("outside the panel band", el >= lo - 1e-4f && el <= hi + 1e-4f)
                // A wall, not glue: on the edge, the first move away from it leaves it.
                val awayFromCeiling = el0 >= hi0 - 0.01f && dy > 0.5f
                val awayFromFloor = el0 <= lo0 + 0.01f && dy < -0.5f
                if (!spike && (awayFromCeiling || awayFromFloor)) {
                    assertTrue("stuck on the edge at message $gi/$i", abs(el - el0) > 1e-6f); leftEdge++
                }
            }
        }
        // (This trace pushes up ~3100 pt net — the user rescuing a cursor flung to -60° by the old build —
        // so resting on the band's ceiling a lot is the wall doing its job.)
        println("replayed $n POINTERs, worst single step ${"%.1f".format(worst)}°, at the band edge $atEdge times, left it $leftEdge times")
    }

    @Test fun spikesAreDropped() {
        val d = desk(); val th = d.cursorTheta
        d.pointer(Pointer(dx = 1745f, dy = 300f), nowMs = 1_000); d.frame(1_001)
        assertTrue(d.cursorTheta == th)
    }

    @Test fun verticalIsCappedAndBandIsAWall() {
        val d = desk()
        var t = 1_000L
        repeat(200) { t += 9; d.pointer(Pointer(dy = 90f), nowMs = t); d.frame(t) } // hard, fast downward swipe
        val (lo, _) = d.elevationBand()
        // Outward gain fades over the last 10°, so it eases up to the floor rather than slamming into it.
        assertTrue("stopped near the band's floor, not -60°", abs(atan(d.cursorT) - lo) < Math.toRadians(0.5).toFloat() && lo > -Math.toRadians(59.0).toFloat())
        // Moving up leaves the wall immediately.
        t += 9; d.pointer(Pointer(dy = -5f), nowMs = t); d.frame(t)
        assertTrue(atan(d.cursorT) > lo)
    }

    @Test fun oneMessageMovesAtMostTheStepCap() {
        val d = desk()
        var t = 1_000L
        repeat(20) { t += 9; d.pointer(Pointer(dx = 190f), nowMs = t); d.frame(t) } // ~21000 pt/s, just under the spike limit
        val before = d.cursorTheta
        t += 9; d.pointer(Pointer(dx = 190f), nowMs = t); d.frame(t)
        assertTrue(Math.toDegrees(abs(wrapAngle(d.cursorTheta - before)).toDouble()) <= Accel.MAX_STEP_DEG + 1e-3)
    }

    @Test fun bandEdgeIsAWallEvenIfPanelsMoveAway() {
        val d = desk()
        var t = 1_000L
        repeat(200) { t += 9; d.pointer(Pointer(dy = -60f), nowMs = t); d.frame(t) } // up to the band's ceiling
        val el = atan(d.cursorT)
        // Lower every panel: the cursor is now above the band, but it must not snap down.
        for (id in d.panels.keys.toList()) d.panels.getValue(id).let { p -> d.moveTo(id, d.position(p.copy(y = p.y - 600f))) }
        t += 9; d.pointer(Pointer(dx = 5f), nowMs = t); d.frame(t)
        assertTrue("no jump", abs(atan(d.cursorT) - el) < 1e-4f)
        t += 9; d.pointer(Pointer(dy = 20f), nowMs = t); d.frame(t)
        assertTrue("can come back down", atan(d.cursorT) < el)
    }
}
