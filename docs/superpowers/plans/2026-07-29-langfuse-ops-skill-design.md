# Langfuse 运维查询 Skill 设计

## 1. 背景

当前项目已经把 LLM 调用链写入自部署的 Langfuse v3，但 Codex 客户端仍需要打开 Langfuse Web UI 才能检索 trace、generation、输入输出、token、费用、延迟和异常信息。目标是在个人 Codex 环境中增加一个可复用的 `langfuse-ops` skill，使 Codex 能把自然语言问题转换为 Langfuse Public API 请求，并对返回的调用日志进行分析。

该 skill 安装在 `C:\Users\Denny\.codex\skills\langfuse-ops`，对本机 Codex 的所有项目生效，不作为当前仓库的运行时依赖。

## 2. 目标

1. 通过自然语言查询自部署 Langfuse v3 中的 trace、observation、generation、session 和 score。
2. 支持按时间、名称、用户、会话、tag、环境等条件筛选调用日志。
3. 支持查看完整 LLM 输入输出、模型、token、费用、耗时、错误和 metadata。
4. 支持汇总失败调用、慢调用、高 token 调用和高费用调用。
5. 支持用户明确要求的写操作，例如创建 score，及通过通用请求入口调用其他 Public API。
6. 不安装 Langfuse SDK、数据库驱动或其他第三方 Python 包。
7. 不保存、打印或提交 Langfuse Secret Key。

## 3. 非目标

1. 不直接连接 Langfuse 使用的 PostgreSQL、ClickHouse、Redis 或对象存储。
2. 不替代 Langfuse Web UI 中的图表、Prompt Playground 和人工标注体验。
3. 不在第一版实现独立 MCP Server。
4. 不绕过 Langfuse Public API 访问内部或未公开数据库结构。
5. 不让查询意图隐式触发修改或删除操作。

## 4. 方案选择

采用“Codex skill + Python 标准库 REST 客户端”。

未采用 Langfuse Python SDK，因为本需求以查询和诊断为主，而 SDK 更偏向埋点、采集和应用侧集成；直接使用 Public API 能获得更完整、可控的查询能力。未采用 MCP Server，因为它需要额外的服务配置、工具协议和生命周期管理，超出本次个人 skill 的必要范围。

REST 客户端只使用 Python 标准库，通过 HTTPS 和 Basic Auth 访问 Langfuse。这样无需 `pip install`，也不会引入当前业务项目的依赖。

## 5. 目录与组件

个人 skill 目录包含：

```text
langfuse-ops/
|-- SKILL.md
|-- agents/
|   `-- openai.yaml
`-- scripts/
    |-- langfuse_api.py
    `-- test_langfuse_api.py
