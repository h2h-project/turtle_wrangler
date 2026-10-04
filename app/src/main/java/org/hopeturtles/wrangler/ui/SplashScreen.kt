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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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

/** Swim (frames 1, 2) three times, then hold the smile: (frame index, ms). */
private val SEQUENCE: List<Pair<Int, Long>> = List(3) { listOf(0 to 400L, 1 to 400L) }.flatten() + (2 to 1_000L)
private const val FADE_MS = 300

/**
 * Full-screen green splash: the turtle swims (frames 1–2, three times,
 * 0.4 s each), then smiles for a second, gently bobbing throughout. The
 * name, credit and version sit at the bottom. Then the whole splash fades
 * away to reveal the app (which has been starting underneath it).
 */
@Composable
fun SplashScreen(onDone: () -> Unit) {
    var phase by remember { mutableIntStateOf(0) }
    val ctx = LocalContext.current
    val version = remember {
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?"
    }
    val screen = remember { Animatable(1f) }      // whole-splash opacity (fade-out)
    val turtle = remember { Animatable(0f) }      // turtle opacity (fade-in)
    val bob by rememberInfiniteTransition(label = "bob").animateFloat(
        initialValue = -2f, targetValue = 2f,
        animationSpec = infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "bob",
    )

    LaunchedEffect(Unit) {
        launch { turtle.animateTo(1f, tween(FADE_MS)) }
        for ((i, ms) in SEQUENCE) {
            phase = i
            delay(ms)
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
                fontWeight = FontWeight.Bold, fontSize = 11.sp, lineHeight = 13.sp, softWrap = false,
            )
        }
        Column(
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 32.dp)
                .graphicsLayer { alpha = turtle.value },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Turtle Wrangler", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text("A Human2Human Development", color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp)
            Text("v$version", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp,
                modifier = Modifier.padding(top = 8.dp))
        }
    }
}
