#!/usr/bin/env bash
set -euo pipefail

# Nginx 部署脚本
# 用法: bash setup-nginx.sh

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"
NGINX_HTML_DIR="${PROJECT_DIR}/docs/dev-ops/nginx/html"
NGINX_CONF="${SCRIPT_DIR}/ai-agent-study.conf"
SITES_AVAILABLE="/etc/nginx/sites-available"
SITES_ENABLED="/etc/nginx/sites-enabled"

echo "=== AI Agent Study Nginx 部署 ==="

# 1. 安装 nginx
if ! command -v nginx &> /dev/null; then
    echo "安装 nginx..."
    sudo apt update && sudo apt install -y nginx
fi

# 2. 部署配置文件
echo "部署 nginx 配置..."
sudo cp "${NGINX_CONF}" "${SITES_AVAILABLE}/ai-agent-study.conf"

# 3. 替换配置中的项目路径（支持不同部署目录）
sudo sed -i "s|/home/ubuntu/ai-agent-project|${PROJECT_DIR}|g" "${SITES_AVAILABLE}/ai-agent-study.conf"

# 4. 启用站点
sudo ln -sf "${SITES_AVAILABLE}/ai-agent-study.conf" "${SITES_ENABLED}/ai-agent-study.conf"
sudo rm -f "${SITES_ENABLED}/default"

# 5. 停掉可能存在的 Python SimpleHTTP
if pgrep -f "SimpleHTTP" &> /dev/null; then
    echo "停止 Python SimpleHTTP..."
    pkill -f SimpleHTTP || true
fi

# 6. 测试并重载
echo "测试 nginx 配置..."
sudo nginx -t

echo "重载 nginx..."
sudo systemctl reload nginx

echo "=== 验证 ==="
sleep 1
if curl -sf http://localhost:8080/ > /dev/null 2>&1; then
    echo "前端页面: OK"
else
    echo "前端页面: FAIL"
fi

if curl -sf "http://localhost:8080/api/v1/session/list?userId=test" > /dev/null 2>&1; then
    echo "API 代理: OK"
else
    echo "API 代理: FAIL (请确认 Java 容器在 8091 端口运行)"
fi

echo "=== 部署完成 ==="
echo "访问地址: http://$(hostname -I | awk '{print $1}'):8080/"
