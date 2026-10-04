package org.hopeturtles.wrangler.ui

import android.bluetooth.BluetoothDevice
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.hopeturtles.wrangler.Notice
import org.hopeturtles.wrangler.ble.LinkState
import org.hopeturtles.wrangler.ble.TurtleTelemetry
import org.hopeturtles.wrangler.ui.theme.Wrangler

/**
 * The Phase 1 link panel — contract check, pairing, Device Information, live
 * values and the test command. Lives in the Diagnostics tab until app
 * Phase 5 builds the real Diagnostics screen.
 */
@Composable
fun LinkPanel(
    state: LinkState,
    tel: TurtleTelemetry,
    bondState: Int,
    notice: Notice?,
    onPair: () -> Unit,
    onTest: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val bonded = bondState == BluetoothDevice.BOND_BONDED
    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state is LinkState.Disconnected) {
            WCard {
                CardLabel("Disconnected")
                Muted(state.reason ?: "The turtle closed the connection.")
            }
        }
        if (state is LinkState.Ready && state.readOnly) {
            WCard {
                CardLabel("Update Wrangler")
                Muted("This turtle speaks a newer contract (v${tel.contract?.contractVersion}). " +
                    "Showing what this version understands; commands are disabled.")
            }
        }

        WCard {
            CardLabel("Link")
            Field("Contract", tel.contract?.let { "v${it.contractVersion}" })
            Field("Paired", when (bondState) {
                BluetoothDevice.BOND_BONDED -> "Yes"
                BluetoothDevice.BOND_BONDING -> "Pairing…"
                else -> "No"
            })
            Field("Link encrypted (turtle's view)", tel.status?.let { if (it.linkBonded) "Yes" else "No" })
            if (tel.status?.requireBond == false) {
                Text("Unsecured — development turtle (ble_require_bond is off)", color = Wrangler.PinkDark)
            }
        }

        WCard {
            CardLabel("Device")
            Field("Manufacturer", tel.deviceInfo?.manufacturer)
            Field("Model", tel.deviceInfo?.model)
            Field("Firmware", tel.deviceInfo?.firmware)
            Field("Serial", tel.deviceInfo?.serial)
        }

        WCard {
            CardLabel("Live")
            val p = tel.position
            Field("Position", if (p?.lat != null && p.lon != null) "%.5f, %.5f".format(p.lat, p.lon) else null)
            Field("Satellites / fix age", p?.let { "${it.sats ?: "—"} sats · ${it.fixAgeS?.let { a -> "${a}s" } ?: "no fix"}" })
            Field("Heading", tel.nav?.headingDeg?.let { "%.1f°".format(it) })
            Field("Nav state", tel.nav?.state?.name)
            Field("Battery", tel.power?.let { pw ->
                listOfNotNull(pw.socPct?.let { "$it%" }, pw.voltageMv?.let { "%.2f V".format(it / 1000.0) })
                    .joinToString(" · ").ifEmpty { null }
            })
            Field("Pressure", tel.imu?.pressureHpa?.let { "%.1f hPa".format(it) })
            Field("Logging", tel.status?.telemetryMode?.name?.lowercase())
            Field("Unsent readings", tel.status?.queueCount?.toString())
        }

        if (state is LinkState.Ready && !state.readOnly) {
            if (!bonded) GreenOutlineButton("Pair with this turtle", onPair)
            PinkButton("Test command", onTest)
        }
        notice?.let {
            Text(it.text, color = if (it.ok) Wrangler.Dark else Wrangler.PinkDark)
        }
        GreenOutlineButton(if (state is LinkState.Disconnected) "Back to scan" else "Disconnect", onDisconnect)
    }
}
