package org.hopeturtles.wrangler.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.hopeturtles.wrangler.ui.theme.Wrangler

/**
 * The turtle from turtleOS's waiting screen (device/src/ui/screens/
 * turtle_waiting.py): swim 1, swim 2, then the smiling rest frame. Lines are
 * padded to one width so the block never shifts between phases.
 */
private val PHASES: List<String> = listOf(
    listOf(
        "  _______    ___",
        "/         \\ |  0|",
        "|         |/ ___-",
        "|___________/",
        " |__| |__|",
    ),
    listOf(
        "  _______   ___",
        "/         \\|  0|",
        "|         || __-",
        "|__________/",
        "  |__| |__|",
    ),
    listOf(
        "  _______    ___",
        "/         \\ |  0|",
        "|         |/ __\\|",
        "|___________/",
        " |__| |__|",
    ),
).map { lines -> lines.joinToString("\n") { it.padEnd(17) } }

private const val PHASE_MS = 500L
private const val FADE_MS = 300

/**
 * Full-screen green splash: the three phases for half a second each, the
 * turtle gently bobbing, then the whole splash fades away to reveal the app
 * (which has been starting underneath it).
 */
@Composable
fun SplashScreen(onDone: () -> Unit) {
    var phase by remember { mutableIntStateOf(0) }
    val screen = remember { Animatable(1f) }      // whole-splash opacity (fade-out)
    val turtle = remember { Animatable(0f) }      // turtle opacity (fade-in)
    val bob by rememberInfiniteTransition(label = "bob").animateFloat(
        initialValue = -3f, targetValue = 3f,
        animationSpec = infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "bob",
    )

    LaunchedEffect(Unit) {
        launch { turtle.animateTo(1f, tween(FADE_MS)) }
        for (i in PHASES.indices) {
            phase = i
            delay(PHASE_MS)
        }
        screen.animateTo(0f, tween(FADE_MS))
        onDone()
    }

    Box(
        Modifier.fillMaxSize().graphicsLayer { alpha = screen.value }.background(Wrangler.Primary),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = phase,
            transitionSpec = { fadeIn(tween(120)) togetherWith fadeOut(tween(120)) },
            modifier = Modifier.graphicsLayer {
                alpha = turtle.value
                translationY = bob * density
            },
            label = "turtle",
        ) { i ->
            Text(
                PHASES[i], color = Color.White, fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 26.sp, softWrap = false,
            )
        }
    }
}
