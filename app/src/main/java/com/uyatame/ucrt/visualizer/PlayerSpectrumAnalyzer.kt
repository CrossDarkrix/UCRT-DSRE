package com.uyatame.ucrt.visualizer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.uyatame.ucrt.player.SpectrumTap
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.pow

private enum class SpectrumStyle(val key: String, val label: String) {
    WAVE("wave", "Wave"),
    BARS("bars", "Bars"),
    MIRROR("mirror", "Mirror"),
    LINE("line", "Line"),
    CIRCLE("circle", "Circle"),
}

private fun smoothBands(source: FloatArray): FloatArray {
    if (source.size < 3) return source.copyOf()
    val firstPass = FloatArray(source.size)
    firstPass[0] = source[0] * 0.96f + source[1] * 0.04f
    for (i in 1 until source.lastIndex) {
        firstPass[i] = source[i - 1] * 0.025f + source[i] * 0.95f + source[i + 1] * 0.025f
    }
    firstPass[firstPass.lastIndex] = source[source.lastIndex] * 0.96f + source[source.lastIndex - 1] * 0.04f
    return firstPass
}

private fun smoothWaveform(source: FloatArray): FloatArray {
    if (source.size < 5) return source.copyOf()
    val out = FloatArray(source.size)
    out[0] = source[0]
    for (i in 1 until source.lastIndex) {
        out[i] = source[i - 1] * 0.06f + source[i] * 0.88f + source[i + 1] * 0.06f
    }
    out[out.lastIndex] = source[source.lastIndex]
    return out
}

private fun smoothLinePath(values: FloatArray, xOf: (Int) -> Float, yOf: (Float) -> Float): Path {
    val path = Path()
    if (values.isEmpty()) return path
    path.moveTo(xOf(0), yOf(values[0]))
    for (i in 1 until values.size) {
        val previousX = xOf(i - 1)
        val previousY = yOf(values[i - 1])
        val x = xOf(i)
        val y = yOf(values[i])
        val middleX = (previousX + x) * 0.5f
        path.cubicTo(middleX, previousY, middleX, y, x, y)
    }
    return path
}

private class VisualizerMotionState {
    private var bands = FloatArray(0)
    private var peaks = FloatArray(0)
    private var wave = FloatArray(0)
    private var lastNanos = 0L

    fun update(target: SpectrumTap.Frame, nowNanos: Long): SpectrumTap.Frame {
        if (bands.size != target.bands.size) bands = target.bands.copyOf()
        if (peaks.size != target.peaks.size) peaks = target.peaks.copyOf()
        if (wave.size != target.waveform.size) wave = target.waveform.copyOf()
        val dt = if (lastNanos == 0L) 1f / 60f else
            ((nowNanos - lastNanos).coerceIn(1L, 100_000_000L) / 1_000_000_000f)
        lastNanos = nowNanos
        val bandAttack = 1f - exp((-68f * dt).toDouble()).toFloat()
        val bandRelease = 1f - exp((-30f * dt).toDouble()).toFloat()
        val peakRate = 1f - exp((-26f * dt).toDouble()).toFloat()
        val waveRate = 1f - exp((-62f * dt).toDouble()).toFloat()
        for (i in bands.indices) {
            val rising = target.bands[i] > bands[i]
            val rate = if (rising) bandAttack else bandRelease
            val transientLift = if (rising) (target.bands[i] - bands[i]) * 0.30f else 0f
            val displayTarget = (target.bands[i] + transientLift).coerceIn(0f, 1f)
            bands[i] += (displayTarget - bands[i]) * rate
            peaks[i] += (target.peaks[i] - peaks[i]) * peakRate
        }
        for (i in wave.indices) {
            val targetWave = (target.waveform[i] * 1.18f).coerceIn(-1f, 1f)
            wave[i] += (targetWave - wave[i]) * waveRate
        }
        val displayBands = FloatArray(bands.size)
        val displayPeaks = FloatArray(peaks.size)
        for (i in bands.indices) {
            // Remove the permanent 1.22x lift that kept most bars near the ceiling.
            // A small floor gate followed by a contrast curve keeps attacks strong while
            // restoring visible travel through the middle and lower parts of the display.
            val gated = ((bands[i] - 0.075f) / 0.925f).coerceIn(0f, 1f)
            val emphasized = gated.toDouble().pow(0.88).toFloat()
            displayBands[i] = emphasized
            val peakGated = ((peaks[i] - 0.055f) / 0.945f).coerceIn(0f, 1f)
            displayPeaks[i] = maxOf(displayBands[i], peakGated)
        }
        return SpectrumTap.Frame(wave.copyOf(), displayBands, displayPeaks, target.rms)
    }
}

