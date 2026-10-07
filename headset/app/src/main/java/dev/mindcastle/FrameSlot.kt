package dev.mindcastle

/**
 * One-frame handoff from the socket reader to a decoder thread. The reader never waits on the
 * decoder: if the decoder hasn't taken the previous frame yet, a frame is dropped instead.
 * Every H.264 P-frame here is a reference frame, so after any drop the decoder can't produce a
 * correct picture again until the next keyframe: non-key frames are discarded until one arrives,
 * and [offer] tells the caller to ask the Mac for one.
 */
class FrameSlot<F : Any>(private val isKey: (F) -> Boolean) {
    private var pending: F? = null
    private var waitKey = true
    private var woken = false
    private var droppedCount = 0
    val dropped: Int @Synchronized get() = droppedCount

    /** Reader side. Returns true when a keyframe should be requested. */
    @Synchronized fun offer(f: F): Boolean {
        if (isKey(f)) {
            if (pending != null) droppedCount++ // a keyframe supersedes anything still waiting
            pending = f; waitKey = false
            (this as Object).notifyAll()
            return false
        }
        if (waitKey) { droppedCount++; return true }
        if (pending == null) { pending = f; (this as Object).notifyAll(); return false }
        // Decoder is behind. Keep the older frame (it's next in the reference chain), drop this one,
        // and skip ahead to the next keyframe.
        droppedCount++; waitKey = true
        return true
    }

    /** Decoder side: the next frame, or null after [timeoutMs] or a [wake]. */
    @Synchronized fun take(timeoutMs: Long): F? {
        if (pending == null && !woken && timeoutMs > 0) (this as Object).wait(timeoutMs) // wait(0) would block forever
        woken = false
        return pending.also { pending = null }
    }

    /** The decoder lost its place (new codec, input queue full): discard until the next keyframe. */
    @Synchronized fun needKey() { waitKey = true }

    /** Drop anything pending and wait for a keyframe (config/surface change). */
    @Synchronized fun reset() { if (pending != null) droppedCount++; pending = null; waitKey = true; wake() }

    @Synchronized fun wake() { woken = true; (this as Object).notifyAll() }
}
