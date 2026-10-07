package dev.mindcastle

import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tan

/** Subspace coordinates in dp: x right, y up, z toward the user's start view. */
data class Vec3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(k: Float) = Vec3(x * k, y * k, z * k)
    infix fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
}

data class Ray(val origin: Vec3, val dir: Vec3)

/**
 * A flat panel standing on a cylinder around the user: [theta] radians along the arc
 * (0 = straight ahead, positive = right), center [y] dp above eye level, [r] dp from the axis.
 * It always faces the eye: yawed to the axis and pitched by atan2(y, r), so a raised or lowered
 * panel tilts toward the head instead of keystoning. [w]/[h] in dp.
 */
data class Panel(val theta: Float, val y: Float, val r: Float, val w: Float, val h: Float) {
    /** Half the arc the panel covers, in radians. */
    val halfAngle get() = atan(w / 2 / r)
    /** Tilt toward the eye, radians: positive for a raised panel (it faces down at the head). */
    val pitch get() = atan2(y, r)
    /** Panel-plane axes: right, up, and the normal toward the eye (yaw = -theta, then pitch). */
    val right get() = Vec3(cos(theta), 0f, sin(theta))
    val up get() = Vec3(-sin(pitch) * sin(theta), cos(pitch), sin(pitch) * cos(theta))
    val normal get() = Vec3(-cos(pitch) * sin(theta), -sin(pitch), cos(pitch) * cos(theta))
    val yawDeg get() = Math.toDegrees(-theta.toDouble()).toFloat()
    val pitchDeg get() = Math.toDegrees(pitch.toDouble()).toFloat()
    /** Plane coords (u right, v up, from the panel center) -> window coords 0..1, top-left origin. */
    fun normX(u: Float) = ((u + w / 2) / w).coerceIn(0f, 1f)
    fun normY(v: Float) = ((h / 2 - v) / h).coerceIn(0f, 1f)
}

/**
 * Where the cursor is on a panel: its content, the grab bar under it, a resize corner, or (device
 * model, 2D mode) the panel's surrounding margin — on the panel, but on no control.
 */
enum class Zone { CONTENT, BAR, CORNER, MARGIN }

/** [cx]/[cy] say which corner for [Zone.CORNER]: -1 left/bottom, +1 right/top (y up). */
data class Hover(val id: Int, val zone: Zone, val cx: Int = 0, val cy: Int = 0)

/** PROTOCOL v1 POINTER. dx/dy in Mac points (dy positive = down); sy positive = scroll up. `mods` is ignored. */
data class Pointer(
    val dx: Float = 0f, val dy: Float = 0f, val buttons: Int = 0,
    val sx: Float = 0f, val sy: Float = 0f, val mods: Set<String> = emptySet(),
) {
    companion object {
        fun parse(json: String): Pointer = JSONObject(json).run {
            val m = optJSONArray("mods")
            Pointer(
                optDouble("dx", 0.0).toFloat(), optDouble("dy", 0.0).toFloat(), optInt("buttons", 0),
                optDouble("sx", 0.0).toFloat(), optDouble("sy", 0.0).toFloat(),
                if (m == null) emptySet() else (0 until m.length()).map { m.getString(it) }.toSet(),
            )
        }
    }
}

sealed class Action {
    /** PROTOCOL v2 MOUSE: [kind] is move/down/up/drag, [button] 0 left / 1 right. */
    data class Mouse(val id: Int, val kind: String, val x: Float, val y: Float, val button: Int = 0) : Action()
    data class Scroll(val id: Int, val x: Float, val y: Float, val dx: Int, val dy: Int) : Action()
    /** Cursor click on picker row [row] (index into the window list). */
    data class PickerRow(val row: Int) : Action()
}

object PickerMetrics {
    const val WIDTH = 420f
    const val PAD = 16f
    const val HEADER = 36f
    const val ROW = 44f
    fun height(rows: Int) = 2 * PAD + HEADER + max(rows, 1) * ROW
    /** Row under [yFromTop] dp (measured from the panel's top edge), or null if not on a row. */
    fun rowAt(yFromTop: Float, rows: Int): Int? {
        val r = ((yFromTop - PAD - HEADER) / ROW)
        return if (r < 0) null else r.toInt().takeIf { it < rows }
    }
}

/** The visionOS-style pill under a panel; v is its center in panel-plane coords. */
object Bar {
    const val GAP = 14f
    const val H = 10f
    const val PAD_X = 16f // hit area beyond the visible pill
    const val PAD_Y = 12f
    fun w(p: Panel) = (p.w * 0.3f).coerceIn(100f, 220f)
    fun v(p: Panel) = -p.h / 2 - GAP - H / 2
}

/**
 * Each panel's own "chrome", drawn inside its SpatialPanel around the content: a margin for the
 * hover outline + corner handles, and the grab bar underneath. Drawing these inside the panel
 * (instead of as separate panels) keeps the SpatialPanel count — each one leaks a system input
 * channel — at one per window. All dp; v is up from the content's center.
 */
object Chrome {
    const val PAD = 22f // left/top/right margin: outline (M out) plus half a hot corner handle (13)
    const val M = 8f // outline frame distance outside the content
    val BOTTOM get() = max(PAD, Bar.GAP + Bar.H) // the bar sits in the bottom margin
    fun panelW(p: Panel) = p.w + 2 * PAD
    fun panelH(p: Panel) = p.h + PAD + BOTTOM
    /** Where the SpatialPanel's center sits relative to the content center. */
    val centerV get() = (PAD - BOTTOM) / 2

    /** What the chrome shows right now ([hot] = the hovered corner's cx/cy). */
    data class State(
        val outline: Boolean = false, val dragging: Boolean = false, val hot: Pair<Int, Int>? = null,
        val corners: Boolean = false, val barHot: Boolean = false,
    )
}

/**
 * Layout and cursor state for everything in the Subspace. Not thread-safe: call from one thread.
 *
 * Panels sit on a cylinder centered on the user's start position ([center]) and face its axis.
 * The cursor lives on the same cylinder as an angle ([cursorTheta]) and an elevation tangent
 * ([cursorT]), so it reads continuously from the user's eye as it crosses panels at different
 * radii; on a panel it is projected onto that panel's plane. Overlaps: the nearest panel wins.
 */
class Desk(dpPerMeter: Float = 1000f, private val onChange: () -> Unit = {}) {
    companion object {
        const val PICKER = 0 // CGWindowID 0 is kCGNullWindowID, never a real window
        const val GAIN = 1f // dp per Mac point, at the cursor's current radius
        const val RADIUS_M = 1.2f
        const val MIN_R_M = 0.5f
        const val MAX_R_M = 2.8f // stay inside Shell.RADIUS_M or the black shell hides the panel
        const val R_PER_LINE_M = 0.03f
        const val MIN_W = 160f
        const val GAP = 60f // dp of arc between neighbours
        const val PANEL_SCALE = 0.4f // Retina pixels -> dp, shrunk so a laptop-sized window fits
        const val CORNER_IN = 10f // corner handle reaches this far into the content...
        const val CORNER_OUT = 28f // ...and this far outside it
        const val MAX_T = 1.7320508f // absolute elevation limit (tan 60°); around the arc the cursor wraps freely
        const val ELEV_MARGIN_DEG = 15f // the cursor may go this far above/below the panels...
        const val ELEV_FALLBACK_DEG = 35f // ...or ±this with no panels
        const val JUMP_IDLE_MS = 500L
        const val TIDY_MAX_DEG = 50f // widest a window gets when tidied
        const val EDGE_CUE_MS = 400L
        const val GESTURE_GAP_MS = 150L // a pause this long ends a travel gesture (for the log)
        const val FLASH_MS = 1200L // focus: outline flash length
        const val OVERSHOOT = 60f // dp of continued push past a captured window's edge before the cursor leaves
        const val ABSORB_MS = 300L // after leaving a window through its bottom onto the bar, eat that flick's momentum
        const val RETINA = 2f // WINDOW_LIST w/h are pixels; window points = pixels / RETINA
    }

