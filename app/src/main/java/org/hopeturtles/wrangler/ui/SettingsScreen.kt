package org.hopeturtles.wrangler.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.hopeturtles.wrangler.Notice
import org.hopeturtles.wrangler.ble.ConnectionMode
import org.hopeturtles.wrangler.ble.LinkState
import org.hopeturtles.wrangler.ble.Op
import org.hopeturtles.wrangler.ble.Payloads
import org.hopeturtles.wrangler.ble.ResultCode
import org.hopeturtles.wrangler.ble.TurtleTelemetry
import org.hopeturtles.wrangler.ui.theme.Wrangler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import org.hopeturtles.wrangler.R
import org.hopeturtles.wrangler.ui.theme.setDarkMode

private enum class SettingsAsk { BLE_OFF, SLEEP, REBOOT, AIROS }

/**
 * Settings (app plan Phase 6), opened from the top-bar gear. Appearance, shore WiFi,
 * the shore link, Bluetooth lockdown, Device and the danger zone. The
 * WiFi password is write-only: the turtle only ever reports the SSID.
 */
@Composable
fun SettingsScreen(
    state: LinkState,
    tel: TurtleTelemetry,
    notice: Notice?,
    busy: String?,
    run: RunCommand,
    quietRun: RunCommand,
    say: (String, Boolean) -> Unit,
) {
    val canCommand = state is LinkState.Ready && !state.readOnly
    val enabled = canCommand && busy == null
    val st = tel.status
    var ssid by remember { mutableStateOf<String?>(null) }
    var ssidKnown by remember { mutableStateOf(false) }
    var ask by remember { mutableStateOf<SettingsAsk?>(null) }

    fun readSsid() = quietRun("Read network", Op.WIFI_GET_SSID, ByteArray(0)) { r ->
        if (r.code.ok) { ssid = Payloads.ssid(r.payload); ssidKnown = true }
    }
    LaunchedEffect(canCommand) { if (canCommand) readSsid() }

    // Pale green cards on a lighter shade of the landing green.
    CompositionLocalProvider(LocalCardColor provides Wrangler.SettingsCard) { Column(
        Modifier.fillMaxSize().background(Wrangler.SettingsBackground).padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppearanceCard()
        NoticeCard(notice, busy)

        WifiCard(tel, enabled, ssid, ssidKnown,
            onWifi = { on ->
                run(if (on) "WiFi on" else "WiFi off", Op.WIFI_SET_ENABLED, byteArrayOf(if (on) 1 else 0)) { r ->
                    when {
                        r.code.ok && on -> say("WiFi is on and the turtle has joined ${ssid ?: "its network"}.", true)
                        r.code.ok -> say("WiFi is off.", true)
                        r.code == ResultCode.WIFI_FAILED -> say("WiFi is on, but the turtle couldn't join " +
                            "${ssid ?: "its network"}. It keeps trying on its own schedule.", false)
                    }
                }
            },
            onSave = { newSsid, password ->
                val payload = Payloads.wifiCredentials(newSsid, password)
                if (payload == null) {
                    say("The network name must be 1–32 bytes and the password at most 63.", false)
                } else {
                    val wifiOn = st?.wifiEnabled == true
                    run("Save WiFi", Op.WIFI_SET_CREDENTIALS, payload) { r ->
                        val name = newSsid.trim()
                        when {
                            r.code.ok && wifiOn -> say("Saved, and the turtle joined $name.", true)
                            r.code.ok -> say("Saved. The turtle's WiFi is off, so it didn't try to join $name.", true)
                            r.code == ResultCode.WIFI_FAILED -> say("Saved, but the turtle couldn't join $name. " +
                                "Check the password and that the network is in range.", false)
                            r.code == ResultCode.BAD_VALUE || r.code == ResultCode.BAD_LENGTH ->
                                say("The turtle refused that name or password (too long?).", false)
                        }
                        if (r.code.ok || r.code == ResultCode.WIFI_FAILED) { ssid = name; ssidKnown = true }
                    }
                }
            })

        ShoreLinkCard(tel, enabled,
            onMode = { m ->
                run("Connection mode", Op.CONNECTION_MODE_SET, byteArrayOf(m.ordinal.toByte())) { r ->
                    if (r.code.ok) say(if (m == ConnectionMode.WIFI_AUTO) "The turtle will look for WiFi on its own."
                        else "The turtle will only join WiFi when you ask it to.", true)
                }
            },
            onInterval = { s ->
                run("Logging interval", Op.TELEMETRY_SET_INTERVAL, Payloads.u16(s)) { r ->
                    if (r.code.ok) say("The turtle logs a reading every ${Format.interval(s)}.", true)
                }
            })

        WCard {
            CardLabel("Bluetooth")
            Muted("A launched turtle should have Bluetooth off: no radio, no power draw, nothing to connect to.")
            Spacer(Modifier.height(8.dp))
            // Pink: the step that can't be undone from here.
            PinkButton("Turn off Bluetooth for the voyage", { ask = SettingsAsk.BLE_OFF }, enabled = enabled)
        }

        DeviceCard(tel)

        WCard {
            Text("Danger zone", fontWeight = FontWeight.Bold, color = Wrangler.Danger, fontSize = 15.sp)
            Muted("Each of these ends the connection.")
            Spacer(Modifier.height(8.dp))
            DangerButton("Put the turtle to sleep", enabled) { ask = SettingsAsk.SLEEP }
            DangerButton("Reboot the turtle", enabled) { ask = SettingsAsk.REBOOT }
            DangerButton("Switch to airOS", enabled) { ask = SettingsAsk.AIROS }
        }
    } }

    when (ask) {
        SettingsAsk.BLE_OFF -> ConfirmDialog(
            title = "Turn off Bluetooth for the voyage?",
            body = "The turtle drops this connection and its Bluetooth stays off, even after a reboot. " +
                "Only the turtle's own button can turn it back on, on its Bluetooth screen. No phone can " +
                "reach it until then.",
            confirm = "Turn off Bluetooth",
            onConfirm = { run("Bluetooth off", Op.BLE_SET_ENABLED, byteArrayOf(0)) {} },
            onDismiss = { ask = null },
        )
        SettingsAsk.SLEEP -> ConfirmDialog(
            title = "Put the turtle to sleep?",
            body = "It says good night, sleeps, and shuts Bluetooth down. Wake it with its button; then " +
                "scan again to reconnect.",
            confirm = "Sleep",
            onConfirm = { run("Sleep", Op.SLEEP, ByteArray(0)) {} },
            onDismiss = { ask = null },
        )
        SettingsAsk.REBOOT -> ConfirmDialog(
            title = "Reboot the turtle?",
            body = "It restarts now. Its Bluetooth window opens again after boot, so you can scan and " +
                "reconnect in about a minute.",
            confirm = "Reboot",
            onConfirm = { run("Reboot", Op.REBOOT, ByteArray(0)) {} },
            onDismiss = { ask = null },
        )
        SettingsAsk.AIROS -> ConfirmDialog(
            title = "Switch to airOS?",
            body = "The turtle reboots as an airOS air monitor. airOS has no Bluetooth, so this app can't " +
                "reach it any more. Switching back to turtleOS is done on the turtle (quint-click on its " +
                "waiting screen).",
            confirm = "Switch to airOS",
            onConfirm = { run("Switch to airOS", Op.SET_TURTLE_MODE, byteArrayOf(0)) {} },
            onDismiss = { ask = null },
        )
        null -> {}
    }
}

