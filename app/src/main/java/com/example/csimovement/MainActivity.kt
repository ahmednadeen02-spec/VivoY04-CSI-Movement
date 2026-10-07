package com.example.csimovement

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { CsiApp() }
    }
}

@Composable
fun CsiApp() {
    var running by remember { mutableStateOf(false) }
    var movement by remember { mutableStateOf(false) }
    var score by remember { mutableStateOf(0f) }
    var status by remember { mutableStateOf("Ready") }
    val samples = remember { mutableStateListOf<Float>() }
    var job by remember { mutableStateOf<Job?>(null) }

    fun addSample(v: Float) {
        samples.add(v)
        if (samples.size > 120) samples.removeAt(0)
        if (samples.size >= 20) {
            val recent = samples.takeLast(12)
            val baseline = samples.takeLast(min(60, samples.size))
            val base = baseline.average().toFloat()
            val mad = max(0.01f, baseline.map { abs(it - base) }.average().toFloat())
            val dev = recent.map { abs(it - base) }.average().toFloat()
            score = min(1f, dev / (mad * 4f))
            movement = score > 0.10f
            status = if (movement) "MOVEMENT DETECTED" else "STILL"
        }
    }

    fun startTest() {
        job?.cancel()
        running = true
        status = "Test stream running"
        job = CoroutineScope(Dispatchers.Default).launch {
            while (isActive) {
                val moving = (System.currentTimeMillis() / 4000L) % 2L == 1L
                val v = if (moving) {
                    1f + Random.nextFloat() * 1.8f + (Random.nextFloat() - .5f) * .7f
                } else {
                    1f + (Random.nextFloat() - .5f) * .08f
                }
                withContext(Dispatchers.Main) { addSample(v) }
                delay(100)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        running = false
        status = "Stopped"
    }

    fun startUdp() {
        job?.cancel()
        running = true
        status = "Listening UDP :5005"
        job = CoroutineScope(Dispatchers.IO).launch {
            try {
                DatagramSocket(5005).use { socket ->
                    val buf = ByteArray(8192)
                    while (isActive) {
                        val packet = DatagramPacket(buf, buf.size)
                        socket.receive(packet)
                        val text = String(packet.data, 0, packet.length)
                        val nums = Regex("-?\\d+(?:\\.\\d+)?").findAll(text)
                            .mapNotNull { it.value.toFloatOrNull() }.toList()
                        if (nums.isNotEmpty()) {
                            withContext(Dispatchers.Main) { nums.take(20).forEach(::addSample) }
                        }
                    }
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) { status = "UDP stopped/error" }
            }
        }
    }

    DisposableEffect(Unit) { onDispose { job?.cancel() } }

    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Vivo Y04 CSI Movement", style = MaterialTheme.typography.headlineSmall)
                Text("Wi-Fi CSI signal movement monitor")
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(status, style = MaterialTheme.typography.titleMedium)
                        Text("Intensity: ${(score * 100).toInt()}%")
                        Text(if (movement) "Movement detected" else "No significant movement")
                    }
                }
                Canvas(Modifier.fillMaxWidth().height(180.dp)) {
                    if (samples.size > 1) {
                        val minV = samples.minOrNull() ?: 0f
                        val maxV = max(minV + .01f, samples.maxOrNull() ?: 1f)
                        val step = size.width / (samples.size - 1)
                        for (i in 1 until samples.size) {
                            val y1 = size.height - ((samples[i-1] - minV) / (maxV-minV) * size.height)
                            val y2 = size.height - ((samples[i] - minV) / (maxV-minV) * size.height)
                            drawLine(Color.Blue, Offset((i-1)*step, y1), Offset(i*step, y2), 3f)
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { startTest() }, enabled = !running) { Text("Test Stream") }
                    Button(onClick = { startUdp() }, enabled = !running) { Text("Start UDP") }
                    OutlinedButton(onClick = { stop() }, enabled = running) { Text("Stop") }
                }
                Text("UDP port: 5005")
                Text("For real CSI, use an ESP32 CSI sender on the same Wi-Fi network.")
            }
        }
    }
}
