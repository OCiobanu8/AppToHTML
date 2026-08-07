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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import com.example.apptohtml.crawler.CrawlStartIntent
import com.example.apptohtml.crawler.CrawlerPhase
import com.example.apptohtml.crawler.CrawlerSession
import com.example.apptohtml.crawler.PauseReason
import com.example.apptohtml.crawler.ResumeMode
import com.example.apptohtml.crawler.ScreenExpansionStatus
import com.example.apptohtml.crawler.SnapshotStatus
import com.example.apptohtml.model.SelectedAppRef
import com.example.apptohtml.storage.AllowedPackage
import com.example.apptohtml.storage.SelectedAppRepository
import com.example.apptohtml.storage.SavedCrawlRepository
import com.example.apptohtml.storage.SavedCrawlSnapshot
import com.example.apptohtml.storage.SavedScreenSummary
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
    val snapshotState by CrawlerSession.snapshotState.collectAsState()
    val scope = rememberCoroutineScope()
    val savedCrawlRepository = remember { SavedCrawlRepository(context.applicationContext) }

    var availableApps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    var showPicker by remember { mutableStateOf(false) }
    var showNewCrawlConfirmation by remember { mutableStateOf(false) }
    var savedCrawlSnapshot by remember { mutableStateOf<SavedCrawlSnapshot?>(null) }
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
        val packageName = selectedApp?.packageName ?: run {
            savedCrawlSnapshot = null
            return@LaunchedEffect
        }
        savedCrawlRepository.snapshotFlow(packageName).collect { snapshot ->
            savedCrawlSnapshot = snapshot
        }
    }

    LaunchedEffect(selectedApp?.packageName, crawlerState.phase) {
        if (
            crawlerState.phase == CrawlerPhase.CAPTURED ||
            crawlerState.phase == CrawlerPhase.ABORTED ||
            crawlerState.phase == CrawlerPhase.FAILED
        ) {
            savedCrawlRepository.refresh()
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
            .verticalScroll(rememberScrollState())
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
                    CrawlerSession.startCrawl(
                        context = context.applicationContext,
                        selectedApp = app,
                        crawlStartIntent = CrawlStartIntent.RESUME,
                        resumeMode = ResumeMode.ContinueAuto,
                    )
                }
            },
            enabled = canStartCapture,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Start Deep Crawl")
        }
        SavedCrawlSection(
            selectedApp = selectedApp,
            snapshot = savedCrawlSnapshot,
            canStartCapture = canStartCapture,
            onContinueAuto = { app ->
                CrawlerSession.startCrawl(
                    context = context.applicationContext,
                    selectedApp = app,
                    crawlStartIntent = CrawlStartIntent.RESUME,
                    resumeMode = ResumeMode.ContinueAuto,
                )
            },
            onResumeFromScreen = { app, screenId ->
                CrawlerSession.startCrawl(
                    context = context.applicationContext,
                    selectedApp = app,
                    crawlStartIntent = CrawlStartIntent.RESUME,
                    resumeMode = ResumeMode.ResumeFromScreen(screenId),
                )
            },
            onReExpandScreen = { app, screenId ->
                CrawlerSession.startCrawl(
                    context = context.applicationContext,
                    selectedApp = app,
                    crawlStartIntent = CrawlStartIntent.RESUME,
                    resumeMode = ResumeMode.ReExpand(screenId),
                )
            },
            onNewCrawl = { showNewCrawlConfirmation = true },
            onRevokeApproval = { edgeId ->
                selectedApp?.packageName?.let { packageName ->
                    if (savedCrawlRepository.revokeApproval(packageName, edgeId)) {
                        savedCrawlRepository.refresh()
                    }
                }
            },
        )
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
            CrawlerPhase.PAUSED_FOR_DECISION -> {
                Text("Deep crawl paused: ${crawlerState.pauseReason?.let(::pauseReasonText) ?: "Waiting for a decision."}")
                crawlerState.pauseElapsedTimeMs?.let { elapsedTimeMs ->
                    Text("Elapsed time: ${formatPauseElapsedTime(elapsedTimeMs)}")
                }
                crawlerState.pauseFailedEdgeCount?.let { count ->
                    Text("Failed edges: $count")
                }
                crawlerState.pausedCapturedScreenCount?.let { count ->
                    Text("Captured screens so far: $count")
                }
                crawlerState.pausedCapturedChildScreenCount?.let { count ->
                    Text("Captured child screens so far: $count")
                }
                if (crawlerState.pauseReason == PauseReason.EXTERNAL_PACKAGE_BOUNDARY) {
                    crawlerState.pauseCurrentPackageName?.let { packageName ->
                        Text("Current package: $packageName")
                    }
                    crawlerState.pauseNextPackageName?.let { packageName ->
                        Text("Next package: $packageName")
                    }
                    crawlerState.pauseTriggerLabel?.let { label ->
                        Text("Trigger label: $label")
                    }
                }
            }

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
                crawlerState.maxDepthReached?.let { depth ->
                    Text("Max depth reached: $depth")
                }
                Text("Saved root HTML: ${crawlerState.htmlPath}")
                Text("Saved root XML: ${crawlerState.xmlPath}")
                crawlerState.mergedXmlPath?.let { path ->
                    Text("Saved merged accessibility XML: $path")
                }
                Text("Saved crawl index: ${crawlerState.crawlIndexPath}")
                crawlerState.graphJsonPath?.let { path ->
                    Text("Saved graph JSON: $path")
                }
                crawlerState.graphHtmlPath?.let { path ->
                    Text("Saved graph viewer: $path")
                }
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
                crawlerState.maxDepthReached?.let { depth ->
                    Text("Max depth reached before abort: $depth")
                }
                Text("Saved root HTML: ${crawlerState.htmlPath}")
                Text("Saved root XML: ${crawlerState.xmlPath}")
                crawlerState.mergedXmlPath?.let { path ->
                    Text("Saved merged accessibility XML: $path")
                }
                Text("Saved crawl index: ${crawlerState.crawlIndexPath}")
                crawlerState.graphJsonPath?.let { path ->
                    Text("Saved graph JSON: $path")
                }
                crawlerState.graphHtmlPath?.let { path ->
                    Text("Saved graph viewer: $path")
                }
            }

            CrawlerPhase.FAILED -> {
                Text("Capture failed: ${crawlerState.failureMessage}")
            }

            else -> Unit
        }

        // Snapshot results are reported separately from the crawl phases above: a snapshot is
        // triggered by broadcast while this app is in the background, so this is purely a record of
        // what the last capture did.
        when (snapshotState.status) {
            SnapshotStatus.CAPTURING -> {
                Text("Snapshot: capturing ${snapshotState.packageName.orEmpty()}…")
            }

            SnapshotStatus.CAPTURED -> {
                Text("Last snapshot: ${snapshotState.screenName} (${snapshotState.packageName})")
                Text("Elements: ${snapshotState.elementCount}; scroll steps: ${snapshotState.scrollStepCount}")
                snapshotState.directoryPath?.let { path ->
                    Text("Saved to: $path")
                }
            }

            SnapshotStatus.FAILED -> {
                Text("Last snapshot failed: ${snapshotState.message.orEmpty()}")
            }

            SnapshotStatus.IDLE -> Unit
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

        if (crawlerState.phase == CrawlerPhase.PAUSED_FOR_DECISION) {
            PauseDecisionDialog(crawlerState = crawlerState)
        }

        if (showNewCrawlConfirmation) {
            val app = selectedApp
            AlertDialog(
                onDismissRequest = { showNewCrawlConfirmation = false },
                title = { Text("New Crawl") },
                text = {
                    Text("Wipe the saved crawl for ${app?.appName ?: "this app"}? This deletes all captured screens for this app.")
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showNewCrawlConfirmation = false
                            app?.let {
                                CrawlerSession.startCrawl(
                                    context = context.applicationContext,
                                    selectedApp = it,
                                    crawlStartIntent = CrawlStartIntent.NEW_CRAWL,
                                    resumeMode = ResumeMode.ContinueAuto,
                                )
                            }
                        }
                    ) {
                        Text("New Crawl")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showNewCrawlConfirmation = false }) {
                        Text("Cancel")
                    }
                },
            )
        }
    }
}

