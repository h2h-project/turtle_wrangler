package org.hopeturtles.wrangler.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.BorderStroke
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import android.content.Intent
import android.provider.Settings
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.hopeturtles.wrangler.ble.Handshake
import org.hopeturtles.wrangler.ble.HandshakeStep
import org.hopeturtles.wrangler.ble.LinkState
import org.hopeturtles.wrangler.ui.theme.Wrangler

/** How long the drawer stays down with every step ticked before it slides up. */
private const val DONE_HOLD_MS = 700L

/**
 * Green drawer under the top bar, white on green so it reads as part of it, that slides down
 * while the link is set up and lists every handshake step as it happens. It
 * slides back up once the turtle is Ready. If setup fails it stays down and
 * says which step stopped and why; the top bar's "Scan" is the way back.
 * After an unexpected drop it comes back down while the link reconnects,
 * with "Stop" ([onStop]) to give up and go back to scanning.
 */
@Composable
fun ConnectingDrawer(
    state: LinkState, hs: Handshake, shown: MutableTransitionState<Boolean>, onStop: () -> Unit,
    onForget: () -> Boolean,
    modifier: Modifier = Modifier,
) {
    // Set while a reconnect cycle runs (waiting or setting up again).
    val reconnecting = hs.retry > 0
    val failed = state is LinkState.Disconnected && hs.failed != null
    AnimatedVisibility(
        visibleState = shown,
        enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
        exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
        modifier = modifier,
    ) {
        Surface(
            color = Wrangler.Primary,
            shape = RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp),
            shadowElevation = 3.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            // Selectable, so an error can be long-pressed and copied.
            SelectionContainer { Column(
                Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val ready = state is LinkState.Ready
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when {
                            ready -> "Connected"
                            failed -> "Couldn't connect"
                            state is LinkState.Reconnecting -> "Link lost"
                            reconnecting -> "Reconnecting… (try ${hs.retry})"
                            else -> "Connecting…"
                        },
                        fontWeight = FontWeight.Bold, color = Color.White, fontSize = 16.sp,
                        modifier = Modifier.weight(1f),
                    )
                    if (reconnecting && !ready && !failed) TextButton(onClick = onStop) {
                        Text("Stop", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
                HandshakeStep.entries.forEach { step ->
                    val done = ready || step.ordinal < hs.step.ordinal
                    val current = !ready && step == hs.step
                    StepRow(
                        step.label,
                        mark = when {
                            done -> StepMark.DONE
                            current && failed -> StepMark.FAILED
                            current -> StepMark.WORKING
                            else -> StepMark.TODO
                        },
                        detail = when {
                            done -> hs.found[step]
                            current && failed -> hs.failed
                            current -> hs.now
                            else -> null
                        },
                    )
                }
                if (failed && hs.staleBond) StaleBondHelp(onForget)
            } }
        }
    }
}

/**
 * The turtle forgot this phone but the phone still holds the old pairing.
 * Forget it here (or in Android's Bluetooth settings), then pair again.
 */
@Composable
private fun StaleBondHelp(onForget: () -> Boolean) {
    val context = LocalContext.current
    // null = not tried yet; true = forgotten; false = Android refused.
    var forgot by remember { mutableStateOf<Boolean?>(null) }
    val body = Color.White.copy(alpha = 0.85f)
    Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (forgot) {
            null -> {
                Text("Forget the old pairing on this phone, then pair again.", color = body, fontSize = 13.sp)
                Button(
                    onClick = { forgot = onForget() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Forget the old pairing", color = Wrangler.Primary, fontWeight = FontWeight.Bold) }
            }
            true -> Text("Forgotten. Now tap Scan and connect again; when the app asks, triple-click " +
                "the turtle to open its Bluetooth screen and enter the code it shows.", color = Color.White, fontSize = 13.sp)
            false -> Text("Android wouldn't forget it from here. In Bluetooth settings, tap the turtle and " +
                "choose Forget (or Unpair), then come back, tap Scan and pair again.", color = Color.White, fontSize = 13.sp)
        }
        if (forgot != true) OutlinedButton(
            onClick = {
                try { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) } catch (_: Exception) {}
            },
            border = BorderStroke(1.dp, Color.White),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Open Bluetooth settings", color = Color.White) }
    }
}

/** Steps still to come, dimmed on the green. */
private val Faint = Color.White.copy(alpha = 0.5f)

/**
 * Whether the connecting drawer is down, hoisted so the top bar can hide its
 * sections handle meanwhile. Starts up, so the first frame slides it down.
 */
@Composable
fun rememberConnectingDrawer(state: LinkState, hs: Handshake): MutableTransitionState<Boolean> {
    val inSetup = state is LinkState.Connecting || state is LinkState.Preparing ||
        state is LinkState.Reconnecting
    val failed = state is LinkState.Disconnected && hs.failed != null
    val shown = remember { MutableTransitionState(false) }
    LaunchedEffect(inSetup, failed) {
        if (inSetup || failed) shown.targetState = true
        else {
            delay(DONE_HOLD_MS)
            shown.targetState = false
        }
    }
    return shown
}

private enum class StepMark { DONE, WORKING, FAILED, TODO }

@Composable
private fun StepRow(label: String, mark: StepMark, detail: String?) {
    Row(verticalAlignment = Alignment.Top) {
        Box(Modifier.width(26.dp).padding(top = 2.dp)) {
            when (mark) {
                StepMark.WORKING -> CircularProgressIndicator(
                    Modifier.size(14.dp), color = Color.White, strokeWidth = 2.dp)
                else -> Text(
                    when (mark) { StepMark.DONE -> "✓"; StepMark.FAILED -> "✕"; else -> "○" },
                    color = if (mark == StepMark.TODO) Faint else Color.White,
                    fontSize = 14.sp, fontWeight = FontWeight.Bold,
                )
            }
        }
        Column {
            Text(
                label, fontSize = 14.sp,
                color = if (mark == StepMark.TODO) Faint else Color.White,
                fontWeight = if (mark == StepMark.WORKING || mark == StepMark.FAILED) FontWeight.Bold else FontWeight.Normal,
            )
            if (detail != null) Text(detail, color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp)
        }
    }
}
