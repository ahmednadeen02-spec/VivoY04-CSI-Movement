package com.example.csimovement

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import kotlin.math.*
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
    var status by remember { mutableStateOf("READY") }
    var source by remember { mutableStateOf("No signal") }
    val samples = remember { mutableStateListOf<Float>() }
    var job by remember { mutableStateOf<Job?>(null) }

    fun addSample(v: Float) {
        samples.add(v)
        if (samples.size > 140) samples.removeAt(0)
        if (samples.size >= 20) {
            val recent = samples.takeLast(12)
            val baseline = samples.takeLast(min(70, samples.size))
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
        samples.clear()
        score = 0f
        movement = false
        running = true
        source = "Demo CSI signal"
        status = "CALIBRATING..."
        samples.clear()
        job = CoroutineScope(Dispatchers.Default).launch {
            while (isActive) {
                val moving = (System.currentTimeMillis() / 4000L) % 2L == 1L
                val t = System.currentTimeMillis() / 1000.0
                val wave = sin(t * 2.4) * 0.04
                val v = if (moving) {
                    1f + Random.nextFloat() * 1.8f + (Random.nextFloat() - .5f) * .7f + wave.toFloat()
                } else {
                    1f + (Random.nextFloat() - .5f) * .08f + wave.toFloat()
                }
                withContext(Dispatchers.Main) { addSample(v) }
                delay(80)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        running = false
        movement = false
        score = 0f
        status = "STOPPED"
        source = "No signal"
    }

    fun startUdp() {
        job?.cancel()
        samples.clear()
        score = 0f
        movement = false
        running = true
        source = "ESP32 CSI / UDP 5005"
        status = "LISTENING"
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
                            withContext(Dispatchers.Main) { nums.take(30).forEach(::addSample) }
                        }
                    }
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    running = false
                    status = "UDP ERROR"
                }
            }
        }
    }

    DisposableEffect(Unit) { onDispose { job?.cancel() } }

    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Vivo Y04 CSI", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("Wi-Fi human movement radar", style = MaterialTheme.typography.bodyMedium)

                Card(
                    Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (movement) Color(0xFFFFE5E5) else Color(0xFFEAF7EE)
                    )
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (movement) "● MOVEMENT" else "● STILL",
                                fontWeight = FontWeight.Bold,
                                color = if (movement) Color(0xFFC62828) else Color(0xFF2E7D32)
                            )
                            Spacer(Modifier.weight(1f))
                            Text("${(score * 100).toInt()}%", fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.height(5.dp))
                        Text(if (movement) "Body movement detected" else "No significant movement")
                        Text(source, style = MaterialTheme.typography.bodySmall)
                    }
                }

                Text("LIVE BODY-WAVE VIEW", fontWeight = FontWeight.Bold)
                Card(Modifier.fillMaxWidth().height(235.dp), shape = RoundedCornerShape(20.dp)) {
                    Canvas(Modifier.fillMaxSize().padding(8.dp)) {
                        val w = size.width
                        val h = size.height
                        val cx = w * 0.78f
                        val cy = h * 0.48f

                        for (r in listOf(34f, 62f, 90f)) {
                            drawArc(
                                Color(0xFF6A5ACD),
                                -55f, 110f, false,
                                Offset(cx - r, cy - r),
                                androidx.compose.ui.geometry.Size(r * 2, r * 2),
                                style = Stroke(width = 5f)
                            )
                        }

                        val headX = w * 0.30f
                        val headY = h * 0.25f
                        drawCircle(Color(0xFF424242), 20f, Offset(headX, headY))
                        drawLine(Color(0xFF424242), Offset(headX, h * 0.36f), Offset(headX, h * 0.70f), 14f)
                        drawLine(Color(0xFF424242), Offset(headX, h * 0.45f), Offset(w * 0.17f, h * 0.58f), 10f)
                        drawLine(Color(0xFF424242), Offset(headX, h * 0.45f), Offset(w * 0.43f, h * 0.58f), 10f)
                        drawLine(Color(0xFF424242), Offset(headX, h * 0.70f), Offset(w * 0.20f, h * 0.90f), 12f)
                        drawLine(Color(0xFF424242), Offset(headX, h * 0.70f), Offset(w * 0.40f, h * 0.90f), 12f)

                        if (movement) {
                            val glow = 24f + score * 55f
                            drawCircle(Color(0x33FF3D00), glow, Offset(headX, h * 0.58f))
                        }

                        if (samples.size > 1) {
                            val minV = samples.minOrNull() ?: 0f
                            val maxV = max(minV + .01f, samples.maxOrNull() ?: 1f)
                            val path = Path()
                            val step = w / (samples.size - 1)
                            for (i in samples.indices) {
                                val y = h - 8f - ((samples[i] - minV) / (maxV - minV) * h * 0.24f)
                                val x = i * step
                                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                            }
                            drawPath(path, Color(0xFF3949AB), style = Stroke(width = 3f))
                        }
                    }
                }

                Text("MOVEMENT INTENSITY", fontWeight = FontWeight.Bold)
                LinearProgressIndicator(
                    progress = { score },
                    modifier = Modifier.fillMaxWidth().height(12.dp),
                )

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(onClick = { startTest() }, enabled = !running, modifier = Modifier.weight(1f)) {
                        Text("Test Demo")
                    }
                    Button(onClick = { startUdp() }, enabled = !running, modifier = Modifier.weight(1f)) {
                        Text("Real CSI")
                    }
                    OutlinedButton(onClick = { stop() }, enabled = running, modifier = Modifier.weight(.75f)) {
                        Text("Stop")
                    }
                }

                Text("ESP32 → Wi-Fi → UDP 5005 → Vivo Y04", style = MaterialTheme.typography.bodySmall)
                Text("Real CSI requires an ESP32 CSI-capable sender on the same Wi-Fi network.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
