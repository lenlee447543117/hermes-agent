package com.ailaohu.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val BrandOrange = Color(0xFFFF9F0A)
private val DarkBackground = Color(0xFF0A0A0A)
private val CardBackground = Color(0xFF1C1C1E)
private val CardBorder = Color(0xFF2C2C2E)
private val TextPrimary = Color(0xFFF5F5F5)
private val TextSecondary = Color(0xFF8E8E93)
private val TextTertiary = Color(0xFF636366)
private val DividerColor = Color(0xFF38383A)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    uiState: SettingsUiState,
    onBack: () -> Unit,
    onVoiceFeedbackToggle: (Boolean) -> Unit,
    onVoiceLanguageChange: (String) -> Unit,
    onTtsSpeechRateChange: (Float) -> Unit,
    onTtsPreview: () -> Unit,
    onAiModelChange: (String) -> Unit,
    onCheckVoicePackage: () -> Unit,
    onUseCloudVoice: () -> Unit,
    onContinuousDialogToggle: (Boolean) -> Unit,
    onFeedbackToneToggle: (Boolean) -> Unit,
    onWakeWordToggle: (Boolean) -> Unit,
    onFloatingButtonToggle: (Boolean) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            SettingsTopBar(onBack = onBack)

            Spacer(modifier = Modifier.height(8.dp))

            VoiceControlSection(
                continuousDialogEnabled = uiState.continuousDialogEnabled,
                feedbackToneEnabled = uiState.voiceFeedbackToneEnabled,
                wakeWordEnabled = uiState.wakeWordEnabled,
                floatingButtonEnabled = uiState.floatingButtonEnabled,
                onContinuousDialogToggle = onContinuousDialogToggle,
                onFeedbackToneToggle = onFeedbackToneToggle,
                onWakeWordToggle = onWakeWordToggle,
                onFloatingButtonToggle = onFloatingButtonToggle
            )

            Spacer(modifier = Modifier.height(16.dp))

            VoiceRecognitionSection(
                feedbackEnabled = uiState.voiceFeedbackEnabled,
                language = uiState.voiceLanguage,
                voicePackageStatus = uiState.voicePackageStatus,
                currentBackend = uiState.currentBackend,
                onFeedbackToggle = onVoiceFeedbackToggle,
                onLanguageChange = onVoiceLanguageChange,
                onCheckVoicePackage = onCheckVoicePackage,
                onUseCloudVoice = onUseCloudVoice
            )

            Spacer(modifier = Modifier.height(16.dp))

            ModelSelectionSection(
                currentModel = uiState.aiModel,
                onModelChange = onAiModelChange
            )

            Spacer(modifier = Modifier.height(16.dp))

            TtsSection(
                speechRate = uiState.ttsSpeechRate,
                onSpeechRateChange = onTtsSpeechRateChange,
                onPreview = onTtsPreview
            )

            Spacer(modifier = Modifier.height(40.dp))
        }
    }
}

@Composable
private fun SettingsTopBar(onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 48.dp, start = 20.dp, end = 20.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(CardBackground, CircleShape)
                .border(1.dp, CardBorder, CircleShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onBack
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(text = "←", color = BrandOrange, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(modifier = Modifier.width(16.dp))
        Text(text = "设置", color = TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SectionHeader(title: String, icon: String = "") {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon.isNotEmpty()) {
            Text(text = icon, fontSize = 18.sp, modifier = Modifier.padding(end = 8.dp))
        }
        Text(text = title, color = BrandOrange, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .background(CardBackground, RoundedCornerShape(16.dp))
            .border(1.dp, CardBorder, RoundedCornerShape(16.dp))
            .padding(vertical = 8.dp),
        content = content
    )
}

@Composable
private fun SettingsRow(
    label: String,
    subtitle: String = "",
    showDivider: Boolean = true,
    content: @Composable () -> Unit
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = label, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                if (subtitle.isNotEmpty()) {
                    Text(text = subtitle, color = TextTertiary, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
                }
            }
            content()
        }
        if (showDivider) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .height(0.5.dp)
                    .background(DividerColor)
            )
        }
    }
}

