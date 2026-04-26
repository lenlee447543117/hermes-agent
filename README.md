# AI沪老 - 适老化AI手机助手

基于 [AutoGLM-For-Android](https://github.com/Luokavin/AutoGLM-For-Android) 二次开发的适老化AI手机智能助手应用。专为老年人设计，通过自然语言语音控制手机完成各种任务。

## 核心特点

- 🎙️ **语音优先**：说"给儿子打视频"即可自动操作，无需手动点击
- 👀 **视觉理解**：AutoGLM 截屏分析 + 无障碍服务执行操作
- 🔊 **语音反馈**：每一步操作都有语音播报，老人无需看屏幕
- 🧓 **适老纠错**：语音识别自动纠错（"威信"→"微信"、"打电话给m"→"打电话给妈"）
- 🔄 **双引擎识别**：本地 SpeechRecognizer + 智谱云端 ASR 自动切换
- 🛡️ **权限安全**：Shizuku/无障碍服务执行，无需 Root

## 架构概览

```
用户语音 → VoiceRecognitionEngine → CommandNormalizer(纠错) → VoiceCommandParser(解析)
    → VoiceStateMachine(状态机) → VoiceCommandBridge(执行+反馈) → AutoGLMExecutor → AutoPilotService
```

### 语音中枢状态机

```
IDLE ⇄ LISTENING → PROCESSING → EXECUTING ⇄ SPEAKING
                        ↓
                      IDLE
```

状态机确保 TTS 播报时不会打开麦克风（AEC 防回声），执行中不会重复触发。

## 技术栈

| 组件 | 技术 |
|------|------|
| UI | Jetpack Compose + Material 3 |
| 架构 | MVVM + Hilt DI + StateFlow |
| 语音识别 | Android SpeechRecognizer + 智谱 ASR |
| 语音合成 | Android TTS |
| 屏幕理解 | AutoGLM (智谱 VLM) |
| 操作执行 | Accessibility Service + Shizuku |
| 数据存储 | Room + DataStore |
| 网络 | Retrofit + OkHttp |

## 快速开始

### 环境要求

- Android Studio Hedgehog (2023.1.1) 及以上
- JDK 17
- Android 7.0 (API 24) 及以上设备
- Shizuku 应用（用于获取系统权限）

### 编译运行

```bash
git clone https://github.com/your-org/AI-LaoHu.git
cd AI-LaoHu
./gradlew assembleDebug
```

### 权限配置

1. 安装并激活 [Shizuku](https://github.com/RikkaApps/Shizuku)
2. 授予麦克风权限、悬浮窗权限
3. 开启无障碍服务
4. 配置智谱 API Key（设置页面）

## 项目结构

```
app/src/main/java/com/ailaohu/
├── data/                    # 数据层
│   ├── local/              # 本地数据 (Room, DataStore)
│   ├── remote/             # 远程 API (AutoGLM, ASR)
│   └── repository/         # 仓库模式
├── di/                     # Hilt 依赖注入
├── domain/                 # 领域层 (UseCase)
├── service/                # 核心服务
│   ├── accessibility/      # 无障碍服务 (AutoPilot)
│   ├── audio/              # 音频焦点管理
│   ├── capture/            # 屏幕截图
│   ├── face/               # 人脸检测唤醒
│   ├── tts/                # 语音合成
│   ├── voice/              # 语音识别 + 状态机 + 纠错
│   └── watchdog/           # 看门狗保活
├── ui/                     # Compose UI
│   ├── home/               # 主界面
│   ├── settings/           # 设置
│   └── setup/              # 权限引导
└── util/                   # 工具类
```

## 语音模块核心类

| 类名 | 职责 |
|------|------|
| `VoiceStateMachine` | 语音状态机，管理 IDLE/LISTENING/PROCESSING/SPEAKING/EXECUTING 状态流转 |
| `CommandNormalizer` | 指令纠错器，应用别名映射 + 通讯录模糊匹配 + 拼音解析 |
| `VoiceCommandBridge` | 指令执行桥，对接 AutoGLM 并提供步骤语音反馈 |
| `VoiceRecognitionEngine` | 语音识别引擎，双后端自动切换 + 错误恢复 |
| `WakeUpService` | 语音唤醒前台服务，后台监听唤醒词 |
| `SmartAudioRecorder` | 智能录音器，防 Bixby 抢占 + 空壳文件过滤 |
| `VoiceInteractionManager` | 音频冲突协调器，屏幕录制与语音识别互斥 |

## 许可证

[Apache License 2.0](LICENSE)

## 致谢

- [AutoGLM-For-Android](https://github.com/Luokavin/AutoGLM-For-Android) - 本项目的基础框架
- [Shizuku](https://github.com/RikkaApps/Shizuku) - 系统权限获取方案
- [智谱 AI](https://open.bigmodel.cn/) - AutoGLM 视觉语言模型
