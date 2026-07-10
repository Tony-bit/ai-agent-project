# 前端产品化打磨第一批：流式正确性与安全实施计划

> **供智能体执行者使用：** 必须使用 `superpowers:subagent-driven-development`（推荐）或 `superpowers:executing-plans` 子技能逐项执行本计划。所有步骤使用复选框（`- [ ]`）跟踪状态。

**目标：** 保证两类 Agent 流在任意网络分块边界下都不丢失事件，安全渲染正文与协议元数据，并让每条流只识别和展示一个终止事件。

**架构：** 在现有静态资源旁新增一个无需构建的 UMD 工具模块，使解析、协议校验、清洗和事件分类逻辑能够使用 Node 内置测试运行器验证。保留现有 HTML 外壳和 UI 渲染器，但让通用流与交易流统一经过共享模块和流级原始事件适配器；流式消息完成与请求终止使用两套独立语义。

**技术栈：** 原生 JavaScript、Fetch 流、DOMPurify、marked、highlight.js、Node.js `node:test`、静态 HTML/Tailwind CSS。

---

## 文件结构

- 新建 `docs/dev-ops/nginx/html/js/agent-ui-core.js`：供页面和 Node 测试共享的解析、响应校验、事件规范化、清洗与分类工具。
- 新建 `docs/dev-ops/nginx/html/test/agent-ui-core.test.js`：解析器、响应校验、事件规范化、清洗器和协议矩阵测试。
- 新建 `docs/dev-ops/nginx/html/test/agent-ui-security-smoke.html`：在真实 marked、DOMPurify 和浏览器 DOM 中执行安全渲染测试。
- 修改 `docs/dev-ops/nginx/html/index.html:6-11,936-1765,1980-2035`：加载模块、替换两套临时流解析器、清洗渲染内容并统一完成事件分类。

## 本批协议与安全边界

实现前先固定以下契约，后续代码和测试不得自行扩大 `completed` 的含义：

| 事件 | 目标面板 | 消息段完成 | 请求终止 | 结果 |
| --- | --- | --- | --- | --- |
| `content, completed=false` | thinking | 否 | 否 | 增量内容 |
| `content, completed=true` | result | 是 | 否 | 将已缓存内容固化为最终消息 |
| `summary`、`final`、`final_decision` | result | 否 | 否 | 结果内容，不负责关闭请求 |
| `complete` | result | 否 | 是 | completed |
| `trading/trading_complete` | result | 否 | 是 | completed |
| `error` 或 `subType=error` | thinking | 否 | 是 | failed |

补充约束：

1. `completed=true` 默认只表示当前消息或阶段完成，不得作为通用请求终止条件。
2. 本批在输入校验通过后、调用 Fetch 前立即设置现有 `isConnected=true`，关闭响应头返回前的重复提交窗口；每条流使用自己的 `streamState` 保存消息缓存和终止状态。
3. 首个请求终止事件生效后，当前流的后续事件全部忽略并记录有限诊断。
4. `content` 是唯一允许按 Markdown 渲染的协议字段。`type`、`subType`、`step`、`model`、错误文本和其他元数据均按纯文本处理。
5. 未知但格式合法的事件可使用固定“未知事件”标签降级展示，不得把原始协议字段直接拼入 HTML。
6. 单个未分隔 SSE 事件的文本缓冲上限为 1 MiB；超限必须失败并释放请求状态，不能继续无限累积。

## 与后续批次的职责边界

- 本批不实现 `AbortController`、取消按钮、可复用生命周期状态机、运行配置或 API Base URL 解析；这些只在 `2026-06-21-frontend-polish-batch-2-lifecycle-config.md` 实现。
- 第二批必须在本批 `streamState` 语义之上增加请求 token，解决“取消旧请求后立即启动新请求”时旧 `finally` 误结束新请求的 ABA 竞态；本批不复制该状态机。
- 本批只做针对流与 XSS 的小范围浏览器冒烟。滚动策略、长内容渲染性能、响应式布局、可访问性和完整 TC/AC 回归矩阵只在 `2026-06-21-frontend-polish-batch-3-ux-regression.md` 实现。
- 第三批可以扩充可读事件标签，但不得恢复把未知 `type/subType` 原值写入 `innerHTML` 的兜底逻辑。

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
    normalizeAgentEvent,
    classifyAgentEvent,
    validateSseResponse
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

