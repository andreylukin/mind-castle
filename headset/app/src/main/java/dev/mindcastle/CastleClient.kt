package dev.mindcastle

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors

private const val TAG = "Castle"

/** Headset clock for PROTOCOL v1 tracing. */
fun nowUs() = SystemClock.elapsedRealtimeNanos() / 1000

/** A decoded Mac cursor image (PROTOCOL v3 CURSOR). */
class CursorImage(val header: CursorHeader, val bitmap: Bitmap)

data class MacWindow(val id: Int, val app: String, val title: String, val w: Int, val h: Int)

/** Connects to the Mac over `adb reverse tcp:7420 tcp:7420` and speaks PROTOCOL.md. */
class CastleClient(private val host: String = "127.0.0.1", private val port: Int = 7420) {
    val windows = MutableStateFlow<List<MacWindow>>(emptyList())
    val status = MutableStateFlow("connecting…")
    val control = MutableStateFlow(false)
    /** Latest Mac cursor image, and the first one received (drawn as the arrow away from window content). */
    val cursor = MutableStateFlow<CursorImage?>(null)
    val arrow = MutableStateFlow<CursorImage?>(null)
    /** The Mac on the other end (PROTOCOL v6 HELLO; [Host.DEFAULT] for streamers without it). */
    val mac = MutableStateFlow<Host?>(null)
    @Volatile private var helloThisConnection = false

    /** Latest push-to-talk status (PROTOCOL v5 VOICE), for the picker header. */
    val voice = MutableStateFlow<Voice?>(null)
    /** Called on the network thread for each POINTER (control mode only). */
    @Volatile var onPointer: ((Pointer) -> Unit)? = null
    private val decoders = ConcurrentHashMap<Int, WindowDecoder>()
    @Volatile private var out: DataOutputStream? = null
    private val sender = Executors.newSingleThreadExecutor { Thread(it, "castle-send").apply { isDaemon = true } }
    private val clock = ClockSync()
    private val traces = ConcurrentLinkedQueue<FrameTrace>()

    @Volatile private var suspended = false
    @Volatile private var socket: Socket? = null

    /**
     * App went to the background (onStop): close the socket and drop the codecs so nothing in this
     * process makes or receives binder calls while the cached-app freezer has us (Android kills a
     * frozen app that gets sync binder traffic). [resume] reconnects; the client re-subscribes itself.
     */
    fun suspend() {
        suspended = true
        socket?.runCatching { close() }
        decoders.values.forEach { it.suspend() }
        Log.i(TAG, "lifecycle: suspended (socket closed, codecs released)")
    }

    fun resume() {
        if (!suspended) return
        suspended = false
        Log.i(TAG, "lifecycle: resumed, reconnecting")
    }

    fun start() {
        Thread({ loop() }, "castle-net").apply { isDaemon = true; start() }
        Thread({ tick() }, "castle-tick").apply { isDaemon = true; start() }
    }

    fun decoder(id: Int): WindowDecoder = decoders.getOrPut(id) { WindowDecoder(id, { requestKeyframe(id) }, traces::add) }

    fun subscribe(ids: Collection<Int>) {
        lastSubscribe = ids.toList()
        send(10, 0, JSONObject().put("ids", JSONArray(ids.toList())).toString().toByteArray())
    }
    /** Re-sent after the first WINDOW_LIST of every connection, so a reconnect (or Mac streamer restart) resumes streams. */
    @Volatile private var lastSubscribe: List<Int> = emptyList()
    @Volatile private var resubscribe = false
    /** Called on the network thread for each COMMAND (raw JSON). */
    @Volatile var onCommand: ((String) -> Unit)? = null

    fun click(id: Int, x: Float, y: Float) =
        send(12, id, JSONObject().put("x", x.toDouble()).put("y", y.toDouble()).toString().toByteArray())

    fun mouse(id: Int, kind: String, x: Float, y: Float, button: Int) = send(17, id,
        JSONObject().put("kind", kind).put("x", x.toDouble()).put("y", y.toDouble()).put("button", button).toString().toByteArray())

    fun focus(id: Int) = send(11, id, ByteArray(0))

    fun requestKeyframe(id: Int) = send(14, id, ByteArray(0))

    fun scroll(id: Int, x: Float, y: Float, dx: Int, dy: Int) = send(13, id,
        JSONObject().put("x", x.toDouble()).put("y", y.toDouble()).put("dx", dx).put("dy", dy).toString().toByteArray())

    // One sender thread keeps messages in order and PING timing honest.
    private fun send(type: Int, id: Int, payload: ByteArray) {
        val o = out ?: return
        sender.execute {
            try {
                o.writeByte(type); o.writeInt(id); o.writeInt(payload.size); o.write(payload); o.flush()
            } catch (e: Exception) { Log.w(TAG, "send failed", e) }
        }
    }