@Composable
fun PlayerSpectrumAnalyzer(
    accent: Color,
    content: Color,
    compact: Boolean,
    refreshMs: Int,
    colorArgb: Int,
    styleKey: String,
    syncOffsetMs: Int,
    modifier: Modifier = Modifier,
) {
    val style = remember(styleKey) {
        SpectrumStyle.entries.firstOrNull { it.key == styleKey } ?: SpectrumStyle.BARS
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        var active = false
        fun update(next: Boolean) {
            if (active != next) {
                active = next
                SpectrumTap.setConsumerActive(next)
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> update(true)
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_DESTROY -> update(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        update(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            update(false)
        }
    }
    LaunchedEffect(syncOffsetMs) {
        SpectrumTap.setDisplayDelayMs(syncOffsetMs.coerceIn(-100, 150))
    }
    val motion = remember { VisualizerMotionState() }
    val currentRefreshMs by rememberUpdatedState(refreshMs.coerceIn(33, 200))
    val frame by produceState(motion.update(SpectrumTap.frame(), System.nanoTime())) {
        while (true) {
            value = motion.update(SpectrumTap.frame(), System.nanoTime())
            delay(currentRefreshMs.toLong())
        }
    }
    val height = if (compact) 62.dp else 82.dp
    Column(modifier.fillMaxWidth()) {
        Canvas(
            Modifier.fillMaxWidth().height(height).background(Color.Black.copy(alpha = 0.14f), RoundedCornerShape(12.dp)),
        ) {
            val bands = smoothBands(frame.bands)
            val peaks = smoothBands(frame.peaks)
            val wave = smoothWaveform(frame.waveform)
            val pad = 8.dp.toPx()
            val w = size.width - pad * 2
            val h = size.height - pad * 2
            val chosen = if (colorArgb == 0) accent else Color(colorArgb)
            val base = chosen.copy(alpha = 0.90f)
            val highlight = Color(
                red = (chosen.red + (1f - chosen.red) * 0.58f).coerceIn(0f, 1f),
                green = (chosen.green + (1f - chosen.green) * 0.58f).coerceIn(0f, 1f),
                blue = (chosen.blue + (1f - chosen.blue) * 0.58f).coerceIn(0f, 1f),
                alpha = 1f,
            )
            when (style) {
                SpectrumStyle.WAVE -> {
                    val path = smoothLinePath(
                        wave,
                        xOf = { i -> pad + w * i / (wave.size - 1).coerceAtLeast(1) },
                        yOf = { value -> size.height / 2f - value * h * 0.42f },
                    )
                    drawPath(path, Brush.horizontalGradient(listOf(chosen.copy(alpha = .45f), highlight, chosen)), style = Stroke(if (compact) 2f else 3f, cap = StrokeCap.Round))
                }
                SpectrumStyle.BARS, SpectrumStyle.MIRROR -> {
                    val slot = w / bands.size
                    val bw = slot * 0.64f
                    for (i in bands.indices) {
                        val x = pad + i * slot + (slot - bw) / 2f
                        val v = bands[i]
                        if (style == SpectrumStyle.BARS) {
                            val bh = maxOf(2f, h * v)
                            drawRoundRect(Brush.verticalGradient(listOf(highlight, base)), Offset(x, pad + h - bh), androidx.compose.ui.geometry.Size(bw, bh), androidx.compose.ui.geometry.CornerRadius(bw * .35f))
                            val py = pad + h - h * peaks[i]
                            drawLine(highlight.copy(alpha = .80f), Offset(x, py), Offset(x + bw, py), 1.5f)
                        } else {
                            val bh = maxOf(1f, h * .48f * v)
                            val cy = size.height / 2f
                            drawRoundRect(base, Offset(x, cy - bh), androidx.compose.ui.geometry.Size(bw, bh * 2f), androidx.compose.ui.geometry.CornerRadius(bw * .35f))
                        }
                    }
                }
                SpectrumStyle.LINE -> {
                    val path = smoothLinePath(
                        bands,
                        xOf = { i -> pad + w * i / (bands.size - 1).coerceAtLeast(1) },
                        yOf = { value -> pad + h * (1f - value) },
                    )
                    drawPath(path, Brush.horizontalGradient(listOf(chosen, highlight, chosen)), style = Stroke(3f, cap = StrokeCap.Round))
                }
                SpectrumStyle.CIRCLE -> {
                    val cx = size.width / 2f; val cy = size.height / 2f
                    val radius = min(w, h) * .24f
                    for (i in bands.indices) {
                        val a = i.toDouble() / bands.size * PI * 2.0 - PI / 2.0
                        val inner = radius
                        val outer = radius + h * .30f * bands[i]
                        val c = cos(a).toFloat(); val s = sin(a).toFloat()
                        drawLine(base, Offset(cx + c * inner, cy + s * inner), Offset(cx + c * outer, cy + s * outer), if (compact) 2f else 3f, StrokeCap.Round)
                    }
                    drawCircle(highlight.copy(alpha = .55f), radius, Offset(cx, cy), style = Stroke(1.5f))
                }
            }
        }
    }
}
