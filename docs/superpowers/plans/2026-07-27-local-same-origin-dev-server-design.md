# 本地同源开发服务器设计

## 背景

生产环境通过 Nginx 在 `8080` 端口同时提供静态前端和 `/api/` 反向代理，因此浏览器访问 API 时保持同源。本地开发目前常直接双击 `docs/dev-ops/nginx/html/index.html`，页面 Origin 为 `null`，再跨域访问 `http://localhost:8090`。这种运行方式会让认证请求和长连接 SSE 依赖浏览器对 `file://` 跨域请求的处理，连接失败时还会被浏览器报告为 CORS 错误。

## 目标

- 提供与生产 Nginx 拓扑一致的本地入口：`http://localhost:8080`。
- 从 `docs/dev-ops/nginx/html` 提供静态文件。
- 将 `/api/` 请求原样代理到 `http://localhost:8090`。
- 保持 SSE 流式传输，不缓存或聚合响应体。
- 使用仓库和本机已有的 Node.js，不新增 npm 依赖。
- 提供适合 Windows 开发环境的一条命令启动方式。

## 非目标

- 不替换云端 Nginx。
- 不扩大后端允许的 CORS 来源。
- 不修改 TradingAgent、认证协议或 SSE 事件格式。
- 不支持面向公网部署或 TLS 终止。

## 方案

新增一个基于 Node.js 内置 `http` 模块的本地开发服务器。服务器仅监听 loopback 地址和 `8080` 端口：

- 普通路径映射到前端静态目录；根路径返回 `index.html`。
- `/api/` 路径使用 `http.request` 转发到 `127.0.0.1:8090`。
- 请求方法、请求体、查询参数和必要请求头原样转发。
- 响应状态、响应头和响应流直接管道写回浏览器，不读取完整响应体，因此 SSE 分块可以即时到达。
- 过滤逐跳请求头，避免 `connection`、`transfer-encoding` 等连接级头部被错误复用。
- 静态文件解析后必须仍位于静态根目录内，拒绝路径穿越。
- 后端不可用时返回 JSON 格式的 `502`，并在终端记录明确错误。

新增 PowerShell 启动脚本，用于定位 Node.js、启动服务器并输出前端 URL 和后端地址。脚本不自动启动 Spring Boot，也不修改后端进程。

## 数据流

```text
Browser http://localhost:8080
  -> GET /...       -> local dev server -> static files
  -> POST /api/...  -> local dev server -> http://127.0.0.1:8090/api/...
                                      <- streamed SSE response
```

浏览器只与 `localhost:8080` 通信，因此没有跨域预检，也不需要 `file://` 的 `null` Origin。

## 错误处理

- 静态文件不存在：返回 `404`。
- 非法或越界静态路径：返回 `403`。
- 后端连接失败：返回 `502 application/json`，不得伪装成 SSE 成功响应。
- 客户端中途断开：销毁对应上游请求，避免继续占用代理连接。
- 上游中途失败：若响应尚未开始则返回 `502`；若已经开始则结束下游连接并记录错误。

## 测试

使用 Node.js 内置测试运行器，不引入测试依赖：

- 根路径能够返回前端 HTML。
- 静态资源使用正确的内容类型。
- 路径穿越不会读取静态目录外文件。
- API 方法、路径、查询参数、请求体和认证头能够到达模拟后端。
- 后端状态码和响应头能够返回浏览器。
- SSE 的第一个数据块在上游结束之前即可被客户端读取，防止代理缓冲回归。
- 后端不可连接时返回 `502`。

## 使用方式

1. 保持 Spring Boot 监听 `localhost:8090`。
2. 运行本地前端启动脚本。
3. 浏览器打开 `http://localhost:8080`，不再双击 `index.html`。

## 验收标准

- 本地页面和 API 请求均显示为 `http://localhost:8080` 同源地址。
- `auto_agent` 请求不再出现由 `file://` 跨域触发的 CORS 提示。
- TradingAgent 的 SSE 事件能够持续显示直至明确终态。
- 云端 Nginx 文件和行为不受影响。
