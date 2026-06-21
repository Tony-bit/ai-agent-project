# 前端产品化打磨第一批：流式正确性与安全实施计划

> **供智能体执行者使用：** 必须使用 `superpowers:subagent-driven-development`（推荐）或 `superpowers:executing-plans` 子技能逐项执行本计划。所有步骤使用复选框（`- [ ]`）跟踪状态。

**目标：** 保证两类 Agent 流在任意网络分块边界下都不丢失事件，安全渲染所有不可信内容，并让每次请求只在正确面板中结束一次。

**架构：** 在现有静态资源旁新增一个无需构建的 UMD 工具模块，使纯解析、清洗和事件分类逻辑能够使用 Node 内置测试运行器验证。保留现有 HTML 外壳和 UI 渲染器，但让通用流与交易流统一经过共享模块和原始事件适配器。

**技术栈：** 原生 JavaScript、Fetch 流、DOMPurify、marked、highlight.js、Node.js `node:test`、静态 HTML/Tailwind CSS。

---

## 文件结构

- 新建 `docs/dev-ops/nginx/html/js/agent-ui-core.js`：供页面和 Node 测试共享的免构建纯工具模块。
- 新建 `docs/dev-ops/nginx/html/test/agent-ui-core.test.js`：解析器、清洗器和事件分类测试。
- 修改 `docs/dev-ops/nginx/html/index.html:6-11,936-1765,1980-2035`：加载模块、替换两套临时流解析器、清洗渲染内容并统一完成事件分类。

### 任务 1：定义共享流与渲染契约

**文件：**
- 新建：`docs/dev-ops/nginx/html/test/agent-ui-core.test.js`
- 测试：`docs/dev-ops/nginx/html/test/agent-ui-core.test.js`

- [ ] **步骤 1：为 SSE 分片、CRLF、多行数据、内容清洗和事件分类编写失败测试**

```javascript
'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const {
    createSseParser,
    escapeHtml,
    sanitizeMarkdown,
    classifyAgentEvent
} = require('../js/agent-ui-core.js');

test('createSseParser joins a JSON event split across chunks', () => {
    const events = [];
    const errors = [];
    const parser = createSseParser({ onEvent: events.push.bind(events), onError: errors.push.bind(errors) });

    parser.push('data: {"type":"content","cont');
    parser.push('ent":"hello"}\n\n');

    assert.deepEqual(events, [{ type: 'content', content: 'hello' }]);
    assert.deepEqual(errors, []);
});

test('createSseParser accepts CRLF and joins multiple data lines', () => {
    const events = [];
    const parser = createSseParser({ onEvent: events.push.bind(events), onError: assert.fail });

    parser.push('event: progress\r\ndata: {"type":"analysis",\r\ndata: "content":"ok"}\r\n\r\n');

    assert.deepEqual(events, [{ type: 'analysis', content: 'ok' }]);
});

test('createSseParser ignores comments and DONE markers', () => {
    const events = [];
    const parser = createSseParser({ onEvent: events.push.bind(events), onError: assert.fail });

    parser.push(': heartbeat\n\ndata: [DONE]\n\n');

    assert.deepEqual(events, []);
});

test('createSseParser reports malformed JSON at end of stream', () => {
    const errors = [];
    const parser = createSseParser({
        onEvent: assert.fail,
        onError: (error) => errors.push(error)
    });

    parser.push('data: {"type":');
    parser.finish();

    assert.equal(errors.length, 1);
    assert.match(errors[0].message, /Invalid SSE JSON/);
});

test('escapeHtml neutralizes user supplied markup', () => {
    assert.equal(
        escapeHtml('<img src=x onerror=alert(1)>'),
        '&lt;img src=x onerror=alert(1)&gt;'
    );
});

test('sanitizeMarkdown sanitizes marked output and falls back to escaped text', () => {
    const markedStub = { parse: (value) => `<p>${value}</p><script>alert(1)</script>` };
    const purifierStub = { sanitize: (value) => value.replace(/<script>[\s\S]*?<\/script>/g, '') };

    assert.equal(sanitizeMarkdown(markedStub, purifierStub, '**safe**'), '<p>**safe**</p>');
    assert.equal(sanitizeMarkdown(null, null, '<b>plain</b>'), '&lt;b&gt;plain&lt;/b&gt;');
});

test('classifyAgentEvent sends terminal results to the result panel', () => {
    assert.deepEqual(classifyAgentEvent({ type: 'content', completed: false }), {
        target: 'thinking', terminal: false, error: false
    });
    assert.deepEqual(classifyAgentEvent({ type: 'content', completed: true }), {
        target: 'result', terminal: true, error: false
    });
    assert.deepEqual(classifyAgentEvent({ type: 'trading', subType: 'trading_complete', completed: true }), {
        target: 'result', terminal: true, error: false
    });
    assert.deepEqual(classifyAgentEvent({ type: 'error', completed: true }), {
        target: 'thinking', terminal: true, error: true
    });
});
```

