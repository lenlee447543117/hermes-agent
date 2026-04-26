package com.ailaohu.ui.setup

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ailaohu.ui.theme.AILaoHuTheme

data class PermissionItem(
    val type: String,
    val title: String,
    val description: String,
    val icon: String
)

private fun getPermissionItem(type: String): PermissionItem {
    return when (type) {
        "MICROPHONE" -> PermissionItem(
            type = "MICROPHONE",
            title = "麦克风权限",
            description = "语音指令识别，我需要听到您的声音",
            icon = "🎤"
        )
        "ACCESSIBILITY" -> PermissionItem(
            type = "ACCESSIBILITY",
            title = "无障碍权限",
            description = "控制手机操作，这是最重要的权限",
            icon = "🤝"
        )
        "SCREEN_CAPTURE" -> PermissionItem(
            type = "SCREEN_CAPTURE",
            title = "屏幕录制权限",
            description = "分析屏幕内容，让我能看到屏幕",
            icon = "👁️"
        )
        "CALL_PHONE" -> PermissionItem(
            type = "CALL_PHONE",
            title = "电话权限",
            description = "帮您拨打电话",
            icon = "📞"
        )
        "SEND_SMS" -> PermissionItem(
            type = "SEND_SMS",
            title = "短信权限",
            description = "帮您发送短信",
            icon = "💬"
        )
        else -> PermissionItem(
            type = type,
            title = "权限",
            description = "需要开启此权限",
            icon = "🔒"
        )
    }
}

@Composable
fun OptimizedPermissionGuideScreen(
    missingPermissions: List<String>,
    currentPermissionIndex: Int,
    isAuthorizing: Boolean,
    permissionGrantState: Map<String, Boolean>,
    onStartAuthorization: () -> Unit,
    onOpenSettings: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val permissionItems = missingPermissions.map { getPermissionItem(it) }
    
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
                text = if (isAuthorizing) "正在授权..." else "请开启以下权限",
                fontSize = 16.sp,
                color = Color(0xFF8E8E93),
                modifier = Modifier.padding(bottom = 24.dp)
            )

            if (!isAuthorizing) {
                // 权限列表展示
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(permissionItems) { item ->
                        PermissionCard(
                            item = item,
                            isGranted = permissionGrantState[item.type] ?: false,
                            isCurrent = false,
                            onOpenSettings = { onOpenSettings(item.type) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = onStartAuthorization,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFFF9F0A)
                    )
                ) {
                    Text(
                        text = "开始授权",
                        color = Color.Black,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                }
            } else {
                // 授权过程中显示
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    if (currentPermissionIndex < permissionItems.size) {
                        val currentItem = permissionItems[currentPermissionIndex]
                        
                        Text(
                            text = currentItem.icon,
                            fontSize = 80.sp,
                            modifier = Modifier.padding(bottom = 24.dp)
                        )

                        Text(
                            text = currentItem.title,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )

                        Text(
                            text = currentItem.description,
                            fontSize = 16.sp,
                            color = Color(0xFFE5E5EA),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )

                        Spacer(modifier = Modifier.height(48.dp))

                        // 进度指示器
                        Row(
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            permissionItems.forEachIndexed { index, item ->
                                val isGranted = permissionGrantState[item.type] ?: false
                                val isCurrent = index == currentPermissionIndex
                                
                                Box(
                                    modifier = Modifier
                                        .size(if (isCurrent) 12.dp else 8.dp)
                                        .clip(CircleShape)
                                        .background(
                                            when {
                                                isGranted -> Color(0xFF34C759)
                                                isCurrent -> Color(0xFFFF9F0A)
                                                else -> Color(0xFF48484A)
                                            }
                                        )
                                )
                                if (index < permissionItems.size - 1) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                }
                            }
                        }
                    } else {
                        // 授权完成
                        Text(
                            text = "✅",
                            fontSize = 80.sp,
                            modifier = Modifier.padding(bottom = 24.dp)
                        )

                        Text(
                            text = "授权完成！",
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PermissionCard(
    item: PermissionItem,
    isGranted: Boolean,
    isCurrent: Boolean,
    onOpenSettings: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF2C2C2E)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .clickable(enabled = !isGranted) { onOpenSettings() },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF3A3A3C)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = item.icon,
                        fontSize = 24.sp
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column {
                    Text(
                        text = item.title,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = item.description,
                        fontSize = 14.sp,
                        color = Color(0xFF8E8E93)
                    )
                }
            }

            if (isGranted) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF34C759)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "✓",
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }
            } else {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = "开启",
                    tint = Color(0xFF8E8E93)
                )
            }
        }
    }
}
