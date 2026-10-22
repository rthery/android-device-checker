package io.github.rthery.devicechecker.data

import io.github.rthery.devicechecker.interactive.HeadlessRegistry
import android.content.Context
import android.util.Log
import io.github.rthery.devicechecker.checks.CheckContext
import io.github.rthery.devicechecker.checks.CheckResult
import io.github.rthery.devicechecker.checks.CheckStatus
import io.github.rthery.devicechecker.checks.withCrashes
import io.github.rthery.devicechecker.pack.LauncherIcon
import io.github.rthery.devicechecker.pack.PackEntry
import io.github.rthery.devicechecker.pack.PackOrigin
import io.github.rthery.devicechecker.pack.PackManager
import io.github.rthery.devicechecker.profile.DeviceProfile
import io.github.rthery.devicechecker.checks.RunConditions
import io.github.rthery.devicechecker.shell.AdbRoot
import io.github.rthery.devicechecker.shell.ShizukuShell
import io.github.rthery.devicechecker.shell.currentRunConditions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import android.net.Uri
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** [ids] lists every test of the run in the order it runs them. */
data class RunProgress(val done: Int, val total: Int, val current: String, val currentId: String, val pendingIds: Set<String>, val ids: List<String> = emptyList())

data class RunSummary(val ran: Int, val failed: Int, val stopped: Boolean)

/** [build] is the short ROM version the report was written on; [rooted] means adbd ran as root then. */
data class HistoryEntry(val file: String, val generatedAt: String, val summary: Map<String, Int>, val build: String? = null, val rooted: Boolean = false)

@kotlinx.serialization.Serializable
private data class HistoryHeader(
    val generatedAt: String = "",
    val summary: Map<String, Int> = emptyMap(),
    val device: Map<String, String> = emptyMap(),
    val conditions: RunConditions? = null,
)

