package io.github.rthery.devicechecker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import io.github.rthery.devicechecker.BuildConfig
import io.github.rthery.devicechecker.catalog.RequirementCatalog

private const val CDD_URL = "https://source.android.com/docs/compatibility/cdd"
private const val SHIZUKU_URL = "https://github.com/RikkaApps/Shizuku-API"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val uri = LocalUriHandler.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("About") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Android Device Checker ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleLarge)
            Text(
                "Tests an Android build, custom ROM or stock, on real hardware: automated checks, in-app tests that only ask " +
                    "you what a machine cannot judge, guided steps for the rest, and Markdown and JSON reports.",
                style = MaterialTheme.typography.bodyMedium,
            )

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Checklist", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "The requirements are paraphrased and adapted from the ${RequirementCatalog.SOURCE_TITLE}, " +
                            "licensed ${RequirementCatalog.SOURCE_LICENSE}. That document itself adapts parts of Google's Android " +
                            "Compatibility Definition Document. Items specific to LineageOS (its Updater, Recovery, browser, " +
                            "LiveDisplay, device tree and maintainer rules) only apply to LineageOS-based builds and show as N/A elsewhere.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "This app is not affiliated with or endorsed by the LineageOS project or Google.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { uri.openUri(RequirementCatalog.SOURCE_URL) }) { Text("LineageOS Device Support Requirements") }
                    TextButton(onClick = { uri.openUri(RequirementCatalog.SOURCE_LICENSE_URL) }) { Text("${RequirementCatalog.SOURCE_LICENSE} license") }
                    TextButton(onClick = { uri.openUri(CDD_URL) }) { Text("Android Compatibility Definition Document") }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Libraries", style = MaterialTheme.typography.titleMedium)
                    Text("Shizuku API (Apache 2.0) for optional shell-level reads.", style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { uri.openUri(SHIZUKU_URL) }) { Text("Shizuku API") }
                    Text("AndroidX, Jetpack Compose and kotlinx.serialization (Apache 2.0).", style = MaterialTheme.typography.bodyMedium)
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