@Composable
private fun OrangeSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        colors = SwitchDefaults.colors(
            checkedTrackColor = BrandOrange,
            checkedThumbColor = Color.White,
            uncheckedTrackColor = Color(0xFF39393D),
            uncheckedThumbColor = Color(0xFF636366)
        ),
        modifier = Modifier.height(24.dp)
    )
}

@Composable
private fun OrangeSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f
) {
    Slider(
        value = value,
        onValueChange = onValueChange,
        valueRange = valueRange,
        colors = SliderDefaults.colors(
            thumbColor = BrandOrange,
            activeTrackColor = BrandOrange,
            inactiveTrackColor = Color(0xFF39393D)
        ),
        modifier = Modifier.width(120.dp).height(24.dp)
    )
}

@Composable
private fun ChipGroup(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (key, label) ->
            val isSelected = key == selected
            Box(
                modifier = Modifier
                    .background(
                        color = if (isSelected) BrandOrange.copy(alpha = 0.2f) else Color.Transparent,
                        shape = RoundedCornerShape(8.dp)
                    )
                    .border(
                        width = 1.dp,
                        color = if (isSelected) BrandOrange.copy(alpha = 0.5f) else CardBorder,
                        shape = RoundedCornerShape(8.dp)
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onSelect(key) }
                    )
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = label,
                    color = if (isSelected) BrandOrange else TextSecondary,
                    fontSize = 13.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                )
            }
        }
    }
}

@Composable
private fun VoiceControlSection(
    continuousDialogEnabled: Boolean,
    feedbackToneEnabled: Boolean,
    wakeWordEnabled: Boolean,
    floatingButtonEnabled: Boolean,
    onContinuousDialogToggle: (Boolean) -> Unit,
    onFeedbackToneToggle: (Boolean) -> Unit,
    onWakeWordToggle: (Boolean) -> Unit,
    onFloatingButtonToggle: (Boolean) -> Unit
) {
    SectionHeader(title = "语音控制", icon = "🎙")

    SettingsCard {
        SettingsRow(
            label = "连续对话",
            subtitle = "说完一句自动继续听下一句",
            showDivider = true
        ) {
            OrangeSwitch(checked = continuousDialogEnabled, onCheckedChange = onContinuousDialogToggle)
        }

        SettingsRow(
            label = "提示音",
            subtitle = "开始和结束时播放提示音",
            showDivider = true
        ) {
            OrangeSwitch(checked = feedbackToneEnabled, onCheckedChange = onFeedbackToneToggle)
        }

        SettingsRow(
            label = "语音唤醒",
            subtitle = "说\"沪沪\"即可唤醒助手",
            showDivider = true
        ) {
            OrangeSwitch(checked = wakeWordEnabled, onCheckedChange = onWakeWordToggle)
        }

        SettingsRow(
            label = "悬浮按钮",
            subtitle = "对话时显示可拖动的语音按钮",
            showDivider = false
        ) {
            OrangeSwitch(checked = floatingButtonEnabled, onCheckedChange = onFloatingButtonToggle)
        }
    }
}

