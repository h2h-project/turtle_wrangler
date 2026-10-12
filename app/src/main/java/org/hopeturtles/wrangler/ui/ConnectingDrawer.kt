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
            } }
        }
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
