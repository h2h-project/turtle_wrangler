package org.hopeturtles.wrangler.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.hopeturtles.wrangler.ble.FoundTurtle
import org.hopeturtles.wrangler.data.LastSeen
import org.hopeturtles.wrangler.ui.theme.Wrangler
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ScanScreen(
    found: List<FoundTurtle>,
    scanning: Boolean,
    noTurtles: Boolean,
    lastSeen: List<LastSeen>,
    onScan: () -> Unit,
    onPick: (FoundTurtle) -> Unit,
    onSettings: () -> Unit,
) {
    Scaffold(
        topBar = { WranglerTopBar("Turtle Wrangler", if (scanning) "Scanning" else "Not connected", false, onSettings) },
        containerColor = Wrangler.Background,
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (found.isEmpty() && noTurtles) {
                NoTurtles(lastSeen, onScan)
            } else {
                if (found.isEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (scanning) CircularProgressIndicator(Modifier.padding(end = 12.dp), color = Wrangler.Primary)
                        Muted(if (scanning) "Looking for turtles nearby…" else "Tap Scan to look for turtles.")
                    }
                }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f, fill = false)) {
                    items(found, key = { it.address }) { t -> TurtleRow(t, onPick) }
                }
                GreenOutlineButton(if (scanning) "Scanning…" else "Scan", onScan, enabled = !scanning)
            }
        }
    }
}

@Composable
private fun TurtleRow(t: FoundTurtle, onPick: (FoundTurtle) -> Unit) {
    WCard(Modifier.clickable { onPick(t) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("🐢 ${t.name}", fontWeight = FontWeight.Bold, color = Wrangler.Dark, fontSize = 17.sp)
                Muted(if (t.bonded) "Paired · ${t.address}" else t.address)
            }
            Muted("${t.rssi} dBm")
        }
    }
}

/** App Phase 1 "No turtles nearby" — copy from the app plan. The login is
 *  Phase 8 (Buwana + hopeturtles.org app API), so it's shown but disabled. */
@Composable
private fun NoTurtles(lastSeen: List<LastSeen>, onScan: () -> Unit) {
    WCard {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("🐢", fontSize = 44.sp)
            Spacer(Modifier.height(8.dp))
            Text("Sorry, we can't find any turtles to connect to via Bluetooth :-(",
                textAlign = TextAlign.Center, color = Wrangler.Text)
            Spacer(Modifier.height(8.dp))
            Text("Would you like to login and connect to your other turtles that are swimming in WiFi?",
                textAlign = TextAlign.Center, color = Wrangler.Text)
            Spacer(Modifier.height(12.dp))
            PinkButton("Log in with Buwana", onClick = {}, enabled = false)
            Muted("coming in a later version")
            Spacer(Modifier.height(8.dp))
            GreenOutlineButton("Scan again", onScan)
            Spacer(Modifier.height(8.dp))
            Muted("Is the turtle's Bluetooth window open? Visit its Bluetooth screen to reopen it.")
        }
    }
    if (lastSeen.isNotEmpty()) {
        WCard {
            CardLabel("Last seen")
            val fmt = SimpleDateFormat("d MMM HH:mm", Locale.getDefault())
            lastSeen.take(5).forEach { s ->
                val bits = listOfNotNull(
                    fmt.format(Date(s.seenAtMs)),
                    s.socPct?.let { "$it%" },
                    if (s.lat != null && s.lon != null) "%.4f, %.4f".format(s.lat, s.lon) else null,
                )
                Field(s.name, bits.joinToString(" · "))
            }
        }
    }
}
