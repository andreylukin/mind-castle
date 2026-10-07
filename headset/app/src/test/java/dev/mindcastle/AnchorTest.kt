package dev.mindcastle

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class AnchorTest {
    private fun rad(d: Float) = Math.toRadians(d.toDouble()).toFloat()
    private fun near(e: Vec3, a: Vec3, tol: Float = 1e-3f) {
        assertEquals("x", e.x, a.x, tol); assertEquals("y", e.y, a.y, tol); assertEquals("z", e.z, a.z, tol)
    }

    /** Head looking with yaw [yaw] (positive = right) and pitch [pitch] (positive = up), no roll. */
    private fun head(yaw: Float, pitch: Float): Pair<Vec3, Vec3> {
        val f = Vec3(sin(rad(yaw)) * cos(rad(pitch)), sin(rad(pitch)), -cos(rad(yaw)) * cos(rad(pitch)))
        val u = Vec3(-sin(rad(yaw)) * sin(rad(pitch)), cos(rad(pitch)), cos(rad(yaw)) * sin(rad(pitch)))
        return f to u
    }

    @Test fun uprightIsIdentityRotation() {
        val (f, u) = head(0f, 0f)
        val a = Anchor.fromHead(Vec3(1f, 2f, 3f), f, u)
        assertEquals(Anchor(Vec3(1f, 2f, 3f)), a)
        near(Vec3(1f, 2f, 3f - 1200f), a.toWorld(Vec3(0f, 0f, -1200f)))
    }

    @Test fun smallPitchSnapsToLevel() {
        val (f, u) = head(20f, 12f)
        val a = Anchor.fromHead(Vec3(0f, 0f, 0f), f, u)
        assertEquals(0f, a.pitchDeg, 0f)
        assertEquals("yaw kept: looking right is a negative rotation about Y", -20f, a.yawDeg, 1e-3f)
        // Straight ahead in the layout frame lands where the head looks (level).
        near(Vec3(sin(rad(20f)), 0f, -cos(rad(20f))), a.rotate(Vec3(0f, 0f, -1f)))
    }

    @Test fun lyingDownPitchesTheWholeFrame() {
        val (f, u) = head(0f, 70f) // on your back, looking at the ceiling
        val a = Anchor.fromHead(Vec3(0f, 0f, 0f), f, u)
        assertEquals(70f, a.pitchDeg, 1e-3f); assertEquals(0f, a.rollDeg, 1e-3f)
        near(f, a.rotate(Vec3(0f, 0f, -1f)))
        near(u, a.rotate(Vec3(0f, 1f, 0f)))
        // A panel straight ahead at 1.2 m sits 1.2 m along the gaze; one 30° to the right stays at eye height in the frame.
        val desk = Desk(1000f).apply { setAnchor(a) }
        val ahead = Panel(0f, 0f, 1200f, 400f, 200f)
        near(f * 1200f, a.toWorld(desk.position(ahead)), 0.05f)
        val right = Panel(rad(30f), 0f, 1200f, 400f, 200f)
        val w = a.toWorld(desk.position(right))
        assertEquals(1200f, kotlin.math.sqrt(w dot w), 0.05f)
        assertEquals("lifted toward the ceiling by the frame pitch", 1200f * cos(rad(30f)) * sin(rad(70f)), w.y, 0.05f)
        // The laptop cutout (below forward in the frame) points toward the chest: frame-down is world -y rotated forward.
        val cutDir = a.rotate(Vec3(0f, -sin(rad(40f)), -cos(rad(40f))))
        assertEquals(sin(rad(70f - 40f)), cutDir.y, 1e-4f)
    }

    @Test fun rollOnlyWhenLyingOnASide() {
        val (f, _) = head(0f, 0f)
        val tilt = Anchor.fromHead(Vec3(0f, 0f, 0f), f, Vec3(-sin(rad(20f)), cos(rad(20f)), 0f))
        assertEquals("casual tilt ignored", 0f, tilt.rollDeg, 0f)
        val side = Anchor.fromHead(Vec3(0f, 0f, 0f), f, Vec3(-1f, 0f, 0f)) // head's up points left
        assertEquals(90f, side.rollDeg, 1e-3f)
        near(Vec3(-1f, 0f, 0f), side.rotate(Vec3(0f, 1f, 0f)))
    }

    @Test fun setAnchorResetsLocalCenterAndPersists() {
        val (f, u) = head(-35f, 60f)
        val a = Anchor.fromHead(Vec3(5f, 1500f, 900f), f, u)
        val d = Desk(1000f); d.setAnchor(a)
        assertEquals(Vec3(0f, 0f, 0f), d.center); assertEquals(0f, d.forward, 0f)
        val e = Desk(1000f); e.restore(LayoutJson.read(LayoutJson.write(d.layoutState())))
        assertEquals(a, e.anchor)
    }

    @Test fun lookingStraightUpFromTheDevice() {
        // 23:27:14 on the headset: recenter while lying on the back, facing (-0.17, 0.99, 0.03).
        val raw = Vec3(-0.17f, 0.99f, 0.03f)
        val f = raw * (1f / kotlin.math.sqrt(raw dot raw))
        // Head's up is perpendicular to the gaze, pointing (mostly) away from the feet: take -z, orthogonalised.
        val z = Vec3(0f, 0f, -1f)
        val uRaw = z - f * (z dot f)
        val u = uRaw * (1f / kotlin.math.sqrt(uRaw dot uRaw))
        val a = Anchor.fromHead(Vec3(0f, 0f, 0f), f, u)
        assertEquals(asinDeg(f.y), a.pitchDeg, 1e-3f)
        // The frame reproduces the head: layout forward = gaze, layout up = head up (no degenerate yaw).
        near(f, a.rotate(Vec3(0f, 0f, -1f)), 2e-3f)
        near(u, a.rotate(Vec3(0f, 1f, 0f)), 2e-3f)
        // A panel straight ahead in the layout is straight up in the room.
        val d = Desk(1000f).apply { setAnchor(a) }
        val w = a.toWorld(d.position(Panel(0f, 0f, 1200f, 400f, 200f)))
        assertEquals(1200f * f.y, w.y, 1f)
    }

    @Test fun exactlyVerticalGazeUsesHeadUpForHeading() {
        val a = Anchor.fromHead(Vec3(0f, 0f, 0f), Vec3(0f, 1f, 0f), Vec3(1f, 0f, 0f))
        near(Vec3(0f, 1f, 0f), a.rotate(Vec3(0f, 0f, -1f)))
        near(Vec3(1f, 0f, 0f), a.rotate(Vec3(0f, 1f, 0f)))
    }

    @Test fun pitchedHeadKeepsSmallRoll() {
        // Lying back at 50° with a 20° head tilt: the frame follows the head exactly (no roll snap when pitched).
        val (f, u0) = head(10f, 50f)
        val axis = f
        val c = cos(rad(20f)); val s = sin(rad(20f))
        val cross = Vec3(axis.y * u0.z - axis.z * u0.y, axis.z * u0.x - axis.x * u0.z, axis.x * u0.y - axis.y * u0.x)
        val u = u0 * c + cross * s // u0 rotated 20° about the gaze
        val a = Anchor.fromHead(Vec3(0f, 0f, 0f), f, u)
        near(f, a.rotate(Vec3(0f, 0f, -1f)), 2e-3f)
        near(u, a.rotate(Vec3(0f, 1f, 0f)), 2e-3f)
    }

    private fun asinDeg(y: Float) = Math.toDegrees(kotlin.math.asin(y.toDouble())).toFloat()
}
