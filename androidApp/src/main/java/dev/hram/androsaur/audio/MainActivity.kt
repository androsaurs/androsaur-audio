package dev.hram.androsaur.audio

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.net.NetworkInterface

class MainActivity : ComponentActivity() {
    private var status by mutableStateOf("Слушание остановлено")
    private var permissionRequested = false
    private var manuallyStopped = false
    private var bound = false
    private var service by mutableStateOf<AudioService?>(null)
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val bound = (binder as AudioService.LocalBinder).service
            service = bound
            status = bound.error?.let { "Ошибка: $it" } ?: "Слушаю"
        }
        override fun onServiceDisconnected(name: ComponentName?) { service = null; status = "Слушание остановлено" }
    }
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startListening()
        else status = "Разрешите доступ к микрофону"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xff24654a), background = Color(0xfff5f3ec))) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(28.dp).statusBarsPadding().navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                        Text("ANDROSAUR", fontSize = 14.sp)
                        Text("Звук", style = MaterialTheme.typography.headlineLarge)
                        Text("Каркас · wake-word «Привет, Петя» пока не подключён")
                        Text(status, style = MaterialTheme.typography.titleMedium)
                        EnginePicker(service)
                        Button(onClick = { requestStart() }, modifier = Modifier.fillMaxWidth()) { Text("Начать слушать") }
                        OutlinedButton(onClick = {
                            manuallyStopped = true
                            disconnect()
                            stopService(Intent(this@MainActivity, AudioService::class.java))
                            status = "Слушание остановлено"
                        }, modifier = Modifier.fillMaxWidth()) { Text("Остановить") }
                        Text("Адрес в этой сети", style = MaterialTheme.typography.titleMedium)
                        SelectionContainer { Text(address(), fontSize = 16.sp) }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (manuallyStopped) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startListening()
        else if (!permissionRequested) { permissionRequested = true; requestStart() }
    }

    private fun requestStart() {
        val requested = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) requested += Manifest.permission.POST_NOTIFICATIONS
        permissions.launch(requested.toTypedArray())
    }

    private fun startListening() {
        manuallyStopped = false
        try {
            startForegroundService(Intent(this, AudioService::class.java))
            if (!bound) bound = bindService(Intent(this, AudioService::class.java), connection, Context.BIND_AUTO_CREATE)
        } catch (_: IllegalStateException) {
            status = "Android отложил запуск. Откройте приложение снова"
        } catch (_: SecurityException) {
            status = "Разрешите приложению доступ к микрофону"
        }
    }

    private fun disconnect() {
        if (bound) unbindService(connection)
        bound = false
        service = null
    }

    override fun onStop() { disconnect(); super.onStop() }

    private fun address(): String {
        val ip = NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
            .firstOrNull { !it.isLoopbackAddress && it.hostAddress?.contains(':') == false }?.hostAddress ?: "IP-телефона"
        return "http://$ip:${PhoneServer.PORT}\nКод доступа (Bearer): ${AccessToken.get(this)}"
    }
}

@Composable
private fun EnginePicker(service: AudioService?) {
    if (service == null) return
    var summary by remember { mutableStateOf("") }
    var current by remember { mutableStateOf("") }
    LaunchedEffect(service) {
        while (true) {
            val engine = service.status().optJSONObject("engine")
            current = engine?.optString("id") ?: ""
            summary = engine?.let {
                "${it.optString("id")} · ${it.optString("state")} · ${it.optDouble("msPerFrame", 0.0)} мс на кадр ${it.optDouble("frameMs", 0.0)} мс"
            } ?: ""
            kotlinx.coroutines.delay(1000)
        }
    }
    Text("Движок wake-word", style = MaterialTheme.typography.titleMedium)
    Text(summary)
    service.availableEngines().forEach { id ->
        if (id == current) Button(onClick = {}, modifier = Modifier.fillMaxWidth()) { Text(id) }
        else OutlinedButton(onClick = { Thread { service.setEngine(id) }.start() }, modifier = Modifier.fillMaxWidth()) { Text(id) }
    }
}
