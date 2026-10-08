package org.hopeturtles.wrangler.ui

import android.bluetooth.BluetoothDevice
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.hopeturtles.wrangler.Notice
import org.hopeturtles.wrangler.ble.BenchSweep
import org.hopeturtles.wrangler.ble.LinkState
import org.hopeturtles.wrangler.ble.NavState
import org.hopeturtles.wrangler.ble.Op
import org.hopeturtles.wrangler.ble.Payloads
import org.hopeturtles.wrangler.ble.ResultCode
import org.hopeturtles.wrangler.ble.SweepState
import org.hopeturtles.wrangler.ble.TurtleTelemetry
import org.hopeturtles.wrangler.ui.theme.Wrangler

private const val NAV_SWEEP = "Nav sweep"
private const val BENCH_SWEEP = "Bench sweep"

/** "Set north here" waiting for a yes: offset now → offset after, at this heading. */
private data class NorthAsk(val from: Int, val to: Int, val headingDeg: Double)

/**
 * Diagnostics (app plan Phase 5): sensor health, navigation and the shore
 * link, the wind-finder (nav sweep, and the bench sweep behind "Advanced"),
 * compass calibration, the turtle clock and secure mode, then the link
 * and Device Information.
 */
@Composable
fun DiagnosticsTab(
    state: LinkState,
    tel: TurtleTelemetry,
    bondState: Int,
    notice: Notice?,
    busy: String?,
    run: RunCommand,
    say: (String, Boolean) -> Unit,
    onPair: () -> Unit,
    onSetClock: () -> Unit,
    onSecureMode: (Boolean) -> Unit,
    onDisconnect: () -> Unit,
) {
    val canCommand = state is LinkState.Ready && !state.readOnly
    val enabled = canCommand && busy == null
    var askBench by remember { mutableStateOf(false) }
    var askNorth by remember { mutableStateOf<NorthAsk?>(null) }
    var navWind by remember { mutableStateOf<String?>(null) }
    var bench by remember { mutableStateOf<BenchSweep?>(null) }

    fun navSweep() = run(NAV_SWEEP, Op.NAV_LUFF_SWEEP, ByteArray(0)) { r ->
        when {
            r.code.ok -> Payloads.sweepWind(r.payload).let { w ->
                navWind = w?.let { "%.0f°".format(it) } ?: "No wind angle found"
                say(w?.let { "Nav sweep done: wind at %.0f°.".format(it) }
                    ?: "Nav sweep ended without finding the wind.", w != null)
            }
            r.code == ResultCode.WRONG_STATE -> say(Payloads.sweepWrongState(r.payload).let { s ->
                if (s == null) "The autopilot is off, so there's no nav sweep. Use the bench sweep instead."
                else "A nav sweep needs the turtle acquiring or sailing; it's ${Format.navState(s) ?: "in another state"}."
            }, false)
            r.code == ResultCode.BUSY -> say("A sweep is already running.", false)
        }
    }

    fun benchSweep() = run(BENCH_SWEEP, Op.SERVO_BENCH_SWEEP, ByteArray(0)) { r ->
        if (r.code.ok) {
            bench = Payloads.benchSweep(r.payload)
            say(bench?.windDeg?.let { "Bench sweep done: wind at %.0f°.".format(it) }
                ?: "Bench sweep ended without finding the wind.", bench?.windDeg != null)
        } else if (r.code == ResultCode.NO_HARDWARE) {
            say("The bench sweep needs the sail servo and its angle sensor (AS5600).", false)
        }
    }

    fun setOffset(deg: Int, label: String) = run(label, Op.COMPASS_SET_OFFSET, Payloads.compassOffset(deg)) { r ->
        if (r.code.ok) say("Compass offset is now $deg°.", true)
    }

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
        NoticeCard(notice, busy)

        SensorHealthCard(tel)
        NavigationCard(tel, enabled, onApi = {
            run("API handshake", Op.API_HANDSHAKE, ByteArray(0)) { r ->
                when (r.code) {
                    ResultCode.OK -> say("hopeturtles.org answered the turtle.", true)
                    ResultCode.WRONG_STATE -> say("Logging is off on the turtle, so it won't send. Turn logging on in the GPS tab.", false)
                    else -> {}
                }
            }
        })
        WindFinderCard(tel, enabled, busy, navWind, bench, onNavSweep = ::navSweep, onBench = { askBench = true })
        CompassCard(tel, enabled, onNorth = {
            val o = tel.nav?.compassOffsetDeg
            val h = tel.nav?.headingDeg
            if (o != null && h != null) askNorth = NorthAsk(o, Payloads.northOffset(o, h), h)
        }, onNudge = { d ->
            tel.nav?.compassOffsetDeg?.let { setOffset(it + d, "Compass offset") }
        }, onReset = { setOffset(0, "Compass offset reset") })
        ClockCard(tel, enabled = enabled, onSetClock)
        SecureModeCard(tel, enabled = enabled, onSecureMode)
        LinkCard(tel, bondState)
        DeviceCard(tel)

        if (canCommand && bondState != BluetoothDevice.BOND_BONDED) GreenOutlineButton("Pair with this turtle", onPair)
        GreenOutlineButton(if (state is LinkState.Disconnected) "Back to scan" else "Disconnect", onDisconnect)
    }

    if (askBench) ConfirmDialog(
        title = "Run a bench sweep?",
        body = "The sail servo sweeps its whole travel to find the wind. The turtle stops sending " +
            "updates and ignores its button until it finishes, which can take a few minutes. " +
            "Keep hands clear of the sail.",
        confirm = "Run sweep",
        onConfirm = ::benchSweep,
        onDismiss = { askBench = false },
    )
    askNorth?.let { n ->
        ConfirmDialog(
            title = "Set north here?",
            body = "The turtle's bow must point at true north now (not magnetic north). Its heading " +
                "reads %.0f°; the compass offset changes from ${n.from}° to ${n.to}° so it reads 0°. "
                    .format(n.headingDeg) + "The autopilot steers by this heading.",
            confirm = "Set north",
            onConfirm = { setOffset(n.to, "Compass: north here") },
            onDismiss = { askNorth = null },
        )
    }

    // The turtle's main loop is inside the sweep, so nothing else will move
    // until it answers: say so instead of showing frozen values.
    if (busy == BENCH_SWEEP) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Bench sweep running…", fontWeight = FontWeight.Bold, color = Wrangler.Dark) },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), color = Wrangler.Primary, strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("The turtle sends no updates until the sweep finishes. Its progress is on the OLED.",
                        color = Wrangler.Text)
                }
            },
            confirmButton = {},
            containerColor = Wrangler.Surface,
        )
    }
}

