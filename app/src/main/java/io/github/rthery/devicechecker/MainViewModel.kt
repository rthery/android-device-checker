package io.github.rthery.devicechecker

import io.github.rthery.devicechecker.ui.UiText
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.rthery.devicechecker.checks.CheckResult
import io.github.rthery.devicechecker.data.DeviceInfo
import io.github.rthery.devicechecker.data.ManualVerdict
import io.github.rthery.devicechecker.data.Repository
import io.github.rthery.devicechecker.data.HistoryEntry
import io.github.rthery.devicechecker.data.RunProgress
import io.github.rthery.devicechecker.data.RunSummary
import io.github.rthery.devicechecker.data.UnifiedTest
import io.github.rthery.devicechecker.data.TestCatalog
import io.github.rthery.devicechecker.data.Verdict
import io.github.rthery.devicechecker.pack.PackEntry
import io.github.rthery.devicechecker.pack.ThemeHints
import android.net.Uri
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import io.github.rthery.devicechecker.data.toJson
import io.github.rthery.devicechecker.data.toMarkdown
import io.github.rthery.devicechecker.profile.DeviceProfile
import io.github.rthery.devicechecker.shell.AdbRoot
import io.github.rthery.devicechecker.shell.ShizukuShell
import io.github.rthery.devicechecker.shell.ShizukuState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = Repository.get(application)

    val profile: StateFlow<DeviceProfile> = repository.profile
    val stock = repository.stock
    val results: StateFlow<Map<String, CheckResult>> = repository.results
    val verdicts: StateFlow<Map<String, ManualVerdict>> = repository.verdicts
    val progress: StateFlow<RunProgress?> = repository.progress
    val deviceInfo: StateFlow<DeviceInfo> = repository.deviceInfo
    val shizukuState: StateFlow<ShizukuState> = ShizukuShell.state
    val shizukuRoot: StateFlow<Boolean> = ShizukuShell.runsAsRoot
    val adbRoot: StateFlow<Boolean?> = AdbRoot.state
    val lastRun: StateFlow<RunSummary?> = repository.lastRun
    val reportsDir: File get() = repository.reportsDir
    val catalog: StateFlow<TestCatalog> = repository.catalog
    val collapsedCategories: StateFlow<Set<String>> = repository.collapsedCategories
    val requirementPriority = repository.requirementPriority
    fun setRequirementPriority(priority: io.github.rthery.devicechecker.data.RequirementPriority) = repository.setRequirementPriority(priority)
    fun toggleCategory(category: io.github.rthery.devicechecker.data.Category) = repository.toggleCategory(category)
    val packs: StateFlow<List<PackEntry>> = repository.packs
    /** Null until startup data is read, so the first frame already shows the right tests and theme. */
    val theme: StateFlow<LoadedTheme?> = combine(repository.startupLoaded, repository.catalog) { loaded, catalog -> if (loaded) LoadedTheme(catalog.theme) else null }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    data class StockTransfer(val busy: Boolean = false, val message: UiText? = null, val error: Boolean = false)
    private val _stockTransfer = MutableStateFlow(StockTransfer())
    val stockTransfer = _stockTransfer.asStateFlow()

    private fun transferStock(block: suspend () -> UiText) {
        if (_stockTransfer.value.busy) return
        _stockTransfer.value = StockTransfer(busy = true)
        viewModelScope.launch {
            try {
                _stockTransfer.value = StockTransfer(message = block())
            } catch (e: kotlinx.coroutines.CancellationException) {
                _stockTransfer.value = StockTransfer()
                throw e
            } catch (e: Exception) {
                _stockTransfer.value = StockTransfer(message = e.message?.let { UiText.Raw(it) } ?: UiText.Res(R.string.screen_stock_transfer_failed), error = true)
            }
        }
    }

    fun importStock(uri: Uri) = transferStock {
        val text = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            getApplication<Application>().contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: error("Cannot read this file")
        }
        repository.importStock(text)?.let { error(it) }
        UiText.Res(R.string.screen_stock_imported)
    }

    fun exportStock(uri: Uri) = transferStock {
        val file = repository.captureStock()
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.use { output ->
                file.inputStream().use { it.copyTo(output) }
            } ?: error("Cannot save to this file")
        }
        UiText.Res(R.string.screen_stock_saved)
    }

    private val _packImportErrors = MutableStateFlow<List<String>?>(null)
    val packImportErrors: StateFlow<List<String>?> = _packImportErrors.asStateFlow()

    fun importPack(uri: Uri) {
        viewModelScope.launch { _packImportErrors.value = repository.importPack(uri) }
    }

    fun dismissPackImportErrors() {
        _packImportErrors.value = null
    }

    fun enablePack(entry: PackEntry) {
        viewModelScope.launch { repository.enablePack(entry) }
    }

    fun disablePack(entry: PackEntry) {
        viewModelScope.launch { repository.disablePack(entry) }
    }

    fun removePack(entry: PackEntry) {
        viewModelScope.launch { repository.removePack(entry) }
    }

    private val _history = MutableStateFlow<List<HistoryEntry>>(emptyList())
    val history: StateFlow<List<HistoryEntry>> = _history.asStateFlow()

    data class DeepLink(val test: String?, val tab: String?)

    private val _deepLink = MutableStateFlow<DeepLink?>(null)
    val deepLink: StateFlow<DeepLink?> = _deepLink.asStateFlow()

    fun deepLink(run: String?, test: String?, tab: String?, options: Map<String, Int>) {
        if (run != null) repository.startRun(repository.resolveRun(run), options)
        _deepLink.value = DeepLink(test, tab ?: if (run != null) "tests" else null)
    }

    fun consumeDeepLink() {
        _deepLink.value = null
    }

    fun runAllAutomatic() = repository.startRun(catalog.value.all.filter { it.kind == io.github.rthery.devicechecker.data.TestKind.AUTO }.map { it.id }.toSet())

    fun runOne(testId: String) = repository.startRun(setOf(testId))

    fun runTests(testIds: Set<String>) = repository.startRun(testIds)

    fun stopRun() = repository.stopRun()

    fun dismissLastRun() = repository.dismissLastRun()

    fun markTest(test: UnifiedTest, verdict: Verdict) = repository.markTest(test, verdict)

    fun refreshHistory() {
        viewModelScope.launch { _history.value = repository.history() }
    }

    fun saveTestResult(testId: String, result: CheckResult) = repository.saveTestResult(testId, result)

    fun setVerdict(requirementId: String, verdict: Verdict, note: String) = repository.setVerdict(requirementId, verdict, note)

    fun saveProfile(profile: DeviceProfile) = repository.saveProfile(profile)

    fun requestShizuku() = ShizukuShell.requestPermission()

    fun refreshShizuku() = ShizukuShell.refresh()

    fun markdownReport(): String = repository.buildReport().toMarkdown()

    fun jsonReport(): String = repository.buildReport().toJson()
}

data class LoadedTheme(val hints: ThemeHints?)
