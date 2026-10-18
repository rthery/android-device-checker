package io.github.rthery.devicechecker.interactive

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import io.github.rthery.devicechecker.checks.CheckStatus
import java.io.File
import kotlin.math.abs

/** Brightness within this fraction of the settled level counts as settled. */
private const val SETTLED_BAND = 0.10

/** A frame-to-frame change above this fraction of the settled level counts toward a swing. */
private const val SWING_STEP = 0.03
private const val MAX_SETTLE_MS = 2_000L
private const val MAX_SWINGS = 3
private const val PIXEL_STEP = 16
private const val DECODE_TIMEOUT_US = 10_000L
private const val DECODE_DEADLINE_MS = 20_000L

/**
 * Exposure over one clip, from the first frame: how long auto-exposure takes to settle, and how
 * often brightness swings back and forth afterwards (flicker or exposure hunting).
 */
internal data class Exposure(
    val firstLuma: Double,
    val settledLuma: Double,
    /** Null when brightness never settles within the clip. */
    val settleMs: Long?,
    val swings: Int,
    val durationMs: Long,
) {
    /** A clip shorter than the settle limit may end before exposure settles; that alone is no warning. */
    val status: CheckStatus
        get() = when {
            swings >= MAX_SWINGS -> CheckStatus.WARN
            settleMs == null -> if (durationMs > MAX_SETTLE_MS) CheckStatus.WARN else CheckStatus.PASS
            settleMs > MAX_SETTLE_MS -> CheckStatus.WARN
            else -> CheckStatus.PASS
        }

    fun describe(): String {
        val settle = settleMs?.let { "settles in ${"%.2f".format(it / 1000.0)} s" } ?: "never settles"
        return "exposure $settle (${firstLuma.toInt()} -> ${settledLuma.toInt()}), $swings brightness swing(s) after"
    }
}

/** Pure part of [clipExposure], on per-frame mean luma and presentation times. */
internal fun analyzeExposure(timesUs: List<Long>, lumas: List<Double>): Exposure? {
    val count = lumas.size
    if (count < 4 || timesUs.size != count) return null
    val tail = (count / 4).coerceIn(3, 10)
    val settled = lumas.takeLast(tail).average()
    val durationMs = (timesUs.last() - timesUs.first()) / 1000
    if (settled <= 0.0) return Exposure(lumas.first(), settled, null, 0, durationMs)
    val lastOutside = lumas.indexOfLast { abs(it - settled) > SETTLED_BAND * settled }
    val settleIndex = lastOutside + 1
    val settleMs = if (settleIndex >= count - tail) null else (timesUs[settleIndex] - timesUs.first()) / 1000
    var swings = 0
    for (i in maxOf(settleIndex, 1) + 1 until count) {
        val before = lumas[i - 1] - lumas[i - 2]
        val after = lumas[i] - lumas[i - 1]
        if (before * after < 0 && abs(after) > SWING_STEP * settled && abs(before) > SWING_STEP * settled) swings++
    }
    return Exposure(lumas.first(), settled, settleMs, swings, durationMs)
}

/** Decodes the clip's video track and measures [Exposure]; null when it cannot be decoded. */
internal fun clipExposure(file: File): Exposure? {
    val extractor = MediaExtractor()
    var codec: MediaCodec? = null
    try {
        extractor.setDataSource(file.absolutePath)
        val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty().startsWith("video/")
        } ?: return null
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        }
        val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!).also { codec = it }
        decoder.configure(format, null, null, 0)
        decoder.start()
        val times = mutableListOf<Long>()
        val lumas = mutableListOf<Double>()
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        val deadline = System.currentTimeMillis() + DECODE_DEADLINE_MS
        while (System.currentTimeMillis() < deadline) {
            if (!inputDone) {
                val input = decoder.dequeueInputBuffer(DECODE_TIMEOUT_US)
                if (input >= 0) {
                    val size = extractor.readSampleData(decoder.getInputBuffer(input)!!, 0)
                    if (size < 0) {
                        decoder.queueInputBuffer(input, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        decoder.queueInputBuffer(input, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val output = decoder.dequeueOutputBuffer(info, DECODE_TIMEOUT_US)
            if (output >= 0) {
                if (info.size > 0) {
                    decoder.getOutputImage(output)?.use { image ->
                        val plane = image.planes[0]
                        val buffer = plane.buffer
                        var sum = 0L
                        var samples = 0
                        for (y in 0 until image.height step PIXEL_STEP) {
                            for (x in 0 until image.width step PIXEL_STEP) {
                                sum += buffer.get(y * plane.rowStride + x * plane.pixelStride).toInt() and 0xFF
                                samples++
                            }
                        }
                        if (samples > 0) {
                            times += info.presentationTimeUs
                            lumas += sum.toDouble() / samples
                        }
                    }
                }
                decoder.releaseOutputBuffer(output, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }
        }
        return analyzeExposure(times, lumas)
    } catch (_: Exception) {
        return null
    } finally {
        runCatching { codec?.stop() }
        codec?.release()
        extractor.release()
    }
}
