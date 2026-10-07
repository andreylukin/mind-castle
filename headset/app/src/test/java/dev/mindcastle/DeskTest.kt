package dev.mindcastle

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

class DeskTest {
    private val eps = 1e-2f
    private val dpm = 1000f // dp per meter, so defaultR = 1200 dp

    /** Desk with the picker and one 1000x500 px window (400x200 dp). */
    private fun desk(): Desk = Desk(dpm).apply {
        toggle(7, 1000, 500)
        control = true
    }

    /** Put the cursor on panel [id] at plane coords (u, v) by sending the exact deltas. */
    private fun Desk.goTo(id: Int, u: Float, v: Float, nowMs: Long = 0L): List<Action> {
        val p = panels.getValue(id)
        val theta = p.theta + atan(u / p.r); val t = (p.y + v) / p.r
        // moveCursor scales by the radius of whatever the cursor is on; hop until it lands exactly.
        var out = emptyList<Action>()
        repeat(8) {
            val r = hover?.let { panels.getValue(it.id).r } ?: panels.getValue(id).r
            val g = cursorDpPerPoint() // native gain over window content
            val dx = (theta - cursorTheta) * r / g; val dy = -(t - cursorT) * r / g
            if (abs(dx) < 1e-3f && abs(dy) < 1e-3f) return out
            out = pointer(Pointer(dx = dx, dy = dy), nowMs)
        }
        return out
    }

    private fun Desk.uv(id: Int) = planeCoords(panels.getValue(id))!!

    @Test fun parsePointer() {
        val p = Pointer.parse("""{"dx":3.5,"dy":-1,"buttons":1,"sx":0,"sy":-2,"mods":["cmd","opt"]}""")
        assertEquals(Pointer(3.5f, -1f, 1, 0f, -2f, setOf("cmd", "opt")), p)
        assertEquals(Pointer(), Pointer.parse("{}"))
    }

    // --- cylinder placement ---

    @Test fun panelsSitOnCylinderFacingCenter() {
        val d = desk()
        for (p in d.panels.values) {
            val pos = d.position(p) - d.center
            assertEquals(1200f, hypot(pos.x, pos.z), eps)
            // Normal points back at the axis.
            val toCenter = (d.center - d.position(p)).let { Vec3(it.x, 0f, it.z) * (1f / 1200f) }
            assertEquals(1f, toCenter dot p.normal, 1e-4f)
            assertEquals(-Math.toDegrees(p.theta.toDouble()).toFloat(), p.yawDeg, 1e-3f)
        }
    }

    @Test fun fallbackCenterPutsAngleZeroAtOrigin() {
        val d = Desk(dpm)
        assertEquals(Vec3(0f, 0f, 1200f), d.center)
        val p = Panel(0f, 0f, 1200f, 100f, 100f)
        val pos = d.position(p)
        assertEquals(0f, pos.x, eps); assertEquals(0f, pos.z, eps)
    }

    @Test fun newPanelsFillToTheRightAlongTheArc() {
        val d = desk(); d.toggle(8, 1000, 500)
        val pk = d.panels.getValue(Desk.PICKER); val a = d.panels.getValue(7); val b = d.panels.getValue(8)
        assertEquals(pk.theta + pk.halfAngle + Desk.GAP / 1200f + a.halfAngle, a.theta, 1e-4f)
        assertEquals(a.theta + a.halfAngle + Desk.GAP / 1200f + b.halfAngle, b.theta, 1e-4f)
        assertEquals(listOf(7, 8), d.shown)
        assertEquals(400f, a.w, eps); assertEquals(200f, a.h, eps)
    }

    @Test fun dpPerMeterRescalesRadii() {
        val d = Desk(1000f); d.setDpPerMeter(1500f)
        assertEquals(1800f, d.panels.getValue(Desk.PICKER).r, eps)
        assertEquals(1800f, d.center.z, eps)
        d.setCenterFromHead(Vec3(10f, 20f, 30f)); d.setCenterFromHead(Vec3(0f, 0f, 0f))
        assertEquals("latest head pose (recenter) wins", Vec3(0f, 0f, 0f), d.center)
    }

    @Test fun handMoveSnapsBackOntoCylinder() {
        val d = desk()
        d.moveTo(7, d.center + Vec3(1000f, 50f, 0f)) // straight right, 1 m out
        val p = d.panels.getValue(7)
        assertEquals((Math.PI / 2).toFloat(), p.theta, 1e-4f); assertEquals(1000f, p.r, eps); assertEquals(50f, p.y, eps)
        d.moveTo(7, d.center + Vec3(0f, 0f, -10f)) // into the user's face: clamped to min radius
        assertEquals(Desk.MIN_R_M * dpm, d.panels.getValue(7).r, eps)
    }

    // --- cursor, arc traversal, hit-testing ---

    @Test fun cursorFollowsMacDeltasOnPanel() {
        val d = desk()
        d.goTo(7, 0f, 0f)
        d.pointer(Pointer(dx = 10f, dy = 5f))
        val (u, v) = d.uv(7)
        // Native gain: 400 dp panel / 500-point window = 0.8 dp per Mac point.
        assertEquals(8f, u, 0.1f); assertEquals(-4f, v, 0.1f)
    }

