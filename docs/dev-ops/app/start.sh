#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
DEPLOY_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
CONTAINER_NAME=ai-agent-study

cd "${DEPLOY_DIR}"

if [ ! -f .env ]; then
  echo "缺少 ${DEPLOY_DIR}/.env，请先复制 .env.example 并填入云端容器地址和密钥。"
  exit 1
fi

echo "应用容器部署开始 ${CONTAINER_NAME}"

docker compose -f docker-compose-app.yml up -d

echo "应用容器部署成功 ${CONTAINER_NAME}"
docker logs -f ${CONTAINER_NAME}
