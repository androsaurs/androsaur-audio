package dev.hram.androsaur.audio

import android.os.SystemClock
import dev.hram.androsaur.audio.command.Command
import dev.hram.androsaur.audio.command.CommandParser
import dev.hram.androsaur.audio.command.Declension
import dev.hram.androsaur.audio.command.QuickCommand
import dev.hram.androsaur.audio.command.QuickCommands
import dev.hram.androsaur.audio.command.Room
import dev.hram.androsaur.audio.command.RoomParser
import dev.hram.androsaur.audio.wake.PhraseMatcher
import dev.hram.androsaur.audio.wake.WakePhrase
import org.json.JSONObject
import org.vosk.Recognizer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * What the assistant does after the wake word: for a few seconds it listens for a command («передай жене…»), with its own speech
 * recognizer (the wake engine rests meanwhile). A command is turned into action here: the assistant repeats whom the note is for
 * and starts recording it. If only the verb was heard, it asks «Кому?» and listens once more. The wake phrase heard again in the
 * window («железяка!» once more, as children do when the answer is slow) is a new call: the window closes and [onRewake] starts over.
 */
class CommandListener(
    private val recognizer: (grammarWords: List<String>) -> Recognizer,
    private val microphone: () -> MicrophoneListener?,
    private val recipients: RecipientBook,
    private val rooms: RoomBook,
    private val announcements: AnnouncementBox,
    private val speech: SpeechController,
    private val notes: NoteController,
    private val ringer: Ringer,
    /** The name of the person the camera sees now (from the main app), or null. */
    private val person: () -> String?,
    /** The permanent id of that person, or null. */
    private val personId: () -> String?,
    private val record: (String, Array<Pair<String, String>>) -> Unit,
    /** The wake phrase now, in [WakePhrase] syntax. */
    private val wakePhrase: () -> String = { WakePhrase.DEFAULT },
    /** Called with what was heard when the wake phrase came again in the window, after the window closed. */
    private val onRewake: (String) -> Unit = {},
) {
    @Volatile var listening = false; private set
    @Volatile var enabled = true
    @Volatile private var repeatedWakeUntil = 0L

    /** Opens the listening window, or extends it after another acoustic wake. Disabled while a note is being made. */
    fun open() {
        if (!enabled) return
        if (listening) { repeatedWakeUntil = SystemClock.elapsedRealtime() + WINDOW_MS; return }
        val mic = microphone() ?: return
        repeatedWakeUntil = 0L
        listening = true
        Thread({ listen(mic) }, "command").start()
    }

    /** Runs a command given as text, as if it was heard (for tests and for other modules that already have text). Returns what was understood. */
    fun run(text: String): JSONObject {
        QuickCommands.parse(text)?.let { quick ->
            record("command_heard", arrayOf("text" to text.take(120), "understood" to name(quick)))
            Thread({ quick(quick) }, "command-run").start()
            return JSONObject().put("understood", true).put("command", name(quick))
        }
        RoomParser(rooms.all()).parse(text)?.let { room ->
            record("command_heard", arrayOf("text" to text.take(120), "understood" to "room:${room.id}"))
            Thread({ carryOutRoom(room) }, "command-run").start()
            return JSONObject().put("understood", true).put("room", room.id)
        }
        val command = CommandParser(recipients.all()).parse(text)
        record("command_heard", arrayOf("text" to text.take(120), "understood" to (command?.recipient?.id ?: if (command != null) "no_recipient" else "none")))
        val result = JSONObject().put("understood", command != null)
        command?.recipient?.let { result.put("recipient", it.id).put("addressee", it.addressee) }
        if (command?.recipient != null) Thread({ carryOut(command) }, "command-run").start()
        return result
    }

    private fun listen(mic: MicrophoneListener) {
        // The recognizer knows only the command words and the recipients in every case, so it cannot turn «передай» into «передвижения».
        val phrase = wakePhrase()
        val wake = PhraseMatcher(phrase)
        val wakeWords = WakePhrase.grammarPhrases(phrase).flatMap { it.split(' ') }
        val words = (CommandParser.vocabulary + QuickCommands.vocabulary + RoomParser.vocabulary(rooms.all()) + wakeWords + recipients.all().flatMap { r -> (listOf(r.id) + r.forms).flatMap { Declension.forms(it) } }).distinct()
        val rec = try { recognizer(words) } catch (e: Exception) { record("command_failed", arrayOf("reason" to "${e.javaClass.simpleName}: ${e.message}".take(120))); listening = false; return }
        val frames = LinkedBlockingQueue<ShortArray>(64)
        var verbSeen = false
        val gain = dev.hram.androsaur.audio.notes.AutoGain()
        var peak = -120.0
        var finals = 0
        var partial = ""
        var deadline = SystemClock.elapsedRealtime() + WINDOW_MS
        var rewake: String? = null
        record("command_listening", emptyArray())
        mic.commandTap = { frame -> frames.offer(frame) }
        try {
            while (SystemClock.elapsedRealtime() < maxOf(deadline, repeatedWakeUntil)) {
                val raw = frames.poll(100, TimeUnit.MILLISECONDS) ?: continue
                peak = maxOf(peak, dev.hram.androsaur.audio.level.AudioLevel.dbfs(raw))
                val frame = gain.apply(raw)
                val final = rec.acceptWaveForm(frame, frame.size)
                val heard = if (final) JSONObject(rec.result).optString("text") else JSONObject(rec.partialResult).optString("partial")
                if (wake.matches(heard)) {
                    record("command_rewake", arrayOf("text" to heard.take(120)))
                    rewake = heard
                    break
                }
                if (!final) { heard.takeIf { it.isNotEmpty() }?.let { partial = it }; continue }
                finals++
                val text = heard
                if (text.isEmpty()) continue
                val quick = if (verbSeen) null else QuickCommands.parse(text)
                if (quick != null) {
                    record("command_heard", arrayOf("text" to text.take(120), "understood" to name(quick)))
                    mic.commandTap = null
                    quick(quick)
                    return
                }
                RoomParser(rooms.all()).parse(text, verbSeen)?.let { room ->
                    record("command_heard", arrayOf("text" to text.take(120), "understood" to "room:${room.id}"))
                    mic.commandTap = null
                    carryOutRoom(room)
                    return
                }
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
            if (rewake == null) record("command_timeout", arrayOf("peakDbfs" to Math.round(peak).toString(), "utterances" to finals.toString(), "lastPartial" to partial.take(80)))
        } catch (e: Exception) {
            record("command_failed", arrayOf("reason" to "${e.javaClass.simpleName}: ${e.message}".take(120)))
        } finally {
            mic.commandTap = null
            rec.close()
            listening = false
        }
        rewake?.let(onRewake)
    }

    /**
     * A short command. The audio module does what is its own (stop the bell, the speech, a note); the rest (the time, the weather,
     * the timer) is the main app's: it gets a `command` event and answers in words.
     */
    private fun quick(command: QuickCommand) {
        when (command) {
            QuickCommand.Stop -> {
                val rang = ringer.stop("stop")
                speech.stop(); notes.stopPlaying()
                record("command", arrayOf("intent" to "stop", "wasRinging" to rang.toString()))
            }
            QuickCommand.RepeatNote -> {
                val note = notes.latestFor(personId(), person())
                when {
                    personId() == null && person() == null -> speech.speakAndWait("Не вижу, кто спрашивает.")
                    note == null -> speech.speakAndWait("Записок для тебя нет.")
                    else -> {
                        // The person's own voice, even when the note was delivered as text.
                        val problem = notes.play(note.id, null, true, original = true)
                        record("command", arrayOf("intent" to "repeat_note", "note" to note.id) + (problem?.let { arrayOf("reason" to it) } ?: emptyArray()))
                    }
                }
            }
            QuickCommand.Time -> record("command", arrayOf("intent" to "time"))
            is QuickCommand.Weather -> record("command", arrayOf("intent" to if (command.tomorrow) "weather_tomorrow" else "weather"))
            is QuickCommand.Horoscope -> record("command", arrayOf("intent" to "horoscope", "tomorrow" to command.tomorrow.toString(), "personId" to personId().orEmpty()))
            is QuickCommand.Timer -> record("command", arrayOf("intent" to "timer", "seconds" to command.seconds.toString()))
            QuickCommand.TimerLeft -> record("command", arrayOf("intent" to "timer_left"))
            is QuickCommand.Calc -> record("command", arrayOf("intent" to "calc", "expression" to command.expression))
            is QuickCommand.Glasses -> record("command", arrayOf("intent" to "character_glasses", "wear" to command.wear.toString(), "personId" to personId().orEmpty()))
            is QuickCommand.Accessory -> record("command", arrayOf("intent" to "character_accessory", "accessory" to command.accessory, "wear" to command.wear.toString(), "personId" to personId().orEmpty()))
        }
        listening = false
    }

    private fun name(q: QuickCommand): String = when (q) {
        QuickCommand.Time -> "time"; is QuickCommand.Weather -> if (q.tomorrow) "weather_tomorrow" else "weather"
        is QuickCommand.Timer -> "timer"; QuickCommand.TimerLeft -> "timer_left"; QuickCommand.Stop -> "stop"
        QuickCommand.RepeatNote -> "repeat_note"; is QuickCommand.Calc -> "calc"
        is QuickCommand.Glasses -> "character_glasses"
        is QuickCommand.Accessory -> "character_accessory"
        is QuickCommand.Horoscope -> "horoscope"
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

    /**
     * «Передай в детскую»: says where it goes and records what is said. The sound is not a note: it waits in [announcements] and the
     * main app takes it to the hub, which writes it out and has the speaker say it; the main app tells the result in words.
     */
    private fun carryOutRoom(room: Room) {
        val who = person(); val whoId = personId()
        speech.speakAndWait(if (room.id == RoomParser.ALL_ID) "На все колонки. Говорите." else "На колонку: ${room.name}. Говорите.")
        var announceId: String? = null
        val problem = notes.startRecording(room.name, who, null, beep = false, fromId = whoId, sink = { wav, _ -> announceId = announcements.add(wav) }) { reason ->
            if (reason == null) record("command", arrayOf("intent" to "announce", "roomId" to room.id, "room" to room.name, "announceId" to announceId.orEmpty(), "personId" to whoId.orEmpty(), "person" to who.orEmpty()))
            else speech.speakAndWait(when (reason) {
                "no_speech" -> "Ничего не слышно. Сообщение не передано."
                "too_short" -> "Слишком коротко. Сообщение не передано."
                else -> "Не получилось записать."
            })
        }
        record(if (problem == null) "command_announce" else "command_failed", arrayOf("to" to room.name) + (problem?.let { arrayOf("reason" to it) } ?: emptyArray()))
        listening = false
    }

    companion object { const val WINDOW_MS = 7_000L }
}
