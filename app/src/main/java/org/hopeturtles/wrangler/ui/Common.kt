package org.hopeturtles.wrangler.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.hopeturtles.wrangler.ui.theme.Wrangler

/** Top bar on every screen: title, link status, settings gear top-right. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WranglerTopBar(title: String, status: String?, statusOk: Boolean, onSettings: () -> Unit) {
    TopAppBar(
        title = { Text(title, fontWeight = FontWeight.Bold, color = Wrangler.Dark, fontSize = 18.sp) },
        actions = {
            if (status != null) {
                Text(
                    (if (statusOk) "● " else "○ ") + status,
                    color = if (statusOk) Wrangler.Primary else Wrangler.TextMuted,
                    fontSize = 13.sp, fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.width(4.dp))
            }
            IconButton(onClick = onSettings) {
                Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = Wrangler.Dark)
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Wrangler.Surface),
    )
}

/** White card with the site's thin green-grey border. */
@Composable
fun WCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Wrangler.Surface),
        border = BorderStroke(1.dp, Wrangler.CardBorder),
    ) {
        Column(Modifier.padding(14.dp), content = content)
    }
}

@Composable
fun CardLabel(text: String) {
    Text(text, fontWeight = FontWeight.Bold, color = Wrangler.Dark, fontSize = 15.sp)
}

@Composable
fun Muted(text: String) {
    Text(text, color = Wrangler.TextMuted, fontSize = 13.sp)
}

/** A label: value row; null values show "—" (never a sentinel number). */
@Composable
fun Field(label: String, value: String?) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Wrangler.TextMuted, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(value ?: "—", color = Wrangler.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

/** The one pink element a screen may have: ghost style, never a solid fill. */
@Composable
fun PinkButton(text: String, onClick: () -> Unit, enabled: Boolean = true, modifier: Modifier = Modifier) {
    OutlinedButton(
        onClick = onClick, enabled = enabled, modifier = modifier.fillMaxWidth(),
        border = BorderStroke(1.dp, if (enabled) Wrangler.Pink else Wrangler.CardBorder),
    ) {
        Text(text, color = if (enabled) Wrangler.PinkDark else Wrangler.TextMuted, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun GreenOutlineButton(text: String, onClick: () -> Unit, enabled: Boolean = true, modifier: Modifier = Modifier) {
    OutlinedButton(
        onClick = onClick, enabled = enabled, modifier = modifier.fillMaxWidth(),
        border = BorderStroke(1.dp, Wrangler.Light),
    ) {
        Text(text, color = Wrangler.Dark, fontWeight = FontWeight.Bold)
    }
}
