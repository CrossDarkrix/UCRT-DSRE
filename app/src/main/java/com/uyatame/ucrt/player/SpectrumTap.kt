package com.uyatame.ucrt.player

import android.media.AudioFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
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

/**
 * Visualization-only PCM tap.
 * Playback never waits for analysis: PCM is accepted only while a visible consumer is active,
 * FFT work runs on a dedicated worker, and publication is scheduled for the estimated audible time.
 */
object SpectrumTap {
    data class Frame(val waveform: FloatArray, val bands: FloatArray, val peaks: FloatArray, val rms: Float)

    private const val FFT_SIZE = 1024
    private const val BAND_COUNT = 48
    private const val ANALYSIS_INTERVAL_NANOS = 33_333_333L
    private const val SILENCE_DELAY_MS = 180L
    private val emptyFrame = Frame(FloatArray(128), FloatArray(BAND_COUNT), FloatArray(BAND_COUNT), 0f)
    private val latest = AtomicReference(emptyFrame)
    private val consumers = AtomicInteger(0)
    private val generation = AtomicLong(0L)
    private val version = AtomicLong(0L)
    private val lastAcceptedAt = AtomicLong(0L)
    private val displayDelayMs = AtomicInteger(58)
    private val worker = ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "UCRT-Spectrum").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 }
    }.apply {
        removeOnCancelPolicy = true
        setExecuteExistingDelayedTasksAfterShutdownPolicy(false)
    }
    private var smooth = FloatArray(BAND_COUNT)
    private var peaks = FloatArray(BAND_COUNT)

    fun setConsumerActive(active: Boolean) {
        if (active) {
            consumers.incrementAndGet()
        } else {
            while (true) {
                val current = consumers.get()
                if (current <= 0 || consumers.compareAndSet(current, current - 1)) break
            }
            if (consumers.get() == 0) clearPending()
        }
    }

    fun isConsumerActive(): Boolean = consumers.get() > 0

    fun setDisplayDelayMs(value: Int) {
        val clamped = value.coerceIn(-100, 150)
        if (displayDelayMs.getAndSet(clamped) != clamped) clearPending()
    }

    private fun clearPending() {
        generation.incrementAndGet()
        lastAcceptedAt.set(0L)
        latest.set(emptyFrame)
        worker.execute {
            smooth.fill(0f)
            peaks.fill(0f)
        }
    }

    fun submit(src: FloatArray, frames: Int, channels: Int, sampleRate: Int, delayNanos: Long = 0L) {
        if (!reserveAnalysisSlot() || frames <= 0 || channels <= 0 || src.isEmpty()) return
        val out = FloatArray(FFT_SIZE)
        val available = min(frames, src.size / channels)
        val start = max(0, available - FFT_SIZE)
        var o = FFT_SIZE - (available - start)
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
        if (!reserveAnalysisSlot() || frames <= 0 || channels <= 0) return
        val b = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val count = min(frames, FFT_SIZE)
        val bytes = when (encoding) {
            AudioFormat.ENCODING_PCM_8BIT -> 1
            AudioFormat.ENCODING_PCM_16BIT -> 2
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
            AudioFormat.ENCODING_PCM_32BIT, AudioFormat.ENCODING_PCM_FLOAT -> 4
            else -> return
        }
        val skip = max(0, frames - count) * channels * bytes
        if (skip > b.remaining()) return
        b.position(b.position() + skip)
        val out = FloatArray(FFT_SIZE)
        var o = FFT_SIZE - count
        repeat(count) {
            var sum = 0f
            var read = 0
            repeat(min(channels, 2)) {
                if (b.remaining() >= bytes) {
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
                    sum += value
                    read++
                }
            }
            val advance = (channels - min(channels, 2)) * bytes
            if (advance <= b.remaining()) b.position(b.position() + advance)
            out[o++] = if (read == 0) 0f else (sum / read).coerceIn(-1f, 1f)
        }
        schedule(out, sampleRate, delayNanos)
    }

    private fun reserveAnalysisSlot(): Boolean {
        if (!isConsumerActive()) return false
        while (true) {
            val now = System.nanoTime()
            val previous = lastAcceptedAt.get()
            if (now - previous < ANALYSIS_INTERVAL_NANOS) return false
            if (lastAcceptedAt.compareAndSet(previous, now)) return true
        }
    }

    private fun schedule(mono: FloatArray, sampleRate: Int, delayNanos: Long) {
        val token = generation.get()
        val offset = displayDelayMs.get().toLong() * 1_000_000L
        val delay = (delayNanos + offset).coerceIn(0L, 2_000_000_000L)
        worker.schedule(task@{
            if (token != generation.get() || !isConsumerActive()) return@task
            val frame = analyze(mono, sampleRate.coerceAtLeast(8000))
            val frameVersion = version.incrementAndGet()
            latest.set(frame)
            worker.schedule({
                if (token == generation.get() && frameVersion == version.get()) latest.set(emptyFrame)
            }, SILENCE_DELAY_MS, TimeUnit.MILLISECONDS)
        }, delay, TimeUnit.NANOSECONDS)
    }

    fun frame(): Frame = latest.get()

    private fun analyze(mono: FloatArray, sampleRate: Int): Frame {
        val real = FloatArray(FFT_SIZE)
        val imag = FloatArray(FFT_SIZE)
        var power = 0.0
        for (i in 0 until FFT_SIZE) {
            val sample = mono[i]
            power += sample * sample
            val window = (0.5 - 0.5 * cos(2.0 * PI * i / (FFT_SIZE - 1))).toFloat()
            real[i] = sample * window
        }
        fft(real, imag)
        val minHz = 30.0
        val maxHz = min(20_000.0, sampleRate / 2.0)
        for (band in 0 until BAND_COUNT) {
            val low = minHz * Math.pow(maxHz / minHz, band.toDouble() / BAND_COUNT)
            val high = minHz * Math.pow(maxHz / minHz, (band + 1.0) / BAND_COUNT)
            val from = max(1, (low * FFT_SIZE / sampleRate).toInt())
            val to = min(FFT_SIZE / 2 - 1, max(from, (high * FFT_SIZE / sampleRate).toInt()))
            var magnitude = 0.0
            for (bin in from..to) {
                val rr = real[bin].toDouble(); val ii = imag[bin].toDouble()
                magnitude += sqrt(rr * rr + ii * ii)
            }
            magnitude /= (to - from + 1).coerceAtLeast(1)
            val db = 20.0 * log10(max(1e-7, magnitude / (FFT_SIZE * .5)))
            val normalized = ((db + 72.0) / 72.0).toFloat().coerceIn(0f, 1f)
            smooth[band] = if (normalized > smooth[band]) normalized * .72f + smooth[band] * .28f else smooth[band] * .82f + normalized * .18f
            peaks[band] = max(smooth[band], peaks[band] - .014f)
        }
        val wave = FloatArray(128)
        for (i in wave.indices) wave[i] = mono[i * (FFT_SIZE / wave.size)]
        return Frame(wave, smooth.copyOf(), peaks.copyOf(), sqrt(power / mono.size).toFloat())
    }

    private fun fft(real: FloatArray, imag: FloatArray) {
        val n = real.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { val tr=real[i];real[i]=real[j];real[j]=tr }
        }
        var len = 2
        while (len <= n) {
            val angle = -2.0 * PI / len
            val wLenR=cos(angle).toFloat(); val wLenI=sin(angle).toFloat()
            var base=0
            while(base<n){var wr=1f;var wi=0f;for(k in 0 until len/2){
                val uR=real[base+k];val uI=imag[base+k]
                val vr=real[base+k+len/2]*wr-imag[base+k+len/2]*wi
                val vi=real[base+k+len/2]*wi+imag[base+k+len/2]*wr
                real[base+k]=uR+vr;imag[base+k]=uI+vi
                real[base+k+len/2]=uR-vr;imag[base+k+len/2]=uI-vi
                val nextWr=wr*wLenR-wi*wLenI;wi=wr*wLenI+wi*wLenR;wr=nextWr
            };base+=len}
            len=len shl 1
        }
    }
}
