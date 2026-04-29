#!/data/data/com.termux/files/usr/bin/bash
# AI沪老 Termux Edge Agent 一键初始化脚本 V4.0
# 对齐解决方案.md 模块六
# 用法：bash setup_termux.sh

set -e

GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

log_info() { echo -e "${GREEN}[INFO]${NC} $1"; }
log_warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }
log_error() { echo -e "${RED}[ERROR]${NC} $1"; }

log_info "========================================="
log_info "  AI沪老 Termux Edge Agent V4.0 一键初始化"
log_info "========================================="

# 1. 更新 Termux 包
log_info "Step 1/10: 更新 Termux 包管理器..."
pkg update -y && pkg upgrade -y

# 2. 安装基础依赖
log_info "Step 2/10: 安装基础依赖..."
pkg install -y python termux-api android-tools termux-services

# 3. 安装 Python 核心依赖
log_info "Step 3/10: 安装 Python 核心依赖..."
pip install --upgrade pip
pip install uiautomator2 websocket-client requests fastapi uvicorn vosk opencv-python-headless

# 4. 安装 PaddleOCR（隐私脱敏）
log_info "Step 4/10: 安装 PaddleOCR（隐私脱敏引擎）..."
pip install paddleocr paddlepaddle 2>/dev/null || log_warn "PaddleOCR 安装失败，将使用 opencv 回退方案"

# 5. 安装 cryptography（记忆加密）
log_info "Step 5/10: 安装 cryptography（记忆加密）..."
pip install cryptography 2>/dev/null || log_warn "cryptography 安装失败，记忆库将不加密"

# 6. 下载 Vosk 离线中文模型
log_info "Step 6/10: 下载 Vosk 离线中文模型（小模型约40MB）..."
VOSK_DIR="$HOME/vosk-model-small-cn-0.22"
if [ ! -d "$VOSK_DIR" ]; then
    log_info "正在下载 vosk-model-small-cn-0.22..."
    cd ~
    wget -q "https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip" -O vosk-model-small-cn-0.22.zip
    unzip -q vosk-model-small-cn-0.22.zip
    rm vosk-model-small-cn-0.22.zip
    log_info "Vosk 小模型已安装"
else
    log_warn "Vosk 模型已存在，跳过下载"
fi

# 7. 配置无线 ADB
log_info "Step 7/10: 配置无线 ADB..."
adb start-server 2>/dev/null || true
adb devices
log_warn "请在手机【开发者选项】中开启【无线调试】，并记下IP和端口"
log_warn "如果本机 ADB 可用，将自动连接 127.0.0.1:5555"
adb connect 127.0.0.1:5555 2>/dev/null || true

# 8. 初始化 uiautomator2 ATX 守护
log_info "Step 8/10: 初始化 uiautomator2 ATX 守护..."
python -c "import uiautomator2 as u2; u2.connect()" 2>/dev/null || log_warn "uiautomator2 初始化失败，请确认 ADB 连接正常"

# 9. 获取代码
log_info "Step 9/10: 获取 HulaoEdgeAgent 代码..."
AGENT_DIR="$HOME/hermes-agent-main"
if [ -d "$AGENT_DIR" ]; then
    cd "$AGENT_DIR"
    git pull 2>/dev/null || log_warn "Git pull 失败，使用本地代码"
else
    log_warn "未找到 $AGENT_DIR，请手动将代码放置到 $AGENT_DIR/"
    mkdir -p "$AGENT_DIR"
fi

# 10. 注册为 Termux 服务（自启动）+ 日志按天切割
log_info "Step 10/10: 注册为 Termux 服务（自启动）..."
SVCS_DIR="$HOME/.termux/svcs"
mkdir -p "$SVCS_DIR"

cat > "$SVCS_DIR/hulao-agent/run" << 'EOF'
#!/data/data/com.termux/files/usr/bin/bash
cd ~/hermes-agent-main
exec uvicorn local_hermes:app --host 127.0.0.1 --port 8000 2>&1
EOF
chmod +x "$SVCS_DIR/hulao-agent/run"

mkdir -p "$SVCS_DIR/hulao-agent/log"
cat > "$SVCS_DIR/hulao-agent/log/run" << 'EOF'
#!/data/data/com.termux/files/usr/bin/bash
exec svlogd -tt ~/hermes-agent-main/logs
EOF
chmod +x "$SVCS_DIR/hulao-agent/log/run"

mkdir -p "$AGENT_DIR/logs"

# 日志按天切割配置
cat > "$AGENT_DIR/logs/config" << 'EOF'
s10485760
n7
EOF

# 创建加密密钥目录
mkdir -p "$HOME/.hermes"

# 创建 opencv 模板目录
mkdir -p "$HOME/.hermes/templates"

# 启用 Termux wake-lock
termux-wake-lock

log_info "========================================="
log_info "  初始化完成！"
log_info "========================================="
echo ""
log_info "启动方式："
echo "  cd ~/hermes-agent-main && uvicorn local_hermes:app --host 127.0.0.1 --port 8000"
echo ""
log_info "服务自启动："
echo "  sv-enable hulao-agent    # 开机自启"
echo "  sv-start hulao-agent     # 立即启动"
echo "  sv-stop hulao-agent      # 停止"
echo ""
log_info "健康检查："
echo "  curl http://127.0.0.1:8000/health"
echo ""
log_info "状态查询："
echo "  curl http://127.0.0.1:8000/api/v1/status"
echo ""
log_info "取消执行："
echo "  curl -X POST http://127.0.0.1:8000/api/v1/cancel"
echo ""
log_warn "重要提醒："
echo "  1. 请在手机设置中将 Termux 加入电池优化白名单"
echo "  2. 请开启 Termux 的自启动权限"
echo "  3. 请在开发者选项中开启无线调试"
echo "  4. 首次使用请配置 AUTOGLM_PHONE_API_KEY 环境变量"
echo "  5. 日志保存在 ~/hermes-agent-main/logs/，按天切割，保留7天"
echo "  6. 加密密钥保存在 ~/.hermes/encryption_key"