@Composable
private fun SavedCrawlSection(
    selectedApp: SelectedAppRef?,
    snapshot: SavedCrawlSnapshot?,
    canStartCapture: Boolean,
    onContinueAuto: (SelectedAppRef) -> Unit,
    onResumeFromScreen: (SelectedAppRef, String) -> Unit,
    onReExpandScreen: (SelectedAppRef, String) -> Unit,
    onNewCrawl: () -> Unit,
    onRevokeApproval: (String) -> Unit,
) {
    HorizontalDivider()
    Text("Saved crawl", style = MaterialTheme.typography.titleMedium)

    val app = selectedApp
    if (app == null || snapshot == null || snapshot.screens.isEmpty()) {
        Text("No saved crawl. Tap Start Deep Crawl to begin one.")
        return
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(
            onClick = { onContinueAuto(app) },
            enabled = canStartCapture && snapshot.hasPendingWork,
            modifier = Modifier.weight(1f),
        ) {
            Text("Continue auto")
        }
        TextButton(
            onClick = onNewCrawl,
            enabled = canStartCapture,
        ) {
            Text("New Crawl")
        }
    }
    if (!snapshot.hasPendingWork) {
        Text(
            text = "Crawl complete. Re-expand a screen to refine.",
            style = MaterialTheme.typography.bodySmall,
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        snapshot.screens.forEach { screen ->
            SavedScreenRow(
                app = app,
                screen = screen,
                canStartCapture = canStartCapture,
                onResumeFromScreen = onResumeFromScreen,
                onReExpandScreen = onReExpandScreen,
            )
        }
    }

    if (snapshot.allowedPackages.isNotEmpty()) {
        Text("Allowed external packages", style = MaterialTheme.typography.titleSmall)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            snapshot.allowedPackages.forEach { allowedPackage ->
                AllowedPackageRow(
                    allowedPackage = allowedPackage,
                    onRevokeApproval = onRevokeApproval,
                )
            }
        }
    }
}

