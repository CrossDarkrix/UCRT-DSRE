package com.uyatame.ucrt.dsre

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.uyatame.ucrt.player.RationalResampler
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max

class DsreFileConverter(private val context: Context) {
    data class Progress(val fraction: Float, val stage: String)
    data class Result(val outputUri: Uri, val frames: Long, val sampleRate: Int)

    @Volatile private var cancelled = false
    fun cancel() { cancelled = true; runCatching { DsreNativeEncoder.cancel() } }

    fun convert(input: Uri, output: Uri, parameters: DsreParameters, outputFormat: String, onProgress: (Progress) -> Unit): Result {
        cancelled = false
        val temp = File.createTempFile("dsre_", ".wav", context.cacheDir)
        val encoded = if (outputFormat == "WAV") null else File.createTempFile(
            "dsre_encoded_",
            when (outputFormat) {
                "ALAC" -> ".m4a"
                "FLAC" -> ".flac"
                "MP3" -> ".mp3"
                else -> throw IOException("Unsupported output format: $outputFormat")
            },
            context.cacheDir,
        )
        var frames = 0L
        try {
            onProgress(Progress(0f, "Opening input"))
            WavWriter(temp, parameters.targetSampleRate, 2).use { writer ->
                frames = decode(input, parameters, writer, onProgress)
            }
            checkCancelled()
            val completed = if (encoded == null) temp else {
                onProgress(Progress(.97f, "Encoding $outputFormat"))
                val code = DsreNativeEncoder.transcodeWav(temp.absolutePath, encoded.absolutePath, outputFormat)
                if (code < 0) throw IOException(DsreNativeEncoder.lastError().ifBlank { "FFmpeg encoding failed: $code" })
                encoded
            }
            checkCancelled()
            onProgress(Progress(.99f, "Saving output"))
            context.contentResolver.openOutputStream(output, "w")?.use { dst ->
                completed.inputStream().buffered().use { src -> src.copyTo(dst, 1 shl 16) }
            } ?: throw IOException("Unable to open the output document")
            onProgress(Progress(1f, "Completed"))
            return Result(output, frames, parameters.targetSampleRate)
        } catch (e: Throwable) {
            runCatching { context.contentResolver.delete(output, null, null) }
            throw e
        } finally {
            temp.delete()
            encoded?.delete()
        }
    }

    private fun decode(input: Uri, p: DsreParameters, writer: WavWriter, onProgress: (Progress) -> Unit): Long {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            context.contentResolver.openAssetFileDescriptor(input, "r")?.use { afd ->
                extractor.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            } ?: throw IOException("Unable to open the input document")
            var track = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { track = i; format = f; break }
            }
            if (track < 0 || format == null) throw IOException("No audio track was found")
            extractor.selectTrack(track)
            val sourceFormat = format
            val mime = sourceFormat.getString(MediaFormat.KEY_MIME) ?: throw IOException("Missing audio MIME type")
            val durationUs = if (sourceFormat.containsKey(MediaFormat.KEY_DURATION)) sourceFormat.getLong(MediaFormat.KEY_DURATION) else 0L
            val decoder = MediaCodec.createDecoderByType(mime)
            codec = decoder
            decoder.configure(sourceFormat, null, null, 0)
            decoder.start()

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var sourceRate = sourceFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var sourceChannels = sourceFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
            var resampler: RationalResampler? = if (sourceRate != p.targetSampleRate) RationalResampler(sourceRate, p.targetSampleRate, 2, null, 32) else null
            var dsp: DsreDspEngine? = DsreDspEngine(p.targetSampleRate, 2, p)
            var outFrames = 0L
            var lastPts = 0L
            val timeoutUs = 10_000L

            while (!outputDone) {
                checkCancelled()
                if (!inputDone) {
                    val index = decoder.dequeueInputBuffer(timeoutUs)
                    if (index >= 0) {
                        val buffer = decoder.getInputBuffer(index) ?: throw IOException("Decoder input buffer unavailable")
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(index, 0, size, extractor.sampleTime, extractor.sampleFlags)
                            extractor.advance()
                        }
                    }
                }

