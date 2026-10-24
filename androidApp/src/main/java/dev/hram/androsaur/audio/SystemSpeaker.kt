package dev.hram.androsaur.audio

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import dev.hram.androsaur.audio.speech.SpeechTiming
import dev.hram.androsaur.audio.speech.WavInfo
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Android's speech service: the default one, or one chosen by package (RHVoice, Google). It plays the sound itself and
 * does not give it to us, so the mouth is only a pulse while it talks.
 */
class SystemSpeaker(private val context: Context, private val enginePackage: String?) : Speaker {
    override val id = if (enginePackage == null) "system" else "system:$enginePackage"
    override val name: String
    override val loadMs: Long
    private val tts: TextToSpeech
    @Volatile private var stopped = false
    @Volatile private var current: Utterance? = null

    private class Utterance(val id: String) {
        val started = CountDownLatch(1)
        val finished = CountDownLatch(1)
        @Volatile var startedAt = 0L
        @Volatile var failed = false
    }

    init {
        val began = SystemClock.elapsedRealtime()
        val ready = CountDownLatch(1)
        var status = TextToSpeech.ERROR
        tts = if (enginePackage == null) TextToSpeech(context) { status = it; ready.countDown() } else TextToSpeech(context, { status = it; ready.countDown() }, enginePackage)
        if (!ready.await(15, TimeUnit.SECONDS) || status != TextToSpeech.SUCCESS) { tts.shutdown(); throw IllegalStateException("tts_init_failed") }
        val language = tts.setLanguage(Locale("ru", "RU"))
        if (language == TextToSpeech.LANG_MISSING_DATA || language == TextToSpeech.LANG_NOT_SUPPORTED) { tts.shutdown(); throw IllegalStateException("russian_not_available") }
        name = "Система · ${tts.defaultEngine}"
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) { current?.takeIf { it.id == utteranceId }?.let { it.startedAt = SystemClock.elapsedRealtime(); it.started.countDown() } }
            override fun onDone(utteranceId: String) { current?.takeIf { it.id == utteranceId }?.let { it.started.countDown(); it.finished.countDown() } }
            @Deprecated("Deprecated in Java") override fun onError(utteranceId: String) { fail(utteranceId) }
            override fun onError(utteranceId: String, errorCode: Int) { fail(utteranceId) }
            override fun onStop(utteranceId: String, interrupted: Boolean) { current?.takeIf { it.id == utteranceId }?.let { it.started.countDown(); it.finished.countDown() } }
            private fun fail(utteranceId: String) { current?.takeIf { it.id == utteranceId }?.let { it.failed = true; it.started.countDown(); it.finished.countDown() } }
        })
        loadMs = SystemClock.elapsedRealtime() - began
    }

    override fun speak(text: String, speed: Float, mouth: Mouth): SpeechTiming {
        stopped = false
        mouth.approximate = true
        tts.setSpeechRate(speed)
        val utterance = Utterance(UUID.randomUUID().toString()).also { current = it }
        val began = SystemClock.elapsedRealtime()
        if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), utterance.id) != TextToSpeech.SUCCESS) throw IllegalStateException("tts_speak_refused")
        utterance.started.await(15, TimeUnit.SECONDS)
        val startedAt = utterance.startedAt
        // A pulse of about four syllables a second while the engine talks.
        while (!stopped && !utterance.finished.await(40, TimeUnit.MILLISECONDS)) {
            mouth.level = (0.15 + 0.85 * abs(sin((SystemClock.elapsedRealtime() - began) / 1000.0 * 2 * PI * 2.2))).toFloat()
        }
        val finishedAt = SystemClock.elapsedRealtime()
        mouth.level = 0f
        if (stopped) tts.stop()
        check(!utterance.failed) { "tts_failed" }
        val audioMs = if (startedAt == 0L) 0 else finishedAt - startedAt
        return SpeechTiming(synthMs = finishedAt - began, audioMs = audioMs, firstAudioMs = if (startedAt == 0L) 0 else startedAt - began)
    }

    override fun measure(text: String, speed: Float): SpeechTiming {
        tts.setSpeechRate(speed)
        val file = File(context.cacheDir, "tts-measure.wav").also { it.delete() }
        val utterance = Utterance(UUID.randomUUID().toString()).also { current = it }
        val began = SystemClock.elapsedRealtime()
        if (tts.synthesizeToFile(text, Bundle(), file, utterance.id) != TextToSpeech.SUCCESS) throw IllegalStateException("tts_synthesis_refused")
        if (!utterance.finished.await(60, TimeUnit.SECONDS) || utterance.failed) throw IllegalStateException("tts_synthesis_failed")
        val synthMs = SystemClock.elapsedRealtime() - began
        val header = file.inputStream().use { input -> ByteArray(44).also { input.read(it) } }
        val audioMs = WavInfo.durationMs(header, file.length()) ?: 0
        file.delete()
        return SpeechTiming(synthMs = synthMs, audioMs = audioMs)
    }

    override fun stop() { stopped = true; runCatching { tts.stop() } }

    override fun close() { tts.shutdown() }
}
