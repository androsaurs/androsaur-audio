package dev.hram.androsaur.audio

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.json.JSONObject

/** Voice settings and checks: pick the engine, say a phrase, watch the mouth, compare engines on the same ten phrases. */
class SpeechActivity : ComponentActivity() {
    private var service by mutableStateOf<AudioService?>(null)
    private var bound = false
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) { service = (binder as AudioService.LocalBinder).service }
        override fun onServiceDisconnected(name: ComponentName?) { service = null }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xff24654a), background = Color(0xfff5f3ec))) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    SpeechScreen(service) { finish() }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        try { startForegroundService(Intent(this, AudioService::class.java)) } catch (_: RuntimeException) { }
        if (!bound) bound = bindService(Intent(this, AudioService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        if (bound) unbindService(connection)
        bound = false; service = null
        super.onStop()
    }
}

private val sample = "Привет! Я Андрозавр, ваш домашний помощник."

private fun hint(id: String) = when (id) {
    "piper:denis" -> "мужской · лицензия CC0"
    "piper:dmitri" -> "мужской · лицензия CC0"
    "piper:irina" -> "женский · лицензия неизвестна"
    "piper:ruslan" -> "мужской · только некоммерческое использование"
    "system" -> "системная служба речи Android"
    else -> if (id.startsWith("system:")) "служба речи ${id.removePrefix("system:")}" else ""
}

@Composable
private fun SpeechScreen(service: AudioService?, close: () -> Unit) {
    if (service == null) { Column(Modifier.padding(28.dp).statusBarsPadding()) { Text("Служба звука не запущена…") }; return }
    val speech = service.speech
    var status by remember { mutableStateOf(speech.status()) }
    var bench by remember { mutableStateOf(speech.benchJson()) }
    var mouth by remember { mutableFloatStateOf(0f) }
    var text by remember { mutableStateOf(sample) }
    var speed by remember { mutableFloatStateOf(speech.speed) }
    var play by remember { mutableStateOf(false) }
    var opened by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf("") }
    LaunchedEffect(speech) {
        var tick = 0
        while (true) {
            mouth = speech.mouth.level
            if (tick % 5 == 0) { status = speech.status(); bench = speech.benchJson() }
            tick++
            delay(50)
        }
    }
    val current = status.optString("speaker")
    val state = status.optString("state")
    val benchRunning = bench.optBoolean("running")
    fun run(block: () -> String?) { Thread { message = block()?.let { "Ошибка: $it" } ?: "" }.start() }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 20.dp).statusBarsPadding().navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = close) { Text("Назад") }
            Text("Голос", style = MaterialTheme.typography.headlineMedium)
        }

        Text("Движок речи", style = MaterialTheme.typography.titleMedium)
        Text("${current} · ${stateText(state)}" + (if (state == "ready") " · загрузка ${status.optLong("loadMs")} мс" else "") +
            (status.optString("error").takeIf { it.isNotEmpty() }?.let { "\n$it" } ?: ""), fontSize = 14.sp)
        val available = status.optJSONArray("available")
        for (i in 0 until (available?.length() ?: 0)) {
            val id = available!!.getString(i)
            val label = "$id\n${hint(id)}"
            if (id == current) Button(onClick = {}, modifier = Modifier.fillMaxWidth()) { Text(label, fontSize = 14.sp) }
            else OutlinedButton(onClick = { run { speech.configure(id, null, null) } }, modifier = Modifier.fillMaxWidth()) { Text(label, fontSize = 14.sp) }
        }

        Text("Скорость речи: %.2f".format(speed), style = MaterialTheme.typography.titleMedium)
        Slider(value = speed, onValueChange = { speed = it }, valueRange = SpeechController.MIN_SPEED..SpeechController.MAX_SPEED,
            onValueChangeFinished = { run { speech.configure(null, speed, null) } })

        if (current.startsWith("piper:")) {
            Text("Потоки процессора: ${status.optInt("threads")} (меняет загрузку голоса)", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (n in 1..4) {
                    if (n == status.optInt("threads")) Button(onClick = {}, modifier = Modifier.weight(1f)) { Text("$n") }
                    else OutlinedButton(onClick = { run { speech.configure(null, null, n) } }, modifier = Modifier.weight(1f)) { Text("$n") }
                }
            }
        }

        Text("Проверка", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(value = text, onValueChange = { text = it.take(SpeechController.MAX_CHARS) }, modifier = Modifier.fillMaxWidth(), minLines = 2)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { run { speech.speak(text, speed) } }, enabled = state == "ready", modifier = Modifier.weight(1f)) { Text("Сказать") }
            OutlinedButton(onClick = { speech.stop() }, modifier = Modifier.weight(1f)) { Text("Стоп") }
        }
        if (message.isNotEmpty()) Text(message, color = Color(0xff9a4b00), fontSize = 14.sp)
        MouthMeter(mouth, status.optBoolean("speaking"))
        Text(buildString {
            append(if (status.optBoolean("speaking")) "Говорит" else "Молчит")
            if (status.optBoolean("micMuted")) append(" · микрофон не слышит движок wake-word")
            if (status.optBoolean("approximateMouth") && status.optBoolean("speaking")) append(" · рот условный: системная речь не отдаёт звук")
        }, fontSize = 14.sp)
        status.optJSONObject("last")?.let { last ->
            Text("Последняя фраза: ${last.optString("speaker")} · до первого звука ${last.optLong("firstAudioMs")} мс · звук ${last.optLong("audioMs")} мс", fontSize = 13.sp)
        }

        Text("Сравнение движков", style = MaterialTheme.typography.titleMedium)
        Text("Одни и те же десять фраз. Скорость: время на звук делится на длину звука (меньше 1 — быстрее, чем говорит). Режим «вслух» ещё проигрывает фразы, чтобы сравнить на слух.", fontSize = 13.sp)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Switch(checked = play, onCheckedChange = { play = it })
            Text("Говорить вслух", fontSize = 14.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { run { speech.benchStart(listOf(current), play) } }, enabled = !benchRunning && state == "ready", modifier = Modifier.weight(1f)) { Text("Этот движок", fontSize = 13.sp) }
            Button(onClick = { run { speech.benchStart(null, play) } }, enabled = !benchRunning, modifier = Modifier.weight(1f)) { Text("Все движки", fontSize = 13.sp) }
        }
        if (benchRunning) {
            Text("Идёт замер: ${bench.optString("progress")}", fontSize = 14.sp)
            OutlinedButton(onClick = { speech.benchCancel() }, modifier = Modifier.fillMaxWidth()) { Text("Остановить замер") }
        }
        val engines = bench.optJSONArray("engines")
        for (i in 0 until (engines?.length() ?: 0)) {
            val e = engines!!.getJSONObject(i)
            BenchCard(e, opened == e.optString("speaker")) { opened = if (opened == e.optString("speaker")) null else e.optString("speaker") }
        }
        Spacer(Modifier.height(24.dp))
    }
}

