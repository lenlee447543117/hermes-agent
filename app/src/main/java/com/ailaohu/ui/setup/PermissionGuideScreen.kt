package com.ailaohu.ui.setup

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ailaohu.R
import com.ailaohu.ui.theme.AILaoHuTheme

data class PermissionStep(
    val title: String,
    val description: String,
    val detail: String,
    val icon: String
)

@Composable
fun PermissionGuideScreen(
    onComplete: () -> Unit,
    modifier: Modifier = Modifier
) {
    var currentStep by remember { mutableStateOf(0) }

    val steps = listOf(
        PermissionStep(
            title = "麦克风权限",
            description = "语音指令识别",
            detail = "我需要通过麦克风听到您的声音。当您对我说\"你好小沪\"时，我会唤醒并听候您的指令。比如您说\"打开微信\"，我就会帮您操作手机。",
            icon = "🎤"
        ),
        PermissionStep(
            title = "无障碍权限",
            description = "控制手机操作",
            detail = "这是最重要的权限！我需要通过无障碍服务来帮您操作手机。当您说\"打开微信\"时，我会自动帮您点击屏幕上的微信图标，就像有人在帮您操作一样。\n\n开启后，我就能：\n• 点击屏幕上的任意按钮\n• 滑动屏幕\n• 输入文字\n• 进入其他App帮您完成任务",
            icon = "🤝"
        ),
        PermissionStep(
            title = "屏幕录制权限",
            description = "分析屏幕内容",
            detail = "我需要看到屏幕上的内容，才能知道要点击哪里。比如您说\"给儿子打电话\"，我会先截屏查看屏幕上有哪些联系人，然后找到\"儿子\"的名字并点击。\n\n这个权限让我的\"眼睛\"正常工作。",
            icon = "👁️"
        ),
        PermissionStep(
            title = "电话和短信权限",
            description = "代拨电话和发短信",
            detail = "如果您说\"给儿子打电话\"或\"发短信给女儿\"，我需要有权限帮您拨打电线或发送短信。没有这些权限，我就无法完成电话相关的操作。",
            icon = "📞"
        )
    )

    AILaoHuTheme {
        Column(
            modifier = modifier
                .fillMaxSize()
                .background(Color(0xFF1C1C1E))
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "让我来帮您操作手机",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier.padding(top = 48.dp, bottom = 8.dp)
            )

            Text(
                text = "请按照以下步骤开启权限",
                fontSize = 16.sp,
                color = Color(0xFF8E8E93),
                modifier = Modifier.padding(bottom = 32.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                steps.forEachIndexed { index, _ ->
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(
                                if (index == currentStep) Color(0xFFFF9F0A)
                                else if (index < currentStep) Color(0xFF34C759)
                                else Color(0xFF48484A)
                            )
                    )
                    if (index < steps.size - 1) {
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                }
            }

            Spacer(modifier = Modifier.height(48.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = steps[currentStep].icon,
                    fontSize = 64.sp,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                Text(
                    text = steps[currentStep].title,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                Text(
                    text = steps[currentStep].description,
                    fontSize = 16.sp,
                    color = Color(0xFFFF9F0A),
                    modifier = Modifier.padding(bottom = 24.dp)
                )

                Text(
                    text = steps[currentStep].detail,
                    fontSize = 16.sp,
                    color = Color(0xFFE5E5EA),
                    textAlign = TextAlign.Center,
                    lineHeight = 26.sp,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                if (currentStep > 0) {
                    OutlinedButton(
                        onClick = { currentStep-- },
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = Color.White
                        )
                    ) {
                        Text("上一步")
                    }
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }

                Button(
                    onClick = {
                        if (currentStep < steps.size - 1) {
                            currentStep++
                        } else {
                            onComplete()
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = if (currentStep > 0) 8.dp else 0.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFFF9F0A)
                    )
                ) {
                    Text(
                        text = if (currentStep < steps.size - 1) "下一步" else "开始授权",
                        color = Color.Black,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            if (currentStep == steps.size - 1) {
                Text(
                    text = "点击\"开始授权\"后，我会一步步引导您开启每个权限",
                    fontSize = 14.sp,
                    color = Color(0xFF8E8E93),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}