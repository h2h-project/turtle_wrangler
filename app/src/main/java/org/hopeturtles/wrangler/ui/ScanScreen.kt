package org.hopeturtles.wrangler.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import org.hopeturtles.wrangler.ble.FoundTurtle
import org.hopeturtles.wrangler.data.LastSeen
import org.hopeturtles.wrangler.ui.theme.Wrangler

/**
 * The green home screen when no turtle is connected: turtles found so far
 * at the top, the ASCII turtle in the middle (it swims while scanning, in
 * place of a spinner), and Scan / Connect to Server at the bottom. Tap
 * Scan again while it's scanning to stop. No top
 * bar, so it reads as a continuation of the splash.
 */
@Composable
fun ScanScreen(
    found: List<FoundTurtle>,
    scanning: Boolean,
    noTurtles: Boolean,
    lastSeen: List<LastSeen>,
    onScan: () -> Unit,
    onCancel: () -> Unit,
    onPick: (FoundTurtle) -> Unit,
) {
    var serverInfo by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().background(Wrangler.Primary).safeDrawingPadding().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Equal weights above and below keep the turtle in the middle.
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (found.isNotEmpty()) {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(found, key = { it.address }) { t -> TurtleRow(t, onPick) }
                }
            } else if (noTurtles && lastSeen.isNotEmpty()) {
                LastSeenCards(lastSeen)
            }
        }

        SwimmingTurtle(scanning)
        Spacer(Modifier.height(12.dp))
        Text(
            when {
                scanning -> "Looking for\nturtles nearby."
                found.isNotEmpty() -> "Tap a turtle to connect."
                noTurtles -> "Sorry, we can't find any turtles nearby. Is the turtle's Bluetooth " +
                    "window open? Visit its Bluetooth screen to reopen it."
                else -> "Tap Scan to look for turtles."
            },
            color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp, textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        Column(
            Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.Bottom,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Pink letters on white, no pink fill.
            // While scanning it reads "Scanning…" and tapping it cancels.
            Button(
                onClick = if (scanning) onCancel else onScan,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.White),
            ) {
                if (scanning) ScanningLabel()
                else Text("Scan for Turtles", color = Wrangler.PinkDark, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = { serverInfo = true },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                border = BorderStroke(1.dp, Color.White),
            ) {
                Text("Connect to Server", color = Color.White, fontWeight = FontWeight.Normal, fontSize = 18.sp)
            }
        }
    }

    if (serverInfo) {
        AlertDialog(
            onDismissRequest = { serverInfo = false },
            title = { Text("Connect to Server", fontWeight = FontWeight.Bold, color = Wrangler.Dark) },
            text = { Text("This feature is still in development.", color = Wrangler.Text) },
            confirmButton = {
                TextButton(onClick = { serverInfo = false }) {
                    Text("OK", color = Wrangler.Primary, fontWeight = FontWeight.Bold)
                }
            },
            containerColor = Wrangler.Surface,
        )
    }
}

/** Swims (logo-2 / logo-3, 0.5 s each) while scanning; smiles otherwise. */
@Composable
private fun SwimmingTurtle(scanning: Boolean) {
    var frame by remember { mutableStateOf(TurtleAscii.SMILE) }
    LaunchedEffect(scanning) {
        if (!scanning) {
            frame = TurtleAscii.SMILE
            return@LaunchedEffect
        }
        while (true) {
            frame = TurtleAscii.SWIM_2
            delay(500)
            frame = TurtleAscii.SWIM_3
            delay(500)
        }
    }
    AsciiTurtle(frame, fontSize = 8.sp)
}

/**
 * "Scanning" with dots that count . .. ... (none), 0.5 s each. The dots
 * that aren't showing are drawn transparent so the word never shifts.
 */
@Composable
private fun ScanningLabel() {
    var dots by remember { mutableIntStateOf(1) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            dots = (dots + 1) % 4
        }
    }
    Text(
        buildAnnotatedString {
            append("Scanning")
            append(".".repeat(dots))
            withStyle(SpanStyle(color = Color.Transparent)) { append(".".repeat(3 - dots)) }
        },
        color = Wrangler.Primary, fontWeight = FontWeight.Bold, fontSize = 18.sp,
    )
}

/** A turtle this scan heard: full-strength pink-tint card, green edge. */
@Composable
private fun TurtleRow(t: FoundTurtle, onPick: (FoundTurtle) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onPick(t) },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Wrangler.PinkTint),
        border = BorderStroke(2.dp, Wrangler.Accent),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("🐢 ${t.name}", fontWeight = FontWeight.Bold, color = Wrangler.Dark, fontSize = 17.sp)
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = Wrangler.Fuchsia)) { append("● ") }
                        append("In range" + if (t.bonded) " · Paired" else "")
                    },
                    color = Wrangler.Primary, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                )
                Muted(t.address)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("Connect ›", color = Wrangler.Primary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Muted("${t.rssi} dBm")
            }
        }
    }
}

/** Shown when a scan finds nothing: one faded card per remembered turtle,
 *  so none of them reads as a turtle that's here now. */
@Composable
private fun LastSeenCards(lastSeen: List<LastSeen>) {
    val fmt = remember { SimpleDateFormat("d MMM HH:mm", Locale.getDefault()) }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(lastSeen.take(5), key = { it.address }) { s ->
            WCard(Modifier.alpha(0.6f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("🐢 ${s.name}", fontWeight = FontWeight.Bold, color = Wrangler.Dark, fontSize = 17.sp,
                        modifier = Modifier.weight(1f))
                    Text(
                        "Not in range", color = Wrangler.TextMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.background(Wrangler.CardBorder, RoundedCornerShape(50))
                            .padding(horizontal = 10.dp, vertical = 3.dp),
                    )
                }
                Spacer(Modifier.height(6.dp))
                Row {
                    Muted("Last seen ${fmt.format(Date(s.seenAtMs))}")
                    Spacer(Modifier.weight(1f))
                    Muted("Battery: ${s.socPct?.let { "$it%" } ?: "—"}")
                }
            }
        }
    }
}
