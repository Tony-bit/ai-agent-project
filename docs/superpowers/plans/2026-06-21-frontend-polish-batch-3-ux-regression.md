# 前端产品化打磨第三批：体验、可访问性与回归交付实施计划

> **供智能体执行者使用：** 必须使用 `superpowers:subagent-driven-development`（推荐）或 `superpowers:executing-plans` 子技能逐项执行本计划。所有步骤使用复选框（`- [ ]`）跟踪状态。

**目标：** 将现有 Agent 页面打磨成在桌面和窄屏上都稳定、易读的产品，并为全部现有能力交付可追踪的回归文档。

**架构：** 保留现有页面结构，只增加小范围 CSS/ARIA 优化、一个经过测试的滚动决策工具和浏览器帧级流式渲染合并。仅当读者原本就在底部附近时才自动滚动，流式阶段按纯文本批量刷新并在完成后统一 Markdown 渲染，以便保留用户控制权并避免每个 chunk 重复解析完整 Markdown；同时用文档覆盖正常、异常、边界和回归四类验证场景。

**技术栈：** 原生 JavaScript、静态 HTML/Tailwind CSS、Node.js `node:test`、Markdown 测试文档、Maven/JUnit 回归套件。

---

## 前置条件

开始本计划前，第一批和第二批必须已经提交并通过验收，其 Node 测试和真实端到端检查点均须通过。

## 文件结构

- 修改 `docs/dev-ops/nginx/html/js/agent-ui-core.js`：增加纯粹的底部邻近判断工具。
- 修改 `docs/dev-ops/nginx/html/test/agent-ui-core.test.js`：覆盖滚动阈值和未知事件。
- 修改 `docs/dev-ops/nginx/html/index.html:77-620,627-935,975-1020,1385-1810,2070-2100`：优化响应式布局、安全滚动行为、可访问状态播报、可读兜底标签及稳定的空态/错误态。
- 新建 `docs/superpowers/test/2026-06-21-frontend-product-polish-test.md`：可执行的四层回归与验收文档。
- 修改 `docs/demo/README.md`：增加前端验证顺序和失败恢复说明，不在产品代码中嵌入 Query 值。

### 任务 1：定义用户可控自动滚动和未知事件兜底

**文件：**
- 修改：`docs/dev-ops/nginx/html/test/agent-ui-core.test.js`
- 测试：`docs/dev-ops/nginx/html/test/agent-ui-core.test.js`

- [ ] **步骤 1：追加滚动阈值和兜底测试**

```javascript
const { isNearBottom } = require('../js/agent-ui-core.js');

test('isNearBottom keeps a reader pinned only inside the threshold', () => {
    assert.equal(isNearBottom({ scrollTop: 700, scrollHeight: 1000, clientHeight: 250 }, 60), true);
    assert.equal(isNearBottom({ scrollTop: 500, scrollHeight: 1000, clientHeight: 250 }, 60), false);
});

test('isNearBottom treats a non-scrollable panel as pinned', () => {
    assert.equal(isNearBottom({ scrollTop: 0, scrollHeight: 200, clientHeight: 300 }), true);
});

test('classifyAgentEvent safely falls back for an unknown event', () => {
    assert.deepEqual(classifyAgentEvent({ type: 'future_event', subType: 'v2' }), {
        target: 'thinking', messageCompleted: false,
        requestTerminal: false, outcome: null, error: false
    });
});
```

- [ ] **步骤 2：运行测试并确认 `isNearBottom` 尚不存在**

运行：

```powershell
node --test docs/dev-ops/nginx/html/test/agent-ui-core.test.js
```

预期：测试失败，因为尚未导出 `isNearBottom`。

- [ ] **步骤 3：实现并导出 `isNearBottom`**

```javascript
function isNearBottom({ scrollTop, scrollHeight, clientHeight }, threshold = 80) {
    if (scrollHeight <= clientHeight) return true;
    return scrollHeight - scrollTop - clientHeight <= threshold;
}
```

将其加入模块导出对象：