    var dpPerMeter = dpPerMeter; private set
    val defaultR get() = RADIUS_M * dpPerMeter

    /** Cylinder axis (at eye height) in Subspace dp. Until the head pose is known, assume the user is defaultR behind the origin. */
    var center = Vec3(0f, 0f, defaultR); private set
    private var centerFromHead = false

    private val _panels = LinkedHashMap<Int, Panel>().apply {
        put(PICKER, Panel(-0.35f, 0f, defaultR, PickerMetrics.WIDTH, PickerMetrics.height(0)))
    }
    val panels: Map<Int, Panel> get() = _panels
    val shown: List<Int> get() = _panels.keys.filter { it != PICKER }

    var control = false
        set(v) { field = v; if (!v) { drag = null; buttons = 0; hover = null; mouse = null; release("control off") }; onChange() }

    /** Window points (w, h) per shown window, for native gain and cursor-image size. */
    private val winPts = HashMap<Int, Pair<Float, Float>>()
    /** Window the cursor is captured in (clicked into), or null. */
    var captured: Int? = null; private set
    /** Told about every capture start (id, "click") and end (null, reason). */
    var onCapture: (Int?, String) -> Unit = { _, _ -> }
    private var overX = 0f
    private var overY = 0f
    /** Until then, downward motion is swallowed (the cursor just landed on a bar from a captured window). */
    private var absorbUntilMs = Long.MIN_VALUE
    /** Look-to-jump on the first plain move after a pause. Off on device: with a trackpad, pauses are constant and the jump read as the cursor bouncing back. */
    var lookToJump = true
    var cursorTheta = 0f; private set
    var cursorT = 0f; private set
    /** Radius the cursor is drawn at when it's between panels: the last panel it was on. */
    private var cursorR = defaultR
    /** What the cursor is over (or dragging); drives the hover feedback. */
    var hover: Hover? = null; private set
    val dragging get() = drag != null

    private class Drag(val h: Hover, val theta: Float, val t: Float, val start: Panel)
    private var drag: Drag? = null
    /** Window holding a Mac mouse button: (id, button). Gets drag/up even if the cursor leaves it. */
    private var mouse: Pair<Int, Int>? = null
    private var buttons = 0
    private var lastPointerMs = Long.MIN_VALUE / 2
    private var scrollId = -1
    private var accX = 0f
    private var accY = 0f

    /** Window metadata (app/title) from WINDOW_LIST, for remembering panels by app + title. */
    private val meta = HashMap<Int, MacWindow>()
    /** Remembered panels, including windows not currently present. Persisted via [layoutState]. */
    private val saved = mutableListOf<Saved>()
    /** Shell dim / "show the room" / laptop cutout. */
    var env = Env(); private set
    /** Window last clicked into (cursor or hand) — the active panel when nothing is captured. */
    var lastClicked: Int? = null; private set
    /** Called after every committed layout/env change (persist it). */
    var onLayoutChange: () -> Unit = {}

    // Undo: snapshots of committed layouts. [committed] is the layout as of the last commit.
    private val undoStack = ArrayDeque<Map<Int, Panel>>()
    private val redoStack = ArrayDeque<Map<Int, Panel>>()
    private var committed: Map<Int, Panel> = HashMap(_panels)
    private var lastCommitKey: String? = null
    private var lastCommitMs = Long.MIN_VALUE / 2
    val canUndo get() = undoStack.isNotEmpty()
    val canRedo get() = redoStack.isNotEmpty()

    /** Real dp-per-meter from the session; rescales radii (panel sizes are already dp). */
    fun setDpPerMeter(v: Float) {
        val k = v / dpPerMeter
        dpPerMeter = v
        for ((id, p) in _panels) _panels[id] = p.copy(r = p.r * k)
        cursorR *= k
        if (!centerFromHead) center = Vec3(0f, 0f, defaultR)
        onChange()
    }

    /** The user's forward direction (theta) at the last centering; the shell's laptop window sits around it. */
    var forward = 0f; private set

    /** The head-aligned frame everything is drawn in (see [Anchor]); identity until the first head pose. */
    var anchor = Anchor(); private set

    /**
     * Recenter on the head's full pose: the layout frame becomes the head's (yaw, pitch beyond the snap,
     * roll only when lying on a side), so inside it the user is at the origin looking down -z. Panels keep
     * their (θ, y, r), so the whole arc — and the shell and laptop cutout — follow the head.
     */
    fun setAnchor(a: Anchor) {
        anchor = a
        centerFromHead = true
        center = Vec3(0f, 0f, 0f); forward = 0f
        onLayoutChange()
        onChange()
    }

    /** Called at startup and on every recenter; panels keep their (θ, y, r) so the whole arc follows the head. */
    fun setCenterFromHead(head: Vec3, dir: Vec3? = null) {
        centerFromHead = true
        center = head
        if (dir != null && (dir.x != 0f || dir.z != 0f)) forward = atan2(dir.x, -dir.z)
        onChange()
    }

    fun position(p: Panel) = center + Vec3(p.r * sin(p.theta), p.y, -p.r * cos(p.theta))

    /** Point on panel [p]'s plane at (u, v), pushed [lift] dp toward the user. */
    fun planePoint(p: Panel, u: Float, v: Float, lift: Float = 0f) =
        position(p) + p.right * u + p.up * v + p.normal * lift

    /** Show [id] (sized from Mac pixels) to the right of everything along the arc, or hide it. */
    fun toggle(id: Int, macW: Int, macH: Int) {
        if (_panels.containsKey(id)) {
            remember() // keep its last spot for when it comes back
            hidePanel(id)
        } else {
            winPts[id] = macW / RETINA to macH / RETINA
            // Back where it was last time, if we remember this window; else to the right of everything.
            val m = meta[id]
            val i = m?.let { w -> saved.indexOfFirst { it.id == null && it.app == w.app && it.title == w.title } }?.takeIf { it >= 0 }
            _panels[id] = if (i != null) {
                saved[i] = saved[i].copy(shown = true, id = id)
                saved[i].panel(dpPerMeter).withAspect(macW, macH)
            } else {
                val w = macW * PANEL_SCALE; val h = macH * PANEL_SCALE; val r = defaultR
                val edge = _panels.values.maxOf { it.theta + it.halfAngle }
                Panel(edge + GAP / r + atan(w / 2 / r), 0f, r, w, h)
            }
            remember()
        }
        commit() // show/hide is one undo step
        onChange()
    }

    private fun hidePanel(id: Int) {
        _panels.remove(id)
        winPts.remove(id)
        if (captured == id) release("hidden")
        if (lastClicked == id) lastClicked = null
        savedIndex(id)?.let { saved[it] = saved[it].copy(shown = false, id = null) }
    }

