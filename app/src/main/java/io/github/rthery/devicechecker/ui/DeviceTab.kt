package io.github.rthery.devicechecker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
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
    val shizukuRoot by viewModel.shizukuRoot.collectAsStateWithLifecycle()
    val adbRoot by viewModel.adbRoot.collectAsStateWithLifecycle()
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val packs by viewModel.packs.collectAsStateWithLifecycle()
    val scheme = MaterialTheme.colorScheme

    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(stringResource(R.string.screen_device_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Heading(stringResource(R.string.screen_device_conditions))
            Text(stringResource(R.string.screen_device_conditions_hint), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))
            AdbRootCard(adbRoot == true)
            ShizukuCard(shizuku, shizukuRoot, onRequest = viewModel::requestShizuku, onRefresh = viewModel::refreshShizuku)
            StockBaselineCard(viewModel)
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Heading(stringResource(R.string.screen_device_stock_profile), Modifier.weight(1f))
                TextButton(onClick = onEditProfile) { Text(stringResource(R.string.screen_device_edit)) }
            }
            Text(stringResource(R.string.screen_device_stock_profile_hint), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
            Rows(profileRows(profile)) { (key, value) ->
                Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(key, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = scheme.onSurfaceVariant)
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Heading(stringResource(R.string.screen_device_this_build))
            Rows(deviceInfo.entries) { (key, value) ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(key, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    Text(value, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        val enabled = packs.filter { it.enabled }.mapNotNull { it.pack?.name }
        Rows(
            listOf(
                Triple(
                    stringResource(R.string.screen_device_packs),
                    when {
                        enabled.isNotEmpty() -> stringResource(R.string.screen_device_packs_enabled, enabled.joinToString())
                        packs.any { it.forThisDevice } -> stringResource(R.string.screen_device_packs_waiting)
                        else -> stringResource(R.string.screen_device_packs_none)
                    },
                    onPacks,
                ),
                Triple(
                    stringResource(R.string.screen_device_about),
                    stringResource(R.string.screen_device_about_summary, RequirementCatalog.SOURCE_TITLE, RequirementCatalog.SOURCE_LICENSE),
                    onAbout,
                ),
            ),
        ) { (title, summary, onClick) ->
            Surface(onClick = onClick, color = Color.Transparent) {
                Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                        Text(summary, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
                }
            }
        }
    }
}

@Composable
private fun Heading(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = modifier.padding(horizontal = 4.dp).semantics { heading() })
}

@Composable
private fun <T> Rows(rows: List<T>, row: @Composable (T) -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column {
            rows.forEachIndexed { index, item ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHighest)
                row(item)
            }
        }
    }
}

/** A test condition: a state, why it matters, and what to do about it. */
@Composable
internal fun ConditionCard(
    title: String,
    text: String,
    icon: ImageVector,
    container: Color,
    content: Color,
    action: (@Composable () -> Unit)? = null,
) {
    Surface(shape = RoundedCornerShape(16.dp), color = container, contentColor = content) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                Icon(icon, null, Modifier.size(20.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(text, style = MaterialTheme.typography.bodyMedium)
                }
            }
            action?.invoke()
        }
    }
}

@Composable
private fun AdbRootCard(rooted: Boolean) {
    val scheme = MaterialTheme.colorScheme
    if (!rooted) {
        ConditionCard(stringResource(R.string.screen_adb_root_off), stringResource(R.string.screen_adb_root_off_hint), Icons.Filled.Check, scheme.surfaceContainer, scheme.onSurface)
        return
    }
    val clipboard = LocalClipboard.current
    val coroutines = rememberCoroutineScope()
    ConditionCard(stringResource(R.string.screen_adb_root_on), stringResource(R.string.screen_adb_root_on_hint), Icons.Filled.Warning, scheme.errorContainer, scheme.onErrorContainer) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "adb unroot", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f).background(scheme.surface.copy(alpha = 0.5f), RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 8.dp),
            )
            IconButton(onClick = { coroutines.launch { clipboard.setClipEntry(ClipEntry(android.content.ClipData.newPlainText("adb", "adb unroot"))) } }) { Icon(Glyphs.ContentCopy, stringResource(R.string.screen_copy_command)) }
        }
    }
}

@Composable
private fun ShizukuCard(state: ShizukuState, root: Boolean, onRequest: () -> Unit, onRefresh: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    when {
        state == ShizukuState.READY && root -> ConditionCard(
            stringResource(R.string.screen_shizuku_root_title), stringResource(R.string.screen_shizuku_root_hint), Icons.Filled.Warning, scheme.errorContainer, scheme.onErrorContainer,
        )
        state == ShizukuState.READY -> ConditionCard(
            stringResource(R.string.screen_shizuku_shell_title), stringResource(R.string.screen_shizuku_ready), Icons.Filled.Check, scheme.secondaryContainer, scheme.onSecondaryContainer,
        )
        state == ShizukuState.PERMISSION_NEEDED -> ConditionCard(
            stringResource(R.string.screen_shizuku_permission_title), stringResource(R.string.screen_shizuku_permission_needed), Glyphs.Block, scheme.surfaceContainerHighest, scheme.onSurface,
        ) { Button(onClick = onRequest) { Text(stringResource(R.string.grant_permission)) } }
        else -> ConditionCard(
            stringResource(R.string.screen_shizuku_off_title),
            stringResource(
                when (state) {
                    ShizukuState.UNSUPPORTED -> R.string.screen_shizuku_unsupported
                    ShizukuState.CONNECTING -> R.string.screen_shizuku_connecting
                    else -> R.string.screen_shizuku_not_running
                },
            ),
            Glyphs.Block, scheme.surfaceContainerHighest, scheme.onSurface,
        ) { if (state != ShizukuState.UNSUPPORTED) OutlinedButton(onClick = onRefresh) { Text(stringResource(R.string.screen_shizuku_retry)) } }
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
