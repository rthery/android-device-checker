package io.github.rthery.devicechecker.interactive

import android.Manifest
import android.graphics.BitmapFactory
import android.hardware.camera2.CameraManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import io.github.rthery.devicechecker.checks.CheckContext
import io.github.rthery.devicechecker.checks.CheckResult
import io.github.rthery.devicechecker.checks.CheckStatus
import io.github.rthery.devicechecker.checks.fail
import io.github.rthery.devicechecker.checks.info
import io.github.rthery.devicechecker.checks.warn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.File

internal val cameraHalTest = InteractiveTest(
    "hw.camera.hal", "Camera HAL and API coverage",
    "Lists the camera HALs, takes photos and records video with sound through Camera2 and legacy Camera1 on every accessible camera. Review the saved media before passing. Public APIs cannot select an alternate HAL version.",
) { scope -> CameraHalScreen(scope) }

internal suspend fun CheckContext.runCameraHalCoverage(onStep: (String) -> Unit = {}): CheckResult {
    val dir = artifactsFor("hw.camera.hal") ?: return warn("No reports folder available")
    onStep("Reading the camera HAL inventory")
    val inventory = read("dumpsys media.camera", { "Camera HAL device" in it })
    val parsed = parseCameraHalInventory(inventory.output.orEmpty())
    val lines = mutableListOf<String>()
    lines += "HAL = Hardware Abstraction Layer. Provider versions and camera-device HAL versions are separate."
    lines += parsed.providers
    lines += parsed.devices
    if (parsed.devices.isEmpty()) lines += "HAL inventory unavailable. Connect Shizuku and rerun; API success alone does not prove HAL-version coverage."
    lines += "Camera1/Camera2 are app APIs, not HAL versions. Public APIs cannot force alternate HAL implementations; additional exposed versions need separate validation."
    val artifacts = mutableListOf<String>()
    var failed = false
    var warnings = false
    val targets = discoverCameras(context.getSystemService(CameraManager::class.java))
    if (targets.isEmpty()) return fail("No Camera2 cameras exposed", lines.joinToString("\n"))
    targets.forEachIndexed { index, target ->
        val position = "camera ${index + 1} of ${targets.size}"
        try {
            onStep("Camera2: ${target.label} photo ($position)")
            val photo = takePhoto(context, target)
            lines += "Camera2 ${target.label} photo: ${photo.status}: ${photo.note}"
            failed = failed || photo.status == CheckStatus.FAIL
            warnings = warnings || photo.status == CheckStatus.WARN
            photo.jpeg?.let { jpeg ->
                val file = File(dir, "camera2-${target.id}.jpg").apply { writeBytes(jpeg) }
                writeExifRotation(file, photo.rotation)
                artifacts += relative(file)
            }
            onStep("Camera2: ${target.label} video with sound ($position)")
            val clip = recordClip(context, target)
            lines += "Camera2 ${target.label} video: ${clip.status}: ${clip.note}"
            failed = failed || clip.status == CheckStatus.FAIL
            warnings = warnings || clip.status == CheckStatus.WARN
            clip.file?.takeIf { it.exists() }?.let { source ->
                val file = File(dir, "camera2-${target.id}.mp4")
                source.copyTo(file, overwrite = true)
                source.delete()
                artifacts += relative(file)
            }
            clip.frame?.recycle()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = true
            lines += "Camera2 ${target.label}: FAIL ${e.message}"
        }
    }
    val legacy = runLegacyCameraProbe(context) { id, count -> onStep("Camera1: camera $id photo and video (camera ${id + 1} of $count)") }
    lines += legacy.details
    artifacts += legacy.artifacts
    failed = failed || legacy.status == CheckStatus.FAIL || legacy.status == CheckStatus.ERROR
    warnings = warnings || legacy.status == CheckStatus.WARN
    val details = lines.joinToString("\n")
    return when {
        failed -> fail("Camera API coverage failed; see per-camera results", details)
        parsed.devices.isEmpty() -> warn("Media saved, but HAL inventory is unavailable", details)
        warnings -> warn("Media saved with warnings. Review every photo and clip", details)
        else -> info("Both APIs exercised. Review every photo and clip with sound", details)
    }.copy(artifacts = artifacts.distinct())
}

@Composable
private fun CameraHalScreen(scope: TestScope) {
    RequirePermissions(listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO), "Testing camera interfaces needs camera and microphone access.") {
        var result by remember { mutableStateOf<CheckResult?>(null) }
        var running by remember { mutableStateOf(false) }
        var step by remember { mutableStateOf("") }
        var playing by remember { mutableStateOf<String?>(null) }
        val coroutine = rememberCoroutineScope()
        val root = remember { File(scope.activity.getExternalFilesDir(null), "reports") }
        Column {
            Text("Use a well-lit scene. This tests each camera through both app APIs; it cannot switch HAL versions. Connect Shizuku to read the HAL inventory.")
            Button(enabled = !running, onClick = {
                coroutine.launch {
                    running = true
                    result = null
                    try {
                        result = CheckContext(scope.activity, scope.profile, scope.privilegedShell, null, root).runCameraHalCoverage { step = it }
                    } finally {
                        running = false
                        step = ""
                    }
                }
            }) { Text(if (running) "Testing camera interfaces…" else "Run coverage test") }
            if (running) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
                Text(step.ifEmpty { "Starting…" })
            }
            result?.let { outcome ->
                Text(outcome.summary)
                Text(outcome.details)
                outcome.artifacts.forEach { path ->
                    val file = File(root, path)
                    Text(file.name)
                    if (file.extension == "jpg") {
                        val bitmap = remember(path, outcome) { BitmapFactory.decodeFile(file.path) }
                        bitmap?.let { Image(it.asImageBitmap(), contentDescription = file.name, modifier = Modifier.size(240.dp, 180.dp)) }
                    } else if (file.extension == "mp4") {
                        if (playing == path) key(path) { ClipPlayer(file) { playing = null } }
                        Button(onClick = { playing = path }) { Text("Play with sound") }
                    }
                }
                if (outcome.status == CheckStatus.FAIL || outcome.status == CheckStatus.ERROR || "HAL inventory unavailable." in outcome.details) {
                    Button(onClick = { scope.finish(outcome) }) { Text("Save result") }
                } else {
                    YesNo("Have you reviewed every photo and clip, including sound, and confirmed all exposed HAL versions are covered?") { ok ->
                        scope.finish(if (ok) outcome.copy(status = CheckStatus.PASS, summary = "Tester verified camera HAL/API coverage and all saved media", byTester = true)
                        else outcome.copy(status = CheckStatus.FAIL, summary = "Tester reported incomplete coverage or a media problem", byTester = true))
                    }
                }
            }
        }
    }
}