- [ ] **步骤 2：运行测试并确认模块缺失**

运行：

```powershell
node --test docs/dev-ops/nginx/html/test/agent-ui-core.test.js
```

预期：测试失败，并显示 `Cannot find module '../js/agent-ui-core.js'`。

- [ ] **步骤 3：提交可执行契约**

```powershell
git add docs/dev-ops/nginx/html/test/agent-ui-core.test.js
git commit -m "test: define frontend stream safety contract"
```

### 任务 2：实现免构建核心工具

**文件：**
- 新建：`docs/dev-ops/nginx/html/js/agent-ui-core.js`
- 测试：`docs/dev-ops/nginx/html/test/agent-ui-core.test.js`

- [ ] **步骤 1：实现 UMD 模块**

```javascript
(function (root, factory) {
    const api = factory();
    if (typeof module === 'object' && module.exports) {
        module.exports = api;
    }
    if (root) {
        root.AgentUiCore = api;
    }
}(typeof globalThis !== 'undefined' ? globalThis : this, function () {
    'use strict';

    function escapeHtml(value) {
        return String(value == null ? '' : value)
            .replaceAll('&', '&amp;')
            .replaceAll('<', '&lt;')
            .replaceAll('>', '&gt;')
            .replaceAll('"', '&quot;')
            .replaceAll("'", '&#39;');
    }

    function sanitizeMarkdown(markedLib, purifier, content) {
        const text = String(content == null ? '' : content);
        if (!markedLib || typeof markedLib.parse !== 'function'
                || !purifier || typeof purifier.sanitize !== 'function') {
            return escapeHtml(text);
        }
        return purifier.sanitize(markedLib.parse(text), {
            USE_PROFILES: { html: true }
        });
    }

    function createSseParser({ onEvent, onError }) {
        let buffer = '';

        function report(error, raw) {
            onError(error, raw.slice(0, 200));
        }

        function dispatch(block) {
            if (!block) return;
            const data = block.split(/\r?\n/)
                .filter((line) => line.startsWith('data:'))
                .map((line) => line.slice(5).replace(/^ /, ''))
                .join('\n')
                .trim();
            if (!data || data === '[DONE]') return;
            try {
                onEvent(JSON.parse(data));
            } catch (cause) {
                const error = new Error(`Invalid SSE JSON: ${cause.message}`);
                error.cause = cause;
                report(error, data);
            }
        }

        function drain() {
            let boundary = buffer.match(/\r?\n\r?\n/);
            while (boundary) {
                const index = boundary.index;
                const block = buffer.slice(0, index);
                buffer = buffer.slice(index + boundary[0].length);
                dispatch(block);
                boundary = buffer.match(/\r?\n\r?\n/);
            }
        }

        return {
            push(chunk) {
                buffer += String(chunk == null ? '' : chunk);
                drain();
            },
            finish() {
                if (buffer.trim()) dispatch(buffer);
                buffer = '';
            },
            reset() {
                buffer = '';
            }
        };
    }

    function classifyAgentEvent(event) {
        const type = event && event.type;
        const subType = event && event.subType;
        const isError = type === 'error' || subType === 'error';
        const isResult = type === 'final'
            || type === 'summary'
            || type === 'complete'
            || (type === 'content' && event.completed === true)
            || (type === 'supervision' && subType === 'inspection_report')
            || (type === 'trading' && ['final_decision', 'trading_complete'].includes(subType));
        const isTerminal = isError
            || type === 'final'
            || type === 'complete'
            || event.completed === true
            || (type === 'trading' && subType === 'trading_complete');
        return {
            target: isResult ? 'result' : 'thinking',
            terminal: isTerminal,
            error: isError
        };
    }

    return { createSseParser, escapeHtml, sanitizeMarkdown, classifyAgentEvent };
}));
```

- [ ] **步骤 2：运行核心测试**

运行：

```powershell
node --test docs/dev-ops/nginx/html/test/agent-ui-core.test.js
```

预期：7 项测试通过，0 项失败。

- [ ] **步骤 3：提交核心模块**

```powershell
git add docs/dev-ops/nginx/html/js/agent-ui-core.js
git commit -m "feat: add tested frontend stream utilities"
```

### 任务 3：在所有消息路径中强制执行安全渲染

**文件：**
- 修改：`docs/dev-ops/nginx/html/index.html:6-11,1272-1355,1665-1795`
- 测试：`docs/dev-ops/nginx/html/test/agent-ui-core.test.js`