    private fun Panel.withAspect(macW: Int, macH: Int): Panel {
        val nh = w * macH / macW
        return if (abs(nh - h) > 0.5f) copy(y = y + h / 2 - nh / 2, h = nh) else this
    }

    private fun savedIndex(id: Int) = saved.indexOfFirst { it.id == id }.takeIf { it >= 0 }

    /** Record the current geometry of every shown panel (and the picker) into [saved]. */
    private fun remember() {
        for ((id, p) in _panels) {
            val app = if (id == PICKER) "" else meta[id]?.app ?: continue
            val title = if (id == PICKER) Saved.PICKER_TITLE else meta.getValue(id).title
            val i = savedIndex(id) ?: saved.indexOfFirst { it.id == null && it.app == app && it.title == title }.takeIf { it >= 0 }
            val e = Saved.of(app, title, p, dpPerMeter, shown = true, id = id)
            if (i != null) saved[i] = e else saved += e
        }
    }

    /** A non-undoable change (show/hide, windows coming and going): the new layout becomes the base. */
    private fun rebase() {
        committed = HashMap(_panels)
        onLayoutChange()
    }

    /**
     * Commit the current layout as one undo step (drag end, resize end, hotkey...). Changes with the same
     * [key] within COALESCE_MS (key-repeat nudges, scroll-to-depth) fold into one step.
     */
    fun commit(key: String? = null, nowMs: Long = lastPointerMs) {
        val cur = HashMap(_panels)
        if (cur == committed) return
        val fold = key != null && key == lastCommitKey && nowMs - lastCommitMs < Comfort.COALESCE_MS
        if (!fold) {
            undoStack.addLast(committed)
            if (undoStack.size > Comfort.UNDO_DEPTH) undoStack.removeFirst()
        }
        redoStack.clear()
        committed = cur; lastCommitKey = key; lastCommitMs = nowMs
        remember()
        onLayoutChange()
    }

    fun undo(): Boolean = step(undoStack, redoStack)
    fun redo(): Boolean = step(redoStack, undoStack)

    private fun step(from: ArrayDeque<Map<Int, Panel>>, to: ArrayDeque<Map<Int, Panel>>): Boolean {
        val snap = from.removeLastOrNull() ?: return false
        to.addLast(HashMap(_panels))
        // Shown set and geometry both come back: hide what the snapshot didn't have, re-show what it had
        // (if the Mac still lists the window), and put every panel where it was.
        for (id in _panels.keys.filter { it != PICKER && it !in snap }) hidePanel(id)
        for ((id, p) in snap) {
            if (_panels.containsKey(id)) _panels[id] = p
            else meta[id]?.let { w -> _panels[id] = p; winPts[id] = w.w / RETINA to w.h / RETINA }
        }
        committed = HashMap(_panels); lastCommitKey = null
        remember()
        onLayoutChange()
        onChange()
        return true
    }

    /**
     * Keep panels in step with WINDOW_LIST: drop vanished windows (remembered for later), follow aspect
     * changes, and bring back remembered windows that (re)appear — by app + title, else by app if unique.
     * Returns true if [shown] changed (re-subscribe).
     */
    fun syncWindows(windows: List<MacWindow>): Boolean {
        val byId = windows.associateBy { it.id }
        meta.keys.retainAll(byId.keys); meta.putAll(byId)
        val gone = shown.filter { it !in byId }
        gone.forEach {
            _panels.remove(it); winPts.remove(it)
            if (captured == it) release("window gone")
            if (lastClicked == it) lastClicked = null
            savedIndex(it)?.let { i -> saved[i] = saved[i].copy(id = null) } // still wanted
        }
        for (id in shown) {
            val mw = byId.getValue(id)
            winPts[id] = mw.w / RETINA to mw.h / RETINA
            _panels[id] = _panels.getValue(id).withAspect(mw.w, mw.h)
        }
        var restored = false
        for ((i, e) in saved.withIndex()) {
            if (!e.shown || e.id != null || e.title == Saved.PICKER_TITLE) continue
            val free = windows.filter { !_panels.containsKey(it.id) }
            val w = matchWindow(e, free, saved) ?: continue
            saved[i] = e.copy(id = w.id)
            _panels[w.id] = e.panel(dpPerMeter).withAspect(w.w, w.h)
            winPts[w.id] = w.w / RETINA to w.h / RETINA
            restored = true
        }
        val pk = _panels.getValue(PICKER); val ph = PickerMetrics.height(windows.size)
        if (ph != pk.h) _panels[PICKER] = pk.copy(y = pk.y + pk.h / 2 - ph / 2, h = ph)
        var changed = gone.isNotEmpty() || restored
        if (changed) { remember(); rebase() }
        pendingFocus?.takeIf { byId.containsKey(it) }?.let { id ->
            val before = _panels.containsKey(id)
            onGesture(focusChanged(id, lastPointerMs))
            if (!before) changed = true
        }
        onChange()
        return changed
    }

    fun shellGeometry() = Shell.Geometry(dpPerMeter, center, forward, env.cutHalfYaw, env.cutTop)

    /** Persisted form: remembered panels (current geometry for shown ones), center/forward, env. */
    fun layoutState(): LayoutState {
        remember()
        return LayoutState(saved.toList(), center, forward, env, anchor)
    }

    /** Bumped on every [loadRoom]: window ids from different Macs can collide, so panels are keyed by it. */
    var roomEpoch = 0; private set

    /**
     * Switch to another Mac's room: forget everything about the current one (panels, windows,
     * remembered spots, env, undo) and load [st] (null = a fresh room). The head anchor stays — it's
     * where the user is, not a property of the Mac.
     */
    fun loadRoom(st: LayoutState?) {
        // Re-key panels only if the old room had windows (first connect: keep the picker's panel — each new one leaks a channel).
        if (_panels.size > 1 || meta.isNotEmpty()) roomEpoch++
        release("host switch")
        drag = null; mouse = null; hover = null; buttons = 0; lastClicked = null; flashId = null
        _panels.clear(); _panels[PICKER] = Panel(-0.35f, 0f, defaultR, PickerMetrics.WIDTH, PickerMetrics.height(0))
        meta.clear(); winPts.clear(); saved.clear()
        env = Env()
        undoStack.clear(); redoStack.clear(); lastCommitKey = null
        committed = HashMap(_panels)
        st?.let { restore(it) }
        onChange()
    }

    /** Load a persisted layout at launch: remembered windows come back as WINDOW_LIST shows them. */
    fun restore(st: LayoutState) {
        saved.clear(); saved += st.saved.map { it.copy(id = null) }
        saved.indexOfFirst { it.title == Saved.PICKER_TITLE }.takeIf { it >= 0 }?.let {
            saved[it] = saved[it].copy(id = PICKER)
            _panels[PICKER] = saved[it].panel(dpPerMeter).copy(h = _panels.getValue(PICKER).h)
        }
        if (!centerFromHead) {
            st.center?.let { center = it }
            forward = st.forward
            st.anchor?.let { anchor = it }
        }
        env = st.env
        committed = HashMap(_panels)
        onChange()
    }

