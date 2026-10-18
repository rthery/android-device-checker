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
import io.github.rthery.devicechecker.shell.ShizukuShell
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
import kotlinx.coroutines.launch
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

data class RunProgress(val done: Int, val total: Int, val current: String, val currentId: String, val pendingIds: Set<String>)

data class RunSummary(val ran: Int, val failed: Int, val stopped: Boolean)

data class HistoryEntry(val file: String, val generatedAt: String, val summary: Map<String, Int>)

@kotlinx.serialization.Serializable
private data class HistoryHeader(val generatedAt: String = "", val summary: Map<String, Int> = emptyMap())

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

    init {
        scope.launch { _deviceInfo.value = DeviceInfo.read() }
        scope.launch { reloadPacks() }
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
    fun startRun(ids: Set<String>? = null, options: Map<String, Int> = emptyMap()) {
        if (runJob?.isActive == true) return
        runJob = scope.launch { runChecks(ids, options) }
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

    suspend fun runChecks(ids: Set<String>? = null, options: Map<String, Int> = emptyMap()) = runMutex.withLock {
        writeStatus("running", "starting")
        ShizukuShell.awaitReady()
        val context = CheckContext(appContext, _profile.value, ShizukuShell.takeIf { it.isReady }, DeviceInfo.isLineageBased(), reportsDir, options, _stock.value)
        val checks = if (ids == null) _catalog.value.checks
        else (_catalog.value.checks + HeadlessRegistry.all).filter { it.id in ids }.distinctBy { it.id }
        var ran = 0
        var stopped = false
        try {
            checks.forEachIndexed { index, check ->
                val pending = checks.drop(index + 1).map { it.id }.toSet()
                _progress.value = RunProgress(index, checks.size, check.title, check.id, pending)
                writeStatus("running", "${index + 1}/${checks.size} ${check.id}")
                val startedAt = context.crashWatcher.mark()
                val result = runCatchingCheck { check.run(context) }
                    .withCrashes(context.crashWatcher.crashesSince(startedAt))
                    .copy(timestamp = System.currentTimeMillis())
                Log.i(TAG, "${check.id}: ${result.status} ${result.summary}")
                _results.update { it + (check.id to result) }
                ran++
            }
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

    /** Captures what this ROM reports into reports/stock.json; meant to run on the stock ROM. */
    suspend fun captureStock(): File = withContext(Dispatchers.IO) {
        ShizukuShell.awaitReady()
        val context = CheckContext(appContext, _profile.value, ShizukuShell.takeIf { it.isReady }, DeviceInfo.isLineageBased(), reportsDir)
        val snapshot = context.captureStock()
        File(reportsDir, STOCK_FILE).apply { writeText(stockJson.encodeToString(StockSnapshot.serializer(), snapshot)) }
            .also { Log.i(TAG, "Stock capture written to ${it.absolutePath}") }
    }

    /** Loads a stock capture: it replaces the stock profile and enables comparisons. Returns an error or null. */
    suspend fun importStock(text: String): String? = withContext(Dispatchers.IO) {
        val snapshot = runCatching { stockJson.decodeFromString(StockSnapshot.serializer(), text) }.getOrElse { return@withContext "Not a stock capture: ${it.message}" }
        if (snapshot.codename != android.os.Build.DEVICE) Log.w(TAG, "Stock capture is for ${snapshot.codename}, this device is ${android.os.Build.DEVICE}")
        _stock.value = snapshot
        store.saveStock(snapshot)
        withContext(Dispatchers.Main) { saveProfile(snapshot.profile) }
        Log.i(TAG, "Stock capture imported: ${snapshot.device}, Android ${snapshot.android}")
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
                HistoryEntry(file.name, report.generatedAt, report.summary)
            }.getOrNull()
        }
    }

    fun saveTestResult(testId: String, result: CheckResult) {
        _results.update { (it + (testId to result)).also(store::saveCheckResults) }
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
    )

    val lineageBased: Boolean get() = _deviceInfo.value.lineageBased

    suspend fun exportReports(snapshot: Boolean) = withContext(Dispatchers.IO) {
        if (_deviceInfo.value.entries.isEmpty()) _deviceInfo.value = DeviceInfo.read()
        val report = buildReport()
        val dir = reportsDir
        val json = report.toJson()
        File(dir, "report.json").writeText(json)
        File(dir, "report.md").writeText(report.toMarkdown())
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
