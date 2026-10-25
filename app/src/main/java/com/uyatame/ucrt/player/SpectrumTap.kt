package com.uyatame.ucrt.player

import android.media.AudioFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Lock-free, visualization-only PCM tap. Playback never waits for analysis. */
object SpectrumTap {
    data class Snapshot(val mono: FloatArray, val sampleRate: Int, val version: Long, val capturedAtNanos: Long)
    data class Frame(val waveform: FloatArray, val bands: FloatArray, val peaks: FloatArray, val rms: Float)
    private data class ScheduledSnapshot(val snapshot: Snapshot, val audibleAtNanos: Long)

    private const val FFT_SIZE = 1024
    private const val BAND_COUNT = 48
    private val version = AtomicLong(0)
    private val latest = AtomicReference(Snapshot(FloatArray(FFT_SIZE), 44100, 0, System.nanoTime()))
    private val pending = ConcurrentLinkedQueue<ScheduledSnapshot>()
    private val pendingCount = AtomicInteger(0)
    private const val MAX_PENDING = 96
    private var smooth = FloatArray(BAND_COUNT)
    private var peaks = FloatArray(BAND_COUNT)
    private var lastVersion = -1L

    fun submit(src: FloatArray, frames: Int, channels: Int, sampleRate: Int, delayNanos: Long = 0L) {
        if (frames <= 0 || channels <= 0 || src.isEmpty()) return
        val out = FloatArray(FFT_SIZE)
        val available = min(frames, src.size / channels)
        val start = max(0, available - FFT_SIZE)
        val pad = FFT_SIZE - (available - start)
        var o = pad
        for (f in start until available) {
            var sum = 0f
            val base = f * channels
            val n = min(channels, 2)
            for (c in 0 until n) sum += src[base + c]
            out[o++] = (sum / n.coerceAtLeast(1)).coerceIn(-1f, 1f)
        }
        schedule(out, sampleRate, delayNanos)
    }

