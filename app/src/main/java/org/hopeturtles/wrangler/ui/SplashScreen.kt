package org.hopeturtles.wrangler.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.hopeturtles.wrangler.ui.theme.Wrangler

/**
 * Swim (logo-2, logo-3) twice at 0.5 s a frame, then hold the smile
 * (turtles-ascii-logo.txt) for a second. Frames cut, like the OLED.
 */
private val SEQUENCE: List<Pair<String, Long>> = listOf(
    TurtleAscii.SWIM_2 to 500L,
    TurtleAscii.SWIM_3 to 500L,
    TurtleAscii.SWIM_2 to 500L,
    TurtleAscii.SWIM_3 to 500L,
    TurtleAscii.SMILE to 1_000L,
)
private const val FADE_MS = 300

/**
 * Full-screen green splash: the turtle swims, then smiles for a second
 * ([SEQUENCE]), gently bobbing throughout. The name, credit and version
 * sit at the bottom. Then the whole splash fades away to reveal the app
 * (which has been starting underneath it).
 */
@Composable
fun SplashScreen(onDone: () -> Unit) {
    var frame by remember { mutableStateOf(SEQUENCE.first().first) }
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
        for ((f, ms) in SEQUENCE) {
            frame = f
            delay(ms)
        }
        screen.animateTo(0f, tween(FADE_MS))
        onDone()
    }

    Box(
        Modifier.fillMaxSize().graphicsLayer { alpha = screen.value }.background(Wrangler.Primary),
        contentAlignment = Alignment.Center,
    ) {
        AsciiTurtle(
            frame,
            Modifier.graphicsLayer {
                alpha = turtle.value
                translationY = bob * density
            },
        )
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