    /** Hand/controller move: absolute new center in Subspace dp; snapped back onto the cylinder facing the user. */
    fun moveTo(id: Int, pos: Vec3) {
        val p = _panels[id] ?: return
        val d = pos - center
        val r = hypot(d.x, d.z).coerceIn(MIN_R_M * dpPerMeter, MAX_R_M * dpPerMeter)
        _panels[id] = p.copy(theta = atan2(d.x, -d.z), y = d.y, r = r)
        onChange()
    }

    /** Hand/controller resize around the panel's center. */
    fun resize(id: Int, w: Float, h: Float) {
        _panels[id] = _panels[id]?.copy(w = w, h = h) ?: return
        onChange()
    }

    /** Cursor (theta, t) projected onto [p]'s plane as (u, v), or null if it's behind/beside it. */
    fun planeCoords(p: Panel, theta: Float = cursorTheta, t: Float = cursorT): Pair<Float, Float>? {
        val d = wrapAngle(theta - p.theta)
        if (cos(d) < 0.3f) return null
        return p.r * tan(d) to p.r * t - p.y
    }

    /** Nearest panel (smallest radius; ties go to the later one) with a zone under the cursor. */
    fun hit(theta: Float = cursorTheta, t: Float = cursorT): Hover? {
        var best: Hover? = null; var bestR = Float.POSITIVE_INFINITY
        for ((id, p) in _panels) {
            val (u, v) = planeCoords(p, theta, t) ?: continue
            val h = zoneOf(id, p, u, v) ?: continue
            if (p.r <= bestR) { best = h; bestR = p.r }
        }
        return best
    }

    private fun zoneOf(id: Int, p: Panel, u: Float, v: Float): Hover? {
        if (id != PICKER) {
            val cx = if (u < 0) -1 else 1; val cy = if (v < 0) -1 else 1
            val ex = cx * u - p.w / 2; val ey = cy * v - p.h / 2 // signed distance past each edge
            if (ex in -CORNER_IN..CORNER_OUT && ey in -CORNER_IN..CORNER_OUT) return Hover(id, Zone.CORNER, cx, cy)
        }
        if (abs(u) <= p.w / 2 && abs(v) <= p.h / 2) return Hover(id, Zone.CONTENT)
        if (abs(u) <= Bar.w(p) / 2 + Bar.PAD_X && abs(v - Bar.v(p)) <= Bar.H / 2 + Bar.PAD_Y) return Hover(id, Zone.BAR)
        return null
    }

    /** Where to draw the cursor: a point slightly in front of the panel it's on (or on the cylinder), and its yaw. */
    /** The plane the cursor is drawn on and its (u, v) there: the panel it's on, or a virtual one on the cylinder between panels. */
    fun cursorFrame(): Triple<Panel, Float, Float> {
        val p = hover?.let { _panels[it.id] }
        val uv = p?.let { planeCoords(it) }
        if (p != null && uv != null) return Triple(p, uv.first, uv.second)
        return Triple(Panel(cursorTheta, cursorT * cursorR, cursorR, 0f, 0f), 0f, 0f)
    }

    /** Panel dp per window point for [id] (native gain), or null for the picker / unknown. */
    fun dpPerPoint(id: Int): Float? = winPts[id]?.let { (pw, _) -> _panels[id]?.w?.div(pw) }

    /** Whether the cursor is over (or captured in) a window's content — where it takes the Mac's cursor image. */
    val overContent get() = captured != null || hover?.let { it.zone == Zone.CONTENT && it.id != PICKER } == true

    /** dp per Mac point where the cursor is: the window's own scale over content, GAIN elsewhere. */
    fun cursorDpPerPoint(): Float = if (overContent) hover?.let { dpPerPoint(it.id) } ?: GAIN else GAIN

    /**
     * The real-trackpad pointer model (see [Accel]): speed-dependent gain on top of macOS's, spike
     * rejection, an 8° per-message cap and an elevation band that acts as a wall. The app turns it on;
     * unit tests use exact raw deltas unless they opt in.
     */
    var acceleration = false
    /** Smoothed pointer speed, Mac points per second (device model). */
    var speed = 0f; private set
    private val speedSamples = ArrayDeque<Pair<Long, Float>>() // (t, |accepted delta|)
    private var detX = 0f // on-panel push past the edge (2D detent)
    private var detY = 0f
    private var enterAcc = 0f // push into a panel from space (3D detent)
    private var planarId: Int? = null // panel whose 2D surface the cursor is on (device model)
    private var pendDx = 0f
    private var pendDy = 0f
    private var lastFlushMs = Long.MIN_VALUE / 2
    /** Until then the cursor shows the edge cue (it ran into the elevation band's limit). */
    var edgeCueUntilMs = 0L; private set

    /** Per-gesture travel summary for the log (a gesture = moves without a [GESTURE_GAP_MS] pause). */
    var onGesture: (String) -> Unit = {}
    private var gestureStartMs = 0L
    private var gestureStartTheta = 0f
    private var gesturePeak = 0f
    private val gestureCrossed = LinkedHashSet<Int>()

    private fun trackGesture(p: Pointer, nowMs: Long, dt: Long) {
        if (p.dx == 0f && p.dy == 0f) return
        if (dt >= GESTURE_GAP_MS || gestureStartMs == 0L) {
            endGesture()
            gestureStartMs = nowMs; gestureStartTheta = cursorTheta; gesturePeak = 0f; gestureCrossed.clear()
        }
        gesturePeak = max(gesturePeak, speed)
        hover?.let { gestureCrossed += it.id }
    }

    private fun endGesture() {
        if (gestureStartMs == 0L) return
        val d = wrapAngle(cursorTheta - gestureStartTheta)
        if (abs(d) > 1e-3f) onGesture("travel: θ %.3f→%.3f (Δ%+.3f rad) in %d ms, peak %.0f pt/s, over %s".format(
            gestureStartTheta, cursorTheta, d, lastPointerMs - gestureStartMs, gesturePeak, gestureCrossed.toList()))
        gestureStartMs = 0L
    }

    fun pointer(p: Pointer, nowMs: Long = 0L, gaze: (() -> Ray?)? = null): List<Action> {
        if (!control) return emptyList()
        val out = mutableListOf<Action>()
        val dt = nowMs - lastPointerMs
        val idle = dt >= JUMP_IDLE_MS
        // A single delta this big is a warp artifact (seen on device: 250–1745 pt in one message), not
        // the user: drop the motion, keep buttons/scroll.
        val spike = acceleration && hypot(p.dx, p.dy) > Accel.SPIKE_PT
        if (spike) onGesture("spike: dropped POINTER dx=%.0f dy=%.0f".format(p.dx, p.dy))
        val pm = if (spike) p.copy(dx = 0f, dy = 0f) else p
        trackGesture(pm, nowMs, dt)
        lastPointerMs = nowMs
        // Only a plain move after a pause jumps: never a press/scroll, never mid-drag, never while
        // parked on a grab bar or corner (the user is about to grab it).
        val plainMove = (p.dx != 0f || p.dy != 0f) && p.buttons == 0 && p.sx == 0f && p.sy == 0f
        val onHandle = hover?.zone.let { it == Zone.BAR || it == Zone.CORNER }
        if (lookToJump && idle && plainMove && buttons == 0 && drag == null && mouse == null && !onHandle) gaze?.invoke()?.let { jumpTo(it) }

        if (acceleration) {
            // Device model: collect accepted motion; [frame] applies it once per display frame. A button
            // or scroll needs the cursor where the motion so far puts it, so flush first.
            pendDx += pm.dx; pendDy += pm.dy
            speedSamples.addLast(nowMs to hypot(pm.dx, pm.dy))
            if (p.buttons != buttons || p.sx != 0f || p.sy != 0f) flush(nowMs, out)
        } else if (pm.dx != 0f || pm.dy != 0f) move(pm.dx, pm.dy, nowMs, out)

        for ((bit, button) in listOf(1 to 0, 2 to 1)) {
            val was = buttons and bit != 0; val now = p.buttons and bit != 0
            if (now && !was) press(button, out)
            if (!now && was) release(button, out)
        }
        buttons = p.buttons
        if (p.sx != 0f || p.sy != 0f) scroll(p, out)
        onChange()
        return out
    }