```javascript
return {
    createSseParser,
    escapeHtml,
    sanitizeMarkdown,
    normalizeAgentEvent,
    classifyAgentEvent,
    validateSseResponse,
    resolveRuntimeConfig,
    buildApiUrl,
    createRequestLifecycle,
    isNearBottom
};
```

- [ ] **步骤 4：运行全部核心测试并提交**

```powershell
node --test docs/dev-ops/nginx/html/test/agent-ui-core.test.js
git add docs/dev-ops/nginx/html/js/agent-ui-core.js docs/dev-ops/nginx/html/test/agent-ui-core.test.js
git commit -m "feat: preserve reader position during streaming"
```

预期：全部测试通过且提交成功。

### 任务 2：在流式输出期间保留读者滚动位置

**文件：**
- 修改：`docs/dev-ops/nginx/html/index.html:1385-1400,1500-1810`

- [ ] **步骤 1：导入已测试工具**

将 `isNearBottom` 加入现有 `window.AgentUiCore` 解构赋值。

- [ ] **步骤 2：使用感知用户意图的工具替换无条件滚动**

```javascript
function panelWasPinned(container) {
    return isNearBottom({
        scrollTop: container.scrollTop,
        scrollHeight: container.scrollHeight,
        clientHeight: container.clientHeight
    });
}

function scrollToBottom(container, force = false) {
    if (!container) {
        ['thinkingMessages', 'resultMessages'].forEach((id) => {
            const panel = document.getElementById(id);
            if (force || panelWasPinned(panel)) panel.scrollTop = panel.scrollHeight;
        });
        return;
    }
    if (force || panelWasPinned(container)) {
        container.scrollTop = container.scrollHeight;
    }
}
```

- [ ] **步骤 3：在 DOM 增长前记录吸底状态**

In `addStageMessage`, before `appendChild`:

```javascript
const keepPinned = panelWasPinned(targetContainer);
targetContainer.appendChild(messageDiv);
if (keepPinned) targetContainer.scrollTop = targetContainer.scrollHeight;
```

In `updateMessageContent`, before replacing HTML:

```javascript
const container = messageDiv.parentElement;
const keepPinned = container ? panelWasPinned(container) : false;
contentDiv.innerHTML = renderMarkdown(content);
if (container && keepPinned) container.scrollTop = container.scrollHeight;
```

删除这些函数末尾无条件调用的 `scrollToBottom(targetContainer)`。新提交用户消息和首次加载会话历史时，继续使用 `scrollToBottom(container, true)`。

- [ ] **步骤 4：将逐 chunk Markdown 重绘改为帧级纯文本刷新**

在页面状态区增加只保留最新值的浏览器帧调度器：

```javascript
const pendingStreamingRenders = new Map();
let streamingRenderFrame = null;

function flushStreamingRenders() {
    pendingStreamingRenders.forEach((latestContent, targetDiv) => {
        const contentDiv = targetDiv.querySelector('.markdown-content');
        if (contentDiv) contentDiv.textContent = latestContent;
    });
    pendingStreamingRenders.clear();
    streamingRenderFrame = null;
}

function scheduleStreamingRender(messageDiv, content) {
    pendingStreamingRenders.set(messageDiv, content);
    if (streamingRenderFrame !== null) return;
    streamingRenderFrame = window.requestAnimationFrame(flushStreamingRenders);
}
```

将第一批 `handleSSEMessage` 增量分支中的：

```javascript
updateMessageContent(cached.div, cached.content);
```

替换为：

```javascript
scheduleStreamingRender(cached.div, cached.content);
```

流式阶段不得调用 `marked.parse`、DOMPurify 或 highlight.js；收到 `content completed=true` 后，结果面板仍通过第一批 `addStageMessage('summary', ...)` 执行一次完整 Markdown 清洗和高亮。`clearStreamingEffects` 同时取消未执行帧并清空 `pendingStreamingRenders`：

```javascript
if (streamingRenderFrame !== null) window.cancelAnimationFrame(streamingRenderFrame);
flushStreamingRenders();
```