/** Single source of truth shared by the UI and the adb receiver. */
class Repository private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val store = Store(appContext)
    private val runMutex = Mutex()

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _profile = MutableStateFlow(store.loadProfile())
    val profile: StateFlow<DeviceProfile> = _profile.asStateFlow()

    private val _results = MutableStateFlow(store.loadCheckResults())
    val results: StateFlow<Map<String, CheckResult>> = _results.asStateFlow()

    private val _verdicts = MutableStateFlow(store.loadVerdicts())
    val verdicts: StateFlow<Map<String, ManualVerdict>> = _verdicts.asStateFlow()

    private val _collapsedCategories = MutableStateFlow(store.loadCollapsedCategories())
    val collapsedCategories: StateFlow<Set<String>> = _collapsedCategories.asStateFlow()

    private val _requirementPriority = MutableStateFlow(store.loadRequirementPriority())
    val requirementPriority: StateFlow<RequirementPriority> = _requirementPriority.asStateFlow()

    fun setRequirementPriority(priority: RequirementPriority) {
        _requirementPriority.value = priority
        store.saveRequirementPriority(priority)
    }

    fun toggleCategory(category: Category) {
        val current = _collapsedCategories.value
        val updated = if (category.name in current) current - category.name else current + category.name
        _collapsedCategories.value = updated
        store.saveCollapsedCategories(updated)
    }

    private val _progress = MutableStateFlow<RunProgress?>(null)
    val progress: StateFlow<RunProgress?> = _progress.asStateFlow()

    private val _stock = MutableStateFlow(store.loadStock())
    val stock: StateFlow<StockSnapshot?> = _stock.asStateFlow()

    private val _lastRun = MutableStateFlow<RunSummary?>(null)
    val lastRun: StateFlow<RunSummary?> = _lastRun.asStateFlow()

    private var runJob: Job? = null

    private val _deviceInfo = MutableStateFlow(DeviceInfo(emptyList()))
    val deviceInfo: StateFlow<DeviceInfo> = _deviceInfo.asStateFlow()

    val reportsDir: File get() = File(appContext.getExternalFilesDir(null), "reports").apply { mkdirs() }

    private val packManager = PackManager(appContext)
    val packs: StateFlow<List<PackEntry>> = packManager.entries

    private val _catalog = MutableStateFlow(TestCatalog.builtIn)
    val catalog: StateFlow<TestCatalog> = _catalog.asStateFlow()

    private val _startupLoaded = MutableStateFlow(false)
    /** True once the ROM and the enabled packs, which set the test list and theme, have been read. */
    val startupLoaded: StateFlow<Boolean> = _startupLoaded.asStateFlow()

    init {
        scope.launch {
            try {
                listOf(
                    launch { _deviceInfo.value = DeviceInfo.read(appContext) },
                    launch { reloadPacks() },
                ).joinAll()
            } finally {
                _startupLoaded.value = true
            }
        }
    }

    private suspend fun reloadPacks() {
        packManager.reload()
        val enabled = packManager.enabledPacks
        if (enabled != _catalog.value.packs) _catalog.value = TestCatalog(enabled)
        LauncherIcon.apply(appContext, LauncherIcon.from(_catalog.value.theme?.icon))
    }

    /** Null on success, otherwise why the file was refused. */
    suspend fun importPack(uri: Uri): List<String>? = packManager.import(uri).also { reloadPacks() }

    /** Called after the user reviewed the pack's commands; applies its stock profile values once. */
    suspend fun enablePack(entry: PackEntry) {
        packManager.approve(entry)
        entry.pack?.profile?.let { mergeProfileJson(it.toString()) }
        reloadPacks()
        exportReports(snapshot = false)
    }

    suspend fun disablePack(entry: PackEntry) {
        packManager.disable(entry)
        reloadPacks()
        exportReports(snapshot = false)
    }

    suspend fun removePack(entry: PackEntry) {
        packManager.remove(entry)
        reloadPacks()
        exportReports(snapshot = false)
    }

    /** Starts a run in the repository scope so it survives screen changes; ignored while one runs. */
    fun startRun(ids: Set<String>? = null, options: Map<String, Int> = emptyMap(), keepTesterResults: Boolean = false) {
        if (runJob?.isActive == true) return
        runJob = scope.launch { runChecks(ids, options, keepTesterResults) }
    }

    /**
     * Turns a run spec into test ids: "checks" or null for every automatic check, "headless" for the
     * guided tests the app can drive alone, "all" for both, or a comma-separated list of ids.
     */
    fun resolveRun(spec: String?): Set<String>? = when (spec?.trim()) {
        null, "", "checks" -> null
        "headless" -> HeadlessRegistry.all.map { it.id }.toSet()
        "all" -> (_catalog.value.checks.map { it.id } + HeadlessRegistry.all.map { it.id }).toSet()
        else -> spec.split(',').map(String::trim).filter(String::isNotEmpty).toSet()
    }

    fun stopRun() {
        runJob?.cancel()
    }

    fun dismissLastRun() {
        _lastRun.value = null
    }

    /** With [keepTesterResults], tests the tester already marked are left out of the run. */
    suspend fun runChecks(ids: Set<String>? = null, options: Map<String, Int> = emptyMap(), keepTesterResults: Boolean = false) = runMutex.withLock {
        writeStatus("running", "starting")
        ShizukuShell.awaitReady()
        val context = CheckContext(appContext, _profile.value, ShizukuShell.takeIf { it.isReady }, DeviceInfo.detectRom(appContext), reportsDir, options, _stock.value)
        val selected = if (ids == null) _catalog.value.checks + HeadlessRegistry.quick
        else (_catalog.value.checks + HeadlessRegistry.all).filter { it.id in ids }.distinctBy { it.id }
        val kept = if (keepTesterResults) testerMarkedIds(selected.map { it.id }, _results.value) else emptySet()
        if (kept.isNotEmpty()) Log.i(TAG, "Keeping tester results: ${kept.joinToString()}")
        val checks = selected.filter { it.id !in kept }
        var ran = 0
        var stopped = false
        try {
            checks.forEachIndexed { index, check ->
                val pending = checks.drop(index + 1).map { it.id }.toSet()
                _progress.value = RunProgress(index, checks.size, check.title, check.id, pending, checks.map { it.id })
                writeStatus("running", "${index + 1}/${checks.size} ${check.id}")
                val startedAt = context.crashWatcher.mark()
                val startedElapsed = android.os.SystemClock.elapsedRealtime()
                context.usedShizuku = false
                val outcome = runCatchingCheck { check.run(context) }
                val conditions = if (context.usedShizuku) currentRunConditions() else null
                val result = outcome
                    .withCrashes(context.crashWatcher.crashesSince(startedAt))
                    .copy(timestamp = System.currentTimeMillis(), conditions = conditions)
                // Keep a single quick check visible long enough to show its progress.
                if (checks.size == 1) {
                    delay((600 - (android.os.SystemClock.elapsedRealtime() - startedElapsed)).coerceAtLeast(0))
                }
                Log.i(TAG, "${check.id}: ${result.status} ${result.summary}")
                _results.update { it + (check.id to result) }
                ran++
            }
            if (ids == null) settleApplicability(context)
        } catch (e: CancellationException) {
            stopped = true
            throw e
        } finally {
            withContext(NonCancellable) {
                _progress.value = null
                store.saveCheckResults(_results.value)
                val failed = checks.take(ran).count { _results.value[it.id]?.status == CheckStatus.FAIL || _results.value[it.id]?.status == CheckStatus.ERROR }
                if (checks.size > 1) _lastRun.value = RunSummary(ran, failed, stopped)
                exportReports(snapshot = true)
                writeStatus(if (stopped) "stopped" else "done", "$ran/${checks.size} checks")
            }
        }
    }

    /** Stores N/A for guided tests the profile or the device rules out; applicable ones keep their result. */
    private suspend fun settleApplicability(context: CheckContext) {
        HeadlessRegistry.applicability.forEach { rule ->
            if (_results.value[rule.id]?.byTester == true) return@forEach
            val result = runCatching { rule.decide(context) }.getOrNull() ?: return@forEach
            Log.i(TAG, "${rule.id}: ${result.status} ${result.summary}")
            _results.update { it + (rule.id to result.copy(timestamp = System.currentTimeMillis())) }
        }
    }

    /** Captures what this ROM reports into reports/stock.json; meant to run on the stock ROM. */
    suspend fun captureStock(): File = withContext(Dispatchers.IO) {
        ShizukuShell.awaitReady()
        val context = CheckContext(appContext, _profile.value, ShizukuShell.takeIf { it.isReady }, DeviceInfo.detectRom(appContext), reportsDir)
        val snapshot = context.captureStock()
        File(reportsDir, STOCK_FILE).apply { writeText(stockJson.encodeToString(StockSnapshot.serializer(), snapshot)) }
            .also { Log.i(TAG, "Stock capture written to ${it.absolutePath}") }
    }

    /** Loads a stock capture, replaces the profile and refreshes comparisons. Returns an error or null. */
    suspend fun importStock(text: String): String? = withContext(Dispatchers.IO) {
        val snapshot = runCatching { decodeStockCapture(text, android.os.Build.DEVICE) }.getOrElse { return@withContext "Cannot import stock capture: ${it.message}" }
        runMutex.withLock {
            _stock.value = snapshot
            store.saveStock(snapshot)
            withContext(Dispatchers.Main) { saveProfile(snapshot.profile) }
        }
        Log.i(TAG, "Stock capture imported: ${snapshot.device}, Android ${snapshot.android}")
        val refresh = stockRefreshIds(_catalog.value, _results.value)
        if (refresh.isNotEmpty()) runChecks(refresh)
        null
    }

    /** For adb imports: an adb caller already has shell rights, so its approval adds no new power. */
    suspend fun approveImportedPacks() {
        packs.value.filter { it.origin == PackOrigin.IMPORTED && !it.enabled && it.forThisDevice }.forEach { enablePack(it) }
    }

    val importDir: File get() = File(appContext.getExternalFilesDir(null), "import").apply { mkdirs() }

    /** A tester verdict on one test. Requirement-only tests keep it as a verdict, others as a result. */
    fun markTest(test: UnifiedTest, verdict: Verdict, note: String = "") {
        if (test.isRequirementOnly) {
            setVerdict(test.id, verdict, note)
            return
        }
        val status = when (verdict) {
            Verdict.PASS -> CheckStatus.PASS
            Verdict.FAIL -> CheckStatus.FAIL
            Verdict.NOT_APPLICABLE -> CheckStatus.NOT_APPLICABLE
            Verdict.UNTESTED -> {
                _results.update { (it - test.id).also(store::saveCheckResults) }
                scope.launch { exportReports(snapshot = false) }
                return
            }
        }
        val previous = _results.value[test.id]
        val label = when (verdict) {
            Verdict.PASS -> "Marked passed by the tester"
            Verdict.FAIL -> "Marked failed by the tester"
            else -> "Marked not applicable by the tester"
        }
        val details = listOfNotNull(
            note.takeIf { it.isNotBlank() }?.let { "Note: $it" },
            previous?.let { "Previous result: ${it.status} ${it.summary}\n${it.details}".trim() },
        ).joinToString("\n")
        saveTestResult(
            test.id,
            CheckResult(status, label + if (note.isNotBlank()) ": $note" else "", details, byTester = true, timestamp = System.currentTimeMillis(), artifacts = previous?.artifacts.orEmpty()),
        )
    }

    suspend fun history(limit: Int = 20): List<HistoryEntry> = withContext(Dispatchers.IO) {
        val files = File(reportsDir, "history").listFiles()?.sortedByDescending { it.name }?.take(limit).orEmpty()
        files.mapNotNull { file ->
            runCatching {
                val report = historyJson.decodeFromString(HistoryHeader.serializer(), file.readText())
                HistoryEntry(file.name, report.generatedAt, report.summary, report.device["ROM family"]?.let(::romVersionOf), report.conditions?.adbRoot == true)
            }.getOrNull()
        }
    }

    fun saveTestResult(testId: String, result: CheckResult) {
        _results.update {
            val updated = it + (testId to result)
            // A real tag read also proves the adapter works, superseding a stale probe failure.
            val confirmed = if (testId == "nfc.tag" && result.status == CheckStatus.PASS && !result.byTester) {
                updated + ("nfc.present" to result.copy(summary = "NFC adapter working: tag read"))
            } else updated
            confirmed.also(store::saveCheckResults)
        }
        Log.i(TAG, "$testId: ${result.status} ${result.summary}")
        scope.launch { exportReports(snapshot = true) }
    }

    fun setVerdict(requirementId: String, verdict: Verdict, note: String) {
        _verdicts.update { current ->
            val updated = if (verdict == Verdict.UNTESTED && note.isBlank()) current - requirementId
            else current + (requirementId to ManualVerdict(verdict, note, System.currentTimeMillis()))
            updated.also(store::saveVerdicts)
        }
        scope.launch { exportReports(snapshot = false) }
    }

    fun saveProfile(profile: DeviceProfile) {
        _profile.value = profile
        store.saveProfile(profile)
        scope.launch { exportReports(snapshot = false) }
    }

    /** Merges a partial profile JSON (only the given keys change) into the current one. */
    fun mergeProfileJson(partial: String) {
        val current = profileJson.encodeToJsonElement(DeviceProfile.serializer(), _profile.value).jsonObject
        val merged = JsonObject(current + profileJson.parseToJsonElement(partial).jsonObject)
        saveProfile(profileJson.decodeFromJsonElement(DeviceProfile.serializer(), merged))
    }

    fun buildReport(): JsonReport = buildJsonReport(
        _deviceInfo.value, _profile.value, _results.value, _verdicts.value, _catalog.value,
        shizukuReady = ShizukuShell.isReady, hasStock = _stock.value != null,
        buildType = android.os.Build.TYPE, now = RunConditions(ShizukuShell.uid, AdbRoot.state.value),
    )

    val lineageBased: Boolean get() = _deviceInfo.value.lineageBased

    suspend fun exportReports(snapshot: Boolean) = withContext(Dispatchers.IO) {
        AdbRoot.refresh()
        if (_deviceInfo.value.entries.isEmpty()) _deviceInfo.value = DeviceInfo.read(appContext)
        val report = buildReport()
        val dir = reportsDir
        val json = report.toJson()
        File(dir, "report.json").writeText(json)
        File(dir, "report.md").writeText(report.toMarkdown())
        File(dir, "report.html").writeText(report.toHtml())
        File(dir, "catalog.json").writeText(buildCatalog(_catalog.value.all, dir.absolutePath).toJson())
        if (snapshot) {
            val history = File(dir, "history").apply { mkdirs() }
            File(history, "${LocalDateTime.now().format(SNAPSHOT_FORMAT)}.json").writeText(json)
            history.listFiles()?.sortedByDescending { it.name }?.drop(MAX_SNAPSHOTS)?.forEach { it.delete() }
        }
        Log.i(TAG, "Reports written to ${dir.absolutePath}")
    }

    private fun writeStatus(state: String, detail: String) {
        runCatching { File(reportsDir, "status.json").writeText("""{"state":"$state","detail":"$detail","time":${System.currentTimeMillis()}}""") }
    }

    private inline fun runCatchingCheck(block: () -> CheckResult): CheckResult = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        CheckResult(CheckStatus.ERROR, "${e::class.java.simpleName}: ${e.message.orEmpty()}")
    }

    companion object {
        const val TAG = "DeviceChecker"
        private const val MAX_SNAPSHOTS = 50
        const val STOCK_FILE = "stock.json"
        private val SNAPSHOT_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
        private val profileJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        private val historyJson = Json { ignoreUnknownKeys = true }

        @Volatile
        private var instance: Repository? = null

        fun get(context: Context): Repository = instance ?: synchronized(this) {
            instance ?: Repository(context).also { instance = it }
        }
    }
}
