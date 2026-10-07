package dev.mindcastle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceTest {
    private val slack = MacWindow(1, "Slack", "general — Acme", 1000, 500)
    private val chromeDocs = MacWindow(2, "Google Chrome", "Design doc — Google Docs", 1000, 500)
    private val chromeMail = MacWindow(3, "Google Chrome", "Inbox — Gmail", 1000, 500)
    private val term = MacWindow(4, "Terminal", "zsh — slackbot", 1000, 500)
    private val all = listOf(slack, chromeDocs, chromeMail, term)

    @Test fun parseWindowAndVoice() {
        assertEquals(Command("show", window = "Slack"), Command.parse("""{"cmd":"show","window":"Slack"}"""))
        assertEquals(Command("nudge", dtheta = 1), Command.parse("""{"cmd":"nudge","dtheta":1}"""))
        assertEquals(Voice("listening", "move slack"), Voice.parse("""{"state":"listening","text":"move slack"}"""))
    }

    @Test fun matchingIsCaseInsensitiveAndPrefersBetterMatches() {
        assertEquals("exact app beats a title mention", slack, findWindow("slack", all, emptySet()))
        assertEquals(chromeMail, findWindow("GMAIL", all, emptySet()))
        assertEquals("prefix", chromeDocs, findWindow("design", all, emptySet()))
        assertNull(findWindow("photoshop", all, emptySet()))
        assertNull(findWindow("  ", all, emptySet()))
    }

    @Test fun shownWindowsWin() {
        // Both Chrome windows match "chrome"; the shown one is meant.
        assertEquals(chromeMail, findWindow("chrome", all, setOf(3)))
        assertEquals(chromeDocs, findWindow("chrome", all, setOf(2)))
        // Even a weaker match among shown windows beats a better one that isn't up.
        assertEquals(term, findWindow("slack", all, setOf(4)))
    }

    private fun desk(): Desk = Desk(1000f).apply { syncWindows(all) }

    @Test fun showHideByName() {
        val d = desk()
        assertTrue(d.command(Command("show", window = "slack"), null, 0).startsWith("show window 1"))
        assertEquals(listOf(1), d.shown)
        assertEquals("shown window becomes the active panel", 1, d.activePanel(null))
        assertTrue(d.command(Command("show", window = "slack"), null, 0).contains("already up"))
        assertEquals(listOf(1), d.shown)
        assertEquals("hide window 1", d.command(Command("hide", window = "Slack"), null, 0))
        assertTrue(d.shown.isEmpty())
        assertEquals("show: no window matches 'excel'", d.command(Command("show", window = "excel"), null, 0))
    }

    @Test fun layoutCommandsTargetTheNamedWindow() {
        val d = desk()
        d.command(Command("show", window = "slack"), null, 0)
        d.command(Command("show", window = "gmail"), null, 0) // now the active panel
        val s0 = d.panels.getValue(1); val g0 = d.panels.getValue(3)
        d.command(Command("depth", d = 1, window = "slack"), null, 0)
        assertEquals(s0.r + Comfort.DEPTH_M * 1000f, d.panels.getValue(1).r, 1e-3f)
        assertEquals("the active panel didn't move", g0, d.panels.getValue(3))
        assertEquals("depth: 'docs' isn't up", d.command(Command("depth", d = 1, window = "docs"), null, 0))
    }

    @Test fun focusMakesActiveAndFlashes() {
        val d = desk()
        d.command(Command("show", window = "slack"), null, 0)
        d.command(Command("show", window = "terminal"), null, 0)
        assertEquals(4, d.activePanel(null))
        assertEquals("focus window 1", d.command(Command("focus", window = "slack"), null, 5_000))
        assertEquals(1, d.activePanel(null))
        assertEquals(1, d.flashId); assertEquals(5_000 + Desk.FLASH_MS, d.flashUntilMs)
        // Untargeted commands now go to it.
        val p = d.panels.getValue(1)
        d.command(Command("nudge", dy = 1), null, 6_000)
        assertEquals(p.y + Comfort.NUDGE_Y_M * 1000f, d.panels.getValue(1).y, 1e-3f)
    }

    @Test fun idBeatsName() {
        assertEquals(Command("show", window = "slack", id = 4), Command.parse("""{"cmd":"show","window":"slack","id":4}"""))
        val d = desk()
        // The Mac resolved "slack" to the terminal window 4 (say, a "slackbot" session): trust the id.
        assertTrue(d.command(Command("show", window = "slack", id = 4), null, 0).startsWith("show window 4"))
        assertEquals(listOf(4), d.shown)
        // Unknown id: fall back to the name, shown windows first — the "slackbot" terminal that's already up.
        assertEquals("show (already up) window 4", d.command(Command("show", window = "slack", id = 999), null, 0))
    }

    @Test fun multiStepAndOneUndoPerVoiceCommand() {
        val d = desk()
        d.command(Command("show", window = "slack", id = 1), null, 0)
        val p = d.panels.getValue(1)
        d.command(Command("nudge", dtheta = -3, window = "slack", id = 1), null, 100)
        assertEquals(p.theta - 3 * Math.toRadians(Comfort.NUDGE_DEG.toDouble()).toFloat(), d.panels.getValue(1).theta, 1e-5f)
        d.command(Command("nudge", dtheta = -3, window = "slack", id = 1), null, 200) // same cmd, 100 ms later: still its own step
        d.undo()
        assertEquals(p.theta - 3 * Math.toRadians(Comfort.NUDGE_DEG.toDouble()).toFloat(), d.panels.getValue(1).theta, 1e-5f)
        d.undo(); assertEquals(p, d.panels.getValue(1))
        d.undo(); assertTrue("and the show itself undoes", d.shown.isEmpty())
    }

    @Test fun hideByIdIsUndoable() {
        val d = desk()
        d.command(Command("show", window = "gmail", id = 3), null, 0)
        d.command(Command("hide", window = "gmail", id = 3), null, 10)
        assertTrue(d.shown.isEmpty())
        d.command(Command("undo"), null, 20)
        assertEquals(listOf(3), d.shown)
    }
}
