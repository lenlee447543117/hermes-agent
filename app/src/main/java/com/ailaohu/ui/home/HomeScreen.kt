package com.ailaohu.ui.home

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ailaohu.R
import com.ailaohu.ui.theme.AILaoHuTheme
import kotlinx.coroutines.delay

/**
 * 宠物状态枚举
 * 定义AI助手的各种表情和交互状态
 */
enum class PetState {
    IDLE, SHY, LISTENING, THINKING, SPEAKING
}

/**
 * 提示文字列表
 * 用于在闲置状态下轮播显示，引导老人如何使用语音交互
 */
val hints = listOf(
    "试试看着我说\"你好\"",
    "可以说\"给儿子打视频\"",
    "也可以说\"我要打车\"",
    "试试说\"打开微信\"",
    "试试说\"搜索美食\"",
    "试试说\"拍照\"",
    "试试说\"打开蓝牙\"",
    "我会一直在这里陪着你"
)

/**
 * 主屏幕组件
 * 应用的首页，包含宠物交互、语音对话和底部导航
 * 
 * @param uiState 界面状态，包含语音状态、识别文本等
 * @param onMicClick 麦克风点击回调
 * @param onSettingsClick 设置按钮点击回调
 * @param onNotificationsClick 通知按钮点击回调
 * @param onMeClick 我的按钮点击回调
 */
@Composable
fun HomeScreen(
    uiState: HomeUiState,
    onMicClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onNotificationsClick: () -> Unit,
    onMeClick: () -> Unit,
    onToggleContinuousListening: () -> Unit = {}
) {
    var hintIndex by remember { mutableStateOf(0) }

    // 提示文字轮播逻辑，每6秒切换一次
    LaunchedEffect(Unit) {
        while (true) {
            delay(6000)
            if (uiState.voiceState == VoiceState.IDLE) {
                hintIndex = (hintIndex + 1) % hints.size
            }
        }
    }

    AILaoHuTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            // 生动的径向渐变背景
            VividGradient()

            // 顶部装饰条
            TopAccentBar()

            // 顶部应用栏
            TopBar(
                onSettingsClick = onSettingsClick,
                onNotificationsClick = onNotificationsClick,
                uiState = uiState
            )

            // 主内容 - 宠物居中显示
        MainContent(
            uiState = uiState,
            hintText = hints[hintIndex],
            onMicClick = onMicClick
        )

            // 底部浮动导航栏
            FloatingNavBar(
                onMicClick = onMicClick,
                onSettingsClick = onSettingsClick,
                onMeClick = onMeClick,
                uiState = uiState
            )

            // 紧急求助全屏覆盖
            if (uiState.isEmergencyActive) {
                EmergencyOverlay()
            }

            // 加载中遮罩层
            if (uiState.isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.3f)),
                    contentAlignment = Alignment.Center
                ) {
                    androidx.compose.material3.CircularProgressIndicator(
                        color = Color(0xFFFF9F0A),
                        strokeWidth = 4.dp
                    )
                }
            }
        }
    }
}

/**
 * 生动的径向渐变背景
 * 从屏幕中心向外扩散的橙色渐变，营造温暖氛围
 */
@Composable
fun VividGradient() {
    Canvas(
        modifier = Modifier
            .fillMaxSize()
    ) {
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color(0xFFFF9F0A).copy(alpha = 0.15f),
                    Color.Transparent
                ),
                radius = size.minDimension * 0.7f
            ),
            radius = size.minDimension,
            center = Offset(size.width / 2, size.height / 2)
        )
    }
}

/**
 * 顶部装饰条
 * 一条精致的水平渐变线条
 */
@Composable
fun TopAccentBar() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        Color.Transparent,
                        Color(0xFFFF9F0A).copy(alpha = 0.3f),
                        Color.Transparent
                    )
                )
            )
    )
}

/**
 * 顶部应用栏
 * 显示应用标题、状态和通知按钮
 * 
 * @param onSettingsClick 设置按钮点击回调
 * @param onNotificationsClick 通知按钮点击回调
 * @param uiState 界面状态
 */
