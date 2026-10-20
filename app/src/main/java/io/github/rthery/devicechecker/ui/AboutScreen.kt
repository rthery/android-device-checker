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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.rthery.devicechecker.BuildConfig
import io.github.rthery.devicechecker.R
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
                title = { Text(stringResource(R.string.screen_about_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.screen_back)) } },
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
            Text(stringResource(R.string.screen_about_version, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.titleLarge)
            Text(
                stringResource(R.string.screen_about_intro),
                style = MaterialTheme.typography.bodyMedium,
            )

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.screen_about_checklist), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.screen_about_checklist_body, RequirementCatalog.SOURCE_TITLE, RequirementCatalog.SOURCE_LICENSE),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        stringResource(R.string.screen_about_not_affiliated),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { uri.openUri(RequirementCatalog.SOURCE_URL) }) { Text(stringResource(R.string.screen_about_link_requirements)) }
                    TextButton(onClick = { uri.openUri(RequirementCatalog.SOURCE_LICENSE_URL) }) { Text(stringResource(R.string.screen_about_link_license, RequirementCatalog.SOURCE_LICENSE)) }
                    TextButton(onClick = { uri.openUri(CDD_URL) }) { Text(stringResource(R.string.screen_about_link_cdd)) }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.screen_about_libraries), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.screen_about_shizuku), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { uri.openUri(SHIZUKU_URL) }) { Text(stringResource(R.string.screen_about_link_shizuku)) }
                    Text(stringResource(R.string.screen_about_androidx), style = MaterialTheme.typography.bodyMedium)
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
