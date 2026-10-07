package dev.mindcastle

import android.annotation.SuppressLint
import android.os.Bundle
import androidx.compose.runtime.DisposableEffect
import android.util.Log
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import androidx.compose.foundation.layout.offset
import android.graphics.drawable.GradientDrawable
import androidx.compose.ui.graphics.toArgb
import androidx.compose.foundation.layout.width
import androidx.xr.runtime.math.Quaternion
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.text.style.TextOverflow
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.xr.compose.platform.LocalSession
import androidx.xr.compose.spatial.Subspace
import androidx.xr.compose.subspace.SpatialAndroidViewPanel
import androidx.xr.compose.subspace.SpatialBox
import androidx.xr.compose.subspace.SceneCoreEntity
import androidx.xr.compose.subspace.SpatialPanel
import androidx.xr.compose.subspace.layout.MovePolicy
import androidx.xr.compose.subspace.layout.ResizePolicy
import androidx.xr.compose.subspace.layout.SpatialMoveEvent
import androidx.xr.compose.subspace.layout.SpatialMoveEventType
import androidx.xr.compose.subspace.layout.SpatialResizeEventType
import androidx.xr.compose.subspace.layout.SubspaceModifier
import androidx.xr.compose.subspace.layout.height
import androidx.xr.compose.subspace.layout.movable
import androidx.xr.compose.subspace.layout.offset
import androidx.xr.compose.subspace.layout.resizable
import androidx.xr.compose.subspace.layout.rotate
import androidx.xr.compose.subspace.layout.SpatialRoundedCornerShape
import androidx.xr.compose.subspace.draw.scale
import androidx.compose.foundation.shape.CornerSize
import androidx.xr.compose.subspace.layout.size
import androidx.xr.compose.subspace.layout.width
import androidx.xr.arcore.ArDevice
import androidx.xr.arcore.TrackingState
import androidx.xr.runtime.Config
import androidx.xr.runtime.DeviceTrackingMode
import androidx.xr.runtime.Session
import androidx.xr.runtime.math.Pose
import androidx.xr.runtime.math.Vector3
import androidx.xr.scenecore.Entity
import androidx.xr.scenecore.SpatialEnvironment
import androidx.xr.scenecore.PointerCaptureComponent
import androidx.xr.scenecore.SpatialPointerIcon
import androidx.xr.compose.subspace.layout.pointerHoverIcon
import androidx.xr.scenecore.scene
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.xr.scenecore.GltfModel
import androidx.xr.scenecore.GltfModelEntity
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    private val client = CastleClient()
    private val main = Handler(Looper.getMainLooper())
    private val desk: Desk by lazy {
        Desk { tick.value++ }.apply {
            lookToJump = false
            acceleration = true
            onGesture = { Log.d("Castle", it) }
        }
    }
    /** Bumped on every Desk change; Desk itself is plain Kotlin touched only on the main thread. */
    private val tick = MutableStateFlow(0)
    private var pointerCount = 0
    /** Bumped on every resume: taking the headset off drops the app out of full space. */
    private val resumes = MutableStateFlow(0)
    private var pointerLogAt = 0L
    private var pointerButtons = 0
    /** Head ray in Subspace dp, for look-to-jump; set once the session and origin entity exist. */
    var gaze: (() -> Ray?)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        desk.onLayoutChange = { main.removeCallbacks(saveLayout); main.postDelayed(saveLayout, 500) }
        client.onCommand = { json -> main.post { command(json) } }
        desk.onCapture = { id, why -> Log.i("Castle", if (id != null) "capture: start window $id ($why)" else "capture: end ($why)") }
        client.onPointer = { p ->
            main.post {
                val now = System.currentTimeMillis()
                val capBefore = desk.captured
                desk.pointer(p, now, gaze).forEach(::perform)
                pointerCount++
                // Every 1 s, and at once on button/capture changes (the emulator lab parses this line).
                val changed = p.buttons != pointerButtons || desk.captured != capBefore
                pointerButtons = p.buttons
                if (changed || now - pointerLogAt > 1000) {
                    Log.i("Castle", "pointer: $pointerCount msgs, cursor=(θ %.3f, t %.3f) hover=${desk.hover} buttons=${p.buttons} captured=${desk.captured} center=${desk.center} panels=${desk.panels.map { (id, q) -> "$id@(θ %.3f y %.0f r %.0f %.0fx%.0f)".format(q.theta, q.y, q.r, q.w, q.h) }}".format(desk.cursorTheta, desk.cursorT))
                    pointerCount = 0; pointerLogAt = now
                }
            }
        }
        client.start()
        window.decorView.setBackgroundColor(android.graphics.Color.BLACK)
        setContent { Castle(client, desk, tick, resumes, recenterRequests, foreground, roomHost, ::switchHost, ::toggle) { gaze = it } }
    }

    /** Bumped by the "recenter" COMMAND; Castle re-centers on the head like the headset's recenter button. */
    private val recenterRequests = MutableStateFlow(0)
    private val prefs by lazy { getSharedPreferences("castle", MODE_PRIVATE) }
    /** One saved layout per Mac (PROTOCOL v6 HELLO host id). */
    private val rooms by lazy {
        Rooms({ prefs.getString(it, null) }, { k, v -> prefs.edit().apply { if (v == null) remove(k) else putString(k, v) }.apply() })
    }
    /** Host id whose room the Desk currently holds; null until the first Mac says who it is. */
    private val roomHost = MutableStateFlow<String?>(null)

    private val saveLayout = Runnable {
        val host = roomHost.value ?: return@Runnable
        val st = desk.layoutState()
        rooms.save(host, LayoutJson.write(st))
        Log.i("Castle", "layout: saved ${st.saved.size} panels for $host env=${st.env}")
    }

    /** A (different) Mac connected: save the old room, load this one's (or start fresh). Main thread. */
    private fun switchHost(h: Host) {
        if (roomHost.value == h.id) return
        roomHost.value?.let { main.removeCallbacks(saveLayout); saveLayout.run() }
        val st = rooms.load(h.id)?.let { json ->
            runCatching { LayoutJson.read(json) }.onFailure { Log.w("Castle", "layout: unreadable for ${h.id}, starting fresh", it) }.getOrNull()
        }
        desk.loadRoom(st)
        roomHost.value = h.id
        Log.i("Castle", "layout: restored ${desk.layoutState().saved.size} remembered panels for ${h.name} (${h.id}) env=${desk.env} anchor=${desk.anchor}")
    }

    private fun command(json: String) {
        val c = runCatching { Command.parse(json) }.getOrElse { Log.w("Castle", "command: bad JSON $json"); return }
        if (c.cmd == "recenter") { recenterRequests.value++; Log.i("Castle", "command: recenter"); return }
        val before = desk.shown
        Log.i("Castle", "command: ${desk.command(c, gaze?.invoke(), System.currentTimeMillis())}")
        if (desk.shown != before) client.subscribe(desk.shown) // show/hide/undo
        // Voice focus/show: Mac typing should go to that window too.
        if (c.cmd == "focus" || c.cmd == "show") desk.lastClicked?.let { client.focus(it) }
    }

    override fun onResume() {
        super.onResume()
        resumes.value++
        foreground.value = true
    }

    override fun onStart() {
        super.onStart()
        client.resume()
    }

    override fun onStop() {
        client.suspend()
        super.onStop()
    }

    // Pointer capture (control mode) is only held while we're in front, so the user is never left
    // without a system pointer after the app goes to the background.
    override fun onPause() {
        foreground.value = false
        super.onPause()
    }

    /** True between onResume and onPause. */
    private val foreground = MutableStateFlow(false)

    private fun toggle(id: Int) {
        val w = client.windows.value.firstOrNull { it.id == id } ?: return
        desk.toggle(id, w.w, w.h)
        client.subscribe(desk.shown)
    }

    private fun perform(a: Action) = when (a) {
        is Action.Mouse -> client.mouse(a.id, a.kind, a.x, a.y, a.button)
        is Action.Scroll -> client.scroll(a.id, a.x, a.y, a.dx, a.dy)
        is Action.PickerRow -> client.windows.value.getOrNull(a.row)?.let { toggle(it.id) } ?: Unit
    }
}

