package dev.hram.androsaur.audio.wake

/**
 * A wake-word detector fed with fixed-size PCM frames (16-bit mono). The concrete engine
 * (Porcupine or an open one) is not chosen yet; the rest of the app depends only on this contract.
 */
interface WakeWordEngine {
    val name: String
    val sampleRate: Int
    /** Samples per frame the engine wants in [process]. */
    val frameLength: Int
    /** True when the wake phrase ended inside this frame. */
    fun process(frame: ShortArray): Boolean
    fun close()
}

/** Placeholder until an engine is chosen: never detects anything. */
class NoWakeWordEngine(override val sampleRate: Int = 16_000, override val frameLength: Int = 512) : WakeWordEngine {
    override val name = "none"
    override fun process(frame: ShortArray) = false
    override fun close() {}
}

/** Suppresses repeated detections of one utterance. Not thread-safe: the capture thread owns it. */
class WakeGate(private val cooldownMs: Long) {
    private var last = 0L
    private var any = false

    fun accept(nowMs: Long): Boolean {
        if (any && nowMs - last < cooldownMs) return false
        any = true
        last = nowMs
        return true
    }
}