@Composable
private fun VoiceRecognitionSection(
    feedbackEnabled: Boolean,
    language: String,
    voicePackageStatus: String,
    currentBackend: String,
    onFeedbackToggle: (Boolean) -> Unit,
    onLanguageChange: (String) -> Unit,
    onCheckVoicePackage: () -> Unit,
    onUseCloudVoice: () -> Unit
) {
    SectionHeader(title = "语音识别", icon = "🗣")

    SettingsCard {
        SettingsRow(
            label = "识别引擎",
            subtitle = when {
                currentBackend == "cloud" -> "智谱云端识别"
                voicePackageStatus == "available" -> "本地语音包已就绪"
                voicePackageStatus == "checking" -> "检查中..."
                else -> "本地语音包未安装"
            },
            showDivider = true
        ) {
            val statusColor = when {
                currentBackend == "cloud" -> BrandOrange
                voicePackageStatus == "available" -> Color(0xFF4ADE80)
                voicePackageStatus == "checking" -> TextSecondary
                else -> Color(0xFFEF4444)
            }
            val statusText = when {
                currentBackend == "cloud" -> "云端"
                voicePackageStatus == "available" -> "本地"
                voicePackageStatus == "checking" -> "..."
                else -> "缺失"
            }
            Box(
                modifier = Modifier
                    .background(statusColor.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                    .border(1.dp, statusColor.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(text = statusText, color = statusColor, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
        }

        if (voicePackageStatus == "missing" && currentBackend != "cloud") {
            SettingsRow(label = "", subtitle = "", showDivider = true) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(text = "本地语音包未安装，建议使用云端识别", color = Color(0xFFEF4444), fontSize = 12.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .background(CardBorder, RoundedCornerShape(10.dp))
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = onCheckVoicePackage
                                )
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(text = "检查语音包", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .background(BrandOrange, RoundedCornerShape(10.dp))
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = onUseCloudVoice
                                )
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(text = "使用云端", color = Color.Black, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        SettingsRow(
            label = "语音反馈",
            subtitle = "操作完成后语音播报结果",
            showDivider = true
        ) {
            OrangeSwitch(checked = feedbackEnabled, onCheckedChange = onFeedbackToggle)
        }

        SettingsRow(
            label = "识别语言",
            subtitle = "",
            showDivider = false
        ) {
            ChipGroup(
                options = listOf("zh-CN" to "中文", "en-US" to "English"),
                selected = language,
                onSelect = onLanguageChange
            )
        }
    }
}

@Composable
private fun ModelSelectionSection(
    currentModel: String,
    onModelChange: (String) -> Unit
) {
    SectionHeader(title = "AI 模型", icon = "🤖")

    SettingsCard {
        val models = listOf(
            Triple("autoglm-phone", "自动操控", "支持屏幕操作自动化，推荐"),
            Triple("glm-4v-flash", "快速响应", "日常对话，速度快"),
            Triple("glm-4v-plus", "精准理解", "复杂指令理解更准确")
        )

        models.forEachIndexed { index, (model, shortDesc, desc) ->
            val isSelected = model == currentModel
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { onModelChange(model) }
                        ),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = isSelected,
                        onClick = { onModelChange(model) },
                        colors = RadioButtonDefaults.colors(selectedColor = BrandOrange, unselectedColor = TextTertiary),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = shortDesc,
                                color = if (isSelected) BrandOrange else TextPrimary,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        Text(text = desc, color = TextTertiary, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
                    }
                }
                if (index < models.size - 1) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .height(0.5.dp)
                            .background(DividerColor)
                    )
                }
            }
        }
    }
}

@Composable
private fun TtsSection(
    speechRate: Float,
    onSpeechRateChange: (Float) -> Unit,
    onPreview: () -> Unit
) {
    SectionHeader(title = "语音播报", icon = "🔊")

    SettingsCard {
        SettingsRow(
            label = "语速",
            subtitle = "调整播报速度",
            showDivider = true
        ) {
            val rateLabel = when {
                speechRate < 0.7f -> "慢"
                speechRate < 1.1f -> "正常"
                else -> "快"
            }
            Text(text = rateLabel, color = BrandOrange, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(end = 8.dp))
            OrangeSlider(value = speechRate, onValueChange = onSpeechRateChange, valueRange = 0.3f..2.0f)
        }

        SettingsRow(label = "", subtitle = "", showDivider = false) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BrandOrange, RoundedCornerShape(12.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onPreview
                    )
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(text = "▶ 试听语音效果", color = Color.Black, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