    /** Device model: apply the motion collected since the last frame (call once per display frame). */
    fun frame(nowMs: Long): List<Action> {
        if (!control || !acceleration || (pendDx == 0f && pendDy == 0f)) return emptyList()
        val out = mutableListOf<Action>()
        flush(nowMs, out)
        onChange()
        return out
    }

    private fun flush(nowMs: Long, out: MutableList<Action>) {
        // Speed: accepted travel over the last WINDOW_MS, EMA-smoothed per frame (never from one
        // message's inter-arrival time, which is jittery).
        while (speedSamples.isNotEmpty() && speedSamples.first().first < nowMs - Accel.WINDOW_MS) speedSamples.removeFirst()
        val vWin = if (speedSamples.isEmpty()) 0f else speedSamples.sumOf { it.second.toDouble() }.toFloat() * 1000f /
            (nowMs - speedSamples.first().first + Accel.NOMINAL_DT_MS).coerceAtLeast(Accel.NOMINAL_DT_MS)
        speed = if (nowMs - lastFlushMs > Accel.WINDOW_MS) vWin else (1 - Accel.EMA) * speed + Accel.EMA * vWin
        lastFlushMs = nowMs
        val dx = pendDx; val dy = pendDy
        pendDx = 0f; pendDy = 0f
        if (dx != 0f || dy != 0f) move(dx, dy, nowMs, out)
    }

    /** Move by (dx, dy) Mac points: captured → native window points; device model → angular gain; raw → dp. */
    private fun move(dx: Float, dy: Float, nowMs: Long, out: MutableList<Action>) {
        // Momentum after a bottom exit would carry the cursor straight past the bar: absorb it until the
        // window runs out or the user reverses (moves up).
        val absorb = nowMs < absorbUntilMs && dy >= 0f
        if (!absorb) absorbUntilMs = Long.MIN_VALUE
        val cap = captured?.let { id -> _panels[id]?.let { id to it } }
        if (absorb) Unit
        else if (cap != null) moveCaptured(cap.first, cap.second, dx, dy)
        else if (acceleration) moveDevice(dx, dy, nowMs)
        else cursorDpPerPoint().let { g -> moveCursor(dx * g, dy * g) }
        drag?.let { applyDrag(it) }
        hover = drag?.h ?: captured?.let { Hover(it, Zone.CONTENT) } ?: hit() ?: planarId?.let { Hover(it, Zone.MARGIN) }
        hover?.let { cursorR = _panels.getValue(it.id).r }

        val m = mouse
        if (m != null) mouseAt(m.first, "drag", m.second)?.let { out += it }
        else if (drag == null) hover?.takeIf { it.zone == Zone.CONTENT && it.id != PICKER }
            ?.let { h -> mouseAt(h.id, "move", 0)?.takeIf { movedAWindowPoint(it) }?.let { out += it } }
    }

    /** True when the cursor is on a panel (2D, flat Mac cursor); false when it's in space (3D reticle). */
    val onPanel get() = captured != null || hover != null

    /**
     * Device model, no headset acceleration (macOS already accelerates):
     * - on a panel (2D): the cursor moves in that panel's own plane at the window's native point
     *   mapping, exactly like the window on the Mac; leaving it takes [Accel.DETENT_DP] of push past the edge.
     * - in space (3D): angular, [Accel.YAW_DEG_PER_PT]/[Accel.ELEV_DEG_PER_PT]; entering a panel also takes
     *   [Accel.DETENT_DP] of push, so crossing an edge is felt and intentional.
     * [Accel.SENSITIVITY] scales both.
     */
    private fun moveDevice(dx: Float, dy: Float, nowMs: Long) {
        val sx = dx * Accel.SENSITIVITY; val sy = dy * Accel.SENSITIVITY
        val h = hover; val p = h?.let { _panels[it.id] }
        if (drag == null && h != null && p != null && movePlanar(h.id, p, sx, sy)) return
        moveSpace(sx, sy, nowMs)
    }

    /** 2D on [p]. Returns false if the cursor isn't on its plane (then it moves in space). */
    private fun movePlanar(id: Int, p: Panel, dx: Float, dy: Float): Boolean {
        val (u0, v0) = planeCoords(p) ?: return false.also { planarId = null }
        val k = dpPerPoint(id) ?: GAIN // window: 1 Mac pt = 1 window pt; picker: 1 dp/pt
        var u = u0 + dx * k; var v = v0 - dy * k
        // The panel's hit region: content plus corner handles, down to the grab bar.
        val l = -p.w / 2 - CORNER_OUT; val rt = p.w / 2 + CORNER_OUT
        val top = p.h / 2 + CORNER_OUT; val bot = Bar.v(p) - Bar.H / 2 - Bar.PAD_Y
        detX = when { u > rt -> max(detX, 0f) + (u - rt); u < l -> min(detX, 0f) + (u - l); else -> 0f }
        detY = when { v > top -> max(detY, 0f) + (v - top); v < bot -> min(detY, 0f) + (v - bot); else -> 0f }
        u = u.coerceIn(l, rt); v = v.coerceIn(bot, top)
        planarId = id // still on this panel's 2D surface (hover is MARGIN where no control is under it)
        if (abs(detX) > Accel.DETENT_DP || abs(detY) > Accel.DETENT_DP) {
            // Through the detent: out into space just past the edge.
            if (detX > Accel.DETENT_DP) u = rt + 1f else if (detX < -Accel.DETENT_DP) u = l - 1f
            if (detY > Accel.DETENT_DP) v = top + 1f else if (detY < -Accel.DETENT_DP) v = bot - 1f
            detX = 0f; detY = 0f; enterAcc = 0f; planarId = null
        }
        cursorTheta = wrapAngle(p.theta + atan(u / p.r)); cursorT = (p.y + v) / p.r; cursorR = p.r
        return true
    }

