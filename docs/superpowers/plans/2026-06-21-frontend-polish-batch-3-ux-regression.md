# Frontend Polish Batch 3: UX, Accessibility, and Regression Delivery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Finish the existing Agent page as a resilient, readable product on desktop and narrow screens, then deliver a traceable regression document for all existing capabilities.

**Architecture:** Keep the existing page structure and add only small CSS/ARIA refinements plus one tested scroll-decision helper. Preserve user control while streaming by auto-scrolling only when the reader was already near the bottom, and document verification across normal, abnormal, boundary, and regression scenarios.

**Tech Stack:** Vanilla JavaScript, static HTML/Tailwind CSS, Node.js `node:test`, Markdown test documentation, Maven/JUnit regression suite.

---

## Prerequisite

Batches 1 and 2 must be committed and accepted. Their Node tests and real end-to-end checkpoints must pass before this plan starts.

## File map

- Modify `docs/dev-ops/nginx/html/js/agent-ui-core.js`: add one pure near-bottom decision helper.
- Modify `docs/dev-ops/nginx/html/test/agent-ui-core.test.js`: cover scroll thresholds and unknown events.
- Modify `docs/dev-ops/nginx/html/index.html:77-620,627-935,975-1020,1385-1810,2070-2100`: responsive layout, safe scroll behavior, accessible state announcements, readable fallback labels, and stable empty/error states.
- Create `docs/superpowers/test/2026-06-21-frontend-product-polish-test.md`: executable four-layer regression and acceptance document.
- Modify `docs/demo/README.md`: add front-end verification sequence and failure recovery notes without embedding Query values in product code.

### Task 1: Specify user-controlled auto-scroll and unknown-event fallback

**Files:**
- Modify: `docs/dev-ops/nginx/html/test/agent-ui-core.test.js`
- Test: `docs/dev-ops/nginx/html/test/agent-ui-core.test.js`

