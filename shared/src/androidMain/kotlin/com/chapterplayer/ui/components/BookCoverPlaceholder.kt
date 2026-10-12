package com.chapterplayer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Gradient placeholder for books without cover art.
 * Each book gets a consistent gradient derived from its title hash,
 * so it doesn't jump around between renders.
 */
@Composable
fun BookCoverPlaceholder(
    title: String,
    modifier: Modifier = Modifier
) {
    val gradient = remember(title) { generateGradient(title) }

    Box(
        modifier = modifier.background(brush = gradient),
        contentAlignment = Alignment.BottomStart
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.9f),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Start,
            modifier = Modifier.padding(8.dp)
        )
    }
}

private fun generateGradient(seed: String): Brush {
    val hash = seed.hashCode()
    val hue1 = ((hash and 0xFF) / 255f) * 360f
    val hue2 = (hue1 + 40f) % 360f

    fun hsvToColor(hue: Float, sat: Float, value: Float): Color {
        val h = hue / 60f
        val i = h.toInt()
        val f = h - i
        val p = value * (1 - sat)
        val q = value * (1 - sat * f)
        val t = value * (1 - sat * (1 - f))
        return when (i % 6) {
            0 -> Color(value, t, p)
            1 -> Color(q, value, p)
            2 -> Color(p, value, t)
            3 -> Color(p, q, value)
            4 -> Color(t, p, value)
            else -> Color(value, p, q)
        }
    }

    val color1 = hsvToColor(hue1, 0.6f, 0.3f)
    val color2 = hsvToColor(hue2, 0.5f, 0.2f)

    return Brush.linearGradient(listOf(color1, color2))
}