    /** 3D: angular travel in the panels' elevation band (outward gain fades near its edges), entry detent. */
    private fun moveSpace(dx: Float, dy: Float, nowMs: Long) {
        val maxStep = rad(Accel.MAX_STEP_DEG) // safety net: no single update moves further than this
        val th1 = wrapAngle(cursorTheta + (rad(Accel.YAW_DEG_PER_PT) * dx).coerceIn(-maxStep, maxStep))
        val e0 = atan(cursorT)
        var de = (-rad(Accel.ELEV_DEG_PER_PT) * dy).coerceIn(-maxStep, maxStep) // Mac dy is down-positive
        val (lo, hi) = elevationBand()
        val fall = rad(Accel.EDGE_FALLOFF_DEG)
        if (de > 0f) {
            val k = smoothstep(0f, fall, hi - e0)
            if (k < 0.15f) edgeCueUntilMs = nowMs + EDGE_CUE_MS
            de = min(de * k, max(0f, hi - e0))
        } else if (de < 0f) {
            val k = smoothstep(0f, fall, e0 - lo)
            if (k < 0.15f) edgeCueUntilMs = nowMs + EDGE_CUE_MS
            de = max(de * k, min(0f, lo - e0))
        }
        val t1 = tan(e0 + de).coerceIn(-MAX_T, MAX_T)
        // Entry detent: pushing into a panel is held at its edge until DETENT_DP of push has built up.
        if (hit(th1, t1) != null && hit() == null) {
            enterAcc += hypot(dx, dy) * GAIN
            if (enterAcc < Accel.DETENT_DP) return
        }
        enterAcc = 0f; planarId = null
        cursorTheta = th1; cursorT = t1; cursorR = defaultR // in space the reticle floats at the default distance
    }

    /**
     * Free movement over the whole sphere: the arc wraps all the way round and elevation is only kept
     * to ±60°, so every panel — however far round or high/low — is reachable by plain moves.
     */
    private fun moveCursor(dx: Float, dy: Float) {
        // Raw model (tests): exact dp deltas at the current radius, only the absolute ±60° limit.
        // Mac dy is down-positive. The device model moves angularly instead (see moveDevice).
        val r = hover?.let { _panels[it.id]?.r } ?: cursorR
        cursorTheta = wrapAngle(cursorTheta + dx / r)
        cursorT = (cursorT - dy / r).coerceIn(-MAX_T, MAX_T)
    }

    /** Elevations (radians) the cursor may use: the panels' vertical extent ± [ELEV_MARGIN_DEG]. */
    fun elevationBand(): Pair<Float, Float> {
        if (_panels.isEmpty()) return -rad(ELEV_FALLBACK_DEG) to rad(ELEV_FALLBACK_DEG)
        val lo = _panels.values.minOf { atan((it.y + Bar.v(it) - Bar.H) / it.r) } - rad(ELEV_MARGIN_DEG)
        val hi = _panels.values.maxOf { atan((it.y + it.h / 2) / it.r) } + rad(ELEV_MARGIN_DEG)
        return max(lo, -atan(MAX_T)) to min(hi, atan(MAX_T))
    }

    /**
     * Captured: move in the window's own points, clamped to its content. Pushing on past an edge
     * builds overshoot; past OVERSHOOT dp the cursor leaves (bottom edge: onto the grab bar).
     * Never leaves while a Mac button is held, so drag-selects can run to the edge.
     */
    private fun moveCaptured(id: Int, p: Panel, dx: Float, dy: Float) {
        val k = dpPerPoint(id) ?: GAIN
        val (u0, v0) = planeCoords(p) ?: (0f to 0f)
        var u = u0 + dx * k; var v = v0 - dy * k
        val hw = p.w / 2; val hh = p.h / 2
        overX = when { u > hw -> max(overX, 0f) + (u - hw); u < -hw -> min(overX, 0f) + (u + hw); else -> 0f }
        overY = when { v > hh -> max(overY, 0f) + (v - hh); v < -hh -> min(overY, 0f) + (v + hh); else -> 0f }
        u = u.coerceIn(-hw, hw); v = v.coerceIn(-hh, hh)
        if (mouse != null) { overX = 0f; overY = 0f }
        val out = CORNER_OUT + 2f // just past the corner handles
        when {
            overY < -OVERSHOOT -> { v = Bar.v(p); u = 0f; absorbUntilMs = lastPointerMs + ABSORB_MS } // bar center
            overY > OVERSHOOT -> v = hh + out
            overX > OVERSHOOT -> u = hw + out
            overX < -OVERSHOOT -> u = -hw - out
        }
        if (abs(overX) > OVERSHOOT || abs(overY) > OVERSHOOT) release("overshoot")
        cursorTheta = p.theta + atan(u / p.r); cursorT = (p.y + v) / p.r; cursorR = p.r
    }

    private fun release(why: String) {
        if (captured == null) return
        captured = null; overX = 0f; overY = 0f
        onCapture(null, why)
    }

    private fun applyDrag(d: Drag) {
        val s = d.start
        val r = _panels.getValue(d.h.id).r // scrolling may change radius mid-drag
        _panels[d.h.id] = when (d.h.zone) {
            Zone.CORNER -> {
                val (u0, v0) = planeCoords(s, d.theta, d.t) ?: return
                val (u1, v1) = planeCoords(s) ?: return
                val (ox, oy, w) = resized(s.w, s.h, d.h.cx, d.h.cy, u1 - u0, v1 - v0)
                s.copy(theta = s.theta + atan(ox / s.r), y = s.y + oy, w = w, h = w * s.h / s.w)
            }
            else -> s.copy(theta = s.theta + wrapAngle(cursorTheta - d.theta), y = s.y + s.r * (cursorT - d.t))
        }.copy(r = r)
    }

    private var lastHover: Action.Mouse? = null

    /** Hover moves only when the pointer moved ≥ 1 point in the window's own coordinates (less is just noise). */
    private fun movedAWindowPoint(m: Action.Mouse): Boolean {
        val last = lastHover
        val (pw, ph) = winPts[m.id] ?: (1f to 1f)
        if (last != null && last.id == m.id && abs(m.x - last.x) * pw < 1f && abs(m.y - last.y) * ph < 1f) return false
        lastHover = m
        return true
    }

    private fun mouseAt(id: Int, kind: String, button: Int): Action.Mouse? {
        val p = _panels[id] ?: return null
        val (u, v) = planeCoords(p) ?: return null
        return Action.Mouse(id, kind, p.normX(u), p.normY(v), button)
    }

    private fun press(button: Int, out: MutableList<Action>) {
        if (drag != null || mouse != null) return // one gesture at a time
        val h = hover ?: return
        val p = _panels.getValue(h.id)
        when {
            h.zone == Zone.MARGIN -> Unit
            h.zone != Zone.CONTENT -> if (button == 0) drag = Drag(h, cursorTheta, cursorT, p)
            h.id == PICKER -> if (button == 0) planeCoords(p)?.let { (_, v) ->
                PickerMetrics.rowAt(p.h / 2 - v, Int.MAX_VALUE)?.let { out += Action.PickerRow(it) }
            }
            else -> mouseAt(h.id, "down", button)?.let {
                out += it; mouse = h.id to button; lastClicked = h.id
                if (captured != h.id) { captured = h.id; overX = 0f; overY = 0f; onCapture(h.id, "click") }
            }
        }
    }

    private fun release(button: Int, out: MutableList<Action>) {
        if (button == 0 && drag != null) { drag = null; commit() }
        val m = mouse ?: return
        if (m.second != button) return
        mouseAt(m.first, "up", button)?.let { out += it }
        mouse = null
    }