- [ ] **步骤 1：在第三方渲染库之后加载核心模块**

在 `highlight.min.js` 之后添加：

```html
<script src="js/agent-ui-core.js"></script>
```

- [ ] **步骤 2：用已测试实现替换本地转义工具**

```javascript
const { createSseParser, escapeHtml, sanitizeMarkdown, classifyAgentEvent } = window.AgentUiCore;

function renderMarkdown(content) {
    return sanitizeMarkdown(window.marked, window.DOMPurify, content);
}
```

删除旧的 DOM 版 `escapeHtml` 函数，确保转义逻辑只有一份实现。

- [ ] **步骤 3：替换所有用于插入 HTML 的直接 Markdown 解析**

修改以下三种写法：

```javascript
const renderedContent = renderMarkdown(content);
const historyContent = renderMarkdown(msg.content || '');
contentDiv.innerHTML = renderMarkdown(content);
```

历史消息模板必须插入 `${historyContent}`，不能继续使用 `${marked.parse(msg.content || '')}`。`renderMarkdown` 之外不得保留任何 `marked.parse(...)` 调用。

- [ ] **步骤 4：在插入模板前转义用户内容**

在 `addMessage` 的用户消息分支开头增加：

```javascript
const safeContent = escapeHtml(content);
```

两个用户气泡都使用 `${safeContent}`。会话列表渲染中现有的 `escapeHtml(...)` 调用继续保留，但底层改为共享实现。

- [ ] **步骤 5：确认危险的直接渲染路径已消失**

运行：

```powershell
rg -n "marked\.parse|\$\{content\}" docs/dev-ops/nginx/html/index.html
node --test docs/dev-ops/nginx/html/test/agent-ui-core.test.js
```

预期：`marked.parse` 只出现在 `renderMarkdown` 内部或完全不出现；不存在未转义的用户 `${content}` 插值；全部测试通过。

- [ ] **步骤 6：提交安全渲染修改**

```powershell
git add docs/dev-ops/nginx/html/index.html
git commit -m "fix: sanitize agent and user message rendering"
```

### 任务 4：使用共享解析器替换两套有损流读取器

**文件：**
- 修改：`docs/dev-ops/nginx/html/index.html:1398-1535,1930-2040`
- 测试：`docs/dev-ops/nginx/html/test/agent-ui-core.test.js`

- [ ] **步骤 1：新增统一 JSON 事件适配器和流消费器**

在 `sendMessage` 前放置以下工具函数：

```javascript
function handleRawSseEvent(jsonData) {
    handleSSEMessage(jsonData);
}

async function consumeSseResponse(response) {
    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    const parser = createSseParser({
        onEvent: handleRawSseEvent,
        onError: (error, raw) => {
            console.warn('SSE 协议数据无法解析:', raw, error.message);
            addStageMessage('error', 'protocol_error', '收到无法解析的流式事件', null);
        }
    });

    try {
        while (true) {
            const { done, value } = await reader.read();
            if (done) break;
            parser.push(decoder.decode(value, { stream: true }));
            scrollToBottom();
        }
        parser.push(decoder.decode());
        parser.finish();
    } finally {
        reader.releaseLock();
    }
}
```

- [ ] **步骤 2：改造 `sendMessage`，等待共享消费器完成**

保留现有请求 DTO 和面板重置逻辑，但将嵌套递归的 `readStream()` 主体替换为：

```javascript
fetch(buildApiUrl('/api/v1/agent/auto_agent'), {
    method: 'POST',
    headers: {
        'Content-Type': 'application/json',
        'Accept': 'text/event-stream'
    },
    body: JSON.stringify(requestData)
})
.then(async (response) => {
    if (!response.ok) {
        throw new NetworkError('网络请求失败: ' + response.status);
    }
    currentStepMessages.clear();
    streamingMessageCache.clear();
    isConnected = true;
    await consumeSseResponse(response);
})
.catch((error) => {
    console.error('请求错误:', error);
    addStageMessage('error', null, getFriendlyErrorMessage(error), null);
})
.finally(() => closeLoadingState('general'));
```

第一批先在 `sendMessage` 上方定义临时 URL 工具；第二批再将其改为可配置实现：