- [ ] **步骤 5：验证流式输出不再抢夺历史浏览位置且不会逐 chunk 重解析 Markdown**

手工检查：

1. 发起一个内容足够长、能够产生滚动条的响应。
2. 在 token 持续输出时向上滚动思考面板。
3. 确认面板停留在用户选择的历史位置。
4. 滚动回距离底部 80px 以内。
5. 确认后续 token 继续让面板吸附在底部。
6. 在浏览器 Performance 面板中确认长响应期间每帧最多执行一次流式 DOM 刷新，`renderMarkdown` 只在消息完成或历史加载时调用。

- [ ] **步骤 6：提交滚动与流式渲染修改**

```powershell
git add docs/dev-ops/nginx/html/index.html
git commit -m "fix: preserve scroll and batch streaming renders"
```

### 任务 3：优化响应式布局和长内容渲染

**文件：**
- 修改：`docs/dev-ops/nginx/html/index.html:77-620,627-935`

- [ ] **步骤 1：在不改变业务结构的前提下增加稳定响应式 ID**

修改外层工作区和面板行：

```html
<div id="agentWorkspace" class="flex-1 flex overflow-hidden p-3 gap-3">
...
<div id="agentPanels" class="flex-1 flex overflow-hidden">
```

- [ ] **步骤 2：增加长内容和焦点样式**

追加到现有样式块：

```css
.markdown-content {
    min-width: 0;
    overflow-wrap: anywhere;
    word-break: break-word;
}

.markdown-content pre {
    max-width: 100%;
    overflow-x: auto;
    overscroll-behavior-inline: contain;
}

.markdown-content table {
    display: block;
    max-width: 100%;
    overflow-x: auto;
}

button:focus-visible,
input:focus-visible,
select:focus-visible {
    outline: 2px solid #3b82f6;
    outline-offset: 2px;
}

.message > .flex-1,
.history-message > .flex-1 {
    min-width: 0;
}
```

- [ ] **步骤 3：增加一个窄屏布局断点**

```css
@media (max-width: 900px) {
    body {
        min-height: 100vh;
        height: auto;
        overflow: auto;
    }

    nav {
        position: sticky;
        top: 0;
    }

    #agentWorkspace {
        min-height: calc(100vh - 64px);
        flex-direction: column;
        overflow: visible;
    }

    #sidebar {
        width: 100%;
        max-height: 12rem;
        flex: none;
    }

    #agentPanels {
        min-height: 48rem;
        flex-direction: column;
    }

    #thinkingPanel,
    #resultPanel {
        width: 100%;
        min-height: 22rem;
    }

    #agentPanels > .divider-gradient {
        width: 100%;
        height: 1px;
    }

    .input-dock {
        position: sticky;
        bottom: 0;
        z-index: 20;
    }
}
```

- [ ] **步骤 4：验证桌面与窄屏布局**

分别在 1280×720 和 390×844 下检查：

1. 导航按钮保持可见。
2. 会话历史可以正常访问。
3. 思考面板和结果面板都可阅读。
4. 通用输入区和交易输入区不被遮挡。
5. Markdown 表格和代码块可以横向滚动，不会撑宽页面。

- [ ] **步骤 5：提交响应式优化**

```powershell
git add docs/dev-ops/nginx/html/index.html
git commit -m "style: polish agent workspace responsiveness"
```

### 任务 4：统一状态、事件标签和可访问性

**文件：**
- 修改：`docs/dev-ops/nginx/html/index.html:627-935,975-1020,1500-1765,2070-2100`

- [ ] **步骤 1：增加可访问实时区域语义**

应用以下属性：

```html
<div id="loading" class="hidden mb-3" role="status" aria-live="polite" aria-atomic="true">
<div id="thinkingMessages" ... role="log" aria-live="polite" aria-relevant="additions text">
<div id="resultMessages" ... role="log" aria-live="polite" aria-relevant="additions text">
<span id="thinkingStatusLabel" ... aria-live="polite">待命</span>
<span id="resultStatusLabel" ... aria-live="polite">就绪</span>
```