@Composable
fun TopBar(
    onSettingsClick: () -> Unit,
    onNotificationsClick: () -> Unit,
    uiState: HomeUiState
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 24.dp, start = 24.dp, end = 24.dp)
            .background(
                color = Color.Black.copy(alpha = 0.4f)
            )
            .padding(vertical = 24.dp, horizontal = 24.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // AI头像
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .border(
                        width = 2.dp,
                        color = Color(0xFFFF9F0A),
                        shape = CircleShape
                    )
            ) {
                Image(
                    painter = painterResource(id = R.drawable.normal),
                    contentDescription = "AI Avatar",
                    modifier = Modifier.fillMaxSize()
                )
            }

            // 标题和状态指示器
            Column {
                Text(
                    text = "沪老",
                    color = Color(0xFFFF9F0A),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.05.sp
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    StatusDot(
                        isListening = uiState.voiceState == VoiceState.LISTENING
                    )
                    Text(
                        text = if (uiState.voiceState == VoiceState.LISTENING) "聆听中" else "在线",
                        color = Color(0xFF71717A),
                        fontSize = 12.sp
                    )
                }
            }
        }

        // 通知铃铛按钮
        NotificationBell(
            hasNewNotification = uiState.hasNewNotification,
            onClick = onNotificationsClick
        )
    }
}

/**
 * 状态指示器点
 * 显示AI助手的当前状态，支持脉冲动画
 * 
 * @param isListening 是否正在聆听
 */
@Composable
fun StatusDot(
    isListening: Boolean = false
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isListening) 1.3f else 0.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (isListening) 500 else 2000, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    Box(
        modifier = Modifier
            .size(8.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .background(
                color = if (isListening) Color(0xFFFF9F0A) else Color(0xFF4ADE80),
                shape = CircleShape
            )
    )
}

/**
 * 通知铃铛组件
 * 有新通知时会抖动并显示红色圆点
 * 
 * @param hasNewNotification 是否有新通知
 * @param onClick 点击回调
 */
@Composable
fun NotificationBell(
    hasNewNotification: Boolean,
    onClick: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "bell-shake")
    
    // 铃铛抖动动画
    val shakeRotation by infiniteTransition.animateFloat(
        initialValue = if (hasNewNotification) -8f else 0f,
        targetValue = if (hasNewNotification) 8f else 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(200, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
        ),
        label = "shake"
    )
    
    // 脉冲动画（通知光晕）
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = if (hasNewNotification) 0.8f else 1f,
        targetValue = if (hasNewNotification) 1.4f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )
    
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = if (hasNewNotification) 1f else 0f,
        targetValue = if (hasNewNotification) 0.3f else 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse-alpha"
    )
    
    Box(
        modifier = Modifier
            .size(32.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        // 脉冲光晕（有通知时显示）
        if (hasNewNotification) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .graphicsLayer {
                        scaleX = pulseScale
                        scaleY = pulseScale
                        alpha = pulseAlpha
                    }
                    .background(
                        color = Color(0xFFFF9F0A).copy(alpha = 0.4f),
                        shape = CircleShape
                    )
            )
        }
        
        // 铃铛图标
        Text(
            text = if (hasNewNotification) "🔔" else "🔔",
            fontSize = 22.sp,
            color = if (hasNewNotification) Color(0xFFFF9F0A) else Color(0xFFA1A1AA),
            modifier = Modifier
                .size(32.dp)
                .graphicsLayer {
                    rotationZ = shakeRotation
                }
        )
        
        // 红色通知圆点（有通知时显示）
        if (hasNewNotification) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(
                        color = Color(0xFFEF4444),
                        shape = CircleShape
                    )
                    .border(
                        width = 2.dp,
                        color = Color(0xFF131313),
                        shape = CircleShape
                    )
                    .align(Alignment.TopEnd)
                    .offset(x = 2.dp, y = -2.dp)
            )
        }
    }
}

/**
 * 主内容区域
 * 包含宠物、状态文字、语音显示和提示文字
 * 
 * @param uiState 界面状态
 * @param hintText 当前显示的提示文字
 * @param onMicClick 麦克风点击回调
 */