    @Test fun cursorCrossesGapToNeighbour() {
        val d = desk(); d.toggle(8, 1000, 500)
        d.goTo(7, 190f, 0f)
        assertEquals(Hover(7, Zone.CONTENT), d.hover)
        d.pointer(Pointer(dx = 30f)) // off the right edge, past the corner band: the gap
        assertNull(d.hover)
        d.pointer(Pointer(dx = 60f)) // gap is 60 dp of arc
        assertEquals(Hover(8, Zone.CONTENT), d.hover)
        assertTrue("entered near the left edge", d.uv(8).first < -150f)
    }

    @Test fun cursorKeepsElevationAcrossRadii() {
        val d = desk(); d.toggle(8, 1000, 500)
        d.moveTo(8, d.position(d.panels.getValue(8)).let { d.center + (it - d.center) * 1.5f }) // push 8 out to 1.8 m
        d.goTo(7, 150f, 50f)
        val t = d.cursorT
        d.pointer(Pointer(dx = 300f))
        assertEquals("horizontal motion doesn't change elevation", t, d.cursorT, 1e-6f)
    }

    @Test fun cursorWrapsAroundAndOnlyElevationIsLimited() {
        val d = desk()
        d.pointer(Pointer(dy = -1e7f))
        assertEquals(Desk.MAX_T, d.cursorT, 1e-6f)
        // A full turn of the arc at the cursor's radius brings it back where it started.
        val r = 1200f
        d.pointer(Pointer(dx = (Math.PI * r).toFloat() * 0.9f))
        val a = d.cursorTheta
        assertTrue(a in -Math.PI.toFloat()..Math.PI.toFloat())
        d.pointer(Pointer(dx = (Math.PI * r).toFloat() * 1.1f))
        assertEquals(0f, wrapAngle(d.cursorTheta), 1e-3f)
    }

    /** Plain trackpad moves (no clicks), in steps, until the cursor hovers [id] or [maxSteps] run out. */
    private fun Desk.driveTo(id: Int, maxSteps: Int = 400): Boolean {
        repeat(maxSteps) {
            if (hover?.id == id) return true
            val p = panels.getValue(id)
            val dTheta = wrapAngle(p.theta - cursorTheta); val dT = p.y / p.r - cursorT
            val r = hover?.let { panels.getValue(it.id).r } ?: p.r
            pointer(Pointer(dx = (dTheta * r).coerceIn(-40f, 40f), dy = (-dT * r).coerceIn(-40f, 40f)))
        }
        return hover?.id == id
    }

    // Device 23:5x: "I can't get the mouse to pass the boundary" — cursor fenced near the picker.
    @Test fun everyPanelReachableFromThePicker() {
        val d = Desk(1000f)
        d.syncWindows(listOf(MacWindow(140, "A", "a", 3455, 2000), MacWindow(446, "B", "b", 3455, 2000)))
        d.toggle(140, 3455, 2000); d.toggle(446, 3455, 2000)
        d.moveTo(Desk.PICKER, d.position(Panel(-0.511f, -401f, 1382f, 420f, 200f)))
        d.moveTo(140, d.position(Panel(1.120f, 786f, 1382f, 1382f, 800f)))
        d.moveTo(446, d.position(Panel(1.991f, 0f, 1382f, 1382f, 800f)))
        d.control = true
        assertTrue(d.driveTo(Desk.PICKER))
        assertTrue("panel 140 (θ 1.12, y +786)", d.driveTo(140))
        assertTrue("panel 446 (θ 1.99)", d.driveTo(446))
        assertTrue("back to the picker", d.driveTo(Desk.PICKER))
    }

    @Test fun reachPanelAtTheta2AndHighUp() {
        val d = desk(); d.toggle(8, 1000, 500)
        d.moveTo(7, d.position(Panel(2.0f, 0f, 1200f, 400f, 200f)))
        d.moveTo(8, d.position(Panel(0.3f, 0.6f * 1200f, 1200f, 400f, 200f)))
        assertTrue(d.driveTo(7)); assertTrue(d.driveTo(8)); assertTrue(d.driveTo(Desk.PICKER))
    }

    @Test fun tidyLaysARowStraightAheadAndIsUndoable() {
        val d = desk(); d.toggle(8, 3455, 2000)
        d.moveTo(7, d.position(Panel(2.5f, 900f, 2000f, 400f, 200f))); d.commit()
        val before = d.panels.toMap()
        d.command(Command("tidy"), null, 0)
        val ps = d.panels
        val order = ps.keys.toList()
        assertEquals(Desk.PICKER, ps.minByOrNull { it.value.theta }!!.key)
        ps.values.forEach { assertEquals(0f, it.y, 0f); assertEquals(d.defaultR, it.r, 1e-3f) }
        val maxW = 2 * d.defaultR * kotlin.math.tan(Math.toRadians(Desk.TIDY_MAX_DEG / 2.0)).toFloat()
        assertTrue(ps.getValue(8).w <= maxW + 1e-3f)
        // Centered: the row's left and right edges are symmetric about straight ahead.
        val left = ps.values.minOf { it.theta - it.halfAngle }; val right = ps.values.maxOf { it.theta + it.halfAngle }
        assertEquals(0f, left + right, 1e-4f)
        assertEquals("aspect kept", 2f, ps.getValue(7).w / ps.getValue(7).h, 1e-3f)
        assertTrue(d.undo()); assertEquals(before, d.panels.toMap())
        assertEquals(order.toSet(), d.panels.keys)
    }

