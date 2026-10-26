package dev.hram.androsaur.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import dev.hram.androsaur.audio.speech.MouthEnvelope
import dev.hram.androsaur.audio.speech.SpeechTiming
import java.io.File

/**
 * A Piper voice run by sherpa-onnx. The sound is played as it is made; the mouth follows the loudness of what is
 * actually playing (the playback position is looked up in the loudness of the sound written so far).
 */
class PiperSpeaker(private val context: Context, private val voice: String, threads: Int) : Speaker {
    override val id = "piper:$voice"
    override val name = "Piper · $voice"
    override val loadMs: Long
    private val tts: OfflineTts
    @Volatile private var stopped = false

    init {
        val began = SystemClock.elapsedRealtime()
        // The engine reads the voice and the phoneme data as real files (with the assets it finds no phonemizer), so both are copied out of the APK once.
        val dataDir = AssetFiles.ensure(context, "tts/espeak-ng-data", File(context.filesDir, "tts/espeak-ng-data"), VERSION)
        val voiceDir = AssetFiles.ensure(context, "tts/voices/$voice", File(context.filesDir, "tts/voices/$voice"), VERSION)
        val vits = OfflineTtsVitsModelConfig(model = File(voiceDir, "model.onnx").absolutePath, tokens = File(voiceDir, "tokens.txt").absolutePath, dataDir = dataDir.absolutePath)
        tts = OfflineTts(null, OfflineTtsConfig(model = OfflineTtsModelConfig(vits = vits, numThreads = threads, debug = false, provider = "cpu")))
        loadMs = SystemClock.elapsedRealtime() - began
    }

    override fun speak(text: String, speed: Float, mouth: Mouth): SpeechTiming {
        stopped = false
        mouth.approximate = false
        val rate = tts.sampleRate()
        val envelope = MouthEnvelope(rate)
        val minBytes = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(maxOf(minBytes, rate * 4 / 2)).setTransferMode(AudioTrack.MODE_STREAM).build()
        var written = 0L
        var firstAudioMs = 0L
        val began = SystemClock.elapsedRealtime()
        val ticking = java.util.concurrent.atomic.AtomicBoolean(true)
        val ticker = Thread({
            while (ticking.get()) { mouth.level = envelope.at(track.playbackHeadPosition.toLong() and 0xffffffffL); Thread.sleep(20) }
        }, "piper-mouth")
        try {
            track.play()
            ticker.start()
            tts.generateWithCallback(text, 0, speed) { chunk ->
                if (stopped) return@generateWithCallback 0
                if (firstAudioMs == 0L) firstAudioMs = SystemClock.elapsedRealtime() - began
                envelope.add(chunk)
                var offset = 0
                while (offset < chunk.size && !stopped) {
                    val n = track.write(chunk, offset, chunk.size - offset, AudioTrack.WRITE_NON_BLOCKING)
                    if (n < 0) { stopped = true; break }
                    if (n == 0) Thread.sleep(5) else offset += n
                }
                written += chunk.size
                if (stopped) 0 else 1
            }
            val synthMs = SystemClock.elapsedRealtime() - began
            // A stream track starts playing only when its buffer is full: a phrase shorter than the buffer («Кому?») never sounded
            // and the wait below never ended. The rest of the buffer is filled with silence, and the wait has a limit.
            val pad = FloatArray(track.bufferSizeInFrames)
            var padded = 0
            val padUntil = SystemClock.elapsedRealtime() + 1000
            while (!stopped && padded < pad.size && SystemClock.elapsedRealtime() < padUntil) {
                val n = track.write(pad, padded, pad.size - padded, AudioTrack.WRITE_NON_BLOCKING)
                if (n < 0) break
                if (n == 0) Thread.sleep(5) else padded += n
            }
            val deadline = SystemClock.elapsedRealtime() + written * 1000 / rate + 2000
            while (!stopped && (track.playbackHeadPosition.toLong() and 0xffffffffL) < written && SystemClock.elapsedRealtime() < deadline) Thread.sleep(20)
            return SpeechTiming(synthMs = synthMs, audioMs = written * 1000 / rate, firstAudioMs = firstAudioMs)
        } finally {
            ticking.set(false)
            ticker.join(200)
            mouth.level = 0f
            runCatching { track.pause(); track.flush() }
            track.release()
        }
    }

    override fun measure(text: String, speed: Float): SpeechTiming {
        val began = SystemClock.elapsedRealtime()
        val audio = tts.generate(text, 0, speed)
        val synthMs = SystemClock.elapsedRealtime() - began
        return SpeechTiming(synthMs = synthMs, audioMs = audio.samples.size * 1000L / audio.sampleRate)
    }

    override fun stop() { stopped = true }

    override fun close() { tts.release() }

    private companion object {
        /** Changes when the files in the assets change (e.g. int8 voices replaced by fp32), so the copies in files/ are made again. */
        const val VERSION = "fp32-1"
    }
}

/** Copies a folder of the assets to the app's files once (a marker file with the version tells it is complete and current). */
object AssetFiles {
    private const val MARKER = ".copied"

    fun ensure(context: Context, asset: String, target: File, version: String): File {
        val marker = File(target, MARKER)
        if (marker.exists() && marker.readText() == version) return target
        target.deleteRecursively()
        copy(context, asset, target)
        marker.writeText(version)
        return target
    }

    private fun copy(context: Context, asset: String, to: File) {
        val children = context.assets.list(asset) ?: emptyArray()
        if (children.isEmpty()) {
            to.parentFile?.mkdirs()
            context.assets.open(asset).use { input -> to.outputStream().use { input.copyTo(it) } }
        } else {
            to.mkdirs()
            children.forEach { copy(context, "$asset/$it", File(to, it)) }
        }
    }
}
