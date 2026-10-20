package io.github.rthery.devicechecker.pack.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.rthery.devicechecker.R
import io.github.rthery.devicechecker.pack.DevicePack
import io.github.rthery.devicechecker.pack.PackEntry
import io.github.rthery.devicechecker.pack.PackManager
import io.github.rthery.devicechecker.pack.PackOrigin
import io.github.rthery.devicechecker.pack.PackParseResult
import io.github.rthery.devicechecker.pack.approvalItems
import io.github.rthery.devicechecker.ui.Badge

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PacksScreen(
    entries: List<PackEntry>,
    importErrors: List<String>?,
    onImport: (Uri) -> Unit,
    onDismissImportErrors: () -> Unit,
    onEnable: (PackEntry) -> Unit,
    onDisable: (PackEntry) -> Unit,
    onRemove: (PackEntry) -> Unit,
    onBack: () -> Unit,
) {
    var reviewing by remember { mutableStateOf<PackEntry?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(onImport) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.screen_packs_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.screen_back)) } },
            )
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.screen_packs_intro, PackManager.SYSTEM_DIRS.joinToString(stringResource(R.string.screen_packs_dirs_separator))),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(onClick = { picker.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }) { Text(stringResource(R.string.screen_packs_import)) }
                }
            }
            if (entries.isEmpty()) {
                item { Text(stringResource(R.string.screen_packs_empty), style = MaterialTheme.typography.bodyMedium) }
            }
            items(entries, key = { it.key }) { entry ->
                PackCard(entry, onReview = { reviewing = entry }, onDisable = { onDisable(entry) }, onRemove = { onRemove(entry) })
            }
        }
    }

    reviewing?.let { entry ->
        val pack = entry.pack
        if (pack != null) {
            ApprovalDialog(pack, onDismiss = { reviewing = null }, onEnable = {
                reviewing = null
                onEnable(entry)
            })
        }
    }

    importErrors?.let { errors ->
        AlertDialog(
            onDismissRequest = onDismissImportErrors,
            title = { Text(stringResource(R.string.screen_packs_not_imported)) },
            text = { ErrorList(errors) },
            confirmButton = { TextButton(onClick = onDismissImportErrors) { Text(stringResource(R.string.screen_packs_ok)) } },
        )
    }
}

@Composable
private fun PackCard(entry: PackEntry, onReview: () -> Unit, onDisable: () -> Unit, onRemove: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val pack = entry.pack
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                when {
                    pack == null -> Badge(stringResource(R.string.screen_packs_badge_invalid), MaterialTheme.colorScheme.error)
                    entry.enabled -> Badge(stringResource(R.string.screen_packs_badge_enabled), MaterialTheme.colorScheme.primary)
                    !entry.forThisDevice -> Badge(stringResource(R.string.screen_packs_badge_not_for_build), MaterialTheme.colorScheme.outline)
                    else -> Badge(stringResource(R.string.screen_packs_badge_not_enabled), MaterialTheme.colorScheme.outline)
                }
                Text(pack?.name ?: entry.file.name, style = MaterialTheme.typography.titleSmall)
            }
            Text(stringResource(R.string.screen_packs_origin_path, stringResource(entry.origin.label), entry.file.path), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (pack != null) {
                Text(
                    stringResource(
                        R.string.screen_packs_summary,
                        "${pack.rom}${pack.romVersion?.let { " $it" }.orEmpty()}",
                        pack.devices.joinToString(),
                        pack.maintainer?.let { stringResource(R.string.screen_packs_summary_maintainer, it) }.orEmpty(),
                        pluralStringResource(R.plurals.screen_packs_tests, pack.tests.size, pack.tests.size),
                        pluralStringResource(R.plurals.screen_packs_waivers, pack.waivers.size, pack.waivers.size),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            (entry.result as? PackParseResult.Invalid)?.let { ErrorList(it.errors) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    pack == null -> Unit
                    entry.enabled -> OutlinedButton(onClick = onDisable) { Text(stringResource(R.string.screen_packs_disable)) }
                    entry.forThisDevice -> Button(onClick = onReview) { Text(stringResource(R.string.screen_packs_review_enable)) }
                }
                if (entry.origin == PackOrigin.IMPORTED) TextButton(onClick = onRemove) { Text(stringResource(R.string.screen_packs_remove)) }
            }
        }
    }
}

@Composable
private fun ApprovalDialog(pack: DevicePack, onDismiss: () -> Unit, onEnable: () -> Unit) {
    val items = pack.approvalItems()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.screen_packs_enable_title, pack.name)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(if (items.isEmpty()) R.string.screen_packs_no_actions else R.string.screen_packs_actions),
                    style = MaterialTheme.typography.bodyMedium,
                )
                items.forEach { Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
                pack.profile?.let { profile ->
                    HorizontalDivider()
                    Text(stringResource(R.string.screen_packs_profile_values), style = MaterialTheme.typography.titleSmall)
                    profile.forEach { (key, value) -> Text("$key = $value", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
                }
                if (pack.waivers.isNotEmpty()) {
                    HorizontalDivider()
                    Text(stringResource(R.string.screen_packs_waivers), style = MaterialTheme.typography.titleSmall)
                    pack.waivers.forEach { waiver ->
                        val scope = if (waiver.checks.isEmpty()) "" else " (${waiver.checks.joinToString()})"
                        Text("${waiver.requirement}$scope: ${waiver.reason}", style = MaterialTheme.typography.bodySmall)
                    }
                }
                pack.theme?.let { theme ->
                    HorizontalDivider()
                    Text(
                        stringResource(
                            R.string.screen_packs_theme,
                            listOfNotNull(
                                theme.accentSeed?.let { stringResource(R.string.screen_packs_theme_accent, it) },
                                theme.cornerScale?.let { stringResource(R.string.screen_packs_theme_corners, it.toString()) },
                                theme.fontFamily?.let { stringResource(R.string.screen_packs_theme_font, it) },
                                theme.icon?.let { stringResource(R.string.screen_packs_theme_icon, it) },
                            ).joinToString(),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onEnable) { Text(stringResource(R.string.screen_packs_enable)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.screen_packs_cancel)) } },
    )
}

@Composable
private fun ErrorList(errors: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        errors.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }
}