private fun stateText(state: String) = when (state) { "ready" -> "готов"; "loading" -> "загружается…"; "failed" -> "не загрузился"; else -> state }

@Composable
private fun MouthMeter(level: Float, speaking: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Canvas(Modifier.size(width = 120.dp, height = 70.dp).clip(RoundedCornerShape(12.dp)).background(Color.Black)) {
            val w = size.width * 0.55f
            val h = (size.height * 0.08f + size.height * 0.62f * level)
            drawRoundRect(Color.White, Offset((size.width - w) / 2, (size.height - h) / 2), Size(w, h), cornerRadius = androidx.compose.ui.geometry.CornerRadius(h / 2, h / 2))
        }
        LinearProgressIndicator(progress = { level }, modifier = Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)), gapSize = 0.dp, drawStopIndicator = {},
            color = if (speaking) Color(0xff24654a) else Color(0xffb9b5a6))
    }
}

@Composable
private fun BenchCard(e: JSONObject, open: Boolean, toggle: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xffe9e5d8)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedButton(onClick = toggle, modifier = Modifier.fillMaxWidth()) { Text(e.optString("speaker") + if (open) "  ▲" else "  ▼") }
        val error = e.optString("error")
        if (error.isNotEmpty()) { Text("Ошибка: $error", color = Color(0xff9a4b00), fontSize = 13.sp); return@Column }
        Text("скорость (rtf) ${e.optDouble("rtf")} · худшая фраза ${e.optDouble("worstRtf")}", fontSize = 14.sp)
        Text("загрузка ${e.optLong("loadMs")} мс · прогрев ${e.optLong("warmupMs")} мс · сделано за ${e.optLong("totalSynthMs")} мс из ${e.optLong("totalAudioMs")} мс звука", fontSize = 13.sp)
        if (e.optBoolean("played")) Text("до первого звука, худшая фраза: ${e.optLong("worstFirstAudioMs")} мс", fontSize = 13.sp)
        if (open) {
            val rows = e.optJSONArray("rows")
            for (i in 0 until (rows?.length() ?: 0)) {
                val r = rows!!.getJSONObject(i)
                Text("${i + 1}. ${r.optString("phrase")}", fontSize = 12.sp)
                Text("   ${r.optLong("synthMs")} мс на ${r.optLong("audioMs")} мс звука · rtf ${r.optDouble("rtf")}" + (if (e.optBoolean("played")) " · первый звук ${r.optLong("firstAudioMs")} мс" else ""),
                    fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}
