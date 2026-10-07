package dev.mindcastle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.tan

class ShellTest {
    private val dpm = 1000f
    private val r = Shell.RADIUS_M * dpm
    private fun rad(d: Float) = (d * PI / 180).toFloat()
    private fun deg(r: Float) = (r * 180 / PI).toFloat()
    /** Signed angle difference in degrees, wrapped to (-180, 180]. */
    private fun diff(a: Float, b: Float) = ((deg(a - b) % 360 + 540) % 360) - 180

    @Test fun stripsRingTheUserWithoutSeams() {
        val fwd = rad(30f)
        val s = Shell.strips(dpm, fwd).sortedBy { diff(it.theta, fwd) }
        assertEquals(Shell.STRIPS, s.size)
        s.forEach { assertEquals(r, it.r, 1e-2f) }
        // Each strip's half-arc reaches past halfway to its neighbour (wrap-around included).
        for (i in s.indices) {
            val a = s[i]; val b = s[(i + 1) % s.size]
            val gap = abs(diff(b.theta, a.theta))
            assertTrue("seam between $i and ${i + 1}", deg(a.halfAngle) + deg(b.halfAngle) >= gap - 1e-3f)
        }
    }

    @Test fun laptopWindowLeftOpenBelowForward() {
        val fwd = rad(-40f)
        val top = r * tan(rad(Shell.PITCH_TOP))
        for (s in Shell.strips(dpm, fwd)) {
            val off = diff(s.theta, fwd)
            val bottom = s.y - s.h / 2
            assertEquals("top edge at pitch ${Shell.PITCH_TOP}", top, s.y + s.h / 2, 0.5f)
            val expected = if (abs(off) < Shell.WINDOW_HALF_YAW) Shell.WINDOW_PITCH_TOP else Shell.PITCH_BOTTOM
            assertEquals("strip at $off°", expected, deg(atan(bottom / r)), 1e-2f)
        }
        // The window is exactly whole strips: edges line up with ±WINDOW_HALF_YAW.
        val inWindow = Shell.strips(dpm, fwd).count { abs(diff(it.theta, fwd)) < Shell.WINDOW_HALF_YAW }
        assertEquals((2 * Shell.WINDOW_HALF_YAW / (360f / Shell.STRIPS)).toInt(), inWindow)
    }

    @Test fun capsCloseTheEnds() {
        val (top, bottom) = Shell.caps(dpm)
        assertEquals(r * tan(rad(Shell.PITCH_TOP)), top.y, 0.5f); assertTrue(!top.up)
        assertEquals(r * tan(rad(Shell.PITCH_BOTTOM)), bottom.y, 0.5f); assertTrue(bottom.up)
        // The square must cover the strips' outer corners (r / cos(half step)).
        assertTrue(top.side / 2 > r / kotlin.math.cos(rad(180f / Shell.STRIPS)))
    }

    @Test fun shellIsOutsideWorkPanels() {
        assertTrue("panels pushed all the way out must stay in front of the shell", Desk.MAX_R_M < Shell.RADIUS_M)
    }

    @Test fun forwardComesFromHeadDirection() {
        val d = Desk(dpm)
        d.setCenterFromHead(Vec3(0f, 0f, 0f), Vec3(1f, 0f, 0f)) // looking right
        assertEquals((PI / 2).toFloat(), d.forward, 1e-5f)
        d.setCenterFromHead(Vec3(0f, 0f, 0f), Vec3(0f, -1f, 0f)) // straight down: keep the old yaw
        assertEquals((PI / 2).toFloat(), d.forward, 1e-5f)
    }

    @Test fun meshIsOneQuadPerStripAndCap() {
        val g = Shell.Geometry(dpm, Vec3(100f, 200f, 300f), 0.3f, Shell.WINDOW_HALF_YAW, Shell.WINDOW_PITCH_TOP)
        val m = Shell.mesh(g)
        val quads = Shell.strips(dpm, g.forward).size + 2
        assertEquals(quads * 4 * 3, m.positions.size)
        assertEquals(quads * 6, m.indices.size)
        assertTrue(m.indices.all { it in 0 until m.positions.size / 3 })
        // Meters, centered on the cylinder axis: every strip vertex is ~RADIUS_M from it horizontally.
        val c = Vec3(0.1f, 0.2f, 0.3f)
        for (v in 0 until Shell.strips(dpm, g.forward).size * 4) {
            val x = m.positions[v * 3] - c.x; val z = m.positions[v * 3 + 2] - c.z
            val d = kotlin.math.hypot(x, z)
            assertTrue("vertex $v at $d m", d >= Shell.RADIUS_M - 1e-3f && d <= Shell.RADIUS_M / kotlin.math.cos(rad(6f)))
        }
        assertEquals(c.y + Shell.RADIUS_M * tan(rad(Shell.PITCH_TOP)), m.max[1], 1e-3f)
        assertEquals(c.y + Shell.RADIUS_M * tan(rad(Shell.PITCH_BOTTOM)), m.min[1], 1e-3f)
    }

    @Test fun glbIsWellFormed() {
        val m = Shell.mesh(Shell.Geometry(dpm, Vec3(0f, 0f, 0f), 0f, Shell.WINDOW_HALF_YAW, Shell.WINDOW_PITCH_TOP))
        val b = java.nio.ByteBuffer.wrap(Glb.write(m.positions, m.indices, floatArrayOf(0f, 0f, 0f, 0.6f))).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        assertEquals(0x46546C67, b.getInt(0)); assertEquals(2, b.getInt(4)); assertEquals(b.capacity(), b.getInt(8))
        val jsLen = b.getInt(12); assertEquals(0x4E4F534A, b.getInt(16)); assertEquals(0, jsLen % 4)
        val json = org.json.JSONObject(String(b.array(), 20, jsLen, Charsets.UTF_8).trim())
        val binLen = b.getInt(20 + jsLen); assertEquals(0x004E4942, b.getInt(24 + jsLen))
        assertEquals(b.capacity(), 28 + jsLen + binLen)
        assertEquals(m.positions.size * 4 + m.indices.size * 4, binLen)
        val mat = json.getJSONArray("materials").getJSONObject(0)
        assertEquals("BLEND", mat.getString("alphaMode")); assertTrue(mat.getBoolean("doubleSided"))
        assertEquals(0.6, mat.getJSONObject("pbrMetallicRoughness").getJSONArray("baseColorFactor").getDouble(3), 1e-6)
        val acc = json.getJSONArray("accessors")
        assertEquals(m.positions.size / 3, acc.getJSONObject(0).getInt("count"))
        assertEquals(m.indices.size, acc.getJSONObject(1).getInt("count"))
        // First position round-trips through the BIN chunk.
        assertEquals(m.positions[0], b.getFloat(28 + jsLen), 0f)
        assertEquals("OPAQUE", org.json.JSONObject(String(Glb.write(m.positions, m.indices, floatArrayOf(0f, 0f, 0f, 1f)), 20,
            java.nio.ByteBuffer.wrap(Glb.write(m.positions, m.indices, floatArrayOf(0f, 0f, 0f, 1f))).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt(12)).trim())
            .getJSONArray("materials").getJSONObject(0).getString("alphaMode"))
    }
}