/** One sensor: ● found (green) or ○ missing (grey), with a note. */
@Composable
private fun HealthRow(label: String, ok: Boolean?, okText: String = "Found", badText: String = "Not found") {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Wrangler.TextMuted, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(
            when (ok) { true -> "● $okText"; false -> "○ $badText"; null -> "—" },
            color = if (ok == true) Wrangler.Primary else Wrangler.TextMuted,
            fontSize = 13.sp, fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun SensorHealthCard(tel: TurtleTelemetry) {
    val s = tel.status
    WCard {
        CardLabel("Sensor health")
        HealthRow("GPS module", s?.gpsHwPresent, badText = if (s?.gpsEnabled == false) "Off" else "Not found")
        HealthRow("Compass", s?.magPresent)
        HealthRow("IMU (pitch / roll)", s?.imuPresent)
        HealthRow("Barometer", s?.baroPresent)
        HealthRow("Battery monitor", s?.ina219Present)
        HealthRow("Sail angle sensor", s?.as5600Present)
        HealthRow("Sail servo", s?.servoPresent, okText = "Fitted", badText = "Not fitted")
        HealthRow("Clock", s?.let { it.rtcSynced && !it.rtcBatteryFault },
            okText = "Set", badText = when {
                s?.rtcBatteryFault == true -> "Battery fault"
                else -> "Not set"
            })
        Muted("Servo is from the turtle's config; the rest are what it found at boot.")
    }
}

@Composable
private fun NavigationCard(tel: TurtleTelemetry, enabled: Boolean, onApi: () -> Unit) {
    val nav = tel.nav
    val s = tel.status
    WCard {
        CardLabel("Navigation and shore link")
        Field("Nav state", Format.navState(nav?.state))
        if (nav?.state == NavState.SAFE) {
            Text(Format.fault(nav.fault) ?: "SAFE mode", color = Wrangler.PinkDark,
                fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
        Field("WiFi", s?.let { if (it.wifiConnected) "Connected" else if (it.wifiEnabled) "Not connected" else "Off" })
        Field("hopeturtles.org", s?.let { if (it.apiOk) "OK" else "Not reached" })
        Spacer(Modifier.height(8.dp))
        GreenOutlineButton("Test hopeturtles.org link", onApi, enabled = enabled)
    }
}

@Composable
private fun WindFinderCard(
    tel: TurtleTelemetry, enabled: Boolean, busy: String?,
    navWind: String?, bench: BenchSweep?,
    onNavSweep: () -> Unit, onBench: () -> Unit,
) {
    val sail = tel.sail
    var advanced by remember { mutableStateOf(false) }
    WCard {
        CardLabel("Wind-finder")
        Field("Wind angle (latest)", sail?.windDeg?.let { "%.0f°".format(it) })
        Field("Sail angle", sail?.sailDeg?.let { "%.0f°".format(it) })
        if (sail?.sweep == SweepState.NAV_SWEEP || busy == NAV_SWEEP) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), color = Wrangler.Primary, strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Muted("Sweeping the sail to find the wind…")
            }
        }
        navWind?.let { Field("Nav sweep result", it) }
        Spacer(Modifier.height(8.dp))
        // The screen's one pink element.
        PinkButton("Run nav sweep", onNavSweep, enabled = enabled)
        Muted("The autopilot's own luff sweep. It runs while the turtle is acquiring or sailing.")

        Spacer(Modifier.height(4.dp))
        TextButton(onClick = { advanced = !advanced }) {
            Text(if (advanced) "Advanced ▴" else "Advanced ▾", color = Wrangler.Primary, fontWeight = FontWeight.Bold)
        }
        if (advanced) {
            bench?.let { b ->
                Field("Bench wind", b.windDeg?.let { "%.0f°".format(it) })
                Field("Other side", b.altWindDeg?.let { "%.0f°".format(it) })
                Field("Confidence", b.confidencePct?.let { "$it%" })
            }
            GreenOutlineButton("Run bench sweep", onBench, enabled = enabled && tel.status?.servoPresent != false)
            Muted("Sweeps the servo through its whole travel on the bench. The turtle is busy until it finishes.")
        }
    }
}

@Composable
private fun CompassCard(
    tel: TurtleTelemetry, enabled: Boolean,
    onNorth: () -> Unit, onNudge: (Int) -> Unit, onReset: () -> Unit,
) {
    val nav = tel.nav
    val offset = nav?.compassOffsetDeg
    WCard {
        CardLabel("Compass")
        Field("Heading", nav?.headingDeg?.let { "%.0f°".format(it) })
        Field("Offset", offset?.let { "$it°" })
        Field("Raw magnetometer", if (offset != null && nav.headingDeg != null)
            "%.0f°".format(((nav.headingDeg - offset) % 360 + 360) % 360) else null)
        if (tel.status?.magPresent == false) Muted("No compass found on this turtle.")
        if (nav != null && offset == null) {
            Muted("This turtle's firmware doesn't report its compass offset. Update turtleOS to calibrate here.")
        }
        val can = enabled && offset != null && nav.headingDeg != null
        Spacer(Modifier.height(8.dp))
        GreenOutlineButton("The bow points at true north now", onNorth, enabled = can)
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(-5, -1, 1, 5).forEach { d ->
                GreenOutlineButton(if (d > 0) "+$d°" else "$d°", { onNudge(d) },
                    enabled = can && offset + d in -180..180, modifier = Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(4.dp))
        GreenOutlineButton("Reset offset to 0°", onReset, enabled = can && offset != 0)
        Muted("The offset is added to the magnetometer's heading so 0° is true north.")
    }
}

@Composable
private fun LinkCard(tel: TurtleTelemetry, bondState: Int) {
    WCard {
        CardLabel("Bluetooth link")
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
}

@Composable
private fun DeviceCard(tel: TurtleTelemetry) {
    WCard {
        CardLabel("Device")
        Field("Manufacturer", tel.deviceInfo?.manufacturer)
        Field("Model", tel.deviceInfo?.model)
        Field("Firmware", tel.deviceInfo?.firmware)
        Field("Serial", tel.deviceInfo?.serial)
    }
}
