package com.example.csimovement

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.*
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
    var demoMode by remember { mutableStateOf(false) }
    var movement by remember { mutableStateOf(false) }
    var score by remember { mutableFloatStateOf(0f) }
    var status by remember { mutableStateOf("SYSTEM READY") }
    var demoPose by remember { mutableStateOf("STANDING") }
    var source by remember { mutableStateOf("Waiting for CSI signal") }
    var signalDbm by remember { mutableIntStateOf(-42) }
    val samples = remember { mutableStateListOf<Float>() }
    var job by remember { mutableStateOf<Job?>(null) }

    fun addSample(v: Float) {
        samples.add(v)
        if (samples.size > 180) samples.removeAt(0)
        if (samples.size >= 20) {
            val recent = samples.takeLast(10)
            val baseline = samples.dropLast(10).takeLast(60).ifEmpty { samples.take(10) }
            val base = baseline.average().toFloat()
            val noise = max(0.015f, baseline.map { abs(it - base) }.average().toFloat())
            val deviation = recent.map { abs(it - base) }.average().toFloat()
            score = (deviation / max(noise * 2.8f, 0.06f)).coerceIn(0f, 1f)
            movement = score > 0.22f
            status = if (movement) "MOVEMENT" else "STILL"
        }
    }

    fun startDemo() {
        job?.cancel()
        samples.clear()
        score = 0f
        movement = false
        demoMode = true
        running = true
        source = "Demo CSI signal (simulated)"
        status = "CALIBRATING"
        job = CoroutineScope(Dispatchers.Default).launch {
            val startAt = System.currentTimeMillis()
            while (isActive) {
                val elapsed = (System.currentTimeMillis() - startAt) % 12000L
                val pose = when (elapsed) { in 0L..2999L -> "STANDING"; in 3000L..5999L -> "WALKING"; in 6000L..8999L -> "SITTING"; else -> "WALKING" }
                val moving = pose != "STANDING"
                val t = System.currentTimeMillis() / 1000.0
                val wave = sin(t * 2.4) * 0.025
                val v = if (moving) {
                    1f + Random.nextFloat() * 0.65f + (Random.nextFloat() - .5f) * .35f + wave.toFloat()
                } else {
                    1f + (Random.nextFloat() - .5f) * .025f + wave.toFloat()
                }
                withContext(Dispatchers.Main) {
                    addSample(v)
                    demoPose = pose
                    status = if (demoMode) pose else status
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
        demoMode = false
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
        demoMode = false
        demoPose = "STANDING"
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
                    Modifier.fillMaxWidth().weight(1f).heightIn(min = 210.dp),
                    movement, score, samples, signalDbm, demoMode
                )
                SkeletonDemoPanel(Modifier.fillMaxWidth().height(205.dp), demoPose, demoMode)
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
                    ActionButton("TEST DEMO", false, Modifier.weight(1.2f)) { startDemo() }
                }
                Text("Source: $source", color = TextDim, style = MaterialTheme.typography.bodySmall)
                Text("ESP32 → Wi-Fi → UDP 5005 → Vivo Y04", color = TextDim, style = MaterialTheme.typography.bodySmall)
                Text("Demo is simulated. Real CSI needs compatible ESP32 CSI hardware on the same network.", color = TextDim, style = MaterialTheme.typography.bodySmall)
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
                    Text(if (running) "ACTIVE MODE" else "SYSTEM READY", color = if (running) Green else TextMain, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
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
                Text(if (movement) "ACTIVITY DETECTED" else "NO MOVEMENT", color = TextDim, style = MaterialTheme.typography.labelSmall)
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
        LinearProgressIndicator(progress = { score }, modifier = Modifier.fillMaxWidth().height(7.dp), color = Green, trackColor = Panel2)
    }
}

@Composable
private fun SignalPanel(modifier: Modifier, signalDbm: Int) {
    DarkCard(modifier) {
        Text("Wi-Fi Signal", color = TextDim, style = MaterialTheme.typography.labelSmall)
        Text("$signalDbm dBm", color = TextMain, fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.Bottom) {
            repeat(4) { i ->
                Box(Modifier.padding(end = 2.dp).width(4.dp).height((7 + i * 5).dp).background(Green, RoundedCornerShape(2.dp)))
            }
        }
    }
}

@Composable
private fun DarkCard(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier = modifier, color = Panel, shape = RoundedCornerShape(14.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF16466F))) {
        Column(Modifier.padding(9.dp), content = content)
    }
}