/** Light or dark: sun, switch, moon. The phone's own setting until this is flipped. */
@Composable
private fun AppearanceCard() {
    val ctx = LocalContext.current
    val dark = isSystemInDarkTheme()
    WCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Appearance", fontWeight = FontWeight.Bold, color = Wrangler.Ink, fontSize = 15.sp,
                modifier = Modifier.weight(1f))
            Icon(painterResource(R.drawable.ic_sun), contentDescription = null,
                tint = if (dark) Wrangler.TextMuted else Wrangler.Primary, modifier = Modifier.size(22.dp))
            Switch(
                checked = dark, onCheckedChange = { setDarkMode(ctx, it) },
                colors = SwitchDefaults.colors(checkedTrackColor = Wrangler.Primary),
                modifier = Modifier.padding(horizontal = 8.dp)
                    .semantics { contentDescription = "Dark mode" },
            )
            Icon(painterResource(R.drawable.ic_moon), contentDescription = null,
                tint = if (dark) Wrangler.Ink else Wrangler.TextMuted, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun WifiCard(
    tel: TurtleTelemetry, enabled: Boolean, ssid: String?, ssidKnown: Boolean,
    onWifi: (Boolean) -> Unit, onSave: (String, String) -> Unit,
) {
    val st = tel.status
    var editing by remember { mutableStateOf(false) }
    var newSsid by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    WCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Shore WiFi", fontWeight = FontWeight.Bold, color = Wrangler.Ink, fontSize = 15.sp,
                modifier = Modifier.weight(1f))
            Switch(
                checked = st?.wifiEnabled == true, onCheckedChange = onWifi,
                enabled = enabled && st != null,
                colors = SwitchDefaults.colors(checkedTrackColor = Wrangler.Primary),
            )
        }
        Field("Current network", when {
            ssid != null -> ssid
            ssidKnown || st?.wifiCredentialsSet == false -> "None saved"
            else -> null
        })
        Field("Status", st?.let {
            when {
                !it.wifiEnabled -> "Off"
                it.wifiConnected -> "Connected"
                else -> "Not connected"
            }
        })
        Muted("The password is never shown: the turtle keeps it and only takes a new one.")
        Spacer(Modifier.height(8.dp))
        if (!editing) {
            GreenOutlineButton(if (ssid == null) "Set network" else "Change network",
                { newSsid = ssid ?: ""; password = ""; editing = true }, enabled = enabled)
        } else {
            val fieldColors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Wrangler.Primary, focusedLabelColor = Wrangler.Primary,
                cursorColor = Wrangler.Primary,
            )
            OutlinedTextField(
                value = newSsid, onValueChange = { newSsid = it }, singleLine = true,
                label = { Text("Network name (SSID)") }, colors = fieldColors,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = password, onValueChange = { password = it }, singleLine = true,
                label = { Text("Password") }, colors = fieldColors,
                visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    TextButton(onClick = { show = !show }) {
                        Text(if (show) "Hide" else "Show", color = Wrangler.Primary)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GreenOutlineButton("Cancel", { editing = false; password = "" }, modifier = Modifier.weight(1f))
                GreenOutlineButton("Save", {
                    onSave(newSsid, password)
                    editing = false
                    password = ""                 // don't keep it in the UI after sending
                }, enabled = enabled && newSsid.isNotBlank(), modifier = Modifier.weight(1f))
            }
            if (st?.wifiEnabled == true) Muted("The turtle drops its current network and joins this one.")
        }
    }
}

