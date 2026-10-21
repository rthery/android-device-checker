package io.github.rthery.devicechecker.data

import android.content.Context
import io.github.rthery.devicechecker.checks.CheckResult
import io.github.rthery.devicechecker.interactive.GuidedDraft
import io.github.rthery.devicechecker.profile.DeviceProfile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

enum class Verdict { UNTESTED, PASS, FAIL, NOT_APPLICABLE }

@Serializable
data class ManualVerdict(val verdict: Verdict, val note: String = "", val timestamp: Long)

class Store(context: Context) {

    private val prefs = context.getSharedPreferences("device_checker", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun loadProfile(): DeviceProfile = read(KEY_PROFILE) ?: DeviceProfile()

    fun saveProfile(profile: DeviceProfile) = write(KEY_PROFILE, profile)

    fun loadCheckResults(): Map<String, CheckResult> = read(KEY_CHECKS) ?: emptyMap()

    fun saveCheckResults(results: Map<String, CheckResult>) = write(KEY_CHECKS, results)

    fun loadVerdicts(): Map<String, ManualVerdict> = read(KEY_VERDICTS) ?: emptyMap()

    fun saveVerdicts(verdicts: Map<String, ManualVerdict>) = write(KEY_VERDICTS, verdicts)

    fun loadCollapsedCategories(): Set<String> = prefs.getStringSet("collapsed_categories", setOf(Category.OFF_DEVICE.name))!!.toSet()

    fun saveCollapsedCategories(categories: Set<String>) {
        prefs.edit().putStringSet("collapsed_categories", categories.toSet()).apply()
    }

    fun loadRequirementPriority(): RequirementPriority = RequirementPriority.entries.firstOrNull {
        it.name == prefs.getString("requirement_priority", null)
    } ?: RequirementPriority.ALL

    fun saveRequirementPriority(priority: RequirementPriority) {
        prefs.edit().putString("requirement_priority", priority.name).apply()
    }

    fun loadStock(): StockSnapshot? = read(KEY_STOCK)

    fun saveStock(stock: StockSnapshot?) {
        if (stock == null) prefs.edit().remove(KEY_STOCK).apply() else write(KEY_STOCK, stock)
    }

    fun loadGuidedDraft(testId: String): GuidedDraft = read("$KEY_GUIDED_DRAFT$testId") ?: GuidedDraft()

    fun saveGuidedDraft(testId: String, draft: GuidedDraft) {
        if (draft.isEmpty()) prefs.edit().remove("$KEY_GUIDED_DRAFT$testId").apply() else write("$KEY_GUIDED_DRAFT$testId", draft)
    }

    private inline fun <reified T> read(key: String): T? =
        prefs.getString(key, null)?.let { runCatching { json.decodeFromString<T>(it) }.getOrNull() }

    private inline fun <reified T> write(key: String, value: T) {
        prefs.edit().putString(key, json.encodeToString(value)).apply()
    }

    private companion object {
        const val KEY_PROFILE = "profile"
        const val KEY_CHECKS = "check_results"
        const val KEY_VERDICTS = "verdicts"
        const val KEY_STOCK = "stock_snapshot"
        const val KEY_GUIDED_DRAFT = "guided_draft_"
    }
}