    // Regression (lab): restore used to auto-tidy a deliberately far panel (Terminal at 100.6°) on every relaunch.
    @Test fun restoreKeepsFarPanelsExactly() {
        val d = desk()
        d.syncWindows(listOf(MacWindow(7, "Terminal", "zsh", 1000, 500)))
        d.moveTo(7, d.position(Panel(Math.toRadians(100.6).toFloat(), 500f, 1200f, 400f, 200f))); d.commit()
        val far = d.panels.getValue(7)
        val e = Desk(1000f); e.restore(LayoutJson.read(LayoutJson.write(d.layoutState())))
        e.syncWindows(listOf(MacWindow(70, "Terminal", "zsh", 1000, 500))) // relaunch / new window id
        assertEquals(far, e.panels.getValue(70))
    }

    @Test fun arrowPointsAtNearestPanelInEmptySpace() {
        val d = desk(); val p = d.panels.getValue(7)
        d.goTo(7, 0f, 0f)
        assertNull("over a panel: no arrow", d.nearestPanelDirection())
        d.pointer(Pointer(dx = 0f, dy = 400f)) // well below window 7, in empty space
        val dir = d.nearestPanelDirection()!!
        assertTrue("points up toward the window: $dir", dir > 1.2f && dir < 1.95f)
        d.pointer(Pointer(dx = 800f, dy = -400f)) // far right of everything
        val left = d.nearestPanelDirection()!!
        assertTrue("points left: $left", kotlin.math.abs(left) > 2.5f)
        assertEquals(p, d.panels.getValue(7))
    }

    @Test fun ignoresPointerOutsideControlMode() {
        val d = desk(); d.control = false
        assertTrue(d.pointer(Pointer(dx = 10f, buttons = 1)).isEmpty())
        assertEquals(0f, d.cursorTheta, 0f)
    }

    @Test fun zones() {
        val d = desk(); val w = d.panels.getValue(7)
        fun at(u: Float, v: Float) = d.hit(w.theta + atan(u / w.r), (w.y + v) / w.r)
        assertEquals(Hover(7, Zone.CONTENT), at(0f, 0f))
        assertEquals(Hover(7, Zone.CONTENT), at(-w.w / 2 + Desk.CORNER_IN + 1f, w.h / 2 - 1f))
        assertEquals(Hover(7, Zone.CORNER, -1, 1), at(-w.w / 2 + 2f, w.h / 2 - 2f))
        assertEquals(Hover(7, Zone.CORNER, 1, -1), at(w.w / 2 + 20f, -w.h / 2 - 20f))
        assertNull("edge band is gone", at(0f, w.h / 2 + 10f))
        assertNull(at(w.w / 2 + 10f, 0f))
        assertEquals(Hover(7, Zone.BAR), at(0f, Bar.v(w)))
        assertEquals(Hover(7, Zone.BAR), at(Bar.w(w) / 2 + Bar.PAD_X - 1f, Bar.v(w) - Bar.H / 2 - Bar.PAD_Y + 1f))
        assertNull(at(Bar.w(w) / 2 + Bar.PAD_X + 1f, Bar.v(w)))
        val pk = d.panels.getValue(Desk.PICKER)
        assertEquals("picker has no resize corners", Hover(Desk.PICKER, Zone.CONTENT),
            d.hit(pk.theta + atan((-pk.w / 2 + 2f) / pk.r), (pk.y + pk.h / 2 - 2f) / pk.r))
    }

    @Test fun hitPrefersNearestPanel() {
        val d = desk(); val a = d.panels.getValue(7)
        d.toggle(8, 1000, 500)
        d.moveTo(8, d.position(a.copy(r = 1500f))) // directly behind 7
        assertEquals(7, d.hit(a.theta, 0f)?.id)
        d.moveTo(8, d.position(a.copy(r = 900f))) // directly in front
        assertEquals(8, d.hit(a.theta, 0f)?.id)
    }

    // --- content -> MOUSE / SCROLL ---

    @Test fun contentGetsMouseMoveDownDragUp() {
        val d = desk(); val p = d.panels.getValue(7)
        val mv = d.goTo(7, -p.w / 4, p.h / 4).single() as Action.Mouse
        assertEquals("move", mv.kind); assertEquals(0.25f, mv.x, 1e-3f); assertEquals(0.25f, mv.y, 1e-3f)
        assertEquals(listOf(Action.Mouse(7, "down", mv.x, mv.y, 0)), d.pointer(Pointer(buttons = 1)))
        val dr = d.pointer(Pointer(dx = 1000f, buttons = 1)).single() as Action.Mouse // way off the panel
        assertEquals("drag", dr.kind); assertEquals(1f, dr.x, 0f)
        assertEquals("up", (d.pointer(Pointer(buttons = 0)).single() as Action.Mouse).kind)
    }