为 `newChatBtn`、`clearAllChatsBtn`、`refreshSessionListBtn`、`sendBtn`、`sendTradingBtn` 和 `syncMemoryBtn` 增加 `type="button"`。

- [ ] **步骤 2：删除前端硬编码示例 Query**

删除包含 `label for="exampleSelect"` 和 `select id="exampleSelect"` 的完整控件块，并删除其 change 监听器：

```javascript
document.getElementById('exampleSelect').addEventListener('change', function() {
    const selectedExample = this.value;
    if (selectedExample) {
        document.getElementById('messageInput').value = selectedExample;
        this.value = '';
        updateInputCounter();
    }
});
```

不要用其他预设、Query 常量、隐藏数据属性或自动填充行为替代它。演示输入继续由用户负责，只能存在于产品外部的演示文档中。

- [ ] **步骤 3：扩充可读事件标签和友好兜底**

在不改变现有标签的前提下增加以下条目：

```javascript
Object.assign(stageTypeMap, {
    content: { name: '流式回答', icon: '💬', class: 'stage-execution' },
    progress: { name: '执行进度', icon: '⏳', class: 'stage-analysis' },
    system: { name: '系统消息', icon: 'ℹ️', class: 'stage-analysis' }
});

Object.assign(subTypeMap, {
    protocol_error: '协议异常',
    stream_interrupted: '连接中断',
    cancelled: '用户取消',
    analyst_progress: '分析师进度'
});
```

保留现有兜底逻辑：

```javascript
const stageInfo = stageTypeMap[type] || {
    name: '未知事件',
    icon: '📝',
    class: 'stage-analysis'
};
```

该兜底继承第一批安全契约：可以记录原始未知类型用于有限诊断，但不得将它写入 `innerHTML`。`subType` 的未知兜底同样固定显示“未知阶段”。

- [ ] **步骤 4：让状态转换播报真实结果**

更新生命周期 `onChange` 回调：

```javascript
const statusText = {
    completed: ['待命', '已完成'],
    failed: ['异常', '未完成'],
    cancelled: ['已取消', '已取消'],
    idle: ['待命', '就绪']
};
if (!running) {
    const [thinkingText, resultText] = statusText[status] || statusText.idle;
    document.getElementById('thinkingStatusLabel').textContent = thinkingText;
    document.getElementById('resultStatusLabel').textContent = resultText;
}
```

现有运行中标签继续使用“思考中/分析中”和“等待结果”。

- [ ] **步骤 5：让 Toast 可访问且不重复堆叠**

更新 `showToast`：

```javascript
function showToast(message, type = 'info') {
    const previous = document.getElementById('activeToast');
    if (previous) previous.remove();

    const toast = document.createElement('div');
    toast.id = 'activeToast';
    toast.setAttribute('role', type === 'error' ? 'alert' : 'status');
    toast.setAttribute('aria-live', type === 'error' ? 'assertive' : 'polite');
    const bgColor = type === 'success' ? 'bg-green-500'
        : type === 'error' ? 'bg-red-500' : 'bg-blue-500';
    toast.className = `fixed top-4 right-4 ${bgColor} text-white px-4 py-2 rounded-lg shadow-lg z-50 animate-slide-down text-sm font-medium`;
    toast.textContent = message;
    document.body.appendChild(toast);

    window.setTimeout(() => {
        toast.classList.remove('animate-slide-down');
        toast.classList.add('animate-fade-out');
        window.setTimeout(() => toast.remove(), 300);
    }, 3000);
}
```

- [ ] **步骤 6：运行自动化测试和标记检查**

```powershell
node --test docs/dev-ops/nginx/html/test/agent-ui-core.test.js
rg -n "role=\"log\"|aria-live|type=\"button\"|protocol_error|stream_interrupted" docs/dev-ops/nginx/html/index.html
rg -n "exampleSelect" docs/dev-ops/nginx/html/index.html
git diff --check
```

