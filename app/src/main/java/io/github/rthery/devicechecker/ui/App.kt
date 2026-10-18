package io.github.rthery.devicechecker.ui

import androidx.compose.runtime.LaunchedEffect
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import io.github.rthery.devicechecker.MainViewModel
import io.github.rthery.devicechecker.pack.ui.PacksScreen
import io.github.rthery.devicechecker.pack.ui.shapesFor
import io.github.rthery.devicechecker.pack.ui.withHints
import androidx.compose.material3.Typography
import androidx.lifecycle.compose.collectAsStateWithLifecycle

enum class Tab(val label: String, val icon: ImageVector) {
    TESTS("Tests", Icons.AutoMirrored.Filled.List),
    REPORT("Report", Icons.Filled.Share),
    DEVICE("Device", Icons.Filled.Settings),
}

private enum class Pushed { NONE, PROFILE, PACKS, ABOUT }

@Composable
fun App(viewModel: MainViewModel) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val hints by viewModel.theme.collectAsStateWithLifecycle()
    val system = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    val colors = system.withHints(hints, dark)
    var tab by rememberSaveable { mutableStateOf(Tab.TESTS) }
    var pushed by rememberSaveable { mutableStateOf(Pushed.NONE) }
    var activeTest by rememberSaveable { mutableStateOf<String?>(null) }
    val deepLink by viewModel.deepLink.collectAsStateWithLifecycle()
    LaunchedEffect(deepLink) {
        val link = deepLink ?: return@LaunchedEffect
        link.tab?.let { name -> Tab.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }?.let { tab = it } }
        link.test?.let { activeTest = it }
        viewModel.consumeDeepLink()
    }
    val testsListState = rememberLazyListState()

    MaterialTheme(colorScheme = colors, shapes = shapesFor(hints), typography = Typography().withHints(hints)) {
        val test = activeTest
        when {
            test != null -> {
                BackHandler { activeTest = null }
                TestRunScreen(viewModel, test, onDone = { activeTest = null })
            }
            pushed == Pushed.PROFILE -> {
                BackHandler { pushed = Pushed.NONE }
                ProfileScreen(viewModel, onBack = { pushed = Pushed.NONE })
            }
            pushed == Pushed.PACKS -> {
                BackHandler { pushed = Pushed.NONE }
                val packs by viewModel.packs.collectAsStateWithLifecycle()
                val importErrors by viewModel.packImportErrors.collectAsStateWithLifecycle()
                PacksScreen(
                    entries = packs,
                    importErrors = importErrors,
                    onImport = viewModel::importPack,
                    onDismissImportErrors = viewModel::dismissPackImportErrors,
                    onEnable = viewModel::enablePack,
                    onDisable = viewModel::disablePack,
                    onRemove = viewModel::removePack,
                    onBack = { pushed = Pushed.NONE },
                )
            }
            pushed == Pushed.ABOUT -> {
                BackHandler { pushed = Pushed.NONE }
                AboutScreen(onBack = { pushed = Pushed.NONE })
            }
            else -> {
                BackHandler(enabled = tab != Tab.TESTS) { tab = Tab.TESTS }
                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            Tab.entries.forEach { entry ->
                                NavigationBarItem(
                                    selected = tab == entry,
                                    onClick = { tab = entry },
                                    icon = { Icon(entry.icon, contentDescription = null) },
                                    label = { Text(entry.label) },
                                )
                            }
                        }
                    },
                ) { padding ->
                    val content = Modifier.padding(padding)
                    when (tab) {
                        Tab.TESTS -> TestsTab(
                            viewModel,
                            testsListState,
                            onOpenTest = { activeTest = it },
                            onOpenDevice = { tab = Tab.DEVICE },
                            modifier = content,
                        )
                        Tab.REPORT -> ReportTab(viewModel, modifier = content)
                        Tab.DEVICE -> DeviceTab(
                            viewModel,
                            onEditProfile = { pushed = Pushed.PROFILE },
                            onPacks = { pushed = Pushed.PACKS },
                            onAbout = { pushed = Pushed.ABOUT },
                            modifier = content,
                        )
                    }
                }
            }
        }
    }
}