    @Test fun rightButtonIsButtonOne() {
        val d = desk(); d.goTo(7, 0f, 0f)
        val down = d.pointer(Pointer(buttons = 2)).single() as Action.Mouse
        assertEquals("down", down.kind); assertEquals(1, down.button); assertEquals(0.5f, down.x, 1e-3f)
        val up = d.pointer(Pointer(buttons = 0)).single() as Action.Mouse
        assertEquals("up", up.kind); assertEquals(1, up.button)
    }

    @Test fun noMouseOffContentOrOnPicker() {
        val d = desk()
        val p = d.panels.getValue(7)
        d.goTo(7, 0f, Bar.v(p))
        assertTrue(d.pointer(Pointer(dx = 1f)).isEmpty())
        val pk = d.panels.getValue(Desk.PICKER)
        assertTrue(d.goTo(Desk.PICKER, 0f, 0f).isEmpty())
        assertTrue(d.pointer(Pointer(buttons = 2)).isEmpty())
        d.pointer(Pointer(buttons = 0))
        assertEquals(pk, d.panels.getValue(Desk.PICKER))
    }

    @Test fun scrollAccumulatesFractionalLines() {
        val d = desk(); d.goTo(7, 0f, 0f)
        assertTrue(d.pointer(Pointer(sy = 0.6f)).isEmpty())
        val sc = d.pointer(Pointer(sy = 0.6f)).single() as Action.Scroll
        assertEquals(7, sc.id); assertEquals(0, sc.dx); assertEquals(1, sc.dy); assertEquals(0.5f, sc.y, 1e-3f)
        assertTrue("0.2 carried + 0.6 < 1 line", d.pointer(Pointer(sy = 0.6f)).isEmpty())
        assertEquals(1, (d.pointer(Pointer(sy = 0.6f)).single() as Action.Scroll).dy)
    }

    @Test fun pickerClickPicksRow() {
        val d = desk()
        d.syncWindows(listOf(MacWindow(7, "A", "a", 1000, 500), MacWindow(9, "B", "b", 800, 600)))
        val pk = d.panels.getValue(Desk.PICKER)
        d.goTo(Desk.PICKER, 0f, pk.h / 2 - PickerMetrics.PAD - PickerMetrics.HEADER - PickerMetrics.ROW * 1.5f)
        assertEquals(listOf(Action.PickerRow(1)), d.pointer(Pointer(buttons = 1)))
    }

    @Test fun pickerRowAt() {
        assertNull(PickerMetrics.rowAt(PickerMetrics.PAD + 5f, 3)) // header
        assertEquals(0, PickerMetrics.rowAt(PickerMetrics.PAD + PickerMetrics.HEADER + 1f, 3))
        assertNull(PickerMetrics.rowAt(PickerMetrics.PAD + PickerMetrics.HEADER + 3 * PickerMetrics.ROW + 1f, 3))
    }

    // --- grab bar, corners ---

    @Test fun barDragMovesAlongCylinder() {
        val d = desk(); val p = d.panels.getValue(7)
        d.goTo(7, 0f, Bar.v(p))
        assertEquals(Hover(7, Zone.BAR), d.hover)
        assertTrue(d.pointer(Pointer(buttons = 1)).isEmpty())
        d.pointer(Pointer(dx = 120f, dy = -60f, buttons = 1))
        val m = d.panels.getValue(7)
        assertEquals(p.theta + 120f / p.r, m.theta, 1e-4f); assertEquals(p.y + 60f, m.y, 0.01f)
        assertEquals(p.r, m.r, 0f); assertEquals(p.w, m.w, 0f)
        assertEquals("cursor rides along on the bar", Hover(7, Zone.BAR), d.hit())
        d.pointer(Pointer(buttons = 0)); d.pointer(Pointer(dx = 30f))
        assertEquals("released", m.theta, d.panels.getValue(7).theta, 0f)
    }

    @Test fun barDragWorksForPicker() {
        val d = desk(); val pk = d.panels.getValue(Desk.PICKER)
        d.goTo(Desk.PICKER, 0f, Bar.v(pk))
        d.pointer(Pointer(buttons = 1)); d.pointer(Pointer(dx = -100f, buttons = 1))
        assertEquals(pk.theta - 100f / pk.r, d.panels.getValue(Desk.PICKER).theta, 1e-4f)
    }

    @Test fun scrollOnBarChangesRadius() {
        val d = desk(); val p = d.panels.getValue(7)
        d.goTo(7, 0f, Bar.v(p))
        assertTrue(d.pointer(Pointer(sy = 2f)).isEmpty())
        assertEquals("scroll up pushes away", p.r + 2 * Desk.R_PER_LINE_M * dpm, d.panels.getValue(7).r, eps)
        d.pointer(Pointer(sy = -1000f))
        assertEquals(Desk.MIN_R_M * dpm, d.panels.getValue(7).r, eps)
        assertEquals("cursor follows the panel in", Hover(7, Zone.BAR), d.hit())
    }

