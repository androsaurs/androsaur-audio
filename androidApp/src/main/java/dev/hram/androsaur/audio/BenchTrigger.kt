package dev.hram.androsaur.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log

/**
 * Diagnostics without a person: `adb shell am broadcast -a dev.hram.androsaur.audio.SPEECH_BENCH` runs the speech benchmark
 * and prints one line per phrase to logcat (tag `SpeechBench`). Only the system permission DUMP may send it (adb shell has it,
 * ordinary apps do not), and nothing secret is printed: the phrases are the fixed benchmark phrases. See scripts/speech-bench.sh.
 * `SPEECH_SAY` says a phrase through the real speech path (the one answers use) `repeat` times with `gap_ms` of silence before
 * each, and prints firstAudioMs / synthMs / audioMs per run (extras: `text`, `repeat`, `gap_ms`, `tag`, `threads`, `engine`: the speaker is saved as the setting): this is how the effect of
 * an idle processor (the first phrase after a pause) is measured, which the back-to-back benchmark hides.
 * `SPEECH_LISTEN` says a phrase and then listens for 3 s exactly like a command that waits for an answer, and prints the recording
 * gate (noise floor, threshold, peak; tag `RecGate`): in a quiet room the floor shows whether the phone hears its own voice after
 * it has spoken (extras: `text` (blank: no phrase, the control), `repeat`, `engine`, `tag`, `during`: a phrase the phone says 1.2 s into the listening as a stand-in for a person). Stay silent while it runs.
 * Extras of SPEECH_BENCH: `threads` (int, saved as the setting), `engine` (id), `play` (bool: also say the phrases), `tag` (label for the lines).
 */
object BenchTrigger {
    const val ACTION = "dev.hram.androsaur.audio.SPEECH_BENCH"
    const val ACTION_SAY = "dev.hram.androsaur.audio.SPEECH_SAY"
    const val ACTION_LISTEN = "dev.hram.androsaur.audio.SPEECH_LISTEN"
    private const val TAG = "SpeechBench"

    fun register(context: Context, speech: SpeechController, notes: NoteController) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val threads = intent.getIntExtra("threads", 0)
                val engine = intent.getStringExtra("engine")
                val play = intent.getBooleanExtra("play", false)
                val tag = intent.getStringExtra("tag") ?: "run"
                Thread({ run(speech, tag, threads, engine, play) }, "speech-bench-trigger").start()
            }
        }
        context.registerReceiver(receiver, IntentFilter(ACTION), "android.permission.DUMP", null, Context.RECEIVER_EXPORTED)
        val sayReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val text = intent.getStringExtra("text") ?: "Проверка скорости речи, это тестовая фраза."
                val times = intent.getIntExtra("repeat", 1).coerceIn(1, 20)
                val gap = intent.getLongExtra("gap_ms", 0L).coerceIn(0L, 120_000L)
                val tag = intent.getStringExtra("tag") ?: "say"
                val threads = intent.getIntExtra("threads", 0)
                val engine = intent.getStringExtra("engine")
                Thread({
                    if (engine != null) speech.configure(engine, null, null)?.let { Log.i(TAG, "$tag ERROR $it"); return@Thread }
                    if (threads > 0) speech.configure(null, null, threads)?.let { Log.i(TAG, "$tag ERROR $it"); return@Thread }
                    while (speech.status().optString("state") != "ready") Thread.sleep(200)
                    for (n in 1..times) {
                        Thread.sleep(gap)
                        val err = speech.speakAndWait(text)
                        if (err != null) { Log.i(TAG, "$tag ERROR $err"); return@Thread }
                        val last = speech.status().optJSONObject("last")
                        Log.i(TAG, "$tag SAY $n speaker=${last?.optString("speaker")} gap=$gap chars=${text.length} first=${last?.optLong("firstAudioMs")} synth=${last?.optLong("synthMs")} audio=${last?.optLong("audioMs")}")
                    }
                    Log.i(TAG, "$tag DONE")
                }, "speech-say-trigger").start()
            }
        }
        val listenReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val text = intent.getStringExtra("text") ?: "Что вспомнить?"
                val times = intent.getIntExtra("repeat", 1).coerceIn(1, 20)
                val engine = intent.getStringExtra("engine")
                val tag = intent.getStringExtra("tag") ?: "listen"
                val during = intent.getStringExtra("during") ?: ""
                Thread({
                    if (engine != null) speech.configure(engine, null, null)?.let { Log.i(TAG, "$tag ERROR $it"); return@Thread }
                    while (speech.status().optString("state") != "ready") Thread.sleep(200)
                    for (n in 1..times) {
                        if (text.isNotBlank()) speech.speakAndWait(text)?.let { Log.i(TAG, "$tag ERROR $it"); return@Thread }
                        else Thread.sleep(2000)
                        val done = java.util.concurrent.CountDownLatch(1)
                        val err = notes.startRecording(".", null, 3, beep = false, sink = { _, _ -> }) { done.countDown() }
                        if (err != null) { Log.i(TAG, "$tag ERROR $err"); return@Thread }
                        // A stand-in for the person: the phone says this while it listens (its own speaker is loud in its microphone).
                        if (during.isNotBlank()) { Thread.sleep(1200); speech.speak(during) }
                        done.await(15, java.util.concurrent.TimeUnit.SECONDS)
                        Log.i(TAG, "$tag LISTEN $n speaker=${speech.status().optString("speaker")}")
                        Thread.sleep(1500)
                    }
                    Log.i(TAG, "$tag DONE")
                }, "speech-listen-trigger").start()
            }
        }
        context.registerReceiver(listenReceiver, IntentFilter(ACTION_LISTEN), "android.permission.DUMP", null, Context.RECEIVER_EXPORTED)
        context.registerReceiver(sayReceiver, IntentFilter(ACTION_SAY), "android.permission.DUMP", null, Context.RECEIVER_EXPORTED)
    }

    private fun run(speech: SpeechController, tag: String, threads: Int, engine: String?, play: Boolean) {
        if (threads > 0) speech.configure(null, null, threads)?.let { Log.i(TAG, "$tag ERROR $it"); return }
        speech.benchStart(engine?.let { listOf(it) }, play)?.let { Log.i(TAG, "$tag ERROR $it"); return }
        Thread.sleep(500)
        var json = speech.benchJson()
        while (json.getBoolean("running")) { Thread.sleep(500); json = speech.benchJson() }
        val engines = json.getJSONArray("engines")
        for (i in 0 until engines.length()) {
            val e = engines.getJSONObject(i)
            if (e.has("error")) { Log.i(TAG, "$tag ${e.getString("speaker")} ERROR ${e.getString("error")}"); continue }
            Log.i(TAG, "$tag ${e.getString("speaker")} SUMMARY threads=${json.getInt("threads")} play=$play load=${e.getLong("loadMs")} warmup=${e.getLong("warmupMs")} rtf=${e.getDouble("rtf")} worst=${e.getDouble("worstRtf")} synth=${e.getLong("totalSynthMs")} audio=${e.getLong("totalAudioMs")}")
            val rows = e.getJSONArray("rows")
            for (r in 0 until rows.length()) {
                val row = rows.getJSONObject(r)
                Log.i(TAG, "$tag ${e.getString("speaker")} ROW ${r + 1} chars=${row.getInt("chars")} synth=${row.getLong("synthMs")} audio=${row.getLong("audioMs")} rtf=${row.getDouble("rtf")} first=${row.optLong("firstAudioMs", -1)}")
            }
        }
        Log.i(TAG, "$tag DONE")
    }
}
