package io.github.rthery.devicechecker.interactive

import io.github.rthery.devicechecker.compat.Compat
import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.ImageReader
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaRecorder
import android.util.Size
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.rthery.devicechecker.R
import io.github.rthery.devicechecker.checks.CheckStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

private const val FIRST_FRAME_TIMEOUT_MS = 2_000L
private const val STEP_TIMEOUT_MS = 12_000L
private const val MIN_GAP_MS = 100L
private const val MAX_GAP_MS = 800L
/** Audio may start this much after video (encoder startup, about 150 ms on the Nexus 5X). */
private const val MAX_AUDIO_GAP_MS = 300L
private const val SAMPLE_RATE = 48_000
private const val MAX_FIRST_READ_MS = 500L
private val ITERATION_CHOICES = listOf(10, 30, 60)

val stressTests = listOf(
    InteractiveTest(
        "camera.stress", "Camera switching stress",
        "Switches cameras and photo/video modes quickly with random gaps, then reopens every camera. After the run it measures exposure " +
            "settle time and brightness swings (flicker) in every clip; clips shorter than 2 s may end before exposure settles.",
    ) { CameraStressTest(it) },
    InteractiveTest(
        "audio.stress", "Audio churn stress",
        "Starts and stops playback and recording quickly while switching outputs, and times the first mic buffer.",
    ) { AudioStressTest(it) },
)

/** Random order, but every block of entries.size steps runs each action once, so short runs still cover all of them. */
private fun <T> shuffledBlocks(entries: List<T>, count: Int): List<T> =
    generateSequence { entries.shuffled() }.flatten().take(count).toList()

/** Reports each line on the main thread; step is 1-based, 0 for the final reopen pass. */
internal suspend fun runCameraStress(context: Context, iterations: Int, onLine: (CheckStatus, String, Int) -> Unit) {
    val manager = context.getSystemService(CameraManager::class.java)
    val ids = manager.cameraIdList.toList()
    suspend fun report(status: CheckStatus, entry: String, step: Int) = withContext(Dispatchers.Main) { onLine(status, entry, step) }
    withContext(Dispatchers.IO) {
        CameraThread().use { thread ->
            val actions = shuffledBlocks(CameraAction.entries, iterations)
            val clips = mutableListOf<Pair<String, File>>()
            repeat(iterations) { step ->
                val id = ids.random()
                val action = actions[step]
                val keep = { file: File ->
                    val kept = File(context.cacheDir, "stress_keep_${step + 1}.mp4")
                    if (file.renameTo(kept)) clips += "#${step + 1} camera $id $action" to kept
                }
                val line = runCatching { withTimeout(STEP_TIMEOUT_MS) { cameraStep(context, manager, id, action, thread, keep) } }
                report(if (line.isSuccess) CheckStatus.PASS else CheckStatus.FAIL, "#${step + 1} camera $id $action: ${line.getOrElse { "FAILED ${it.message}" }}", step + 1)
                delay(Random.nextLong(MIN_GAP_MS, MAX_GAP_MS))
            }
            clips.forEach { (label, file) ->
                val exposure = clipExposure(file)
                file.delete()
                report(exposure?.status ?: CheckStatus.WARN, "$label exposure: ${exposure?.describe() ?: "not measured"}", 0)
            }
            val idsAfter = manager.cameraIdList.toList()
            if (idsAfter != ids) report(CheckStatus.FAIL, "Camera list changed: $ids -> $idsAfter", 0)
            idsAfter.forEach { id ->
                val line = runCatching { withTimeout(STEP_TIMEOUT_MS) { cameraStep(context, manager, id, CameraAction.PHOTO, thread) } }
                report(if (line.isSuccess) CheckStatus.PASS else CheckStatus.FAIL, "Reopen camera $id after stress: ${line.getOrElse { "FAILED ${it.message}" }}", 0)
            }
        }
    }
}

internal suspend fun runAudioStress(audio: AudioManager, iterations: Int, onLine: (CheckStatus, String, Int) -> Unit) {
    val actions = shuffledBlocks(AudioAction.entries, iterations)
    repeat(iterations) { step ->
        val line = runCatching { withTimeout(STEP_TIMEOUT_MS) { audioStep(audio, actions[step]) } }
        withContext(Dispatchers.Main) {
            onLine(if (line.isSuccess) CheckStatus.PASS else CheckStatus.FAIL, "#${step + 1} ${actions[step]}: ${line.getOrElse { "FAILED ${it.message}" }}", step + 1)
        }
        delay(Random.nextLong(MIN_GAP_MS, MAX_GAP_MS))
    }
}

