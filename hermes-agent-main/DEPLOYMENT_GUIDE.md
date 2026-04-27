# AI沪老 2.0 完整部署指南

## 目录
1. [本地 Git 仓库初始化与配置](#1-本地-git-仓库初始化与配置)
2. [后端项目文件的添加、提交与推送到 GitHub](#2-后端项目文件的添加提交与推送到-github)
3. [阿里云服务器 Git 环境与 SSH 密钥配置](#3-阿里云服务器-git-环境与-ssh-密钥配置)
4. [GitHub 与阿里云同步机制设置](#4-github-与阿里云同步机制设置)
5. [验证同步结果](#5-验证同步结果)

---

## 1. 本地 Git 仓库初始化与配置

### 步骤 1.1 检查现有 Git 仓库
```bash
cd /Users/LENLEE/Androidhulao_2.0/hermes-agent-main
git status
```

**如果已有 `.git` 目录（可能来自原始代码）**：
```bash
# 查看 git 历史（如果需要）
git log --oneline -5

# 重置为干净的本地仓库（保留代码）
git reset --mixed HEAD
rm -rf .git  # 完全清理旧仓库（重新开始）
```

### 步骤 1.2 初始化新的 Git 仓库
```bash
git init
```

### 步骤 1.3 配置用户信息
```bash
# 全局配置（如果还没配置过）
git config --global user.name "您的名字"
git config --global user.email "您的邮箱@example.com"

# 项目级配置（可选，不同项目不同作者）
git config user.name "AI沪老开发团队"
git config user.email "dev@hulao.ai"
```

### 步骤 1.4 创建/完善 .gitignore
```bash
# 创建 .gitignore 文件（保护敏感信息）
cat > .gitignore << 'EOF'
# 敏感配置（重要！）
.env
.env.local
.hermes
hermes-home/
*.env

# Python 生成的
__pycache__/
*.pyc
*.pyo
.pytest_cache/
.coverage

# Node/npm
node_modules/

# 日志
*.log
agent.log
errors.log

# IDE
.vscode/
.idea/
*.swp
*.swo
*~
.DS_Store

# Docker
docker-compose.override.yml

# 临时文件
tmp/
temp/
EOF
```

---

## 2. 后端项目文件的添加、提交与推送到 GitHub

### 步骤 2.1 检查要提交的文件
```bash
git status
git add -n .  # 查看哪些文件将要添加（预览模式）
```

### 步骤 2.2 添加文件到暂存区
```bash
# 添加所有文件（除了 .gitignore 中的）
git add .

# 或者逐个添加重要文件
git add pyproject.toml README.md config.yaml docker/SOUL.md
git add gateway/ agent/ hermes_cli/
git add start_hulao.sh docker-compose-hulao.yml DEPLOYMENT_GUIDE.md
```

### 步骤 2.3 第一次提交
```bash
git commit -m "feat: AI沪老 2.0 后端服务完整部署

- 配置智谱 GLM-4-Plus 模型
- 设置 API Server 端点（端口 8642）
- 配置老人助手人设 SOUL
- 添加 Docker 部署配置
- 创建一键启动脚本
- 完善 .env 和 .gitignore
"
```

### 步骤 2.4 创建 GitHub 远程仓库
#### 方式 A：使用 GitHub 网站（推荐）
1. 访问 https://github.com/new
2. 仓库名称：`hermes-agent-hulao` 或 `Androidhulao-2.0`
3. 设置为 **Private**（私有仓库，安全第一）
4. **不要**勾选 Initialize 选项（README、.gitignore、License）
5. 点击 Create Repository

#### 方式 B：使用 GitHub CLI（如果安装了）
```bash
gh repo create hermes-agent-hulao --private --source=. --remote=origin
```

### 步骤 2.5 添加远程仓库地址
```bash
# 使用 HTTPS
git remote add origin https://github.com/您的用户名/hermes-agent-hulao.git

# 或者使用 SSH（需要配置 SSH Key，见下文）
git remote add origin git@github.com:您的用户名/hermes-agent-hulao.git

# 验证远程地址
git remote -v
```

### 步骤 2.6 推送到 GitHub
```bash
# 第一次推送：创建 main 分支并推送所有内容
git branch -M main  # 重命名为 main（如果还是 master）
git push -u origin main

# 后续推送
git push
```

### 常见问题解决
**问题 1：认证失败（HTTPS）**
```bash
# 使用 Personal Access Token（推荐，比密码安全）
# 访问 https://github.com/settings/tokens 生成
# 推送时输入：
# 用户名：您的GitHub用户名
# 密码：生成的 Personal Access Token
```

**问题 2：GitHub 拒绝推送（大文件限制）**
```bash
# 查看大文件
find . -size +100M -type f

# 移除大文件（如果不应该提交）
git reset --mixed HEAD
rm -f 大文件路径
git add .
git commit --amend --no-edit
```

---

## 3. 阿里云服务器 Git 环境与 SSH 密钥配置

### 步骤 3.1 连接到阿里云服务器
```bash
ssh root@您的阿里云服务器IP
# 或使用密钥（推荐）
ssh -i ~/.ssh/阿里云密钥.pem root@服务器IP
```

### 步骤 3.2 安装 Git（如未安装）
```bash
# Ubuntu/Debian
apt update && apt install -y git

# CentOS/RHEL
yum install -y git
```

### 步骤 3.3 配置服务器 Git 用户信息
```bash
git config --global user.name "阿里云服务器部署"
git config --global user.email "server@hulao.ai"
```

### 步骤 3.4 生成服务器端 SSH 密钥
```bash
# 生成新密钥（一路回车使用默认选项）
ssh-keygen -t ed25519 -C "hulao-server-aliyun" -f ~/.ssh/hulao_deploy_key

# 显示公钥（复制内容）
cat ~/.ssh/hulao_deploy_key.pub
```

### 步骤 3.5 添加 SSH 密钥到 GitHub 部署密钥
1. 访问 https://github.com/您的用户名/hermes-agent-hulao/settings/keys
2. 点击 **Add deploy key**
3. Title: `阿里云生产服务器`
4. Key: 粘贴刚才复制的公钥内容
5. 勾选 **Allow write access**（如果需要推送，可选）
6. 点击 **Add key**

### 步骤 3.6 在服务器上配置 SSH 使用该密钥
```bash
# 创建 SSH 配置文件
cat > ~/.ssh/config << 'EOF'
Host github.com-hulao
    HostName github.com
    User git
    IdentityFile ~/.ssh/hulao_deploy_key
    IdentitiesOnly yes
EOF

# 测试连接
ssh -T git@github.com-hulao
```

### 步骤 3.7 在服务器上克隆仓库
```bash
# 创建部署目录
mkdir -p /opt/hulao-backend
cd /opt/hulao-backend

# 克隆仓库（注意使用 GitHub deploy key 的 Host）
git clone git@github.com-hulao:您的用户名/hermes-agent-hulao.git .

# 验证仓库
git status
git log --oneline -5
```

---

## 4. GitHub 与阿里云同步机制设置

### 方案 A：使用 GitHub WebHook（主动推送，推荐）
优点：代码推送后立即同步到服务器

#### 4.1.1 在服务器上安装简单的 WebHook 接收器
```bash
cd /opt/hulao-backend

# 创建 WebHook 接收器脚本
cat > webhook_receiver.py << 'EOF'
#!/usr/bin/env python3
"""
AI沪老 GitHub WebHook 接收器
监听代码推送，自动拉取更新
"""
import os
import subprocess
from flask import Flask, request, jsonify

app = Flask(__name__)

# 配置：您设置的 WebHook 密钥（可选但推荐）
GITHUB_SECRET = os.getenv("GITHUB_WEBHOOK_SECRET", "your_secure_secret_here")
DEPLOY_PATH = "/opt/hulao-backend"

def run_cmd(cmd):
    """运行命令并返回结果"""
    try:
        result = subprocess.run(
            cmd,
            cwd=DEPLOY_PATH,
            capture_output=True,
            text=True,
            check=True,
            timeout=60
        )
        return {
            "success": True,
            "stdout": result.stdout,
            "stderr": result.stderr
        }
    except Exception as e:
        return {
            "success": False,
            "error": str(e)
        }

@app.route("/webhook", methods=["POST"])
def webhook():
    # 简单认证（生产环境请使用 GitHub 的签名验证）
    auth_header = request.headers.get("X-Hub-Signature", "")
    
    # 获取 payload
    payload = request.json
    if not payload:
        return jsonify({"error": "Invalid payload"}), 400
    
    # 仅响应 push 事件
    if request.headers.get("X-GitHub-Event") != "push":
        return jsonify({"status": "ignored", "event": request.headers.get("X-GitHub-Event")}), 200
    
    # 执行拉取更新
    print("📦 收到代码推送，开始拉取更新...")
    git_pull = run_cmd(["git", "pull", "origin", "main"])
    
    if git_pull["success"]:
        print("✅ 代码更新成功！")
        # 重启服务（可选，根据您的部署方式）
        print("🔄 重启服务...")
        # 重启命令示例：
        # restart_result = run_cmd(["systemctl", "restart", "hulao-backend"])
        return jsonify({
            "status": "ok",
            "git_pull": git_pull,
            # "restart": restart_result
        }), 200
    else:
        print("❌ 代码更新失败！")
        return jsonify({"status": "error", "git_pull": git_pull}), 500

@app.route("/health", methods=["GET"])
def health():
    return jsonify({"status": "ok", "service": "hulao-webhook"})

if __name__ == "__main__":
    print("🚀 AI沪老 WebHook 接收器启动中...")
    app.run(host="0.0.0.0", port=9999)
EOF

# 安装依赖
pip3 install flask gunicorn

# 创建 systemd 服务（开机自启动）
cat > /etc/systemd/system/hulao-webhook.service << 'EOF'
[Unit]
Description=AI沪老 GitHub WebHook Receiver
After=network.target

[Service]
Type=simple
User=root
WorkingDirectory=/opt/hulao-backend
ExecStart=/usr/local/bin/gunicorn -w 2 -b 0.0.0.0:9999 webhook_receiver:app
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
EOF

# 启动服务
systemctl daemon-reload
systemctl enable hulao-webhook
systemctl start hulao-webhook
systemctl status hulao-webhook
```

#### 4.1.2 在 GitHub 上配置 WebHook
1. 访问 https://github.com/您的用户名/hermes-agent-hulao/settings/hooks
2. 点击 **Add webhook**
3. Payload URL: `http://您的阿里云IP:9999/webhook`
4. Content type: `application/json`
5. Secret: 您自己设置的密码（可选但推荐）
6. Which events: **Just the push event**
7. Active: 勾选
8. 点击 **Add webhook**

---

### 方案 B：使用定时拉取（更简单，适合非紧急更新）
优点：稳定、不需要开放额外端口，缺点：有延迟

```bash
# 在服务器上创建定时拉取脚本
cat > /opt/hulao-backend/deploy_pull.sh << 'EOF'
#!/bin/bash
# AI沪老 定时同步脚本
cd /opt/hulao-backend

echo "[$(date '+%Y-%m-%d %H:%M:%S')] 开始检查更新..."

# 检查是否有更新
git fetch origin

LOCAL=$(git rev-parse HEAD)
REMOTE=$(git rev-parse origin/main)

if [ "$LOCAL" != "$REMOTE" ]; then
    echo "🚀 发现新代码，开始拉取..."
    git pull origin main
    
    # 可选：重启服务
    # systemctl restart hulao-backend
    
    echo "✅ 更新完成！"
else
    echo "✨ 代码已是最新，无需更新"
fi
EOF

chmod +x /opt/hulao-backend/deploy_pull.sh

# 添加到 crontab（每 5 分钟检查一次）
(crontab -l 2>/dev/null; echo "*/5 * * * * /opt/hulao-backend/deploy_pull.sh >> /var/log/hulao-deploy.log 2>&1") | crontab -

# 查看 crontab 配置
crontab -l

# 查看日志
tail -f /var/log/hulao-deploy.log
```

---

## 5. 验证同步结果

### 5.1 本地测试推送
```bash
# 在本地修改一个文件
echo "测试同步 @ $(date)" >> test_sync.txt
git add test_sync.txt
git commit -m "test: 验证GitHub-阿里云同步"
git push
```

### 5.2 检查服务器端同步状态
```bash
# 方案 A（WebHook）：查看 WebHook 服务日志
journalctl -u hulao-webhook -n 50 -f

# 方案 B（定时拉取）：查看拉取日志
tail -f /var/log/hulao-deploy.log

# 检查服务器端的 git 状态
cd /opt/hulao-backend
git status
git log --oneline -5
```

### 5.3 验证后端服务是否正常运行
```bash
# 检查 API 端口监听
netstat -tlnp | grep 8642

# 健康检查
curl http://localhost:8642/health
```

---

## 6. 部署 .env 文件到服务器（注意安全！）

`.env` 不应该提交到 Git，需要单独部署到服务器：

```bash
# 方式 1：使用 SCP 上传本地 .env
scp /Users/LENLEE/Androidhulao_2.0/hermes-agent-main/.env root@服务器IP:/opt/hulao-backend/.env

# 方式 2：在服务器上直接创建
cat > /opt/hulao-backend/.env << 'EOF'
GLM_API_KEY=your_glm_api_key_here
GLM_BASE_URL=https://api.z.ai/api/paas/v4
API_SERVER_KEY=hulao_2026_secure_api_key_change_in_production
API_SERVER_ENABLED=true
API_SERVER_HOST=0.0.0.0
API_SERVER_PORT=8642
API_SERVER_CORS_ORIGINS=*
GATEWAY_ALLOW_ALL_USERS=true
HERMES_HOME=/opt/hulao-backend/hermes-home
HERMES_REDACT_SECRETS=true
HERMES_TIMEZONE=Asia/Shanghai
EOF

# 设置权限（重要！）
chmod 600 /opt/hulao-backend/.env
```

---

## 附录：阿里云防火墙设置
```bash
# 如果使用 WebHook 方案，需要开放 9999 端口（建议限制 IP 到 GitHub）
# 如果直接访问 API，开放 8642 端口

# Ubuntu ufw
ufw allow from 185.199.108.0/22 to any port 9999  # GitHub CIDR
ufw allow 8642/tcp
ufw status

# 或使用阿里云控制台的安全组规则（推荐）
```
EOF
