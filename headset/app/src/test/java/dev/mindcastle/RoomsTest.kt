package dev.mindcastle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomsTest {
    private val store = HashMap<String, String>()
    private val rooms = Rooms({ store[it] }, { k, v -> if (v == null) store.remove(k) else store[k] = v })
    private fun win(id: Int, app: String, title: String) = MacWindow(id, app, title, 1000, 500)

    @Test fun parseHello() {
        assertEquals(Host("a1b2", "Work MacBook", "6"), Host.parse("""{"host":"Work MacBook","id":"a1b2","version":"6"}"""))
        assertEquals(Host.DEFAULT, Host.parse("{}"))
    }

    @Test fun legacyLayoutMigratesToFirstHostOnly() {
        store[Rooms.LEGACY] = "OLD"
        assertEquals("OLD", rooms.load("personal"))
        assertNull(store[Rooms.LEGACY])
        assertEquals("personal", store[Rooms.MIGRATED_TO])
        assertNull("the second Mac starts fresh", rooms.load("work"))
        assertEquals("OLD", rooms.load("personal"))
    }

    @Test fun eachMacKeepsItsOwnRoom() {
        val d = Desk(1000f)
        // Personal Mac: Code window pushed back, dimmed.
        d.loadRoom(null)
        d.syncWindows(listOf(win(7, "Code", "main.kt"))); d.toggle(7, 1000, 500); d.noteClicked(7)
        d.command(Command("depth", d = 2), null, 0); d.command(Command("dim", d = -2), null, 0)
        val personal7 = d.panels.getValue(7)
        rooms.save("personal", LayoutJson.write(d.layoutState()))

        // Switch to the work Mac: nothing carried over, undo reset — even though its window id collides (7).
        val epoch = d.roomEpoch
        d.loadRoom(rooms.load("work")?.let(LayoutJson::read))
        assertTrue(d.roomEpoch > epoch)
        assertTrue(d.shown.isEmpty()); assertEquals(Env(), d.env); assertTrue(!d.canUndo)
        assertTrue("work's window 7 isn't auto-shown", !d.syncWindows(listOf(win(7, "Slack", "general"))))
        d.toggle(7, 1000, 500)
        assertTrue(d.panels.getValue(7) != personal7)
        rooms.save("work", LayoutJson.write(d.layoutState()))

        // Back to the personal Mac: its room comes back (window re-bound by app + title).
        d.loadRoom(rooms.load("personal")?.let(LayoutJson::read))
        assertEquals(3, d.env.dim)
        assertTrue(d.syncWindows(listOf(win(7, "Code", "main.kt"))))
        assertEquals(personal7, d.panels.getValue(7))
    }

    @Test fun firstLoadKeepsThePickerPanel() {
        val d = Desk(1000f)
        d.loadRoom(null)
        assertEquals("no windows yet: panels keep their keys", 0, d.roomEpoch)
    }
}