@Composable
fun MainContent(
    uiState: HomeUiState,
    hintText: String,
    onMicClick: () -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 100.dp, bottom = 180.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // 宠物形象区域
        PetSection(
            uiState = uiState,
            onClick = onMicClick
        )

        // 状态提示文字
        StatusLabel(
            uiState = uiState
        )

        // 语音识别和回复显示区
        VoiceDisplaySection(
            uiState = uiState
        )

        // 引导提示文字
        HintText(
            uiState = uiState,
            hintText = hintText
        )
    }
}

/**
 * 语音和回复显示区域
 * 显示用户说的话和AI的回复
 * 
 * @param uiState 界面状态
 */
@Composable
fun VoiceDisplaySection(
    uiState: HomeUiState
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 用户语音识别文字气泡
        if (uiState.recognizedText.isNotEmpty()) {
            UserSpeechBubble(
                text = uiState.recognizedText,
                isListening = uiState.voiceState == VoiceState.LISTENING
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // AI回复气泡
        if (uiState.responseText.isNotEmpty()) {
            AiResponseBubble(
                text = uiState.responseText,
                isSpeaking = uiState.voiceState == VoiceState.SPEAKING
            )
        }
    }
}

/**
 * 用户语音气泡
 * 显示用户说的话，在屏幕右侧
 * 
 * @param text 要显示的文本
 * @param isListening 是否正在聆听
 */
@Composable
fun UserSpeechBubble(
    text: String,
    isListening: Boolean
) {
    val infiniteTransition = rememberInfiniteTransition(label = "mic-pulse")
    val micScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(500),
            repeatMode = RepeatMode.Reverse
        ),
        label = "mic-scale"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isListening) {
            // 麦克风脉冲动画
            Icon(
                painter = painterResource(id = R.drawable.normal),
                contentDescription = "正在聆听",
                tint = Color(0xFFFF9F0A),
                modifier = Modifier
                    .size(20.dp)
                    .graphicsLayer {
                        scaleX = micScale
                        scaleY = micScale
                    }
                    .padding(end = 8.dp)
            )
        }

        Box(
            modifier = Modifier
                .background(
                    color = Color(0xFFFF9F0A).copy(alpha = 0.2f),
                    shape = RoundedCornerShape(20.dp)
                )
                .border(
                    width = 1.dp,
                    color = Color(0xFFFF9F0A).copy(alpha = 0.3f),
                    shape = RoundedCornerShape(20.dp)
                )
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Text(
                text = text,
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.End
            )
        }
    }
}

/**
 * AI回复气泡
 * 显示AI的回复，在屏幕左侧
 * 
 * @param text 要显示的文本
 * @param isSpeaking 是否正在说话
 */