/** MODE_SWITCH keeps the camera open and reconfigures photo -> video -> photo, like a camera app switching modes. */
private enum class CameraAction { PHOTO, VIDEO, CLOSE_WHILE_RECORDING, AF_CHURN, MODE_SWITCH }

/** Camera opened with a callback that also records errors raised after the open. */
private class OpenedCamera(val device: CameraDevice, val lateErrors: MutableList<String>)

@SuppressLint("MissingPermission")
private suspend fun CameraManager.openTracked(id: String, thread: CameraThread): OpenedCamera = suspendCancellableCoroutine { cont ->
    val lateErrors = mutableListOf<String>()
    openCamera(id, object : CameraDevice.StateCallback() {
        override fun onOpened(device: CameraDevice) {
            if (cont.isActive) cont.resume(OpenedCamera(device, lateErrors)) else device.close()
        }

        override fun onDisconnected(device: CameraDevice) {
            device.close()
            if (cont.isActive) cont.resumeWithException(IllegalStateException("disconnected while opening")) else lateErrors += "onDisconnected"
        }

        override fun onError(device: CameraDevice, error: Int) {
            device.close()
            val name = cameraErrorName(error)
            if (cont.isActive) cont.resumeWithException(IllegalStateException("onError $name while opening")) else lateErrors += "onError $name"
        }
    }, thread.handler)
}

private fun cameraErrorName(error: Int) = when (error) {
    CameraDevice.StateCallback.ERROR_CAMERA_IN_USE -> "CAMERA_IN_USE"
    CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE -> "MAX_CAMERAS_IN_USE"
    CameraDevice.StateCallback.ERROR_CAMERA_DISABLED -> "CAMERA_DISABLED"
    CameraDevice.StateCallback.ERROR_CAMERA_DEVICE -> "CAMERA_DEVICE"
    CameraDevice.StateCallback.ERROR_CAMERA_SERVICE -> "CAMERA_SERVICE"
    else -> "error $error"
}

