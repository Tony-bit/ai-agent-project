# Langfuse 运维查询 Skill 实现计划

> **致智能体工作者：** 必需子技能：使用 superpowers:executing-plans 来按任务逐步实现本计划。步骤使用复选框（`- [ ]`）语法进行跟踪。

**目标：** 在个人 Codex 技能目录中创建一个无需第三方依赖的 `langfuse-ops` skill，通过 Langfuse v3 Public API 查询和操作 LLM 调用日志。

**架构：** `SKILL.md` 把自然语言请求路由到一个 Python 标准库 REST 客户端。客户端集中处理环境变量、Basic Auth、同源路径限制、分页、重试、响应限制和结构化错误，具名命令覆盖常用日志资源，通用命令覆盖其余 Public API。

**技术栈：** Codex Skills、Python 3 标准库、Langfuse v3 Public API、`unittest`

---

## 文件结构

- 创建：`C:\Users\Denny\.codex\skills\langfuse-ops\SKILL.md`，定义触发条件和操作流程。
- 创建：`C:\Users\Denny\.codex\skills\langfuse-ops\agents\openai.yaml`，定义客户端界面元数据。
- 创建：`C:\Users\Denny\.codex\skills\langfuse-ops\scripts\langfuse_api.py`，实现 REST 客户端和命令行接口。
- 创建：`C:\Users\Denny\.codex\skills\langfuse-ops\scripts\test_langfuse_api.py`，通过本地 HTTP 服务测试协议行为。

### 任务 1：初始化个人 Skill

| 任务 | status |
|------|------|
| 任务 1：初始化个人 Skill | pass |

**文件：**
- 创建：`C:\Users\Denny\.codex\skills\langfuse-ops\SKILL.md`
- 创建：`C:\Users\Denny\.codex\skills\langfuse-ops\agents\openai.yaml`
- 创建：`C:\Users\Denny\.codex\skills\langfuse-ops\scripts\`

- [ ] **步骤 1：运行标准初始化器**

```powershell
python C:\Users\Denny\.codex\skills\.system\skill-creator\scripts\init_skill.py langfuse-ops `
  --path C:\Users\Denny\.codex\skills `
  --resources scripts `
  --interface 'display_name=Langfuse Ops' `
  --interface 'short_description=查询并诊断 Langfuse LLM 调用日志' `
  --interface 'default_prompt=Use $langfuse-ops to inspect recent failed LLM calls in Langfuse.'
```

预期：创建 skill 目录、`SKILL.md`、`agents/openai.yaml` 和 `scripts` 目录。

- [ ] **步骤 2：检查生成目录**

```powershell
Get-ChildItem -Recurse C:\Users\Denny\.codex\skills\langfuse-ops
```

预期：只包含计划内目录和生成文件，不包含示例占位资源。

### 任务 2：以测试驱动实现 REST 客户端

| 任务 | status |
|------|------|
| 任务 2：以测试驱动实现 REST 客户端 | pass |

**文件：**
- 创建：`C:\Users\Denny\.codex\skills\langfuse-ops\scripts\test_langfuse_api.py`
- 创建：`C:\Users\Denny\.codex\skills\langfuse-ops\scripts\langfuse_api.py`

- [ ] **步骤 1：编写失败测试**

使用 `ThreadingHTTPServer` 建立本地测试服务器，定义以下测试类和断言：

```python
class LangfuseApiTests(unittest.TestCase):
    def test_missing_configuration(self): ...
    def test_basic_auth_and_query_encoding(self): ...
    def test_get_trace_uses_public_path(self): ...
    def test_pagination_stops_at_max_pages(self): ...
    def test_http_errors_are_structured_and_redacted(self): ...
    def test_raw_request_rejects_external_and_private_paths(self): ...
    def test_write_request_is_not_retried(self): ...
    def test_response_size_limit_is_reported(self): ...
```

测试服务器记录 method、path、query、Authorization 和 body，按测试场景返回 JSON、错误状态或大响应。

- [ ] **步骤 2：运行测试验证失败**

```powershell
Push-Location C:\Users\Denny\.codex\skills\langfuse-ops\scripts
python -m unittest test_langfuse_api.py -v
Pop-Location
```

预期：因 `langfuse_api` 尚未实现而失败。

- [ ] **步骤 3：实现配置、请求和错误类型**

在 `langfuse_api.py` 中实现以下稳定接口：

```python
@dataclass(frozen=True)
class Config:
    host: str
    public_key: str
    secret_key: str
    timeout: float = 20.0
    max_response_bytes: int = 10 * 1024 * 1024

class LangfuseError(Exception):
    def as_dict(self) -> dict[str, object]: ...

class LangfuseClient:
    def request(self, method: str, path: str, *, query=None, body=None) -> object: ...
    def collect_pages(self, path: str, *, query=None, max_pages=1) -> dict[str, object]: ...
```