    @Test fun scrollDuringBarDragKeepsRadius() {
        val d = desk(); val p = d.panels.getValue(7)
        d.goTo(7, 0f, Bar.v(p))
        d.pointer(Pointer(buttons = 1)); d.pointer(Pointer(sy = 4f, buttons = 1)); d.pointer(Pointer(dx = 5f, buttons = 1))
        assertEquals(p.r + 4 * Desk.R_PER_LINE_M * dpm, d.panels.getValue(7).r, eps)
    }

    @Test fun cornerDragResizesKeepingAspectWithOppositeCornerPinned() {
        val d = desk(); val p = d.panels.getValue(7)
        val topLeft = d.planePoint(p, -p.w / 2, p.h / 2)
        d.goTo(7, p.w / 2 + 5f, -p.h / 2 - 5f) // bottom-right handle
        assertEquals(Hover(7, Zone.CORNER, 1, -1), d.hover)
        d.pointer(Pointer(buttons = 1))
        d.pointer(Pointer(dx = 100f, dy = 50f, buttons = 1))
        val r = d.panels.getValue(7)
        assertEquals(400f / 200f, r.w / r.h, 1e-4f)
        assertTrue("grew", r.w > 480f)
        val tl = d.planePoint(r, -r.w / 2, r.h / 2)
        // The pinned corner stays put up to the re-facing: the panel slides along the arc and turns
        // to face the user, which swings its far corner by a few dp in depth.
        assertTrue("top-left moved ${tl - topLeft}", abs(tl.x - topLeft.x) < 5f && abs(tl.y - topLeft.y) < 0.5f && abs(tl.z - topLeft.z) < 15f)
        d.pointer(Pointer(dx = -700f, buttons = 1))
        assertEquals(Desk.MIN_W, d.panels.getValue(7).w, eps)
    }

    @Test fun resizedMath() {
        assertEquals(Triple(50f, -25f, 500f), resized(400f, 200f, 1, -1, 100f, -50f))
        assertEquals(Triple(50f, -25f, 300f), resized(400f, 200f, -1, 1, 100f, -50f))
    }

    @Test fun syncWindowsDropsGoneAndFollowsAspect() {
        val d = desk(); d.toggle(8, 1000, 500)
        assertTrue(d.syncWindows(listOf(MacWindow(7, "A", "a", 1000, 1000))))
        assertEquals(listOf(7), d.shown)
        val w = d.panels.getValue(7)
        assertEquals(400f, w.w, eps); assertEquals(400f, w.h, eps)
        assertEquals("top edge kept", 100f, w.y + w.h / 2, eps)
        assertEquals(PickerMetrics.height(1), d.panels.getValue(Desk.PICKER).h, eps)
    }

    // --- look-to-jump ---

    private fun Desk.rayAt(id: Int, u: Float = 0f, v: Float = 0f): Ray {
        val target = planePoint(panels.getValue(id), u, v)
        val dir = target - center
        return Ray(center, dir * (1f / hypot(hypot(dir.x, dir.y), dir.z)))
    }

    @Test fun jumpLandsOnGazedPanel() {
        val d = desk()
        assertEquals(7, d.jumpTo(d.rayAt(7, 50f, -20f)))
        val (u, v) = d.uv(7)
        assertEquals(50f, u, 0.5f); assertEquals(-20f, v, 0.5f)
        assertEquals(Hover(7, Zone.CONTENT), d.hover)
        assertNull("already there: no jump", d.jumpTo(d.rayAt(7)))
        assertNull("looking at nothing", d.jumpTo(Ray(d.center, Vec3(0f, 0f, 1f))))
    }

    @Test fun jumpOnlyAfterIdle() {
        val d = desk()
        var asked = 0
        val gaze = { asked++; d.rayAt(Desk.PICKER) }
        d.pointer(Pointer(dx = 1f), nowMs = 10_000, gaze = gaze)
        assertEquals(1, asked); assertEquals(Desk.PICKER, d.hover?.id)
        d.pointer(Pointer(dx = 1f), nowMs = 10_200, gaze = gaze)
        assertEquals("busy: no gaze lookups", 1, asked)
        d.pointer(Pointer(dx = 1f), nowMs = 10_800, gaze = gaze)
        assertEquals(2, asked)
    }

    // Regression (lab): pause on the bar, then press-drag — the press must not teleport the cursor.
    @Test fun noJumpOnPressAfterIdle() {
        val d = desk(); val p = d.panels.getValue(7)
        d.goTo(7, 0f, Bar.v(p), nowMs = 0)
        var asked = 0
        val gaze = { asked++; d.rayAt(Desk.PICKER) }
        d.pointer(Pointer(buttons = 1), nowMs = 10_000, gaze = gaze)
        d.pointer(Pointer(dx = 50f, buttons = 1), nowMs = 10_010, gaze = gaze)
        assertEquals(0, asked)
        assertEquals("bar drag moved the panel", p.theta + 50f / p.r, d.panels.getValue(7).theta, 1e-4f)
    }

