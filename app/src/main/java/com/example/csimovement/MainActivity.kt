package com.example.csimovement

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Panel)
    ) {
        Canvas(Modifier.fillMaxSize().padding(6.dp)) {
            val w = size.width
            val h = size.height
            val bodyX = w * 0.48f
            val bodyY = h * 0.50f
            val maxR = min(w, h) * 0.44f

            drawRect(Color(0xFF071A2B), size = Size(w, h))

            for (i in 1..5) {
                val r = maxR * i / 5f
                drawCircle(Color(0xFF16466F), r, Offset(bodyX, bodyY), style = Stroke(width = 1.2f))
            }
            for (i in -3..3) {
                val x = w * (i + 3) / 6f
                drawLine(Color(0xFF123653), Offset(x, 0f), Offset(x, h), 1f)
            }
            for (i in -2..2) {
                val y = h * (i + 2) / 4f
                drawLine(Color(0xFF123653), Offset(0f, y), Offset(w, y), 1f)
            }

            val waveColor = if (movement) Red else Cyan
            for (i in 1..4) {
                val r = maxR * (0.25f + i * 0.14f)
                drawArc(waveColor.copy(alpha = 0.65f), -55f, 110f, false,
                    Offset(bodyX - r, bodyY - r), Size(r * 2, r * 2), style = Stroke(width = 2.5f))
            }

            val glow = 35f + score * 65f
            if (movement) {
                drawCircle(Color(0x44FF4250), glow, Offset(bodyX, bodyY))
                drawCircle(Color(0x2255FFCC), glow * 1.5f, Offset(bodyX, bodyY))
            }

            val head = Offset(bodyX, bodyY - h * .19f)
            drawCircle(Color(0xFF0B0F16), h * .055f, head)
            drawCircle(waveColor, h * .055f, head, style = Stroke(width = 3f))

            val shoulderY = bodyY - h * .08f
            val hipY = bodyY + h * .14f
            drawLine(waveColor, Offset(bodyX, shoulderY), Offset(bodyX, hipY), h * .045f, cap = StrokeCap.Round)
            drawLine(waveColor, Offset(bodyX, shoulderY), Offset(bodyX - w*.10f, bodyY + h*.03f), h*.032f, cap = StrokeCap.Round)
            drawLine(waveColor, Offset(bodyX, shoulderY), Offset(bodyX + w*.10f, bodyY + h*.03f), h*.032f, cap = StrokeCap.Round)
            drawLine(waveColor, Offset(bodyX, hipY), Offset(bodyX - w*.075f, bodyY + h*.29f), h*.04f, cap = StrokeCap.Round)
            drawLine(waveColor, Offset(bodyX, hipY), Offset(bodyX + w*.075f, bodyY + h*.29f), h*.04f, cap = StrokeCap.Round)

            if (movement) {
                val pulse = 1f + 0.12f * sin(System.currentTimeMillis() / 180.0).toFloat()
                drawCircle(Red, 7f * pulse, Offset(bodyX, bodyY + h*.31f))
            }

            TextOnCanvas("DETECTION ZONE", Offset(14f, 22f), TextMain)
            TextOnCanvas("3.0m x 3.0m", Offset(14f, 42f), TextDim)
            TextOnCanvas(if (movement) "● PERSON" else "● SCANNING", Offset(w - 105f, 22f), if (movement) Red else Green)
            TextOnCanvas("$signalDbm dBm", Offset(w - 105f, 42f), TextDim)

            if (samples.size > 1) {
                val chartTop = h * .80f
                val minV = samples.minOrNull() ?: 0f
                val maxV = max(minV + .01f, samples.maxOrNull() ?: 1f)
                val path = Path()
                val step = w / (samples.size - 1)
                for (i in samples.indices) {
                    val y = chartTop + h*.16f - ((samples[i] - minV)/(maxV-minV) * h*.14f)
                    val x = i * step
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, Green, style = Stroke(width = 2.5f))
            }
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
