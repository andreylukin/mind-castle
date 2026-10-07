package dev.mindcastle

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.pow

/** Every comfort/hotkey tuning number (PROTOCOL v4) in one place. */
object Comfort {
    /** A sharpness/size preset: distance and the panel's angular width (aspect kept). */
    data class Preset(val name: String, val rM: Float, val widthDeg: Float, val straightAhead: Boolean)

    val EDITOR = Preset("editor", 0.9f, 50f, straightAhead = true)
    val SIDE = Preset("side", 1.1f, 35f, straightAhead = false)
    val GLANCE = Preset("glance", 1.4f, 25f, straightAhead = false)
    val PRESETS = listOf(EDITOR, SIDE, GLANCE).associateBy { it.name }

    const val NUDGE_DEG = 5f
    const val NUDGE_Y_M = 0.05f
    const val DEPTH_M = 0.10f
    const val SIZE_STEP = 1.1f
    const val UNDO_DEPTH = 50
    /** Repeated changes of the same kind to the same panel within this window are one undo step. */
    const val COALESCE_MS = 1000L
    const val DIM_MAX = 5
    const val CUTOUT_YAW_STEP = 5f // total width change per press (± half each side)
    const val CUTOUT_TOP_STEP = 5f
    const val CUTOUT_MAX_HALF_YAW = 90f
    const val CUTOUT_MIN_TOP_GAP = 5f // the cutout's top stays this far below the shell's top
}

/** PROTOCOL v4/v5 COMMAND. Absent fields are 0 / null. [window] (v5) names the target by app/title substring. */
data class Command(
    val cmd: String, val dtheta: Int = 0, val dy: Int = 0, val d: Int = 0,
    val name: String? = null, val dw: Int = 0, val dh: Int = 0, val window: String? = null,
    /** CGWindowID the Mac already resolved [window] to (preferred over matching the name here). */
    val id: Int? = null,
) {
    companion object {
        fun parse(json: String): Command = JSONObject(json).run {
            Command(optString("cmd"), optInt("dtheta"), optInt("dy"), optInt("d"),
                optString("name").ifEmpty { null }, optInt("dw"), optInt("dh"), optString("window").ifEmpty { null },
                if (has("id")) optInt("id") else null)
        }
    }
}

/**
 * The window a spoken/typed name refers to: case-insensitive match on app or title — exact beats
 * prefix beats substring — searching shown windows first, then the rest. Null if nothing matches.
 */
fun findWindow(query: String, windows: List<MacWindow>, shown: Set<Int>): MacWindow? {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return null
    fun score(s: String) = s.lowercase().let { when { it == q -> 3; it.startsWith(q) -> 2; q in it -> 1; else -> 0 } }
    fun best(ws: List<MacWindow>) = ws.map { it to maxOf(score(it.app), score(it.title)) }
        .filter { it.second > 0 }.maxByOrNull { it.second }?.first
    return best(windows.filter { it.id in shown }) ?: best(windows.filter { it.id !in shown })
}

/** PROTOCOL v5 VOICE: push-to-talk status for the HUD in the picker header. */
data class Voice(val state: String, val text: String) {
    companion object {
        fun parse(json: String): Voice = JSONObject(json).run { Voice(optString("state"), optString("text")) }
    }
}

/** Room/environment state: shell dim level, "show the room", and the laptop cutout (degrees). */
data class Env(
    val dim: Int = Comfort.DIM_MAX,
    val passthrough: Boolean = false,
    val cutHalfYaw: Float = Shell.WINDOW_HALF_YAW,
    val cutTop: Float = Shell.WINDOW_PITCH_TOP,
) {
    /**
     * Shell opacity: dim 5 = opaque black, 0 = clear; "show the room" hides it. Gamma-shaped so the
     * steps look evenly spaced (linear alpha makes the low levels barely different from clear).
     */
    val shellAlpha get() = if (passthrough) 0f else (dim.toFloat() / Comfort.DIM_MAX).pow(1f / 2.2f)

    fun dimmed(d: Int) = copy(dim = (dim + d).coerceIn(0, Comfort.DIM_MAX))
    fun cut(dw: Int, dh: Int) = copy(
        cutHalfYaw = (cutHalfYaw + dw * Comfort.CUTOUT_YAW_STEP / 2).coerceIn(0f, Comfort.CUTOUT_MAX_HALF_YAW),
        cutTop = (cutTop + dh * Comfort.CUTOUT_TOP_STEP).coerceIn(Shell.PITCH_BOTTOM, Shell.PITCH_TOP - Comfort.CUTOUT_MIN_TOP_GAP),
    )
}

/**
 * A remembered panel, keyed by the Mac window's app + title (window IDs don't survive restarts).
 * [rM] is in meters so it's independent of the session's dp-per-meter. [id] is the window it's
 * currently bound to (runtime only); [shown] = the user wants it up (false once they hide it).
 */
data class Saved(
    val app: String, val title: String,
    val theta: Float, val y: Float, val rM: Float, val w: Float, val h: Float,
    val shown: Boolean, val id: Int? = null,
) {
    fun panel(dpPerMeter: Float) = Panel(theta, y, rM * dpPerMeter, w, h)

    companion object {
        const val PICKER_TITLE = "\u0000picker"
        fun of(app: String, title: String, p: Panel, dpPerMeter: Float, shown: Boolean, id: Int?) =
            Saved(app, title, p.theta, p.y, p.r / dpPerMeter, p.w, p.h, shown, id)
    }
}