@Composable
private fun RadarPanel(
    modifier: Modifier,
    movement: Boolean,
    score: Float,
    samples: List<Float>,
    signalDbm: Int,
    demoMode: Boolean
) {
    val radarAnimation = rememberInfiniteTransition(label = "radar-scan")
    val scanPhase by radarAnimation.animateFloat(0f, 1f, infiniteRepeatable(tween(2600, easing = LinearEasing)), label = "scan-phase")
    val bodyAnimation = rememberInfiniteTransition(label = "body-motion")
    val sway by bodyAnimation.animateFloat(
        initialValue = -1f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(850, easing = FastOutSlowInEasing), repeatMode = RepeatMode.Reverse),
        label = "body-sway"
    )
    val step by bodyAnimation.animateFloat(
        initialValue = -1f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(420, easing = LinearEasing), repeatMode = RepeatMode.Reverse),
        label = "body-step"
    )
    val motionAmount = if (movement) (0.35f + score * 0.65f).coerceIn(0.35f, 1f) else 0.025f
    val bodyShift = if (movement) sway * 7f * motionAmount else 0f

    Card(modifier = modifier, shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Panel)) {
        Canvas(Modifier.fillMaxSize().padding(6.dp)) {
            val w = size.width
            val h = size.height
            val bodyX = w * 0.50f + bodyShift
            val bodyY = h * 0.42f
            val maxR = min(w * 0.40f, h * 0.34f)
            val waveColor = if (movement) Red else Cyan
            drawRect(Color(0xFF04101F), size = Size(w, h))
            for (i in 0..8) drawLine(Color(0xFF102B43), Offset(w * i / 8f, 0f), Offset(w * i / 8f, h), 1f)
            for (i in 0..6) drawLine(Color(0xFF102B43), Offset(0f, h * i / 6f), Offset(w, h * i / 6f), 1f)
            val corner = 18f; val inset = 10f; val marker = Color(0xFF2A91C9)
            drawLine(marker, Offset(inset,inset), Offset(inset+corner,inset), 2.5f); drawLine(marker, Offset(inset,inset), Offset(inset,inset+corner), 2.5f)
            drawLine(marker, Offset(w-inset,inset), Offset(w-inset-corner,inset), 2.5f); drawLine(marker, Offset(w-inset,inset), Offset(w-inset,inset+corner), 2.5f)
            drawLine(marker, Offset(inset,h-inset), Offset(inset+corner,h-inset), 2.5f); drawLine(marker, Offset(inset,h-inset), Offset(inset,h-inset-corner), 2.5f)
            drawLine(marker, Offset(w-inset,h-inset), Offset(w-inset-corner,h-inset), 2.5f); drawLine(marker, Offset(w-inset,h-inset), Offset(w-inset,h-inset-corner), 2.5f)
            // Layered sonar rings with a breathing pulse for a more cinematic CCTV display.
            for (i in 1..6) {
                val ringPulse = ((scanPhase + i * 0.13f) % 1f)
                val ringRadius = maxR * (0.18f + ringPulse * 0.82f)
                drawCircle(
                    Color(0xFF20CFFF).copy(alpha = (1f - ringPulse) * if (movement) 0.34f else 0.20f),
                    ringRadius,
                    Offset(bodyX, bodyY),
                    style = Stroke(if (i == 1) 2f else 1.15f)
                )
            }
            for (i in 1..5) drawCircle(Color(0xFF1C5275).copy(alpha = 0.70f - i * 0.07f), maxR * i / 5f, Offset(bodyX, bodyY), style = Stroke(1.1f))
            val sweepAngle = scanPhase * 360f
            drawArc(brush = Brush.sweepGradient(listOf(Color.Transparent, Color(0x4430C8FF), Color(0x8830C8FF)), center = Offset(bodyX, bodyY)), startAngle = sweepAngle, sweepAngle = 48f, useCenter = true, topLeft = Offset(bodyX-maxR, bodyY-maxR), size = Size(maxR*2f,maxR*2f))
            val rayAngle = Math.toRadians(sweepAngle.toDouble())
            drawLine(Color(0xFF36D8FF).copy(alpha = .9f), Offset(bodyX,bodyY), Offset(bodyX+cos(rayAngle).toFloat()*maxR,bodyY+sin(rayAngle).toFloat()*maxR), 2f)
            val heatAlpha = (0.10f + score.coerceIn(0f,1f)*0.42f).coerceIn(.10f,.52f)
            val heatColor = if (movement) Color(0xFFFF3D5A) else Color(0xFF00D9FF)
            drawCircle(heatColor.copy(alpha=heatAlpha*.16f),maxR*.70f,Offset(bodyX,bodyY)); drawCircle(heatColor.copy(alpha=heatAlpha*.25f),maxR*.47f,Offset(bodyX,bodyY)); drawCircle(heatColor.copy(alpha=heatAlpha*.34f),maxR*.24f,Offset(bodyX,bodyY))
            // Glowing target reticle and four tracking ticks around the detected zone.
            val reticleR = maxR * 0.30f
            drawCircle(heatColor.copy(alpha = if (movement) 0.65f else 0.35f), reticleR, Offset(bodyX, bodyY), style = Stroke(1.5f))
            for (tick in 0..3) {
                val a = Math.toRadians((scanPhase * 360f + tick * 90f).toDouble())
                val inner = reticleR + 5f
                val outer = inner + 9f
                drawLine(Color(0xFF75EDFF).copy(alpha = 0.85f), Offset(bodyX + cos(a).toFloat() * inner, bodyY + sin(a).toFloat() * inner), Offset(bodyX + cos(a).toFloat() * outer, bodyY + sin(a).toFloat() * outer), 2f)
            }
            // The body is a stylized visualization; movement data cannot reconstruct a camera-quality image.
            val bob = if (movement) step * 5f * motionAmount else sin(scanPhase * 6.28318f) * 0.6f
            val armSwing = if (movement) step * 0.45f * motionAmount else 0.02f
            val legSwing = if (movement) -step * 0.42f * motionAmount else 0.015f
            val boxW = w*.20f; val boxH = h*.40f; val boxTop = bodyY-h*.24f+bob
            drawRoundRect(waveColor.copy(alpha=if(movement) .98f else .52f),Offset(bodyX-boxW/2f,boxTop),Size(boxW,boxH),androidx.compose.ui.geometry.CornerRadius(8f,8f),style=Stroke(if(movement) 2.5f else 1.5f))
            val head = Offset(bodyX, bodyY-h*.14f+bob); val headR=h*.035f
            drawCircle(waveColor.copy(alpha = if (movement) 0.14f else 0.07f), headR * 2.3f, head)
            drawCircle(Color(0xFF06111E),headR,head); drawCircle(waveColor,headR,head,style=Stroke(2.5f))
            drawCircle(waveColor.copy(alpha = 0.75f), headR * 0.22f, head)
            val shoulderY=bodyY-h*.06f+bob; val hipY=bodyY+h*.10f+bob
            drawLine(waveColor,Offset(bodyX,shoulderY),Offset(bodyX,hipY),h*.022f,cap=StrokeCap.Round)
            drawLine(waveColor,Offset(bodyX,shoulderY),Offset(bodyX-w*.065f+armSwing*14f,bodyY+h*.025f+bob),h*.018f,cap=StrokeCap.Round)
            drawLine(waveColor,Offset(bodyX,shoulderY),Offset(bodyX+w*.065f-armSwing*14f,bodyY+h*.025f+bob),h*.018f,cap=StrokeCap.Round)
            drawLine(waveColor,Offset(bodyX,hipY),Offset(bodyX-w*.055f+legSwing*12f,bodyY+h*.19f+bob),h*.021f,cap=StrokeCap.Round)
            drawLine(waveColor,Offset(bodyX,hipY),Offset(bodyX+w*.055f-legSwing*12f,bodyY+h*.19f+bob),h*.021f,cap=StrokeCap.Round)
            if (samples.size > 8) {
                val trailCount=min(10,samples.size); val sampleMin=samples.minOrNull()?:0f; val sampleMax=max(sampleMin+.01f,samples.maxOrNull()?:1f)
                for(i in 0 until trailCount) { val sample=samples[samples.size-trailCount+i]; val spread=((sample-sampleMin)/(sampleMax-sampleMin)).coerceIn(0f,1f); val fraction=i/(trailCount-1f); drawCircle(Color(0xFF4CE6FF).copy(alpha=.18f+fraction*.72f),2.5f+spread*2f,Offset(bodyX-maxR*.75f+fraction*maxR*1.5f,bodyY+maxR*.63f-spread*maxR*.38f)) }
            }
            val chartTop=h*.76f
            drawRoundRect(Color(0xAA061528),Offset(8f,chartTop),Size(w-16f,h*.18f),androidx.compose.ui.geometry.CornerRadius(8f,8f))
            if(samples.size>1) { val visible=samples.takeLast(90); val minV=visible.minOrNull()?:0f; val maxV=max(minV+.01f,visible.maxOrNull()?:1f); val path=Path(); val gw=w-28f; val st=gw/(visible.size-1).coerceAtLeast(1); for(i in visible.indices){val normalized=(visible[i]-minV)/(maxV-minV);val y=chartTop+h*.155f-normalized*h*.12f;val x=14f+i*st;if(i==0)path.moveTo(x,y)else path.lineTo(x,y)};drawPath(path,Color(0xFF48E7FF),style=Stroke(2f)) }
            TextOnCanvas(if (demoMode) "DEMO CSI RADAR" else "LIVE CSI RADAR", Offset(14f,23f), TextMain)
            TextOnCanvas("ZONE A / ESTIMATED MAP",Offset(14f,41f),TextDim)
            TextOnCanvas(if(movement)"MOTION DETECTED"else"SCANNING",Offset(w-144f,23f),if(movement)Red else Green)
            TextOnCanvas("$signalDbm dBm",Offset(w-90f,41f),TextDim)
            TextOnCanvas("CSI SIGNAL TRACE",Offset(14f,chartTop+14f),TextDim)
            TextOnCanvas(if(movement)"ACTIVITY ${(score*100).toInt()}%"else"MONITORING",Offset(14f,h-10f),if(movement)Red else Green)
        }
    }
}

