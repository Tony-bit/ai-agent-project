# 本地前端运行

本地开发不要直接双击 `html/index.html`。先启动监听 `8090` 的 Spring Boot 后端，然后在仓库根目录运行：

```powershell
powershell -ExecutionPolicy Bypass -File .\docs\dev-ops\nginx\start-local-ui.ps1
```

浏览器打开 `http://localhost:8080`。本地服务从 `html` 目录提供前端文件，并将同源的 `/api/` 请求流式代理到 `http://127.0.0.1:8090`。

可选参数：

```powershell
.\docs\dev-ops\nginx\start-local-ui.ps1 -Port 8088 -BackendUrl http://127.0.0.1:8091
```

云端部署继续使用 `ai-agent-study.conf`，不需要运行本地开发服务器。
