package io.github.rthery.devicechecker.interactive

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.lifecycle.lifecycleScope
import io.github.rthery.devicechecker.checks.CheckResult
import io.github.rthery.devicechecker.checks.CrashWatcher
import io.github.rthery.devicechecker.checks.withCrashes
import kotlinx.coroutines.launch
import io.github.rthery.devicechecker.profile.DeviceProfile
import io.github.rthery.devicechecker.shell.Shell
import io.github.rthery.devicechecker.shell.TrackedShell
import io.github.rthery.devicechecker.shell.currentRunConditions

class TestScope(
    val activity: ComponentActivity,
    val profile: DeviceProfile,
    shizuku: Shell?,
    private val onFinish: (CheckResult) -> Unit,
) {
    private val tracked = shizuku?.let(::TrackedShell)
    val privilegedShell: Shell? = tracked
    val crashWatcher = CrashWatcher(shizuku)
    private val startedAt = crashWatcher.mark()

    /** Attaches every crash that happened since the test screen opened, and the root state if Shizuku was used. */
    fun finish(result: CheckResult) {
        activity.lifecycleScope.launch {
            val crashes = crashWatcher.crashesSince(startedAt)
            val conditions = if (tracked?.used == true) currentRunConditions() else null
            onFinish(result.withCrashes(crashes).copy(timestamp = System.currentTimeMillis(), conditions = conditions))
        }
    }
}

class InteractiveTest(
    val id: String,
    val title: String,
    val summary: String,
    /** A step list the tester works through, as opposed to a test the app drives. */
    val manual: Boolean = false,
    val source: String? = null,
    val content: @Composable (TestScope) -> Unit,
)

object TestRegistry {
    val all: List<InteractiveTest> = audioTests + cameraTests + cameraHalTest + stressTests + radioTests + sensorTests + guidedTests + integrityTest + bluetoothCodecTest
    val byId: Map<String, InteractiveTest> = all.associateBy { it.id }

    init {
        check(byId.size == all.size) { "Duplicate test id" }
    }
}
