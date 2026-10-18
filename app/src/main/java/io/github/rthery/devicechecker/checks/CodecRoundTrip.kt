package io.github.rthery.devicechecker.checks

import io.github.rthery.devicechecker.compat.Compat.isHardwareCompat
import io.github.rthery.devicechecker.compat.Compat.isAliasCompat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val WIDTH = 640
private const val HEIGHT = 480
private const val FRAMES = 20
private const val FRAME_RATE = 30
private const val BIT_RATE = 2_000_000
private const val DEQUEUE_TIMEOUT_US = 10_000L
private const val CODEC_TIMEOUT_MS = 10_000L

/** Vendor-specific or licensed formats the charter does not require. */
private val PROPRIETARY_MIMES = listOf("dolby-vision", "x-ms-", "divx", "wmv", "vc1", "x-ms", "hevc-dv")

private class Sample(val data: ByteArray, val presentationUs: Long, val flags: Int)

private class EncodedStream(val config: List<ByteArray>, val samples: List<Sample>)

val codecRoundTripCheck = Check("codecs.roundtrip", "Hardware encode and decode") {
    withContext(Dispatchers.IO) {
        val infos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isAliasCompat() }
        val video = infos.filter { info -> info.supportedTypes.any { it.startsWith("video/") && PROPRIETARY_MIMES.none(it::contains) } }
        val hwEncoders = video.filter { it.isEncoder && it.isHardwareCompat() }
        val hwDecoders = video.filter { !it.isEncoder && it.isHardwareCompat() && !it.name.endsWith(".secure") }
        val lines = mutableListOf<Pair<CheckStatus, String>>()

        val mimes = (hwEncoders + hwDecoders).flatMap { it.supportedTypes.toList() }.filter { it.startsWith("video/") && PROPRIETARY_MIMES.none(it::contains) }.distinct()
        for (mime in mimes) {
            val encodersForMime = video.filter { it.isEncoder && mime in it.supportedTypes }
            val hwEncoder = encodersForMime.firstOrNull { it in hwEncoders }
            val sourceEncoder = hwEncoder ?: encodersForMime.firstOrNull()
            val decoders = hwDecoders.filter { mime in it.supportedTypes }

            if (sourceEncoder == null) {
                lines += CheckStatus.WARN to "$mime: hardware decoder only, no encoder on the device to make a test stream"
                continue
            }
            val stream = runCatching { encode(sourceEncoder, mime) }
            val encoded = stream.getOrNull()
            if (hwEncoder != null) {
                lines += if (encoded != null && encoded.samples.isNotEmpty()) {
                    CheckStatus.PASS to "$mime enc ${hwEncoder.name}: ${encoded.samples.size} of $FRAMES frames (rate control may drop some)"
                } else {
                    CheckStatus.FAIL to "$mime enc ${hwEncoder.name}: ${stream.exceptionOrNull()?.message ?: "only ${encoded?.samples?.size ?: 0} frames"}"
                }
            }
            if (encoded == null || encoded.samples.isEmpty()) continue
            for (decoder in decoders) {
                val decoded = runCatching { decode(decoder, mime, encoded) }
                val count = decoded.getOrNull() ?: 0
                lines += if (count > 0 && count >= encoded.samples.size / 2) {
                    CheckStatus.PASS to "$mime dec ${decoder.name}: $count frames${if (hwEncoder == null) " (stream from ${sourceEncoder.name})" else ""}"
                } else {
                    CheckStatus.FAIL to "$mime dec ${decoder.name}: ${decoded.exceptionOrNull()?.message ?: "$count of ${encoded.samples.size} frames"}"
                }
            }
        }

        val details = lines.joinToString("\n") { (status, line) -> "${status.name}: $line" }
        val failed = lines.count { it.first == CheckStatus.FAIL }
        when {
            lines.isEmpty() -> warn("No hardware video codecs to test")
            failed > 0 -> fail("$failed hardware codec(s) failed the round trip", details)
            else -> pass("${lines.count { it.first == CheckStatus.PASS }} hardware encode/decode runs passed", details)
        }
    }
}

