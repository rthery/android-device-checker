package io.github.rthery.devicechecker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.rthery.devicechecker.MainViewModel
import io.github.rthery.devicechecker.catalog.RequirementCatalog
import io.github.rthery.devicechecker.profile.DeviceProfile
import io.github.rthery.devicechecker.shell.ShizukuState

@Composable
fun DeviceTab(viewModel: MainViewModel, onEditProfile: () -> Unit, onPacks: () -> Unit, onAbout: () -> Unit, modifier: Modifier = Modifier) {
    val deviceInfo by viewModel.deviceInfo.collectAsStateWithLifecycle()
    val shizuku by viewModel.shizukuState.collectAsStateWithLifecycle()
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val packs by viewModel.packs.collectAsStateWithLifecycle()

    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Device", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                deviceInfo.entries.forEach { (key, value) ->
                    Text("$key: $value", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        ShizukuCard(shizuku, onRequest = viewModel::requestShizuku, onRefresh = viewModel::refreshShizuku)

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Stock profile", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = onEditProfile) { Text("Edit") }
        }
        Text(
            "What the stock ROM supports. Tests compare this build with it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            profileRows(profile).forEach { (key, value) ->
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(key, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        Surface(onClick = onPacks, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Device packs", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                val enabled = packs.filter { it.enabled }.mapNotNull { it.pack?.name }
                Text(
                    when {
                        enabled.isNotEmpty() -> "Enabled: ${enabled.joinToString()}"
                        packs.any { it.forThisDevice } -> "A pack for this device is waiting for your review"
                        else -> "None. Import one from your ROM maintainer"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Surface(onClick = onAbout, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("About", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(
                    "Checklist paraphrased and adapted from the ${RequirementCatalog.SOURCE_TITLE} (${RequirementCatalog.SOURCE_LICENSE}). Not affiliated with LineageOS or Google.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun yesNo(value: Boolean) = if (value) "Yes" else "No"

private fun profileRows(profile: DeviceProfile): List<Pair<String, String>> = listOf(
    "Resolution" to (if (profile.stockWidthPx != null && profile.stockHeightPx != null) "${profile.stockWidthPx} x ${profile.stockHeightPx}" else "Not set"),
    "Density" to (profile.stockDensityDpi?.let { "$it dpi" } ?: "Not set"),
    "Cameras" to "${profile.stockRearCameraCount ?: if (profile.stockHasRearCamera) "?" else 0} rear, ${profile.stockFrontCameraCount ?: if (profile.stockHasFrontCamera) "?" else 0} front",
    "Telephony" to yesNo(profile.stockHasTelephony),
    "NFC" to yesNo(profile.stockHasNfc),
    "Fingerprint" to yesNo(profile.stockHasFingerprint),
    "SD slot" to yesNo(profile.stockHasSdSlot),
    "Wi-Fi MAC" to profile.stockWifiMac.ifBlank { "Not set" },
    "Bluetooth MAC" to profile.stockBluetoothMac.ifBlank { "Not set" },
)

@Composable
private fun ShizukuCard(state: ShizukuState, onRequest: () -> Unit, onRefresh: () -> Unit) {
    val ready = state == ShizukuState.READY
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = if (ready) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (ready) "Shizuku connected" else "Shizuku (optional)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(
                when (state) {
                    ShizukuState.NOT_RUNNING -> "Not running. Tests that need shell access show as Check this, and the crash watcher is off."
                    ShizukuState.UNSUPPORTED -> "This Shizuku version is too old. Update it."
                    ShizukuState.PERMISSION_NEEDED -> "Running, permission not granted yet."
                    ShizukuState.CONNECTING -> "Connecting to the shell service..."
                    ShizukuState.READY -> "Shell-level reads and crash watching are on."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            when (state) {
                ShizukuState.PERMISSION_NEEDED -> Button(onClick = onRequest) { Text("Grant permission") }
                ShizukuState.NOT_RUNNING, ShizukuState.CONNECTING -> OutlinedButton(onClick = onRefresh) { Text("Retry") }
                else -> Unit
            }
        }
    }
}