    /** PING every 1 s, FRAME_REPORT every 500 ms. */
    private fun tick() {
        var n = 0L
        while (true) {
            Thread.sleep(500)
            if (n++ % 2 == 0L) send(15, 0, ByteBuffer.allocate(8).putLong(nowUs()).array())
            if (n % 20 == 0L) clock.best()?.let { (off, rtt) -> Log.i(TAG, "clock offset=$off us rtt=$rtt us") }
            val batch = generateSequence { traces.poll() }.toList()
            val off = clock.offset()
            if (batch.isNotEmpty() && off != null) send(16, 0, frameReportJson(batch, off).toByteArray())
        }
    }

    private fun loop() {
        while (true) {
            if (suspended) { status.value = "paused"; Thread.sleep(200); continue }
            try {
                Socket(host, port).also { socket = it }.use { s ->
                    s.tcpNoDelay = true
                    s.soTimeout = 6000 // Mac sends WINDOW_LIST every ~2s; silence means a stale adb tunnel
                    val inp = DataInputStream(s.getInputStream().buffered(1 shl 20))
                    out = DataOutputStream(s.getOutputStream())
                    status.value = "connected"
                    resubscribe = true
                    helloThisConnection = false
                    while (true) {
                        val type = inp.readUnsignedByte()
                        val id = inp.readInt()
                        val payload = ByteArray(inp.readInt()).also { inp.readFully(it) }
                        handle(type, id, payload, nowUs())
                    }
                }
            } catch (e: Exception) {
                status.value = "disconnected: ${e.message} — retrying"
            }
            out = null
            socket = null
            control.value = false
            clock.reset() // a new connection may be a new Mac process / adb tunnel
            Thread.sleep(1000)
        }
    }

    /**
     * Network thread. A different Mac than last time: its window ids mean nothing here, so drop every
     * decoder and the remembered subscription before any of its frames arrive.
     */
    private fun setHost(h: Host) {
        helloThisConnection = true
        val old = mac.value
        if (old != null && old.id != h.id) {
            decoders.values.forEach { it.release() }
            decoders.clear()
            lastSubscribe = emptyList()
            windows.value = emptyList()
        }
        Log.i(TAG, "host: ${h.name} (${h.id}) v${h.version}" + if (old != null && old.id != h.id) " — switched from ${old.name}" else "")
        mac.value = h
    }

    private fun handle(type: Int, id: Int, p: ByteArray, recvUs: Long) {
        when (type) {
            22 -> setHost(runCatching { Host.parse(String(p)) }.getOrDefault(Host.DEFAULT))
            1 -> {
                if (!helloThisConnection) setHost(Host.DEFAULT) // older streamer: no HELLO before its first WINDOW_LIST
                val a = JSONArray(String(p))
                windows.value = (0 until a.length()).map { i ->
                    a.getJSONObject(i).run { MacWindow(getInt("id"), getString("app"), getString("title"), getInt("w"), getInt("h")) }
                }
                if (resubscribe) {
                    resubscribe = false
                    val live = windows.value.map { it.id }.toSet()
                    lastSubscribe.filter { it in live }.takeIf { it.isNotEmpty() }?.let { subscribe(it) }
                }
            }
            2 -> decoder(id).onConfig(p)
            3, 4 -> {
                val pts = java.nio.ByteBuffer.wrap(p, 0, 8).long
                decoder(id).onFrame(p.copyOfRange(8, p.size), pts, type == 4, recvUs)
            }
            5 -> decoders.remove(id)?.release()
            6 -> onPointer?.invoke(Pointer.parse(String(p)))
            7 -> control.value = JSONObject(String(p)).optBoolean("control").also { Log.i(TAG, "control mode $it") }
            8 -> ByteBuffer.wrap(p).run { clock.add(long, long, recvUs) }
            20 -> onCommand?.invoke(String(p))
            21 -> voice.value = runCatching { Voice.parse(String(p)) }.getOrNull()?.also { Log.i(TAG, "voice: ${it.state} '${it.text.take(80)}'") }
            9 -> {
                val h = CursorHeader.parse(p)
                val bmp = BitmapFactory.decodeByteArray(p, CursorHeader.SIZE, p.size - CursorHeader.SIZE)
                if (bmp == null) Log.w(TAG, "cursor: PNG decode failed ($h, ${p.size} bytes)")
                else CursorImage(h, bmp).also { cursor.value = it; if (arrow.value == null) arrow.value = it }
            }
        }
    }
}

/**
 * One H.264 decoder rendering straight into a panel's Surface, on its own thread. The network
 * reader only hands frames over through a [FrameSlot] and never blocks here, so a slow decoder
 * drops frames instead of backing up the socket (and with it PING/POINTER/COMMAND).
 */
class WindowDecoder(private val id: Int, private val needKeyframe: () -> Unit, private val onTrace: (FrameTrace) -> Unit) {
    private class Frame(val au: ByteArray, val pts: Long, val key: Boolean, val recvUs: Long)

