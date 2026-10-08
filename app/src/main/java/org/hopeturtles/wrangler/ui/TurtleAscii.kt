package org.hopeturtles.wrangler.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * The hopeturtles.org ASCII turtle (public/turtles-ascii-logo*.txt), which is
 * also turtleOS's waiting-screen turtle. Lines are padded to one width so the
 * block never shifts between frames.
 */
object TurtleAscii {
    /** turtles-ascii-logo.txt: the smiling rest frame. */
    val SMILE = frame(
        "  _______    ___",
        "/         \\ |  0|",
        "|         |/ __\\|",
        "|___________/",
        " |__| |__|",
    )

    /** turtles-ascii-logo-2.txt: swim, head tucked. */
    val SWIM_2 = frame(
        "  _______   ___",
        "/         \\|  0|",
        "|         || __-",
        "|__________/",
        "  |__| |__|",
    )

    /** turtles-ascii-logo-3.txt: swim, head out. */
    val SWIM_3 = frame(
        "  _______    ___",
        "/         \\ |  0|",
        "|         |/ ___-",
        "|___________/",
        " |__| |__|",
    )

    private fun frame(vararg lines: String) = lines.joinToString("\n") { it.padEnd(17) }
}

/** One ASCII turtle frame in bold white monospace. */
@Composable
fun AsciiTurtle(
    frame: String, modifier: Modifier = Modifier,
    fontSize: TextUnit = 11.sp, color: Color = Color.White,
) {
    Text(
        frame, modifier = modifier, color = color, fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold, fontSize = fontSize, lineHeight = fontSize * 1.18f,
        softWrap = false,
    )
}