@Composable
private fun SavedScreenRow(
    app: SelectedAppRef,
    screen: SavedScreenSummary,
    canStartCapture: Boolean,
    onResumeFromScreen: (SelectedAppRef, String) -> Unit,
    onReExpandScreen: (SelectedAppRef, String) -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (screen.depth * 16).dp, top = 4.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(screen.screenName)
            Text(
                text = screen.screenId,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(
            text = screenStatusText(screen.expansionStatus),
            style = MaterialTheme.typography.bodySmall,
        )
        if (screen.pendingEdgeCount > 0) {
            Text(
                text = "${screen.pendingEdgeCount} pending",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        TextButton(
            onClick = { menuExpanded = true },
            enabled = canStartCapture,
        ) {
            Text("...")
        }
        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
        ) {
            DropdownMenuItem(
                text = { Text("Resume from here") },
                onClick = {
                    menuExpanded = false
                    onResumeFromScreen(app, screen.screenId)
                },
                enabled = canStartCapture,
            )
            DropdownMenuItem(
                text = { Text("Re-expand") },
                onClick = {
                    menuExpanded = false
                    onReExpandScreen(app, screen.screenId)
                },
                enabled = canStartCapture,
            )
        }
    }
}

@Composable
private fun AllowedPackageRow(
    allowedPackage: AllowedPackage,
    onRevokeApproval: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(allowedPackage.packageName)
            Text(
                text = buildString {
                    append("approved from ")
                    append(allowedPackage.parentScreenName)
                    allowedPackage.triggerLabel?.let { label ->
                        append(" -> ")
                        append(label)
                    }
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
        TextButton(
            onClick = {
                onRevokeApproval(allowedPackage.edgeId)
            }
        ) {
            Text("Revoke")
        }
    }
}

private fun screenStatusText(status: ScreenExpansionStatus): String {
    return when (status) {
        ScreenExpansionStatus.COMPLETE -> "complete"
        ScreenExpansionStatus.IN_PROGRESS -> "in progress"
        ScreenExpansionStatus.NOT_STARTED -> "not started"
    }
}

@Composable
private fun PauseDecisionDialog(crawlerState: com.example.apptohtml.crawler.CrawlerUiState) {
    val requestId = crawlerState.requestId ?: return
    val decisionId = crawlerState.pauseDecisionId ?: return
    val isExternalPackagePause = crawlerState.pauseReason == PauseReason.EXTERNAL_PACKAGE_BOUNDARY

    AlertDialog(
        onDismissRequest = {},
        title = { Text("Deep Crawl Paused") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(pauseReasonText(crawlerState.pauseReason))
                crawlerState.pauseElapsedTimeMs?.let { elapsedTimeMs ->
                    Text("Elapsed time: ${formatPauseElapsedTime(elapsedTimeMs)}")
                }
                crawlerState.pauseFailedEdgeCount?.let { count ->
                    Text("Failed edges: $count")
                }
                crawlerState.pausedCapturedScreenCount?.let { count ->
                    Text("Captured screens so far: $count")
                }
                crawlerState.pausedCapturedChildScreenCount?.let { count ->
                    Text("Captured child screens so far: $count")
                }
                if (isExternalPackagePause) {
                    crawlerState.pauseCurrentPackageName?.let { packageName ->
                        Text("Current package: $packageName")
                    }
                    crawlerState.pauseNextPackageName?.let { packageName ->
                        Text("Next package: $packageName")
                    }
                    crawlerState.pauseTriggerLabel?.let { label ->
                        Text("Trigger label: $label")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    CrawlerSession.resumeCrawl(
                        requestId = requestId,
                        decisionId = decisionId,
                    )
                }
            ) {
                Text(
                    if (isExternalPackagePause) {
                        "Continue outside package"
                    } else {
                        "Continue"
                    }
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    if (isExternalPackagePause) {
                        CrawlerSession.skipExternalEdge(
                            requestId = requestId,
                            decisionId = decisionId,
                        )
                    } else {
                        CrawlerSession.stopAndSave(
                            requestId = requestId,
                            decisionId = decisionId,
                        )
                    }
                }
            ) {
                Text(
                    if (isExternalPackagePause) {
                        "Skip edge"
                    } else {
                        "Stop and save"
                    }
                )
            }
        },
    )
}

private fun pauseReasonText(reason: PauseReason?): String {
    return when (reason) {
        PauseReason.ELAPSED_TIME_EXCEEDED ->
            "The crawl reached its elapsed-time checkpoint and is waiting for your decision."

        PauseReason.FAILED_EDGE_COUNT_EXCEEDED ->
            "The crawl reached its failed-edge checkpoint and is waiting for your decision."

        PauseReason.EXTERNAL_PACKAGE_BOUNDARY ->
            "The crawl is about to continue into another package and needs your approval."

        null -> "The crawl is paused and waiting for your decision."
    }
}

private fun formatPauseElapsedTime(elapsedTimeMs: Long): String {
    val totalSeconds = elapsedTimeMs / 1_000L
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        String.format("%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%02d:%02d", minutes, seconds)
    }
}
