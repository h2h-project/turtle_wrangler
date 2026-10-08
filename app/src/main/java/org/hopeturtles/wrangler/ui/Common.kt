package org.hopeturtles.wrangler.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.hopeturtles.wrangler.R
import org.hopeturtles.wrangler.ui.theme.Wrangler

/**
 * Top bar on every screen: title, link status, settings gear top-right.
 * With [onStatusClick] the status becomes a button (e.g. "Disconnected · Scan").
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WranglerTopBar(
    title: String, status: String?, statusOk: Boolean, onSettings: () -> Unit,
    onStatusClick: (() -> Unit)? = null, statusAction: String = "Scan",
    connected: Boolean? = null,
) {
    TopAppBar(
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // The app icon's smiling ASCII turtle: green = connected, grey = not.
                // The art fills the middle half of its 108-unit canvas, hence 66 dp
                // for a turtle about 33 dp wide; the offsets trim the empty margin.
                if (connected != null) Icon(
                    painterResource(R.drawable.ic_launcher_foreground),
                    contentDescription = if (connected) "Connected" else "Not connected",
                    tint = if (connected) Wrangler.Primary else Wrangler.TextMuted,
                    modifier = Modifier.size(66.dp).offset(x = (-14).dp),
                )
                Text(title, fontWeight = FontWeight.Bold, color = Wrangler.Dark, fontSize = 18.sp,
                    modifier = Modifier.offset(x = if (connected != null) (-24).dp else 0.dp))
            }
        },
        actions = {
            if (status != null && onStatusClick != null) {
                OutlinedButton(
                    onClick = onStatusClick,
                    border = BorderStroke(1.dp, Wrangler.Primary),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
                    modifier = Modifier.height(34.dp),
                ) {
                    Text("○ $status · $statusAction", color = Wrangler.Primary, fontSize = 13.sp,
                        fontWeight = FontWeight.Bold)
                }
            } else if (status != null) {
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

/** A card title with a status pill at the right: green when [ok], pink tint when not. */
@Composable
fun CardLabelWithPill(text: String, pill: String?, ok: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text, fontWeight = FontWeight.Bold, color = Wrangler.Dark, fontSize = 15.sp,
            modifier = Modifier.weight(1f))
        if (pill != null) Text(
            pill, color = if (ok) Wrangler.Primary else Wrangler.PinkDark,
            fontSize = 12.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier
                .background(if (ok) Wrangler.Light else Wrangler.PinkTint, RoundedCornerShape(50))
                .padding(horizontal = 10.dp, vertical = 3.dp),
        )
    }
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

/** Solid lightning bolt — charging (same meaning as the OLED's bolt). */
@Composable
fun Bolt(size: androidx.compose.ui.unit.Dp = 14.dp, color: androidx.compose.ui.graphics.Color = Wrangler.Primary) {
    androidx.compose.foundation.Canvas(Modifier.width(size).height(size)) {
        val w = this.size.width; val h = this.size.height
        val p = androidx.compose.ui.graphics.Path().apply {
            moveTo(w * 0.62f, 0f); lineTo(w * 0.12f, h * 0.58f); lineTo(w * 0.46f, h * 0.58f)
            lineTo(w * 0.36f, h); lineTo(w * 0.88f, h * 0.40f); lineTo(w * 0.54f, h * 0.40f); close()
        }
        drawPath(p, color)
    }
}

/** "⚡ Charging" / "Discharging" / "Idle" from the INA219 current. */
@Composable
fun ChargeLine(ma: Int?) {
    val c = Format.charge(ma) ?: return
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (c == Format.Charge.CHARGING) {
            Bolt()
            Spacer(Modifier.width(4.dp))
        }
        Text(Format.chargeWord(c)!!, fontWeight = FontWeight.Bold, fontSize = 13.sp,
            color = if (c == Format.Charge.CHARGING) Wrangler.Primary else Wrangler.TextMuted)
    }
}

/** What the last command did, as a card (pink text = it failed). */
@Composable
fun NoticeCard(notice: org.hopeturtles.wrangler.Notice?, busy: String?) {
    val text = busy?.let { "$it…" } ?: notice?.text ?: return
    val ok = busy != null || notice?.ok == true
    WCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (busy != null) {
                androidx.compose.material3.CircularProgressIndicator(
                    Modifier.width(16.dp).height(16.dp), color = Wrangler.Primary, strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(text, color = if (ok) Wrangler.Dark else Wrangler.PinkDark, fontSize = 14.sp)
        }
    }
}

/** Yes/no before anything that changes where the turtle goes. */
@Composable
fun ConfirmDialog(
    title: String, body: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit,
    dismiss: String = "Cancel",
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.Bold, color = Wrangler.Dark) },
        text = { Text(body, color = Wrangler.Text) },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = { onDismiss(); onConfirm() }) {
                Text(confirm, color = Wrangler.Primary, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text(dismiss, color = Wrangler.TextMuted)
            }
        },
        containerColor = Wrangler.Surface,
    )
}
