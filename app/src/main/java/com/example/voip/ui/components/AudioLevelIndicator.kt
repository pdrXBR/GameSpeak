package com.example.voip.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate

@Composable
fun AudioLevelIndicator(level: Float, modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition()
    val angle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing)
        )
    )

    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2, size.height / 2)
        val radius = size.minDimension / 2
        val pulseRadius = radius * (0.8f + level * 0.2f)

        drawCircle(
            color = Color.Cyan.copy(alpha = 0.3f),
            radius = pulseRadius,
            center = center
        )
        drawCircle(
            color = Color.Cyan,
            radius = radius * 0.2f,
            center = center
        )
        rotate(degrees = angle) {
            drawLine(
                color = Color.White,
                start = center,
                end = Offset(center.x + radius * 0.8f, center.y),
                strokeWidth = 4f
            )
        }
    }
}