package com.doseward.spike

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Scaffold { innerPadding ->
                    SpikeScreen(Modifier.padding(innerPadding))
                }
            }
        }
    }
}

@Composable
fun SpikeScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var mode by remember { mutableStateOf(AlarmMode.ALARM_CLOCK) }
    var minutes by remember { mutableStateOf("2") }
    var canExact by remember { mutableStateOf(AlarmScheduler.canExact(context)) }
    var log by remember { mutableStateOf(SpikeLog.read(context)) }

    fun refresh() {
        canExact = AlarmScheduler.canExact(context)
        log = SpikeLog.read(context)
    }

    Column(
        modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Exact alarms: " + if (canExact) "allowed" else "NOT allowed")
        if (!canExact && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Button(onClick = { openExactAlarmSettings(context) }) { Text("Allow exact alarms") }
        }

        AlarmMode.entries.forEach { option ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = mode == option, onClick = { mode = option })
                Text(option.label)
            }
        }

        OutlinedTextField(
            value = minutes,
            onValueChange = { minutes = it.filter(Char::isDigit) },
            label = { Text("Ring in (minutes)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val delay = (minutes.toLongOrNull() ?: 1L) * 60_000L
                AlarmScheduler.arm(context, System.currentTimeMillis() + delay, mode)
                refresh()
            }) { Text("Arm") }
            OutlinedButton(onClick = { refresh() }) { Text("Refresh") }
            OutlinedButton(onClick = { SpikeLog.clear(context); refresh() }) { Text("Clear") }
        }

        Text(log, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
    }
}

@RequiresApi(Build.VERSION_CODES.S)
private fun openExactAlarmSettings(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, "package:${context.packageName}".toUri())
    )
}
