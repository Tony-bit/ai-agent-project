# 测试：AI Agent 前端产品化打磨端到端回归

## 1. 测试背景

### 1.1 对应设计与计划

- 总体设计：`docs/superpowers/plans/2026-06-21-frontend-product-polish-design.md`
- 第一批：`docs/superpowers/plans/2026-06-21-frontend-polish-batch-1-stream-security.md`
- 第二批：`docs/superpowers/plans/2026-06-21-frontend-polish-batch-2-lifecycle-config.md`
- 第三批：`docs/superpowers/plans/2026-06-21-frontend-polish-batch-3-ux-regression.md`

### 1.2 测试目标

- 验证通用 Agent 与股票分析的 SSE 流在分包、断流、异常响应和重复终止事件下仍能正确收口。
- 验证实时消息、历史消息、用户输入和协议元数据均经过安全渲染，不产生 XSS。
- 验证请求从 `idle` 到 `running/completed/failed/cancelled` 的状态转换一致、可取消且幂等。
- 验证 API Base URL、`userId` 的解析、校验、持久化和降级符合设计优先级。
- 验证桌面端、窄屏、长内容、滚动控制、Toast 和可访问状态具备产品级可用性。
- 验证改动未破坏意图路由、多任务、多 Agent 股票分析、会话历史和记忆同步等既有能力。

### 1.3 测试范围

- 页面：`docs/dev-ops/nginx/html/index.html`
- 前端核心工具：`docs/dev-ops/nginx/html/js/agent-ui-core.js`
- 纯函数测试：`docs/dev-ops/nginx/html/test/agent-ui-core.test.js`
- 安全冒烟页：`docs/dev-ops/nginx/html/test/agent-ui-security-smoke.html`
- 前端调用的通用 Agent、股票分析、会话列表、会话消息和记忆同步接口。

### 1.4 不在本次测试范围

- 不以模型回答内容的业务准确率或投资建议质量作为前端通过条件。
- 不重新评测 Agent 路由算法、Prompt、记忆策略和股票分析图的内部实现。
- 不把模型供应商、行情服务、Mem0 等第三方服务的 SLA 作为本次前端成败依据。
- 不验证 React/Vue 等框架迁移、Mock 回放模式或新增业务页面，因为本需求不包含这些能力。
- 不展示或推导模型原始思维链，只验证后端实际返回的阶段性执行摘要。

---

## 2. 测试策略

### 2.1 测试分层

| 测试层级 | 是否覆盖 | 说明 |
|---|---|---|
| 单元测试 | 是 | 使用 Node `node:test` 验证 SSE 解析、清洗、事件分类、配置解析、生命周期和滚动判断 |
| 浏览器组件测试 | 是 | 使用真实 marked、DOMPurify 和 DOM 验证安全渲染与降级 |
| 可控 E2E | 是 | 通过本地 SSE 测试服务或浏览器网络拦截精确制造半包、断流、错误和竞态 |
| 真实 E2E | 是 | 页面连接本地真实后端，验证通用对话、股票分析、会话和记忆完整链路 |
| 回归测试 | 是 | 执行 Node 测试、浏览器回归和 Maven 默认测试 |
| 手工验证 | 是 | 验证窄屏布局、滚动体验、键盘操作和辅助技术语义 |

### 2.2 混合 E2E 原则

采用两条互补链路：

1. **可控链路**：使用固定 SSE fixture，保证异常分包、非法协议、断流、ABA 竞态和 XSS 可稳定复现。该链路负责确定性断言，不依赖模型输出。
2. **真实链路**：使用本地真实服务和真实页面，验证浏览器、Nginx/同源代理、后端 Agent、会话与记忆的完整协作。第三方不可用时标记 `blocked`，不得伪造为通过。

核心安全、状态和协议用例必须先通过可控链路；真实链路负责证明集成没有断点，两者不能相互替代。

### 2.3 Mock 策略