`Config.from_env()` 只读取 `LANGFUSE_HOST`、`LANGFUSE_PUBLIC_KEY` 和 `LANGFUSE_SECRET_KEY`。`request()` 使用 Basic Auth，只允许相对 `/api/public/` 路径，GET 对临时错误最多重试两次，写请求不自动重试。

- [ ] **步骤 4：实现命令行命令**

用 `argparse` 实现：

```text
doctor
traces list|get
observations list|get
sessions list|get
scores list|create
request METHOD PATH
```

列表命令接受重复的 `--param key=value` 和 `--max-pages`；详情命令接受资源 ID；写请求体通过 `--data-file` 或 `--data-stdin` 提供。所有成功与失败输出都使用 JSON，失败同时返回非零退出码。

- [ ] **步骤 5：运行测试验证通过**

```powershell
Push-Location C:\Users\Denny\.codex\skills\langfuse-ops\scripts
python -m unittest test_langfuse_api.py -v
Pop-Location
```

预期：所有认证、路径、分页、重试、限制和错误脱敏测试通过。

### 任务 3：编写 Skill 工作流与界面元数据

| 任务 | status |
|------|------|
| 任务 3：编写 Skill 工作流与界面元数据 | pass |

**文件：**
- 修改：`C:\Users\Denny\.codex\skills\langfuse-ops\SKILL.md`
- 修改：`C:\Users\Denny\.codex\skills\langfuse-ops\agents\openai.yaml`

- [ ] **步骤 1：替换 SKILL.md 模板**

使用以下 frontmatter，并在正文中写明命令选择、筛选策略、详情按需加载、写操作意图边界、敏感数据处理和结果呈现：

```yaml
---
name: langfuse-ops
description: Query, inspect, diagnose, and explicitly modify self-hosted Langfuse v3 data through its Public API. Use when Codex needs to find LLM traces or generations, inspect prompts and outputs, analyze latency, tokens, cost or errors, work with sessions and scores, or perform a user-requested Langfuse API operation.
---
```

正文要求 Codex 优先使用具名只读命令；未指定时间时采用有限近期范围；完整 prompt/output 只按需读取；只有明确修改意图才允许 POST、PATCH、PUT 或 DELETE。

- [ ] **步骤 2：校验界面元数据**

```powershell
Get-Content -Raw C:\Users\Denny\.codex\skills\langfuse-ops\agents\openai.yaml
```

预期：`display_name`、25 至 64 字符的 `short_description` 和显式提及 `$langfuse-ops` 的 `default_prompt` 与 `SKILL.md` 一致。

### 任务 4：验证与真实实例冒烟检查

| 任务 | status |
|------|------|
| 任务 4：验证与真实实例冒烟检查 | pass |

**文件：**
- 验证：`C:\Users\Denny\.codex\skills\langfuse-ops\`

- [ ] **步骤 1：运行技能结构验证**

```powershell
python C:\Users\Denny\.codex\skills\.system\skill-creator\scripts\quick_validate.py C:\Users\Denny\.codex\skills\langfuse-ops
```

预期：输出 `Skill is valid!`。

- [ ] **步骤 2：运行语法和自动化测试**

```powershell
python -m py_compile C:\Users\Denny\.codex\skills\langfuse-ops\scripts\langfuse_api.py
Push-Location C:\Users\Denny\.codex\skills\langfuse-ops\scripts
python -m unittest test_langfuse_api.py -v
Pop-Location
```

预期：语法检查无输出，所有测试通过。

- [ ] **步骤 3：检测本机凭据状态**

```powershell
@('LANGFUSE_HOST','LANGFUSE_PUBLIC_KEY','LANGFUSE_SECRET_KEY') | ForEach-Object {
  [pscustomobject]@{ Name = $_; Configured = [bool][Environment]::GetEnvironmentVariable($_) }
}
```

预期：只报告是否配置，不显示变量值。

- [ ] **步骤 4：在凭据齐全时运行只读冒烟测试**

```powershell
python C:\Users\Denny\.codex\skills\langfuse-ops\scripts\langfuse_api.py doctor
python C:\Users\Denny\.codex\skills\langfuse-ops\scripts\langfuse_api.py traces list --param limit=1 --max-pages 1
```

预期：返回结构化 JSON，认证信息不出现在输出中；凭据未配置时跳过真实调用并报告配置方法。

- [ ] **步骤 5：检查个人 skill 最终文件**

```powershell
Get-ChildItem -Recurse C:\Users\Denny\.codex\skills\langfuse-ops | Select-Object FullName,Length
```

预期：仅包含 `SKILL.md`、`agents/openai.yaml`、客户端脚本和测试脚本，以及 Python 测试可能生成的 `__pycache__`。