@Composable
fun AiResponseBubble(
    text: String,
    isSpeaking: Boolean
) {
    val infiniteTransition = rememberInfiniteTransition(label = "ai-pulse")
    val dotOffset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = -8.dp.value,
        animationSpec = infiniteRepeatable(
            animation = tween(600),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot-offset"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.Top
    ) {
        // AI头像
        Image(
            painter = painterResource(id = R.drawable.normal),
            contentDescription = "AI",
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "沪老",
                    color = Color(0xFFFF9F0A),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )

                if (isSpeaking) {
                    Spacer(modifier = Modifier.width(8.dp))
                    // 说话中的三个跳动点动画
                    Row {
                        repeat(3) { index ->
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .graphicsLayer {
                                        translationY = dotOffset + (index * 4).dp.value
                                    }
                                    .background(
                                        color = Color(0xFFFF9F0A),
                                        shape = CircleShape
                                    )
                            )
                            if (index < 2) {
                                Spacer(modifier = Modifier.width(3.dp))
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Box(
                modifier = Modifier
                    .background(
                        color = Color.White.copy(alpha = 0.08f),
                        shape = RoundedCornerShape(20.dp)
                    )
                    .border(
                        width = 1.dp,
                        color = Color.White.copy(alpha = 0.1f),
                        shape = RoundedCornerShape(20.dp)
                    )
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Text(
                    text = text,
                    color = Color(0xFFE2E2E2),
                    fontSize = 18.sp,
                    lineHeight = 26.sp
                )
            }
        }
    }
}

/**
 * 宠物形象区域
 * 显示可爱的宠物动画，带漂浮效果和状态装饰
 * 
 * @param uiState 界面状态
 * @param onClick 点击回调
 */
@Composable
fun PetSection(
    uiState: HomeUiState,
    onClick: () -> Unit = {}
) {
    val infiniteTransition = rememberInfiniteTransition(label = "float")
    val floatOffset by infiniteTransition.animateFloat(
        initialValue = -6.dp.value,
        targetValue = 6.dp.value,
        animationSpec = infiniteRepeatable(
            animation = tween(2500, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
        ),
        label = "float"
    )

    val isListening = uiState.voiceState == VoiceState.LISTENING

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
    ) {
        // 橙色光波动画（聆听时显示）
        if (isListening) {
            OrangeLightWaves()
        }

        // 宠物周围的光晕效果
        Box(
            modifier = Modifier
                .size(if (isListening) 240.dp else 140.dp)
                .offset(y = if (isListening) 40.dp else 80.dp)
                .blur(if (isListening) 24.dp else 16.dp)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color(0xFFFF9F0A).copy(alpha = if (isListening) 0.4f else 0.2f),
                            Color.Transparent
                        )
                    ),
                    shape = CircleShape
                )
        )

        // 宠物容器，带漂浮动画
        Box(
            modifier = Modifier
                .graphicsLayer {
                    translationY = floatOffset
                }
        ) {
            // 宠物图片（开心或正常状态）
            Image(
                painter = painterResource(
                    id = if (uiState.isPetHappy) R.drawable.happy else R.drawable.normal
                ),
                contentDescription = if (uiState.isPetHappy) "开心" else "正常",
                modifier = Modifier.size(if (isListening) 200.dp else 180.dp)
            )

            // 思考点点动画（处理中时显示）
            if (uiState.voiceState == VoiceState.PROCESSING) {
                ThinkingDots(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .offset(y = -24.dp)
                )
            }

            // 执行中旋转指示器
            if (uiState.voiceState == VoiceState.PROCESSING && uiState.isLoading) {
                ExecutingIndicator(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .offset(y = 10.dp)
                )
            }
        }
    }
}

/**
 * 橙色光波动画组件
 * 多层向外扩散的橙色光波，显示AI正在聆听
 */
@Composable
fun OrangeLightWaves() {
    Box(
        contentAlignment = Alignment.Center
    ) {
        // 4层不同大小和动画参数的光波
        repeat(4) { index ->
            OrangeLightWave(index = index)
        }
    }
}

/**
 * 单层橙色光波
 * 
 * @param index 光波索引，用于错开动画
 */
@Composable
fun OrangeLightWave(index: Int) {
    val infiniteTransition = rememberInfiniteTransition(label = "light-wave-$index")
    
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.6f + (index * 0.1f),
        targetValue = 1.8f + (index * 0.2f),
        animationSpec = infiniteRepeatable(
            animation = tween(
                2500 + (index * 200),
                delayMillis = index * 300,
                easing = EaseOut
            ),
            repeatMode = RepeatMode.Restart
        ),
        label = "light-wave-scale-$index"
    )
    
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.5f - (index * 0.08f),
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                2500 + (index * 200),
                delayMillis = index * 300,
                easing = EaseOut
            ),
            repeatMode = RepeatMode.Restart
        ),
        label = "light-wave-alpha-$index"
    )

    Canvas(
        modifier = Modifier
            .size(300.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                this.alpha = alpha
            }
    ) {
        // 外层光晕圆
        drawCircle(
            color = Color(0xFFFF9F0A),
            radius = size.minDimension / 2,
            style = Stroke(width = (3 + index).dp.toPx())
        )
        
        // 内层半透明填充
        drawCircle(
            color = Color(0xFFFF9F0A).copy(alpha = 0.08f),
            radius = size.minDimension / 2
        )
    }
}

/**
 * 思考点点组件
 * 三个依次跳动的小圆点，显示AI正在思考
 * 
 * @param modifier 修饰符
 */