| 依赖项 | 是否 Mock | Mock 方式 | 说明 |
|---|---|---|---|
| SSE 字节流与 HTTP Response | 可控链路 Mock | 本地测试服务或浏览器 route fulfill | 控制 chunk 边界、延迟、响应头、断流和终止序列 |
| AbortController | 单元测试 Stub，E2E 使用浏览器原生实现 | 可记录 `abort` 次数的 Stub | 同时验证状态机和真实网络取消 |
| marked、DOMPurify | 单元测试 Stub，浏览器使用真实库 | 依赖注入与 vendored 脚本 | 验证纯函数接线和真实 DOM sink |
| localStorage | 可控链路 Stub | 正常、抛异常、非法值三类存储对象 | 验证优先级和不可用降级 |
| LLM、行情、Mem0 | 可控链路 Mock；真实链路不 Mock | 固定 fixture / 真实服务 | 不把第三方波动混入前端确定性断言 |

### 2.4 结果状态定义

| status | 含义 |
|---|---|
| `pending` | 尚未执行 |
| `pass` | 所有预期结果均有证据支持 |
| `fail` | 实际行为与需求不一致 |
| `blocked` | 环境或外部服务阻塞，不能判断实现正确性 |
| `not-applicable` | 经评审确认当前构建不适用，并已记录原因 |

---

## 3. 测试环境与数据准备

### 3.1 环境矩阵

| 环境 | 用途 | 最低要求 | status |
|---|---|---|---|
| Node 测试环境 | 纯函数与状态机验证 | 项目支持的 Node.js，能够执行 `node --test` | pending |
| 可控 SSE 环境 | 异常流与安全 E2E | 可设置响应头、chunk、延迟和主动断开 | pending |
| 本地真实环境 | 主链路 E2E | 前端可访问，后端及必要中间件已启动 | pending |
| 桌面浏览器 | 主流程与长内容 | Chromium，视口 `1280×720` | pending |
| 窄屏浏览器 | 响应式与核心操作 | Chromium，视口 `390×844` | pending |

### 3.2 测试数据约定

- 测试用户：`frontend-polish-e2e`
- 每条独立用例使用唯一 `sessionId`，格式为 `fpp-<TC编号>-<时间戳>`。
- 同一用例需要验证跨会话行为时，保持 `userId` 不变并显式切换 `sessionId`。
- 通用 Agent 输入应使用普通自然语言，不依赖前端硬编码或自动填充 Query。
- 股票分析使用测试环境可识别的合法股票代码；若行情依赖不可用，用例记为 `blocked`。
- 安全载荷仅在隔离测试环境使用，执行前设置 `window.__xssExecuted = false` 作为哨兵。

### 3.3 可控 SSE fixture

| fixture | 响应行为 | 覆盖用例 |
|---|---|---|
| `normal-general` | 多个思考事件、`content completed=true`、`complete` | TC-001、TC-203 |
| `normal-trading` | 分析师进度、`final_decision`、`trading_complete` | TC-002 |
| `split-chunks` | JSON 在 UTF-8 字符和任意字段处拆分，混用 LF/CRLF、多 `data:` 行 | TC-201 |
| `malformed-json` | 完整事件后追加非法 JSON | TC-101 |
| `disconnect-before-terminal` | 输出部分内容后直接断开 | TC-102 |
| `http-contract-errors` | 500、204、200 JSON、空 body | TC-103 |
| `xss-payloads` | 正文和元数据包含 script、事件属性和危险 URL | TC-104 |
| `oversized-event` | 单个未分隔事件超过 1 MiB | TC-105 |
| `duplicate-terminal` | `complete` 后继续发送内容和第二个终止事件 | TC-204 |
| `slow-cancellable` | 延迟输出，允许取消后立即启动新流 | TC-005、TC-205 |
| `long-markdown` | 长 Markdown、表格、代码块、超长英文和大量 chunk | TC-206 |

### 3.4 观测与证据

每条 E2E 用例至少记录：

