package io.github.rthery.devicechecker

import java.io.File
import android.net.Uri
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.rthery.devicechecker.data.Repository
import io.github.rthery.devicechecker.data.Verdict
import io.github.rthery.devicechecker.shell.ShizukuShell
import kotlinx.coroutines.launch

/**
 * Headless control over adb. Guarded by the DUMP permission, which the shell uid has and normal apps do not.
 * Results land in reports/ under the app's external files dir and in logcat (tag DeviceChecker).
 */
class AdbCommandReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val repository = Repository.get(context)
        when (intent.action) {
            ACTION_RUN_CHECKS -> repository.scope.launch {
                repository.runChecks(repository.resolveRun(intent.getStringExtra(EXTRA_IDS)))
            }
            ACTION_SET_RESULT -> {
                val id = intent.getStringExtra(EXTRA_TEST)
                val verdict = intent.getStringExtra(EXTRA_VERDICT)?.let { value -> Verdict.entries.firstOrNull { it.name.equals(value, ignoreCase = true) } }
                val test = id?.let { repository.catalog.value.byId[it] }
                if (test == null || verdict == null) {
                    Log.e(Repository.TAG, "SET_RESULT needs --es $EXTRA_TEST <id from catalog.json> --es $EXTRA_VERDICT ${Verdict.entries.joinToString("|") { it.name.lowercase() }}")
                    return
                }
                repository.markTest(test, verdict, intent.getStringExtra(EXTRA_NOTE).orEmpty())
                Log.i(Repository.TAG, "${test.id}: set to ${verdict.name}")
            }
            ACTION_CAPTURE_STOCK -> repository.scope.launch {
                runCatching { repository.captureStock() }.onFailure { Log.e(Repository.TAG, "Stock capture failed: ${it.message}") }
            }
            ACTION_IMPORT_STOCK -> repository.scope.launch {
                val file = importFile(repository, intent.getStringExtra(EXTRA_FILE) ?: Repository.STOCK_FILE) ?: return@launch
                val error = if (!file.exists()) "No file at ${file.absolutePath}" else repository.importStock(file.readText())
                if (error != null) Log.e(Repository.TAG, "IMPORT_STOCK failed: $error") else repository.exportReports(snapshot = false)
            }
            ACTION_IMPORT_PACK -> repository.scope.launch {
                val file = importFile(repository, intent.getStringExtra(EXTRA_FILE) ?: "pack.json") ?: return@launch
                if (!file.exists()) {
                    Log.e(Repository.TAG, "IMPORT_PACK failed: no file at ${file.absolutePath}")
                    return@launch
                }
                val errors = repository.importPack(Uri.fromFile(file))
                if (errors != null) {
                    Log.e(Repository.TAG, "IMPORT_PACK failed: ${errors.joinToString("; ")}")
                    return@launch
                }
                if (intent.getBooleanExtra(EXTRA_APPROVE, false)) repository.approveImportedPacks()
                repository.exportReports(snapshot = false)
                Log.i(Repository.TAG, "Pack imported from ${file.name}${if (intent.getBooleanExtra(EXTRA_APPROVE, false)) " and approved" else "; approve it in the app or pass --ez approve true"}")
            }
            ACTION_EXPORT -> repository.scope.launch { repository.exportReports(snapshot = false) }
            ACTION_SET_VERDICT -> {
                val requirement = intent.getStringExtra(EXTRA_REQUIREMENT)
                val verdict = intent.getStringExtra(EXTRA_VERDICT)?.let { value -> Verdict.entries.firstOrNull { it.name.equals(value, ignoreCase = true) } }
                if (requirement == null || verdict == null) {
                    Log.e(Repository.TAG, "SET_VERDICT needs --es $EXTRA_REQUIREMENT <id> --es $EXTRA_VERDICT ${Verdict.entries.joinToString("|")}")
                    return
                }
                repository.setVerdict(requirement, verdict, intent.getStringExtra(EXTRA_NOTE).orEmpty())
            }
            ACTION_SET_PROFILE -> {
                val json = intent.getStringExtra(EXTRA_JSON)
                if (json == null) {
                    Log.e(Repository.TAG, "SET_PROFILE needs --es $EXTRA_JSON '<partial profile json>'")
                    return
                }
                runCatching { repository.mergeProfileJson(json) }.onFailure { Log.e(Repository.TAG, "Bad profile JSON: ${it.message}") }
            }
            else -> Log.e(Repository.TAG, "Unknown action ${intent.action}")
        }
    }

    /** A bare file name inside the import folder; anything with a path is refused. */
    private fun importFile(repository: Repository, name: String): File? {
        if (!SAFE_FILE_NAME.matches(name)) {
            Log.e(Repository.TAG, "Refused file name '$name': use a plain name inside ${repository.importDir.absolutePath}")
            return null
        }
        return File(repository.importDir, name)
    }

    companion object {
        private val SAFE_FILE_NAME = Regex("""^[A-Za-z0-9][A-Za-z0-9._-]{0,99}$""")
        private const val PREFIX = "io.github.rthery.devicechecker."
        const val ACTION_RUN_CHECKS = PREFIX + "RUN_CHECKS"
        const val ACTION_EXPORT = PREFIX + "EXPORT"
        const val ACTION_SET_VERDICT = PREFIX + "SET_VERDICT"
        const val ACTION_SET_PROFILE = PREFIX + "SET_PROFILE"
        const val ACTION_SET_RESULT = PREFIX + "SET_RESULT"
        const val ACTION_CAPTURE_STOCK = PREFIX + "CAPTURE_STOCK"
        const val ACTION_IMPORT_STOCK = PREFIX + "IMPORT_STOCK"
        const val ACTION_IMPORT_PACK = PREFIX + "IMPORT_PACK"
        const val EXTRA_TEST = "test"
        const val EXTRA_FILE = "file"
        const val EXTRA_APPROVE = "approve"
        const val EXTRA_IDS = "ids"
        const val EXTRA_REQUIREMENT = "requirement"
        const val EXTRA_VERDICT = "verdict"
        const val EXTRA_NOTE = "note"
        const val EXTRA_JSON = "json"
    }
}