@Composable
private fun ShoreLinkCard(
    tel: TurtleTelemetry, enabled: Boolean,
    onMode: (ConnectionMode) -> Unit, onInterval: (Int) -> Unit,
) {
    val st = tel.status
    var intervalText by remember(st?.intervalS) { mutableStateOf(st?.intervalS?.toString() ?: "") }
    WCard {
        CardLabel("Shore link")
        Text("When the turtle looks for WiFi", color = Wrangler.Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(ConnectionMode.WIFI_AUTO to "Automatically", ConnectionMode.WIFI_MANUAL to "Only when asked")
                .forEach { (m, label) ->
                    val selected = st?.connectionMode == m
                    OutlinedButton(
                        onClick = { if (!selected) onMode(m) }, enabled = enabled,
                        border = BorderStroke(if (selected) 2.dp else 1.dp,
                            if (selected) Wrangler.Primary else Wrangler.CardBorder),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(label, color = if (selected) Wrangler.Primary else Wrangler.TextMuted,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, fontSize = 13.sp)
                    }
                }
        }
        Muted(when (st?.connectionMode) {
            ConnectionMode.WIFI_MANUAL -> "Right for a launched turtle: there's no WiFi at sea, so every search " +
                "wastes battery. It joins only when you turn WiFi on here or on its WiFi screen."
            ConnectionMode.LORA -> "LoRa (reserved, not built yet)."
            else -> "Tries every 15 min, backing off to every 12 h, until it gets through."
        })

        Spacer(Modifier.height(10.dp))
        Text("Logging interval", color = Wrangler.Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        val secs = intervalText.toIntOrNull()
        val valid = secs != null && secs in 10..65_535
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = intervalText, onValueChange = { v -> intervalText = v.filter { it.isDigit() }.take(5) },
                singleLine = true, label = { Text("Seconds") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Wrangler.Primary, focusedLabelColor = Wrangler.Primary,
                    cursorColor = Wrangler.Primary,
                ),
                isError = intervalText.isNotEmpty() && !valid,
                modifier = Modifier.weight(1f),
            )
            GreenOutlineButton("Save", { secs?.let(onInterval) },
                enabled = enabled && valid && secs != st?.intervalS, modifier = Modifier.weight(1f))
        }
        Muted(if (secs != null && valid) "A reading every ${Format.interval(secs)} in auto logging. At least 10 s."
            else "At least 10 seconds.")
    }
}

@Composable
private fun DeviceCard(tel: TurtleTelemetry) {
    val shore = tel.shore
    WCard {
        CardLabel("Device")
        Field("Name", tel.name)
        Field("Firmware", tel.deviceInfo?.firmware?.let { "turtleOS $it" })
        Field("Model", tel.deviceInfo?.model)
        Field("Serial (device ID)", tel.deviceInfo?.serial)
        Field("Turtle clock", shore?.deviceNow?.let { clockVsPhone(difference(it - System.currentTimeMillis() / 1000)) }
            ?: if (shore != null) "Not set" else null)
        Muted("Set the clock and see it in full in Diagnostics.")
    }
}

/** Red outline: these end the connection, so they shouldn't look like routine actions. */
@Composable
private fun DangerButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        border = BorderStroke(1.dp, if (enabled) Wrangler.Danger else Wrangler.CardBorder),
    ) {
        Text(text, color = if (enabled) Wrangler.Danger else Wrangler.TextMuted, fontWeight = FontWeight.Bold)
    }
}

/** "in step" → "In step with this phone"; "3 min behind" → "3 min behind this phone". */
internal fun clockVsPhone(d: String): String = when {
    d == "in step" -> "In step with this phone"
    d.endsWith("ahead") -> "$d of this phone"
    else -> "$d this phone"
}
