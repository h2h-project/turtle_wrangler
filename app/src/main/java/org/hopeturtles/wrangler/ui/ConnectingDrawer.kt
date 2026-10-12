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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
 * White drawer under the top bar, so it reads as part of it, that slides down
 * while the link is set up and lists every handshake step as it happens. It
 * slides back up once the turtle is Ready. If setup fails it stays down and
 * says which step stopped and why; the top bar's "Scan" is the way back.
 */
@Composable
fun ConnectingDrawer(state: LinkState, hs: Handshake, modifier: Modifier = Modifier) {
    val inSetup = state is LinkState.Connecting || state is LinkState.Preparing
    val failed = state is LinkState.Disconnected && hs.failed != null
    // Starts up so the first frame slides it down rather than appearing.
    val shown = remember { MutableTransitionState(false) }
    LaunchedEffect(inSetup, failed) {
        if (inSetup || failed) shown.targetState = true
        else {
            delay(DONE_HOLD_MS)
            shown.targetState = false
        }
    }
    AnimatedVisibility(
        visibleState = shown,
        enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
        exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
        modifier = modifier,
    ) {
        Surface(
            color = Wrangler.Surface,
            shape = RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp),
            shadowElevation = 3.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val ready = state is LinkState.Ready
                Text(
                    when {
                        ready -> "Connected"
                        failed -> "Couldn't connect"
                        else -> "Connecting…"
                    },
                    fontWeight = FontWeight.Bold, color = Wrangler.Dark, fontSize = 16.sp,
                )
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
            }
        }
    }
}

private enum class StepMark { DONE, WORKING, FAILED, TODO }

@Composable
private fun StepRow(label: String, mark: StepMark, detail: String?) {
    Row(verticalAlignment = Alignment.Top) {
        Box(Modifier.width(26.dp).padding(top = 2.dp)) {
            when (mark) {
                StepMark.WORKING -> CircularProgressIndicator(
                    Modifier.size(14.dp), color = Wrangler.Primary, strokeWidth = 2.dp)
                else -> Text(
                    when (mark) { StepMark.DONE -> "✓"; StepMark.FAILED -> "✕"; else -> "○" },
                    color = when (mark) {
                        StepMark.DONE -> Wrangler.Primary
                        StepMark.FAILED -> Wrangler.Dark
                        else -> Wrangler.TextMuted
                    },
                    fontSize = 14.sp, fontWeight = FontWeight.Bold,
                )
            }
        }
        Column {
            Text(
                label, fontSize = 14.sp,
                color = if (mark == StepMark.TODO) Wrangler.TextMuted else Wrangler.Text,
                fontWeight = if (mark == StepMark.WORKING || mark == StepMark.FAILED) FontWeight.Bold else FontWeight.Normal,
            )
            if (detail != null) Muted(detail)
        }
    }
}
