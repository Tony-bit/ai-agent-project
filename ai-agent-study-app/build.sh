#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
IMAGE_NAME="${IMAGE_NAME:-system/ai-agent-study-app:1.0}"

cd "${PROJECT_DIR}"

mvn -pl ai-agent-study-app -am clean package -DskipTests

# 普通镜像构建，随系统版本构建 amd/arm
docker build -t "${IMAGE_NAME}" -f ai-agent-study-app/Dockerfile ai-agent-study-app

# 兼容 amd、arm 构建镜像
# docker buildx build --load --platform linux/amd64,linux/arm64 -t "${IMAGE_NAME}" -f ai-agent-study-app/Dockerfile ai-agent-study-app --push