- 用例编号、环境、浏览器版本、视口、`userId` 和 `sessionId`。
- 请求 URL、方法、请求次数、状态码和 `Content-Type`；敏感内容需脱敏。
- 关键 DOM 状态：Loading、发送/取消按钮、状态标签、思考面板、结果面板和 Toast。
- 浏览器控制台错误；预期之外的 JavaScript 异常数量必须为 0。
- 安全用例中 `window.__xssExecuted` 的最终值。
- 失败时的截图、有限长度协议摘要和复现步骤。

---

## 4. 需求追踪矩阵

| 需求来源 | 新增能力/风险 | 对应用例 |
|---|---|---|
| Batch 1 | SSE 跨 chunk、CRLF、多 data 行、结束冲刷 | TC-201 |
| Batch 1 | 非法 JSON、缓冲超限、响应契约校验 | TC-101、TC-103、TC-105 |
| Batch 1 | Markdown、用户输入、历史消息和元数据安全渲染 | TC-104、TC-301 |
| Batch 1 | 消息完成与请求终止分离、终止幂等 | TC-203、TC-204 |
| Batch 1 | 通用流和交易流最终结果归类 | TC-001、TC-002 |
| Batch 2 | 防重复提交、取消、统一收口 | TC-005、TC-202 |
| Batch 2 | 请求 token 隔离与 ABA 竞态 | TC-205 |
| Batch 2 | API Base URL 与 `userId` 优先级和校验 | TC-006、TC-107 |
| Batch 2 | localStorage 不可用降级 | TC-106 |
| Batch 2 | 新建/切换会话和业务入口时清理旧状态 | TC-208、TC-306 |
| Batch 3 | 长流帧级合并、完成后 Markdown 渲染 | TC-206 |
| Batch 3 | 用户可控自动滚动 | TC-008、TC-207 |
| Batch 3 | 窄屏、长内容、可访问性和 Toast | TC-007、TC-210、TC-307 |
| Batch 3 | 未知事件安全降级 | TC-209 |
| 整体回归 | 路由、多任务、股票分析、会话与记忆 | TC-301～TC-306 |

---

## 5. 测试场景设计

### 5.1 正常场景

| 编号 | 优先级 | 场景名称 | 前置条件 | 操作 | 预期结果 | 执行链路 | status |
|---|---|---|---|---|---|---|---|
| TC-001 | P0 | 通用 Agent 流式对话 | 页面空闲，通用接口可用 | 输入合法问题并发送，等待完整流 | 内容增量更新；最终内容仅出现一次并进入结果面板；收到 `complete` 后 Loading 与按钮恢复 | 可控 + 真实 | pending |
| TC-002 | P0 | 股票多 Agent 分析 | 股票分析及行情依赖可用 | 输入合法股票代码、日期和轮次并发送 | 分析师进度可读；最终决策进入结果面板；`trading_complete` 只收口一次 | 可控 + 真实 | pending |
| TC-003 | P1 | 会话历史加载与恢复 | 当前用户存在多轮历史 | 选择历史会话并加载更早消息 | 顺序、角色和分页正确；历史正文使用与实时正文相同的安全渲染策略 | 真实 | pending |
| TC-004 | P1 | 会话记忆同步 | 当前会话存在完整问答 | 点击同步记忆，等待接口结束 | 按钮只提交一次；显示非阻塞结果 Toast；成功或失败后按钮均恢复 | 真实 | pending |
| TC-005 | P0 | 主动取消并继续请求 | `slow-cancellable` 正在输出 | 点击取消，随后发起新请求 | reader 停止；状态为“已取消”而非“系统失败”；无需刷新即可成功发起下一请求 | 可控 + 真实 | pending |
| TC-006 | P1 | 运行配置优先级 | 可设置 URL 参数和 localStorage | 分别使用默认值、存储值、URL 值打开页面并触发全部接口 | 所有 API 使用同一解析后的 Base URL；`userId` 遵循 URL → localStorage → 默认值 | 可控 | pending |
| TC-007 | P1 | 桌面、窄屏和键盘操作 | 两种规定视口 | 完成模式切换、输入、发送、取消、会话选择和同步 | 核心按钮无遮挡；焦点可见；控件名称可识别；状态变化可通过 live region 获取 | 手工 | pending |
| TC-008 | P1 | 长流中用户回看历史 | 长流持续输出，内容超过面板高度 | 保持底部观察，再向上滚动，最后回到底部 | 底部附近时自动跟随；向上回看后不抢滚动；回到底部后恢复跟随 | 可控 + 手工 | pending |

