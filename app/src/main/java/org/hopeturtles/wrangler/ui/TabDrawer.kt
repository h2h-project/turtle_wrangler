package org.hopeturtles.wrangler.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.hopeturtles.wrangler.ui.theme.Wrangler

/** How far a finger must travel (px) before a swipe opens or closes the drawer. */
private const val SWIPE_PX = 40f

/**
 * Swipe down to call [onDown], up to call [onUp]. Taps pass through, so
 * the buttons inside (gear, Scan, tab items) still work.
 */
fun Modifier.verticalSwipe(onDown: () -> Unit, onUp: () -> Unit): Modifier = pointerInput(Unit) {
    var travel = 0f
    detectVerticalDragGestures(
        onDragStart = { travel = 0f },
        onVerticalDrag = { change, dy -> travel += dy; change.consume() },
        onDragEnd = {
            if (travel > SWIPE_PX) onDown() else if (travel < -SWIPE_PX) onUp()
        },
    )
}

/**
 * The short white line under the top bar: swipe down on the bar (or tap it)
 * for the sections. Hidden, but still taking its space so nothing jumps,
 * while [visible] is false (the connecting drawer is down).
 */
@Composable
fun TabHandle(open: Boolean, visible: Boolean, onToggle: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().background(Wrangler.Primary)
            .clickable(
                enabled = visible,
                interactionSource = remember { MutableInteractionSource() }, indication = null,
                role = Role.Button, onClickLabel = if (open) "Hide sections" else "Show sections",
                onClick = onToggle,
            )
            .padding(top = 2.dp, bottom = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (visible) Box(Modifier.width(64.dp).height(4.dp)
            .background(Color.White.copy(alpha = if (open) 0.9f else 0.6f), RoundedCornerShape(50)))
        else Box(Modifier.height(4.dp))
    }
}

/**
 * Green drawer under the top bar with the four sections (Dashboard,
 * Navigate, GPS, Diagnostics), so each page gets the whole screen. Picking
 * one closes it; so do a swipe up, a tap outside, and back.
 */
@Composable
fun TabDrawer(
    open: Boolean,
    tab: Tab,
    settingsOpen: Boolean,
    onPick: (Tab) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize()) {
        // A light scrim over the page: tap it to close.
        AnimatedVisibility(open, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.18f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
                    onClick = onClose))
        }
        AnimatedVisibility(
            open,
            enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
            exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
        ) {
            Surface(
                color = Wrangler.Primary,
                shape = RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp),
                shadowElevation = 3.dp,
                modifier = Modifier.fillMaxWidth().verticalSwipe(onDown = {}, onUp = onClose),
            ) {
                Row(Modifier.padding(horizontal = 8.dp, vertical = 10.dp)) {
                    Tab.entries.forEach { t ->
                        val selected = !settingsOpen && t == tab
                        Column(
                            Modifier.weight(1f)
                                .background(if (selected) Color.White.copy(alpha = 0.2f) else Color.Transparent,
                                    RoundedCornerShape(12.dp))
                                .clickable(role = Role.Tab) { onPick(t) }
                                .padding(vertical = 10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Icon(t.icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(26.dp))
                            Text(t.label, color = Color.White, fontSize = 12.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }
        }
    }
}