@Composable
fun Castle(
    client: CastleClient, desk: Desk, tick: MutableStateFlow<Int>, resumes: MutableStateFlow<Int>,
    recenterRequests: MutableStateFlow<Int>, foreground: MutableStateFlow<Boolean>,
    roomHost: MutableStateFlow<String?>, switchHost: (Host) -> Unit,
    toggle: (Int) -> Unit, setGaze: ((() -> Ray?)?) -> Unit,
) {
    val session = LocalSession.current
    val resumed by resumes.collectAsState()
    val enterFullSpace = {
        session?.scene?.run {
            requestFullSpaceMode()
            // Passthrough on; BlackShell blacks out everything except the laptop window.
            spatialEnvironment.preferredPassthroughOpacity = 1f
            spatialEnvironment.preferredSpatialEnvironment = SpatialEnvironment.SpatialEnvironmentPreference(null, null)
        }
        Unit
    }
    LaunchedEffect(session, resumed) { enterFullSpace() }
    // Shown only when the system has put us back in home space (e.g. after the headset was taken off).
    Box(Modifier.fillMaxSize().background(Color.Black).clickable { enterFullSpace() }, contentAlignment = Alignment.Center) {
        Text("Tap to enter Mind Castle", color = Color.White, fontSize = 28.sp)
    }
    val windows by client.windows.collectAsState()
    val status by client.status.collectAsState()
    val control by client.control.collectAsState()
    val macCursor by client.cursor.collectAsState()
    val arrow by client.arrow.collectAsState()
    val voice by client.voice.collectAsState()
    // Done/error lines linger, then the header goes back to the connection status.
    LaunchedEffect(voice) {
        val v = voice ?: return@LaunchedEffect
        if (v.state == "done" || v.state == "error") { delay(4000); client.voice.value = null }
    }
    LaunchedEffect(control) { desk.control = control }
    val host by client.mac.collectAsState()
    val room by roomHost.collectAsState()
    LaunchedEffect(host) { host?.let(switchHost) }
    // Only sync once the Desk holds this Mac's room (window ids are per Mac).
    LaunchedEffect(windows, room) { if (room != null && room == host?.id && desk.syncWindows(windows)) client.subscribe(desk.shown) }
    val density = LocalDensity.current.density
    // Entity at the anchored layout origin (head poses in its frame = layout-frame dp; the shell hangs off it),
    // and one at the plain Subspace origin, to measure the head for a new anchor.
    val origin = remember { mutableStateOf<Entity?>(null) }
    val root = remember { mutableStateOf<Entity?>(null) }
    LaunchedEffect(session) {
        session ?: return@LaunchedEffect
        desk.setDpPerMeter(session.scene.virtualPixelDensity.pixelsPerMeter / density)
        val res = runCatching {
            session.configure(Config.Builder(session.config).setDeviceTracking(DeviceTrackingMode.SPATIAL).build())
        }
        Log.i("Castle", "head: dpPerMeter=%.1f deviceTracking -> ${res.getOrElse { "failed: $it" }}".format(desk.dpPerMeter))
    }
    // The headset's recenter button moves the activity-space origin: treat it as "relaunch the layout".
    val recenters = remember { mutableStateOf(0) }
    DisposableEffect(session) {
        val space = session?.scene?.activitySpace
        val onRecenter = Runnable { Log.i("Castle", "head: recenter"); recenters.value++ }
        space?.addOriginChangedListener(onRecenter)
        onDispose { space?.removeOriginChangedListener(onRecenter) }
    }
    val recenterReq by recenterRequests.collectAsState()
    LaunchedEffect(recenterReq) { if (recenterReq > 0) recenters.value++ }
    LaunchedEffect(session, origin.value, root.value, recenters.value, resumed) {
        val s = session ?: return@LaunchedEffect
        val o = origin.value ?: return@LaunchedEffect
        val rt = root.value ?: return@LaunchedEffect
        if (recenters.value > 0) enterFullSpace()
        setGaze { headRay(s, o, desk.dpPerMeter) } // in the anchored (layout) frame
        // Re-anchor the whole layout on the head's current pose (incl. pitch when lying down). Keep
        // waiting: the headset may be off-head at launch.
        var waited = 0
        while (true) {
            val a = headAnchor(s, rt, desk.dpPerMeter)
            if (a != null) {
                desk.setAnchor(a)
                Log.i("Castle", "head: cylinder center=${a.pos} anchor=$a")
                return@LaunchedEffect
            }
            if (++waited == 100) Log.w("Castle", "head: no tracked pose yet after 20 s; still waiting (fallback center ${desk.center})")
            delay(200)
        }
    }

    val shellGeo by remember { tick.map { desk.shellGeometry() }.distinctUntilChanged() }.collectAsState(desk.shellGeometry())
    val shellAlpha by remember { tick.map { desk.env.shellAlpha }.distinctUntilChanged() }.collectAsState(desk.env.shellAlpha)
    ShellMesh(session, origin.value, shellGeo, shellAlpha)
    val inFront by foreground.collectAsState()
    HandPointerOff(session, origin.value, control && inFront, recenters.value)

    Subspace {
        // Desk is plain Kotlin; reading tick here makes the subspace recompose on every Desk change.
        tick.collectAsState().value
        // The focus flash ends by itself: redraw once it's over (keyed here, where Desk changes recompose).
        LaunchedEffect(desk.flashUntilMs) {
            val left = desk.flashUntilMs - System.currentTimeMillis()
            if (left > 0) { delay(left + 50); tick.value++ }
        }
        session?.let { s -> SceneCoreEntity(factory = { Entity.create(s, "castle-root").also { root.value = it } }) }
        // Everything else lives in the head-aligned frame: one transform for the whole layout.
        val a = desk.anchor
        SpatialBox(SubspaceModifier.offset(a.pos.x.dp, a.pos.y.dp, a.pos.z.dp).rotate(
            Quaternion.fromAxisAngle(Vector3(0f, 1f, 0f), a.yawDeg) * Quaternion.fromAxisAngle(Vector3(1f, 0f, 0f), a.pitchDeg) *
                Quaternion.fromAxisAngle(Vector3(0f, 0f, 1f), a.rollDeg))) {
            session?.let { s -> SceneCoreEntity(factory = { Entity.create(s, "castle-origin").also { origin.value = it } }) }
            // Every SpatialPanel gets a system input channel the platform never frees (system_server fd leak),
            // so the panel count is kept minimal: one per window (outline, corner handles and grab bar are
            // drawn inside it — see Chrome), one for the picker, and one cursor panel that's moved and parked
            // instead of added/removed. A hidden window's panel stays composed, parked out of sight, so showing
            // it again reuses it. Positions are passed as modifiers/values, never read from `desk` inside a
            // child composable, so strong skipping can't leave a stale pose.
            val panels = desk.panels
            val h = desk.hover
            // In control mode the finger resting on the trackpad reads as a hand pointer: hands off.
            val hands = !control
            val kept = remember(desk.roomEpoch) { LinkedHashMap<Int, Panel>() } // id -> last shown geometry (this Mac's)
            for ((id, p) in panels) kept[id] = p
            kept.keys.retainAll { it == Desk.PICKER || windows.any { w -> w.id == it } }
            // Keyed by room too: another Mac's window ids can collide with this one's.
            for ((id, last) in kept.entries.toList()) key(desk.roomEpoch, id) {
                val p = panels[id] // null: hidden by the user, parked
                val chrome = if (p == null) Chrome.State() else Chrome.State(
                    outline = control && h?.id == id || desk.flashId == id && System.currentTimeMillis() < desk.flashUntilMs,
                    dragging = desk.dragging && h?.id == id,
                    hot = h?.takeIf { it.id == id && it.zone == Zone.CORNER }?.let { it.cx to it.cy },
                    corners = id != Desk.PICKER,
                    barHot = control && h?.id == id && h.zone == Zone.BAR,
                )
                val mod = panelModifier(desk, id, p ?: last, parked = p == null, resizable = id != Desk.PICKER, hands = hands && p != null)
                if (id == Desk.PICKER) {
                    // Square panel: the chrome's corner handles sit in the corners a rounded panel would clip.
                    SpatialPanel(mod, shape = SpatialRoundedCornerShape(CornerSize(0.dp))) {
                        PickerChrome(last, chrome) { Picker(host?.name ?: "Mac", status, control, voice, windows, desk.shown, toggle) }
                    }
                } else {
                    windows.firstOrNull { it.id == id }?.let { w -> WindowPanel(client, w, p ?: last, chrome, mod, desk::noteClicked) }
                }
            }
            // One cursor panel, always drawn in control mode: the Mac's own cursor at the window's scale
            // (hotspot on the pointer), or a white dot until the Mac has sent a cursor image; 4 dp in front of
            // whatever it's on. In empty space: the dot plus a chevron pointing at the nearest panel.
            val img = if (desk.overContent) macCursor else arrow
            val (fp, fu, fv) = desk.cursorFrame()
            val toward = if (control) desk.nearestPanelDirection() else null
            val cursorAt = when {
                !control -> parked(desk).size(20.dp)
                toward != null -> placed(desk.planePoint(fp, fu, fv, lift = 4f), fp).size(EMPTY_CURSOR.dp)
                img != null -> img.header.placement(img.bitmap.width, img.bitmap.height, desk.cursorDpPerPoint()).let { pl ->
                    placed(desk.planePoint(fp, fu + pl.du, fv + pl.dv, lift = 4f), fp).width(pl.w.dp).height(pl.h.dp)
                }
                else -> placed(desk.planePoint(fp, fu, fv, lift = 4f), fp).size(20.dp)
            }
            SpatialPanel(cursorAt.pointerHoverIcon(SpatialPointerIcon.NONE), shape = SpatialRoundedCornerShape(CornerSize(0.dp))) {
                when {
                    !control -> {}
                    toward != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(20.dp).background(Color.White, CircleShape).border(2.dp, Color(0xFF202020), CircleShape))
                        val d = 22f // chevron distance from the dot, dp
                        Text("➤", color = Color(0xFF7FD4FF), fontSize = 16.sp, modifier = Modifier
                            .offset((cos(toward) * d).dp, (-sin(toward) * d).dp)
                            .graphicsLayer { rotationZ = -Math.toDegrees(toward.toDouble()).toFloat() })
                    }
                    img != null -> Image(img.bitmap.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize())
                    else -> Box(Modifier.fillMaxSize().background(Color.White, CircleShape).border(2.dp, Color(0xFF202020), CircleShape))
                }
            }
        }
    }
}