- [ ] **Step 1: Append scroll-threshold and fallback tests**

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
        target: 'thinking', terminal: false, error: false
    });
});
```

- [ ] **Step 2: Run tests and verify `isNearBottom` is missing**

Run:

```powershell
node --test docs/dev-ops/nginx/html/test/agent-ui-core.test.js
```

Expected: FAIL because `isNearBottom` is not exported.

- [ ] **Step 3: Implement and export `isNearBottom`**

```javascript
function isNearBottom({ scrollTop, scrollHeight, clientHeight }, threshold = 80) {
    if (scrollHeight <= clientHeight) return true;
    return scrollHeight - scrollTop - clientHeight <= threshold;
}
```

Add it to the module export object:

```javascript
return {
    createSseParser,
    escapeHtml,
    sanitizeMarkdown,
    classifyAgentEvent,
    resolveRuntimeConfig,
    buildApiUrl,
    createRequestLifecycle,
    isNearBottom
};
```

- [ ] **Step 4: Run all core tests and commit**

```powershell
node --test docs/dev-ops/nginx/html/test/agent-ui-core.test.js
git add docs/dev-ops/nginx/html/js/agent-ui-core.js docs/dev-ops/nginx/html/test/agent-ui-core.test.js
git commit -m "feat: preserve reader position during streaming"
```

Expected: all tests PASS and the commit succeeds.

### Task 2: Preserve reader scroll position while streaming

**Files:**
- Modify: `docs/dev-ops/nginx/html/index.html:1385-1400,1500-1810`

- [ ] **Step 1: Import the tested helper**

Add `isNearBottom` to the existing `window.AgentUiCore` destructuring.

- [ ] **Step 2: Replace unconditional scrolling with intent-aware helpers**

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

- [ ] **Step 3: Capture pin state before DOM growth**

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

Remove the unconditional `scrollToBottom(targetContainer)` calls at the end of these functions. Keep `scrollToBottom(container, true)` for a newly submitted user message and initial session-history load.

- [ ] **Step 4: Verify streaming no longer steals history review**

Manual check:

1. Start a response long enough to scroll.
2. Scroll the thinking panel upward while tokens continue.
3. Confirm the panel stays at the chosen history position.
4. Scroll back within 80px of the bottom.
5. Confirm subsequent tokens keep the panel pinned to the bottom.

- [ ] **Step 5: Commit scroll behavior**

```powershell
git add docs/dev-ops/nginx/html/index.html
git commit -m "fix: avoid stealing scroll during agent streaming"
```

### Task 3: Refine responsive layout and long-content rendering

**Files:**
- Modify: `docs/dev-ops/nginx/html/index.html:77-620,627-935`

- [ ] **Step 1: Add stable IDs for responsive layout without changing business structure**

Change the outer workspace and panel row:

```html
<div id="agentWorkspace" class="flex-1 flex overflow-hidden p-3 gap-3">
...
<div id="agentPanels" class="flex-1 flex overflow-hidden">
```

- [ ] **Step 2: Add long-content and focus styles**

Append to the existing style block:

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

- [ ] **Step 3: Add one narrow-screen layout breakpoint**

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

- [ ] **Step 4: Verify desktop and narrow layouts**

Check at 1280×720 and 390×844:

1. Navigation buttons remain visible.
2. Session history is reachable.
3. Thinking and result panels are both readable.
4. General and trading inputs are not covered.
5. Markdown tables and code blocks scroll horizontally without widening the page.

- [ ] **Step 5: Commit responsive refinements**

```powershell
git add docs/dev-ops/nginx/html/index.html
git commit -m "style: polish agent workspace responsiveness"
```

### Task 4: Normalize states, event labels, and accessibility

**Files:**
- Modify: `docs/dev-ops/nginx/html/index.html:627-935,975-1020,1500-1765,2070-2100`

- [ ] **Step 1: Add accessible live-region semantics**

Apply these attributes:

```html
<div id="loading" class="hidden mb-3" role="status" aria-live="polite" aria-atomic="true">
<div id="thinkingMessages" ... role="log" aria-live="polite" aria-relevant="additions text">
<div id="resultMessages" ... role="log" aria-live="polite" aria-relevant="additions text">
<span id="thinkingStatusLabel" ... aria-live="polite">待命</span>
<span id="resultStatusLabel" ... aria-live="polite">就绪</span>
```

Add `type="button"` to `newChatBtn`, `clearAllChatsBtn`, `refreshSessionListBtn`, `sendBtn`, `sendTradingBtn`, and `syncMemoryBtn`.

- [ ] **Step 2: Remove front-end hard-coded example Queries**

Delete the complete control block containing `label for="exampleSelect"` and the `select id="exampleSelect"`. Delete its change listener:

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

Do not replace it with another preset, Query constant, hidden data attribute, or automatic fill behavior. Demonstration inputs remain the user's responsibility and belong only in external runbooks.

- [ ] **Step 3: Expand readable event labels and graceful fallback**

Add these entries without changing existing labels:

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

Keep the existing fallback:

```javascript
const stageInfo = stageTypeMap[type] || {
    name: type || '未知事件',
    icon: '📝',
    class: 'stage-analysis'
};
```

- [ ] **Step 4: Make status transitions announce the real outcome**

Update the lifecycle `onChange` callback:

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

The existing running labels remain “思考中/分析中” and “等待结果”.

- [ ] **Step 5: Make Toasts accessible and non-duplicating**

Update `showToast`:

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

- [ ] **Step 6: Run automated and markup checks**

```powershell
node --test docs/dev-ops/nginx/html/test/agent-ui-core.test.js
rg -n "role=\"log\"|aria-live|type=\"button\"|protocol_error|stream_interrupted" docs/dev-ops/nginx/html/index.html
rg -n "exampleSelect" docs/dev-ops/nginx/html/index.html
git diff --check
```

Expected: all Node tests PASS; each new state/accessibility marker is found; `exampleSelect` prints no matches; diff check prints nothing.

- [ ] **Step 7: Commit state and accessibility polish**

```powershell
git add docs/dev-ops/nginx/html/index.html
git commit -m "fix: clarify agent states and accessibility"
```

### Task 5: Create the formal regression and acceptance document

**Files:**
- Create: `docs/superpowers/test/2026-06-21-frontend-product-polish-test.md`

- [ ] **Step 1: Create the test document with all four scenario layers**

Use the project `test-doc-template` skill and write the following complete scenario matrix. The document must explicitly reference `docs/superpowers/plans/2026-06-21-frontend-product-polish-design.md` and the three batch plans.

```markdown
# Test: AI Agent 前端产品化打磨

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