### 5.2 异常场景

| 编号 | 优先级 | 场景名称 | 前置条件 | 操作 | 预期结果 | 执行链路 | status |
|---|---|---|---|---|---|---|---|
| TC-101 | P0 | SSE 非法 JSON | 使用 `malformed-json` | 发起请求并读到非法事件 | 显示“协议异常”或等价友好错误；不泄露完整响应；状态只收口一次且控件恢复 | 可控 | pending |
| TC-102 | P0 | 业务完成前网络断流 | 使用 `disconnect-before-terminal` | 发起请求并等待连接被关闭 | 标记“连接中断/未完成”，不得包装成成功；按钮和 Loading 恢复 | 可控 | pending |
| TC-103 | P0 | HTTP 与响应协议异常 | 使用 `http-contract-errors` | 依次返回 500、204、200 JSON、空 body | 每次只出现一个友好错误；不得进入永久 Loading；可继续下一请求 | 可控 | pending |
| TC-104 | P0 | XSS 与危险协议注入 | 设置安全哨兵并使用 `xss-payloads` | 覆盖用户输入、实时正文、历史正文、type/subType/step/model | 脚本、事件属性和危险 URL 均不执行；元数据按纯文本或固定标签展示；哨兵保持 `false` | 可控 + 浏览器 | pending |
| TC-105 | P1 | SSE 单事件缓冲超限 | 使用 `oversized-event` | 读取超过 1 MiB 且无边界的事件 | 立即失败并释放缓冲和请求状态；诊断被截断；页面可继续使用 | 可控 | pending |
| TC-106 | P1 | localStorage 不可用 | 让 get/set 抛出异常 | 刷新页面并执行通用、会话和记忆操作 | 降级为页面内存与默认配置；无未捕获异常；核心操作不被阻塞 | 可控 | pending |
| TC-107 | P1 | 非法运行配置 | URL/存储中放入超长或非法 `userId`、危险 Base URL | 打开页面并触发请求 | 非法值被拒绝；回退到下一合法来源；不得构造脚本协议或错误目标 URL | 可控 | pending |
| TC-108 | P0 | Markdown 依赖缺失 | 阻止 marked 或 DOMPurify 加载 | 获取含 HTML 的正文 | 停止富文本渲染并安全降级为纯文本，不继续写入未清洗 HTML | 可控 + 浏览器 | pending |
| TC-109 | P0 | 业务 error 后仍有事件 | error 后继续发送 content/complete | 读取整个响应 | 首个 error 终止生效；后续事件不改变 DOM 和最终状态；只显示一次失败提示 | 可控 | pending |

### 5.3 边界场景