    @Test fun noJumpWhenParkedOnBarOrCorner() {
        val d = desk(); val p = d.panels.getValue(7)
        var asked = 0
        val gaze = { asked++; d.rayAt(Desk.PICKER) }
        d.goTo(7, 0f, Bar.v(p), nowMs = 0)
        d.pointer(Pointer(dx = 1f), nowMs = 10_000, gaze = gaze)
        assertEquals(Hover(7, Zone.BAR), d.hover)
        d.goTo(7, p.w / 2 + 5f, p.h / 2 + 5f, nowMs = 10_000)
        assertEquals(Hover(7, Zone.CORNER, 1, 1), d.hover)
        d.pointer(Pointer(dx = -1f), nowMs = 20_000, gaze = gaze)
        assertEquals(0, asked)
    }

    @Test fun noJumpOnScrollOrZeroDeltaKeepalive() {
        val d = desk(); d.goTo(7, 0f, 0f, nowMs = 0)
        var asked = 0
        val gaze = { asked++; d.rayAt(Desk.PICKER) }
        d.pointer(Pointer(sy = 1f), nowMs = 10_000, gaze = gaze)
        d.pointer(Pointer(), nowMs = 20_000, gaze = gaze)
        assertEquals(0, asked)
        assertEquals(7, d.hover?.id)
    }

    @Test fun exitCapturedOntoBarThenPauseThenDrag() {
        val d = captured(); val p = d.panels.getValue(7)
        d.pointer(Pointer(dy = 125f), nowMs = 0); d.pointer(Pointer(dy = 100f), nowMs = 0) // out the bottom onto the bar
        assertEquals(Hover(7, Zone.BAR), d.hover)
        val gaze = { d.rayAt(Desk.PICKER) }
        d.pointer(Pointer(buttons = 1), nowMs = 10_000, gaze = gaze)
        d.pointer(Pointer(dx = 40f, buttons = 1), nowMs = 10_010, gaze = gaze)
        assertEquals(p.theta + 40f / p.r, d.panels.getValue(7).theta, 1e-4f)
    }

    @Test fun jumpPrefersNearestPanelOnTheRay() {
        val d = desk(); val a = d.panels.getValue(7)
        d.toggle(8, 1000, 500)
        d.moveTo(8, d.position(a.copy(r = 1500f)))
        assertEquals(7, d.jumpTo(d.rayAt(7)))
        assertNotNull(d.hover)
    }

    @Test fun headRayFromCenterMatchesCylinderAngles() {
        // A ray from the center hits a panel at the same angle the cursor uses.
        val d = desk(); val p = d.panels.getValue(7)
        d.jumpTo(d.rayAt(7, 0f, 0f))
        assertEquals(p.theta, d.cursorTheta, 1e-4f)
        val pos = d.position(p) - d.center
        assertEquals(p.theta, kotlin.math.atan2(pos.x, -pos.z), 1e-4f)
        assertEquals(cos(p.theta) * p.r, -pos.z, eps); assertEquals(sin(p.theta) * p.r, pos.x, eps)
    }

    // --- v3: capture, overshoot, native gain ---

    private val k7 = 400f / 500f // window 7: 400 dp wide, 1000 px = 500 points

    @Test fun nativeGainOverContentOnly() {
        val d = desk()
        assertEquals(k7, d.dpPerPoint(7)!!, 1e-6f)
        assertNull(d.dpPerPoint(Desk.PICKER))
        d.goTo(7, 0f, 0f)
        assertEquals(k7, d.cursorDpPerPoint(), 1e-6f)
        d.goTo(7, 0f, Bar.v(d.panels.getValue(7)))
        assertEquals(Desk.GAIN, d.cursorDpPerPoint(), 0f)
    }

    @Test fun hoverDoesNotCaptureClickDoes() {
        val d = desk(); val log = mutableListOf<Pair<Int?, String>>()
        d.onCapture = { id, why -> log += id to why }
        d.goTo(7, 0f, 0f)
        assertNull(d.captured)
        d.pointer(Pointer(buttons = 1)); d.pointer(Pointer(buttons = 0))
        assertEquals(7, d.captured)
        assertEquals(listOf<Pair<Int?, String>>(7 to "click"), log)
        assertTrue(d.overContent)
    }

    private fun captured(): Desk = desk().apply {
        goTo(7, 0f, 0f); pointer(Pointer(buttons = 1)); pointer(Pointer(buttons = 0))
    }

    @Test fun capturedMovesInWindowPoints() {
        val d = captured()
        d.pointer(Pointer(dx = 100f, dy = 50f))
        val (u, v) = d.uv(7)
        assertEquals(100 * k7, u, 0.01f); assertEquals(-50 * k7, v, 0.01f)
    }

    @Test fun capturedClampsThenOvershootLeaves() {
        val d = captured(); val p = d.panels.getValue(7)
        d.pointer(Pointer(dx = 250f)) // exactly to the right edge (250 pt * 0.8 = 200 dp)
        assertEquals(p.w / 2, d.uv(7).first, 0.01f)
        d.pointer(Pointer(dx = 30f)) // 24 dp of overshoot
        d.pointer(Pointer(dx = 30f)) // 48
        assertEquals("still captured, clamped", 7, d.captured)
        assertEquals(p.w / 2, d.uv(7).first, 0.01f)
        // Pinned to the edge: the window pointer doesn't move, so no hover MOUSE is sent.
        assertTrue(d.pointer(Pointer(dx = 1f)).isEmpty())
        d.pointer(Pointer(dx = 20f)) // 48.8 + 16 > 60: leaves
        assertNull(d.captured)
        assertNull("parked just past the edge, in the gap", d.hover)
        assertEquals(p.w / 2 + Desk.CORNER_OUT + 2f, p.r * kotlin.math.tan(d.cursorTheta - p.theta), 0.01f)
    }