    private fun scroll(p: Pointer, out: MutableList<Action>) {
        val h = drag?.h ?: hover ?: return
        val panel = _panels.getValue(h.id)
        if (h.zone == Zone.BAR) {
            // Scroll up pushes the panel away.
            val r = (panel.r + p.sy * R_PER_LINE_M * dpPerMeter).coerceIn(MIN_R_M * dpPerMeter, MAX_R_M * dpPerMeter)
            val (u, v) = planeCoords(panel) ?: (0f to Bar.v(panel))
            _panels[h.id] = panel.copy(r = r)
            // Keep the cursor on the same spot of the bar as the panel comes in/out.
            cursorTheta = panel.theta + atan(u / r); cursorT = (panel.y + v) / r; cursorR = r
            drag?.let { drag = Drag(it.h, cursorTheta, cursorT, it.start.copy(theta = _panels.getValue(h.id).theta, y = panel.y, r = r)) }
            if (drag == null) commit("depth:${h.id}") // mid-drag, the drag's own commit covers it
            return
        }
        if (h.zone != Zone.CONTENT || h.id == PICKER) return
        val (u, v) = planeCoords(panel) ?: return
        // The Mac injects whole lines; carry trackpad fractions over to the next event.
        if (h.id != scrollId) { scrollId = h.id; accX = 0f; accY = 0f }
        accX += p.sx; accY += p.sy
        val lx = accX.toInt(); val ly = accY.toInt()
        if (lx == 0 && ly == 0) return
        accX -= lx; accY -= ly
        out += Action.Scroll(h.id, panel.normX(u), panel.normY(v), lx, ly)
    }

    /**
     * Look-to-jump: put the cursor where [ray] meets the nearest panel, unless it's already on that one.
     * Returns the panel id jumped to.
     */
    fun jumpTo(ray: Ray): Int? {
        val (id, u, v) = rayHit(ray) ?: return null
        if (hover?.id == id) return null
        release("look")
        val p = _panels.getValue(id)
        cursorTheta = wrapAngle(p.theta + atan(u / p.r))
        cursorT = (p.y + v) / p.r
        cursorR = p.r
        hover = hit()
        return id
    }

    /** Nearest panel [ray] hits (incl. its corners and bar), with the hit in plane coords. */
    fun rayHit(ray: Ray): Triple<Int, Float, Float>? {
        var best: Int? = null; var bestT = Float.POSITIVE_INFINITY; var bestUV = 0f to 0f
        for ((id, p) in _panels) {
            val n = p.normal
            val denom = ray.dir dot n
            if (denom >= -1e-4f) continue // parallel, or looking at its back
            val k = ((position(p) - ray.origin) dot n) / denom
            if (k <= 0 || k >= bestT) continue
            val hitPt = ray.origin + ray.dir * k - position(p)
            val u = hitPt dot p.right; val v = hitPt dot p.up
            if (abs(u) > p.w / 2 + CORNER_OUT || v > p.h / 2 + CORNER_OUT || v < Bar.v(p) - Bar.H) continue
            best = id; bestT = k; bestUV = u to v
        }
        val id = best ?: return null
        return Triple(id, bestUV.first, bestUV.second)
    }

    /**
     * Re-lay every shown panel in one row centered straight ahead at eye level, picker on the left, at the
     * default distance; windows at their natural size but no wider than TIDY_MAX_DEG each. Undoable.
     */
    fun tidy(nowMs: Long = lastPointerMs): String {
        val r = defaultR
        val maxW = 2 * r * tan(rad(TIDY_MAX_DEG / 2))
        val sized = _panels.mapValues { (id, p) ->
            val w = if (id == PICKER) p.w else min(maxW, winPts[id]?.let { it.first * RETINA * PANEL_SCALE } ?: p.w)
            p.copy(r = r, y = 0f, w = w, h = w * p.h / p.w)
        }
        val order = listOf(PICKER) + sized.keys.filter { it != PICKER }
        val gap = GAP / r
        val total = order.sumOf { (2 * sized.getValue(it).halfAngle).toDouble() }.toFloat() + gap * (order.size - 1)
        var edge = -total / 2
        for (id in order) {
            val p = sized.getValue(id)
            _panels[id] = p.copy(theta = edge + p.halfAngle)
            edge += 2 * p.halfAngle + gap
        }
        commit("tidy", nowMs)
        onChange()
        return "tidy: ${order.size} panels across %.0f°".format(Math.toDegrees(total.toDouble()))
    }

    /**
     * Direction (radians, 0 = right, π/2 = up, in the cursor's view) from the cursor to the nearest panel
     * center, or null when the cursor is over a panel (or there are none).
     */
    fun nearestPanelDirection(): Float? {
        if (hover != null) return null
        val best = _panels.values.minByOrNull { p -> val dx = wrapAngle(p.theta - cursorTheta); val dy = atan(p.y / p.r) - atan(cursorT); dx * dx + dy * dy }
            ?: return null
        return atan2(atan(best.y / best.r) - atan(cursorT), wrapAngle(best.theta - cursorTheta))
    }

    /** A FOCUS_CHANGED for a window the WINDOW_LIST hasn't shown us yet: handled when it appears. */
    private var pendingFocus: Int? = null
    /** The window the Mac last focused on its own (Raycast, ⌘-Tab…), for the off-view arrow. */
    var focusTarget: Int? = null; private set
    var focusAtMs = 0L; private set

    /**
     * PROTOCOL v7 FOCUS_CHANGED: the Mac's frontmost window changed. Make it the active panel, flash it
     * and put the cursor on it — showing it first (remembered spot, else next to the active panel) if
     * it isn't up. Unknown windows wait for the next WINDOW_LIST.
     */
    fun focusChanged(id: Int, nowMs: Long): String {
        val w = meta[id] ?: run { pendingFocus = id; return "focus-follow: window $id not listed yet, pending" }
        pendingFocus = null
        var how = "active"
        if (!_panels.containsKey(id)) {
            val active = lastClicked?.let { _panels[it] }
            val remembered = saved.any { it.id == null && it.app == w.app && it.title == w.title }
            toggle(id, w.w, w.h)
            if (!remembered && active != null) {
                // Next to the active panel rather than at the end of the row.
                val p = _panels.getValue(id)
                _panels[id] = p.copy(theta = active.theta + active.halfAngle + GAP / active.r + atan(p.w / 2 / active.r),
                    y = active.y, r = active.r)
                commit("focus-show:$id", nowMs)
            }
            how = "shown"
        }
        if (captured != null && captured != id) release("focus-follow")
        lastClicked = id
        flashId = id; flashUntilMs = nowMs + FLASH_MS
        focusTarget = id; focusAtMs = nowMs
        val p = _panels.getValue(id)
        if (hover?.id != id) {
            cursorTheta = wrapAngle(p.theta); cursorT = p.y / p.r; cursorR = p.r
            hover = hit()
        }
        onChange()
        return "focus-follow: window $id '${w.app}' $how"
    }

    /** Panel whose outline flashes (v5 focus) until [flashUntilMs]. */
    var flashId: Int? = null; private set
    var flashUntilMs = 0L; private set

    /** Make [id] the active panel (target of the next commands) and flash its outline so the user sees which. */
    private fun focus(id: Int, nowMs: Long): String {
        if (id != PICKER) lastClicked = id
        flashId = id; flashUntilMs = nowMs + FLASH_MS
        onChange()
        return "focus window $id"
    }

    /** Hand tap on a window's content: it becomes the active panel. */
    fun noteClicked(id: Int) { if (_panels.containsKey(id) && id != PICKER) lastClicked = id }

