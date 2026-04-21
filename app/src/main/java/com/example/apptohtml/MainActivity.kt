package com.example.apptohtml

import android.content.Intent
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.apptohtml.crawler.CrawlerPhase
import com.example.apptohtml.crawler.CrawlerSession
import com.example.apptohtml.model.SelectedAppRef
import com.example.apptohtml.storage.SelectedAppRepository
import com.example.apptohtml.ui.theme.AppToHTMLTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        val repository = SelectedAppRepository(applicationContext)
        setContent {
            AppToHTMLTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    AppToHtmlScreen(
                        repository = repository,
                        modifier = Modifier.padding(innerPadding),
                    )
                }
            }
        }
    }
}

data class InstalledApp(
    val appName: String,
    val packageName: String,
    val launcherActivity: String,
)

@Composable
private fun AppToHtmlScreen(
    repository: SelectedAppRepository,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val selectedApp by repository.selectedAppFlow.collectAsState(initial = null)
    val crawlerState by CrawlerSession.uiState.collectAsState()
    val scope = rememberCoroutineScope()

    var availableApps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    var showPicker by remember { mutableStateOf(false) }
    var accessibilityEnabled by remember {
        mutableStateOf(AppDiscovery.isAccessibilityServiceEnabled(context, context.packageName))
    }
    val isSelfSelected = selectedApp?.packageName == context.packageName
    val canStartCapture = selectedApp != null && accessibilityEnabled && !isSelfSelected
    val openAccessibilitySettings = {
        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    LaunchedEffect(selectedApp?.packageName) {
        if (isSelfSelected) {
            repository.clearSelectedApp()
            return@LaunchedEffect
        }
    }

    LaunchedEffect(context) {
        while (true) {
            accessibilityEnabled = AppDiscovery.isAccessibilityServiceEnabled(context, context.packageName)
            delay(1_000L)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = "AppToHTML", style = MaterialTheme.typography.headlineMedium)
        HorizontalDivider()

        Text("Accessibility readiness", style = MaterialTheme.typography.titleMedium)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = openAccessibilitySettings)
                    .padding(end = 16.dp)
            ) {
                Text(
                    text = if (accessibilityEnabled) {
                        "AppToHTML accessibility access is enabled."
                    } else {
                        "AppToHTML accessibility access is disabled."
                    }
                )
                Text(
                    text = "Tap the switch to open Android Accessibility Settings and confirm the change.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(
                checked = accessibilityEnabled,
                onCheckedChange = { openAccessibilitySettings() },
            )
        }
        HorizontalDivider()

        Button(
            onClick = {
                availableApps = AppDiscovery.queryLauncherApps(
                    packageManager = context.packageManager,
                    excludePackageName = context.packageName,
                )
                showPicker = true
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Pick App")
        }

        if (selectedApp != null) {
            Text("Selected app: ${selectedApp?.appName}")
            Text("Package: ${selectedApp?.packageName}")
            Text("Launcher: ${selectedApp?.launcherActivity}")
            if (isSelfSelected) {
                Text("Please pick another app. AppToHTML cannot target itself.")
            }
        } else {
            Text("No app selected yet.")
        }
        HorizontalDivider()

        Text("Crawler", style = MaterialTheme.typography.titleMedium)
        Button(
            onClick = {
                selectedApp?.let { app ->
                    CrawlerSession.startCapture(context.applicationContext, app)
                }
            },
            enabled = canStartCapture,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Start Deep Crawl")
        }
        Text(crawlerState.statusMessage)
        if (!canStartCapture) {
            Text(
                text = when {
                    selectedApp == null -> "Pick an app before starting the crawler."
                    !accessibilityEnabled -> "Enable AppToHTML accessibility access before capturing."
                    isSelfSelected -> "Pick another app. AppToHTML cannot target itself."
                    else -> "The crawler is not ready yet."
                },
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            Text(
                text = "The crawler brings the target app to the foreground, resets to the entry screen, explores safe targets breadth-first, records captured, linked, and skipped outcomes, and saves a crawl index plus per-screen HTML and XML output for the upcoming graph viewer.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        when (crawlerState.phase) {
            CrawlerPhase.CAPTURED -> {
                Text("Root screen: ${crawlerState.screenName}")
                crawlerState.scrollStepCount?.let { stepCount ->
                    Text("Root scroll steps: $stepCount")
                }
                crawlerState.capturedScreenCount?.let { count ->
                    Text("Captured screens: $count")
                }
                crawlerState.capturedChildScreenCount?.let { count ->
                    Text("Captured child screens: $count")
                }
                crawlerState.skippedElementCount?.let { count ->
                    Text("Skipped targets: $count")
                }
                Text("Saved root HTML: ${crawlerState.htmlPath}")
                Text("Saved root XML: ${crawlerState.xmlPath}")
                crawlerState.mergedXmlPath?.let { path ->
                    Text("Saved merged accessibility XML: $path")
                }
                Text("Saved crawl index: ${crawlerState.crawlIndexPath}")
            }

            CrawlerPhase.ABORTED -> {
                Text("Partial crawl saved: ${crawlerState.failureMessage}")
                crawlerState.capturedScreenCount?.let { count ->
                    Text("Captured screens before abort: $count")
                }
                crawlerState.capturedChildScreenCount?.let { count ->
                    Text("Captured child screens before abort: $count")
                }
                crawlerState.skippedElementCount?.let { count ->
                    Text("Skipped targets: $count")
                }
                Text("Saved root HTML: ${crawlerState.htmlPath}")
                Text("Saved root XML: ${crawlerState.xmlPath}")
                crawlerState.mergedXmlPath?.let { path ->
                    Text("Saved merged accessibility XML: $path")
                }
                Text("Saved crawl index: ${crawlerState.crawlIndexPath}")
            }

            CrawlerPhase.FAILED -> {
                Text("Capture failed: ${crawlerState.failureMessage}")
            }

            else -> Unit
        }

        if (showPicker) {
            AlertDialog(
                onDismissRequest = { showPicker = false },
                confirmButton = {
                    TextButton(onClick = { showPicker = false }) {
                        Text("Close")
                    }
                },
                title = { Text("Choose Installed App") },
                text = {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(availableApps, key = { it.packageName }) { app ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        scope.launch {
                                            repository.saveSelectedApp(
                                                SelectedAppRef(
                                                    packageName = app.packageName,
                                                    appName = app.appName,
                                                    launcherActivity = app.launcherActivity,
                                                    selectedAt = System.currentTimeMillis(),
                                                )
                                            )
                                            showPicker = false
                                        }
                                    }
                                    .padding(vertical = 6.dp)
                            ) {
                                Text(text = app.appName)
                                Text(text = app.packageName, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                },
            )
        }
    }
}
