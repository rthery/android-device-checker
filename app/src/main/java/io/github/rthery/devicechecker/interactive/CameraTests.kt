package io.github.rthery.devicechecker.interactive

import io.github.rthery.devicechecker.compat.Compat
import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.ExifInterface
import android.media.ImageReader
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import android.widget.VideoView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import io.github.rthery.devicechecker.checks.CheckStatus
import io.github.rthery.devicechecker.checks.fail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.atan2
import kotlin.math.sqrt

private const val HIDDEN_CAMERA_PROBE_MAX_ID = 15
private const val PREVIEW_WARMUP_MS = 1_200L
private const val GRAVITY_TIMEOUT_MS = 500L
private const val CAMERA_TIMEOUT_MS = 10_000L
private const val VIDEO_MS = 3_000L
private const val MIN_VIDEO_DURATION_MS = 2_000L
private const val UNIFORM_STDDEV = 6.0
private const val DARK_MEAN = 12.0
private const val THUMBNAIL_PX = 240

val cameraTests = listOf(
    InteractiveTest("camera.capture", "Photo on every camera", "Opens each camera, takes a photo and checks it is not blank.") { PhotoTest(it) },
    InteractiveTest(
        "video.record", "Video recording",
        "Records a short clip on the first rear and front camera from the moment the camera opens, checks the file, and measures how long " +
            "exposure takes to settle and how often brightness swings afterwards (flicker). To compare with stock, film the same scene in the same light on both.",
    ) { VideoTest(it) },
)

class CameraTarget(val id: String, val facing: Int?, val hidden: Boolean) {
    val label: String
        get() {
            val side = when (facing) {
                CameraCharacteristics.LENS_FACING_BACK -> "rear"
                CameraCharacteristics.LENS_FACING_FRONT -> "front"
                CameraCharacteristics.LENS_FACING_EXTERNAL -> "external"
                else -> "unknown"
            }
            return "Camera $id ($side${if (hidden) ", hidden" else ""})"
        }
}

/** Listed ids plus ids the HAL exposes without listing them, which LineageOS uses for auxiliary cameras. */
fun discoverCameras(manager: CameraManager): List<CameraTarget> {
    val listed = manager.cameraIdList.toList()
    val hidden = (0..HIDDEN_CAMERA_PROBE_MAX_ID).map(Int::toString).filter { id ->
        id !in listed && runCatching { manager.getCameraCharacteristics(id) }.isSuccess
    }
    return (listed + hidden).map { id ->
        CameraTarget(id, manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING), id in hidden)
    }
}

class CameraThread : AutoCloseable {
    private val thread = HandlerThread("camera-test").apply { start() }
    val handler = Handler(thread.looper)
    val executor = Executor { handler.post(it) }
    override fun close() {
        thread.quitSafely()
    }
}

@SuppressLint("MissingPermission")
suspend fun CameraManager.open(id: String, thread: CameraThread): CameraDevice = suspendCancellableCoroutine { cont ->
    openCamera(id, object : CameraDevice.StateCallback() {
        override fun onOpened(device: CameraDevice) {
            if (cont.isActive) cont.resume(device) else device.close()
        }

        override fun onDisconnected(device: CameraDevice) {
            device.close()
            if (cont.isActive) cont.resumeWithException(IllegalStateException("Camera disconnected"))
        }

        override fun onError(device: CameraDevice, error: Int) {
            device.close()
            if (cont.isActive) cont.resumeWithException(IllegalStateException("Camera error $error"))
        }
    }, thread.handler)
}

suspend fun CameraDevice.session(surfaces: List<Surface>, thread: CameraThread): CameraCaptureSession = suspendCancellableCoroutine { cont ->
    Compat.createSession(this, surfaces, thread.handler, thread.executor, object : CameraCaptureSession.StateCallback() {
        override fun onConfigured(session: CameraCaptureSession) = cont.resume(session)
        override fun onConfigureFailed(session: CameraCaptureSession) = cont.resumeWithException(IllegalStateException("Session configuration failed"))
    })
}

private fun Array<Size>.smallestAtLeast(width: Int): Size? =
    filter { it.width >= width }.minByOrNull { it.width * it.height } ?: maxByOrNull { it.width * it.height }

internal class Photo(
    val target: CameraTarget,
    val thumbnail: Bitmap?,
    val status: CheckStatus,
    val note: String,
    val jpeg: ByteArray? = null,
    /** Clockwise degrees that turn [jpeg] upright, for its EXIF orientation. */
    val rotation: Int = 0,
)