@Composable
private fun SkeletonDemoPanel(modifier: Modifier, pose: String, demoMode: Boolean) {
    val transition = rememberInfiniteTransition(label = "skeleton-demo")
    val swing by transition.animateFloat(-1f, 1f, infiniteRepeatable(tween(420, easing = LinearEasing), repeatMode = RepeatMode.Reverse), label = "skeleton-swing")
    val cyan = Color(0xFF35DFFF)
    Card(modifier = modifier, shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Panel), border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF16466F))) {
        Row(Modifier.fillMaxSize().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.weight(1f).fillMaxHeight()) {
                val cx = size.width * .5f
                val top = size.height * .10f
                val unit = size.height * .19f
                val shift = if (pose == "WALKING") swing * 8f else 0f
                val head = Offset(cx, top + unit * .35f)
                val neck = Offset(cx, top + unit * .75f)
                val shoulderY = top + unit
                val hipY = if (pose == "SITTING") top + unit * 2.2f else top + unit * 2.35f
                val shoulderL = Offset(cx - 18f, shoulderY)
                val shoulderR = Offset(cx + 18f, shoulderY)
                val hipL = Offset(cx - 11f, hipY)
                val hipR = Offset(cx + 11f, hipY)
                fun bone(a: Offset, b: Offset, width: Float = 4f) {
                    drawLine(cyan.copy(alpha=.25f), a, b, width+5f, cap=StrokeCap.Round)
                    drawLine(cyan, a, b, width, cap=StrokeCap.Round)
                    drawCircle(Color(0xFFB9FAFF), 3.5f, a)
                    drawCircle(Color(0xFFB9FAFF), 3.5f, b)
                }
                drawCircle(cyan.copy(alpha=.2f), 18f, head)
                drawCircle(cyan, 10f, head, style=Stroke(2.5f))
                bone(head.copy(y=head.y+10f), neck)
                bone(shoulderL, shoulderR)
                bone(neck, Offset(cx, hipY))
                if (pose == "SITTING") {
                    bone(shoulderL, Offset(cx-26f, shoulderY+unit*.65f))
                    bone(Offset(cx-26f, shoulderY+unit*.65f), Offset(cx-20f, hipY-unit*.2f))
                    bone(shoulderR, Offset(cx+26f, shoulderY+unit*.65f))
                    bone(Offset(cx+26f, shoulderY+unit*.65f), Offset(cx+20f, hipY-unit*.2f))
                    bone(hipL, Offset(cx-20f, hipY+unit*.65f))
                    bone(Offset(cx-20f, hipY+unit*.65f), Offset(cx+15f, hipY+unit*.65f))
                    bone(hipR, Offset(cx+15f, hipY+unit*.65f))
                } else {
                    bone(shoulderL, Offset(cx-26f-shift, shoulderY+unit*.65f))
                    bone(Offset(cx-26f-shift, shoulderY+unit*.65f), Offset(cx-22f-shift, shoulderY+unit*1.05f))
                    bone(shoulderR, Offset(cx+26f+shift, shoulderY+unit*.65f))
                    bone(Offset(cx+26f+shift, shoulderY+unit*.65f), Offset(cx+22f+shift, shoulderY+unit*1.05f))
                    bone(hipL, Offset(cx-16f+shift, hipY+unit*.7f))
                    bone(Offset(cx-16f+shift, hipY+unit*.7f), Offset(cx-22f-shift, hipY+unit*1.35f))
                    bone(hipR, Offset(cx+16f-shift, hipY+unit*.7f))
                    bone(Offset(cx+16f-shift, hipY+unit*.7f), Offset(cx+22f+shift, hipY+unit*1.35f))
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("HUMAN SKELETON", color = TextMain, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                Text(if (demoMode) "SIMULATED DEMO" else "WAITING FOR DATA", color = TextDim, style = MaterialTheme.typography.labelSmall)
                Surface(color = if (pose=="WALKING") Color(0xFF073F43) else Panel2, shape = RoundedCornerShape(10.dp), border = androidx.foundation.BorderStroke(1.dp, cyan.copy(alpha=.7f))) {
                    Text(pose, Modifier.padding(horizontal=12.dp, vertical=8.dp), color=cyan, fontWeight=FontWeight.Bold)
                }
                Text(when(pose) { "SITTING" -> "Posture: seated"; "WALKING" -> "Motion: walking"; else -> "Posture: upright" }, color=TextMain, style=MaterialTheme.typography.bodySmall)
                Text("Demo pose is animated, not measured from real CSI.", color=TextDim, style=MaterialTheme.typography.labelSmall)
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.TextOnCanvas(text: String, position: Offset, color: Color) {
    drawContext.canvas.nativeCanvas.drawText(text, position.x, position.y, android.graphics.Paint().apply {
        this.color=color.toArgb(); textSize=13f; isAntiAlias=true
        typeface=android.graphics.Typeface.create("sans",android.graphics.Typeface.BOLD)
    })
}

@Composable
private fun ActionButton(label: String, disabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Button(onClick=onClick,enabled=!disabled,modifier=modifier.height(44.dp),shape=RoundedCornerShape(11.dp),colors=ButtonDefaults.buttonColors(
        containerColor=if(label=="STOP")Color(0xFF0D4F8B)else Color(0xFF0B66D6),contentColor=TextMain,
        disabledContainerColor=Color(0xFF0A2038),disabledContentColor=TextDim
    )) { Text(label,fontWeight=FontWeight.Bold,style=MaterialTheme.typography.labelSmall) }
}