    /** PROTOCOL v4 active panel: captured, else last clicked, else the window panel the head points at. */
    fun activePanel(gaze: Ray?): Int? =
        captured?.takeIf { _panels.containsKey(it) }
            ?: lastClicked?.takeIf { _panels.containsKey(it) }
            ?: gaze?.let { rayHit(it)?.first }?.takeIf { it != PICKER }

    /**
     * Apply a COMMAND (all but "recenter", which needs the head pose and is the caller's job).
     * Returns a one-line description for the log.
     */
    fun command(c: Command, gaze: Ray?, nowMs: Long): String {
        // v5: an explicit window name picks the target (shown windows first, then any the Mac lists).
        // The Mac sends the CGWindowID it resolved ("id"); the name match is only a fallback.
        val named = c.id?.let { meta[it] } ?: c.window?.let { findWindow(it, meta.values.toList(), _panels.keys) }
        if ((c.window != null || c.id != null) && named == null) return "${c.cmd}: no window matches '${c.window ?: c.id}'"
        when (c.cmd) {
            "show" -> {
                val w = named ?: return "show: which window?"
                if (_panels.containsKey(w.id)) return focus(w.id, nowMs).replace("focus", "show (already up)")
                toggle(w.id, w.w, w.h)
                lastClicked = w.id
                return "show window ${w.id} '${w.app} — ${w.title}'"
            }
            "hide" -> {
                val id = named?.id ?: activePanel(gaze) ?: return "hide: no active panel"
                if (!_panels.containsKey(id) || id == PICKER) return "hide: window $id isn't up"
                val w = meta[id]
                toggle(id, w?.w ?: 0, w?.h ?: 0)
                return "hide window $id"
            }
            "focus" -> {
                val id = named?.id ?: activePanel(gaze) ?: return "focus: no active panel"
                if (!_panels.containsKey(id)) return "focus: window $id isn't up"
                return focus(id, nowMs)
            }
            "undo" -> return if (undo()) "undo" else "undo: nothing to undo"
            "redo" -> return if (redo()) "redo" else "redo: nothing to redo"
            "passthrough" -> return setEnv(env.copy(passthrough = !env.passthrough))
            "dim" -> return setEnv(env.dimmed(c.d))
            "cutout" -> return setEnv(env.cut(c.dw, c.dh))
            "tidy" -> return tidy(nowMs)
        }
        val id = named?.id?.takeIf { _panels.containsKey(it) } ?: if (named != null) return "${c.cmd}: '${c.window}' isn't up"
            else activePanel(gaze) ?: return "${c.cmd}: no active panel"
        val p = _panels.getValue(id)
        val dpm = dpPerMeter
        val np = when (c.cmd) {
            "nudge" -> p.copy(theta = p.theta + rad(c.dtheta * Comfort.NUDGE_DEG), y = p.y + c.dy * Comfort.NUDGE_Y_M * dpm)
            "depth" -> p.copy(r = (p.r + c.d * Comfort.DEPTH_M * dpm).coerceIn(MIN_R_M * dpm, MAX_R_M * dpm))
            "size" -> {
                val w = max(MIN_W, p.w * Comfort.SIZE_STEP.pow(c.d))
                p.copy(w = w, h = w * p.h / p.w)
            }
            "center" -> preset(p, Comfort.EDITOR)
            "preset" -> preset(p, Comfort.PRESETS[c.name] ?: return "preset: unknown '${c.name}'")
            else -> return "unknown command '${c.cmd}'"
        }
        // Carry the cursor along if it's on this panel.
        val uv = if (hover?.id == id) planeCoords(p) else null
        _panels[id] = np
        uv?.let { (u, v) -> cursorTheta = np.theta + atan(u / np.r); cursorT = (np.y + v) / np.r; cursorR = np.r }
        // Key-repeat hotkeys fold into one undo step; each voice COMMAND (named target) is its own step.
        commit(if (named != null) null else "${c.cmd}:${c.name ?: ""}:$id", nowMs)
        onChange()
        return "%s window %d -> θ %.1f° y %.0f r %.0f %.0fx%.0f".format(
            c.cmd + (c.name?.let { " $it" } ?: ""), id, Math.toDegrees(np.theta.toDouble()), np.y, np.r, np.w, np.h)
    }

    /** [p] resized to the preset's angular width at its distance (aspect kept); editor goes straight ahead at eye level. */
    fun preset(p: Panel, pr: Comfort.Preset): Panel {
        val r = pr.rM * dpPerMeter
        val w = 2 * r * tan(rad(pr.widthDeg / 2))
        return Panel(if (pr.straightAhead) forward else p.theta, if (pr.straightAhead) 0f else p.y, r, w, w * p.h / p.w)
    }

    private fun setEnv(e: Env): String {
        env = e
        onLayoutChange(); onChange()
        return "env dim=${e.dim} passthrough=${e.passthrough} cutout=±%.1f° top %.0f° shellAlpha=%.2f".format(e.cutHalfYaw, e.cutTop, e.shellAlpha)
    }

    private fun rad(deg: Float) = Math.toRadians(deg.toDouble()).toFloat()
}

/**
 * Aspect-locked resize of a w×h rect centered at the origin: corner ([cx], [cy]) moves by
 * ([ddx], [ddy]), the opposite corner stays put. Returns the new center (x, y) and width.
 */
/**
 * Device pointer model, no headset acceleration (macOS already accelerates trackpad deltas). Starting
 * design values; tune [SENSITIVITY] first.
 */
object Accel {
    const val SENSITIVITY = 1f // one knob: scales on-panel and in-space motion
    const val YAW_DEG_PER_PT = 0.04f // in space
    const val ELEV_DEG_PER_PT = 0.03f
    /** Push needed to enter or leave a panel. The app sets 0 on device (user found any detent sticky); tests use 15. */
    var DETENT_DP = 15f
    const val EMA = 0.3f // travel-log speed: 0.7·previous + 0.3·window
    const val WINDOW_MS = 60L
    const val NOMINAL_DT_MS = 8L // POINTER is coalesced to ≤120/s
    const val SPIKE_PT = 200f // one message moving more than this is a warp artifact: dropped
    const val MAX_STEP_DEG = 8f // safety net: no single update moves the cursor further than this
    const val EDGE_FALLOFF_DEG = 10f // outward elevation gain fades over this much before the band limit
}

fun smoothstep(a: Float, b: Float, x: Float): Float {
    val t = ((x - a) / (b - a)).coerceIn(0f, 1f)
    return t * t * (3 - 2 * t)
}

/** Angle wrapped to [-π, π). */
fun wrapAngle(a: Float): Float {
    val twoPi = (2 * Math.PI).toFloat()
    var x = (a + Math.PI.toFloat()) % twoPi
    if (x < 0) x += twoPi
    return x - Math.PI.toFloat()
}

internal fun resized(w: Float, h: Float, cx: Int, cy: Int, ddx: Float, ddy: Float): Triple<Float, Float, Float> {
    // Project the corner's motion onto the diagonal so both axes contribute.
    val k = ((w + cx * ddx) * w + (h + cy * ddy) * h) / (w * w + h * h)
    val nw = max(Desk.MIN_W, w * k); val nh = nw * h / w
    return Triple(-cx * w / 2 + cx * nw / 2, -cy * h / 2 + cy * nh / 2, nw)
}
