package org.hopeturtles.wrangler.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.hopeturtles.wrangler.ble.TurtleTelemetry
import org.hopeturtles.wrangler.ui.theme.Wrangler
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * Diagnostics: the turtle's clock next to the phone's, even when the
 * turtle's is wrong (Shore `device_clock`, e.g. 2000-01-01 after its RTC
 * lost power), and the button that sets it from the phone (TIME_SET).
 * Shore notifies once a minute, so the turtle time ticks on locally.
 */
@Composable
fun ClockCard(tel: TurtleTelemetry, enabled: Boolean, onSetClock: () -> Unit) {
    val shore = tel.shore
    val synced = tel.status?.rtcSynced
    val clock = shore?.deviceClock ?: shore?.deviceNow
    val receivedAt = remember(shore) { System.currentTimeMillis() }
    val now by produceState(System.currentTimeMillis()) {
        while (true) { delay(1_000); value = System.currentTimeMillis() }
    }
    WCard {
        CardLabel("Turtle clock")
        when (synced) {
            true -> Text("● Set", color = Wrangler.Primary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            false -> Text("Not set: stamps and journeys are refused", color = Wrangler.PinkDark,
                fontWeight = FontWeight.Bold, fontSize = 14.sp)
            null -> {}
        }
        val turtleMs = clock?.let { it * 1000 + (now - receivedAt) }
        Field("Turtle", turtleMs?.let { fullTime(it) })
        Field("This phone", fullTime(now))
        Field("Difference", turtleMs?.let { difference((turtleMs - now) / 1000) })
        shore?.clockSource?.let { Field("Set by", it.label) }
        if (tel.status?.rtcBatteryFault == true) {
            Spacer(Modifier.height(6.dp))
            Text("Clock-chip battery fault", color = Wrangler.PinkDark, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Muted("The turtle loses its time whenever it loses power. Replace the clock chip's coin cell, " +
                "set the clock, then switch the turtle off and on once to clear this. On a mission the " +
                "clock chip must be sound.")
        }
        val s = tel.status
        if (synced == false && shore?.clockSource != null && s != null && !s.secureMode && !s.secureModeBlocked) {
            Muted("The turtle sets its clock from GPS at its first fix.")
        }
        if (shore != null && clock == null) {
            Muted(if (synced == false) "This turtle's firmware doesn't report an unset clock. " +
                "Update turtleOS to see what it says." else "—")
        }
        Muted("Shown in this phone's time zone. The turtle keeps UTC.")
        Spacer(Modifier.height(8.dp))
        SetClockButton(enabled, onSetClock)
    }
}

/** "Set turtle clock to phone time", asking first. */
@Composable
fun SetClockButton(enabled: Boolean, onSetClock: () -> Unit) {
    var ask by remember { mutableStateOf(false) }
    GreenOutlineButton("Set turtle clock to phone time", { ask = true }, enabled = enabled)
    if (ask) ConfirmDialog(
        title = "Set the turtle's clock?",
        body = "The turtle's clock is set to this phone's time. Readings, stamps and journeys are " +
            "timed by it. If the turtle's clock-chip battery is flat, the time is lost again when the " +
            "turtle loses power. Replacing that coin cell fixes it for good.",
        confirm = "Set clock",
        onConfirm = onSetClock,
        onDismiss = { ask = false },
    )
}

/**
 * Diagnostics: secure mode (config `secure_mode`), for missions where GPS
 * may be spoofed. The turtle refuses to turn it on with a faulty RTC
 * battery; if config asks for it anyway it reports "blocked".
 */
@Composable
fun SecureModeCard(tel: TurtleTelemetry, enabled: Boolean, onSecureMode: (Boolean) -> Unit) {
    val s = tel.status ?: return
    var ask by remember { mutableStateOf<Boolean?>(null) }
    WCard {
        CardLabel("Secure mode")
        when {
            s.secureMode -> Text("● On: GPS time isn't trusted", color = Wrangler.Primary,
                fontWeight = FontWeight.Bold, fontSize = 14.sp)
            s.secureModeBlocked -> {
                Text("Blocked by the clock-chip battery", color = Wrangler.PinkDark,
                    fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Muted("Secure mode is asked for but can't be in force until the coin cell is replaced. " +
                    "GPS time is still refused.")
            }
            else -> Text("Off", color = Wrangler.Dark, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
        Muted("For missions where GPS may be spoofed. The turtle keeps time only from its clock chip, " +
            "so it can't turn on while that chip's battery is faulty.")
        Spacer(Modifier.height(8.dp))
        val on = s.secureMode || s.secureModeBlocked
        GreenOutlineButton(if (on) "Turn secure mode off" else "Turn secure mode on", { ask = !on },
            enabled = enabled && (on || !s.rtcBatteryFault))
        if (!on && s.rtcBatteryFault) Muted("Replace the clock-chip coin cell first.")
    }
    ask?.let { turnOn ->
        ConfirmDialog(
            title = if (turnOn) "Turn secure mode on?" else "Turn secure mode off?",
            body = if (turnOn) "The turtle will stop setting its clock from GPS. Only its clock chip, " +
                "a phone or the internet can set it." else "The turtle may set its clock from GPS again " +
                "when its clock chip can't be trusted.",
            confirm = if (turnOn) "Turn on" else "Turn off",
            onConfirm = { onSecureMode(turnOn) },
            onDismiss = { ask = null },
        )
    }
}

private fun fullTime(ms: Long): String =
    SimpleDateFormat("d MMM yyyy, HH:mm:ss", Locale.getDefault()).format(Date(ms))

/** Turtle minus phone, in words: "in step" · "3 min behind" · "26 years behind". */
internal fun difference(s: Long): String {
    val a = abs(s)
    val dir = if (s < 0) "behind" else "ahead"
    return when {
        a <= 2 -> "in step"
        a < 120 -> "$a s $dir"
        a < 7_200 -> "${a / 60} min $dir"
        a < 172_800 -> "${a / 3_600} h $dir"
        a < 63_072_000 -> "${a / 86_400} days $dir"
        else -> "${a / 31_557_600} years $dir"
    }
}
