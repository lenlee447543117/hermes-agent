# 贡献指南

感谢你对 AI沪老 项目的关注！欢迎提交 Issue 和 Pull Request。

## 开发环境设置

1. Fork 本仓库
2. 使用 Android Studio 打开项目
3. 等待 Gradle Sync 完成
4. 连接 Android 设备或模拟器运行

## 代码规范

### Kotlin 代码风格

- 遵循 [Kotlin 编码规范](https://kotlinlang.org/docs/coding-conventions.html)
- 使用 4 空格缩进
- 类和函数使用 KDoc 注释说明职责
- 变量命名使用 camelCase，常量使用 UPPER_SNAKE_CASE

### 提交规范

使用 [Conventional Commits](https://www.conventionalcommits.org/) 格式：

```
feat(voice): 添加语音唤醒服务
fix(asr): 修复智谱ASR超时未降级问题
docs: 更新README架构说明
refactor(state-machine): 优化状态转换线程安全
```

### 分支策略

- `main` - 稳定发布分支
- `develop` - 开发分支
- `feature/*` - 功能分支
- `fix/*` - 修复分支

## Pull Request 流程

1. 从 `develop` 创建功能分支
2. 编写代码并确保编译通过 (`./gradlew assembleDebug`)
3. 如有新功能，添加对应的测试用例
4. 提交 PR 并描述变更内容
5. 等待 Code Review

## 语音模块开发注意事项

- 所有语音交互必须经过 `VoiceStateMachine`，禁止直接调用 TTS 或麦克风
- 新增语音指令需在 `VoiceCommandParser` 中注册
- 新增应用别名需在 `CommandNormalizer.APP_ALIAS_MAP` 中添加
- 修改状态机流转规则需同步更新 `isValidTransition()` 方法

## 问题反馈

提交 Issue 时请包含：

- 设备型号和 Android 版本
- 复现步骤
- 预期行为和实际行为
- Logcat 日志（如有可能）