/** The head's pose in [root]'s frame (plain Subspace dp) as a layout anchor, or null while untracked. */
fun headAnchor(session: Session, root: Entity, dpPerMeter: Float): Anchor? = runCatching {
    val st = ArDevice.getInstance(session).state.value
    if (st.trackingState != TrackingState.TRACKING) return null
    val pose: Pose = session.scene.perceptionSpace.transformPoseTo(st.devicePose, root)
    val t = pose.translation
    val f = pose.rotation * Vector3(0f, 0f, -1f); val u = pose.rotation * Vector3(0f, 1f, 0f)
    Anchor.fromHead(Vec3(t.x * dpPerMeter, t.y * dpPerMeter, t.z * dpPerMeter), Vec3(f.x, f.y, f.z), Vec3(u.x, u.y, u.z))
}.getOrNull()

/** Head pose -> ray in Subspace dp (frame of the origin entity), or null while untracked. */
fun headRay(session: Session, origin: Entity, dpPerMeter: Float): Ray? = runCatching {
    val st = ArDevice.getInstance(session).state.value
    if (st.trackingState != TrackingState.TRACKING) return null
    val pose: Pose = session.scene.perceptionSpace.transformPoseTo(st.devicePose, origin)
    val t = pose.translation; val f = pose.rotation * Vector3(0f, 0f, -1f)
    Ray(Vec3(t.x * dpPerMeter, t.y * dpPerMeter, t.z * dpPerMeter), Vec3(f.x, f.y, f.z))
}.getOrNull()