    private val slot = FrameSlot<Frame> { it.key }
    private var codec: MediaCodec? = null
    @Volatile private var config: ByteArray? = null
    @Volatile private var surface: Surface? = null
    @Volatile private var rebuild = false
    @Volatile private var released = false
    private var codecNeedsKey = true
    private var drainer: Thread? = null
    private val received = ConcurrentHashMap<Long, Long>() // pts -> nowUs() the frame was read off the socket
    @Volatile private var lastKeyRequestMs = 0L

    init { Thread({ run() }, "castle-dec-$id").apply { isDaemon = true; start() } }

    fun setSurface(s: Surface?) { surface = s; rebuild = true; slot.reset(); if (s != null) requestKeyframe() }

    fun onConfig(csd: ByteArray) { config = csd; rebuild = true; slot.reset() }

    /** Called on the network thread; never blocks on the codec. */
    fun onFrame(au: ByteArray, ptsUs: Long, key: Boolean, recvUs: Long) {
        if (slot.offer(Frame(au, ptsUs, key, recvUs))) requestKeyframe()
    }

    fun release() { released = true; slot.wake() }

    /** Background: tear the codec down (on the decode thread) until frames flow again. */
    fun suspend() { rebuild = true; slot.reset() }

    private fun requestKeyframe() {
        val now = System.currentTimeMillis()
        if (now - lastKeyRequestMs < 300) return // one in flight is enough
        lastKeyRequestMs = now
        needKeyframe()
    }

    private fun run() {
        while (!released) {
            val f = slot.take(200)
            if (released) break
            if (rebuild) { rebuild = false; teardown() }
            f ?: continue
            if (codec == null && !ensureCodec()) { slot.needKey(); continue }
            if (codecNeedsKey && !f.key) { slot.needKey(); requestKeyframe(); continue }
            val c = codec ?: continue
            val idx = try { c.dequeueInputBuffer(50_000) } catch (e: IllegalStateException) { -1 }
            if (idx < 0) { slot.needKey(); requestKeyframe(); continue } // decoder full: this frame is lost
            c.getInputBuffer(idx)!!.apply { clear(); put(f.au) }
            received[f.pts] = f.recvUs
            c.queueInputBuffer(idx, 0, f.au.size, f.pts, if (f.key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
            if (f.key) codecNeedsKey = false
        }
        teardown()
    }

    private fun ensureCodec(): Boolean {
        val s = surface ?: return false
        val csd = config ?: return false
        return try {
            val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).also { codec = it }
            val lowLatency = c.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                .isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)
            val vendor = c.supportedVendorParameters
            // Qualcomm decoders otherwise hold ~5 frames for reordering (measured ~300 ms).
            val applied = listOf("vendor.qti-ext-dec-low-latency.enable", "vendor.qti-ext-dec-picture-order.enable")
                .filter { it in vendor }
            Log.i(TAG, "decoder $id: codec=${c.name} FEATURE_LowLatency=$lowLatency vendorParams=$vendor applied=$applied")
            val f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1920, 1080).apply {
                setInteger(MediaFormat.KEY_MAX_WIDTH, 3840); setInteger(MediaFormat.KEY_MAX_HEIGHT, 2400)
                setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                applied.forEach { setInteger(it, 1) }
            }
            c.apply {
                configure(f, s, null, 0); start()
                val idx = dequeueInputBuffer(100_000)
                getInputBuffer(idx)!!.apply { clear(); put(csd) }
                queueInputBuffer(idx, 0, csd.size, 0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
            }
            codecNeedsKey = true
            drainer = Thread({ drain(codec!!) }, "castle-drain-$id").apply { start() }
            requestKeyframe()
            true
        } catch (e: Exception) { Log.e(TAG, "decoder $id init failed", e); teardown(); false }
    }

    // Render each frame the moment it's decoded. Capture only sends on change, so waiting
    // for the next input to drain would hold a keystroke on screen until the following one.
    private fun drain(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        var n = 0; var sum = 0.0; var max = 0.0; var since = System.nanoTime()
        try {
            while (!Thread.currentThread().isInterrupted) {
                val o = c.dequeueOutputBuffer(info, 5_000)
                if (o < 0) continue
                c.releaseOutputBuffer(o, true)
                val outUs = nowUs()
                received.remove(info.presentationTimeUs)?.let {
                    val ms = (outUs - it) / 1e3; n++; sum += ms; if (ms > max) max = ms
                    onTrace(FrameTrace(id, info.presentationTimeUs, it, outUs))
                }
                if (System.nanoTime() - since > 5_000_000_000 && n > 0) {
                    Log.i(TAG, "window $id: decode avg %.1f ms max %.1f ms over $n frames, dropped ${slot.dropped} total".format(sum / n, max))
                    n = 0; sum = 0.0; max = 0.0; since = System.nanoTime()
                }
            }
        } catch (_: IllegalStateException) { /* codec released */ }
    }

    private fun teardown() {
        received.clear()
        drainer?.run { interrupt(); join(200) }
        drainer = null
        codec?.runCatching { stop(); release() }
        codec = null
        codecNeedsKey = true
    }
}