预期：全部 Node 测试通过；可以检索到每个新增状态/可访问性标记；`exampleSelect` 无匹配；差异检查无输出。

- [ ] **步骤 7：提交状态与可访问性优化**

```powershell
git add docs/dev-ops/nginx/html/index.html
git commit -m "fix: clarify agent states and accessibility"
```

### 任务 5：创建正式回归与验收文档

**文件：**
- 新建：`docs/superpowers/test/2026-06-21-frontend-product-polish-test.md`

- [ ] **步骤 1：创建覆盖四层场景的测试文档**

使用项目 `test-doc-template` 技能编写以下完整场景矩阵。文档必须显式引用 `docs/superpowers/plans/2026-06-21-frontend-product-polish-design.md` 和三份分批计划。

```markdown
# 测试：AI Agent 前端产品化打磨

## 1. 测试背景

### 1.1 对应设计与计划
- 设计：`docs/superpowers/plans/2026-06-21-frontend-product-polish-design.md`
- 第一批：`docs/superpowers/plans/2026-06-21-frontend-polish-batch-1-stream-security.md`
- 第二批：`docs/superpowers/plans/2026-06-21-frontend-polish-batch-2-lifecycle-config.md`
- 第三批：`docs/superpowers/plans/2026-06-21-frontend-polish-batch-3-ux-regression.md`

### 1.2 测试目标
- 验证 SSE 分包、输出清洗、事件归类和请求生命周期正确。
- 验证同源/覆盖配置、跨会话用户标识和取消行为稳定。
- 验证通用对话、股票分析、历史会话和记忆同步未回归。

### 1.3 测试范围
- `index.html` 页面交互与布局。
- `agent-ui-core.js` 纯函数和状态控制。
- 通用 Agent、交易 Agent、会话与记忆接口的前端集成。

### 1.4 不在范围
- 不验证模型回答的业务准确率。
- 不修改或重新评测路由 Prompt、记忆策略和交易图。
- 不验证真实第三方服务的 SLA。

## 2. 测试策略

### 2.1 测试分层

| 测试层级 | 是否覆盖 | 说明 |
|---|---|---|
| 单元测试 | 是 | Node 内置测试覆盖纯函数 |
| 集成测试 | 是 | 浏览器连接本地后端验证 SSE 和状态 |
| 接口测试 | 部分 | 沿用现有接口，不新增契约 |
| 回归测试 | 是 | 覆盖现有五条能力线 |
| 手工验证 | 是 | 布局、滚动、取消和可访问状态 |

### 2.2 Mock 策略

| 依赖项 | 是否 Mock | Mock 方式 | 说明 |
|---|---|---|---|
| SSE 文本流与 HTTP Response | 是 | Node Stub / 浏览器测试流 | 精确控制半包、非法 JSON、断流和响应类型 |
| AbortController | 是 | 可记录 abort 次数的对象 Stub | 只验证本层 token 与状态转换 |
| marked、DOMPurify | 单元测试 Stub，浏览器冒烟使用真实库 | 注入对象 / vendored browser scripts | 同时验证纯函数接线与真实 DOM 清洗 |
| LLM、行情、Mem0 等外部服务 | 否，不纳入自动化断言 | 真实联调仅记录可用性 | 不把第三方 SLA 当作本次前端成败依据 |

## 3. 测试场景设计

### 3.1 正常场景

| 编号 | 场景 | 前置条件 | 操作 | 预期结果 | status |
|---|---|---|---|---|---|
| TC-001 | 通用流式对话 | 后端可用 | 提交任意合法问题 | 内容连续更新、最终结果一次、按钮恢复 | pending |
| TC-002 | 股票多 Agent 分析 | 行情依赖可用 | 提交合法股票分析 | 分析事件可读、最终决策进入结果面板 | pending |
| TC-003 | 会话历史恢复 | 当前用户存在历史 | 选择历史会话 | 消息安全渲染、顺序正确 | pending |
| TC-004 | 记忆同步 | 当前会话有完整问答 | 点击同步记忆 | Toast 成功、按钮恢复、页面可继续操作 | pending |
| TC-005 | 主动取消 | 流正在输出 | 点击取消任务 | 请求中止、状态为已取消、可立即重试 | pending |

### 3.2 异常场景

| 编号 | 场景 | 前置条件 | 操作 | 预期结果 | status |
|---|---|---|---|---|---|
| TC-101 | SSE 非法 JSON | 测试流返回非法事件 | 读取事件 | 显示协议异常、不执行脚本、后续状态可收口 | pending |
| TC-102 | 网络中途断开 | 流未完成 | 停止后端连接 | 显示连接中断、按钮恢复 | pending |
| TC-103 | HTTP 或响应协议异常 | 后端返回非 2xx、204、200 JSON 或空响应体 | 提交请求 | 只显示一个友好错误、无永久 Loading | pending |
| TC-104 | localStorage 禁用 | 浏览器阻止存储 | 刷新并提交 | 使用默认配置、不阻断请求 | pending |
| TC-105 | 恶意正文与事件元数据 | 正文或 type/subType/step/model 含 script/onerror/javascript URL | 提交、注入测试流或加载历史 | 内容被清洗或按纯文本展示、没有代码执行 | pending |

### 3.3 边界场景

| 编号 | 场景 | 前置条件 | 操作 | 预期结果 | status |
|---|---|---|---|---|---|
| TC-201 | SSE 任意半包 | JSON 跨多个 chunk | 读取完整流 | 只产生一个完整事件、不丢内容 | pending |
| TC-202 | 快速重复提交 | 页面空闲 | 连续双击发送 | 只有一个网络请求 | pending |
| TC-203 | 超长 Markdown/代码块 | 返回长表格和长代码 | 查看结果 | 页面不横向撑开，局部可滚动 | pending |
| TC-204 | 流中回看历史 | 长流持续输出 | 向上滚动 | 页面不抢滚动；回到底部后继续跟随 | pending |
| TC-205 | 非法 userId | URL 含不合法 userId | 打开页面 | 使用已存储或默认 userId | pending |
| TC-206 | 终止事件序列 | 测试流分别发送 content→complete、final→trading_complete、error→complete | 读取完整流 | 消息完成与请求终止语义正确，每条流只结束一次 | pending |
| TC-207 | 取消后立即重试 | 请求 A 运行中 | 取消 A 并立即启动 B，等待 A 的 finally 晚到 | A 的回调不结束 B，B 可独立完成 | pending |
| TC-208 | 长流渲染合并 | 返回长 Markdown/代码流 | 记录 Performance | 流式阶段帧级纯文本刷新，完成时只做一次 Markdown 清洗和高亮 | pending |

### 3.4 回归场景

| 编号 | 场景 | 前置条件 | 操作 | 预期结果 | status |
|---|---|---|---|---|---|
| TC-301 | 意图路由 | 使用统一 Agent 入口 | 自由输入不同意图 | 原有路由和下游执行不变 | pending |
| TC-302 | 多任务拆解 | 输入复合任务 | 等待执行完成 | 子任务过程和汇总正常 | pending |
| TC-303 | 多 Agent 协作 | 使用股票分析 | 等待分析完成 | 分析师、辩论和最终决策正常 | pending |
| TC-304 | 跨会话记忆 | 使用同一 userId 不同 session | 完成记忆同步后新建会话 | 原有 Persona/情景记忆能力不变 | pending |
| TC-305 | 会话列表分页 | 历史数量超过一页 | 滚动侧栏和消息历史 | 不重复、不丢失、游标正常 | pending |

## 4. 用例与代码映射

| 用例 | 测试方法/验证位置 | 目标组件/方法 | 类型 |
|---|---|---|---|
| TC-101、TC-201 | `createSseParser reports malformed JSON at end of stream`、分片与多事件测试 | `AgentUiCore#createSseParser` | 单元 |
| TC-103 | `validateSseResponse rejects success responses that are not readable SSE` | `AgentUiCore#validateSseResponse` | 单元 |
| TC-105 | `sanitizeMarkdown applies a narrow Markdown policy and falls back to escaped text` + `agent-ui-security-smoke.html` | `sanitizeMarkdown`、浏览器 DOM sinks | 单元/浏览器 |
| TC-202、TC-207 | `request lifecycle rejects duplicate starts and finishes the matching token once`、`a cancelled request cannot finish a newer request` | `AgentUiCore#createRequestLifecycle` | 单元 |
| TC-204 | `isNearBottom keeps a reader pinned only inside the threshold` | `AgentUiCore#isNearBottom` | 单元 |
| TC-206 | `classifyAgentEvent separates message completion from request termination` | `AgentUiCore#classifyAgentEvent` | 单元 |
| TC-208 | 浏览器 Performance 记录 | `scheduleStreamingRender` | 手工/集成 |
| 其余场景 | 本地浏览器与现有后端联调 | `index.html` 和现有接口 | 手工/集成 |