/** Device rotation from gravity, as OrientationEventListener computes it; 0 when flat or unknown. */
internal suspend fun deviceRotation(context: Context): Int {
    val sensors = context.getSystemService(SensorManager::class.java)
    val accelerometer = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return 0
    val gravity = withTimeoutOrNull(GRAVITY_TIMEOUT_MS) {
        suspendCancellableCoroutine<FloatArray> { cont ->
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    sensors.unregisterListener(this)
                    if (cont.isActive) cont.resume(event.values.copyOf())
                }
                override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
            }
            sensors.registerListener(listener, accelerometer, SensorManager.SENSOR_DELAY_UI)
            cont.invokeOnCancellation { sensors.unregisterListener(listener) }
        }
    } ?: return 0
    val x = -gravity[0]
    val y = -gravity[1]
    val z = -gravity[2]
    if ((x * x + y * y) * 4 < z * z) return 0
    val degrees = (90 - Math.round(Math.toDegrees(atan2(-y, x).toDouble())).toInt() + 360) % 360
    return (degrees + 45) / 90 * 90 % 360
}

/** The rotation camera2 documents for JPEG_ORIENTATION. */
internal fun jpegRotation(sensorOrientation: Int, front: Boolean, device: Int): Int =
    if (front) (sensorOrientation - device + 360) % 360 else (sensorOrientation + device) % 360

/** Lossless: tags the file instead of re-encoding it, so it stays comparable with stock. */
internal fun writeExifRotation(file: File, rotation: Int) {
    val tag = when (rotation) {
        90 -> ExifInterface.ORIENTATION_ROTATE_90
        180 -> ExifInterface.ORIENTATION_ROTATE_180
        270 -> ExifInterface.ORIENTATION_ROTATE_270
        else -> return
    }
    ExifInterface(file.path).apply { setAttribute(ExifInterface.TAG_ORIENTATION, tag.toString()) }.saveAttributes()
}

internal suspend fun takePhoto(context: Context, target: CameraTarget): Photo = withContext(Dispatchers.IO) {
    val manager = context.getSystemService(CameraManager::class.java)
    CameraThread().use { thread ->
        try {
            withTimeout(CAMERA_TIMEOUT_MS) {
                val map = manager.getCameraCharacteristics(target.id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    ?: return@withTimeout Photo(target, null, CheckStatus.FAIL, "No stream configuration")
                val jpegSize = map.getOutputSizes(ImageFormat.JPEG)?.smallestAtLeast(640)
                    ?: return@withTimeout Photo(target, null, CheckStatus.FAIL, "No JPEG output")
                val previewSize = map.getOutputSizes(ImageFormat.YUV_420_888)?.smallestAtLeast(320)
                val jpegReader = ImageReader.newInstance(jpegSize.width, jpegSize.height, ImageFormat.JPEG, 2)
                val previewReader = previewSize?.let { ImageReader.newInstance(it.width, it.height, ImageFormat.YUV_420_888, 3) }
                previewReader?.setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, thread.handler)
                val device = manager.open(target.id, thread)
                try {
                    val session = device.session(listOfNotNull(jpegReader.surface, previewReader?.surface), thread)
                    previewReader?.let { reader ->
                        val preview = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(reader.surface) }.build()
                        session.setRepeatingRequest(preview, null, thread.handler)
                        delay(PREVIEW_WARMUP_MS)
                    }
                    val bytes = suspendCancellableCoroutine { cont ->
                        jpegReader.setOnImageAvailableListener({ reader ->
                            reader.acquireLatestImage()?.use { image ->
                                val buffer = image.planes[0].buffer
                                val data = ByteArray(buffer.remaining()).also(buffer::get)
                                if (cont.isActive) cont.resume(data)
                            }
                        }, thread.handler)
                        val still = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                            addTarget(jpegReader.surface)
                            set(CaptureRequest.JPEG_QUALITY, 85.toByte())
                        }.build()
                        session.capture(still, null, thread.handler)
                    }
                    session.close()
                    val characteristics = manager.getCameraCharacteristics(target.id)
                    val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
                    val front = characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
                    analyzePhoto(target, bytes, jpegSize, jpegRotation(sensorOrientation, front, deviceRotation(context)))
                } finally {
                    device.close()
                    jpegReader.close()
                    previewReader?.close()
                }
            }
        } catch (e: Exception) {
            Photo(target, null, CheckStatus.FAIL, "${e::class.java.simpleName}: ${e.message}")
        }
    }
}