```javascript
function buildApiUrl(path) {
    return `http://localhost:8090${path}`;
}
```

- [ ] **步骤 3：将 `sendTradingAnalysis` 改为使用同一消费器**

沿用相同结构，使用 `buildApiUrl('/api/v1/trading/analysis')`、现有交易请求体和 `.finally(() => closeLoadingState('trading'))`。删除第二套 `TextDecoder`、`reader.read()` 递归和逐行处理 chunk 的循环。

- [ ] **步骤 4：确认两套有损解析循环均已删除**

运行：

```powershell
rg -n "chunk\.split|function readStream|new TextDecoder" docs/dev-ops/nginx/html/index.html
```

预期：不存在 `chunk.split` 或递归 `readStream`；只在 `consumeSseResponse` 内保留一个 `new TextDecoder`。

- [ ] **步骤 5：提交共享流消费器**

```powershell
git add docs/dev-ops/nginx/html/index.html
git commit -m "fix: preserve fragmented SSE events"
```

### 任务 5：保证完成处理和面板路由幂等

**文件：**
- 修改：`docs/dev-ops/nginx/html/index.html:1500-1765`
- 测试：`docs/dev-ops/nginx/html/test/agent-ui-core.test.js`

- [ ] **步骤 1：记录请求是否已收到终止事件**

在现有连接状态旁新增：

```javascript
let terminalEventReceived = false;
```

Set it to `false` at the beginning of both send functions and in `createNewChat`.

- [ ] **步骤 2：使用事件分类驱动的逻辑替换 `handleSSEMessage`**

```javascript
function handleSSEMessage(jsonData) {
    const { type, subType, step, content = '', completed } = jsonData;
    const classification = classifyAgentEvent(jsonData);
    const cacheKey = `${type}-${subType || 'default'}-${step || ''}`;

    if (type === 'content' && completed === false) {
        let cached = streamingMessageCache.get(cacheKey);
        if (!cached) {
            cached = { div: addStageMessage(type, subType, content, step, 'thinking'), content: '' };
            streamingMessageCache.set(cacheKey, cached);
        }
        cached.content += content;
        updateMessageContent(cached.div, cached.content);
        cached.div.classList.add('streaming-message');
        return;
    }

    if (type === 'content' && completed === true) {
        const cached = streamingMessageCache.get(cacheKey);
        if (cached && cached.content.trim()) {
            addStageMessage('summary', subType, cached.content, step, 'result');
            cached.div.classList.remove('streaming-message');
        }
        streamingMessageCache.delete(cacheKey);
    } else if (content.trim()) {
        addStageMessage(type, subType, content, step, classification.target);
    }

    if (classification.terminal) {
        terminalEventReceived = true;
    }
}
```

- [ ] **步骤 3：让 `addStageMessage` 接受显式目标面板**

修改函数签名和目标选择逻辑：

```javascript
function addStageMessage(type, subType, content, step, explicitTarget) {
    const classification = classifyAgentEvent({ type, subType });
    const target = explicitTarget || classification.target;
    const targetContainer = document.getElementById(
        target === 'result' ? 'resultMessages' : 'thinkingMessages'
    );
    // 保留现有消息构造、代码高亮、追加和滚动逻辑。
}
```

- [ ] **步骤 4：删除网络流结束时合成的完成消息**

Neither `sendMessage` nor `sendTradingAnalysis` may append an unconditional `addStageMessage('complete', ...)` after `consumeSseResponse`. Business terminal events control visible completion; `.finally(...)` only restores UI state. If EOF occurs without a terminal event, add one warning before cleanup:

```javascript
if (!terminalEventReceived) {
    addStageMessage('error', 'stream_interrupted', '连接已结束，但未收到任务完成事件', null, 'thinking');
}
```

- [ ] **步骤 5：运行自动化测试和静态检查**

运行：

```powershell
node --test docs/dev-ops/nginx/html/test/agent-ui-core.test.js
rg -n "addStageMessage\('complete'|chunk\.split|marked\.parse" docs/dev-ops/nginx/html/index.html
git diff --check
```

预期：测试全部通过；不存在无条件合成完成消息、有损解析器或直接 marked 解析；`git diff --check` 无输出。

- [ ] **步骤 6：执行第一批浏览器冒烟测试**

通过现有 Nginx 配置提供静态目录并验证：

1. 通用对话能够增量流式输出，并且只生成一个最终结果。
2. 交易分析将 `final_decision` 和 `trading_complete` 放入结果面板。
3. Entering `<img src=x onerror=alert(1)>` displays text and does not execute code.
4. 后端停止后，两个发送按钮都能恢复，并显示非阻塞错误卡片。

- [ ] **步骤 7：提交第一批完成结果**

```powershell
git add docs/dev-ops/nginx/html/index.html docs/dev-ops/nginx/html/js/agent-ui-core.js docs/dev-ops/nginx/html/test/agent-ui-core.test.js
git commit -m "fix: harden agent streaming and rendering"
```

## 第一批检查点

完成任务 5 后停止。汇报自动化测试输出、四项浏览器冒烟结果、修改文件，以及观察到但尚未被 `classifyAgentEvent` 覆盖的协议事件。用户完成本批真实端到端演示前，不得开始第二批。
