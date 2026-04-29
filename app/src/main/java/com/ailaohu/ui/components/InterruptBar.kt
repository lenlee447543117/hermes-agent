package com.ailaohu.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ailaohu.service.hermes.AgentState

@Composable
fun InterruptBar(
    agentState: AgentState,
    taskDescription: String = "",
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (agentState == AgentState.IDLE) return

    val stateColor = when (agentState) {
        AgentState.LISTENING -> Color(0xFFFF9800)
        AgentState.THINKING -> Color(0xFF2196F3)
        AgentState.EXECUTING -> Color(0xFFFF5722)
        AgentState.SUCCESS -> Color(0xFF4CAF50)
        AgentState.ERROR -> Color(0xFFF44336)
        else -> Color.Gray
    }

    val stateLabel = when (agentState) {
        AgentState.LISTENING -> "正在听..."
        AgentState.THINKING -> "思考中..."
        AgentState.EXECUTING -> "操作中..."
        AgentState.SUCCESS -> "完成"
        AgentState.ERROR -> "出错了"
        else -> ""
    }

    val showCancel = agentState in listOf(
        AgentState.LISTENING,
        AgentState.THINKING,
        AgentState.EXECUTING
    )

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(stateColor.copy(alpha = 0.15f))
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(stateColor.copy(alpha = if (showCancel) pulseAlpha else 1f))
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = stateLabel,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = stateColor
                    )
                    if (taskDescription.isNotEmpty()) {
                        Text(
                            text = taskDescription,
                            fontSize = 14.sp,
                            color = Color.Gray
                        )
                    }
                }
            }

            if (showCancel) {
                Button(
                    onClick = onCancel,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = stateColor
                    ),
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.height(48.dp)
                ) {
                    Text(
                        text = "停止",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