    fun submitPcm(
        buffer: ByteBuffer,
        encoding: Int,
        frames: Int,
        channels: Int,
        sampleRate: Int,
        delayNanos: Long = 0L,
    ) {
        if (frames <= 0 || channels <= 0) return
        val b = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val count = min(frames, FFT_SIZE)
        val skipFrames = max(0, frames - count)
        val bytes = when (encoding) {
            AudioFormat.ENCODING_PCM_8BIT -> 1
            AudioFormat.ENCODING_PCM_16BIT -> 2
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
            AudioFormat.ENCODING_PCM_32BIT, AudioFormat.ENCODING_PCM_FLOAT -> 4
            else -> return
        }
        val skip = skipFrames * channels * bytes
        if (skip <= b.remaining()) b.position(b.position() + skip) else return
        val out = FloatArray(FFT_SIZE)
        var o = FFT_SIZE - count
        repeat(count) {
            var sum = 0f
            var read = 0
            repeat(min(channels, 2)) {
                if (b.remaining() < bytes) return@repeat
                val value = when (encoding) {
                    AudioFormat.ENCODING_PCM_8BIT -> ((b.get().toInt() and 0xff) - 128) / 128f
                    AudioFormat.ENCODING_PCM_16BIT -> b.short / 32768f
                    AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
                        val x = (b.get().toInt() and 0xff) or ((b.get().toInt() and 0xff) shl 8) or (b.get().toInt() shl 16)
                        x / 8388608f
                    }
                    AudioFormat.ENCODING_PCM_32BIT -> b.int / 2147483648f
                    else -> b.float
                }
                sum += value; read++
            }
            val remainingChannels = channels - min(channels, 2)
            val advance = remainingChannels * bytes
            if (advance <= b.remaining()) b.position(b.position() + advance)
            out[o++] = if (read == 0) 0f else (sum / read).coerceIn(-1f, 1f)
        }
        schedule(out, sampleRate, delayNanos)
    }

    private fun schedule(mono: FloatArray, sampleRate: Int, delayNanos: Long) {
        val now = System.nanoTime()
        val snap = Snapshot(mono, sampleRate.coerceAtLeast(8000), version.incrementAndGet(), now)
        val delay = delayNanos.coerceIn(0L, 2_000_000_000L)
        if (delay == 0L) {
            latest.set(snap)
            return
        }
        pending.offer(ScheduledSnapshot(snap, now + delay))
        val count = pendingCount.incrementAndGet()
        if (count > MAX_PENDING) {
            pending.poll()?.let { pendingCount.decrementAndGet() }
        }
    }

    private fun publishAudibleSnapshot(now: Long = System.nanoTime()) {
        while (true) {
            val next = pending.peek() ?: return
            if (next.audibleAtNanos > now) return
            pending.poll() ?: continue
            pendingCount.decrementAndGet()
            latest.set(next.snapshot)
        }
    }

    @Synchronized
    fun frame(): Frame {
        publishAudibleSnapshot()
        val snap = latest.get()
        if (snap.version == lastVersion) {
            // Keep the last target stable between normal PCM callbacks. Per-UI-frame decay
            // caused all bands to fall together and jump on the next PCM block.
            if (System.nanoTime() - snap.capturedAtNanos > 180_000_000L) {
                for (i in smooth.indices) {
                    smooth[i] *= 0.955f
                    peaks[i] = max(smooth[i], peaks[i] - 0.012f)
                }
            }
        } else {
            val real = FloatArray(FFT_SIZE)
            val imag = FloatArray(FFT_SIZE)
            var power = 0.0
            for (i in 0 until FFT_SIZE) {
                val sample = snap.mono[i]
                power += sample * sample
                val window = (0.5 - 0.5 * cos(2.0 * PI * i / (FFT_SIZE - 1))).toFloat()
                real[i] = sample * window
            }
            fft(real, imag)
            val minHz = 35.0
            val maxHz = min(18000.0, snap.sampleRate * 0.5)
            for (band in 0 until BAND_COUNT) {
                val f0 = minHz * Math.pow(maxHz / minHz, band.toDouble() / BAND_COUNT)
                val f1 = minHz * Math.pow(maxHz / minHz, (band + 1.0) / BAND_COUNT)
                val k0 = max(1, (f0 * FFT_SIZE / snap.sampleRate).toInt())
                val k1 = max(k0 + 1, min(FFT_SIZE / 2, (f1 * FFT_SIZE / snap.sampleRate).toInt()))
                var m = 0.0
                for (k in k0 until k1) m = max(m, sqrt((real[k] * real[k] + imag[k] * imag[k]).toDouble()))
                // Hann coherent gain is approximately 0.5. Convert the one-sided FFT
                // magnitude to full-scale amplitude before mapping a -72..-6 dBFS display range.
                // The old raw-magnitude logarithm omitted FFT-size normalization and saturated bars.
                val amplitude = (4.0 * m / FFT_SIZE).coerceAtLeast(1.0e-9)
                val dbfs = 20.0 * log10(amplitude)
                val normalized = ((dbfs + 72.0) / 66.0).toFloat().coerceIn(0f, 1f)
                smooth[band] = if (normalized > smooth[band]) smooth[band] * 0.52f + normalized * 0.48f else smooth[band] * 0.82f + normalized * 0.18f
                peaks[band] = max(smooth[band], peaks[band] - 0.014f)
            }
            lastVersion = snap.version
        }
        val wave = FloatArray(128)
        for (i in wave.indices) wave[i] = snap.mono[i * (FFT_SIZE / wave.size)]
        var power = 0f
        for (v in snap.mono) power += v * v
        return Frame(wave, smooth.copyOf(), peaks.copyOf(), sqrt(power / snap.mono.size))
    }

    private fun fft(real: FloatArray, imag: FloatArray) {
        val n = real.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { val tr = real[i]; real[i] = real[j]; real[j] = tr }
        }
        var len = 2
        while (len <= n) {
            val angle = -2.0 * PI / len
            val wLenR = cos(angle).toFloat(); val wLenI = sin(angle).toFloat()
            var base = 0
            while (base < n) {
                var wr = 1f; var wi = 0f
                for (k in 0 until len / 2) {
                    val uR = real[base + k]; val uI = imag[base + k]
                    val vr = real[base + k + len / 2] * wr - imag[base + k + len / 2] * wi
                    val vi = real[base + k + len / 2] * wi + imag[base + k + len / 2] * wr
                    real[base + k] = uR + vr; imag[base + k] = uI + vi
                    real[base + k + len / 2] = uR - vr; imag[base + k + len / 2] = uI - vi
                    val nextWr = wr * wLenR - wi * wLenI
                    wi = wr * wLenI + wi * wLenR; wr = nextWr
                }
                base += len
            }
            len = len shl 1
        }
    }
}
