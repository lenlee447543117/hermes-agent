# 正确官方基础镜像
FROM ghcr.io/astral-sh/uv:0.11.6-python3.13-trixie AS uv_source
FROM tianon/gosu:1.19-trixie AS gosu_source
FROM debian:13.4

# 环境配置
ENV PYTHONUNBUFFERED=1
ENV PLAYWRIGHT_BROWSERS_PATH=/opt/hermes/.playwright

# 安装系统依赖
RUN apt-get update && \
    apt-get install -y --no-install-recommends \
        build-essential nodejs npm python3 ripgrep ffmpeg gcc python3-dev libffi-dev procps git openssh-client docker-cli tini && \
    rm -rf /var/lib/apt/lists/*

# 创建运行用户
RUN useradd -u 10000 -m -d /opt/data hermes

# 复制工具
COPY --chmod=0755 --from=gosu_source /gosu /usr/local/bin/
COPY --chmod=0755 --from=uv_source /usr/local/bin/uv /usr/local/bin/uvx /usr/local/bin/

WORKDIR /opt/hermes

# 安装前端依赖
COPY package.json package-lock.json ./
COPY web/package.json web/package-lock.json web/

RUN npm install --prefer-offline --no-audit && \
    npx playwright install --with-deps chromium --only-shell && \
    (cd web && npm install --prefer-offline --no-audit) && \
    npm cache clean --force

# 复制代码
COPY --chown=hermes:hermes . .

# 构建前端
RUN cd web && npm run build

# 权限
USER root
RUN chmod -R a+rX /opt/hermes

# Python 环境
RUN uv venv && \
    uv pip install --no-cache-dir -e ".[all]"

# 运行配置
ENV HERMES_WEB_DIST=/opt/hermes/hermes_cli/web_dist
ENV HERMES_HOME=/opt/data
ENV PATH="/opt/data/.local/bin:${PATH}"
VOLUME [ "/opt/data" ]
ENTRYPOINT [ "/usr/bin/tini", "-g", "--", "/opt/hermes/docker/entrypoint.sh" ]