private fun CameraManager.sizesFor(id: String, format: Int): Array<Size>? =
    getCameraCharacteristics(id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(format)

private fun Array<Size>.closestTo(width: Int): Size = minBy { abs(it.width - width) }

private suspend fun awaitImage(reader: ImageReader, thread: CameraThread, timeoutMs: Long): Boolean =
    withTimeoutOrNull(timeoutMs) {
        suspendCancellableCoroutine { cont ->
            reader.setOnImageAvailableListener({ r ->
                r.acquireLatestImage()?.close()
                if (cont.isActive) cont.resume(true)
            }, thread.handler)
        }
    } ?: false

/**
 * One camera step. Returns a short outcome line; throws on failure.
 * [keep] takes over a recorded clip so its exposure can be measured after the run, off the timed steps.
 */
private suspend fun cameraStep(
    context: Context,
    manager: CameraManager,
    id: String,
    action: CameraAction,
    thread: CameraThread,
    keep: (File) -> Unit = {},
): String {
    val started = System.currentTimeMillis()
    val opened = manager.openTracked(id, thread)
    val openMs = System.currentTimeMillis() - started
    val device = opened.device
    val file = File(context.cacheDir, "stress_$id.mp4")
    var recorder: MediaRecorder? = null
    try {
        val previewSize = manager.sizesFor(id, ImageFormat.YUV_420_888)?.closestTo(640) ?: error("no YUV output")
        val preview = ImageReader.newInstance(previewSize.width, previewSize.height, ImageFormat.YUV_420_888, 4)
        val jpeg = manager.sizesFor(id, ImageFormat.JPEG)?.closestTo(1280)?.let { ImageReader.newInstance(it.width, it.height, ImageFormat.JPEG, 2) }
        val videoSize = manager.getCameraCharacteristics(id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(MediaRecorder::class.java)
            ?.filter { it.width <= 1920 && it.height <= 1080 }?.maxByOrNull { it.width * it.height }
        if (action == CameraAction.MODE_SWITCH) return modeSwitch(context, opened, thread, preview, jpeg ?: error("no JPEG output"), videoSize ?: error("no video size"), file, keep) + ", open $openMs ms"
        val recording = action == CameraAction.VIDEO || action == CameraAction.CLOSE_WHILE_RECORDING
        if (recording) {
            val size = videoSize ?: error("no video size")
            recorder = Compat.newMediaRecorder(context).apply {
                setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setOutputFile(file)
                setVideoEncodingBitRate(8_000_000)
                setVideoFrameRate(30)
                setVideoSize(size.width, size.height)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                prepare()
            }
        }
        val targets = listOfNotNull(preview.surface, recorder?.surface ?: jpeg?.surface)
        val session = device.session(targets, thread)
        val template = if (recording) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW
        val repeating = device.createCaptureRequest(template).apply { targets.filter { it != jpeg?.surface }.forEach(::addTarget) }
        session.setRepeatingRequest(repeating.build(), null, thread.handler)
        if (!awaitImage(preview, thread, FIRST_FRAME_TIMEOUT_MS)) error("no preview frame within ${FIRST_FRAME_TIMEOUT_MS} ms")
        val outcome = when (action) {
            CameraAction.PHOTO -> {
                val reader = jpeg ?: error("no JPEG output")
                val still = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply { addTarget(reader.surface) }.build()
                session.capture(still, null, thread.handler)
                if (!awaitImage(reader, thread, FIRST_FRAME_TIMEOUT_MS * 2)) error("JPEG never arrived")
                "photo"
            }
            CameraAction.VIDEO -> {
                val lengthMs = Random.nextLong(500, 3_000)
                recorder!!.start()
                delay(lengthMs)
                recorder.stop()
                session.close()
                "video ${lengthMs} ms: ${inspectClipTracks(file)}".also { keep(file) }
            }
            CameraAction.CLOSE_WHILE_RECORDING -> {
                recorder!!.start()
                delay(Random.nextLong(200, 800))
                device.close()
                runCatching { recorder.stop() }
                "closed while recording"
            }
            CameraAction.MODE_SWITCH -> error("unreachable")
            CameraAction.AF_CHURN -> {
                val modes = manager.getCameraCharacteristics(id).get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.toList().orEmpty()
                if (modes.size <= 1) return "AF churn skipped (fixed focus), open ${openMs} ms"
                repeat(12) {
                    val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(preview.surface)
                        set(CaptureRequest.CONTROL_AF_MODE, modes.random())
                        set(CaptureRequest.CONTROL_AF_TRIGGER, if (it % 2 == 0) CaptureRequest.CONTROL_AF_TRIGGER_START else CaptureRequest.CONTROL_AF_TRIGGER_CANCEL)
                    }.build()
                    session.capture(request, null, thread.handler)
                    delay(Random.nextLong(20, 120))
                }
                "AF churn"
            }
        }
        if (opened.lateErrors.isNotEmpty()) error("camera reported ${opened.lateErrors.joinToString()}")
        preview.close()
        jpeg?.close()
        return "$outcome, open ${openMs} ms"
    } finally {
        runCatching { device.close() }
        recorder?.release()
        file.delete()
    }
}

private suspend fun modeSwitch(
    context: Context,
    opened: OpenedCamera,
    thread: CameraThread,
    preview: ImageReader,
    jpeg: ImageReader,
    videoSize: Size,
    file: File,
    keep: (File) -> Unit,
): String {
    val device = opened.device
    suspend fun photoSession() {
        val session = device.session(listOf(preview.surface, jpeg.surface), thread)
        session.setRepeatingRequest(device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(preview.surface) }.build(), null, thread.handler)
        if (!awaitImage(preview, thread, FIRST_FRAME_TIMEOUT_MS)) error("no preview frame in photo mode")
        session.capture(device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply { addTarget(jpeg.surface) }.build(), null, thread.handler)
        if (!awaitImage(jpeg, thread, FIRST_FRAME_TIMEOUT_MS * 2)) error("JPEG never arrived in photo mode")
        session.close()
    }
    photoSession()
    val recorder = Compat.newMediaRecorder(context).apply {
        setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
        setVideoSource(MediaRecorder.VideoSource.SURFACE)
        setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        setOutputFile(file)
        setVideoEncodingBitRate(8_000_000)
        setVideoFrameRate(30)
        setVideoSize(videoSize.width, videoSize.height)
        setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        prepare()
    }
    val clip = try {
        val session = device.session(listOf(preview.surface, recorder.surface), thread)
        session.setRepeatingRequest(device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply { addTarget(preview.surface); addTarget(recorder.surface) }.build(), null, thread.handler)
        if (!awaitImage(preview, thread, FIRST_FRAME_TIMEOUT_MS)) error("no preview frame after switching to video")
        recorder.start()
        delay(Random.nextLong(800, 2_000))
        recorder.stop()
        session.close()
        inspectClipTracks(file).also { keep(file) }
    } finally {
        recorder.release()
    }
    photoSession()
    if (opened.lateErrors.isNotEmpty()) error("camera reported ${opened.lateErrors.joinToString()}")
    return "photo -> video ($clip) -> photo on one open camera"
}

