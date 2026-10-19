package io.github.rthery.devicechecker.interactive

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.hardware.Camera
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import io.github.rthery.devicechecker.checks.CheckResult
import io.github.rthery.devicechecker.checks.CheckStatus
import io.github.rthery.devicechecker.checks.info
import io.github.rthery.devicechecker.compat.Compat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.CancellationException

private const val LEGACY_CAMERA_TIMEOUT_MS = 15_000L
private const val LEGACY_VIDEO_MS = 3_000L

/**
 * Exercises the deprecated Camera1 API on every enumerated camera. This is API-path coverage,
 * not a way to select or certify a particular vendor Camera HAL version.
 */
@SuppressLint("MissingPermission", "Deprecated")
suspend fun runLegacyCameraProbe(context: Context, onCamera: (id: Int, count: Int) -> Unit = { _, _ -> }): CheckResult = withContext(Dispatchers.IO) {
    val root = File(context.getExternalFilesDir(null) ?: context.filesDir, "reports/artifacts/hw.camera.hal")
    if (!root.exists() && !root.mkdirs()) {
        return@withContext CheckResult(CheckStatus.FAIL, "Could not create legacy camera artifact folder", root.absolutePath)
    }

    val results = mutableListOf<String>()
    val artifacts = mutableListOf<String>()
    val count = runCatching { Camera.getNumberOfCameras() }.getOrElse {
        return@withContext CheckResult(CheckStatus.FAIL, "Camera1 enumeration failed", "${it.javaClass.simpleName}: ${it.message}")
    }
    if (count == 0) return@withContext CheckResult(CheckStatus.FAIL, "Camera1 exposed no cameras")

    for (id in 0 until count) {
        val jpeg = File(root, "legacy-camera-$id.jpg")
        val video = File(root, "legacy-camera-$id.mp4")
        jpeg.delete()
        video.delete()
        onCamera(id, count)
        try {
            withTimeout(LEGACY_CAMERA_TIMEOUT_MS) {
                runOneLegacyCamera(context, id, jpeg, video)
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(jpeg.absolutePath, bounds)
            check(bounds.outWidth > 0 && bounds.outHeight > 0) { "Camera1 JPEG does not decode" }
            if (jpeg.length() == 0L || video.length() == 0L) {
                results += "Camera $id: failed: empty photo or video artifact"
            } else {
                val videoDescription = describeVideo(video)
                if (videoDescription.startsWith("invalid")) {
                    results += "Camera $id: failed: $videoDescription"
                    jpeg.delete()
                    video.delete()
                } else {
                    val reports = File(context.getExternalFilesDir(null) ?: context.filesDir, "reports")
                    artifacts += jpeg.relativeTo(reports).path
                    artifacts += video.relativeTo(reports).path
                    results += "Camera $id: JPEG ${jpeg.length()} bytes; $videoDescription"
                }
            }
        } catch (e: TimeoutCancellationException) {
            jpeg.delete()
            video.delete()
            results += "Camera $id: failed: timed out after ${LEGACY_CAMERA_TIMEOUT_MS}ms"
        } catch (e: Exception) {
            jpeg.delete()
            video.delete()
            if (e is CancellationException) throw e
            results += "Camera $id: failed: ${e.javaClass.simpleName}: ${e.message ?: "no detail"}"
        }
    }
    val failures = results.count { it.contains(": failed:") }
    val details = buildString {
        appendLine("Camera1 legacy API coverage; this does not force or identify a HAL version.")
        results.forEach(::appendLine)
        if (artifacts.isNotEmpty()) append("Artifacts: ").append(artifacts.joinToString())
    }
    when {
        failures > 0 -> CheckResult(CheckStatus.FAIL, "$failures of $count Camera1 camera(s) failed", details, artifacts = artifacts)
        else -> info("Camera1 photo and video captured on all $count camera(s). Review picture and sound, then record a tester verdict.", details)
            .copy(artifacts = artifacts)
    }
}

private suspend fun runOneLegacyCamera(context: Context, id: Int, jpeg: File, video: File) {
    val camera = Camera.open(id)
    var recorder: MediaRecorder? = null
    var texture: DrainingTexture? = null
    var previewSurface: Surface? = null
    try {
        val params = camera.parameters
        val pictureSize = params.supportedPictureSizes
            ?.filter { it.width <= 1920 && it.height <= 1080 }
            ?.maxByOrNull { it.width * it.height }
            ?: params.supportedPictureSizes?.maxByOrNull { it.width * it.height }
            ?: error("Camera1 reported no JPEG sizes")
        params.setPictureSize(pictureSize.width, pictureSize.height)
        val videoSize = params.supportedVideoSizes
            ?.filter { it.width <= 1280 && it.height <= 720 }
            ?.maxByOrNull { it.width * it.height }
            ?: params.supportedPreviewSizes?.filter { it.width <= 1280 && it.height <= 720 }
                ?.maxByOrNull { it.width * it.height }
            ?: error("Camera1 reported no video-compatible preview size")
        val previewSize = params.supportedPreviewSizes
            ?.filter { it.width <= 1280 && it.height <= 720 }
            ?.minByOrNull { kotlin.math.abs(it.width - videoSize.width) + kotlin.math.abs(it.height - videoSize.height) }
            ?: error("Camera1 reported no preview sizes")
        params.setPreviewSize(previewSize.width, previewSize.height)
        camera.parameters = params

        texture = DrainingTexture()
        camera.setPreviewTexture(texture.texture)
        camera.startPreview()
        delay(800)
        val bytes = suspendCancellableCoroutine<ByteArray> { cont ->
            camera.takePicture(null, null) { data, _ ->
                if (cont.isActive) cont.resume(data) else Unit
            }
        }
        check(bytes.isNotEmpty()) { "Camera1 returned an empty JPEG" }
        jpeg.writeBytes(bytes)
        camera.stopPreview()

        // MediaRecorder owns the camera after unlock. A Surface backed by a drained SurfaceTexture
        // keeps the legacy preview output flowing while the recorder receives camera frames.
        val surfaceTexture = texture.texture
        previewSurface = Surface(surfaceTexture)
        val recorderInstance = Compat.newMediaRecorder(context)
        recorder = recorderInstance
        camera.unlock()
        recorderInstance.setCamera(camera)
        recorderInstance.setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
        recorderInstance.setVideoSource(MediaRecorder.VideoSource.CAMERA)
        recorderInstance.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        recorderInstance.setOutputFile(video.absolutePath)
        recorderInstance.setVideoEncodingBitRate(4_000_000)
        recorderInstance.setVideoFrameRate(30)
        recorderInstance.setVideoSize(videoSize.width, videoSize.height)
        recorderInstance.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        recorderInstance.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        recorderInstance.setPreviewDisplay(previewSurface)
        recorderInstance.prepare()
        recorderInstance.start()
        delay(LEGACY_VIDEO_MS)
        recorderInstance.stop()
    } finally {
        runCatching { recorder?.reset() }
        runCatching { recorder?.release() }
        runCatching { camera.lock() }
        runCatching { camera.stopPreview() }
        runCatching { camera.release() }
        previewSurface?.release()
        texture?.close()
    }
}

private fun describeVideo(file: File): String {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(file.absolutePath)
        val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        val hasVideo = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
        val hasAudio = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
        if (duration < 2_000 || !hasVideo || !hasAudio) "invalid MP4 (${duration}ms, video=$hasVideo, audio=$hasAudio)"
        else "MP4 ${duration}ms, video and AAC audio present"
    } finally {
        retriever.release()
    }
}