                when (val index = decoder.dequeueOutputBuffer(info, timeoutUs)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outFormat = decoder.outputFormat
                        sourceRate = outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        sourceChannels = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        pcmEncoding = if (outFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) outFormat.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                        resampler = if (sourceRate != p.targetSampleRate) RationalResampler(sourceRate, p.targetSampleRate, 2, null, 32) else null
                        dsp = DsreDspEngine(p.targetSampleRate, 2, p)
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if (index >= 0) {
                        if (info.size > 0) {
                            val buffer = decoder.getOutputBuffer(index) ?: throw IOException("Decoder output buffer unavailable")
                            buffer.position(info.offset); buffer.limit(info.offset + info.size)
                            val interleaved = pcmToStereo(buffer.slice().order(ByteOrder.nativeOrder()), pcmEncoding, sourceChannels)
                            val sourceFrames = interleaved.size / 2
                            val converted = resampler?.let { rs ->
                                val dst = FloatArray(max(2, rs.maxOut(sourceFrames) * 2))
                                val count = rs.process(interleaved, sourceFrames, dst)
                                dst.copyOf(count * 2)
                            } ?: interleaved
                            val channelFirst = deinterleave(converted)
                            val processed = (dsp ?: DsreDspEngine(p.targetSampleRate, 2, p).also { dsp = it }).process(channelFirst)
                            writer.write(processed)
                            outFrames += processed[0].size
                            lastPts = info.presentationTimeUs
                            val fraction = if (durationUs > 0) (lastPts.toDouble() / durationUs).toFloat().coerceIn(0f, .96f) else 0f
                            onProgress(Progress(fraction, "Converting audio"))
                        }
                        outputDone = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        decoder.releaseOutputBuffer(index, false)
                    }
                }
            }
            resampler?.let { rs ->
                val zeros = FloatArray(rs.delayFrames * 2)
                val dst = FloatArray(max(2, rs.maxOut(rs.delayFrames) * 2))
                val count = rs.process(zeros, rs.delayFrames, dst)
                if (count > 0) {
                    val processed = (dsp ?: DsreDspEngine(p.targetSampleRate, 2, p)).process(deinterleave(dst.copyOf(count * 2)))
                    writer.write(processed)
                    outFrames += processed[0].size
                }
            }
            return outFrames
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }

    private fun pcmToStereo(buffer: ByteBuffer, encoding: Int, channels: Int): FloatArray {
        val bytesPerSample = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
        val frames = buffer.remaining() / max(1, bytesPerSample * channels)
        val out = FloatArray(frames * 2)
        fun sample(): Float = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) buffer.float else buffer.short / 32768f
        for (i in 0 until frames) {
            if (channels == 1) { val v = sample(); out[i * 2] = v; out[i * 2 + 1] = v }
            else {
                var left = sample(); var right = sample()
                for (ch in 2 until channels) { val v = sample() * .7071f; if (ch % 2 == 0) left += v else right += v }
                val scale = if (channels > 2) .5f else 1f
                out[i * 2] = left * scale; out[i * 2 + 1] = right * scale
            }
        }
        return out
    }

    private fun deinterleave(x: FloatArray): Array<FloatArray> {
        val frames = x.size / 2; val left = FloatArray(frames); val right = FloatArray(frames)
        for (i in 0 until frames) { left[i] = x[i * 2]; right[i] = x[i * 2 + 1] }
        return arrayOf(left, right)
    }

    private fun checkCancelled() { if (cancelled || Thread.currentThread().isInterrupted) throw InterruptedException("Conversion cancelled") }
}

private class WavWriter(private val file: File, private val sampleRate: Int, private val channels: Int) : AutoCloseable {
    private val out = BufferedOutputStream(file.outputStream(), 1 shl 16)
    private var dataBytes = 0L
    init { out.write(ByteArray(44)) }
    fun write(pcm: Array<FloatArray>) {
        val frames = pcm.firstOrNull()?.size ?: 0; if (frames == 0) return
        val bytes = ByteArray(frames * channels * 2); var o = 0
        for (i in 0 until frames) for (ch in 0 until channels) {
            val s = (pcm[ch][i].coerceIn(-1f, 1f) * 32767f).toInt()
            bytes[o++] = (s and 0xff).toByte(); bytes[o++] = ((s ushr 8) and 0xff).toByte()
        }
        out.write(bytes); dataBytes += bytes.size
    }
    override fun close() {
        out.flush(); out.close()
        RandomAccessFile(file, "rw").use { raf ->
            fun ascii(s: String) = raf.write(s.toByteArray(Charsets.US_ASCII))
            fun le16(v: Int) { raf.write(v and 0xff); raf.write((v ushr 8) and 0xff) }
            fun le32(v: Long) { repeat(4) { raf.write(((v ushr (it * 8)) and 0xff).toInt()) } }
            raf.seek(0); ascii("RIFF"); le32(36 + dataBytes); ascii("WAVEfmt "); le32(16); le16(1); le16(channels)
            le32(sampleRate.toLong()); le32(sampleRate.toLong() * channels * 2); le16(channels * 2); le16(16); ascii("data"); le32(dataBytes)
        }
    }
}