| 编号 | 优先级 | 场景名称 | 前置条件 | 操作 | 预期结果 | 执行链路 | status |
|---|---|---|---|---|---|---|---|
| TC-201 | P0 | 任意 SSE 分包与边界 | 使用 `split-chunks` | 在 JSON 字段和 UTF-8 字符间拆包，混合 CR/LF/CRLF、多 data 行、注释与 `[DONE]` | 事件数量、顺序和正文不变；注释与 `[DONE]` 被忽略；流结束时剩余完整事件被冲刷 | 可控 | pending |
| TC-202 | P0 | 快速重复提交 | 页面空闲 | 连续双击发送或快速按两次 Enter | 网络面板只有一个业务请求；运行中发送入口禁用，取消入口可用 | 可控 + 真实 | pending |
| TC-203 | P0 | 消息完成不等于请求终止 | `normal-general` | 先发 `content completed=true`，延迟后再发 `complete` | 消息进入结果面板，但请求仍保持 running；只有 `complete` 才恢复控件 | 可控 | pending |
| TC-204 | P0 | 重复终止与终止后事件 | 使用 `duplicate-terminal` | 发送首个终止事件后继续推送 | 首个终止事件只执行一次收口；最终结果、Toast、状态不重复；后续事件被忽略 | 可控 | pending |
| TC-205 | P0 | 取消旧请求后立即启动新请求 | 请求 A 为慢流 | 取消 A，立即启动 B，让 A 的 `finally` 晚于 B 的 start | A 的迟到回调不能结束或修改 B；B 可独立完成；每个 token 只结束一次 | 可控 | pending |
| TC-206 | P1 | 超长 Markdown 与帧级合并 | 使用 `long-markdown` | 记录渲染次数和 Performance，等待完成 | 流式阶段按帧合并纯文本更新；完成时统一清洗 Markdown 和高亮；长代码局部横向滚动 | 可控 + 浏览器 | pending |
| TC-207 | P1 | 自动滚动阈值 | 面板已产生长内容 | 分别停在阈值内、阈值外并继续推流 | 仅阈值内自动跟随；阈值外保持用户位置；无明显跳动 | 可控 + 浏览器 | pending |
| TC-208 | P1 | 请求中切换会话或业务模式 | 请求 A 正在运行 | 新建会话、选择历史会话或切换通用/交易模式 | A 被取消或隔离；新视图不残留旧 Loading、按钮文本、状态和流式缓存 | 可控 + 手工 | pending |
| TC-209 | P1 | 未知但合法事件类型 | 返回合法未知 type/subType | 读取事件后继续发送已知终止事件 | 使用“未知事件/未知阶段”安全展示；不抛异常；后续事件继续处理 | 可控 | pending |
| TC-210 | P2 | 连续 Toast | 短时间触发多个校验或错误 | 连续触发 3 次提示 | 页面最多保留一个活动 Toast；最新内容正确；错误使用 alert 语义，其余使用 status | 可控 + 手工 | pending |

### 5.4 回归场景

| 编号 | 优先级 | 场景名称 | 前置条件 | 操作 | 预期结果 | 执行链路 | status |
|---|---|---|---|---|---|---|---|
| TC-301 | P0 | 统一入口意图路由 | 真实后端可用 | 自由输入不同意图并等待完成 | 原有路由及下游执行可用；阶段摘要安全展示；最终结果正确归类 | 真实 | pending |
| TC-302 | P1 | 多任务拆解与汇总 | 真实后端可用 | 输入包含多个独立或依赖任务的请求 | 子任务过程、依赖执行和最终汇总仍可完成；无前一请求缓存污染 | 真实 | pending |
| TC-303 | P0 | 股票多 Agent 协作 | 行情和模型依赖可用 | 执行完整股票分析 | 分析师、辩论、风控和最终决策链路仍可观察并正常终止 | 真实 | pending |
| TC-304 | P1 | 跨会话 Persona/情景记忆 | 同一用户可同步记忆 | 会话 A 同步记忆，新建会话 B 后进行相关追问 | 原有跨会话记忆能力不变；不同 `userId` 之间不串数据 | 真实 | pending |
| TC-305 | P1 | 会话列表与消息分页 | 历史数据超过一页 | 滚动加载列表和更早消息 | 游标正确；无重复或丢失；切换会话后内容与会话 ID 一致 | 真实 | pending |
| TC-306 | P1 | 新建、切换与清空会话 | 存在历史和活动请求 | 依次新建、切换、清空并返回业务页面 | 会话状态、空态和按钮正确；不遗留上一个请求的视觉状态 | 真实 + 手工 | pending |
| TC-307 | P2 | 输入校验和非阻塞反馈 | 页面空闲 | 提交空问题、非法股票代码/日期并快速修正 | 不发送无效请求；不使用阻塞式 alert；Toast 后输入与页面仍可操作 | 可控 + 手工 | pending |

---

## 6. 高风险用例执行步骤

### 6.1 TC-201：任意 SSE 分包

