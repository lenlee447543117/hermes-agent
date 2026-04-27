#!/bin/bash
# =============================================================================
# AI沪老 2.0 - Hermes 后端服务一键启动脚本
# =============================================================================
# 使用方法:
#   ./start_hulao.sh          # 本地模式启动
#   ./start_hulao.sh docker   # Docker 模式启动
#   ./start_hulao.sh stop     # 停止服务
#   ./start_hulao.sh status   # 查看服务状态
# =============================================================================

set -e

# 颜色定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

# 项目路径
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
HERMES_DIR="$SCRIPT_DIR"
HERMES_HOME="${HERMES_HOME:-$HOME/.hermes}"

# 打印带颜色的信息
info()  { echo -e "${BLUE}[信息]${NC} $1"; }
ok()    { echo -e "${GREEN}[成功]${NC} $1"; }
warn()  { echo -e "${YELLOW}[警告]${NC} $1"; }
error() { echo -e "${RED}[错误]${NC} $1"; }

# =============================================================================
# 环境检查
# =============================================================================
check_environment() {
    info "检查运行环境..."

    # 检查 Python 版本
    if command -v python3 &>/dev/null; then
        PYTHON_VERSION=$(python3 --version 2>&1 | awk '{print $2}')
        info "Python 版本: $PYTHON_VERSION"
    else
        error "未找到 Python3，请先安装 Python 3.11+"
        exit 1
    fi

    # 检查 .env 文件
    if [ ! -f "$HERMES_DIR/.env" ]; then
        warn ".env 文件不存在，正在从模板创建..."
        cp "$HERMES_DIR/.env.example" "$HERMES_DIR/.env"
        warn "请编辑 .env 文件，填入您的 API 密钥"
    fi

    # 检查 GLM_API_KEY
    if grep -q "GLM_API_KEY=your" "$HERMES_DIR/.env" 2>/dev/null; then
        warn "GLM_API_KEY 尚未配置，请在 .env 文件中填入智谱 AI 密钥"
    fi

    # 创建必要目录
    mkdir -p "$HERMES_HOME"/{cron,sessions,logs,hooks,memories,skills,skins,plans,workspace,home,cache}

    ok "环境检查完成"
}

# =============================================================================
# 安装依赖
# =============================================================================
install_dependencies() {
    info "安装 Python 依赖..."

    cd "$HERMES_DIR"

    # 检查是否使用 uv
    if command -v uv &>/dev/null; then
        info "使用 uv 安装依赖（更快）..."
        uv venv 2>/dev/null || true
        uv pip install -e ".[web,messaging,cron,mcp,cli]" 2>&1 | tail -5
    elif command -v pip3 &>/dev/null; then
        info "使用 pip 安装依赖..."
        pip3 install -e ".[web,messaging,cron,mcp,cli]" 2>&1 | tail -5
    else
        error "未找到 pip3 或 uv，请先安装包管理器"
        exit 1
    fi

    ok "依赖安装完成"
}

# =============================================================================
# 本地模式启动
# =============================================================================
start_local() {
    info "以本地模式启动 AI沪老 后端服务..."

    cd "$HERMES_DIR"

    # 加载环境变量
    if [ -f "$HERMES_DIR/.env" ]; then
        export $(grep -v '^#' "$HERMES_DIR/.env" | grep -v '^$' | xargs)
    fi

    # 设置必要的环境变量
    export HERMES_HOME="$HERMES_HOME"
    export HERMES_TIMEZONE="${HERMES_TIMEZONE:-Asia/Shanghai}"

    # 确保 API 服务器配置
    export API_SERVER_HOST="${API_SERVER_HOST:-0.0.0.0}"
    export API_SERVER_PORT="${API_SERVER_PORT:-8642}"
    export API_SERVER_KEY="${API_SERVER_KEY:-hulao_2026_secure_api_key_change_in_production}"

    # 激活虚拟环境（如果存在）
    if [ -d "$HERMES_DIR/.venv" ]; then
        source "$HERMES_DIR/.venv/bin/activate"
    fi

    # 复制 SOUL.md 到 HERMES_HOME
    if [ ! -f "$HERMES_HOME/SOUL.md" ]; then
        cp "$HERMES_DIR/docker/SOUL.md" "$HERMES_HOME/SOUL.md"
    fi

    # 复制 config.yaml 到 HERMES_HOME
    if [ ! -f "$HERMES_HOME/config.yaml" ]; then
        cp "$HERMES_DIR/config.yaml" "$HERMES_HOME/config.yaml"
    fi

    echo ""
    echo "╔══════════════════════════════════════════════╗"
    echo "║       🧓 AI沪老 2.0 后端服务启动中...        ║"
    echo "╠══════════════════════════════════════════════╣"
    echo "║  API 地址: http://${API_SERVER_HOST}:${API_SERVER_PORT}          ║"
    echo "║  健康检查: http://${API_SERVER_HOST}:${API_SERVER_PORT}/health   ║"
    echo "║  模型接口: http://${API_SERVER_HOST}:${API_SERVER_PORT}/v1       ║"
    echo "╠══════════════════════════════════════════════╣"
    echo "║  按 Ctrl+C 停止服务                         ║"
    echo "╚══════════════════════════════════════════════╝"
    echo ""

    # 启动 Gateway（包含 API Server）
    python3 -m gateway.run
}

