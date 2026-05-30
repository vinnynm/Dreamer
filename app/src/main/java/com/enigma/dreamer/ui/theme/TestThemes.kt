package com.enigma.dreamer.ui.theme

import android.system.Os.close
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

@Composable
fun September(modifier: Modifier = Modifier) {
    val WoodBrown = Color(0xFF8B4513)
    val LightWood = Color(0xFFAB5328)

    val woodBrush = Brush.linearGradient(
        0.0f to WoodBrown,
        0.2f to LightWood,
        0.4f to WoodBrown,
        0.7f to LightWood,
        1.0f to WoodBrown,
        start = Offset(0f, 0f),
        end = Offset(100f, 200f) // Angled grain
    )

    Box(
        modifier = Modifier
            .size(64.dp)
            .aspectRatio(1f)
            .background(woodBrush, shape = RoundedCornerShape(4.dp))
            .drawWithContent {
                drawContent()
                // Optional: Draw very thin, darker lines for extra "grain"
                for (i in 0..10) {
                    drawLine(
                        color = Color.Black.copy(alpha = 0.1f),
                        start = Offset(i * 20f, 0f),
                        end = Offset(i * 20f + 10f, size.height),
                        strokeWidth = 1f
                    )
                }
            }
    )
}

@Preview
@Composable
private fun SeptemberPrev() {
    September()
}

@Composable
fun Icicle(modifier: Modifier = Modifier) {
    val IceBlue = Color(0xFFE0F7FA)
    val DeepIce = Color(0xFF81D4FA)

    Box(
        modifier = Modifier
            .size(64.dp)
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(IceBlue, DeepIce)
                ),
                shape = RoundedCornerShape(8.dp)
            )

            .border(1.dp, Color.White.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
    ) {
        // Add a "Gloss" overlay
        Canvas(modifier = Modifier.matchParentSize()) {
            val path = Path().apply {
                moveTo(0f, 0f)
                lineTo(size.width, 0f)
                lineTo(0f, size.height * 0.5f)
                close()
            }
            drawPath(
                path = path,
                color = Color.White.copy(alpha = 0.3f)
            )
        }
    }
}

@Preview
@Composable
private fun IciclePrev() {
    Icicle()
}