@Composable
fun ThinkingDots(
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        repeat(3) { index ->
            ThinkingDot(index = index)
        }
    }
}

/**
 * 单个思考点
 * 
 * @param index 点的索引，用于错开动画
 */
@Composable
fun ThinkingDot(
    index: Int
) {
    val infiniteTransition = rememberInfiniteTransition(label = "dot")
    val offset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = -10.dp.value,
        animationSpec = infiniteRepeatable(
            animation = tween(
                1200,
                delayMillis = index * 200,
                easing = EaseInOut
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot"
    )

    Box(
        modifier = Modifier
            .size(7.dp)
            .graphicsLayer {
                translationY = offset
            }
            .background(
                color = Color(0xFFFFC688),
                shape = CircleShape
            )
    )
}

/**
 * 声波效果组件
 * 三个依次扩散的圆环，显示AI正在聆听
 * 
 * @param modifier 修饰符
 */
@Composable
fun SoundWaves(
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
    ) {
        repeat(3) { index ->
            SoundWave(index = index)
        }
    }
}

/**
 * 单个声波环
 * 
 * @param index 环的索引，用于错开动画
 */
@Composable
fun SoundWave(
    index: Int
) {
    val infiniteTransition = rememberInfiniteTransition(label = "wave")
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                2000,
                delayMillis = index * 400,
                easing = EaseOut
            ),
            repeatMode = RepeatMode.Restart
        ),
        label = "wave"
    )
    val waveAlpha by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                2000,
                delayMillis = index * 400,
                easing = EaseOut
            ),
            repeatMode = RepeatMode.Restart
        ),
        label = "wave-alpha"
    )

    Canvas(
        modifier = Modifier
            .size(112.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = waveAlpha
            }
    ) {
        drawCircle(
            color = Color(0xFFFF9F0A).copy(alpha = 0.3f),
            radius = size.minDimension / 2,
            style = Stroke(width = 2.dp.toPx())
        )
    }
}

@Composable
fun ExecutingIndicator(
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "exec")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
        ),
        label = "exec-pulse"
    )

    Canvas(
        modifier = modifier
            .size(112.dp)
            .graphicsLayer {
                this.rotationZ = rotation
                alpha = pulseAlpha
            }
    ) {
        val arcLength = 90f
        drawArc(
            color = Color(0xFFFF9F0A),
            startAngle = 0f,
            sweepAngle = arcLength,
            useCenter = false,
            style = Stroke(width = 3.dp.toPx())
        )
        drawArc(
            color = Color(0xFFFF9F0A).copy(alpha = 0.5f),
            startAngle = 180f,
            sweepAngle = arcLength,
            useCenter = false,
            style = Stroke(width = 3.dp.toPx())
        )
    }
}

/**
 * 状态提示文字
 * 根据语音状态显示不同的提示信息
 * 
 * @param uiState 界面状态
 */
@Composable
fun StatusLabel(
    uiState: HomeUiState
) {
    val text = when (uiState.voiceState) {
        VoiceState.IDLE -> "看着我，我就醒了~"
        VoiceState.LISTENING -> "正在聆听…"
        VoiceState.PROCESSING -> if (uiState.isLoading) "正在操作手机…" else "让我想想…"
        VoiceState.SPEAKING -> ""
    }

    Text(
        text = text,
        color = Color.White.copy(alpha = 0.5f),
        fontSize = 16.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 24.dp)
    )
}

/**
 * 引导提示文字
 * 在闲置状态下显示，引导老人使用
 * 
 * @param uiState 界面状态
 * @param hintText 要显示的提示文字
 */
@Composable
fun HintText(
    uiState: HomeUiState,
    hintText: String
) {
    val visibility by animateFloatAsState(
        targetValue = if (uiState.voiceState == VoiceState.IDLE) 1f else 0f,
        animationSpec = tween(500),
        label = "hint-text"
    )

    Text(
        text = hintText,
        color = Color.White.copy(alpha = 0.25f),
        fontSize = 14.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .padding(top = 6.dp)
            .graphicsLayer { alpha = visibility }
    )
}

