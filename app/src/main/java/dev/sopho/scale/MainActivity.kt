package dev.sopho.scale

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.sopho.scale.ui.DebugScreen
import dev.sopho.scale.ui.HistoryScreen
import dev.sopho.scale.ui.PeopleScreen
import dev.sopho.scale.ui.ReportScreen
import dev.sopho.scale.ui.ScaleScreen
import dev.sopho.scale.ui.ScaleViewModel
import dev.sopho.scale.ui.SettingsScreen
import dev.sopho.scale.ui.theme.BleWeightScaleTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BleWeightScaleTheme {
                ScaleApp()
            }
        }
    }
}

private enum class Tab(val label: String, val icon: ImageVector) {
    SCALE("称重", Icons.Default.Home),
    HISTORY("记录", Icons.AutoMirrored.Filled.List),
    SETTINGS("设置", Icons.Default.Settings),
    DEBUG("日志", Icons.Default.Build),
}

/** Permissions the scan needs, which differ across the Android 12 Bluetooth permission split. */
private fun requiredBlePermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

@Composable
private fun ScaleApp(viewModel: ScaleViewModel = viewModel()) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var subScreen by rememberSaveable { androidx.compose.runtime.mutableStateOf<String?>(null) }
    var reportId by rememberSaveable { androidx.compose.runtime.mutableStateOf<String?>(null) }
    var showUnclaimed by rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { viewModel.startScanning() }

    // Ask once on first composition; the user can retry from the weighing screen afterwards.
    LaunchedEffect(Unit) {
        if (!viewModel.hasPermission()) {
            permissionLauncher.launch(requiredBlePermissions())
        }
    }

    // Scanning only makes sense while the app is on screen, so it is tied to the activity
    // lifecycle rather than to a button the user has to remember to press.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    if (viewModel.hasPermission()) viewModel.startScanning()
                }
                Lifecycle.Event.ON_STOP -> viewModel.stopScanning()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val toast by viewModel.toast.collectAsStateWithLifecycle()
    LaunchedEffect(toast) {
        toast?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeToast()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index; subScreen = null; showUnclaimed = false },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when (subScreen) {
                "people" -> PeopleScreen(viewModel, onBack = { subScreen = null })
                "report" -> {
                    val records by viewModel.history.collectAsStateWithLifecycle()
                    val people by viewModel.people.collectAsStateWithLifecycle()
                    val record = records.firstOrNull { it.id == reportId }
                    ReportScreen(
                        record = record,
                        person = people.firstOrNull { it.id == record?.personId },
                        onBack = { subScreen = null },
                        onHistory = { subScreen = null; selectedTab = Tab.HISTORY.ordinal },
                    )
                }
                else -> when (Tab.entries[selectedTab]) {
                Tab.SCALE -> ScaleScreen(
                    viewModel = viewModel,
                    onRequestPermissions = {
                        permissionLauncher.launch(requiredBlePermissions())
                    },
                    onOpenSettings = { selectedTab = Tab.SETTINGS.ordinal },
                    onEnableBluetooth = { context.openSystemSettings(Settings.ACTION_BLUETOOTH_SETTINGS) },
                    onEnableLocation = {
                        context.openSystemSettings(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                    },
                    onOpenPeople = { subScreen = "people" },
                    onOpenReport = { reportId = it; subScreen = "report" },
                    onOpenHistory = { unclaimed -> showUnclaimed = unclaimed; selectedTab = Tab.HISTORY.ordinal },
                )
                Tab.HISTORY -> HistoryScreen(
                    viewModel,
                    initialUnclaimed = showUnclaimed,
                    onReport = { reportId = it; subScreen = "report" },
                    onManagePeople = { subScreen = "people" },
                )
                Tab.SETTINGS -> SettingsScreen(viewModel)
                Tab.DEBUG -> DebugScreen(viewModel)
                }
            }
        }
    }
}

/** Opens a system settings page, tolerating devices that do not implement the requested one. */
private fun Context.openSystemSettings(action: String) {
    runCatching {
        startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
