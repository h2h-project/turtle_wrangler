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
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.zIndex
import org.hopeturtles.wrangler.BattSample
import org.hopeturtles.wrangler.EnvSample
import org.hopeturtles.wrangler.Notice
import org.hopeturtles.wrangler.ble.Handshake
import org.hopeturtles.wrangler.ble.LatLon
import org.hopeturtles.wrangler.ble.LinkState
import org.hopeturtles.wrangler.ble.TurtleTelemetry
import org.hopeturtles.wrangler.data.StampEntry
import org.hopeturtles.wrangler.ui.theme.Wrangler

/** The four sections, in the helm (the drawer under the top bar). Settings is not one — it's the gear. */
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
    trail: List<LatLon>,
    departures: Map<Long, LatLon>,
    onOpenMap: () -> Unit,
    onStartJourney: () -> Unit,
    journeySetup: Boolean,
    onJourneySetup: (Boolean) -> Unit,
    stampLog: List<StampEntry>,
    onStamp: () -> Unit,
    onSetClock: () -> Unit,
    onSecureMode: (Boolean) -> Unit,
    onPair: () -> Unit,
    onDisconnect: () -> Unit,
    onForget: () -> Boolean,
    settingsOpen: Boolean,
    onSettings: (Boolean) -> Unit,
    quietRun: RunCommand,
) {
    // The helm (sections drawer): swipe down on the top bar, or tap its line or the turtle.
    var helmOpen by rememberSaveable { mutableStateOf(false) }
    // Back closes the drawer first, then Settings (which sits over the sections).
    BackHandler(enabled = settingsOpen && !helmOpen) { onSettings(false) }
    BackHandler(enabled = helmOpen) { helmOpen = false }
    // The turtle logo's colour says connected. Setup progress lives in the
    // connecting drawer, not here; text only for read-only and disconnected.
    val (statusText, ok) = when (state) {
        is LinkState.Connecting, is LinkState.Preparing, is LinkState.Reconnecting -> null to false
        is LinkState.Ready -> (if (state.readOnly) "Read-only" else null) to true
        is LinkState.Disconnected -> "Disconnected" to false
    }
    val connecting = rememberConnectingDrawer(state, handshake)
    // No helm while the connecting drawer is down (or going up).
    val canOpenHelm = !connecting.currentState && !connecting.targetState
    LaunchedEffect(canOpenHelm) { if (!canOpenHelm) helmOpen = false }
    Scaffold(
        topBar = {
            // Swipe down anywhere on the bar to show the sections, up to hide them.
            Box(Modifier.verticalSwipe(onDown = { if (canOpenHelm) helmOpen = true }, onUp = { helmOpen = false })) {
                // Once the link has dropped, the status is the way back: tap to scan again.
                WranglerTopBar(tel.name ?: "Turtle", statusText, ok,
                    onSettings = { helmOpen = false; onSettings(!settingsOpen) },
                    onStatusClick = if (state is LinkState.Disconnected) onDisconnect else null,
                    connected = state is LinkState.Ready, settingsOpen = settingsOpen,
                    subtitle = if (settingsOpen) "Settings" else tab.label,
                    // The turtle goes home: from any other section (or Settings) back to
                    // the Dashboard; on the Dashboard it opens the helm.
                    onLogo = {
                        if (settingsOpen || tab != Tab.DASHBOARD) {
                            helmOpen = false; onSettings(false); onTab(Tab.DASHBOARD)
                        } else if (canOpenHelm) helmOpen = !helmOpen
                    },
                    linkDot = when (state) {
                        is LinkState.Ready -> LinkDot.CONNECTED
                        is LinkState.Disconnected -> LinkDot.OFF
                        else -> LinkDot.CONNECTING
                    })
                HelmHandle(helmOpen, visible = canOpenHelm, onToggle = { helmOpen = !helmOpen },
                    modifier = Modifier.align(Alignment.BottomCenter))
            }
        },
        containerColor = Wrangler.Background,
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            if (settingsOpen) SettingsScreen(state, tel, notice, busy, run, quietRun, say)
            else when (tab) {
                Tab.DASHBOARD -> DashboardTab(tel, live, battHistory, onBattery, envHistory, onBottle,
                    onMap = { onTab(Tab.NAVIGATE) })
                Tab.NAVIGATE -> NavigateTab(
                    tel, live, canCommand = state is LinkState.Ready && !state.readOnly,
                    busy = busy, notice = notice, run = run, say = say, onPickOnMap = onPickOnMap,
                    onServo = onServo, trail = trail, departures = departures, stamps = stampLog,
                    onOpenMap = onOpenMap, onStartJourney = onStartJourney,
                    setupOpen = journeySetup, onSetupOpen = onJourneySetup,
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
            ConnectingDrawer(state, handshake, connecting, onStop = onDisconnect, onForget = onForget,
                modifier = Modifier.zIndex(1f))
            HelmDrawer(helmOpen, tab, settingsOpen,
                onPick = { helmOpen = false; onSettings(false); onTab(it) },
                onClose = { helmOpen = false },
                modifier = Modifier.zIndex(2f))
        }
    }
}