/**
 * Which unclaimed window a remembered, wanted-but-unbound entry should take: exact app+title,
 * else the app's only candidate window — unless some other remembered entry names that exact window.
 */
fun matchWindow(e: Saved, candidates: List<MacWindow>, saved: List<Saved>): MacWindow? {
    candidates.firstOrNull { it.app == e.app && it.title == e.title }?.let { return it }
    val sameApp = candidates.filter { it.app == e.app }
    val only = sameApp.singleOrNull() ?: return null
    return only.takeIf { w -> saved.none { it !== e && it.app == w.app && it.title == w.title } }
}

/** Persisted form of the layout. */
data class LayoutState(val saved: List<Saved>, val center: Vec3?, val forward: Float, val env: Env, val anchor: Anchor? = null)

object LayoutJson {
    fun write(s: LayoutState): String = JSONObject().apply {
        put("v", 1)
        s.center?.let { put("center", JSONArray().put(it.x.toDouble()).put(it.y.toDouble()).put(it.z.toDouble())) }
        put("forward", s.forward.toDouble())
        s.anchor?.let { a ->
            put("anchor", JSONObject().put("pos", JSONArray().put(a.pos.x.toDouble()).put(a.pos.y.toDouble()).put(a.pos.z.toDouble()))
                .put("yaw", a.yawDeg.toDouble()).put("pitch", a.pitchDeg.toDouble()).put("roll", a.rollDeg.toDouble()))
        }
        put("env", JSONObject().put("dim", s.env.dim).put("passthrough", s.env.passthrough)
            .put("cutHalfYaw", s.env.cutHalfYaw.toDouble()).put("cutTop", s.env.cutTop.toDouble()))
        put("panels", JSONArray().apply {
            for (e in s.saved) put(JSONObject().put("app", e.app).put("title", e.title).put("shown", e.shown)
                .put("theta", e.theta.toDouble()).put("y", e.y.toDouble()).put("rM", e.rM.toDouble())
                .put("w", e.w.toDouble()).put("h", e.h.toDouble()))
        })
    }.toString()

    fun read(json: String): LayoutState = JSONObject(json).run {
        val c = optJSONArray("center")?.let { Vec3(it.getDouble(0).toFloat(), it.getDouble(1).toFloat(), it.getDouble(2).toFloat()) }
        val e = optJSONObject("env")?.let {
            Env(it.optInt("dim", Comfort.DIM_MAX), it.optBoolean("passthrough"),
                it.optDouble("cutHalfYaw", Shell.WINDOW_HALF_YAW.toDouble()).toFloat(),
                it.optDouble("cutTop", Shell.WINDOW_PITCH_TOP.toDouble()).toFloat())
        } ?: Env()
        val ps = optJSONArray("panels") ?: JSONArray()
        val saved = (0 until ps.length()).map { i ->
            ps.getJSONObject(i).run {
                Saved(getString("app"), getString("title"), getDouble("theta").toFloat(), getDouble("y").toFloat(),
                    getDouble("rM").toFloat(), getDouble("w").toFloat(), getDouble("h").toFloat(), optBoolean("shown", true))
            }
        }
        val a = optJSONObject("anchor")?.let {
            val p = it.getJSONArray("pos")
            Anchor(Vec3(p.getDouble(0).toFloat(), p.getDouble(1).toFloat(), p.getDouble(2).toFloat()),
                it.getDouble("yaw").toFloat(), it.getDouble("pitch").toFloat(), it.getDouble("roll").toFloat())
        }
        LayoutState(saved, c, optDouble("forward", 0.0).toFloat(), e, a)
    }
}

/** PROTOCOL v6 HELLO: which Mac this connection is. Streamers without HELLO count as [DEFAULT]. */
data class Host(val id: String, val name: String, val version: String = "") {
    companion object {
        val DEFAULT = Host("default", "Mac")
        fun parse(json: String): Host = JSONObject(json).run {
            Host(optString("id").ifEmpty { "default" }, optString("host").ifEmpty { "Mac" }, optString("version"))
        }
    }
}

/**
 * One saved layout ("room") per Mac, keyed by host id, over a plain key/value store. The pre-v6
 * single layout is handed to the first host that connects, once.
 */
class Rooms(private val get: (String) -> String?, private val put: (String, String?) -> Unit) {
    fun load(host: String): String? {
        get(KEY + host)?.let { return it }
        val legacy = get(LEGACY) ?: return null
        put(KEY + host, legacy); put(LEGACY, null); put(MIGRATED_TO, host)
        return legacy
    }

    fun save(host: String, json: String) = put(KEY + host, json)

    companion object {
        const val KEY = "layout:"
        const val LEGACY = "layout"
        const val MIGRATED_TO = "layout.migratedTo"
    }
}

/** PROTOCOL v7 OVERLAY: a transient launcher/popup window (Raycast…), streamed while [visible]. */
data class Overlay(val id: Int, val app: String, val visible: Boolean, val w: Int, val h: Int) {
    companion object {
        fun parse(json: String): Overlay = JSONObject(json).run {
            Overlay(getInt("id"), optString("app"), optBoolean("visible"), optInt("w", 1200), optInt("h", 800))
        }
    }
}

/** PROTOCOL v7 FOCUS_CHANGED payload (only the id is needed; app/title are for the log). */
data class FocusChanged(val id: Int, val app: String, val title: String) {
    companion object {
        fun parse(json: String): FocusChanged = JSONObject(json).run { FocusChanged(getInt("id"), optString("app"), optString("title")) }
    }
}