private fun analyzePhoto(target: CameraTarget, bytes: ByteArray, size: Size, rotation: Int): Photo {
    val sample = maxOf(1, size.width / THUMBNAIL_PX)
    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        ?: return Photo(target, null, CheckStatus.FAIL, "JPEG does not decode (${bytes.size} bytes)")
    val bitmap = if (rotation == 0) decoded
    else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
    val pixels = IntArray(bitmap.width * bitmap.height).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) }
    val luma = pixels.map { 0.299 * (it shr 16 and 0xFF) + 0.587 * (it shr 8 and 0xFF) + 0.114 * (it and 0xFF) }
    val mean = luma.average()
    val stddev = sqrt(luma.sumOf { (it - mean) * (it - mean) } / luma.size)
    val stats = "${size.width}x${size.height}, mean ${"%.0f".format(mean)}, contrast ${"%.0f".format(stddev)}"
    return when {
        mean < DARK_MEAN -> Photo(target, bitmap, CheckStatus.WARN, "Almost black ($stats). Lens covered or sensor dead?", bytes, rotation)
        stddev < UNIFORM_STDDEV -> Photo(target, bitmap, CheckStatus.WARN, "Uniform image ($stats). Point at something with detail.", bytes, rotation)
        else -> Photo(target, bitmap, CheckStatus.PASS, stats, bytes, rotation)
    }
}

@Composable
private fun PhotoTest(scope: TestScope) {
    RequirePermissions(listOf(Manifest.permission.CAMERA), "Taking photos needs the camera permission.") {
        PhotoContent(scope)
    }
}

