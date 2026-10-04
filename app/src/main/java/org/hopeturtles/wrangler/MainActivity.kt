package org.hopeturtles.wrangler

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.hopeturtles.wrangler.ui.CardLabel
import org.hopeturtles.wrangler.ui.GreenOutlineButton
import org.hopeturtles.wrangler.ui.BatteryGraphScreen
import org.hopeturtles.wrangler.ui.BottleGraphScreen
import org.hopeturtles.wrangler.ui.MapPickerScreen
import org.hopeturtles.wrangler.ui.RunCommand
import org.hopeturtles.wrangler.ui.Format
import org.hopeturtles.wrangler.ui.Tab
import org.hopeturtles.wrangler.ui.TurtleHome
import org.hopeturtles.wrangler.ble.LinkState
import kotlinx.coroutines.delay
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import org.hopeturtles.wrangler.ui.Muted
import org.hopeturtles.wrangler.ui.ScanScreen
import org.hopeturtles.wrangler.ui.SplashScreen
import org.hopeturtles.wrangler.ui.WCard
import org.hopeturtles.wrangler.ui.WranglerTopBar
import org.hopeturtles.wrangler.ui.theme.Wrangler
import org.hopeturtles.wrangler.ui.theme.WranglerTheme

private val BLE_PERMISSIONS = arrayOf(
    Manifest.permission.BLUETOOTH_SCAN,
    Manifest.permission.BLUETOOTH_CONNECT,
)

class MainActivity : ComponentActivity() {
    private val vm: WranglerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WranglerTheme {
                // The splash covers the app while it starts underneath, then fades away.
                // Saveable: a rotation or theme change doesn't replay it.
                var splash by rememberSaveable { mutableStateOf(true) }
                Box(Modifier.fillMaxSize()) {
                    App(vm)
                    if (splash) SplashScreen { splash = false }
                }
            }
        }
    }
}

/** Permission and Bluetooth-on gates, then the app proper. */
@Composable
private fun App(vm: WranglerViewModel) {
    val ctx = LocalContext.current
    fun granted() = BLE_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
    }
    var hasPermission by remember { mutableStateOf(granted()) }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { hasPermission = granted() }

    var btOn by remember { mutableStateOf(vm.scanner.bluetoothOn) }
    val enableLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { btOn = vm.scanner.bluetoothOn }

    when {
        !hasPermission -> Gate(
            title = "Bluetooth permission needed",
            body = "Turtle Wrangler talks to turtles over Bluetooth from a few metres away. " +
                "It needs permission to find and connect to nearby devices. It never uses your location.",
            button = "Allow Bluetooth",
        ) { permLauncher.launch(BLE_PERMISSIONS) }

        !btOn -> Gate(
            title = "Bluetooth is off",
            body = "Turn Bluetooth on to find turtles nearby.",
            button = "Turn on Bluetooth",
        ) { enableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }

        else -> Main(vm)
    }
}

@Composable
private fun Main(vm: WranglerViewModel) {
    val link by vm.connection.collectAsStateWithLifecycle()
    val found by vm.scanner.found.collectAsStateWithLifecycle()
    val scanning by vm.scanner.scanning.collectAsStateWithLifecycle()
    val noTurtles by vm.noTurtles.collectAsStateWithLifecycle()
    val lastSeen by vm.lastSeen.collectAsStateWithLifecycle()
    val bond by vm.bondState.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var showSettings by remember { mutableStateOf(false) }

    if (showSettings) {
        SettingsPlaceholder { showSettings = false }
        return
    }

    val current = link
    if (current == null) {
        LaunchedEffect(Unit) { vm.startScan() }
        ScanScreen(
            found, scanning, noTurtles, lastSeen,
            onScan = vm::startScan, onPick = vm::connect, onSettings = { showSettings = true },
        )
    } else {
        val state by current.state.collectAsStateWithLifecycle()
        val tel by current.telemetry.collectAsStateWithLifecycle()
        val batt by vm.battHistory.collectAsStateWithLifecycle()
        var tab by rememberSaveable { mutableStateOf(Tab.DASHBOARD) }
        // Re-evaluate "live" every second: connected AND updated recently.
        val now by produceState(System.currentTimeMillis()) {
            while (true) { delay(1_000); value = System.currentTimeMillis() }
        }
        val live = state is LinkState.Ready && tel.lastUpdateMs > 0 &&
            now - tel.lastUpdateMs < Format.LIVE_STALE_MS
        val env by vm.envHistory.collectAsStateWithLifecycle()
        var showBattery by rememberSaveable { mutableStateOf(false) }
        var showBottle by rememberSaveable { mutableStateOf(false) }
        var showMap by rememberSaveable { mutableStateOf(false) }
        val run: RunCommand = { label, op, payload, onResult -> vm.command(label, op, payload, onResult) }
        val canCommand = state.let { it is LinkState.Ready && !it.readOnly }
        if (showMap) {
            MapPickerScreen(tel, canCommand, busy, notice, run, onBack = { showMap = false })
            return
        }
        if (showBattery) {
            BatteryGraphScreen(tel.name, batt, live, onBack = { showBattery = false })
            return
        }
        if (showBottle) {
            BottleGraphScreen(tel.name, tel.environment, env, live, onBack = { showBottle = false })
            return
        }
        TurtleHome(
            state = state, tel = tel, live = live, battHistory = batt,
            onBattery = { showBattery = true },
            envHistory = env,
            onBottle = { showBottle = true },
            tab = tab, onTab = { tab = it },
            bondState = bond, notice = notice, busy = busy, run = run, say = vm::say,
            onPickOnMap = { showMap = true },
            onPair = vm::pair,
            onTest = { vm.testCommand(tel) },
            onDisconnect = vm::disconnect,
            onSettings = { showSettings = true },
        )
    }
}

@Composable
private fun Gate(title: String, body: String, button: String, onClick: () -> Unit) {
    Scaffold(
        topBar = { WranglerTopBar("Turtle Wrangler", null, false, onSettings = {}) },
        containerColor = Wrangler.Background,
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            WCard {
                CardLabel(title)
                Muted(body)
            }
            GreenOutlineButton(button, onClick)
        }
    }
}

/** App Phase 6 builds the real Settings page; the gear already leads here. */
@Composable
private fun SettingsPlaceholder(onBack: () -> Unit) {
    Scaffold(
        topBar = { WranglerTopBar("Settings", null, false, onSettings = onBack) },
        containerColor = Wrangler.Background,
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            WCard {
                CardLabel("Coming in app Phase 6")
                Muted("WiFi, logging interval, Bluetooth lockdown, device info and the danger zone will live here.")
            }
            GreenOutlineButton("Back", onBack)
        }
    }
}
