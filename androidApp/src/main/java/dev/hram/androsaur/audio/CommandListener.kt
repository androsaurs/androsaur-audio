package dev.hram.androsaur.audio

import android.os.SystemClock
import dev.hram.androsaur.audio.command.Command
import dev.hram.androsaur.audio.command.CommandParser
import dev.hram.androsaur.audio.command.Declension
import org.json.JSONObject
import org.vosk.Recognizer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * What the assistant does after the wake word: for a few seconds it listens for a command («передай жене…»), with its own speech
 * recognizer (the wake engine rests meanwhile). A command is turned into action here: the assistant repeats whom the note is for
 * and starts recording it. If only the verb was heard, it asks «Кому?» and listens once more.
 */
class CommandListener(
    private val recognizer: (grammarWords: List<String>) -> Recognizer,
    private val microphone: () -> MicrophoneListener?,
    private val recipients: RecipientBook,
    private val speech: SpeechController,
    private val notes: NoteController,
    /** The name of the person the camera sees now (from the main app), or null. */
    private val person: () -> String?,
    /** The permanent id of that person, or null. */
    private val personId: () -> String?,
    private val record: (String, Array<Pair<String, String>>) -> Unit,
) {
    @Volatile var listening = false; private set
    @Volatile var enabled = true

    /** Opens the listening window after a wake word. Does nothing while one is open or a note is being made. */
    fun open() {
        if (!enabled || listening) return
        val mic = microphone() ?: return
        if (recipients.all().isEmpty()) return
        listening = true
        Thread({ listen(mic) }, "command").start()
    }

    /** Runs a command given as text, as if it was heard (for tests and for other modules that already have text). Returns what was understood. */
    fun run(text: String): JSONObject {
        val command = CommandParser(recipients.all()).parse(text)
        record("command_heard", arrayOf("text" to text.take(120), "understood" to (command?.recipient?.id ?: if (command != null) "no_recipient" else "none")))
        val result = JSONObject().put("understood", command != null)
        command?.recipient?.let { result.put("recipient", it.id).put("addressee", it.addressee) }
        if (command?.recipient != null) Thread({ carryOut(command) }, "command-run").start()
        return result
    }

    private fun listen(mic: MicrophoneListener) {
        // The recognizer knows only the command words and the recipients in every case, so it cannot turn «передай» into «передвижения».
        val words = (CommandParser.vocabulary + recipients.all().flatMap { r -> (listOf(r.id) + r.forms).flatMap { Declension.forms(it) } }).distinct()
        val rec = try { recognizer(words) } catch (e: Exception) { record("command_failed", arrayOf("reason" to "${e.javaClass.simpleName}: ${e.message}".take(120))); listening = false; return }
        val frames = LinkedBlockingQueue<ShortArray>(64)
        var verbSeen = false
        val gain = dev.hram.androsaur.audio.notes.AutoGain()
        var peak = -120.0
        var finals = 0
        var partial = ""
        var deadline = SystemClock.elapsedRealtime() + WINDOW_MS
        record("command_listening", emptyArray())
        mic.commandTap = { frame -> frames.offer(frame) }
        try {
            while (SystemClock.elapsedRealtime() < deadline) {
                val raw = frames.poll(100, TimeUnit.MILLISECONDS) ?: continue
                peak = maxOf(peak, dev.hram.androsaur.audio.level.AudioLevel.dbfs(raw))
                val frame = gain.apply(raw)
                val final = rec.acceptWaveForm(frame, frame.size)
                if (!final) { JSONObject(rec.partialResult).optString("partial").takeIf { it.isNotEmpty() }?.let { partial = it }; continue }
                finals++
                val text = JSONObject(rec.result).optString("text")
                if (text.isEmpty()) continue
                val command = CommandParser(recipients.all()).parse(text, verbSeen)
                record("command_heard", arrayOf("text" to text.take(120), "understood" to (command?.recipient?.id ?: if (command != null) "no_recipient" else "none")))
                when {
                    command == null -> {}
                    command.recipient != null -> { mic.commandTap = null; carryOut(command); return }
                    !verbSeen -> {
                        verbSeen = true
                        mic.commandTap = null
                        speech.speakAndWait("Кому?")
                        frames.clear()
                        mic.commandTap = { f -> frames.offer(f) }
                        deadline = SystemClock.elapsedRealtime() + WINDOW_MS
                    }
                }
            }
            // What the recognizer made of the window, so a failure can be told from silence: the loudest frame, how many utterances ended, the last partial text.
            record("command_timeout", arrayOf("peakDbfs" to Math.round(peak).toString(), "utterances" to finals.toString(), "lastPartial" to partial.take(80)))
        } catch (e: Exception) {
            record("command_failed", arrayOf("reason" to "${e.javaClass.simpleName}: ${e.message}".take(120)))
        } finally {
            mic.commandTap = null
            rec.close()
            listening = false
        }
    }

    /** Says whom the note is for and starts recording it. */
    private fun carryOut(command: Command) {
        val recipient = command.recipient ?: return
        // Told in words, step by step: whom the note is for, that it is time to talk, and in the end whether it was kept.
        speech.speakAndWait((command.echo() ?: "Записка") + ". Говорите.")
        val problem = notes.startRecording(recipient.addressee, person(), null, beep = false, toId = recipient.personId, fromId = personId()) { reason ->
            speech.speakAndWait(when (reason) {
                null -> "Записано."
                "no_speech" -> "Ничего не слышно. Записка не сохранена."
                "too_short" -> "Слишком коротко. Записка не сохранена."
                else -> "Не получилось записать."
            })
        }
        record(if (problem == null) "command_note" else "command_failed", arrayOf("to" to recipient.addressee) + (problem?.let { arrayOf("reason" to it) } ?: emptyArray()))
        listening = false
    }

    companion object { const val WINDOW_MS = 7_000L }
}