/** Place at [at] (Subspace dp), yawed then pitched (degrees; pitch about the panel's own x axis). */
fun placed(at: Vec3, yaw: Float, pitch: Float = 0f): SubspaceModifier =
    SubspaceModifier.offset(at.x.dp, at.y.dp, at.z.dp)
        .rotate(Quaternion.fromAxisAngle(Vector3(0f, 1f, 0f), yaw) * Quaternion.fromAxisAngle(Vector3(1f, 0f, 0f), pitch))

/** Place at [at] in [frame]'s orientation (facing the eye). */
fun placed(at: Vec3, frame: Panel): SubspaceModifier = placed(at, frame.yawDeg, frame.pitchDeg)


/** Out of sight and out of reach: far below the user, behind the shell's bottom cap. */
fun parked(desk: Desk): SubspaceModifier = placed(desk.center + Vec3(0f, -15f * desk.dpPerMeter, 0f), 0f)

/**
 * While [on] (control mode, app in front): capture all hand/eye pointer input to our root entity so
 * the system stops drawing its own pointer ray — otherwise the finger resting on the trackpad shows
 * up as a second "mouse". The captured events are ignored. Released when [on] goes false (control off,
 * client disconnect, onPause) or the composition goes away.
 */
@Composable
fun HandPointerOff(session: Session?, origin: Entity?, on: Boolean, recenters: Int) {
    // The system pauses our capture when it takes pointer focus (headset touchpad, recenter). Re-acquire:
    // bumping [attempt] re-creates the component; also re-done on every recenter/resume via the keys.
    var attempt by remember { mutableStateOf(0) }
    DisposableEffect(session, origin, on, recenters, attempt) {
        val s = session; val o = origin
        if (!on || s == null || o == null) return@DisposableEffect onDispose {}
        val main = Handler(Looper.getMainLooper())
        var events = 0
        var state: PointerCaptureComponent.PointerCaptureState? = null
        val retry = Runnable {
            if (state == PointerCaptureComponent.PointerCaptureState.PAUSED) {
                Log.i("Castle", "pointer capture: still PAUSED, re-acquiring (attempt ${attempt + 1})")
                attempt++
            }
        }
        val c = PointerCaptureComponent.create(s, HandlerExecutor(main),
            { st ->
                state = st
                Log.i("Castle", "pointer capture: $st")
                if (st == PointerCaptureComponent.PointerCaptureState.PAUSED) main.postDelayed(retry, 3000)
            },
            { e -> if (events++ % 100 == 0) Log.i("Castle", "pointer capture: swallowed $events hand/eye events (last ${e.source}/${e.action})") })
        val ok = runCatching { o.addComponent(c) }.onFailure { Log.e("Castle", "pointer capture: attach failed", it) }.getOrDefault(false)
        Log.i("Castle", "pointer capture: attach=${ok}")
        onDispose {
            main.removeCallbacks(retry)
            if (ok) runCatching { o.removeComponent(c) }
            Log.i("Castle", "pointer capture: released")
        }
    }
}