/** Checks the clip has video and audio, with audio missing from at most [MAX_AUDIO_GAP_MS] of the video (the "no sound on short clips" bug). */
internal fun inspectClipTracks(file: File): String {
    val extractor = MediaExtractor()
    try {
        extractor.setDataSource(file.absolutePath)
        var videoUs = 0L
        var audioUs = 0L
        var audioSamples = 0
        for (track in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
            val duration = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0
            if (mime.startsWith("video/")) videoUs = duration
            if (mime.startsWith("audio/")) {
                audioUs = duration
                extractor.selectTrack(track)
                while (extractor.advance()) audioSamples++
                extractor.unselectTrack(track)
            }
        }
        check(videoUs > 0) { "no video track" }
        check(audioUs > 0 && audioSamples > 0) { "no audio in a ${videoUs / 1000} ms clip" }
        check(videoUs - audioUs <= MAX_AUDIO_GAP_MS * 1000) { "audio ${audioUs / 1000} ms for ${videoUs / 1000} ms of video, more than $MAX_AUDIO_GAP_MS ms short" }
        return "video ${videoUs / 1000} ms, audio ${audioUs / 1000} ms"
    } finally {
        extractor.release()
    }
}

@Composable
private fun CameraStressTest(scope: TestScope) {
    RequirePermissions(listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO), stringResource(R.string.stress_camera_permission)) {
        CameraStressContent(scope)
    }
}

@Composable
private fun CameraStressContent(scope: TestScope) {
    val context = scope.activity
    val manager = remember { context.getSystemService(CameraManager::class.java) }
    var iterations by remember { mutableIntStateOf(ITERATION_CHOICES[1]) }
    var done by remember { mutableIntStateOf(0) }
    var running by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }
    val results = remember { ItemResults() }
    val log = remember { mutableStateListOf<String>() }
    val coroutines = rememberCoroutineScope()

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Instructions(stringResource(R.string.stress_camera_instructions))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ITERATION_CHOICES.forEach { count ->
                FilterChip(selected = iterations == count, enabled = !running, onClick = { iterations = count }, label = { Text(pluralStringResource(R.plurals.stress_steps, count, count)) })
            }
        }
        Button(enabled = !running, onClick = {
            coroutines.launch {
                running = true
                finished = false
                done = 0
                results.lines.clear()
                log.clear()
                runCameraStress(context, iterations) { status, entry, step ->
                    results.add(status, entry)
                    log.add(0, entry)
                    if (step > 0) done = step
                }
                running = false
                finished = true
            }
        }) { Text(if (running) stringResource(R.string.stress_running, done, iterations) else stringResource(R.string.stress_start)) }
        if (running) LinearProgressIndicator(progress = { done.toFloat() / iterations }, modifier = Modifier.fillMaxWidth())
        val failures = results.lines.count { it.first == CheckStatus.FAIL }
        if (log.isNotEmpty()) Text(pluralStringResource(R.plurals.stress_failures_so_far, failures, failures), color = if (failures > 0) MaterialTheme.colorScheme.error else Color.Unspecified)
        log.take(12).forEach { Mono(it) }
        if (finished) {
            Button(onClick = { scope.finish(results.toResult("$iterations steps and a reopen of every camera without errors", "No steps ran")) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.save_result))
            }
        }
    }
}

@Composable
private fun AudioStressTest(scope: TestScope) {
    RequirePermissions(listOf(Manifest.permission.RECORD_AUDIO), stringResource(R.string.stress_audio_permission)) {
        AudioStressContent(scope)
    }
}