1. 将页面 API 指向可控 SSE 环境，启用 `split-chunks`。
2. 确认 fixture 至少包含两个事件，其中一个 JSON 在 UTF-8 多字节字符中间拆分。
3. 发起通用请求，等待流正常关闭。
4. 对比 fixture 事件与页面解析结果的数量、顺序和完整正文。
5. 检查控制台、Loading、按钮与最终结果面板。

通过断言：事件无丢失、无重复、无乱码；控制台无异常；结束后控件恢复。

### 6.2 TC-104：全路径 XSS

1. 在隔离页面设置 `window.__xssExecuted = false`。
2. 用户输入、实时 `content`、历史消息及元数据分别注入 `<script>`、`onerror`、`javascript:` URL 和恶意 Markdown。
3. 触发实时流、历史会话加载和未知事件展示。
4. 检查 DOM 中不存在可执行危险属性或协议，并读取安全哨兵。
5. 确认普通 Markdown、代码块和安全链接仍可读。

通过断言：`window.__xssExecuted === false`；无弹窗、跳转或网络外带；正文被清洗，元数据不进入 HTML sink。

### 6.3 TC-203/TC-204：完成语义与幂等

1. 发出 `content completed=true`，暂不发送请求终止事件。
2. 确认内容进入结果面板，但 Loading 和取消能力仍保持运行态。
3. 发出 `complete`，记录结果节点数、Toast 数和状态转换次数。
4. 再发一条 content 和第二个 `complete`。

通过断言：消息完成不提前结束请求；首个终止事件只收口一次；终止后的事件不改变 UI。

### 6.4 TC-205：取消后的 ABA 竞态

1. 启动慢请求 A，保存其网络请求和页面 token 证据。
2. 请求 A 输出第一个 chunk 后点击取消。
3. 不刷新页面，立即启动请求 B。
4. 安排 A 的拒绝或 `finally` 在 B 启动后返回。
5. 继续推送 B 直至业务完成。

通过断言：A 只进入 cancelled；A 的迟到回调不改变 B；B 的 Loading、按钮、内容和最终状态独立正确。

### 6.5 TC-006/TC-107：运行配置

1. 清空相关 localStorage，以无参数 URL 打开页面，记录默认配置。
2. 写入合法存储值并刷新，验证存储值生效。
3. 添加合法 URL 参数并刷新，验证 URL 覆盖存储值。
4. 分别提供非法、超长和危险协议值，验证逐级回退。
5. 触发通用、交易、会话列表、会话消息和记忆同步请求。

通过断言：所有接口使用同一 Base URL；`userId` 优先级一致；非法值不进入请求；默认测试用户仍可兼容现有数据。

### 6.6 TC-206/TC-207：长流渲染与滚动

1. 使用 `long-markdown` 连续推送大量小 chunk，记录渲染与 Markdown 解析次数。
2. 在面板底部观察自动跟随，然后滚动到阈值之外继续推流。
3. 回到底部并等待完成事件。
4. 检查最终 Markdown、表格、代码高亮、长英文换行和代码横向滚动。

通过断言：流式阶段没有逐 chunk 重解析完整 Markdown；用户回看时不被抢滚动；完成后仅对最终内容执行统一安全富文本渲染。

---

## 7. 用例与自动化映射

