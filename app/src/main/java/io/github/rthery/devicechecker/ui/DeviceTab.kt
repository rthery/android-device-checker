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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.rthery.devicechecker.MainViewModel
import io.github.rthery.devicechecker.R
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
        Text(stringResource(R.string.screen_device_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                deviceInfo.entries.forEach { (key, value) ->
                    Text(stringResource(R.string.screen_device_entry, key, value), style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        ShizukuCard(shizuku, onRequest = viewModel::requestShizuku, onRefresh = viewModel::refreshShizuku)

        StockBaselineCard(viewModel)

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.screen_device_stock_profile), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = onEditProfile) { Text(stringResource(R.string.screen_device_edit)) }
        }
        Text(
            stringResource(R.string.screen_device_stock_profile_hint),
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
                Text(stringResource(R.string.screen_device_packs), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                val enabled = packs.filter { it.enabled }.mapNotNull { it.pack?.name }
                Text(
                    when {
                        enabled.isNotEmpty() -> stringResource(R.string.screen_device_packs_enabled, enabled.joinToString())
                        packs.any { it.forThisDevice } -> stringResource(R.string.screen_device_packs_waiting)
                        else -> stringResource(R.string.screen_device_packs_none)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Surface(onClick = onAbout, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.screen_device_about), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(
                    stringResource(R.string.screen_device_about_summary, RequirementCatalog.SOURCE_TITLE, RequirementCatalog.SOURCE_LICENSE),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun yesNo(value: Boolean) = stringResource(if (value) R.string.yes else R.string.no)

@Composable
private fun profileRows(profile: DeviceProfile): List<Pair<String, String>> {
    val notSet = stringResource(R.string.screen_not_set)
    val width = profile.stockWidthPx
    val height = profile.stockHeightPx
    val rear = profile.stockRearCameraCount?.toString() ?: if (profile.stockHasRearCamera) "?" else "0"
    val front = profile.stockFrontCameraCount?.toString() ?: if (profile.stockHasFrontCamera) "?" else "0"
    return listOf(
        stringResource(R.string.screen_profile_row_resolution) to (if (width != null && height != null) stringResource(R.string.screen_profile_row_resolution_value, width, height) else notSet),
        stringResource(R.string.screen_profile_row_density) to (profile.stockDensityDpi?.let { stringResource(R.string.screen_profile_row_density_value, it) } ?: notSet),
        stringResource(R.string.screen_profile_row_cameras) to stringResource(R.string.screen_profile_row_cameras_value, rear, front),
        stringResource(R.string.screen_profile_row_telephony) to yesNo(profile.stockHasTelephony),
        stringResource(R.string.screen_profile_row_nfc) to yesNo(profile.stockHasNfc),
        stringResource(R.string.screen_profile_row_fingerprint) to yesNo(profile.stockHasFingerprint),
        stringResource(R.string.screen_profile_row_sd_slot) to yesNo(profile.stockHasSdSlot),
        stringResource(R.string.screen_profile_row_wifi_mac) to profile.stockWifiMac.ifBlank { notSet },
        stringResource(R.string.screen_profile_row_bluetooth_mac) to profile.stockBluetoothMac.ifBlank { notSet },
    )
}

@Composable
private fun ShizukuCard(state: ShizukuState, onRequest: () -> Unit, onRefresh: () -> Unit) {
    val ready = state == ShizukuState.READY
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = if (ready) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(if (ready) R.string.screen_shizuku_connected else R.string.screen_shizuku_optional), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(
                stringResource(
                    when (state) {
                        ShizukuState.NOT_RUNNING -> R.string.screen_shizuku_not_running
                        ShizukuState.UNSUPPORTED -> R.string.screen_shizuku_unsupported
                        ShizukuState.PERMISSION_NEEDED -> R.string.screen_shizuku_permission_needed
                        ShizukuState.CONNECTING -> R.string.screen_shizuku_connecting
                        ShizukuState.READY -> R.string.screen_shizuku_ready
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            when (state) {
                ShizukuState.PERMISSION_NEEDED -> Button(onClick = onRequest) { Text(stringResource(R.string.grant_permission)) }
                ShizukuState.NOT_RUNNING, ShizukuState.CONNECTING -> OutlinedButton(onClick = onRefresh) { Text(stringResource(R.string.screen_shizuku_retry)) }
                else -> Unit
            }
        }
    }
}