private enum class AudioAction { PLAY_SPEAKER, PLAY_EARPIECE, RECORD, PLAY_AND_RECORD, START_STOP_BURST }

@SuppressLint("MissingPermission")
private suspend fun audioStep(audio: AudioManager, action: AudioAction): String = withContext(Dispatchers.IO) {
    fun output(type: Int) = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type == type }
    fun track(usage: Int): AudioTrack {
        val samples = ShortArray(SAMPLE_RATE / 4) { i -> (sin(2 * PI * 660.0 * i / SAMPLE_RATE) * 6000).toInt().toShort() }
        return AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(usage).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(SAMPLE_RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(samples.size * 2)
            .build().apply { check(write(samples, 0, samples.size) > 0) { "AudioTrack write failed (dead object?)" } }
    }
    suspend fun record(ms: Long): Long {
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val recorder = AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuffer * 2)
        try {
            check(recorder.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord not initialized" }
            val buffer = ShortArray(minBuffer / 2)
            val started = System.currentTimeMillis()
            recorder.startRecording()
            val first = recorder.read(buffer, 0, buffer.size)
            val firstMs = System.currentTimeMillis() - started
            check(first > 0) { "AudioRecord read returned $first" }
            check(firstMs <= MAX_FIRST_READ_MS) { "first mic buffer took $firstMs ms" }
            val until = started + ms
            while (System.currentTimeMillis() < until) {
                val read = recorder.read(buffer, 0, buffer.size)
                check(read > 0) { "AudioRecord read returned $read" }
            }
            return firstMs
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
        }
    }
    when (action) {
        AudioAction.PLAY_SPEAKER -> track(AudioAttributes.USAGE_MEDIA).run {
            output(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)?.let(::setPreferredDevice)
            play(); delay(250); stop(); release()
            "speaker"
        }
        AudioAction.PLAY_EARPIECE -> {
            val earpiece = output(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE) ?: return@withContext "earpiece skipped (none)"
            val unroute = Compat.routeCommunication(audio, earpiece)
            try {
                track(AudioAttributes.USAGE_VOICE_COMMUNICATION).run { play(); delay(250); stop(); release() }
            } finally {
                unroute()
            }
            "earpiece"
        }
        AudioAction.RECORD -> "record, first buffer ${record(400)} ms"
        AudioAction.PLAY_AND_RECORD -> {
            val player = track(AudioAttributes.USAGE_MEDIA).apply { play() }
            try {
                "play+record, first buffer ${record(300)} ms"
            } finally {
                player.stop(); player.release()
            }
        }
        AudioAction.START_STOP_BURST -> {
            val player = track(AudioAttributes.USAGE_MEDIA)
            repeat(10) { player.play(); delay(15); player.pause(); player.flush() }
            player.release()
            "start/stop burst"
        }
    }
}

@Composable
private fun AudioStressContent(scope: TestScope) {
    val audio = remember { scope.activity.getSystemService(AudioManager::class.java) }
    var iterations by remember { mutableIntStateOf(ITERATION_CHOICES[1]) }
    var done by remember { mutableIntStateOf(0) }
    var running by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }
    val results = remember { ItemResults() }
    val log = remember { mutableStateListOf<String>() }
    val coroutines = rememberCoroutineScope()

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Instructions(stringResource(R.string.stress_audio_instructions, MAX_FIRST_READ_MS.toInt()))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ITERATION_CHOICES.forEach { count ->
                FilterChip(selected = iterations == count, enabled = !running, onClick = { iterations = count }, label = { Text(pluralStringResource(R.plurals.stress_steps, count, count)) })
            }
        }
        Button(enabled = !running, onClick = {
            coroutines.launch {
                running = true
                finished = false
                done = 0
                results.lines.clear()
                log.clear()
                runAudioStress(audio, iterations) { status, entry, step ->
                    results.add(status, entry)
                    log.add(0, entry)
                    done = step
                }
                running = false
                finished = true
            }
        }) { Text(if (running) stringResource(R.string.stress_running, done, iterations) else stringResource(R.string.stress_start)) }
        if (running) LinearProgressIndicator(progress = { done.toFloat() / iterations }, modifier = Modifier.fillMaxWidth())
        log.take(12).forEach { Mono(it) }
        if (finished) {
            Button(onClick = { scope.finish(results.toResult("$iterations audio steps without errors", "No steps ran")) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.save_result))
            }
        }
    }
}