private class HandlerExecutor(private val h: Handler) : java.util.concurrent.Executor {
    override fun execute(r: Runnable) { h.post(r) }
}

/**
 * The black shell as one glTF entity under the Subspace origin (no panels, so no input channels).
 * The .glb is generated at runtime (Glb) — SceneCore's CustomMesh/MeshEntity creates a node but
 * draws nothing on the XR emulator, while glTF renders. Rebuilt when geometry or alpha changes
 * (both rare: recenter, cutout/dim hotkeys); the new entity replaces the old once it's loaded.
 */
@Composable
fun ShellMesh(session: Session?, origin: Entity?, geo: Shell.Geometry, alpha: Float) {
    val current = remember { arrayOfNulls<Pair<GltfModel, GltfModelEntity>>(1) }
    fun drop() { current[0]?.let { (m, e) -> e.dispose(); m.close() }; current[0] = null }
    LaunchedEffect(session, origin, geo, alpha) {
        val s = session ?: return@LaunchedEffect
        val o = origin ?: return@LaunchedEffect
        if (alpha <= 0f) { drop(); Log.i("Castle", "shell: hidden"); return@LaunchedEffect }
        val m = Shell.mesh(geo)
        val bytes = Glb.write(m.positions, m.indices, floatArrayOf(0f, 0f, 0f, alpha))
        // Nothing that can throw after the entity exists: a half-built shell that never reaches
        // current[0] would never be disposed and would black out the room for good. (No pointer
        // component on it — that NPE'd on re-create; pointer capture already hides the system pointer.)
        var model: GltfModel? = null
        runCatching {
            @Suppress("RestrictedApi") // the ByteArray overload is the only way to load a generated .glb
            val gm = GltfModel.create(s, bytes, "shell-%08x".format(bytes.contentHashCode())).also { model = it }
            gm to GltfModelEntity.create(s, gm, parent = o)
        }.onSuccess {
            drop(); current[0] = it
            Log.i("Castle", "shell: gltf ${m.positions.size / 3} vertices ${m.indices.size / 3} triangles alpha=%.2f cutout=±%.1f° top %.0f°".format(alpha, geo.cutHalfYaw, geo.cutTop))
        }.onFailure {
            model?.runCatching { close() }
            if (it is CancellationException) throw it
            Log.e("Castle", "shell: gltf failed", it)
        }
    }
    // The shell is the blackout; the room behind it must always be passthrough (never a black environment).
    LaunchedEffect(session, alpha) {
        val env = session?.scene?.spatialEnvironment ?: return@LaunchedEffect
        env.preferredPassthroughOpacity = 1f
        Log.i("Castle", "shell: alpha=%.2f passthrough preferred=1 current=%.2f".format(alpha, env.currentPassthroughOpacity))
    }
    DisposableEffect(Unit) { onDispose { drop() } }
}