```

各组件职责如下：

- `SKILL.md`：定义触发场景、查询工作流、写操作边界、结果整理规则和脚本用法。
- `agents/openai.yaml`：提供 Codex 客户端展示名称、简短说明和默认提示词。
- `scripts/langfuse_api.py`：负责配置读取、认证、HTTP 请求、分页、API 能力探测、结构化输出和错误归一化。
- `scripts/test_langfuse_api.py`：使用本地 HTTP 测试服务验证认证、查询参数、分页、响应解析和错误处理，不访问生产实例。

## 6. 配置与认证

客户端只读取以下环境变量：

- `LANGFUSE_HOST`：自部署实例的 HTTPS 根地址。
- `LANGFUSE_PUBLIC_KEY`：Langfuse Public Key。
- `LANGFUSE_SECRET_KEY`：Langfuse Secret Key。

脚本不接受命令行明文 Secret Key，避免密钥进入终端历史、Codex 对话或进程列表。日志和错误信息必须对认证头及 Secret Key 做脱敏。

`doctor` 命令先检查变量是否存在，再请求健康接口和一个低成本只读端点，以区分网络、TLS、认证、权限和 API 兼容性问题。由于部署使用浮动镜像标签 `langfuse/langfuse:3`，客户端以运行时能力探测和 Public API 响应为准，不绑定具体的 v3 小版本。

## 7. 命令面

第一版提供以下稳定命令：

- `doctor`：验证配置、连通性、认证和基本 API 能力。
- `traces list`：分页筛选 trace。
- `traces get`：按 trace ID 获取完整 trace。
- `observations list`：分页筛选 observation 或 generation。
- `observations get`：按 observation ID 获取详情。
- `sessions list`：分页筛选 session。
- `sessions get`：按 session ID 获取详情和关联 trace。
- `scores list`：查询 score。
- `scores create`：在用户明确要求时创建 score。
- `request`：向 `/api/public/` 下的其他端点发送显式 GET、POST、PATCH、PUT 或 DELETE 请求，以覆盖自部署版本新增但稳定命令尚未封装的能力。

通用 `request` 不接收任意外部 URL，只允许请求已配置 `LANGFUSE_HOST` 的 `/api/public/` 路径，防止携带 Langfuse 凭据访问其他主机。请求体通过 JSON 文件或标准输入提供，避免复杂 JSON 的命令行转义问题。

## 8. 自然语言工作流

Codex 收到类似“查最近一小时失败的 GLM 调用”时，按以下流程执行：

1. 确认查询所需的时间范围和筛选条件；用户未指定时使用保守的近期范围与有限页数。
2. 调用稳定命令获取 traces 或 observations。
3. 仅在摘要无法回答问题时继续获取单条详情，避免无界下载大量 prompt 和 output。
4. 对模型、状态、延迟、token、费用和错误进行聚合。
5. 输出结论、筛选条件、样本数量和相关 trace ID；需要时附完整 JSON 字段。

对于“给这个 trace 打 0 分”之类的明确写入请求，Codex 调用写命令并报告目标 ID、操作类型和 Langfuse 返回结果。对于“看看这个 trace 有没有评分”之类的查询，不得创建或修改 score。

## 9. 输出策略

脚本统一输出 JSON，便于 Codex 可靠解析。默认列表输出保留定位和分析所需字段，并限制单次页数；完整 input、output 和 metadata 只在详情查询或显式完整输出时返回。

Codex 面向用户的结果应优先给出：

1. 查询条件和覆盖时间。
2. 命中数量与主要结论。
3. 错误、延迟、token 或费用的关键分布。
4. 可继续深挖的 trace ID 或 observation ID。

不得把 Secret Key、Authorization 头或包含认证信息的请求对象写入输出。

## 10. 分页与数据量控制

列表命令支持 Langfuse Public API 的页码或游标分页形式，并把分页差异封装在客户端中。默认只读取足够回答当前问题的数据；只有用户明确要求全量导出或统计时才继续翻页。

客户端设置合理的连接和读取超时、最大页数及最大响应体限制。达到限制时返回截断标记和下一页信息，不把部分结果伪装成全量结果。

## 11. 错误处理

错误统一转换为结构化 JSON，并至少区分：

- 配置缺失；
- DNS、连接或 TLS 失败；
- `401` 认证失败；
- `403` 权限不足；
- `404` 资源或端点不存在；
- `429` 限流；
- `5xx` Langfuse 服务异常；
- 非 JSON 或不兼容响应；
- 分页或响应体超过本地限制。

只读请求可对临时网络错误、`429` 和部分 `5xx` 做有限次数指数退避重试。写请求默认不自动重试，除非端点具备明确幂等语义或用户提供幂等标识，避免重复写入。

## 12. 权限与操作边界

用户允许 skill 使用具备完整权限的 Langfuse API Key，但完整权限不改变意图边界：

- 查询、诊断和汇总请求只调用 GET 类接口。
- 创建、更新或删除操作必须来自用户明确的修改意图。
- DELETE 等不可逆操作通过显式 `request` 调用，并在结果中报告实际影响。
- skill 不向 Langfuse 之外的服务转发日志、prompt、output 或凭据。

## 13. 测试与验收

本地自动化测试覆盖：

1. Basic Auth 生成正确且不会出现在错误输出中。
2. 环境变量缺失时返回明确诊断。
3. 查询参数正确编码，中文和特殊字符不会破坏请求。
4. traces、observations、sessions 和 scores 的分页能正确合并或返回续页信息。
5. `401`、`403`、`404`、`429`、`5xx` 和非 JSON 响应被正确分类。
6. 只读请求的重试符合限制，写请求不会被意外重复。
7. 通用请求拒绝外部主机、非 `/api/public/` 路径和无效 JSON。
8. 大响应和最大页数限制会明确标记截断。

真实实例冒烟测试只在本机已经配置三个 Langfuse 环境变量时执行，依次验证 `doctor`、少量 trace 列表和单条详情查询。默认不在真实实例上运行写入或删除测试。

验收标准：Codex 能根据自然语言请求调用该 skill，在自部署 Langfuse v3 中定位 LLM 调用日志，准确返回输入输出、模型、token、费用、延迟和错误；未配置凭据时给出可操作的诊断；查询过程不泄露密钥且不产生写入副作用。

## 14. 后续扩展

当稳定命令无法覆盖频繁使用的 Public API 时，再把对应能力从通用 `request` 提升为具名命令。只有在多台机器共享、需要更强工具模式发现或需要长期服务进程时，才评估升级为 MCP Server。
