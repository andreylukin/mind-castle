package dev.mindcastle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.tan

class LayoutTest {
    private val dpm = 1000f
    private val eps = 1e-2f
    private fun rad(d: Float) = (d * PI / 180).toFloat()
    private fun win(id: Int, app: String, title: String, w: Int = 1000, h: Int = 500) = MacWindow(id, app, title, w, h)

    /** Windows already up, with an empty undo history (as after a launch restore). */
    private fun desk(vararg ws: MacWindow): Desk {
        val setup = Desk(dpm).apply { syncWindows(ws.toList()); for (w in ws) toggle(w.id, w.w, w.h) }
        return Desk(dpm).apply { restore(LayoutJson.read(LayoutJson.write(setup.layoutState()))); syncWindows(ws.toList()) }
    }

    // --- commands, presets ---

    @Test fun parseCommand() {
        assertEquals(Command("nudge", dtheta = -1), Command.parse("""{"cmd":"nudge","dtheta":-1}"""))
        assertEquals(Command("preset", name = "side"), Command.parse("""{"cmd":"preset","name":"side"}"""))
        assertEquals(Command("cutout", dw = 1, dh = -1), Command.parse("""{"cmd":"cutout","dw":1,"dh":-1}"""))
    }

    @Test fun presetsHitTheirAngularWidthAndDistance() {
        val d = desk(win(7, "Code", "a"))
        d.setCenterFromHead(Vec3(0f, 0f, 0f), Vec3(1f, 0f, -1f)) // facing 45° right
        val p = d.panels.getValue(7)
        for (pr in listOf(Comfort.EDITOR, Comfort.SIDE, Comfort.GLANCE)) {
            val q = d.preset(p, pr)
            assertEquals(pr.rM * dpm, q.r, eps)
            assertEquals(pr.widthDeg, (2 * atan(q.w / 2 / q.r) * 180 / PI).toFloat(), 1e-3f)
            assertEquals("aspect kept", p.w / p.h, q.w / q.h, 1e-4f)
        }
        val ed = d.preset(p, Comfort.EDITOR)
        assertEquals((PI / 4).toFloat(), ed.theta, 1e-5f); assertEquals(0f, ed.y, 0f)
        assertEquals("side keeps its place on the arc", p.theta, d.preset(p, Comfort.SIDE).theta, 0f)
    }

    @Test fun nudgeDepthSizeCenter() {
        val d = desk(win(7, "Code", "a")); d.noteClicked(7)
        val p = d.panels.getValue(7)
        d.command(Command("nudge", dtheta = 1), null, 0)
        d.command(Command("nudge", dy = -1), null, 0)
        var q = d.panels.getValue(7)
        assertEquals(p.theta + rad(5f), q.theta, 1e-5f); assertEquals(p.y - 50f, q.y, eps)
        d.command(Command("depth", d = -1), null, 0)
        assertEquals(p.r - 100f, d.panels.getValue(7).r, eps)
        d.command(Command("size", d = 1), null, 0)
        q = d.panels.getValue(7)
        assertEquals(p.w * 1.1f, q.w, eps); assertEquals(p.h * 1.1f, q.h, eps)
        d.command(Command("depth", d = 100), null, 0)
        assertEquals("clamped in front of the shell", Desk.MAX_R_M * dpm, d.panels.getValue(7).r, eps)
        d.command(Command("center"), null, 0)
        assertEquals(d.preset(q, Comfort.EDITOR).r, d.panels.getValue(7).r, eps)
        assertEquals(d.forward, d.panels.getValue(7).theta, 0f)
    }

    @Test fun activePanelPrecedence() {
        val d = desk(win(7, "Code", "a"), win(8, "Chrome", "b"))
        val gaze8 = (d.planePoint(d.panels.getValue(8), 0f, 0f) - d.center).let { Ray(d.center, it * (1f / it.let { v -> kotlin.math.sqrt(v dot v) })) }
        assertEquals("head", 8, d.activePanel(gaze8))
        assertNull("no head, nothing clicked", d.activePanel(null))
        d.noteClicked(7)
        assertEquals("last clicked beats head", 7, d.activePanel(gaze8))
        val gazePicker = (d.planePoint(d.panels.getValue(Desk.PICKER), 0f, 0f) - d.center).let { Ray(d.center, it * (1f / kotlin.math.sqrt(it dot it))) }
        val fresh = desk(win(7, "Code", "a"))
        assertNull("picker is never active", fresh.activePanel(gazePicker))
        assertTrue(d.command(Command("nudge", dtheta = 1), null, 0).startsWith("nudge window 7"))
        assertEquals("nudge: no active panel", fresh.command(Command("nudge", dtheta = 1), null, 0))
    }

    @Test fun capturedIsActive() {
        val d = desk(win(7, "Code", "a"), win(8, "Chrome", "b"))
        d.control = true
        d.noteClicked(7)
        // Click into 8's content with the cursor: captured and last-clicked.
        val p = d.panels.getValue(8)
        repeat(6) {
            val g = d.cursorDpPerPoint(); val r = d.hover?.let { d.panels.getValue(it.id).r } ?: p.r
            d.pointer(Pointer(dx = (p.theta - d.cursorTheta) * r / g, dy = -((p.y) / p.r - d.cursorT) * r / g))
        }
        d.pointer(Pointer(buttons = 1)); d.pointer(Pointer(buttons = 0))
        assertEquals(8, d.captured)
        assertEquals(8, d.activePanel(null))
    }

