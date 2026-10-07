package dev.mindcastle

import org.json.JSONArray
import org.json.JSONObject

/**
 * Headset->Mac clock offset from PING/PONG: offset = mac - (t0 + t1) / 2, taken from the
 * lowest-RTT sample of the last [window] (queueing only ever inflates RTT, so the fastest is truest).
 */
class ClockSync(private val window: Int = 10) {
    private class Sample(val rtt: Long, val offset: Long)
    private val samples = ArrayDeque<Sample>()

    @Synchronized fun add(t0: Long, macNow: Long, t1: Long) {
        samples.addLast(Sample(t1 - t0, macNow - (t0 + t1) / 2))
        if (samples.size > window) samples.removeFirst()
    }

    @Synchronized fun best(): Pair<Long, Long>? = samples.minByOrNull { it.rtt }?.let { it.offset to it.rtt }

    /** Headset µs -> Mac µs, or null before the first PONG. */
    fun offset(): Long? = best()?.first

    @Synchronized fun reset() = samples.clear()
}

/** One decoded frame; [recv] and [out] are headset µs (elapsedRealtime). */
data class FrameTrace(val id: Int, val pts: Long, val recv: Long, val out: Long)

/** FRAME_REPORT payload with times shifted onto the Mac clock. */
fun frameReportJson(frames: List<FrameTrace>, offset: Long): String = JSONArray().apply {
    for (f in frames) put(JSONObject().put("id", f.id).put("pts", f.pts).put("recv", f.recv + offset).put("out", f.out + offset))
}.toString()
