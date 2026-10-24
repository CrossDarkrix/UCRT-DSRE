package com.uyatame.ucrt.dsre

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Channel-first PCM DSP port. Each channel array must contain the same frame count. */
class DsreDspEngine(
    private val sampleRate: Int,
    private val channels: Int,
    private val parameters: DsreParameters,
) {
    private val contextFrames = max(0, (sampleRate * parameters.contextSeconds).toInt())
    private var tail = Array(channels) { FloatArray(0) }
    private var dynamicEnvelope = FloatArray(channels)

    init { require(sampleRate > 0); require(channels > 0) }

    fun process(input: Array<FloatArray>): Array<FloatArray> {
        validate(input)
        if (input[0].isEmpty()) return Array(channels) { FloatArray(0) }
        val prefix = tail[0].size
        val work = Array(channels) { ch -> tail[ch] + input[ch] }
        val excited = multibandExciter(work, parameters.harmonicCount / 16.0f)
        val valid = Array(channels) { ch -> excited[ch].copyOfRange(prefix, prefix + input[ch].size) }
        val dynamic = statefulDynamic(valid, parameters.dynamic)
        val stereo = stereoWidth(dynamic, parameters.stereoWidth)
        val blend = parameters.strength.coerceIn(0.1f, 0.8f)
        val output = Array(channels) { ch -> FloatArray(input[ch].size) { i ->
            finite(input[ch][i] * (1f - blend) + stereo[ch][i] * blend)
        }}
        safetyScale(output, 0.98f)
        tail = if (contextFrames == 0) Array(channels) { FloatArray(0) } else Array(channels) { ch ->
            val start = max(0, work[ch].size - contextFrames)
            work[ch].copyOfRange(start, work[ch].size)
        }
        return output
    }

    fun reset() { tail = Array(channels) { FloatArray(0) }; dynamicEnvelope = FloatArray(channels) }

    private fun validate(x: Array<FloatArray>) {
        require(x.size == channels) { "Expected $channels channels, got ${x.size}" }
        val n = x.firstOrNull()?.size ?: 0
        require(x.all { it.size == n }) { "All channels must have equal frame counts" }
    }

    private data class Band(val low: Double, val high: Double, val gain: Float, val harmonics: Int, val strength: Float)

    private fun multibandExciter(x: Array<FloatArray>, intensity: Float): Array<FloatArray> {
        val nyquist = sampleRate / 2.0
        val bands = listOf(
            Band(20.0,80.0,1.10f,3,.08f), Band(80.0,250.0,1.20f,4,.14f),
            Band(250.0,800.0,1.25f,5,.18f), Band(800.0,2500.0,1.35f,6,.22f),
            Band(2500.0,8000.0,1.45f,4,.25f), Band(8000.0,16000.0,1.50f,3,.18f),
            Band(16000.0,min(20000.0,nyquist-1000.0),1.35f,2,.12f),
        ).filter { it.low < nyquist && it.high < nyquist && it.high > it.low }
        if (bands.isEmpty()) return Array(channels) { x[it].copyOf() }
        val scale = intensity.coerceIn(.1f,1.5f)
        return Array(channels) { ch ->
            val original=x[ch]; val enhanced=FloatArray(original.size)
            for (band in bands) {
                val filtered=bandpass(original,band.low,band.high)
                var center=(band.low+band.high)/2.0
                if(center<=0.0) center=1.0
                val harmonic=generateHarmonics(filtered,center,band.harmonics,band.strength*scale)
                for(i in enhanced.indices) enhanced[i] += harmonic[i]*band.gain/bands.size
            }
            for(i in enhanced.indices) enhanced[i]=finite(original[i]*.78f+enhanced[i]*.22f)
            enhanced
        }
    }

    private fun generateHarmonics(signal: FloatArray, fundamental: Double, count: Int, strength: Float): FloatArray {
        val out=signal.copyOf(); val sr=sampleRate.toDouble()
        for(h in 2 until count+2){ val freq=fundamental*h; if(freq>=sr/2.0) continue
            val inc=2.0*PI*freq/sr
            for(i in signal.indices) out[i]=finite(out[i]+signal[i]*sin(inc*i).toFloat()*(strength/h))
        }
        return sanitize(out)
    }

    private fun bandpass(input: FloatArray, low: Double, high: Double): FloatArray {
        if(input.isEmpty() || low>=high) return FloatArray(input.size)
        val n=input.size; val re=DoubleArray(n){input[it].toDouble()}; val im=DoubleArray(n)
        Fourier.transform(re,im,false)
        val nyquist=sampleRate/2.0; val bw=max(20.0,(high-low)*.15)
        val low1=max(0.0,low-bw); val high2=min(nyquist,high+bw)
        for(k in 0 until n){ val signed=if(k<=n/2) k else k-n; val f=abs(signed.toDouble())*sampleRate/n
            val mask=when { f<low1 || f>high2 -> 0.0; f<low -> {val t=(f-low1)/max(1e-12,low-low1);.5-.5*cos(PI*t)}; f<=high -> 1.0; else -> {val t=(f-high)/max(1e-12,high2-high);.5+.5*cos(PI*t)} }
            re[k]*=mask; im[k]*=mask
        }
        Fourier.transform(re,im,true)
        return FloatArray(n){finite(re[it].toFloat())}
    }

    private fun statefulDynamic(x:Array<FloatArray>, ratio:Float):Array<FloatArray>{
        val y=Array(channels){FloatArray(x[it].size)}
        val attack=exp(-1.0/max(1.0,sampleRate*5.0/1000.0)).toFloat()
        val release=exp(-1.0/max(1.0,sampleRate*50.0/1000.0)).toFloat()
        val amount=(ratio-1f).coerceIn(0f,.5f)
        for(i in x[0].indices) for(ch in 0 until channels){ val a=abs(x[ch][i]); val c=if(a>dynamicEnvelope[ch])attack else release
            dynamicEnvelope[ch]=c*dynamicEnvelope[ch]+(1f-c)*a
            y[ch][i]=finite(x[ch][i]*(1f+amount*dynamicEnvelope[ch].coerceIn(0f,1f))) }
        return y
    }

    private fun stereoWidth(x:Array<FloatArray>, width:Float):Array<FloatArray>{
        if(channels!=2)return Array(channels){x[it].copyOf()}; val w=width.coerceIn(1f,1.8f)
        val l=FloatArray(x[0].size);val r=FloatArray(x[0].size)
        for(i in l.indices){val mid=(x[0][i]+x[1][i])/2f;val side=(x[0][i]-x[1][i])/2f*w;l[i]=finite(mid+side);r[i]=finite(mid-side)}
        return arrayOf(l,r)
    }

    private fun sanitize(x:FloatArray):FloatArray{val y=FloatArray(x.size){finite(x[it])};var peak=0f;for(v in y)peak=max(peak,abs(v));if(peak>1000f)for(i in y.indices)y[i]/=peak;return y}
    private fun safetyScale(x:Array<FloatArray>, limit:Float){var peak=0f;for(c in x)for(v in c)peak=max(peak,abs(v));if(peak>limit){val s=limit/peak;for(c in x)for(i in c.indices)c[i]*=s}}
    private fun finite(v:Float)=if(v.isFinite())v else 0f
}