/** Picker content with its chrome (outline when hovered, grab bar) around it, in Compose. */
@Composable
fun PickerChrome(p: Panel, c: Chrome.State, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        if (c.outline) Box(Modifier.offset((Chrome.PAD - Chrome.M).dp, (Chrome.PAD - Chrome.M).dp)
            .size((p.w + 2 * Chrome.M).dp, (p.h + 2 * Chrome.M).dp).border(2.dp, c.outlineColor, RoundedCornerShape(Chrome.M.dp)))
        Box(Modifier.offset(Chrome.PAD.dp, Chrome.PAD.dp).size(p.w.dp, p.h.dp)) { content() }
        Box(Modifier.offset((Chrome.PAD + p.w / 2 - Bar.w(p) / 2).dp, (Chrome.PAD + p.h + Bar.GAP).dp)
            .size(Bar.w(p).dp, Bar.H.dp).background(c.barColor, RoundedCornerShape(50)))
    }
}

/**
 * Pose/size come from [Desk]; hand/controller grab and resize write back into it
 * (custom policies, so the system never moves the panel behind Desk's back). A grabbed
 * panel is snapped onto the cylinder facing the user as it moves.
 */
@Composable
fun panelModifier(desk: Desk, id: Int, p: Panel, parked: Boolean, resizable: Boolean, hands: Boolean): SubspaceModifier {
    val density = LocalDensity.current.density
    val grab = remember { arrayOfNulls<Any>(2) } // [start Pose, start position]
    val onMove = { e: SpatialMoveEvent ->
        if (e.type == SpatialMoveEventType.Start) { grab[0] = e.pose; grab[1] = desk.panels[id]?.let(desk::position) }
        val p0 = grab[0] as? Pose; val s = grab[1] as? Vec3
        if (p0 != null && s != null) {
            // Pose translation is in layout px, y up — same axes as offset().
            val d = e.pose.translation - p0.translation
            desk.moveTo(id, s + Vec3(d.x / density, d.y / density, d.z / density))
        }
        if (e.type == SpatialMoveEventType.End) desk.commit()
    }
    // The panel is the content plus its Chrome margins (outline, handles, bar), same size when parked.
    val at = if (parked) parked(desk) else placed(desk.planePoint(p, 0f, Chrome.centerV), p)
    var m = at.width(Chrome.panelW(p).dp).height(Chrome.panelH(p).dp)
        .pointerHoverIcon(if (hands) SpatialPointerIcon.DEFAULT else SpatialPointerIcon.NONE)
        .movable(enabled = hands, movePolicy = MovePolicy.custom(onMove = onMove))
    if (resizable) m = m.resizable(enabled = hands, maintainAspectRatio = true, resizePolicy = ResizePolicy.custom { e ->
        desk.resize(id, e.size.width / density - 2 * Chrome.PAD, e.size.height / density - Chrome.PAD - Chrome.BOTTOM)
        if (e.type == SpatialResizeEventType.End) desk.commit()
    })
    return m
}

