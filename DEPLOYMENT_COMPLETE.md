# AI沪老 2.0 完整部署指南

## 概述

本文档提供 AI沪老 2.0 项目的完整部署指南，包括：
- 前后端功能一致性检查
- GitHub 代码同步
- 阿里云服务器部署

## 目录

1. [前后端功能一致性检查](#1-前后端功能一致性检查)
2. [GitHub 代码同步](#2-github-代码同步)
3. [阿里云服务器部署](#3-阿里云服务器部署)
4. [部署验证](#4-部署验证)

---

## 1. 前后端功能一致性检查

### 1.1 项目结构

```
AI沪老 2.0/
├── app/                              # Android 前端
│   └── src/main/java/com/ailaohu/
├── hermes/                           # FastAPI 后端（主要使用）
│   ├── app/api/                      # API 路由
│   ├── app/config/                   # 配置
│   ├── app/services/                 # 业务逻辑
│   └── app/models/                   # 数据模型
└── hermes-agent-main/                # Hermes Agent 框架（备用）
```

### 1.2 前端 API 定义 (app/src/main/java/com/ailaohu/data/remote/api/HermesApiService.kt)

| 端点 | 方法 | 功能 | 状态 |
|------|------|------|------|
| `/api/v1/chat` | POST | 对话接口 | ✅ 已实现 |
| `/api/v1/action` | POST | 操作执行接口 | ✅ 已实现 |
| `/api/v1/habit/{userId}` | GET | 获取习惯画像 | ✅ 已实现 |
| `/api/v1/habit/{userId}` | PUT | 更新习惯画像 | ✅ 已实现 |
| `/api/v1/habit/{userId}/report` | GET | 获取日报 | ✅ 已实现 |
| `/api/v1/habit/{userId}/care` | GET | 主动关怀检查 | ✅ 已实现 |
| `/api/v1/sync-config` | POST | 配置同步 | ✅ 已实现 |
| `/health` | GET | 健康检查 | ✅ 已实现 |

### 1.3 后端 API 实现 (hermes/app/api/routes.py)

所有前端定义的 API 端点在 FastAPI 后端都已完整实现，包括：

- **对话接口**: `/api/v1/chat` - 支持方言模式、历史对话
- **操作接口**: `/api/v1/action` - 记录用户操作
- **习惯画像**: 完整的 CRUD 接口
- **主动关怀**: 检查用户关怀消息
- **配置同步**: 同步联系人和应用白名单等配置
- **WebSocket**: 实时对话支持

### 1.4 前后端 API 一致性检查结果

✅ **完全一致** - 所有 API 端点已正确实现并对齐

---

## 2. GitHub 代码同步

### 2.1 当前仓库状态

✅ 代码已推送到 GitHub: `https://github.com/lenlee447543117/hermes-agent.git`

### 2.2 仓库包含内容

- Android 前端完整代码
- FastAPI 后端 (hermes/)
- Hermes Agent 框架 (hermes-agent-main/)
- 部署配置文件
- Docker Compose 配置
- 一键启动脚本

### 2.3 后续代码同步流程

```bash
# 本地提交变更
git add .
git commit -m "描述变更内容"

# 推送到 GitHub
git push origin main
```

---

## 3. 阿里云服务器部署

### 3.1 服务器准备

1. **服务器规格建议**
   - CPU: 2核或以上
   - 内存: 4GB 或以上
   - 存储: 40GB SSD
   - 操作系统: Ubuntu 20.04/22.04 LTS

2. **安全组配置**
   - 开放 8642 端口 (API 服务)
   - 开放 22 端口 (SSH)
   - 如果使用 WebHook, 开放 9999 端口

### 3.2 服务器环境配置

```bash
# 1. 连接服务器
ssh root@your-server-ip

# 2. 更新系统
apt update && apt upgrade -y

# 3. 安装基础工具
apt install -y git python3 python3-pip docker.io docker-compose

# 4. 配置 Python 环境
pip3 install --upgrade pip
```

### 3.3 部署方式一: Docker Compose (推荐)

```bash
# 1. 克隆代码
cd /opt
git clone https://github.com/lenlee447543117/hermes-agent.git hulao-backend
cd hulao-backend/hermes-agent-main

# 2. 配置环境变量
cp .env.example .env
# 编辑 .env 文件，填入 GLM_API_KEY 等配置

# 3. 启动服务
HERMES_UID=$(id -u) HERMES_GID=$(id -g) docker-compose -f docker-compose-hulao.yml up -d

# 4. 查看状态
docker-compose -f docker-compose-hulao.yml ps
docker-compose -f docker-compose-hulao.yml logs -f
```

### 3.4 部署方式二: 本地运行 + 脚本

```bash
# 使用 hermes-agent-main 中的启动脚本
cd /opt/hulao-backend/hermes-agent-main
chmod +x start_hulao.sh

# 本地模式启动
./start_hulao.sh

# 或 Docker 模式
./start_hulao.sh docker

# 查看状态
./start_hulao.sh status
```

### 3.5 环境变量配置

创建/编辑 `.env` 文件:

```env
# 智谱 AI 配置
GLM_API_KEY=your-glm-api-key-here
GLM_BASE_URL=https://api.z.ai/api/paas/v4

# API 服务配置
API_SERVER_HOST=0.0.0.0
API_SERVER_PORT=8642
API_SERVER_KEY=hulao-2026-secure-key-change-in-production

# 其他配置
HERMES_HOME=/opt/hulao-backend/hermes-home
HERMES_TIMEZONE=Asia/Shanghai
GATEWAY_ALLOW_ALL_USERS=true
```

---

## 4. 部署验证

### 4.1 健康检查

```bash
# 检查 API 服务是否正常
curl http://your-server-ip:8642/health

# 预期响应
{
  "status": "healthy",
  "service": "AI沪老 Hermes",
  "version": "2.0",
  "timestamp": 1234567890
}
```

### 4.2 Android App 配置

在 Android App 中修改后端地址 (app/src/main/java/com/ailaohu/di/NetworkModule.kt):

```kotlin
private const val HERMES_BASE_URL = "http://your-server-ip:8642/"
```

### 4.3 功能验证清单

- [ ] 语音对话功能正常
- [ ] 微信视频通话自动化可用
- [ ] 习惯画像数据正常存储
- [ ] 主动关怀功能正常
- [ ] 配置同步功能正常

---

## 附录 A: 快速启动命令

```bash
# 一键部署 (需要先配置好服务器)
ssh root@your-server-ip "cd /opt && git clone https://github.com/lenlee447543117/hermes-agent.git hulao-backend && cd hulao-backend/hermes-agent-main && ./start_hulao.sh docker"

# 查看部署日志
ssh root@your-server-ip "cd /opt/hulao-backend/hermes-agent-main && docker-compose -f docker-compose-hulao.yml logs -f"
```

---

## 附录 B: 常见问题

### Q: Docker 服务无法启动？
A: 检查 Docker 是否正确安装: `systemctl status docker`

### Q: API 无法访问？
A: 检查防火墙/安全组是否开放了 8642 端口

### Q: 智谱 API 调用失败？
A: 确认 .env 中的 GLM_API_KEY 是否正确配置

---

**部署完成日期**: 2026年
**版本**: AI沪老 2.0