private object Fourier {
    fun transform(re:DoubleArray,im:DoubleArray,inverse:Boolean){require(re.size==im.size);if(re.isEmpty())return; if ((re.size and (re.size - 1)) == 0) radix2(re,im,inverse) else bluestein(re,im,inverse);if(inverse){val n=re.size.toDouble();for(i in re.indices){re[i]/=n;im[i]/=n}}}
    private fun radix2(re:DoubleArray,im:DoubleArray,inverse:Boolean){val n=re.size;var levels=0;var t=n;while(t>1){levels++;t=t ushr 1};for(i in 0 until n){val j=Integer.reverse(i) ushr (32-levels);if(j>i){var q=re[i];re[i]=re[j];re[j]=q;q=im[i];im[i]=im[j];im[j]=q}}
        var size=2;while(size<=n){val half=size/2;val sign=if(inverse)2.0 else -2.0;for(i in 0 until n step size)for(j in 0 until half){val a=sign*PI*j/size;val c=cos(a);val s=sin(a);val k=i+j;val l=k+half;val tr=re[l]*c-im[l]*s;val ti=re[l]*s+im[l]*c;re[l]=re[k]-tr;im[l]=im[k]-ti;re[k]+=tr;im[k]+=ti};size=size shl 1}}
    private fun bluestein(re:DoubleArray,im:DoubleArray,inverse:Boolean){val n=re.size;var m=1;while(m<n*2-1)m=m shl 1;val ar=DoubleArray(m);val ai=DoubleArray(m);val br=DoubleArray(m);val bi=DoubleArray(m);val sign=if(inverse)1.0 else -1.0
        for(i in 0 until n){val a=PI*(i.toLong()*i%n).toDouble()/n;val c=cos(a);val s=sin(a)*sign;ar[i]=re[i]*c-im[i]*s;ai[i]=re[i]*s+im[i]*c;br[i]=c;bi[i]=-s;if(i!=0){br[m-i]=c;bi[m-i]=-s}}
        convolve(ar,ai,br,bi);for(i in 0 until n){val a=PI*(i.toLong()*i%n).toDouble()/n;val c=cos(a);val s=sin(a)*sign;re[i]=ar[i]*c-ai[i]*s;im[i]=ar[i]*s+ai[i]*c}}
    private fun convolve(xr:DoubleArray,xi:DoubleArray,yr:DoubleArray,yi:DoubleArray){radix2(xr,xi,false);radix2(yr,yi,false);for(i in xr.indices){val r=xr[i]*yr[i]-xi[i]*yi[i];val v=xi[i]*yr[i]+xr[i]*yi[i];xr[i]=r;xi[i]=v};radix2(xr,xi,true);val n=xr.size.toDouble();for(i in xr.indices){xr[i]/=n;xi[i]/=n}}
}
