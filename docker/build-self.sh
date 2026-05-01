#!/usr/bin/env bash
# Chat2DB 自构建镜像一键脚本
# 用法：从项目根目录运行：
#   ./docker/build-self.sh         # 仅构建镜像
#   ./docker/build-self.sh run     # 构建并启动容器

set -euo pipefail

# 无论从哪里调用本脚本，都切到项目根（脚本所在目录的上一级）
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$PROJECT_ROOT"

IMAGE_NAME="chat2db-self:latest"
CONTAINER_NAME="chat2db-self"
HOST_DATA_DIR="${HOST_DATA_DIR:-$HOME/.chat2db-docker}"
HOST_PORT="${HOST_PORT:-10824}"

echo "==> 项目根目录：$PROJECT_ROOT"
echo "==> 构建镜像：$IMAGE_NAME"
docker build -f docker/Dockerfile.selfbuild -t "$IMAGE_NAME" .

if [[ "${1:-}" == "run" ]]; then
  echo "==> 清理同名旧容器（若存在）"
  docker rm -f "$CONTAINER_NAME" >/dev/null 2>&1 || true

  echo "==> 启动容器"
  echo "    数据目录：$HOST_DATA_DIR → /root/.chat2db"
  echo "    端口：$HOST_PORT → 10824"
  docker run -d \
    --name "$CONTAINER_NAME" \
    -p "${HOST_PORT}:10824" \
    -v "${HOST_DATA_DIR}:/root/.chat2db" \
    --restart unless-stopped \
    "$IMAGE_NAME"

  echo ""
  echo "✅ 启动成功。浏览器访问：http://<服务器IP>:${HOST_PORT}"
  echo "   查看日志：docker logs -f $CONTAINER_NAME"
fi