| 测试编号 | 对应用例方法/位置 | 目标组件/方法 | 覆盖类型 |
|---|---|---|---|
| TC-101、TC-105、TC-201 | `createSseParser ...` 系列测试 | `AgentUiCore#createSseParser` | 单元 |
| TC-103 | `validateSseResponse rejects ...` | `AgentUiCore#validateSseResponse` | 单元 |
| TC-104、TC-108 | `sanitizeMarkdown ...` + `agent-ui-security-smoke.html` | `sanitizeMarkdown`、浏览器 DOM sink | 单元/浏览器 |
| TC-203、TC-204、TC-209 | `classifyAgentEvent separates ...` 及事件矩阵 | `AgentUiCore#classifyAgentEvent` | 单元 |
| TC-006、TC-106、TC-107 | `resolveRuntimeConfig ...` 系列测试 | `resolveRuntimeConfig`、`buildApiUrl` | 单元 |
| TC-202、TC-205、TC-208 | `request lifecycle ...` 系列测试 | `AgentUiCore#createRequestLifecycle` | 单元/可控 E2E |
| TC-008、TC-207 | `isNearBottom keeps ...` | `AgentUiCore#isNearBottom` | 单元/浏览器 |
| TC-206 | 浏览器长流与 Performance 记录 | 流式渲染调度器 | 可控 E2E |
| TC-001～TC-008、TC-301～TC-307 | 本文档场景步骤 | `index.html` 与现有后端接口 | 真实 E2E/手工 |

浏览器自动化落地时，测试方法建议使用：

- `should_render_one_final_result_when_general_stream_completes`
- `should_cancel_active_request_and_allow_immediate_retry`
- `should_not_finish_new_request_when_old_finally_arrives`
- `should_neutralize_xss_from_live_history_and_metadata_paths`
- `should_preserve_reader_scroll_position_during_long_stream`

---

## 8. 关键校验点

### 8.1 数据正确性

- 任意合法 chunk 组合不改变事件数量、顺序、UTF-8 字符和 `content`。
- 历史消息与实时消息使用相同的正文清洗策略。
- 最终结果只出现一次，并进入正确面板。
- `userId` 和 session 必须与当前视图及请求一致，不跨用户串数据。

### 8.2 状态流转正确性

- `content completed=true` 仅表示消息段完成，不结束整个请求。
- 只有显式 `complete`、`trading_complete`、`error` 或真实断流/取消才能收口请求。
- 成功、失败、取消和断流路径都必须恢复按钮与 Loading。
- token 不匹配时，取消、失败和 `finally` 均不得改变当前活动请求。

### 8.3 异常处理正确性

- 非 SSE 响应、非法事件、缓冲超限、断流、取消都只产生一个最终状态。
- 用户取消不计为系统失败，业务完成后的网络关闭不重复报错。
- localStorage 或富文本依赖不可用时安全降级，不能阻断后续操作。
- 未知合法事件使用固定兜底标签，不阻断后续流。

### 8.4 安全、日志与监控

- 原始协议诊断最多记录 200 字符，不记录完整模型响应、用户历史或敏感凭据。
- `content` 是唯一允许进入 Markdown 清洗链路的协议字段；其他字段按纯文本处理。
- 浏览器控制台不出现未处理 Promise rejection 或 DOM 异常。
- 若系统接入 Langfuse/日志平台，应能按 session 或请求标识关联异常，但本测试不要求记录原始思维链。

---

## 9. 执行计划

### 9.1 自动化与集成执行

| 步骤 | 内容 | 预期结果 | status |
|---|---|---|---|
| 1 | 执行 `node --test docs/dev-ops/nginx/html/test/agent-ui-core.test.js` | 全部通过，0 失败 | pending |
| 2 | 打开 `agent-ui-security-smoke.html` 执行真实 DOM 清洗冒烟 | 全部安全载荷不执行 | pending |
| 3 | 执行可控 SSE 场景 TC-101～TC-210 | 所有协议、异常、竞态场景有确定结果 | pending |
| 4 | 执行真实主链路 TC-001～TC-008、TC-301～TC-307 | 无阻塞回归；外部阻塞被如实记录 | pending |
| 5 | 执行 `mvn clean test` | 默认测试无本轮新增失败 | pending |
| 6 | 执行 `git diff --check` | 无空白错误 | pending |

### 9.2 手工验证顺序

| 步骤 | 操作 | 预期结果 | status |
|---|---|---|---|
| 1 | 在 `1280×720` 完成通用、交易、取消和记忆同步 | 核心流程稳定，控件无遮挡 | pending |
| 2 | 在 `390×844` 重复核心操作并使用键盘导航 | 布局可用，焦点和状态语义清晰 | pending |
| 3 | 长流期间回看历史并恢复到底部 | 滚动控制符合用户意图 | pending |
| 4 | 触发失败、取消、空态和未知事件 | 文案、Toast、状态点和恢复行为一致 | pending |