/**
 * 底部浮动导航栏
 * 包含设置、进入对话和我的三个按钮
 * 
 * @param onMicClick 麦克风点击回调
 * @param onSettingsClick 设置点击回调
 * @param onMeClick 我的点击回调
 * @param uiState 界面状态
 */
@Composable
fun FloatingNavBar(
    onMicClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onMeClick: () -> Unit,
    uiState: HomeUiState
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = 40.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .background(
                    color = Color.White.copy(alpha = 0.05f),
                    shape = RoundedCornerShape(48.dp)
                )
                .border(
                    width = 1.dp,
                    color = Color.White.copy(alpha = 0.1f),
                    shape = RoundedCornerShape(48.dp)
                )
                .padding(horizontal = 32.dp, vertical = 16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 设置按钮
                NavButton(
                    emoji = "⚙️",
                    label = "设置",
                    onClick = onSettingsClick
                )

                // 中间大按钮 - 进入对话
                EnterChatButton(
                    onClick = onMicClick,
                    uiState = uiState
                )

                // 我的按钮
                NavButton(
                    emoji = "👤",
                    label = "我的",
                    onClick = onMeClick
                )
            }
        }
    }
}

/**
 * 普通导航按钮
 * 包含emoji图标和文字标签
 * 
 * @param emoji 表情图标
 * @param label 文字标签
 * @param onClick 点击回调
 */
@Composable
fun NavButton(
    emoji: String,
    label: String,
    onClick: () -> Unit
) {
    var isPressed by remember { mutableStateOf(false) }
    val bgColor = if (isPressed) Color.White.copy(alpha = 0.1f) else Color.Transparent

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .background(bgColor, CircleShape)
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = emoji,
                fontSize = 28.sp,
                modifier = Modifier.size(32.dp)
            )
        }

        Text(
            text = label,
            color = Color(0xFFA1A1AA),
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.02.sp
        )
    }
}

/**
 * 进入对话按钮（中间大按钮）
 * 带脉冲和波纹动画效果
 * 
 * @param onClick 点击回调
 * @param uiState 界面状态
 */
@Composable
fun EnterChatButton(
    onClick: () -> Unit,
    uiState: HomeUiState
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.02f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    val rippleScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = EaseOut),
            repeatMode = RepeatMode.Restart
        ),
        label = "ripple"
    )
    val rippleAlpha by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = EaseOut),
            repeatMode = RepeatMode.Restart
        ),
        label = "ripple-alpha"
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .offset(y = -24.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
    ) {
        Box(
            contentAlignment = Alignment.Center
        ) {
            // 脉冲光晕
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .graphicsLayer {
                        scaleX = pulseScale
                        scaleY = pulseScale
                    }
                    .background(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color(0xFFFF9F0A).copy(alpha = 0.4f),
                                Color.Transparent
                            )
                        ),
                        shape = CircleShape
                    )
                    .blur(8.dp)
            )

            // 向外扩散的波纹
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .graphicsLayer {
                        scaleX = rippleScale
                        scaleY = rippleScale
                        alpha = rippleAlpha
                    }
                    .background(
                        color = Color(0xFFFF9F0A),
                        shape = CircleShape
                    )
            )

            // 主圆形按钮
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .background(
                        color = Color(0xFFFF9F0A),
                        shape = CircleShape
                    )
                    .padding(20.dp),
                contentAlignment = Alignment.Center
            ) {
                // 对话框图标
                Text(
                    text = "💬",
                    fontSize = 40.sp,
                    color = Color.Black,
                    modifier = Modifier.size(40.dp)
                )
            }
        }

        Text(
            text = "进入对话",
            color = Color(0xFFFF9F0A),
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.05.sp,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

/**
 * 紧急求助全屏覆盖
 * 红色闪烁背景，显示求助信息
 */
@Composable
fun EmergencyOverlay() {
    val infiniteTransition = rememberInfiniteTransition(label = "emergency")
    val flashAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(500, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
        ),
        label = "flash"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFDC2626).copy(alpha = flashAlpha)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "🆘 紧急求助中",
                color = Color.White,
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "正在拨打120并通知紧急联系人",
                color = Color.White.copy(alpha = 0.9f),
                fontSize = 18.sp
            )
        }
    }
}