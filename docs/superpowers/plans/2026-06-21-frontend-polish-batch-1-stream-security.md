# Frontend Polish Batch 1: Stream Correctness and Security Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make both Agent streams lossless across arbitrary network chunk boundaries, render all untrusted content safely, and terminate each request exactly once in the correct panel.

**Architecture:** Add one build-free UMD utility module beside the existing static assets so pure parsing, sanitizing, and event-classification logic can be tested with Node's built-in test runner. Keep the existing HTML shell and UI renderers, but route both general and trading streams through the shared module and one raw-event adapter.

**Tech Stack:** Vanilla JavaScript, Fetch streams, DOMPurify, marked, highlight.js, Node.js `node:test`, static HTML/Tailwind CSS.

---

## File map

- Create `docs/dev-ops/nginx/html/js/agent-ui-core.js`: build-free pure utilities shared by the page and Node tests.
- Create `docs/dev-ops/nginx/html/test/agent-ui-core.test.js`: parser, sanitizer, and event-classification tests.
- Modify `docs/dev-ops/nginx/html/index.html:6-11,936-1765,1980-2035`: load the module, replace both ad-hoc stream parsers, sanitize rendering, and unify completion classification.

### Task 1: Specify the shared stream and rendering contract

**Files:**
- Create: `docs/dev-ops/nginx/html/test/agent-ui-core.test.js`
- Test: `docs/dev-ops/nginx/html/test/agent-ui-core.test.js`

- [ ] **Step 1: Create failing tests for fragmented SSE, CRLF, multiline data, sanitizing, and event classification**

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

- [ ] **Step 2: Run the tests and verify the module is missing**

Run:

```powershell
node --test docs/dev-ops/nginx/html/test/agent-ui-core.test.js
```

Expected: FAIL with `Cannot find module '../js/agent-ui-core.js'`.

- [ ] **Step 3: Commit the executable contract**

```powershell
git add docs/dev-ops/nginx/html/test/agent-ui-core.test.js
git commit -m "test: define frontend stream safety contract"
```

### Task 2: Implement the build-free core utilities

**Files:**
- Create: `docs/dev-ops/nginx/html/js/agent-ui-core.js`
- Test: `docs/dev-ops/nginx/html/test/agent-ui-core.test.js`

- [ ] **Step 1: Implement the UMD module**

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

- [ ] **Step 2: Run the core tests**

Run:

```powershell
node --test docs/dev-ops/nginx/html/test/agent-ui-core.test.js
```

Expected: 7 tests PASS, 0 FAIL.

- [ ] **Step 3: Commit the core module**

```powershell
git add docs/dev-ops/nginx/html/js/agent-ui-core.js
git commit -m "feat: add tested frontend stream utilities"
```

### Task 3: Enforce safe rendering in every message path

**Files:**
- Modify: `docs/dev-ops/nginx/html/index.html:6-11,1272-1355,1665-1795`
- Test: `docs/dev-ops/nginx/html/test/agent-ui-core.test.js`

- [ ] **Step 1: Load the core module after third-party render libraries**

Add after `highlight.min.js`:

```html
<script src="js/agent-ui-core.js"></script>
```

- [ ] **Step 2: Replace the local escape helper with the tested implementation**

```javascript
const { createSseParser, escapeHtml, sanitizeMarkdown, classifyAgentEvent } = window.AgentUiCore;

function renderMarkdown(content) {
    return sanitizeMarkdown(window.marked, window.DOMPurify, content);
}
```

Delete the old DOM-based `escapeHtml` function so there is only one escaping implementation.

- [ ] **Step 3: Replace every direct Markdown parse used in HTML insertion**

Change all three forms below:

```javascript
const renderedContent = renderMarkdown(content);
const historyContent = renderMarkdown(msg.content || '');
contentDiv.innerHTML = renderMarkdown(content);
```

The history message template must interpolate `${historyContent}` instead of `${marked.parse(msg.content || '')}`. No `marked.parse(...)` call may remain outside `renderMarkdown`.

- [ ] **Step 4: Escape user content before template insertion**

At the start of the user branch in `addMessage`:

```javascript
const safeContent = escapeHtml(content);
```

Use `${safeContent}` in both user bubbles. Keep existing `escapeHtml(...)` calls in session list rendering, now backed by the shared implementation.

- [ ] **Step 5: Verify dangerous direct render paths are gone**

Run:

```powershell
rg -n "marked\.parse|\$\{content\}" docs/dev-ops/nginx/html/index.html
node --test docs/dev-ops/nginx/html/test/agent-ui-core.test.js
```

Expected: `marked.parse` appears only inside `renderMarkdown` or not at all; no unescaped user `${content}` interpolation remains; all tests PASS.

