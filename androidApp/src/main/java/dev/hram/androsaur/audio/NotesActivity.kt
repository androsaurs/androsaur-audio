package dev.hram.androsaur.audio

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

/** Voice notes: leave a note for a person (beep, then talk), see what waits, play or delete. */
class NotesActivity : ComponentActivity() {
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
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { NotesScreen(service) { finish() } }
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

@Composable
private fun NotesScreen(service: AudioService?, close: () -> Unit) {
    if (service == null) { Column(Modifier.padding(28.dp).statusBarsPadding()) { Text("Служба звука не запущена…") }; return }
    val notes = service.notes
    var to by remember { mutableStateOf("жена") }
    var from by remember { mutableStateOf("") }
    var status by remember { mutableStateOf(notes.status()) }
    var list by remember { mutableStateOf(JSONArray()) }
    var last by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        while (true) { status = notes.status(); list = notes.list(null, false); delay(400) }
    }
    val state = status.optString("state")
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Записки", style = MaterialTheme.typography.headlineSmall)
        Text("Нажмите «Записать», дождитесь сигнала и говорите. Запись закончится после паузы около двух секунд.", style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(to, { to = it }, label = { Text("Кому") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(from, { from = it }, label = { Text("От кого (произносится при воспроизведении)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        when (state) {
            "recording" -> Button(onClick = { notes.stopRecording() }, modifier = Modifier.fillMaxWidth()) { Text("Идёт запись… Остановить") }
            "playing" -> Button(onClick = { notes.stopPlaying() }, modifier = Modifier.fillMaxWidth()) { Text("Играет… Остановить") }
            else -> {
                Button(onClick = { last = notes.startRecording(to, from.ifBlank { null }, null)?.let { "Не получилось: $it" } ?: "" }, modifier = Modifier.fillMaxWidth()) { Text("Записать") }
                OutlinedButton(onClick = { last = notes.play(null, to, true)?.let { if (it == "no_notes") "Для этого человека нет новых записок" else "Не получилось: $it" } ?: "" },
                    modifier = Modifier.fillMaxWidth()) { Text("Проиграть новую для «$to»") }
            }
        }
        if (last.isNotEmpty()) Text(last, color = Color(0xff9a3412))
        Text("Записки", style = MaterialTheme.typography.titleMedium)
        if (list.length() == 0) Text("Пока ни одной.")
        for (i in 0 until list.length()) {
            val n = list.getJSONObject(i)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Для: ${n.getString("to")}" + n.optString("from").let { if (it.isEmpty()) "" else " · от: $it" })
                    Text("${n.getLong("durationMs") / 1000.0} с · " + if (n.getBoolean("heard")) "прослушана" else "ждёт", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(enabled = state == "idle", onClick = { last = notes.play(n.getString("id"), null, false)?.let { "Не получилось: $it" } ?: "" }) { Text("Играть") }
                        OutlinedButton(enabled = state == "idle", onClick = { notes.delete(n.getString("id")) }) { Text("Удалить") }
                    }
                }
            }
        }
        OutlinedButton(onClick = close, modifier = Modifier.fillMaxWidth()) { Text("Назад") }
    }
}
