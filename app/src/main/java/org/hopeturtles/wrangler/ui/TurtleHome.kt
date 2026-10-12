package org.hopeturtles.wrangler.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.zIndex
import org.hopeturtles.wrangler.BattSample
import org.hopeturtles.wrangler.EnvSample
import org.hopeturtles.wrangler.Notice
import org.hopeturtles.wrangler.ble.Handshake
import org.hopeturtles.wrangler.ble.LinkState
import org.hopeturtles.wrangler.ble.TurtleTelemetry
import org.hopeturtles.wrangler.data.StampEntry
import org.hopeturtles.wrangler.ui.theme.Wrangler

/** The four bottom tabs. Settings is not a tab — it's the gear, top-right. */
enum class Tab(val label: String, val icon: ImageVector) {
    DASHBOARD("Dashboard", Icons.Filled.Home),
    NAVIGATE("Navigate", @Suppress("DEPRECATION") Icons.Filled.Send),
    GPS("GPS", Icons.Filled.LocationOn),
    DIAGNOSTICS("Diagnostics", Icons.Filled.Build),
}

@Composable
fun TurtleHome(
    state: LinkState,
    handshake: Handshake,
    tel: TurtleTelemetry,
    live: Boolean,
    battHistory: List<BattSample>,
    onBattery: () -> Unit,
    envHistory: List<EnvSample>,
    onBottle: () -> Unit,
    tab: Tab,
    onTab: (Tab) -> Unit,
    bondState: Int,
    notice: Notice?,
    busy: String?,
    run: RunCommand,
    say: (String, Boolean) -> Unit,
    onPickOnMap: () -> Unit,
    onServo: (Int) -> Unit,
    stampLog: List<StampEntry>,
    onStamp: () -> Unit,
    onSetClock: () -> Unit,
    onSecureMode: (Boolean) -> Unit,
    onPair: () -> Unit,
    onDisconnect: () -> Unit,
    settingsOpen: Boolean,
    onSettings: (Boolean) -> Unit,
    quietRun: RunCommand,
) {
    // Settings sits over the tabs: back, or any tab, closes it.
    BackHandler(enabled = settingsOpen) { onSettings(false) }
    // The turtle logo's colour says connected. Setup progress lives in the
    // connecting drawer, not here; text only for read-only and disconnected.
    val (statusText, ok) = when (state) {
        is LinkState.Connecting, is LinkState.Preparing -> null to false
        is LinkState.Ready -> (if (state.readOnly) "Read-only" else null) to true
        is LinkState.Disconnected -> "Disconnected" to false
    }
    Scaffold(
        topBar = {
            // Once the link has dropped, the status is the way back: tap to scan again.
            WranglerTopBar(tel.name ?: "Turtle", statusText, ok, onSettings = { onSettings(!settingsOpen) },
                onStatusClick = if (state is LinkState.Disconnected) onDisconnect else null,
                connected = state is LinkState.Ready, settingsOpen = settingsOpen)
        },
        bottomBar = {
            NavigationBar(containerColor = Wrangler.Surface) {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = !settingsOpen && t == tab,
                        onClick = { onSettings(false); onTab(t) },
                        icon = { Icon(t.icon, contentDescription = t.label) },
                        label = { Text(t.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Wrangler.Primary,
                            selectedTextColor = Wrangler.Primary,
                            indicatorColor = Wrangler.Light,
                            unselectedIconColor = Wrangler.TextMuted,
                            unselectedTextColor = Wrangler.TextMuted,
                        ),
                    )
                }
            }
        },
        containerColor = Wrangler.Background,
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            if (settingsOpen) SettingsScreen(state, tel, notice, busy, run, quietRun, say)
            else when (tab) {
                Tab.DASHBOARD -> DashboardTab(tel, live, battHistory, onBattery, envHistory, onBottle)
                Tab.NAVIGATE -> NavigateTab(
                    tel, live, canCommand = state is LinkState.Ready && !state.readOnly,
                    busy = busy, notice = notice, run = run, say = say, onPickOnMap = onPickOnMap,
                    onServo = onServo,
                )
                Tab.GPS -> GpsTab(
                    tel, live, canCommand = state is LinkState.Ready && !state.readOnly,
                    busy = busy, notice = notice, log = stampLog, run = run, onStamp = onStamp,
                    onSetClock = onSetClock,
                )
                Tab.DIAGNOSTICS -> DiagnosticsTab(
                    state, tel, bondState, notice, busy, run = run, say = say,
                    onPair = onPair, onSetClock = onSetClock, onSecureMode = onSecureMode, onDisconnect = onDisconnect,
                )
            }
            // Over the tabs, so they don't jump when it slides away.
            ConnectingDrawer(state, handshake, Modifier.zIndex(1f))
        }
    }
}