- [ ] **Step 6: Commit safe rendering**

```powershell
git add docs/dev-ops/nginx/html/index.html
git commit -m "fix: sanitize agent and user message rendering"
```

### Task 4: Replace both lossy stream readers with the shared parser

**Files:**
- Modify: `docs/dev-ops/nginx/html/index.html:1398-1535,1930-2040`
- Test: `docs/dev-ops/nginx/html/test/agent-ui-core.test.js`

- [ ] **Step 1: Add one JSON event adapter and one stream consumer**

Place these helpers before `sendMessage`:

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

- [ ] **Step 2: Convert `sendMessage` to await the shared consumer**

Keep the existing request DTO and panel reset, but replace the nested recursive `readStream()` body with:

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

For Batch 1, define the temporary URL helper immediately above `sendMessage`; Batch 2 will make it configurable:

```javascript
function buildApiUrl(path) {
    return `http://localhost:8090${path}`;
}
```

- [ ] **Step 3: Convert `sendTradingAnalysis` to the same consumer**

Use the same structure with `buildApiUrl('/api/v1/trading/analysis')`, the existing trading request body, and `.finally(() => closeLoadingState('trading'))`. Remove the second `TextDecoder`, `reader.read()` recursion, and chunk-by-line loop.

- [ ] **Step 4: Verify both lossy parsing loops are removed**

Run:

```powershell
rg -n "chunk\.split|function readStream|new TextDecoder" docs/dev-ops/nginx/html/index.html
```

Expected: no `chunk.split` or recursive `readStream`; exactly one `new TextDecoder` remains inside `consumeSseResponse`.

- [ ] **Step 5: Commit the shared stream consumer**

```powershell
git add docs/dev-ops/nginx/html/index.html
git commit -m "fix: preserve fragmented SSE events"
```

### Task 5: Make completion and panel routing idempotent

**Files:**
- Modify: `docs/dev-ops/nginx/html/index.html:1500-1765`
- Test: `docs/dev-ops/nginx/html/test/agent-ui-core.test.js`

- [ ] **Step 1: Track whether a request has already received a terminal event**

Add beside the existing connection state:

```javascript
let terminalEventReceived = false;
```

Set it to `false` at the beginning of both send functions and in `createNewChat`.

- [ ] **Step 2: Replace `handleSSEMessage` with classification-driven logic**

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

- [ ] **Step 3: Let `addStageMessage` accept an explicit target**

Change the signature and target selection:

```javascript
function addStageMessage(type, subType, content, step, explicitTarget) {
    const classification = classifyAgentEvent({ type, subType });
    const target = explicitTarget || classification.target;
    const targetContainer = document.getElementById(
        target === 'result' ? 'resultMessages' : 'thinkingMessages'
    );
    // Keep the existing message construction, highlighting, append and scroll code.
}
```

- [ ] **Step 4: Remove synthetic completion messages from network EOF**

Neither `sendMessage` nor `sendTradingAnalysis` may append an unconditional `addStageMessage('complete', ...)` after `consumeSseResponse`. Business terminal events control visible completion; `.finally(...)` only restores UI state. If EOF occurs without a terminal event, add one warning before cleanup:

```javascript
if (!terminalEventReceived) {
    addStageMessage('error', 'stream_interrupted', '连接已结束，但未收到任务完成事件', null, 'thinking');
}
```

- [ ] **Step 5: Run automated and static checks**

Run:

```powershell
node --test docs/dev-ops/nginx/html/test/agent-ui-core.test.js
rg -n "addStageMessage\('complete'|chunk\.split|marked\.parse" docs/dev-ops/nginx/html/index.html
git diff --check
```

Expected: tests PASS; no unconditional synthetic completion, lossy parser, or direct marked parse remains; `git diff --check` prints nothing.

- [ ] **Step 6: Perform the Batch 1 browser smoke test**

Serve the static directory through the existing Nginx setup and verify:

1. General chat streams incrementally and produces one final result.
2. Trading analysis places `final_decision` and `trading_complete` in the result panel.
3. Entering `<img src=x onerror=alert(1)>` displays text and does not execute code.
4. A stopped backend restores both send buttons and shows a non-blocking error card.

- [ ] **Step 7: Commit Batch 1 completion**

```powershell
git add docs/dev-ops/nginx/html/index.html docs/dev-ops/nginx/html/js/agent-ui-core.js docs/dev-ops/nginx/html/test/agent-ui-core.test.js
git commit -m "fix: harden agent streaming and rendering"
```

## Batch 1 checkpoint

Stop after Task 5. Report automated-test output, the four browser smoke-test results, files changed, and any protocol event observed but not covered by `classifyAgentEvent`. Do not begin Batch 2 until the user has completed the real end-to-end demonstration for this batch.
