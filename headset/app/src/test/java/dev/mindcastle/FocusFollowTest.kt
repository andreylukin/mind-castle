package dev.mindcastle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class FocusFollowTest {
    private fun win(id: Int, app: String, title: String) = MacWindow(id, app, title, 1000, 500)
    private val all = listOf(win(1, "Code", "a"), win(2, "Slack", "b"), win(3, "Terminal", "c"))

    private fun desk(): Desk = Desk(1000f).apply {
        syncWindows(all); toggle(1, 1000, 500); toggle(2, 1000, 500)
        control = true
    }

    @Test fun parse() {
        assertEquals(FocusChanged(2, "Slack", "b"), FocusChanged.parse("""{"id":2,"app":"Slack","title":"b"}"""))
        assertEquals(Overlay(456, "Raycast", true, 1500, 900), Overlay.parse("""{"id":456,"app":"Raycast","visible":true,"w":1500,"h":900}"""))
        assertEquals(false, Overlay.parse("""{"id":456,"visible":false}""").visible)
    }

    @Test fun shownWindowBecomesActiveFlashesAndGetsTheCursor() {
        val d = desk()
        assertTrue(d.focusChanged(2, 5_000).endsWith("active"))
        assertEquals(2, d.activePanel(null)); assertEquals(2, d.flashId); assertEquals(2, d.focusTarget)
        val p = d.panels.getValue(2)
        assertEquals(p.theta, d.cursorTheta, 1e-5f); assertEquals(p.y / p.r, d.cursorT, 1e-6f)
        assertEquals(2, d.hover?.id)
    }

    @Test fun cursorAlreadyOnItStaysPut() {
        val d = desk(); d.focusChanged(2, 1_000)
        d.pointer(Pointer(dx = 30f, dy = 20f), nowMs = 1_100) // move within window 2
        val th = d.cursorTheta; val t = d.cursorT
        d.focusChanged(2, 2_000)
        assertEquals(th, d.cursorTheta, 0f); assertEquals(t, d.cursorT, 0f)
    }

    @Test fun hiddenWindowIsShownNextToTheActivePanel() {
        val d = desk(); d.noteClicked(1)
        assertTrue(d.focusChanged(3, 1_000).endsWith("shown"))
        assertTrue(3 in d.shown)
        val a = d.panels.getValue(1); val p = d.panels.getValue(3)
        assertEquals(a.theta + a.halfAngle + Desk.GAP / a.r + kotlin.math.atan(p.w / 2 / a.r), p.theta, 1e-4f)
        assertEquals(3, d.activePanel(null))
    }

    @Test fun releasesCaptureInAnotherWindow() {
        val d = desk(); val p1 = d.panels.getValue(1)
        d.pointer(Pointer(dx = (p1.theta - d.cursorTheta) * p1.r, dy = -(p1.y / p1.r - d.cursorT) * p1.r))
        d.pointer(Pointer(buttons = 1)); d.pointer(Pointer(buttons = 0))
        assertEquals(1, d.captured)
        d.focusChanged(2, 3_000)
        assertNull(d.captured); assertEquals(2, d.hover?.id)
    }

    @Test fun unknownWindowWaitsForTheWindowList() {
        val d = desk()
        assertTrue(d.focusChanged(9, 1_000).contains("pending"))
        assertTrue(9 !in d.shown)
        assertTrue("sync shows it -> resubscribe", d.syncWindows(all + win(9, "Raycast Notes", "n")))
        assertTrue(9 in d.shown); assertEquals(9, d.activePanel(null))
    }

    @Test fun offViewArrowOnlyWhenOutOfView() {
        val head = Ray(Vec3(0f, 0f, 0f), Vec3(0f, 0f, -1f))
        assertNull("straight ahead", offViewArrow(head, Vec3(100f, 0f, -1000f), 1000f))
        val a = offViewArrow(head, Vec3(1000f, 0f, 0f), 1000f) // 90° to the right
        assertNotNull(a)
        assertEquals("glyph points right", 0f, a!!.screenAngle, 1e-4f)
        assertTrue("1 m ahead, nudged right", a.pos.x > 0f && abs(a.pos.z + 1000f) < 1f)
        val up = offViewArrow(head, Vec3(0f, 1000f, -100f), 1000f)!!
        assertEquals((Math.PI / 2).toFloat(), up.screenAngle, 1e-3f)
    }
}
