package io.github.rthery.devicechecker.pack

import android.content.Context
import android.net.Uri
import android.os.Build
import io.github.rthery.devicechecker.catalog.RequirementCatalog
import io.github.rthery.devicechecker.shell.AppShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

enum class PackOrigin(val label: String) { SYSTEM("Shipped with the ROM"), IMPORTED("Imported") }

data class PackEntry(
    val file: File,
    val origin: PackOrigin,
    val result: PackParseResult,
    val forThisDevice: Boolean,
    val enabled: Boolean,
) {
    val pack: DevicePack? get() = (result as? PackParseResult.Valid)?.pack
    val key: String get() = "${origin.name}:${file.name}"
}

/**
 * Finds packs in /product/etc/devicechecker and /system_ext/etc/devicechecker, then imported ones,
 * and remembers which the user approved. An approval is tied to the file hash, so an edited pack needs a new one.
 */
class PackManager(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("device_packs", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val importDir: File get() = File(appContext.filesDir, "packs").apply { mkdirs() }

    private val _entries = MutableStateFlow<List<PackEntry>>(emptyList())
    val entries: StateFlow<List<PackEntry>> = _entries.asStateFlow()

    /** Valid, matching and approved packs, system ones first, then imported. */
    val enabledPacks: List<DevicePack> get() = _entries.value.filter { it.enabled }.mapNotNull { it.pack }

    suspend fun reload() = withContext(Dispatchers.IO) {
        val device = Build.DEVICE
        val props = mutableMapOf<String, String>()
        suspend fun prop(name: String) = props.getOrPut(name) { AppShell.exec("getprop $name").output.trim() }
        val approvals = loadApprovals()

        val files = SYSTEM_DIRS.flatMap { dir -> jsonFiles(File(dir)).map { it to PackOrigin.SYSTEM } } +
            jsonFiles(importDir).map { it to PackOrigin.IMPORTED }
        val loaded = files.map { (file, origin) ->
            val result = runCatching { PackParser.parse(file.readText(), RequirementCatalog.all) }
                .getOrElse { PackParseResult.Invalid(listOf("Cannot read ${file.path}: ${it.message}")) }
            val pack = (result as? PackParseResult.Valid)?.pack
            val conditions = pack?.match.orEmpty().associate { it.prop to prop(it.prop) }
            val forThisDevice = pack?.isFor(device) { conditions[it].orEmpty() } ?: false
            val hash = (result as? PackParseResult.Valid)?.sha256
            PackEntry(file, origin, result, forThisDevice, enabled = forThisDevice && hash != null && approvals[pack?.id] == hash)
        }
        // A later pack with the same id replaces an earlier one, so an import overrides the ROM's copy.
        val enabledIds = mutableSetOf<String>()
        _entries.value = loaded.asReversed().map { entry ->
            val id = entry.pack?.id
            if (entry.enabled && id != null && !enabledIds.add(id)) entry.copy(enabled = false) else entry
        }.asReversed()
    }

    /** Copies a picked file into app storage. Returns the parse errors, or null on success. */
    suspend fun import(uri: Uri): List<String>? = withContext(Dispatchers.IO) {
        val text = runCatching { appContext.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } }.getOrNull()
            ?: return@withContext listOf("Cannot read the file")
        if (text.length > MAX_PACK_BYTES) return@withContext listOf("File is larger than ${MAX_PACK_BYTES / 1024} KiB")
        when (val result = PackParser.parse(text, RequirementCatalog.all)) {
            is PackParseResult.Invalid -> result.errors
            is PackParseResult.Valid -> {
                File(importDir, "${result.pack.id}.json").writeText(text)
                reload()
                null
            }
        }
    }

    /** Records approval of exactly this file content. */
    suspend fun approve(entry: PackEntry) {
        val valid = entry.result as? PackParseResult.Valid ?: return
        if (!entry.forThisDevice) return
        saveApprovals(loadApprovals() + (valid.pack.id to valid.sha256))
        reload()
    }

    suspend fun disable(entry: PackEntry) {
        val id = entry.pack?.id ?: return
        saveApprovals(loadApprovals() - id)
        reload()
    }

    suspend fun remove(entry: PackEntry) {
        if (entry.origin != PackOrigin.IMPORTED) return
        withContext(Dispatchers.IO) { entry.file.delete() }
        disable(entry)
        reload()
    }

    private fun jsonFiles(dir: File): List<File> =
        dir.listFiles { file -> file.isFile && file.name.endsWith(".json") }?.sortedBy { it.name }.orEmpty()

    private fun loadApprovals(): Map<String, String> =
        prefs.getString(KEY_APPROVALS, null)?.let { runCatching { json.decodeFromString<Map<String, String>>(it) }.getOrNull() } ?: emptyMap()

    private fun saveApprovals(approvals: Map<String, String>) {
        prefs.edit().putString(KEY_APPROVALS, json.encodeToString(approvals)).apply()
    }

    companion object {
        val SYSTEM_DIRS = listOf("/product/etc/devicechecker", "/system_ext/etc/devicechecker")
        private const val KEY_APPROVALS = "approvals"
        private const val MAX_PACK_BYTES = 256 * 1024
    }
}