## 5. 关键校验点

### 5.1 数据正确性
- 任意合法分片组合不改变事件数量、顺序和 `content`。
- 历史消息与实时消息使用相同的正文清洗策略。

### 5.2 状态流转正确性
- 消息段完成不结束请求；显式 `complete/trading_complete/error` 才结束请求。
- 请求 token 不匹配时，取消、失败和 `finally` 都不得改变当前活动请求。

### 5.3 异常处理正确性
- 非 SSE 响应、非法事件、缓冲超限、断流和取消都只产生一个最终状态。
- 任何错误路径都必须恢复按钮和 Loading，不要求刷新页面。

### 5.4 日志与诊断
- 原始协议片段最多记录 200 字符，不记录完整模型响应和用户历史。
- 未知事件允许记录类型用于诊断，但 UI 只显示固定安全兜底。

## 6. 执行计划

| 步骤 | 内容 | 预期结果 | status |
|---|---|---|---|
| 1 | 运行 Node 单元测试 | 全部通过 | pending |
| 2 | 执行桌面与窄屏手工验证 | 无阻塞问题 | pending |
| 3 | 执行通用、交易、会话、记忆回归 | 既有能力不回归 | pending |
| 4 | 运行 Maven 默认测试 | 无新增失败 | pending |

## 7. 验收标准