| 测试层级 | 是否覆盖 | 说明 |
|---|---|---|
| 单元测试 | 是 | Node 内置测试覆盖纯函数 |
| 集成测试 | 是 | 浏览器连接本地后端验证 SSE 和状态 |
| 接口测试 | 部分 | 沿用现有接口，不新增契约 |
| 回归测试 | 是 | 覆盖现有五条能力线 |
| 手工验证 | 是 | 布局、滚动、取消和可访问状态 |

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
| TC-103 | HTTP 非 2xx | 后端返回错误码 | 提交请求 | 显示友好错误、无永久 Loading | pending |
| TC-104 | localStorage 禁用 | 浏览器阻止存储 | 刷新并提交 | 使用默认配置、不阻断请求 | pending |
| TC-105 | 恶意 HTML/Markdown | 输入含 script/onerror/javascript URL | 提交或加载历史 | 内容被清洗、没有代码执行 | pending |

### 3.3 边界场景

| 编号 | 场景 | 前置条件 | 操作 | 预期结果 | status |
|---|---|---|---|---|---|
| TC-201 | SSE 任意半包 | JSON 跨多个 chunk | 读取完整流 | 只产生一个完整事件、不丢内容 | pending |
| TC-202 | 快速重复提交 | 页面空闲 | 连续双击发送 | 只有一个网络请求 | pending |
| TC-203 | 超长 Markdown/代码块 | 返回长表格和长代码 | 查看结果 | 页面不横向撑开，局部可滚动 | pending |
| TC-204 | 流中回看历史 | 长流持续输出 | 向上滚动 | 页面不抢滚动；回到底部后继续跟随 | pending |
| TC-205 | 非法 userId | URL 含不合法 userId | 打开页面 | 使用已存储或默认 userId | pending |

### 3.4 回归场景

| 编号 | 场景 | 前置条件 | 操作 | 预期结果 | status |
|---|---|---|---|---|---|
| TC-301 | 意图路由 | 使用统一 Agent 入口 | 自由输入不同意图 | 原有路由和下游执行不变 | pending |
| TC-302 | 多任务拆解 | 输入复合任务 | 等待执行完成 | 子任务过程和汇总正常 | pending |
| TC-303 | 多 Agent 协作 | 使用股票分析 | 等待分析完成 | 分析师、辩论和最终决策正常 | pending |
| TC-304 | 跨会话记忆 | 使用同一 userId 不同 session | 完成记忆同步后新建会话 | 原有 Persona/情景记忆能力不变 | pending |
| TC-305 | 会话列表分页 | 历史数量超过一页 | 滚动侧栏和消息历史 | 不重复、不丢失、游标正常 | pending |

## 4. 自动化映射

| 用例 | 测试方法/验证位置 | 类型 |
|---|---|---|
| TC-101、TC-201 | `agent-ui-core.test.js` parser tests | 单元 |
| TC-105 | `agent-ui-core.test.js` sanitizer tests | 单元 |
| TC-202 | `createRequestLifecycle` duplicate-start test | 单元 |
| TC-204 | `isNearBottom` threshold tests | 单元 |
| 其余场景 | 本地浏览器与现有后端联调 | 手工/集成 |

## 5. 执行计划

| 步骤 | 内容 | 预期结果 | status |
|---|---|---|---|
| 1 | 运行 Node 单元测试 | 全部通过 | pending |
| 2 | 执行桌面与窄屏手工验证 | 无阻塞问题 | pending |
| 3 | 执行通用、交易、会话、记忆回归 | 既有能力不回归 | pending |
| 4 | 运行 Maven 默认测试 | 无新增失败 | pending |

## 6. 验收标准