test('createSseParser accepts CR-only boundaries and drains multiple events', () => {
    const events = [];
    const parser = createSseParser({ onEvent: (event) => events.push(event), onError: assert.fail });

    parser.push('data: {"type":"analysis"}\r\rdata: {"type":"complete"}\n\n');

    assert.deepEqual(events, [{ type: 'analysis' }, { type: 'complete' }]);
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

test('createSseParser does not relabel consumer failures as JSON failures', () => {
    const protocolErrors = [];
    const parser = createSseParser({
        onEvent: () => { throw new Error('render failed'); },
        onError: (error) => protocolErrors.push(error)
    });

    assert.throws(() => parser.push('data: {"type":"content"}\n\n'), /render failed/);
    assert.deepEqual(protocolErrors, []);
});

test('createSseParser rejects an oversized unterminated event', () => {
    const errors = [];
    const parser = createSseParser({
        onEvent: assert.fail,
        onError: (error) => errors.push(error),
        maxBufferLength: 16
    });

    assert.throws(() => parser.push('data: 12345678901234567'), /exceeds 16 characters/);
    assert.equal(errors.length, 1);
});

test('createSseParser limits each event rather than the aggregate chunk', () => {
    const events = [];
    const parser = createSseParser({
        onEvent: (event) => events.push(event),
        onError: assert.fail,
        maxBufferLength: 20
    });

    parser.push('data: {"a":1}\n\ndata: {"b":2}\n\n');
    assert.deepEqual(events, [{ a: 1 }, { b: 2 }]);
});

test('escapeHtml neutralizes user supplied markup', () => {
    assert.equal(
        escapeHtml('<img src=x onerror=alert(1)>'),
        '&lt;img src=x onerror=alert(1)&gt;'
    );
});

test('sanitizeMarkdown applies a narrow Markdown policy and falls back to escaped text', () => {
    let receivedOptions;
    const markedStub = { parse: (value) => `<p>${value}</p><script>alert(1)</script>` };
    const purifierStub = {
        sanitize(value, options) {
            receivedOptions = options;
            return value.replace(/<script>[\s\S]*?<\/script>/g, '');
        }
    };

    assert.equal(sanitizeMarkdown(markedStub, purifierStub, '**safe**'), '<p>**safe**</p>');
    assert.ok(receivedOptions.ALLOWED_TAGS.includes('code'));
    assert.equal(receivedOptions.ALLOWED_TAGS.includes('img'), false);
    assert.deepEqual(receivedOptions.ALLOWED_ATTR, ['href', 'title']);
    assert.equal(receivedOptions.ALLOW_UNKNOWN_PROTOCOLS, false);
    assert.equal(sanitizeMarkdown(null, null, '<b>plain</b>'), '&lt;b&gt;plain&lt;/b&gt;');
});

test('normalizeAgentEvent validates shape and normalizes protocol metadata', () => {
    assert.deepEqual(normalizeAgentEvent({
        type: 'future_event', subType: 'v2', step: 2, content: 'ok', completed: true
    }), {
        type: 'future_event', subType: 'v2', step: 2, content: 'ok', completed: true
    });
    assert.throws(() => normalizeAgentEvent(null), /plain object/);
    assert.throws(() => normalizeAgentEvent({ type: 'content', content: { unsafe: true } }), /content must be a string/);
    assert.throws(() => normalizeAgentEvent({ type: '<img>', content: 'x' }), /type is invalid/);
});

test('classifyAgentEvent separates message completion from request termination', () => {
    assert.deepEqual(classifyAgentEvent({ type: 'content', completed: false }), {
        target: 'thinking', messageCompleted: false, requestTerminal: false, outcome: null, error: false
    });
    assert.deepEqual(classifyAgentEvent({ type: 'content', completed: true }), {
        target: 'result', messageCompleted: true, requestTerminal: false, outcome: null, error: false
    });
    assert.deepEqual(classifyAgentEvent({ type: 'final', subType: 'final_decision', completed: false }), {
        target: 'result', messageCompleted: false, requestTerminal: false, outcome: null, error: false
    });
    assert.deepEqual(classifyAgentEvent({ type: 'trading', subType: 'trading_complete', completed: true }), {
        target: 'result', messageCompleted: false, requestTerminal: true, outcome: 'completed', error: false
    });
    assert.deepEqual(classifyAgentEvent({ type: 'error', completed: true }), {
        target: 'thinking', messageCompleted: false, requestTerminal: true, outcome: 'failed', error: true
    });
});

test('validateSseResponse rejects success responses that are not readable SSE', () => {
    const valid = {
        ok: true,
        status: 200,
        headers: { get: () => 'text/event-stream; charset=UTF-8' },
        body: { getReader() {} }
    };
    assert.equal(validateSseResponse(valid), valid);
    assert.throws(() => validateSseResponse({ ...valid, headers: { get: () => 'application/json' } }), /Expected text\/event-stream/);
    assert.throws(() => validateSseResponse({ ...valid, body: null }), /readable response body/);
    assert.throws(() => validateSseResponse({ ...valid, ok: false, status: 503 }), /HTTP 503/);
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
            ALLOWED_TAGS: [
                'p', 'br', 'hr', 'strong', 'em', 'del', 'blockquote',
                'ul', 'ol', 'li', 'pre', 'code',
                'h1', 'h2', 'h3', 'h4', 'h5', 'h6',
                'a', 'table', 'thead', 'tbody', 'tr', 'th', 'td'
            ],
            ALLOWED_ATTR: ['href', 'title'],
            ALLOW_UNKNOWN_PROTOCOLS: false
        });
    }

    function createSseParser({ onEvent, onError, maxBufferLength = 1024 * 1024 }) {
        let buffer = '';

        function report(error, raw) {
            onError(error, raw.slice(0, 200));
        }

        function dispatch(block) {
            if (!block) return;
            const data = block.split(/\r\n|\r|\n/)
                .filter((line) => line.startsWith('data:'))
                .map((line) => line.slice(5).replace(/^ /, ''))
                .join('\n')
                .trim();
            if (!data || data === '[DONE]') return;
            let event;
            try {
                event = JSON.parse(data);
            } catch (cause) {
                const error = new Error(`Invalid SSE JSON: ${cause.message}`);
                error.cause = cause;
                report(error, data);
                return;
            }
            onEvent(event);
        }

        function drain() {
            let boundary = buffer.match(/(?:\r\n|\r|\n){2}/);
            while (boundary) {
                const index = boundary.index;
                if (index > maxBufferLength) {
                    const raw = buffer.slice(0, index);
                    buffer = '';
                    const error = new Error(`SSE buffer exceeds ${maxBufferLength} characters`);
                    report(error, raw);
                    throw error;
                }
                const block = buffer.slice(0, index);
                buffer = buffer.slice(index + boundary[0].length);
                dispatch(block);
                boundary = buffer.match(/(?:\r\n|\r|\n){2}/);
            }
            if (buffer.length > maxBufferLength) {
                const raw = buffer;
                buffer = '';
                const error = new Error(`SSE buffer exceeds ${maxBufferLength} characters`);
                report(error, raw);
                throw error;
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

    const PROTOCOL_TOKEN = /^[a-z][a-z0-9_-]{0,63}$/;

    function normalizeAgentEvent(input) {
        if (!input || typeof input !== 'object' || Array.isArray(input)) {
            throw new TypeError('Agent event must be a plain object');
        }
        if (!PROTOCOL_TOKEN.test(input.type || '')) {
            throw new TypeError('Agent event type is invalid');
        }
        if (input.subType != null && !PROTOCOL_TOKEN.test(input.subType)) {
            throw new TypeError('Agent event subType is invalid');
        }
        if (input.content != null && typeof input.content !== 'string') {
            throw new TypeError('Agent event content must be a string');
        }
        const step = Number.isInteger(input.step) && input.step >= 0 && input.step <= 10000
            ? input.step : null;
        return {
            type: input.type,
            subType: input.subType || null,
            step,
            content: input.content || '',
            completed: input.completed === true
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
        const requestTerminal = isError
            || type === 'complete'
            || (type === 'trading' && subType === 'trading_complete');
        return {
            target: isResult ? 'result' : 'thinking',
            messageCompleted: type === 'content' && event.completed === true,
            requestTerminal,
            outcome: requestTerminal ? (isError ? 'failed' : 'completed') : null,
            error: isError
        };
    }

    function validateSseResponse(response) {
        if (!response || !response.ok) {
            const status = response && response.status != null ? response.status : 'unknown';
            throw new Error(`HTTP ${status}`);
        }
        const contentType = response.headers && typeof response.headers.get === 'function'
            ? response.headers.get('content-type') || '' : '';
        if (!contentType.toLowerCase().includes('text/event-stream')) {
            throw new Error(`Expected text/event-stream but received ${contentType || 'an empty Content-Type'}`);
        }
        if (!response.body || typeof response.body.getReader !== 'function') {
            throw new Error('SSE response requires a readable response body');
        }
        return response;
    }

    return {
        createSseParser,
        escapeHtml,
        sanitizeMarkdown,
        normalizeAgentEvent,
        classifyAgentEvent,
        validateSseResponse
    };
}));
```

- [ ] **步骤 2：运行核心测试**

运行：

```powershell
node --test docs/dev-ops/nginx/html/test/agent-ui-core.test.js
```

预期：全部解析、协议校验、清洗和事件分类测试通过，0 项失败。

- [ ] **步骤 3：提交核心模块**

```powershell
git add docs/dev-ops/nginx/html/js/agent-ui-core.js
git commit -m "feat: add tested frontend stream utilities"
```

### 任务 3：在所有消息路径中强制执行安全渲染

**文件：**
- 修改：`docs/dev-ops/nginx/html/index.html:6-11,1272-1355,1665-1795`
- 测试：`docs/dev-ops/nginx/html/test/agent-ui-core.test.js`
- 新建：`docs/dev-ops/nginx/html/test/agent-ui-security-smoke.html`

- [ ] **步骤 1：在第三方渲染库之后加载核心模块**

在 `highlight.min.js` 之后添加：

```html
<script src="js/agent-ui-core.js"></script>
```

- [ ] **步骤 2：用已测试实现替换本地转义工具**

```javascript
const {
    createSseParser,
    escapeHtml,
    sanitizeMarkdown,
    normalizeAgentEvent,
    classifyAgentEvent,
    validateSseResponse
} = window.AgentUiCore;

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

- [ ] **步骤 5：清理所有非 Markdown 元数据插值**

历史消息元数据必须先转义：

```javascript
const modelInfo = msg.model
    ? `<span class="text-[10px] text-gray-400 ml-2">${escapeHtml(String(msg.model))}</span>`
    : '';
const latencyInfo = Number.isFinite(Number(msg.latencyMs))
    ? `<span class="text-[10px] text-gray-400 ml-2">${Number(msg.latencyMs)}ms</span>`
    : '';
```

阶段消息只允许使用内部映射值；未知值显示固定标签，不回显原始协议字段：

```javascript
const stageInfo = stageTypeMap[type] || {
    name: '未知事件', icon: '📝', class: 'stage-analysis'
};
const subTypeName = subType ? (subTypeMap[subType] || '未知阶段') : '';
const safeStep = Number.isInteger(step) ? String(step) : '';
```

插入 `subTypeName`、`safeStep`、捕获到的 `error.message` 及其他动态元数据时继续调用 `escapeHtml`。CSS 类名只能来自页面内固定映射，不能使用事件字段生成。

- [ ] **步骤 6：创建真实浏览器清洗冒烟页**

创建 `docs/dev-ops/nginx/html/test/agent-ui-security-smoke.html`：

```html
<!doctype html>
<meta charset="utf-8">
<title>Agent UI security smoke</title>
<script src="../js/marked.min.js"></script>
<script src="../js/purify.min.js"></script>
<script src="../js/agent-ui-core.js"></script>
<div id="sandbox"></div>
<pre id="result">running</pre>
<script>
    const { sanitizeMarkdown } = window.AgentUiCore;
    const sandbox = document.getElementById('sandbox');
    const dirty = [
        '<img src=x onerror="window.__xss=1">',
        '<script>window.__xss=1<\/script>',
        '<style>body{display:none}</style>',
        '[bad](javascript:window.__xss=1)',
        '<form><input autofocus onfocus="window.__xss=1"></form>'
    ].join('\n\n');
    window.__xss = 0;
    sandbox.innerHTML = sanitizeMarkdown(window.marked, window.DOMPurify, dirty);
    const forbidden = sandbox.querySelector('script,img,style,form,input,[onerror],[onfocus]');
    const dangerousHref = Array.from(sandbox.querySelectorAll('[href]'))
        .some((node) => /^javascript:/i.test(node.getAttribute('href') || ''));
    if (window.__xss !== 0 || forbidden || dangerousHref) {
        throw new Error('Unsafe Markdown survived sanitization');
    }
    document.getElementById('result').textContent = 'PASS';
</script>
```

通过现有 Nginx 静态目录访问 `/test/agent-ui-security-smoke.html`，页面必须显示 `PASS`，控制台不得出现未捕获异常。

- [ ] **步骤 7：确认危险的直接渲染路径已消失**

运行：

```powershell
rg -n "marked\.parse|\$\{content\}|name: type|subTypeMap\[subType\] \|\| subType" docs/dev-ops/nginx/html/index.html
node --test docs/dev-ops/nginx/html/test/agent-ui-core.test.js
```

预期：`marked.parse` 只出现在 `renderMarkdown` 内部或完全不出现；不存在未转义的用户 `${content}` 插值；不存在回显原始未知事件名称的兜底；全部测试通过。

- [ ] **步骤 8：提交安全渲染修改**

```powershell
git add docs/dev-ops/nginx/html/index.html docs/dev-ops/nginx/html/test/agent-ui-security-smoke.html
git commit -m "fix: sanitize agent and user message rendering"
```

### 任务 4：使用共享解析器替换两套有损流读取器

**文件：**
- 修改：`docs/dev-ops/nginx/html/index.html:1398-1535,1930-2040`
- 测试：`docs/dev-ops/nginx/html/test/agent-ui-core.test.js`

- [ ] **步骤 1：新增统一 JSON 事件适配器和流消费器**

在 `sendMessage` 前放置以下工具函数：

```javascript
function createStreamState(mode) {
    return {
        mode,
        terminalSeen: false,
        outcome: null,
        protocolErrors: 0,
        streamingCache: new Map()
    };
}

function reportProtocolError(streamState, error, raw) {
    streamState.protocolErrors += 1;
    streamState.outcome = 'failed';
    console.warn('SSE 协议数据无法解析:', String(raw || '').slice(0, 200), error.message);
    if (streamState.protocolErrors === 1) {
        addStageMessage('error', 'protocol_error', '收到无法解析的流式事件', null, 'thinking');
    }
}

function handleRawSseEvent(streamState, rawEvent) {
    let event;
    try {
        event = normalizeAgentEvent(rawEvent);
    } catch (error) {
        reportProtocolError(streamState, error, JSON.stringify(rawEvent));
        return;
    }
    handleSSEMessage(streamState, event);
}

async function consumeSseResponse(response, streamState) {
    validateSseResponse(response);
    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    const parser = createSseParser({
        onEvent: (event) => handleRawSseEvent(streamState, event),
        onError: (error, raw) => reportProtocolError(streamState, error, raw)
    });

    try {
        while (true) {
            const { done, value } = await reader.read();
            if (done) break;
            parser.push(decoder.decode(value, { stream: true }));
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
if (isConnected) {
    alert('正在处理中，请稍候...');
    return;
}
isConnected = true;
const streamState = createStreamState('general');

fetch(buildApiUrl('/api/v1/agent/auto_agent'), {
    method: 'POST',
    headers: {
        'Content-Type': 'application/json',
        'Accept': 'text/event-stream'
    },
    body: JSON.stringify(requestData)
})
.then(async (response) => {
    currentStepMessages.clear();
    await consumeSseResponse(response, streamState);
    if (!streamState.terminalSeen) {
        streamState.outcome = 'failed';
        addStageMessage('error', 'stream_interrupted',
            '连接已结束，但未收到任务完成事件', null, 'thinking');
    }
})
.catch((error) => {
    console.error('请求错误:', error);
    streamState.outcome = 'failed';
    if (streamState.protocolErrors === 0) {
        addStageMessage('error', null, getFriendlyErrorMessage(error), null, 'thinking');
    }
})
.finally(() => closeLoadingState('general'));
```

`isConnected=true` 必须位于输入校验和现有重复提交判断之后、Fetch 之前。第二批会用可取消且带 token 的生命周期替换这段最小锁定逻辑；本批不得提前复制第二批状态机。

第一批先在 `sendMessage` 上方定义临时 URL 工具；第二批再将其改为可配置实现：

```javascript
function buildApiUrl(path) {
    return `http://localhost:8090${path}`;
}
```

- [ ] **步骤 3：将 `sendTradingAnalysis` 改为使用同一消费器**

沿用相同结构，创建独立的 `createStreamState('trading')`，使用 `buildApiUrl('/api/v1/trading/analysis')`、现有交易请求体和 `.finally(() => closeLoadingState('trading'))`。删除第二套 `TextDecoder`、`reader.read()` 递归和逐行处理 chunk 的循环。

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

- [ ] **步骤 1：删除全局终止标记和流式消息缓存**

不得新增全局 `terminalEventReceived`，也不得继续让两类流共享 `streamingMessageCache`。终止标记和流式缓存已经由任务 4 的 `streamState` 持有；`createNewChat` 不得重置正在运行的流状态。新建会话、切换会话和模式时取消旧请求的行为留给第二批 `AbortController` 生命周期实现。

- [ ] **步骤 2：使用事件分类驱动的逻辑替换 `handleSSEMessage`**

```javascript
function handleSSEMessage(streamState, event) {
    if (streamState.terminalSeen) {
        console.warn('忽略终止事件后的 SSE 数据:', event.type, event.subType || '');
        return;
    }

    const { type, subType, step, content, completed } = event;
    const classification = classifyAgentEvent(event);
    const cacheKey = `${type}-${subType || 'default'}-${step == null ? '' : step}`;

    if (classification.requestTerminal) {
        const protocolAlreadyFailed = streamState.outcome === 'failed';
        streamState.terminalSeen = true;
        if (streamState.outcome !== 'failed') {
            streamState.outcome = classification.outcome;
        }
        if (protocolAlreadyFailed && classification.outcome === 'completed') {
            console.warn('协议已失败，忽略后续成功终止事件');
            return;
        }
    }

    if (type === 'content' && completed === false) {
        let cached = streamState.streamingCache.get(cacheKey);
        if (!cached) {
            cached = { div: addStageMessage(type, subType, content, step, 'thinking'), content: '' };
            streamState.streamingCache.set(cacheKey, cached);
        }
        cached.content += content;
        updateMessageContent(cached.div, cached.content);
        cached.div.classList.add('streaming-message');
        return;
    }

    if (type === 'content' && completed === true) {
        const cached = streamState.streamingCache.get(cacheKey);
        const finalContent = cached && cached.content ? cached.content : content;
        if (finalContent.trim()) {
            addStageMessage('summary', subType, finalContent, step, 'result');
        }
        if (cached) {
            cached.div.classList.remove('streaming-message');
        }
        streamState.streamingCache.delete(cacheKey);
    } else if (content.trim() || classification.error) {
        const visibleContent = content.trim() ? content : '任务执行失败';
        addStageMessage(type, subType, visibleContent, step, classification.target);
    }
}
```

这里必须覆盖三条后端真实序列：`content(completed=true) → complete`、`final_decision → final_completed → trading_complete`、`error → complete`。前两条只在显式请求终止事件到达时结束；第三条只展示首次 `error`，忽略其后的 `complete`。

- [ ] **步骤 3：让 `addStageMessage` 接受显式目标面板**

修改函数签名和目标选择逻辑：

```javascript
function addStageMessage(type, subType, content, step, explicitTarget) {
    const classification = classifyAgentEvent({ type, subType });
    const target = explicitTarget || classification.target;
    const targetContainer = document.getElementById(
        target === 'result' ? 'resultMessages' : 'thinkingMessages'
    );
    const stageInfo = stageTypeMap[type] || {
        name: '未知事件', icon: '📝', class: 'stage-analysis'
    };
    const subTypeName = subType ? (subTypeMap[subType] || '未知阶段') : '';
    const safeStep = Number.isInteger(step) ? escapeHtml(String(step)) : '';
    // 正文只使用 renderMarkdown；stageInfo/subTypeName/safeStep 均按纯文本插入。
}
```

- [ ] **步骤 4：删除网络流结束时合成的完成消息**

`sendMessage` 和 `sendTradingAnalysis` 都不得在 `consumeSseResponse` 之后无条件追加 `addStageMessage('complete', ...)`。业务终止事件控制可见完成状态；EOF 无终止事件的警告已在任务 4 的正常读取分支中处理，网络异常只走 `.catch(...)`，不得在 `.finally(...)` 再追加第二张错误卡片。

- [ ] **步骤 5：运行自动化测试和静态检查**

运行：

```powershell
node --test docs/dev-ops/nginx/html/test/agent-ui-core.test.js
rg -n "addStageMessage\('complete'|chunk\.split|marked\.parse|terminalEventReceived|name: type" docs/dev-ops/nginx/html/index.html
git diff --check
```

预期：测试全部通过；不存在无条件合成完成消息、有损解析器、全局终止标记、原始事件名兜底或直接 marked 解析；`git diff --check` 无输出。

- [ ] **步骤 6：执行第一批浏览器冒烟测试**

通过现有 Nginx 配置提供静态目录并验证：

1. 通用对话按 `content completed → complete` 顺序输出，只固化一次正文并只展示一次终止状态。
2. 交易分析将 `final_decision`、`final_completed` 和 `trading_complete` 放入结果面板，但只把 `trading_complete` 视为请求终止。
3. 人工发送 `error → complete` 时只展示错误终止，不再追加成功完成卡片。
4. `/test/agent-ui-security-smoke.html` 显示 `PASS`。
5. 用户输入和历史消息中的 `<img src=x onerror=alert(1)>` 只显示文本，不执行代码。
6. SSE 的恶意 `type/subType/step` 和历史消息 `model` 不生成可执行标签。
7. 返回 HTTP 200 JSON、204 空响应或中途断流时恢复按钮并只显示一个错误结果。
8. 在响应头返回前快速再次提交，不会启动第二个 Fetch；完整取消与立即重试留到第二批验证。

- [ ] **步骤 7：提交第一批完成结果**

```powershell
git add docs/dev-ops/nginx/html/index.html docs/dev-ops/nginx/html/js/agent-ui-core.js docs/dev-ops/nginx/html/test/agent-ui-core.test.js docs/dev-ops/nginx/html/test/agent-ui-security-smoke.html
git commit -m "fix: harden agent streaming and rendering"
```

## 第一批检查点

完成任务 5 后停止。汇报自动化测试输出、八项浏览器冒烟结果、修改文件、协议错误数量，以及观察到但尚未被 `classifyAgentEvent` 覆盖的事件。不要在本批实现取消、运行配置、滚动优化或完整回归文档；用户完成本批真实端到端演示前不得开始第二批。