    // --- undo / redo ---

    @Test fun undoRedoCommittedChanges() {
        val d = desk(win(7, "Code", "a")); d.noteClicked(7)
        val p0 = d.panels.getValue(7)
        d.command(Command("depth", d = 1), null, 0)
        val p1 = d.panels.getValue(7)
        d.command(Command("size", d = 1), null, 5_000)
        val p2 = d.panels.getValue(7)
        assertTrue(d.undo()); assertEquals(p1, d.panels.getValue(7))
        assertTrue(d.undo()); assertEquals(p0, d.panels.getValue(7))
        assertFalse(d.undo())
        assertTrue(d.redo()); assertEquals(p1, d.panels.getValue(7))
        assertTrue(d.redo()); assertEquals(p2, d.panels.getValue(7))
        assertFalse(d.redo())
        d.undo(); d.command(Command("nudge", dy = 1), null, 9_000)
        assertFalse("a new change drops the redo branch", d.canRedo)
    }

    @Test fun keyRepeatFoldsIntoOneStep() {
        val d = desk(win(7, "Code", "a")); d.noteClicked(7)
        val p0 = d.panels.getValue(7)
        repeat(5) { d.command(Command("nudge", dtheta = 1), null, 1_000L + it * 100) }
        d.undo()
        assertEquals(p0, d.panels.getValue(7))
        assertFalse(d.canUndo)
    }

    @Test fun undoDepthIsBounded() {
        val d = desk(win(7, "Code", "a")); d.noteClicked(7)
        repeat(Comfort.UNDO_DEPTH + 10) { d.command(Command("nudge", dy = 1), null, it * 10_000L) }
        var n = 0
        while (d.undo()) n++
        assertEquals(Comfort.UNDO_DEPTH, n)
    }

    @Test fun barDragAndHandMoveCommit() {
        val d = desk(win(7, "Code", "a")); d.control = true
        val p = d.panels.getValue(7)
        val t = (p.y + Bar.v(p)) / p.r
        d.pointer(Pointer(dx = (p.theta - d.cursorTheta) * p.r, dy = -(t - d.cursorT) * p.r))
        assertEquals(Hover(7, Zone.BAR), d.hover)
        d.pointer(Pointer(buttons = 1)); d.pointer(Pointer(dx = 80f, buttons = 1))
        assertFalse("not committed mid-drag", d.canUndo)
        d.pointer(Pointer(buttons = 0))
        assertTrue(d.canUndo)
        d.moveTo(7, d.center + Vec3(0f, 0f, -1500f)); d.commit()
        d.undo(); d.undo()
        assertEquals(p, d.panels.getValue(7))
    }

    @Test fun showHideIsUndoable() {
        val d = desk(win(7, "Code", "a"), win(8, "Chrome", "b")); d.noteClicked(7)
        d.command(Command("depth", d = 1), null, 0)
        val p8 = d.panels.getValue(8)
        d.toggle(8, 1000, 500) // hide 8
        assertEquals(listOf(7), d.shown)
        assertTrue(d.undo())
        assertEquals("undo brings the hidden window back where it was", setOf(7, 8), d.shown.toSet())
        assertEquals(p8, d.panels.getValue(8))
        assertTrue(d.redo()); assertEquals(listOf(7), d.shown)
    }

    @Test fun matchByAppTitleThenUniqueApp() {
        val e = Saved("Chrome", "Docs", 0f, 0f, 1f, 400f, 200f, shown = true)
        val docs = win(1, "Chrome", "Docs"); val mail = win(2, "Chrome", "Mail"); val term = win(3, "Terminal", "zsh")
        assertEquals(docs, matchWindow(e, listOf(mail, docs, term), listOf(e)))
        assertEquals("app-only when unique", mail, matchWindow(e, listOf(mail, term), listOf(e)))
        assertNull("ambiguous app-only", matchWindow(e, listOf(mail, win(4, "Chrome", "News"), term), listOf(e)))
        val mailEntry = Saved("Chrome", "Mail", 0f, 0f, 1f, 400f, 200f, shown = false)
        assertNull("Mail is remembered as its own entry", matchWindow(e, listOf(mail, term), listOf(e, mailEntry)))
        assertNull(matchWindow(e, listOf(term), listOf(e)))
    }

    @Test fun layoutSurvivesRestartAndIdChanges() {
        val d = desk(win(7, "Code", "main.kt"), win(8, "Chrome", "Docs")); d.noteClicked(7)
        d.command(Command("preset", name = "side"), null, 0)
        val p7 = d.panels.getValue(7); val p8 = d.panels.getValue(8)
        val json = LayoutJson.write(d.layoutState())

        // New process; the Mac streamer restarted too, so window IDs changed.
        val e = Desk(dpm); e.restore(LayoutJson.read(json))
        assertTrue(e.shown.isEmpty())
        assertTrue("restored -> resubscribe", e.syncWindows(listOf(win(70, "Code", "main.kt"), win(80, "Chrome", "Docs"), win(90, "Mail", "Inbox"))))
        assertEquals(setOf(70, 80), e.shown.toSet())
        assertEquals(p7, e.panels.getValue(70)); assertEquals(p8, e.panels.getValue(80))
    }

