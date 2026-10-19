package io.github.rthery.devicechecker

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A request from adb, handed over in process memory so no other app can forge one. */
data class AdbLink(val run: String?, val test: String?, val tab: String?, val options: Map<String, Int>)

object AdbLinks {
    private val _pending = MutableStateFlow<AdbLink?>(null)
    val pending: StateFlow<AdbLink?> = _pending.asStateFlow()

    fun post(link: AdbLink) {
        _pending.value = link
    }

    fun consume(): AdbLink? = _pending.value.also { _pending.value = null }
}

/**
 * The entry point for scripts and LLM agents. The manifest guards it with the DUMP permission,
 * which adb shell holds and normal apps do not, so only adb can start tests, open tests or wake
 * the screen. `adb shell am start -n <pkg>/.AdbLinkActivity` with:
 * `--es run <checks|headless|all|id,id>`, `--es test <id>`, `--es tab <tests|report|device>`,
 * `--ei iterations N` (1 to 200), `--ei gnss_timeout S` (10 to 900).
 */
class AdbLinkActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val extras = intent?.extras
        if (extras != null) {
            val options = buildMap {
                if (extras.containsKey(EXTRA_ITERATIONS)) put(EXTRA_ITERATIONS, extras.getInt(EXTRA_ITERATIONS).coerceIn(1, MAX_ITERATIONS))
                if (extras.containsKey(EXTRA_GNSS_TIMEOUT)) put(EXTRA_GNSS_TIMEOUT, extras.getInt(EXTRA_GNSS_TIMEOUT).coerceIn(MIN_GNSS_TIMEOUT_S, MAX_GNSS_TIMEOUT_S))
            }
            AdbLinks.post(AdbLink(extras.getString(EXTRA_RUN), extras.getString(EXTRA_TEST), extras.getString(EXTRA_TAB), options))
        }
        // Reuse the running MainActivity: a second one would leave the first collecting the link.
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        finish()
    }

    companion object {
        const val EXTRA_RUN = "run"
        const val EXTRA_TEST = "test"
        const val EXTRA_TAB = "tab"
        const val EXTRA_ITERATIONS = "iterations"
        const val EXTRA_GNSS_TIMEOUT = "gnss_timeout"
        private const val MAX_ITERATIONS = 200
        private const val MIN_GNSS_TIMEOUT_S = 10
        private const val MAX_GNSS_TIMEOUT_S = 900
    }
}