@Composable
private fun PhotoContent(scope: TestScope) {
    val context = scope.activity
    val targets = remember { discoverCameras(context.getSystemService(CameraManager::class.java)) }
    val photos = remember { mutableStateListOf<Photo>() }
    var running by remember { mutableStateOf(false) }
    val coroutines = rememberCoroutineScope()

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Instructions("Point the phone at something with detail and good light, then start. Each of the ${targets.size} camera(s) takes one photo.")
        Button(enabled = !running, onClick = {
            coroutines.launch {
                running = true
                photos.clear()
                targets.forEach { photos += takePhoto(context, it) }
                running = false
            }
        }) { Text(if (running) "Capturing ${photos.size + 1}/${targets.size}..." else "Take photos") }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            photos.forEach { photo ->
                Column(Modifier.size(160.dp, 240.dp)) {
                    photo.thumbnail?.let { Image(it.asImageBitmap(), contentDescription = photo.target.label, modifier = Modifier.size(160.dp, 160.dp)) }
                    Text("${photo.status.name} ${photo.target.label}", style = MaterialTheme.typography.labelSmall)
                    Text(photo.note, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        if (photos.size == targets.size && photos.isNotEmpty() && !running) {
            val results = ItemResults().apply { photos.forEach { add(it.status, "${it.target.label}: ${it.note}") } }
            YesNo("Do the photos look right (sharp, correct colors, the expected lens)?", yes = "Look right", no = "Something is wrong") { ok ->
                val auto = results.toResult("Every camera took a photo", "No cameras")
                scope.finish(if (ok) auto else fail("Tester reported a problem with the photos", auto.details))
            }
        }
    }
}

internal class Clip(val label: String, val status: CheckStatus, val note: String, val frame: Bitmap?, val file: File? = null)

private fun pickVideoSize(manager: CameraManager, id: String): Size? =
    manager.getCameraCharacteristics(id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        ?.getOutputSizes(MediaRecorder::class.java)
        ?.filter { it.width <= 1920 && it.height <= 1080 }
        ?.maxByOrNull { it.width * it.height }

internal suspend fun recordClip(context: Context, target: CameraTarget): Clip = withContext(Dispatchers.IO) {
    val manager = context.getSystemService(CameraManager::class.java)
    val file = File(context.cacheDir, "clip_${target.id}.mp4")
    CameraThread().use { thread ->
        try {
            val size = pickVideoSize(manager, target.id) ?: return@use Clip(target.label, CheckStatus.FAIL, "No video sizes", null)
            val recorder = Compat.newMediaRecorder(context).apply {
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
            try {
                withTimeout(CAMERA_TIMEOUT_MS + VIDEO_MS) {
                    val device = manager.open(target.id, thread)
                    try {
                        val session = device.session(listOf(recorder.surface), thread)
                        val request = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply { addTarget(recorder.surface) }.build()
                        session.setRepeatingRequest(request, null, thread.handler)
                        recorder.start()
                        delay(VIDEO_MS)
                        recorder.stop()
                        session.close()
                    } finally {
                        device.close()
                    }
                }
            } finally {
                recorder.release()
            }
            inspectClip(target.label, file, size)
        } catch (e: Exception) {
            Clip(target.label, CheckStatus.FAIL, "${e::class.java.simpleName}: ${e.message}", null)
        }
    }
}

private fun inspectClip(label: String, file: File, size: Size): Clip {
    val retriever = MediaMetadataRetriever()
    try {
        return readClip(retriever, label, file, size)
    } finally {
        retriever.release()
    }
}

private fun readClip(retriever: MediaMetadataRetriever, label: String, file: File, size: Size): Clip {
    retriever.setDataSource(file.absolutePath)
    val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
    val hasVideo = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
    val hasAudio = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
    val frame = Compat.frameAt(retriever, 1_000_000, THUMBNAIL_PX)
    val tracks = runCatching { inspectClipTracks(file) }
    val exposure = clipExposure(file)
    val note = "${size.width}x${size.height}, ${file.length() / 1024} KB, ${tracks.getOrElse { "FAILED ${it.message}" }}, " +
        (exposure?.describe() ?: "exposure not measured")
    val status = when {
        !hasVideo || duration < MIN_VIDEO_DURATION_MS -> CheckStatus.FAIL
        !hasAudio || tracks.isFailure -> CheckStatus.FAIL
        exposure?.status == CheckStatus.WARN -> CheckStatus.WARN
        else -> CheckStatus.PASS
    }
    return Clip(label, status, note, frame, file)
}

@Composable
internal fun ClipPlayer(file: File, onDone: () -> Unit) {
    AndroidView(
        factory = { context ->
            VideoView(context).apply {
                setVideoPath(file.absolutePath)
                setOnCompletionListener { onDone() }
                setOnErrorListener { _, _, _ -> onDone(); true }
                start()
            }
        },
        modifier = Modifier.size(240.dp, 135.dp),
    )
}

@Composable
private fun VideoTest(scope: TestScope) {
    RequirePermissions(listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO), "Recording video needs the camera and microphone permissions.") {
        VideoContent(scope)
    }
}

@Composable
private fun VideoContent(scope: TestScope) {
    val context = scope.activity
    val targets = remember {
        val all = discoverCameras(context.getSystemService(CameraManager::class.java)).filterNot { it.hidden }
        listOfNotNull(
            all.firstOrNull { it.facing == CameraCharacteristics.LENS_FACING_BACK },
            all.firstOrNull { it.facing == CameraCharacteristics.LENS_FACING_FRONT },
        )
    }
    val clips = remember { mutableStateListOf<Clip>() }
    var running by remember { mutableStateOf(false) }
    val coroutines = rememberCoroutineScope()

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Instructions("Records ${VIDEO_MS / 1000} s with sound on ${targets.joinToString { it.label }}, then checks duration, video and audio tracks.")
        Button(enabled = !running, onClick = {
            coroutines.launch {
                running = true
                clips.clear()
                targets.forEach { clips += recordClip(context, it) }
                running = false
            }
        }) { Text(if (running) "Recording..." else "Record clips") }
        var playing by remember { mutableStateOf<File?>(null) }
        clips.forEach { clip ->
            if (playing == clip.file && clip.file != null) {
                ClipPlayer(clip.file, onDone = { playing = null })
            } else {
                clip.frame?.let { Image(it.asImageBitmap(), contentDescription = clip.label, modifier = Modifier.size(160.dp, 120.dp)) }
            }
            Text("${clip.status.name} ${clip.label}: ${clip.note}", style = MaterialTheme.typography.bodySmall)
            clip.file?.let { file -> OutlinedButton(onClick = { playing = file }) { Text("Play with sound") } }
        }
        if (clips.size == targets.size && clips.isNotEmpty() && !running) {
            YesNo("Play each clip. Are the picture and the sound right on all of them?", yes = "All right", no = "Something is wrong") { ok ->
                val results = ItemResults().apply { clips.forEach { add(it.status, "${it.label}: ${it.note}") } }
                val auto = results.toResult("Video with sound on every tested camera", "No cameras")
                clips.forEach { it.file?.delete() }
                scope.finish(if (ok) auto else fail("Tester reported a problem with picture or sound", auto.details))
            }
        }
    }
}
