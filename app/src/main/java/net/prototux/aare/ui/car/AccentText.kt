package net.prototux.aare.ui.car

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit

/**
 * Text in the accent color, kept readable on the car when that color is dark (the GTS red).
 *
 * The car gets its screen as H.264 video, whose color has half the resolution of its brightness:
 * the GTS red on a dark grey differs from it almost only in color, so its letters came out blocky
 * and hard to read (2026-11-13). Two remedies, both keeping the exact red:
 * - [backdrop]: the text on pure black (the user's choice), a small rounded patch behind it, which
 *   gives the letters the most brightness step there can be. Off where the place around it is
 *   already black (the clock card's agenda row).
 * - a thin near-black outline around the letters, for where there's no black behind them.
 * A light accent (the other styles' teal) needs neither: it's plain text.
 */
@Composable
internal fun AccentText(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontWeight: FontWeight? = null,
    style: TextStyle = LocalTextStyle.current,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    backdrop: Boolean = true,
) {
    // On a light theme (1.5.0's day colours) a dark accent already stands out from its background.
    if (color.luminance() >= DARK_ACCENT || androidx.compose.material3.MaterialTheme.colorScheme.surface.luminance() > 0.5f) {
        Text(text, modifier, color = color, fontSize = fontSize, fontWeight = fontWeight, maxLines = maxLines, overflow = overflow, style = style)
        return
    }
    val size = if (fontSize != TextUnit.Unspecified) fontSize else style.fontSize
    val density = LocalDensity.current
    val px = with(density) { size.toPx() }
    // About a tenth of the letters' height, at least 2 px: drawn on the outline's middle, the
    // letter on top covers its inner half.
    val width = (px * OUTLINE_SHARE).coerceIn(2f, 6f)
    val patch = if (backdrop) with(density) {
        Modifier.background(Color.Black, RoundedCornerShape((px * 0.3f).toDp()))
            .padding(horizontal = (px * 0.35f).toDp(), vertical = (px * 0.08f).toDp())
    } else Modifier
    Box(modifier.then(patch)) {
        Text(text, fontSize = fontSize, fontWeight = fontWeight, maxLines = maxLines, overflow = overflow,
            style = style.copy(color = OUTLINE, drawStyle = Stroke(width = width, join = StrokeJoin.Round)))
        Text(text, color = color, fontSize = fontSize, fontWeight = fontWeight, maxLines = maxLines, overflow = overflow, style = style)
    }
}

/** Below this luminance the accent is dark enough to blur on the car's video (the GTS red is about 0.17). */
private const val DARK_ACCENT = 0.3f

/** The outline: near black, so it reads as the letter's edge rather than a second color. */
private val OUTLINE = Color(0xFF050506)

/** The outline's width against the font size (the thin one the user picked, 2026-11-13). */
private const val OUTLINE_SHARE = 0.11f
