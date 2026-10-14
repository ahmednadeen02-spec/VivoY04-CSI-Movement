package com.example.csimovement

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.*
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

private val Bg = Color(0xFF020B18)
private val Panel = Color(0xFF061528)
private val Panel2 = Color(0xFF0A1C32)
private val Cyan = Color(0xFF29A8FF)
private val Green = Color(0xFF22E39A)
private val Red = Color(0xFFFF5B61)
private val TextMain = Color(0xFFEAF5FF)
private val TextDim = Color(0xFF88A6C3)

@Composable
fun CsiApp() {
    var running by remember { mutableStateOf(false) }
    var movement by remember { mutableStateOf(false) }
    var score by remember { mutableStateOf(0f) }
    var status by remember { mutableStateOf("SYSTEM READY") }
    var source by remember { mutableStateOf("Waiting for CSI signal") }
    var signalDbm by remember { mutableStateOf(-42) }
    val samples = remember { mutableStateListOf<Float>() }
    var job by remember { mutableStateOf<Job?>(null) }

    fun addSample(v: Float) {
        samples.add(v)
        if (samples.size > 150) samples.removeAt(0)
        if (samples.size >= 20) {
            val recent = samples.takeLast(12)
            val baseline = samples.takeLast(min(70, samples.size))
            val base = baseline.average().toFloat()
            val mad = max(0.01f, baseline.map { abs(it - base) }.average().toFloat())
            val dev = recent.map { abs(it - base) }.average().toFloat()
            score = min(1f, dev / (mad * 4f))
            movement = score > 0.10f
            status = if (movement) "MOVEMENT" else "STILL"
        }
    }

    fun startDemo() {
        job?.cancel()
        samples.clear()
        score = 0f
        movement = false
        running = true
        source = "Demo CSI signal"
        status = "CALIBRATING"
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
                withContext(Dispatchers.Main) {
                    addSample(v)
                    signalDbm = -42 + Random.nextInt(-3, 4)
                }
                delay(80)
            }
        }
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
                        val packetText = String(packet.data, 0, packet.length)
                        val nums = Regex("-?\\d+(?:\\.\\d+)?").findAll(packetText)
                            .mapNotNull { it.value.toFloatOrNull() }.toList()
                        if (nums.isNotEmpty()) {
                            withContext(Dispatchers.Main) {
                                nums.take(30).forEach(::addSample)
                                signalDbm = -42
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    running = false
                    status = "UDP ERROR"
                    source = "Could not open UDP 5005"
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        running = false
        movement = false
        score = 0f
        status = "SYSTEM READY"
        source = "Waiting for CSI signal"
        samples.clear()
    }

    DisposableEffect(Unit) { onDispose { job?.cancel() } }

    MaterialTheme {
        Surface(Modifier.fillMaxSize(), color = Bg) {
            Column(
                Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Header(running)

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    StatusPanel(Modifier.weight(1.3f), movement, score, status)
                    IntensityPanel(Modifier.weight(1f), score)
                    SignalPanel(Modifier.weight(.85f), signalDbm)
                }

                Text("LIVE WI-FI DETECTION ZONE", color = TextMain, fontWeight = FontWeight.Bold)

                RadarPanel(
                    Modifier.fillMaxWidth().weight(1f).heightIn(min = 250.dp),
                    movement, score, samples, signalDbm
                )

                Text("MOVEMENT INTENSITY", color = TextMain, fontWeight = FontWeight.Bold)
                LinearProgressIndicator(
                    progress = { score },
                    modifier = Modifier.fillMaxWidth().height(10.dp),
                    color = if (movement) Red else Green,
                    trackColor = Panel2
                )

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    ActionButton("START", running, Modifier.weight(1f)) { startUdp() }
                    ActionButton("STOP", !running, Modifier.weight(1f)) { stop() }
                    ActionButton("TEST DEMO", running, Modifier.weight(1.2f)) { startDemo() }
                }

                Text("ESP32 → Wi-Fi → UDP 5005 → Vivo Y04", color = TextDim, style = MaterialTheme.typography.bodySmall)
                Text("Real CSI needs an ESP32 CSI-capable sender on the same Wi-Fi network.", color = TextDim, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun Header(running: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Wi-Fi Motion Detector", color = TextMain, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("CSI Based Human Detection", color = TextDim)
        }
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = Panel,
            border = androidx.compose.foundation.BorderStroke(1.dp, if (running) Green else Cyan)
        ) {
            Row(Modifier.padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("●", color = if (running) Green else TextDim, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(5.dp))
                Column {
                    Text(if (running) "REAL CSI MODE" else "SYSTEM READY", color = if (running) Green else TextMain, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                    Text("UDP : 5005", color = TextDim, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun StatusPanel(modifier: Modifier, movement: Boolean, score: Float, status: String) {
    DarkCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("●", color = if (movement) Red else Green, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(4.dp))
            Column {
                Text(status, color = if (movement) Red else Green, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                Text(if (movement) "PERSON DETECTED" else "NO MOVEMENT", color = TextDim, style = MaterialTheme.typography.labelSmall)
            }
        }
        Text("${(score * 100).toInt()}", color = TextMain, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun IntensityPanel(modifier: Modifier, score: Float) {
    DarkCard(modifier) {
        Text("Movement", color = TextDim, style = MaterialTheme.typography.labelSmall)
        Text("${(score * 100).toInt()}", color = TextMain, fontWeight = FontWeight.Bold)
        LinearProgressIndicator(
            progress = { score },
            modifier = Modifier.fillMaxWidth().height(7.dp),
            color = Green,
            trackColor = Panel2
        )
    }
}

@Composable
private fun SignalPanel(modifier: Modifier, signalDbm: Int) {
    DarkCard(modifier) {
        Text("Wi-Fi Signal", color = TextDim, style = MaterialTheme.typography.labelSmall)
        Text("$signalDbm dBm", color = TextMain, fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.Bottom) {
            repeat(4) { i ->
                Box(
                    Modifier.padding(end = 2.dp).width(4.dp).height((7 + i * 5).dp)
                        .background(Green, RoundedCornerShape(2.dp))
                )
            }
        }
    }
}

@Composable
private fun DarkCard(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier,
        color = Panel,
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF16466F))
    ) {
        Column(Modifier.padding(9.dp), content = content)
    }
}

@Composable
private fun RadarPanel(
    modifier: Modifier,
    movement: Boolean,
    score: Float,
    samples: List<Float>,
    signalDbm: Int
) {
    val radarAnimation = rememberInfiniteTransition(label = "radar-scan")
    val scanPhase by radarAnimation.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "scan-phase"
    )

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Panel)
    ) {
        Canvas(Modifier.fillMaxSize().padding(6.dp)) {
            val w = size.width
            val h = size.height
            val bodyX = w * 0.50f
            val bodyY = h * 0.43f
            val maxR = min(w * 0.40f, h * 0.34f)
            val waveColor = if (movement) Red else Cyan

            drawRect(Color(0xFF04101F), size = Size(w, h))

            // CCTV-like grid and corner markers
            for (i in 0..8) {
                val x = w * i / 8f
                drawLine(Color(0xFF102B43), Offset(x, 0f), Offset(x, h), 1f)
            }
            for (i in 0..6) {
                val y = h * i / 6f
                drawLine(Color(0xFF102B43), Offset(0f, y), Offset(w, y), 1f)
            }
            val corner = 18f
            val inset = 10f
            val marker = Color(0xFF2A91C9)
            drawLine(marker, Offset(inset, inset), Offset(inset + corner, inset), 2.5f)
            drawLine(marker, Offset(inset, inset), Offset(inset, inset + corner), 2.5f)
            drawLine(marker, Offset(w - inset, inset), Offset(w - inset - corner, inset), 2.5f)
            drawLine(marker, Offset(w - inset, inset), Offset(w - inset, inset + corner), 2.5f)
            drawLine(marker, Offset(inset, h - inset), Offset(inset + corner, h - inset), 2.5f)
            drawLine(marker, Offset(inset, h - inset), Offset(inset, h - inset - corner), 2.5f)
            drawLine(marker, Offset(w - inset, h - inset), Offset(w - inset - corner, h - inset), 2.5f)
            drawLine(marker, Offset(w - inset, h - inset), Offset(w - inset, h - inset - corner), 2.5f)

            // Radar rings and animated sweep
            for (i in 1..5) {
                drawCircle(
                    Color(0xFF1C5275).copy(alpha = 0.85f - i * 0.09f),
                    maxR * i / 5f,
                    Offset(bodyX, bodyY),
                    style = Stroke(width = 1.25f)
                )
            }
            val sweepAngle = scanPhase * 360f
            drawArc(
                brush = Brush.sweepGradient(
                    listOf(Color.Transparent, Color(0x4430C8FF), Color(0x8830C8FF)),
                    center = Offset(bodyX, bodyY)
                ),
                startAngle = sweepAngle,
                sweepAngle = 48f,
                useCenter = true,
                topLeft = Offset(bodyX - maxR, bodyY - maxR),
                size = Size(maxR * 2f, maxR * 2f)
            )
            val rayAngle = Math.toRadians(sweepAngle.toDouble())
            drawLine(
                Color(0xFF36D8FF).copy(alpha = 0.9f),
                Offset(bodyX, bodyY),
                Offset(bodyX + cos(rayAngle).toFloat() * maxR, bodyY + sin(rayAngle).toFloat() * maxR),
                2f
            )

            // CSI-derived heatmap estimate, not a camera image
            val heatAlpha = (0.10f + score.coerceIn(0f, 1f) * 0.42f).coerceIn(0.10f, 0.52f)
            val heatColor = if (movement) Color(0xFFFF3D5A) else Color(0xFF00D9FF)
            drawCircle(heatColor.copy(alpha = heatAlpha * 0.24f), maxR * 0.70f, Offset(bodyX, bodyY))
            drawCircle(heatColor.copy(alpha = heatAlpha * 0.34f), maxR * 0.47f, Offset(bodyX, bodyY))
            drawCircle(heatColor.copy(alpha = heatAlpha * 0.44f), maxR * 0.24f, Offset(bodyX, bodyY))

            // Stylized human-shaped estimate and tracking box
            val boxW = w * 0.20f
            val boxH = h * 0.40f
            val boxTop = bodyY - h * 0.24f
            drawRoundRect(
                color = waveColor.copy(alpha = if (movement) 0.95f else 0.52f),
                topLeft = Offset(bodyX - boxW / 2f, boxTop),
                size = Size(boxW, boxH),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(8f, 8f),
                style = Stroke(width = if (movement) 2.5f else 1.5f)
            )
            val head = Offset(bodyX, bodyY - h * 0.14f)
            val headR = h * 0.035f
            drawCircle(Color(0xFF06111E), headR, head)
            drawCircle(waveColor, headR, head, style = Stroke(width = 2.5f))
            val shoulderY = bodyY - h * 0.06f
            val hipY = bodyY + h * 0.10f
            drawLine(waveColor, Offset(bodyX, shoulderY), Offset(bodyX, hipY), h * 0.022f, cap = StrokeCap.Round)
            drawLine(waveColor, Offset(bodyX, shoulderY), Offset(bodyX - w * 0.065f, bodyY + h * 0.025f), h * 0.018f, cap = StrokeCap.Round)
            drawLine(waveColor, Offset(bodyX, shoulderY), Offset(bodyX + w * 0.065f, bodyY + h * 0.025f), h * 0.018f, cap = StrokeCap.Round)
            drawLine(waveColor, Offset(bodyX, hipY), Offset(bodyX - w * 0.055f, bodyY + h * 0.19f), h * 0.021f, cap = StrokeCap.Round)
            drawLine(waveColor, Offset(bodyX, hipY), Offset(bodyX + w * 0.055f, bodyY + h * 0.19f), h * 0.021f, cap = StrokeCap.Round)

            // Recent sample trail
            if (samples.size > 8) {
                val trailCount = min(10, samples.size)
                val sampleMin = samples.minOrNull() ?: 0f
                val sampleMax = max(sampleMin + 0.01f, samples.maxOrNull() ?: 1f)
                for (i in 0 until trailCount) {
                    val sample = samples[samples.size - trailCount + i]
                    val spread = ((sample - sampleMin) / (sampleMax - sampleMin)).coerceIn(0f, 1f)
                    val fraction = i / (trailCount - 1f)
                    val tx = bodyX - maxR * 0.75f + fraction * maxR * 1.5f
                    val ty = bodyY + maxR * 0.63f - spread * maxR * 0.38f
                    drawCircle(
                        Color(0xFF4CE6FF).copy(alpha = 0.18f + fraction * 0.72f),
                        2.5f + spread * 2f,
                        Offset(tx, ty)
                    )
                }
            }

            // Live CSI waveform strip
            val chartTop = h * 0.76f
            drawRoundRect(
                color = Color(0xAA061528),
                topLeft = Offset(8f, chartTop),
                size = Size(w - 16f, h * 0.18f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(8f, 8f)
            )
            if (samples.size > 1) {
                val visibleSamples = samples.takeLast(90)
                val minV = visibleSamples.minOrNull() ?: 0f
                val maxV = max(minV + 0.01f, visibleSamples.maxOrNull() ?: 1f)
                val path = Path()
                val graphWidth = w - 28f
                val step = graphWidth / (visibleSamples.size - 1).coerceAtLeast(1)
                for (i in visibleSamples.indices) {
                    val normalized = (visibleSamples[i] - minV) / (maxV - minV)
                    val y = chartTop + h * 0.155f - normalized * h * 0.12f
                    val x = 14f + i * step
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, Color(0xFF48E7FF), style = Stroke(width = 2f))
            }

            TextOnCanvas("LIVE CSI RADAR", Offset(14f, 23f), TextMain)
            TextOnCanvas("ZONE A / ESTIMATED MAP", Offset(14f, 41f), TextDim)
            TextOnCanvas(if (movement) "MOTION DETECTED" else "SCANNING", Offset(w - 144f, 23f), if (movement) Red else Green)
            TextOnCanvas("$signalDbm dBm", Offset(w - 90f, 41f), TextDim)
            TextOnCanvas("CSI SIGNAL TRACE", Offset(14f, chartTop + 14f), TextDim)
            TextOnCanvas(if (movement) "ACTIVITY \${(score * 100).toInt()}%" else "MONITORING", Offset(14f, h - 10f), if (movement) Red else Green)
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.TextOnCanvas(
    text: String,
    position: Offset,
    color: Color
) {
    drawContext.canvas.nativeCanvas.drawText(
        text, position.x, position.y,
        android.graphics.Paint().apply {
            this.color = color.toArgb()
            textSize = 13f
            isAntiAlias = true
            typeface = android.graphics.Typeface.create("sans", android.graphics.Typeface.BOLD)
        }
    )
}

@Composable
private fun ActionButton(label: String, disabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = !disabled,
        modifier = modifier.height(44.dp),
        shape = RoundedCornerShape(11.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (label == "STOP") Color(0xFF0D4F8B) else Color(0xFF0B66D6),
            contentColor = TextMain,
            disabledContainerColor = Color(0xFF0A2038),
            disabledContentColor = TextDim
        )
    ) {
        Text(label, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
    }
}