---

## 10. 验收标准

| 编号 | 验收项 | 标准 | status |
|---|---|---|---|
| AC-001 | 流式正确性 | 半包、多事件、CR/LF、多 data 行和结束冲刷全部通过 | pending |
| AC-002 | 安全渲染 | 实时、历史、用户输入和元数据路径中的恶意内容均不执行 | pending |
| AC-003 | 完成语义 | 消息完成与请求终止分离，每条流只收口一次 | pending |
| AC-004 | 生命周期 | 成功、失败、取消、断流后均可继续下一请求 | pending |
| AC-005 | 竞态隔离 | 取消 A 后立即启动 B，A 的迟到回调不影响 B | pending |
| AC-006 | 运行配置 | 同源、URL/localStorage 覆盖、校验和默认回退符合设计 | pending |
| AC-007 | 响应式与可访问性 | `1280×720`、`390×844` 核心操作可用，状态可被辅助技术识别 | pending |
| AC-008 | 长流体验 | 不逐 chunk 全量解析 Markdown，不抢用户滚动位置 | pending |
| AC-009 | 核心回归 | 通用 Agent、股票分析、会话、记忆和多任务无阻塞回归 | pending |
| AC-010 | 自动化回归 | Node 与 Maven 默认测试无本轮新增失败 | pending |

全部 P0/P1 用例必须为 `pass`；真实第三方服务导致的 `blocked` 必须有环境证据和补测计划，不能用可控链路结果直接代替真实链路验收。

---

## 11. 风险与说明

| 风险点 | 影响 | 应对措施 |
|---|---|---|
| 外部模型、行情或记忆服务不可用 | 真实 E2E 无法完成 | 标记 `blocked` 并保留证据；先完成可控链路，环境恢复后补测 |
| 模型输出非确定 | 最终文本无法精确比对 | 断言事件、状态、面板和结构，不断言固定回答全文 |
| 浏览器时序导致 ABA 难复现 | 旧请求可能误结束新请求 | 可控 fixture 固定回调顺序，浏览器再执行取消后立即重试 |
| 超长 Markdown 受设备性能影响 | 性能数据波动 | 固定浏览器、视口、fixture 长度；验收渲染策略和交互可用性 |
| 真实后端协议与计划不一致 | 前端结果可能误判 | 保存原始事件类型与有限摘要，按需求契约提 BUG，不私自放宽标准 |
| 当前实现尚未完成三个批次 | 部分用例预期失败 | 保持 `pending/fail` 真实状态，作为提测门禁，不修改预期迎合现状 |

---

## 12. 执行结果记录

### 12.1 汇总

| 项目 | 结果 | 通过/总数 | 证据位置 |
|---|---|---|---|
| Node 单元测试 | not-run | 0/0 | - |
| 浏览器安全冒烟 | not-run | 0/0 | - |
| 可控 SSE E2E | not-run | 0/0 | - |
| 真实后端 E2E | not-run | 0/0 | - |
| 桌面与窄屏手工验证 | not-run | 0/0 | - |
| Maven 回归 | not-run | 0/0 | - |

### 12.2 用例执行记录模板

| 用例编号 | 环境/视口 | userId/sessionId | 结果 | 请求与 DOM 证据 | 备注 |
|---|---|---|---|---|---|
| TC-xxx | - | - | pending | - | - |

### 12.3 问题记录

| 编号 | 关联用例 | 问题描述 | 严重级别 | 影响范围 | 状态 |
|---|---|---|---|---|---|
| BUG-001 | - | 暂无 | - | - | none |

### 12.4 结论

- 是否达到提测条件：`否（待执行）`
- 是否达到发布/演示条件：`否（待执行）`
- 结论规则：全部验收项有结果、P0/P1 用例通过且不存在未关闭阻塞缺陷后，方可改为“是”。

