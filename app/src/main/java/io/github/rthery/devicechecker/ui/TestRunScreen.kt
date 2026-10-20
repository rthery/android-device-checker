package io.github.rthery.devicechecker.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.rthery.devicechecker.R
import io.github.rthery.devicechecker.MainViewModel
import io.github.rthery.devicechecker.catalog.RequirementCatalog
import io.github.rthery.devicechecker.interactive.TestScope
import io.github.rthery.devicechecker.shell.ShizukuShell

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TestRunScreen(viewModel: MainViewModel, testId: String, onDone: () -> Unit) {
    val catalog = viewModel.catalog.collectAsStateWithLifecycle().value
    val test = catalog.interactiveById[testId] ?: return onDone()
    val activity = LocalActivity.current as ComponentActivity
    val view = LocalView.current
    DisposableEffect(view) {
        // Interactive tests do not publish automatic-run progress. Keep their visible
        // screen awake too, without changing the automatic runner's window flags.
        val previous = view.keepScreenOn
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = previous }
    }
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val results by viewModel.results.collectAsStateWithLifecycle()
    val scope = remember(testId, profile) {
        TestScope(activity, profile, ShizukuShell.takeIf { it.isReady }) { result ->
            viewModel.saveTestResult(testId, result)
            android.widget.Toast.makeText(activity, activity.getString(R.string.tests_run_result_saved, result.summary), android.widget.Toast.LENGTH_LONG).show()
            onDone()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(test.title) },
                navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.tests_run_back)) } },
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
            Text(test.summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                stringResource(if (scope.crashWatcher.available) R.string.tests_run_crash_watcher_on else R.string.tests_run_crash_watcher_off),
                style = MaterialTheme.typography.labelSmall,
                color = if (scope.crashWatcher.available) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
            results[testId]?.let { previous ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Badge(previous.status.name.replace('_', ' '), previous.status.color())
                    Text(stringResource(R.string.tests_run_last_result, previous.summary), style = MaterialTheme.typography.bodySmall)
                }
            }
            test.content(scope)
            Spacer(Modifier.height(16.dp))
        }
    }
}
