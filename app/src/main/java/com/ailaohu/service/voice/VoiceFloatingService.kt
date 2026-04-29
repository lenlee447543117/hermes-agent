package com.ailaohu.service.voice

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.ailaohu.ui.home.HomeActivity

class VoiceFloatingService : Service(), LifecycleOwner, SavedStateRegistryOwner {

    companion object {
        private const val TAG = "VoiceFloating"
        private const val CHANNEL_ID = "voice_floating_channel"
        private const val NOTIFICATION_ID = 3001
        private const val ACTION_SHOW = "action_show"
        private const val ACTION_HIDE = "action_hide"
        private const val ACTION_TOGGLE_LISTEN = "action_toggle_listen"
        private const val AUTO_IDLE_TIMEOUT_MS = 30_000L

        var isRunning = false
            private set

        var onMicToggle: (() -> Unit)? = null
        var currentPipelineState: VoicePipelineState = VoicePipelineState.IDLE
        var currentPartialText: String = ""
        var onCloseRequested: (() -> Unit)? = null

        private var updateUiCallback: (() -> Unit)? = null

        fun show(context: Context) {
            val intent = Intent(context, VoiceFloatingService::class.java).apply {
                action = ACTION_SHOW
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun hide(context: Context) {
            val intent = Intent(context, VoiceFloatingService::class.java).apply {
                action = ACTION_HIDE
            }
            context.startService(intent)
        }

        fun toggleListen(context: Context) {
            val intent = Intent(context, VoiceFloatingService::class.java).apply {
                action = ACTION_TOGGLE_LISTEN
            }
            context.startService(intent)
        }

        fun updateState(state: VoicePipelineState, partial: String = "") {
            currentPipelineState = state
            currentPartialText = partial
            updateUiCallback?.invoke()
        }
    }

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    private var windowManager: WindowManager? = null
    private var floatingView: ComposeView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var lastInteractionTime = 0L

    // 用于触发UI更新的状态
    private var uiUpdateTrigger by mutableStateOf(0)

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        isRunning = true
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        updateUiCallback = {
            uiUpdateTrigger++
        }
        Log.d(TAG, "悬浮语音服务已创建")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW -> {
                startForeground(NOTIFICATION_ID, createNotification())
                showFloatingButton()
            }
            ACTION_HIDE -> {
                removeFloatingButton()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_TOGGLE_LISTEN -> {
                onMicToggle?.invoke()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        removeFloatingButton()
        isRunning = false
        updateUiCallback = null
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        super.onDestroy()
        Log.d(TAG, "悬浮语音服务已销毁")
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showFloatingButton() {
        if (floatingView != null) return

        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val initialX = screenWidth - 160
        val initialY = displayMetrics.heightPixels / 2

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = initialX
            y = initialY
        }

        val composeView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@VoiceFloatingService)
            setViewTreeSavedStateRegistryOwner(this@VoiceFloatingService)
        }

        composeView.setContent {
            // 读取uiUpdateTrigger来确保当状态变化时重新渲染
            uiUpdateTrigger

            FloatingVoiceButton(
                pipelineState = currentPipelineState,
                partialText = currentPartialText,
                onMicClick = {
                    lastInteractionTime = System.currentTimeMillis()
                    onMicToggle?.invoke()
                },
                onCloseClick = {
                    onCloseRequested?.invoke()
                    hide(this@VoiceFloatingService)
                },
                onDrag = { dx, dy ->
                    layoutParams?.let { params ->
                        params.x += dx.toInt()
                        params.y += dy.toInt()
                        windowManager?.updateViewLayout(floatingView, params)
                    }
                }
            )
        }

        floatingView = composeView
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED

        try {
            windowManager?.addView(composeView, layoutParams)
            Log.d(TAG, "悬浮按钮已显示")
        } catch (e: Exception) {
            Log.e(TAG, "添加悬浮视图失败", e)
        }

        lastInteractionTime = System.currentTimeMillis()
    }

    private fun removeFloatingButton() {
        floatingView?.let {
            try {
                windowManager?.removeView(it)
            } catch (e: Exception) {
                Log.e(TAG, "移除悬浮视图失败", e)
            }
        }
        floatingView = null
        layoutParams = null
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, HomeActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            else PendingIntent.FLAG_UPDATE_CURRENT
        )

        return androidx.core.app.NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("语音助手")
            .setContentText("点击悬浮按钮开始语音对话")
            .setSmallIcon(com.ailaohu.R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_LOW)
            .setCategory(androidx.core.app.NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "语音悬浮窗",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "语音助手悬浮窗服务"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }
}

@Composable
fun FloatingVoiceButton(
    pipelineState: VoicePipelineState,
    partialText: String,
    onMicClick: () -> Unit,
    onCloseClick: () -> Unit,
    onDrag: (Float, Float) -> Unit
) {
    var isListening by remember { mutableStateOf(false) }
    var showPartial by remember { mutableStateOf(false) }

    LaunchedEffect(pipelineState) {
        isListening = pipelineState == VoicePipelineState.LISTENING
        showPartial = pipelineState == VoicePipelineState.LISTENING && partialText.isNotEmpty()
    }

    val infiniteTransition = rememberInfiniteTransition(label = "floating")

    val glowScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isListening) 1.6f else 1.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (isListening) 800 else 2000, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow-scale"
    )

    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = if (isListening) 0.6f else 0.2f,
        targetValue = if (isListening) 0.2f else 0.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (isListening) 800 else 2000, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow-alpha"
    )

    val waveScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 2.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = EaseOut),
            repeatMode = RepeatMode.Restart
        ),
        label = "wave-scale"
    )

    val waveAlpha by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = EaseOut),
            repeatMode = RepeatMode.Restart
        ),
        label = "wave-alpha"
    )

    val buttonColor = when (pipelineState) {
        VoicePipelineState.IDLE -> Color(0xFFFF9F0A)
        VoicePipelineState.LISTENING -> Color(0xFFFF6B00)
        VoicePipelineState.PROCESSING -> Color(0xFFFF9F0A).copy(alpha = 0.7f)
        VoicePipelineState.SPEAKING -> Color(0xFF4ADE80)
        VoicePipelineState.EXECUTING -> Color(0xFFFF9F0A).copy(alpha = 0.5f)
    }

    Box(
        modifier = Modifier
            .width(80.dp)
            .wrapContentHeight()
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onDrag(dragAmount.x, dragAmount.y)
                }
            },
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                contentAlignment = Alignment.Center
            ) {
                if (isListening) {
                    Canvas(
                        modifier = Modifier
                            .size(72.dp)
                            .graphicsLayer {
                                scaleX = waveScale
                                scaleY = waveScale
                                alpha = waveAlpha
                            }
                    ) {
                        drawCircle(
                            color = Color(0xFFFF6B00).copy(alpha = 0.3f),
                            radius = size.minDimension / 2,
                            style = Stroke(width = 2.dp.toPx())
                        )
                    }

                    Canvas(
                        modifier = Modifier
                            .size(72.dp)
                            .graphicsLayer {
                                scaleX = waveScale * 0.7f
                                scaleY = waveScale * 0.7f
                                alpha = waveAlpha * 0.6f
                            }
                    ) {
                        drawCircle(
                            color = Color(0xFFFF6B00).copy(alpha = 0.2f),
                            radius = size.minDimension / 2,
                            style = Stroke(width = 1.5.dp.toPx())
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .graphicsLayer {
                            scaleX = glowScale
                            scaleY = glowScale
                            alpha = glowAlpha
                        }
                        .background(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    buttonColor.copy(alpha = 0.6f),
                                    Color.Transparent
                                )
                            ),
                            shape = CircleShape
                        )
                        .blur(12.dp)
                )

                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .background(buttonColor, CircleShape)
                        .clip(CircleShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            onMicClick()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = when (pipelineState) {
                            VoicePipelineState.LISTENING -> "🎙️"
                            VoicePipelineState.SPEAKING -> "🔊"
                            VoicePipelineState.PROCESSING -> "⏳"
                            VoicePipelineState.EXECUTING -> "🤖"
                            else -> "💬"
                        },
                        fontSize = 24.sp
                    )
                }

                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .align(Alignment.TopEnd)
                        .background(Color(0xFFEF4444), CircleShape)
                        .clip(CircleShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            onCloseClick()
                        }
                        .then(
                            Modifier.pointerInput(Unit) {
                                detectDragGestures(
                                    onDrag = { _, _ -> }
                                )
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "✕",
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            if (isListening && partialText.isNotEmpty()) {
                Text(
                    text = partialText.take(10),
                    color = Color.White,
                    fontSize = 11.sp,
                    maxLines = 1,
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .background(
                            Color.Black.copy(alpha = 0.6f),
                            androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }

            val statusText = when (pipelineState) {
                VoicePipelineState.LISTENING -> "聆听中"
                VoicePipelineState.PROCESSING -> "思考中"
                VoicePipelineState.SPEAKING -> "回答中"
                VoicePipelineState.EXECUTING -> "操作中"
                else -> ""
            }

            if (statusText.isNotEmpty()) {
                Text(
                    text = statusText,
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 10.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}