| 编号 | 验收项 | 标准 | status |
|---|---|---|---|
| AC-001 | 流式正确性 | 半包、多事件和结束冲刷测试通过 | pending |
| AC-002 | 安全渲染 | 恶意内容不执行 | pending |
| AC-003 | 生命周期 | 成功、失败、取消均可继续下一请求 | pending |
| AC-004 | 响应式体验 | 1280×720 与 390×844 核心操作可用 | pending |
| AC-005 | 核心回归 | 五条现有能力线无阻塞回归 | pending |
| AC-006 | 竞态隔离 | 取消 A 后立即启动 B，A 的迟到回调不影响 B | pending |
| AC-007 | 长流性能 | 流式阶段不逐 chunk 重解析完整 Markdown | pending |

## 8. 风险与说明

| 风险点 | 影响 | 应对措施 |
|---|---|---|
| 外部模型或行情服务不可用 | 真实 E2E 无法完成 | 标记 blocked/not-run，不伪造通过；先执行 Stub 和本地异常恢复用例 |
| 浏览器时序导致 ABA 竞态难复现 | 旧请求可能误结束新请求 | 单元测试固定调用顺序，浏览器再执行取消后立即重试 |
| 超长 Markdown 引发设备差异 | 性能结果不稳定 | 记录视口、浏览器版本和样例长度，只验收是否逐 chunk 全量解析 |

## 9. 执行结果记录

| 项目 | 结果 |
|---|---|
| Node 单元测试 | not-run |
| 浏览器手工验证 | not-run |
| Maven 回归 | not-run |
| 用户端到端演示 | not-run |

### 9.1 问题记录

| 编号 | 问题描述 | 影响范围 | 状态 |
|---|---|---|---|
| - | 暂无 | - | none |