# =============================================================================
# Docker 模式启动
# =============================================================================
start_docker() {
    info "以 Docker 模式启动 AI沪老 后端服务..."

    cd "$HERMES_DIR"

    # 检查 Docker
    if ! command -v docker &>/dev/null; then
        error "未找到 Docker，请先安装 Docker"
        exit 1
    fi

    # 加载环境变量
    if [ -f "$HERMES_DIR/.env" ]; then
        export $(grep -v '^#' "$HERMES_DIR/.env" | grep -v '^$' | xargs)
    fi

    export HERMES_UID=$(id -u)
    export HERMES_GID=$(id -g)

    echo ""
    echo "╔══════════════════════════════════════════════╗"
    echo "║     🧓 AI沪老 2.0 Docker 服务启动中...       ║"
    echo "╠══════════════════════════════════════════════╣"
    echo "║  API 地址: http://0.0.0.0:8642              ║"
    echo "║  健康检查: http://0.0.0.0:8642/health        ║"
    echo "╚══════════════════════════════════════════════╝"
    echo ""

    docker compose -f docker-compose-hulao.yml up -d --build

    ok "Docker 服务已启动"
    info "查看日志: docker compose -f docker-compose-hulao.yml logs -f"
}

# =============================================================================
# 停止服务
# =============================================================================
stop_service() {
    info "停止 AI沪老 后端服务..."

    cd "$HERMES_DIR"

    # 尝试停止 Docker 服务
    if [ -f "docker-compose-hulao.yml" ]; then
        docker compose -f docker-compose-hulao.yml down 2>/dev/null && ok "Docker 服务已停止"
    fi

    # 尝试停止本地进程
    pkill -f "gateway.run" 2>/dev/null && ok "本地服务已停止"

    ok "所有服务已停止"
}

# =============================================================================
# 查看状态
# =============================================================================
show_status() {
    echo ""
    echo "╔══════════════════════════════════════════════╗"
    echo "║          🧓 AI沪老 2.0 服务状态              ║"
    echo "╠══════════════════════════════════════════════╣"

    # 检查 API 服务器
    if curl -s http://localhost:8642/health >/dev/null 2>&1; then
        echo -e "║  API 服务器:  ${GREEN}运行中${NC}"
    else
        echo -e "║  API 服务器:  ${RED}未运行${NC}"
    fi

    # 检查 Docker
    if command -v docker &>/dev/null; then
        if docker ps | grep -q hulao; then
            echo -e "║  Docker 容器: ${GREEN}运行中${NC}"
        else
            echo -e "║  Docker 容器: ${YELLOW}未启动${NC}"
        fi
    fi

    # 检查配置
    if [ -f "$HERMES_DIR/.env" ]; then
        echo -e "║  配置文件:    ${GREEN}已配置${NC}"
    else
        echo -e "║  配置文件:    ${RED}未找到${NC}"
    fi

    echo "╚══════════════════════════════════════════════╝"
    echo ""
}

# =============================================================================
# 主入口
# =============================================================================
case "${1:-local}" in
    local)
        check_environment
        install_dependencies
        start_local
        ;;
    docker)
        check_environment
        start_docker
        ;;
    stop)
        stop_service
        ;;
    status)
        show_status
        ;;
    install)
        check_environment
        install_dependencies
        ok "依赖安装完成，可以使用 ./start_hulao.sh 启动服务"
        ;;
    *)
        echo "AI沪老 2.0 - Hermes 后端服务管理"
        echo ""
        echo "使用方法:"
        echo "  $0 local    本地模式启动（默认）"
        echo "  $0 docker   Docker 模式启动"
        echo "  $0 stop     停止服务"
        echo "  $0 status   查看服务状态"
        echo "  $0 install  仅安装依赖"
        ;;
esac