| 编号 | 验收项 | 标准 | status |
|---|---|---|---|
| AC-001 | 流式正确性 | 半包、多事件和结束冲刷测试通过 | pending |
| AC-002 | 安全渲染 | 恶意内容不执行 | pending |
| AC-003 | 生命周期 | 成功、失败、取消均可继续下一请求 | pending |
| AC-004 | 响应式体验 | 1280×720 与 390×844 核心操作可用 | pending |
| AC-005 | 核心回归 | 五条现有能力线无阻塞回归 | pending |

## 7. 执行结果记录

| 项目 | 结果 |
|---|---|
| Node 单元测试 | not-run |
| 浏览器手工验证 | not-run |
| Maven 回归 | not-run |
| 用户端到端演示 | not-run |
```

- [ ] **Step 2: Check the test document structure and statuses**

Run:

```powershell
rg -n "^### 3\.[1-4]|\| TC-|\| AC-|status|not-run" docs/superpowers/test/2026-06-21-frontend-product-polish-test.md
```

Expected: four scenario sections, TC rows, AC rows, and executable status fields are present.

- [ ] **Step 3: Commit the regression document**

```powershell
git add docs/superpowers/test/2026-06-21-frontend-product-polish-test.md
git commit -m "test: add frontend product polish regression plan"
```

### Task 6: Update the demo runbook and execute final regression

**Files:**
- Modify: `docs/demo/README.md`
- Verify: `docs/superpowers/test/2026-06-21-frontend-product-polish-test.md`

- [ ] **Step 1: Add a front-end verification section to the runbook**

Append:

```markdown
## Front-end Product Verification

Use the existing page inputs; do not add fixed demonstration queries to the front-end source.

1. Confirm the active `userId` and session ID shown by the page.
2. Run one general Agent request and verify incremental output, one final result, and restored controls.
3. Run one trading analysis and verify analyst progress and final decision placement.
4. While a long request is running, scroll upward and confirm the page does not force the panel back to the bottom.
5. Cancel one running request and immediately start another without refreshing.
6. Sync a completed conversation to memory and verify the control always leaves its loading state.
7. Repeat the critical flow at a narrow viewport before the interview.

Failure recovery:

- If a provider is unavailable, wait for the page to return to a failed state and start another request; a refresh should not be required.
- If a stream ends without a business completion event, treat the visible “连接中断” state as a real protocol failure rather than presenting it as success.
- If runtime configuration changes, pass `apiBase` and `userId` through the page URL instead of editing source code.
```

- [ ] **Step 2: Run frontend automated tests**

```powershell
node --test docs/dev-ops/nginx/html/test/agent-ui-core.test.js
```

Expected: all tests PASS.

- [ ] **Step 3: Run repository regression tests**

```powershell
mvn clean test
```

Expected: all default Maven modules PASS; integration-profile tests remain excluded as designed.

- [ ] **Step 4: Execute and record browser scenarios**

Run TC-001 through TC-305 from `docs/superpowers/test/2026-06-21-frontend-product-polish-test.md`. Change each executed row from `pending` to `pass` or `fail`, and replace the four `not-run` result values with the observed result. For any failure, add a `BUG-xxx` row containing the symptom, affected scenario, and status.

- [ ] **Step 5: Run final repository checks**

```powershell
git diff --check
git status --short
```

Expected: no whitespace errors; only intentional Batch 3 files and unrelated pre-existing user changes appear.

- [ ] **Step 6: Commit final delivery material**

```powershell
git add docs/dev-ops/nginx/html/index.html docs/dev-ops/nginx/html/js/agent-ui-core.js docs/dev-ops/nginx/html/test/agent-ui-core.test.js docs/superpowers/test/2026-06-21-frontend-product-polish-test.md docs/demo/README.md
git commit -m "docs: complete frontend polish verification"
```

## Batch 3 and overall completion checkpoint

Report:

1. Node test count and result.
2. Maven module result.
3. Desktop and narrow-screen verification result.
4. TC/AC pass and fail counts.
5. Any external-service scenario not executed.
6. Remaining unrelated worktree changes.

The three-batch initiative is complete only after the user independently performs the real end-to-end demonstration and confirms that a failed or cancelled request can be followed by another request without refreshing the page.