    @Test fun missingWindowsKeptForLater() {
        val d = desk(win(7, "Code", "main.kt"), win(8, "Chrome", "Docs"))
        val p8 = d.panels.getValue(8)
        assertTrue(d.syncWindows(listOf(win(7, "Code", "main.kt")))) // Chrome closed / streamer lost it
        assertEquals(listOf(7), d.shown)
        assertFalse(d.syncWindows(listOf(win(7, "Code", "main.kt"))))
        assertTrue("comes back where it was", d.syncWindows(listOf(win(7, "Code", "main.kt"), win(81, "Chrome", "Docs"))))
        assertEquals(p8, d.panels.getValue(81))
    }

    @Test fun hiddenWindowsStayHiddenButRememberTheirSpot() {
        val d = desk(win(7, "Code", "main.kt")); d.noteClicked(7)
        d.command(Command("depth", d = 2), null, 0)
        val p = d.panels.getValue(7)
        d.toggle(7, 1000, 500)
        assertFalse("hidden by the user: not auto-restored", d.syncWindows(listOf(win(7, "Code", "main.kt"))))
        assertTrue(d.shown.isEmpty())
        d.toggle(7, 1000, 500)
        assertEquals("re-shown at its old spot", p, d.panels.getValue(7))
    }

    @Test fun pickerAndEnvPersist() {
        val d = desk(win(7, "Code", "a"))
        d.command(Command("dim", d = -2), null, 0)
        d.command(Command("cutout", dw = 2, dh = 1), null, 0)
        d.command(Command("passthrough"), null, 0)
        d.control = true
        val pk = d.panels.getValue(Desk.PICKER)
        val e = Desk(dpm); e.restore(LayoutJson.read(LayoutJson.write(d.layoutState())))
        assertEquals(d.env, e.env)
        assertEquals(pk.theta, e.panels.getValue(Desk.PICKER).theta, 0f)
    }

    // --- environment ---

    @Test fun dimAndPassthroughAlpha() {
        var env = Env()
        assertEquals(1f, env.shellAlpha, 0f)
        env = env.dimmed(-2); assertEquals(Math.pow(0.6, 1 / 2.2).toFloat(), env.shellAlpha, 1e-6f)
        // Perceptually even: each step changes perceived darkness by the same amount (alpha^2.2 is linear in dim).
        val steps = (0..5).map { Math.pow(Env(dim = it).shellAlpha.toDouble(), 2.2) }
        steps.zipWithNext().forEach { (a, b) -> assertEquals(0.2, b - a, 1e-6) }
        env = env.dimmed(-10); assertEquals(0, env.dim); assertEquals(0f, env.shellAlpha, 0f)
        env = env.dimmed(+10); assertEquals(Comfort.DIM_MAX, env.dim)
        assertEquals("show the room hides the shell", 0f, env.copy(passthrough = true).shellAlpha, 0f)
    }

    @Test fun cutoutSteps() {
        val e = Env()
        val wider = e.cut(1, 0)
        assertEquals(e.cutHalfYaw + Comfort.CUTOUT_YAW_STEP / 2, wider.cutHalfYaw, 1e-6f)
        assertEquals(e.cutTop + Comfort.CUTOUT_TOP_STEP, e.cut(0, 1).cutTop, 1e-6f)
        assertEquals(0f, e.cut(-100, 0).cutHalfYaw, 0f)
        assertEquals(Shell.PITCH_TOP - Comfort.CUTOUT_MIN_TOP_GAP, e.cut(0, 100).cutTop, 0f)
        assertEquals(Shell.PITCH_BOTTOM, e.cut(0, -100).cutTop, 0f)
    }

    @Test fun shellTilesAnyCutout() {
        val r = Shell.RADIUS_M * dpm
        for (half in listOf(0f, 12.5f, 25f, 37.5f, 90f)) {
            val strips = Shell.strips(dpm, 0f, half, -10f)
            // Total angular coverage is the full ring (each strip covers its step; OVERLAP only adds).
            val total = strips.sumOf { (2 * atan(it.w / 2 / Shell.OVERLAP / it.r) * 180 / PI) }
            assertEquals("half=$half", 360.0, total, 0.01)
            strips.forEach { s ->
                val off = abs((s.theta * 180 / PI).toFloat())
                val bottomPitch = (atan((s.y - s.h / 2) / r) * 180 / PI).toFloat()
                assertEquals(if (off < half) -10f else Shell.PITCH_BOTTOM, bottomPitch, 1e-2f)
                assertTrue(s.w <= 2 * r * tan(rad(5f)) * Shell.OVERLAP + 1e-2f)
            }
        }
    }
}