### 9.2 结论
- 是否达到提测条件：否（待执行）。
- 所有 TC/AC 有结果且不存在未关闭的阻塞问题后，才能改为“是”。
```

- [ ] **步骤 2：检查测试文档结构和状态字段**

运行：

```powershell
rg -n "^### 2\.2|^### 3\.[1-4]|^## [5-9]\.|\| TC-|\| AC-|status|not-run|问题记录" docs/superpowers/test/2026-06-21-frontend-product-polish-test.md
```

预期：包含 Mock 策略、四类场景、代码映射、关键校验点、风险、执行结果、问题记录、TC/AC 行和可执行的状态字段。

- [ ] **步骤 3：提交回归文档**

```powershell
git add docs/superpowers/test/2026-06-21-frontend-product-polish-test.md
git commit -m "test: add frontend product polish regression plan"
```

### 任务 6：更新演示手册并执行最终回归

**文件：**
- 修改：`docs/demo/README.md`
- 验证：`docs/superpowers/test/2026-06-21-frontend-product-polish-test.md`

- [ ] **步骤 1：在演示手册中增加前端验证章节**

追加：

```markdown
## 前端产品验证

使用现有页面输入框；不要在前端源码中增加固定演示 Query。

1. 确认页面显示的当前 `userId` 和会话 ID。
2. 执行一次通用 Agent 请求，验证增量输出、唯一最终结果以及控件恢复。
3. 执行一次交易分析，验证分析师进度和最终决策位置。
4. 长请求运行期间向上滚动，确认页面不会强制将面板拉回底部。
5. 取消一个运行中请求，并在不刷新页面的情况下立即启动另一个请求。
6. 将已完成的对话同步到记忆，确认控件总能退出加载状态。
7. 面试前在窄屏视口下重复关键流程。

失败恢复：

- 如果服务商不可用，等待页面回到失败状态后再发起新请求；不应要求刷新页面。
- 如果流结束时没有收到业务完成事件，应将页面显示的“连接中断”视为真实协议失败，不能包装成成功。
- 如果运行配置发生变化，通过页面 URL 传递 `apiBase` 和 `userId`，不要修改源码。
```

- [ ] **步骤 2：运行前端自动化测试**

```powershell
node --test docs/dev-ops/nginx/html/test/agent-ui-core.test.js
```

预期：全部测试通过。

- [ ] **步骤 3：运行仓库回归测试**

```powershell
mvn clean test
```

预期：全部默认 Maven 模块通过；集成 profile 测试继续按设计排除。

- [ ] **步骤 4：执行并记录浏览器场景**

执行 `docs/superpowers/test/2026-06-21-frontend-product-polish-test.md` 中的 TC-001 至 TC-305。将每个已执行用例从 `pending` 改为 `pass` 或 `fail`，并将四个 `not-run` 结果替换为实际结果。任何失败都要新增一条 `BUG-xxx` 记录，包含现象、受影响场景和状态。

- [ ] **步骤 5：运行最终仓库检查**

```powershell
git diff --check
git status --short
```

预期：不存在空白错误；只出现第三批有意修改的文件和无关的既有用户改动。

- [ ] **步骤 6：提交最终交付材料**

```powershell
git add docs/dev-ops/nginx/html/index.html docs/dev-ops/nginx/html/js/agent-ui-core.js docs/dev-ops/nginx/html/test/agent-ui-core.test.js docs/superpowers/test/2026-06-21-frontend-product-polish-test.md docs/demo/README.md
git commit -m "docs: complete frontend polish verification"
```

## 第三批及整体完成检查点

汇报：

1. Node 测试数量和结果。
2. Maven 模块结果。
3. 桌面与窄屏验证结果。
4. TC/AC 通过和失败数量。
5. 所有未执行的外部服务场景。
6. 工作区中剩余的无关改动。

只有在用户独立完成真实端到端演示，并确认失败或取消请求后无需刷新页面即可继续发起新请求时，三批打磨工作才算完成。