/** Provides a current EGL context so SurfaceTexture frames can be consumed instead of stalling. */
private class DrainingTexture : AutoCloseable {
    private val thread = HandlerThread("legacy-camera-texture").apply { start() }
    private val handler = Handler(thread.looper)
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    val texture: SurfaceTexture

    init {
        val ready = CountDownLatch(1)
        var failure: Throwable? = null
        var created: SurfaceTexture? = null
        handler.post {
            try {
                initializeEgl()
                val names = IntArray(1)
                GLES20.glGenTextures(1, names, 0)
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, names[0])
                created = SurfaceTexture(names[0]).apply {
                    setOnFrameAvailableListener({ surface ->
                        runCatching { surface.updateTexImage() }
                    }, handler)
                }
            } catch (e: Throwable) {
                failure = e
            } finally {
                ready.countDown()
            }
        }
        val initialized = runCatching { ready.await(5, TimeUnit.SECONDS) }.getOrDefault(false)
        if (!initialized || failure != null || created == null) {
            val cleaned = CountDownLatch(1)
            handler.post {
                releaseEgl()
                cleaned.countDown()
            }
            cleaned.await(2, TimeUnit.SECONDS)
            thread.quitSafely()
            throw IllegalStateException(
                if (!initialized) "Timed out creating camera preview texture" else "Could not create preview texture",
                failure,
            )
        }
        texture = created!!
    }

    private fun initializeEgl() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "No EGL display" }
        check(EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 0)) { "EGL initialize failed" }
        val attrs = intArrayOf(EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_NONE)
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, count, 0) && count[0] > 0) { "No EGL config" }
        val contextAttrs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, contextAttrs, 0)
        check(eglContext != EGL14.EGL_NO_CONTEXT) { "EGL context creation failed" }
        val surfaceAttrs = intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE)
        eglSurface = EGL14.eglCreatePbufferSurface(display, configs[0], surfaceAttrs, 0)
        check(eglSurface != EGL14.EGL_NO_SURFACE) { "EGL pbuffer creation failed" }
        check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, eglContext)) { "EGL make-current failed" }
    }

    override fun close() {
        val done = CountDownLatch(1)
        handler.post {
            runCatching { texture.setOnFrameAvailableListener(null) }
            runCatching { texture.release() }
            releaseEgl()
            done.countDown()
        }
        done.await(2, TimeUnit.SECONDS)
        thread.quitSafely()
    }

    private fun releaseEgl() {
        if (display == EGL14.EGL_NO_DISPLAY) return
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, eglSurface)
        if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, eglContext)
        EGL14.eglTerminate(display)
        display = EGL14.EGL_NO_DISPLAY
        eglSurface = EGL14.EGL_NO_SURFACE
        eglContext = EGL14.EGL_NO_CONTEXT
    }
}
