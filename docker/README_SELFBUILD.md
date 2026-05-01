# Chat2DB 自构建 Docker 部署指南

> 本文档介绍如何在服务器上 clone 本 fork 仓库并用 Docker 运行 **包含本仓库所有改动**（DeepSeek 流式兼容、AI 输出清洗、AI 自动选表等）的 Chat2DB。

## 一、适用对象

官方镜像 `chat2db/chat2db:latest` 不包含本 fork 的改动。本指南适用于需要跑 **自构建镜像** 的场景。

## 二、服务器前置要求

- 架构：`x86_64`
- 已安装 **Docker 20+**（`docker buildx` 建议可用以启用缓存加速）
- 网络：能访问 `docker.io`、`registry.npmjs.org`（或国内镜像源）、`repo.maven.apache.org`
- 端口 `10824` 未被占用
- 磁盘：≥ 5GB 可用空间（构建中间层会暂占较多空间，构建完可 `docker builder prune`）

> 注意：**服务器无需安装 JDK/Maven/Node**，一切编译都发生在 Docker 多阶段构建中。

## 三、一键部署

```bash
# 1. Clone 仓库（替换成你的 fork 地址）
git clone https://github.com/benhuachen-tencent/Chat2DB.git
cd Chat2DB

# 2. 构建镜像（首次 8~15 分钟：要拉取 node 依赖和 Maven 依赖；有缓存后 1~2 分钟）
docker build -f docker/Dockerfile.selfbuild -t chat2db-self:latest .

# 3. 运行容器
docker run -d \
  --name chat2db-self \
  -p 10824:10824 \
  -v ~/.chat2db-docker:/root/.chat2db \
  --restart unless-stopped \
  chat2db-self:latest

# 4. 验证启动（容器日志）
docker logs -f chat2db-self
# 看到类似 "Started Application in ... seconds" 即启动成功
```

浏览器访问：`http://<服务器IP>:10824`

## 四、常用运维命令

```bash
# 查看日志（最近 200 行并持续跟踪）
docker logs --tail 200 -f chat2db-self

# 进入容器
docker exec -it chat2db-self sh

# 停止 / 启动 / 重启
docker stop chat2db-self
docker start chat2db-self
docker restart chat2db-self

# 删除容器（数据保留在 ~/.chat2db-docker）
docker rm -f chat2db-self

# 删除镜像
docker rmi chat2db-self:latest
```

## 五、更新流程（拉取最新代码后重建）

```bash
cd Chat2DB
git pull origin main

# 重新构建（会尽量复用未变层）
docker build -f docker/Dockerfile.selfbuild -t chat2db-self:latest .

# 替换容器
docker rm -f chat2db-self
docker run -d --name chat2db-self \
  -p 10824:10824 \
  -v ~/.chat2db-docker:/root/.chat2db \
  --restart unless-stopped \
  chat2db-self:latest
```

## 六、数据持久化说明

- 所有用户配置、本地 H2 数据（连接信息、AI 配置等）都在 **容器内 `/root/.chat2db`**
- 通过 `-v ~/.chat2db-docker:/root/.chat2db` 挂载到宿主机，容器删了数据不丢
- 换到新服务器：把 `~/.chat2db-docker` 目录整个拷过去即可

## 七、网络慢？启用国内镜像

如果在大陆服务器上构建慢，编辑 `docker/Dockerfile.selfbuild`，解开这些注释：

- **前端 stage 1**：
  ```dockerfile
  RUN yarn config set registry https://registry.npmmirror.com
  ```

- **后端 stage 2**：可在 Dockerfile 里显式指定 Maven 镜像，或使用自定义 `settings.xml` 映射到阿里云镜像。

## 八、自定义 JVM 参数

```bash
docker run -d --name chat2db-self \
  -p 10824:10824 \
  -v ~/.chat2db-docker:/root/.chat2db \
  -e JAVA_OPTS="-Xms512m -Xmx2048m -XX:+UseG1GC" \
  chat2db-self:latest
```

## 九、故障排查

| 症状 | 排查思路 |
|---|---|
| `docker build` 报 `yarn install` 失败 | 检查网络；启用 npmmirror；清理 `node_modules` 后重试 |
| 构建 OK，页面打不开 | `docker logs` 看应用是否启动成功；确认 10824 端口未被占用 |
| 页面 404 | 说明前端资源没打进镜像，检查 `static/front/` 目录是否有内容：`docker run --rm chat2db-self ls /app`，或者直接看构建日志 |
| AI 流式响应异常 | 查 `docker logs -f chat2db-self` 是否有 `[V3-BUFFER-CLEAN]` 标记；没有说明镜像里还是旧代码（可能构建缓存问题），`docker build --no-cache -f docker/Dockerfile.selfbuild -t chat2db-self:latest .` |

## 十、完整构建命令参考

```bash
# 清空构建缓存并从头构建（排查问题用）
docker build --no-cache -f docker/Dockerfile.selfbuild -t chat2db-self:latest .

# 查看镜像大小（预期 ~250~350 MB）
docker images chat2db-self
```