    @Test fun pullingBackResetsOvershoot() {
        val d = captured()
        d.pointer(Pointer(dx = 250f)); d.pointer(Pointer(dx = 70f)) // 56 dp over
        d.pointer(Pointer(dx = -1f)) // back inside
        d.pointer(Pointer(dx = 70f)) // 0.8 dp to the edge + 55.2 over: not enough
        assertEquals(7, d.captured)
    }

    @Test fun bottomOvershootLandsOnGrabBar() {
        val d = captured()
        d.pointer(Pointer(dx = 240f)) // near the right edge (a bigger push would overshoot out the side)...
        d.pointer(Pointer(dy = 125f)) // ...to the bottom edge
        d.pointer(Pointer(dy = 100f)) // 80 dp past it
        assertNull(d.captured)
        assertEquals(Hover(7, Zone.BAR), d.hover)
    }

    @Test fun neverLeavesWhileButtonHeld() {
        val d = captured()
        d.pointer(Pointer(buttons = 1))
        val drags = d.pointer(Pointer(dx = 5000f, buttons = 1))
        assertEquals(7, d.captured)
        assertEquals(1f, (drags.single() as Action.Mouse).x, 0f)
        d.pointer(Pointer(buttons = 0))
        d.pointer(Pointer(dx = 1f))
        assertEquals("overshoot during the drag doesn't count", 7, d.captured)
    }

    @Test fun lookReleasesToOtherPanel() {
        val d = captured(); val why = mutableListOf<String>()
        d.onCapture = { _, w -> why += w }
        d.pointer(Pointer(dx = 1f), nowMs = 10_000, gaze = { Ray(d.center, (d.planePoint(d.panels.getValue(Desk.PICKER), 0f, 0f) - d.center).let { it * (1f / hypot(hypot(it.x, it.y), it.z)) }) })
        assertNull(d.captured); assertEquals(listOf("look"), why)
        assertEquals(Desk.PICKER, d.hover?.id)
    }

    @Test fun lookAtSamePanelKeepsCapture() {
        val d = captured()
        d.pointer(Pointer(dx = 1f), nowMs = 10_000, gaze = { d.rayAt(7) })
        assertEquals(7, d.captured)
    }

    @Test fun hidingWindowReleases() {
        val d = captured()
        d.toggle(7, 1000, 500)
        assertNull(d.captured)
    }

    @Test fun cursorHeaderAndPlacement() {
        val bytes = byteArrayOf(0, 4, 0, 6, 0, 16, 0, 24, 0x89.toByte())
        val h = CursorHeader.parse(bytes)
        assertEquals(CursorHeader(4, 6, 16, 24), h)
        // 32x48 px image, 16x24 pt, at 0.5 dp/pt -> 8x12 dp; hotspot (4,6) px = (1, 1.5) dp from top-left.
        val pl = h.placement(32, 48, 0.5f)
        assertEquals(8f, pl.w, 1e-5f); assertEquals(12f, pl.h, 1e-5f)
        assertEquals(4f - 1f, pl.du, 1e-5f); assertEquals(-(6f - 1.5f), pl.dv, 1e-5f)
        assertEquals("u16 is unsigned", 65535, CursorHeader.parse(byteArrayOf(-1, -1, 0, 0, 0, 0, 0, 0)).hotX)
    }

    // --- lab v4 fixes ---

    @Test fun bottomExitSnapsToBarCenterAndAbsorbsMomentum() {
        val d = captured()
        d.pointer(Pointer(dx = 100f), nowMs = 1_000) // off-center horizontally
        d.pointer(Pointer(dy = 125f), nowMs = 1_000)
        d.pointer(Pointer(dy = 100f), nowMs = 1_010) // out the bottom
        assertEquals(Hover(7, Zone.BAR), d.hover)
        assertEquals("snapped to the bar's center", 0f, d.uv(7).first, 0.01f)
        val t = d.cursorT
        d.pointer(Pointer(dy = 40f), nowMs = 1_060) // the flick keeps going: swallowed
        d.pointer(Pointer(dx = 30f, dy = 10f), nowMs = 1_120)
        assertEquals(t, d.cursorT, 0f); assertEquals(Hover(7, Zone.BAR), d.hover)
        d.pointer(Pointer(dy = 5f), nowMs = 1_400) // window over: moves again
        assertTrue(d.cursorT < t)
    }

    @Test fun absorbEndsWhenUserReverses() {
        val d = captured()
        d.pointer(Pointer(dy = 125f), nowMs = 1_000); d.pointer(Pointer(dy = 100f), nowMs = 1_010)
        val t = d.cursorT
        d.pointer(Pointer(dy = -5f), nowMs = 1_030)
        assertTrue("moving up is never swallowed", d.cursorT > t)
        d.pointer(Pointer(dy = 5f), nowMs = 1_040)
        assertTrue("and absorption is over", d.cursorT < t + 5f / d.panels.getValue(7).r)
    }

