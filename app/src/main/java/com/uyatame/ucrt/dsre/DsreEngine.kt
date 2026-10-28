package com.uyatame.ucrt.dsre

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

interface DsreProcessingEngine {
    fun process(input: Array<FloatArray>): Array<FloatArray>
    fun reset()
}

object DsreEngineFactory {
    fun create(sampleRate: Int, channels: Int, parameters: DsreParameters): DsreProcessingEngine =
        when (parameters.engineMode) {
            DsreEngineMode.LEGACY_OPTIMIZED -> DsreDspEngine(sampleRate, channels, parameters)
            DsreEngineMode.OVERSAMPLED_PR -> OversampledPrDsreEngine(sampleRate, channels, parameters)
        }
}

/**
 * R=1 oversampled partition-of-unity spectral filter bank.
 * The analysis masks sum to one, so the dry synthesis path reconstructs the input spectrum.
 * Harmonic energy is generated in the spectral domain and the complete signal uses one IFFT.
 */
class OversampledPrDsreEngine(
    private val sampleRate: Int,
    private val channels: Int,
    private val parameters: DsreParameters,
) : DsreProcessingEngine {
    private val fftLength = 65_536
    private val ranges = arrayOf(
        20.0 to 80.0, 80.0 to 250.0, 250.0 to 800.0, 800.0 to 2500.0,
        2500.0 to 8000.0, 8000.0 to 16000.0, 16000.0 to 20000.0,
    )
    private val masks = createPartitionMasks()
    private var dynamicEnvelope = FloatArray(channels)

    init { require(sampleRate > 0); require(channels > 0) }

    override fun process(input: Array<FloatArray>): Array<FloatArray> {
        require(input.size == channels)
        val frames = input.firstOrNull()?.size ?: 0
        require(input.all { it.size == frames })
        if (frames == 0) return Array(channels) { FloatArray(0) }
        val spectral = Array(channels) { ch -> processChannel(input[ch]) }
        val dynamic = statefulDynamic(spectral, parameters.dynamic)
        val stereo = stereoWidth(dynamic, parameters.stereoWidth)
        safetyScale(stereo, .98f)
        return stereo
    }

    private fun processChannel(input: FloatArray): FloatArray {
        val output = FloatArray(input.size)
        var offset = 0
        while (offset < input.size) {
            val live = min(fftLength, input.size - offset)
            val re = DoubleArray(fftLength)
            val im = DoubleArray(fftLength)
            for (i in 0 until live) re[i] = input[offset + i].toDouble()
            Fourier.transform(re, im, false)
            val dryRe = DoubleArray(fftLength)
            val dryIm = DoubleArray(fftLength)
            val wetRe = DoubleArray(fftLength)
            val wetIm = DoubleArray(fftLength)
            for (k in 0 until fftLength) {
                var partition = 0.0
                for (b in masks.indices) partition += masks[b][k]
                dryRe[k] = re[k] * partition
                dryIm[k] = im[k] * partition
            }
            val amount = (parameters.harmonicCount / 24.0).coerceIn(0.0, 1.0) * parameters.strength
            for (b in masks.indices) {
                val order = 2 + (b % 3)
                val gain = amount * (0.18 + b * 0.025) / order
                for (k in 1 until fftLength / 2) {
                    val weight = masks[b][k]
                    if (weight <= 1e-12) continue
                    val target = k * order
                    if (target >= fftLength / 2) break
                    wetRe[target] += re[k] * weight * gain
                    wetIm[target] += im[k] * weight * gain
                }
            }
            for (k in 1 until fftLength / 2) {
                val mirror = fftLength - k
                wetRe[mirror] = wetRe[k]
                wetIm[mirror] = -wetIm[k]
            }
            val wetMix = parameters.strength.coerceIn(.10f, .80f).toDouble()
            for (k in 0 until fftLength) {
                re[k] = dryRe[k] + wetRe[k] * wetMix
                im[k] = dryIm[k] + wetIm[k] * wetMix
            }
            Fourier.transform(re, im, true)
            for (i in 0 until live) output[offset + i] = finite(re[i].toFloat())
            offset += live
        }
        return output
    }

    private fun createPartitionMasks(): Array<DoubleArray> {
        val raw = Array(ranges.size) { DoubleArray(fftLength) }
        val nyquist = sampleRate / 2.0
        for (b in ranges.indices) {
            val low = ranges[b].first
            val high = min(ranges[b].second, nyquist)
            val transition = max(20.0, (high - low) * .18)
            for (k in 0 until fftLength) {
                val signed = if (k <= fftLength / 2) k else k - fftLength
                val f = abs(signed.toDouble()) * sampleRate / fftLength
                val left = when {
                    f <= low - transition -> 0.0
                    f >= low -> 1.0
                    else -> .5 - .5 * cos(PI * (f - (low - transition)) / transition)
                }
                val right = when {
                    f <= high -> 1.0
                    f >= high + transition -> 0.0
                    else -> .5 + .5 * cos(PI * (f - high) / transition)
                }
                raw[b][k] = left * right
            }
        }
        for (k in 0 until fftLength) {
            var sum = 0.0
            for (b in raw.indices) sum += raw[b][k]
            if (sum > 1e-12) {
                for (b in raw.indices) raw[b][k] /= sum
            } else {
                val signed = if (k <= fftLength / 2) k else k - fftLength
                val f = abs(signed.toDouble()) * sampleRate / fftLength
                val nearest = ranges.indices.minByOrNull { b -> abs((ranges[b].first + ranges[b].second) * .5 - f) } ?: 0
                raw[nearest][k] = 1.0
            }
        }
        return raw
    }

    private fun statefulDynamic(x: Array<FloatArray>, ratio: Float): Array<FloatArray> {
        val y = Array(channels) { FloatArray(x[it].size) }
        val attack = exp(-1.0 / max(1.0, sampleRate * 5.0 / 1000.0)).toFloat()
        val release = exp(-1.0 / max(1.0, sampleRate * 50.0 / 1000.0)).toFloat()
        val amount = (ratio - 1f).coerceIn(0f, .5f)
        for (i in x[0].indices) for (ch in 0 until channels) {
            val a=abs(x[ch][i]); val c=if(a>dynamicEnvelope[ch])attack else release
            dynamicEnvelope[ch]=c*dynamicEnvelope[ch]+(1f-c)*a
            y[ch][i]=finite(x[ch][i]*(1f+amount*dynamicEnvelope[ch].coerceIn(0f,1f)))
        }
        return y
    }
    private fun stereoWidth(x:Array<FloatArray>, width:Float):Array<FloatArray>{
        if(channels!=2)return Array(channels){x[it].copyOf()}; val w=width.coerceIn(.5f,1.5f)
        val l=FloatArray(x[0].size); val r=FloatArray(x[0].size)
        for(i in l.indices){val mid=(x[0][i]+x[1][i])/2f;val side=(x[0][i]-x[1][i])/2f*w;l[i]=finite(mid+side);r[i]=finite(mid-side)}
        return arrayOf(l,r)
    }
    private fun safetyScale(x:Array<FloatArray>, limit:Float){var peak=0f;for(c in x)for(v in c)peak=max(peak,abs(v));if(peak>limit){val scale=limit/peak;for(c in x)for(i in c.indices)c[i]*=scale}}
    private fun finite(v:Float)=if(v.isFinite())v else 0f
    override fun reset(){dynamicEnvelope=FloatArray(channels)}
}