@Composable
fun Picker(hostName: String, status: String, control: Boolean, voice: Voice?, windows: List<MacWindow>, shown: List<Int>, toggle: (Int) -> Unit) {
    // Fixed row heights (PickerMetrics) so the Mac cursor can hit-test rows without Compose.
    Column(Modifier.fillMaxSize().background(Color(0xFF151515)).padding(PickerMetrics.PAD.dp)) {
        Box(Modifier.height(PickerMetrics.HEADER.dp).fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
            if (voice != null) VoiceLine(voice)
            else Text(hostName + (if (status == "connected") "" else " · $status") + if (control) " · CONTROL" else "",
                color = if (control) Color(0xFF7FD4FF) else Color.Gray, fontSize = 16.sp, maxLines = 1)
        }
        for (w in windows) {
            Box(Modifier.height(PickerMetrics.ROW.dp).fillMaxWidth().clickable(enabled = !control) { toggle(w.id) },
                contentAlignment = Alignment.CenterStart) {
                Text(
                    (if (w.id in shown) "● " else "○ ") + "${w.app} — ${w.title}",
                    color = if (w.id in shown) Color.White else Color.LightGray,
                    fontSize = 18.sp, maxLines = 1,
                )
            }
        }
    }
}

/** Push-to-talk status in the picker header (PROTOCOL v5 VOICE): one line, no extra panels. */
@Composable
fun VoiceLine(v: Voice) {
    val anim = rememberInfiniteTransition(label = "voice")
    val pulse by anim.animateFloat(0.35f, 1f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "mic")
    val spin by anim.animateFloat(0f, 360f, infiniteRepeatable(tween(900)), label = "spinner")
    val (icon, color, text) = when (v.state) {
        "listening" -> Triple("🎤", Color(0xFF7FD4FF), v.text.takeLast(60).ifEmpty { "listening…" })
        "thinking" -> Triple("◌", Color(0xFFB0B0B0), v.text.ifEmpty { "thinking" })
        "done" -> Triple("✓", Color(0xFF8BE28B), v.text)
        else -> Triple("!", Color(0xFFFF6B6B), v.text.ifEmpty { "voice error" })
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(icon, color = color, fontSize = 16.sp,
            modifier = when (v.state) {
                "listening" -> Modifier.graphicsLayer { alpha = pulse }
                "thinking" -> Modifier.graphicsLayer { rotationZ = spin }
                else -> Modifier
            })
        Text(" $text", color = color, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@SuppressLint("ClickableViewAccessibility")
@Composable
fun WindowPanel(client: CastleClient, w: MacWindow, p: Panel, c: Chrome.State, modifier: SubspaceModifier, onTap: (Int) -> Unit) {
    SpatialAndroidViewPanel(
        factory = { ctx ->
            // Video, outline frame, four corner handles and the grab bar, all inside one panel (see Chrome).
            FrameLayout(ctx).apply {
                addView(View(ctx).apply { tag = "outline" })
                addView(SurfaceView(ctx).apply {
                    tag = "video"
                    val decoder = client.decoder(w.id)
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(h: SurfaceHolder) = decoder.setSurface(h.surface)
                        override fun surfaceChanged(h: SurfaceHolder, f: Int, width: Int, height: Int) {}
                        override fun surfaceDestroyed(h: SurfaceHolder) = decoder.setSurface(null)
                    })
                    setOnTouchListener { v, e ->
                        if (e.action == MotionEvent.ACTION_UP && !client.control.value) {
                            client.click(w.id, e.x / v.width, e.y / v.height)
                            onTap(w.id)
                        }
                        true
                    }
                })
                for (k in 0 until 4) addView(View(ctx).apply { tag = "corner$k" })
                addView(View(ctx).apply { tag = "bar" })
            }
        },
        modifier = modifier,
        shape = SpatialRoundedCornerShape(CornerSize(0.dp)), // corner handles live in the panel's corners
        update = { root ->
            val d = root.resources.displayMetrics.density
            fun View.place(x: Float, y: Float, wDp: Float, hDp: Float) {
                layoutParams = FrameLayout.LayoutParams((wDp * d).toInt(), (hDp * d).toInt()).apply {
                    leftMargin = (x * d).toInt(); topMargin = (y * d).toInt()
                }
            }
            val pad = Chrome.PAD; val m = Chrome.M
            root.findViewWithTag<View>("video").place(pad, pad, p.w, p.h)
            root.findViewWithTag<View>("outline").apply {
                place(pad - m, pad - m, p.w + 2 * m, p.h + 2 * m)
                visibility = if (c.outline) View.VISIBLE else View.INVISIBLE
                background = GradientDrawable().apply { cornerRadius = m * d; setStroke((2 * d).toInt(), c.outlineColor.toArgb()) }
            }
            for ((k, cxcy) in listOf(-1 to 1, 1 to 1, -1 to -1, 1 to -1).withIndex()) {
                val (cx, cy) = cxcy
                val hot = c.hot == cxcy
                val sz = if (hot) 26f else 16f
                val x = pad + (if (cx < 0) -m else p.w + m) - sz / 2
                val y = pad + (if (cy > 0) -m else p.h + m) - sz / 2
                root.findViewWithTag<View>("corner$k").apply {
                    place(x, y, sz, sz)
                    visibility = if (c.outline && c.corners) View.VISIBLE else View.INVISIBLE
                    background = GradientDrawable().apply { cornerRadius = 4 * d; setColor((if (hot) HOT else HANDLE).toArgb()) }
                }
            }
            root.findViewWithTag<View>("bar").apply {
                place(pad + p.w / 2 - Bar.w(p) / 2, pad + p.h + Bar.GAP, Bar.w(p), Bar.H)
                background = GradientDrawable().apply { cornerRadius = Bar.H * d / 2; setColor(c.barColor.toArgb()) }
            }
        },
    )
}

private const val EMPTY_CURSOR = 72f // dp: dot + chevron toward the nearest panel, in empty space
private val HOT = Color(0xFF7FD4FF)
private val HANDLE = Color(0xAAFFFFFF)
val Chrome.State.outlineColor get() = if (dragging) HOT else Color(0x55FFFFFF)
val Chrome.State.barColor get() = when { dragging -> HOT; barHot -> Color(0xEEFFFFFF); else -> Color(0x66FFFFFF) }