private fun encode(info: MediaCodecInfo, mime: String): EncodedStream {
    val caps = info.getCapabilitiesForType(mime)
    require(caps.videoCapabilities?.isSizeSupported(WIDTH, HEIGHT) != false) { "${WIDTH}x$HEIGHT not supported" }
    val format = MediaFormat.createVideoFormat(mime, WIDTH, HEIGHT).apply {
        setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
        setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
    }
    val codec = MediaCodec.createByCodecName(info.name)
    val config = mutableListOf<ByteArray>()
    val samples = mutableListOf<Sample>()
    try {
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        var queued = 0
        var inputDone = false
        val deadline = System.currentTimeMillis() + CODEC_TIMEOUT_MS
        val bufferInfo = MediaCodec.BufferInfo()
        while (System.currentTimeMillis() < deadline) {
            if (!inputDone) {
                val index = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                if (index >= 0) {
                    val presentationUs = queued * 1_000_000L / FRAME_RATE
                    if (queued == FRAMES) {
                        codec.queueInputBuffer(index, 0, 0, presentationUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.getInputImage(index)?.let { fillFrame(it, queued) }
                        codec.queueInputBuffer(index, 0, WIDTH * HEIGHT * 3 / 2, presentationUs, 0)
                        queued++
                    }
                }
            }
            val out = codec.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)
            if (out >= 0) {
                val buffer = codec.getOutputBuffer(out)!!
                val bytes = ByteArray(bufferInfo.size).also { buffer.position(bufferInfo.offset); buffer.get(it) }
                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) config += bytes
                else if (bytes.isNotEmpty()) samples += Sample(bytes, bufferInfo.presentationTimeUs, bufferInfo.flags)
                codec.releaseOutputBuffer(out, false)
                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }
        }
    } finally {
        runCatching { codec.stop() }
        codec.release()
    }
    return EncodedStream(config, samples)
}

private fun fillFrame(image: android.media.Image, frame: Int) {
    val (y, u, v) = image.planes
    for (row in 0 until HEIGHT) {
        for (col in 0 until WIDTH) {
            y.buffer.put(row * y.rowStride + col * y.pixelStride, ((col + row + frame * 8) and 0xFF).toByte())
        }
    }
    for (row in 0 until HEIGHT / 2) {
        for (col in 0 until WIDTH / 2) {
            u.buffer.put(row * u.rowStride + col * u.pixelStride, (128 + frame).toByte())
            v.buffer.put(row * v.rowStride + col * v.pixelStride, (128 - frame).toByte())
        }
    }
}

private fun decode(info: MediaCodecInfo, mime: String, stream: EncodedStream): Int {
    val codec = MediaCodec.createByCodecName(info.name)
    var decoded = 0
    try {
        codec.configure(MediaFormat.createVideoFormat(mime, WIDTH, HEIGHT), null, null, 0)
        codec.start()
        val inputs = stream.config.map { Sample(it, 0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG) } + stream.samples
        var next = 0
        var inputDone = false
        val deadline = System.currentTimeMillis() + CODEC_TIMEOUT_MS
        val bufferInfo = MediaCodec.BufferInfo()
        while (System.currentTimeMillis() < deadline) {
            if (!inputDone) {
                val index = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                if (index >= 0) {
                    if (next == inputs.size) {
                        codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        val sample = inputs[next++]
                        codec.getInputBuffer(index)!!.apply { clear(); put(sample.data) }
                        val flags = sample.flags and (MediaCodec.BUFFER_FLAG_CODEC_CONFIG or MediaCodec.BUFFER_FLAG_KEY_FRAME)
                        codec.queueInputBuffer(index, 0, sample.data.size, sample.presentationUs, flags)
                    }
                }
            }
            val out = codec.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)
            if (out >= 0) {
                if (bufferInfo.size > 0) decoded++
                codec.releaseOutputBuffer(out, false)
                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }
        }
    } finally {
        runCatching { codec.stop() }
        codec.release()
    }
    return decoded
}
