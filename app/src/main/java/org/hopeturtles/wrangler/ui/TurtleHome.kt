package org.hopeturtles.wrangler.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import org.hopeturtles.wrangler.BattSample
import org.hopeturtles.wrangler.EnvSample
import org.hopeturtles.wrangler.Notice
import org.hopeturtles.wrangler.ble.LinkState
import org.hopeturtles.wrangler.ble.TurtleTelemetry
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
    onPair: () -> Unit,
    onTest: () -> Unit,
    onDisconnect: () -> Unit,
    onSettings: () -> Unit,
) {
    val (statusText, ok) = when (state) {
        is LinkState.Connecting -> "Connecting" to false
        is LinkState.Preparing -> state.step to false
        is LinkState.Ready -> (if (state.readOnly) "Read-only" else "Connected") to true
        is LinkState.Disconnected -> "Disconnected" to false
    }
    Scaffold(
        topBar = { WranglerTopBar("🐢 ${tel.name ?: "Turtle"}", statusText, ok, onSettings) },
        bottomBar = {
            NavigationBar(containerColor = Wrangler.Surface) {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = t == tab,
                        onClick = { onTab(t) },
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
        Column(Modifier.fillMaxSize().padding(pad)) {
            if (state is LinkState.Connecting || state is LinkState.Preparing) {
                Row(Modifier.padding(start = 16.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.padding(end = 12.dp), color = Wrangler.Primary)
                    Muted("$statusText…")
                }
            }
            when (tab) {
                Tab.DASHBOARD -> DashboardTab(tel, live, battHistory, onBattery, envHistory, onBottle)
                Tab.NAVIGATE -> ComingSoon("Navigate", "App Phase 3: destination on a map, journey start/end, " +
                    "cross-track gauge.")
                Tab.GPS -> ComingSoon("GPS", "App Phase 4: switch to manual logging and stamp the turtle's " +
                    "position with one big button.")
                Tab.DIAGNOSTICS -> LinkPanel(state, tel, bondState, notice, onPair, onTest, onDisconnect)
            }
        }
    }
}

@Composable
private fun ComingSoon(title: String, body: String) {
    Column(Modifier.padding(16.dp)) {
        WCard {
            CardLabel(title)
            Muted(body)
        }
    }
}
