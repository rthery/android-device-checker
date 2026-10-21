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

class TestScope(
    val activity: ComponentActivity,
    val profile: DeviceProfile,
    val privilegedShell: Shell?,
    private val onFinish: (CheckResult) -> Unit,
) {
    val crashWatcher = CrashWatcher(privilegedShell)
    private val startedAt = crashWatcher.mark()

    /** Attaches every crash that happened since the test screen opened. */
    fun finish(result: CheckResult) {
        activity.lifecycleScope.launch {
            val crashes = crashWatcher.crashesSince(startedAt)
            onFinish(result.withCrashes(crashes).copy(timestamp = System.currentTimeMillis()))
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
    val all: List<InteractiveTest> = audioTests + cameraTests + cameraHalTest + stressTests + radioTests + sensorTests + guidedTests + integrityTest
    val byId: Map<String, InteractiveTest> = all.associateBy { it.id }

    init {
        check(byId.size == all.size) { "Duplicate test id" }
    }
}
