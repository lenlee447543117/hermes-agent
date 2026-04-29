package com.ailaohu.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ailaohu.service.hermes.AgentState

@Composable
fun RobotStateIndicator(
    state: AgentState,
    size: Dp = 80.dp,
    modifier: Modifier = Modifier
) {
    val stateColor = when (state) {
        AgentState.IDLE -> Color(0xFF9E9E9E)
        AgentState.LISTENING -> Color(0xFFFF9800)
        AgentState.THINKING -> Color(0xFF2196F3)
        AgentState.EXECUTING -> Color(0xFFFF5722)
        AgentState.SUCCESS -> Color(0xFF4CAF50)
        AgentState.ERROR -> Color(0xFFF44336)
    }

    val infiniteTransition = rememberInfiniteTransition(label = "robot")

    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = when (state) {
                    AgentState.LISTENING -> 600
                    AgentState.THINKING -> 1000
                    AgentState.EXECUTING -> 400
                    AgentState.ERROR -> 200
                    else -> 2000
                },
                easing = LinearEasing
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = when (state) {
                    AgentState.THINKING -> 3000
                    AgentState.EXECUTING -> 1500
                    else -> 8000
                },
                easing = LinearEasing
            ),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    val waveOffset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "waveOffset"
    )

    val isActive = state != AgentState.IDLE

    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        if (isActive) {
            for (i in 0..2) {
                val wavePhase = (waveOffset + i * 120f) % 360f
                val waveScale = 1f + (wavePhase / 360f) * 0.5f
                val waveAlpha = 1f - (wavePhase / 360f)

                Canvas(modifier = Modifier.size(size)) {
                    val center = Offset(this.size.width / 2, this.size.height / 2)
                    val radius = (this.size.minDimension / 2) * waveScale * 0.8f

                    drawCircle(
                        color = stateColor.copy(alpha = waveAlpha * 0.3f),
                        radius = radius,
                        center = center,
                        style = Stroke(width = 2.dp.toPx())
                    )
                }
            }
        }

        Canvas(modifier = Modifier.size(size * 0.6f)) {
            val center = Offset(this.size.width / 2, this.size.height / 2)
            val radius = (this.size.minDimension / 2) * (if (isActive) pulseScale else 1f)

            drawCircle(
                color = stateColor,
                radius = radius * 0.7f,
                center = center
            )

            if (state == AgentState.THINKING) {
                val eyeOffset = radius * 0.2f
                val eyeRadius = radius * 0.1f
                drawCircle(
                    color = Color.White,
                    radius = eyeRadius,
                    center = Offset(center.x - eyeOffset, center.y - eyeOffset * 0.5f)
                )
                drawCircle(
                    color = Color.White,
                    radius = eyeRadius,
                    center = Offset(center.x + eyeOffset, center.y - eyeOffset * 0.5f)
                )
            }

            if (state == AgentState.LISTENING) {
                val arcRadius = radius * 0.9f
                for (i in 0..2) {
                    val startAngle = rotation + i * 120f
                    drawArc(
                        color = stateColor.copy(alpha = 0.6f),
                        startAngle = startAngle,
                        sweepAngle = 60f,
                        useCenter = false,
                        topLeft = Offset(center.x - arcRadius, center.y - arcRadius),
                        size = Size(arcRadius * 2, arcRadius * 2),
                        style = Stroke(width = 3.dp.toPx())
                    )
                }
            }

            if (state == AgentState.SUCCESS) {
                val checkSize = radius * 0.3f
                drawLine(
                    color = Color.White,
                    start = Offset(center.x - checkSize, center.y),
                    end = Offset(center.x - checkSize * 0.3f, center.y + checkSize * 0.7f),
                    strokeWidth = 3.dp.toPx()
                )
                drawLine(
                    color = Color.White,
                    start = Offset(center.x - checkSize * 0.3f, center.y + checkSize * 0.7f),
                    end = Offset(center.x + checkSize, center.y - checkSize * 0.5f),
                    strokeWidth = 3.dp.toPx()
                )
            }

            if (state == AgentState.ERROR) {
                val crossSize = radius * 0.25f
                drawLine(
                    color = Color.White,
                    start = Offset(center.x - crossSize, center.y - crossSize),
                    end = Offset(center.x + crossSize, center.y + crossSize),
                    strokeWidth = 3.dp.toPx()
                )
                drawLine(
                    color = Color.White,
                    start = Offset(center.x + crossSize, center.y - crossSize),
                    end = Offset(center.x - crossSize, center.y + crossSize),
                    strokeWidth = 3.dp.toPx()
                )
            }
        }
    }
}

private fun drawLine(
    color: Color,
    start: Offset,
    end: Offset,
    strokeWidth: Float
) {
}