    @Test fun raisedPanelPitchesTowardTheEye() {
        val d = desk(); val p0 = d.panels.getValue(7)
        d.moveTo(7, d.center + Vec3(0f, 300f, -1000f))
        val p = d.panels.getValue(7)
        val toEye = (d.center - d.position(p)).let { it * (1f / kotlin.math.sqrt(it dot it)) }
        assertEquals("normal points at the head", 1f, toEye dot p.normal, 1e-5f)
        assertEquals(Math.toDegrees(kotlin.math.atan2(300.0, 1000.0)).toFloat(), p.pitchDeg, 1e-3f)
        // Orthonormal frame.
        assertEquals(0f, p.right dot p.up, 1e-6f); assertEquals(0f, p.up dot p.normal, 1e-6f); assertEquals(1f, p.up dot p.up, 1e-6f)
        assertEquals("eye-level panels stay vertical", 0f, p0.pitch, 0f)
    }

    @Test fun lookToJumpOnTiltedPanel() {
        val d = desk()
        d.moveTo(7, d.center + Vec3(200f, -400f, -1000f))
        assertEquals(7, d.jumpTo(d.rayAt(7, 60f, 40f)))
        assertEquals(Hover(7, Zone.CONTENT), d.hover)
    }

    @Test fun frameSlotNeverQueuesMoreThanOneAndRecoversOnKey() {
        val s = FrameSlot<Pair<Int, Boolean>> { it.second }
        assertTrue("P before any keyframe: ask for one", s.offer(1 to false))
        assertTrue(!s.offer(2 to true))
        assertEquals(2 to true, s.take(0))
        assertTrue(!s.offer(3 to false))
        assertTrue("decoder behind: drop and ask for a keyframe", s.offer(4 to false))
        assertEquals("older frame kept: it's next in the chain", 3 to false, s.take(0))
        assertTrue("rest of the GOP discarded", s.offer(5 to false))
        assertNull(s.take(0))
        assertTrue(!s.offer(6 to true)); assertTrue(!s.offer(7 to true))
        assertEquals("a newer keyframe supersedes", 7 to true, s.take(0))
        assertEquals(4, s.dropped)
        s.reset(); assertTrue("reset waits for a keyframe", s.offer(8 to false))
    }

    @Test fun frameSlotTakeTimesOutAndWakes() {
        val s = FrameSlot<Int> { true }
        val t0 = System.nanoTime(); assertNull(s.take(30)); assertTrue(System.nanoTime() - t0 >= 25_000_000)
        val th = Thread { Thread.sleep(20); s.wake() }.apply { start() }
        assertNull(s.take(5_000)); th.join()
        Thread { Thread.sleep(20); s.offer(9) }.start()
        assertEquals(9, s.take(5_000))
    }

    @Test fun chromeFitsInsideThePanel() {
        val p = Panel(0f, 0f, 1200f, 720f, 586f)
        // Hot corner handle (26 dp) centered M outside the content corner stays inside the margin.
        assertTrue(Chrome.M + 13f <= Chrome.PAD)
        // The bar (and its gap) fits in the bottom margin.
        assertTrue(Bar.GAP + Bar.H <= Chrome.BOTTOM)
        assertEquals(p.w + 2 * Chrome.PAD, Chrome.panelW(p), 0f)
        // Panel center offset: halfway between the top margin and the bottom margin.
        val top = p.h / 2 + Chrome.PAD; val bottom = -(p.h / 2 + Chrome.BOTTOM)
        assertEquals((top + bottom) / 2, Chrome.centerV, 1e-4f)
        assertEquals(top - bottom, Chrome.panelH(p), 1e-4f)
        // The drawn bar is where Desk hit-tests it.
        assertEquals(Bar.v(p), -(p.h / 2 + Bar.GAP + Bar.H / 2), 1e-4f)
    }

    // --- tracing ---

    @Test fun clockUsesLowestRttSample() {
        val c = ClockSync(window = 3)
        assertNull(c.offset())
        c.add(t0 = 1000, macNow = 5600, t1 = 1200) // rtt 200, offset 4500
        c.add(t0 = 2000, macNow = 7020, t1 = 2040) // rtt 40,  offset 5000
        c.add(t0 = 3000, macNow = 8500, t1 = 3400) // rtt 400
        assertEquals(5000L to 40L, c.best())
        c.add(4000, 9000, 4100); c.add(5000, 10000, 5100); c.add(6000, 11000, 6300)
        assertEquals("best sample aged out of the window", 4950L, c.offset())
    }

    @Test fun frameReportShiftsToMacClock() {
        val a = JSONArray(frameReportJson(listOf(FrameTrace(630, 123, 456, 789)), offset = 1000))
        val o = a.getJSONObject(0)
        assertEquals(630, o.getInt("id")); assertEquals(123L, o.getLong("pts"))
        assertEquals(1456L, o.getLong("recv")); assertEquals(1789L, o.getLong("out"))
    }
}
