# 前端产品化打磨第二批：请求生命周期与运行配置实施计划

> **供智能体执行者使用：** 必须使用 `superpowers:subagent-driven-development`（推荐）或 `superpowers:executing-plans` 子技能逐项执行本计划。所有步骤使用复选框（`- [ ]`）跟踪状态。

**目标：** 为通用对话、交易分析、会话历史和记忆同步提供统一运行配置，以及可取消、幂等的请求生命周期。

**架构：** 在第一批已经测试的 UMD 核心中增加纯配置与生命周期工具。页面启动时只解析一次配置，通过同一工具构造全部 API URL，并使用一个由 `AbortController` 支撑的生命周期对象驱动两类流式请求 UI。

**技术栈：** 原生 JavaScript、Fetch、AbortController、URLSearchParams、localStorage、Node.js `node:test`、静态 HTML/Tailwind CSS。

---

## 前置条件

开始本计划前，第一批必须已经提交并通过检查点验收；`js/agent-ui-core.js` 和 `test/agent-ui-core.test.js` 必须存在，且第一批测试全部通过。

## 文件结构

- 修改 `docs/dev-ops/nginx/html/js/agent-ui-core.js`：增加带校验的运行配置和请求生命周期工具。
- 修改 `docs/dev-ops/nginx/html/test/agent-ui-core.test.js`：增加确定性的配置与生命周期测试。
- 修改 `docs/dev-ops/nginx/html/index.html:768-775,936-1070,1160-1225,1390-1660,1800-2080`：增加取消 UI、解析运行配置、更新全部 Fetch 调用并统一请求状态转换。

### 任务 1：定义运行配置行为

**文件：**
- 修改：`docs/dev-ops/nginx/html/test/agent-ui-core.test.js`
- 测试：`docs/dev-ops/nginx/html/test/agent-ui-core.test.js`

- [ ] **步骤 1：追加 API Base URL 和用户 ID 优先级测试**

```javascript
const { resolveRuntimeConfig, buildApiUrl } = require('../js/agent-ui-core.js');

function memoryStorage(initial = {}) {
    const values = new Map(Object.entries(initial));
    return {
        getItem: (key) => values.has(key) ? values.get(key) : null,
        setItem: (key, value) => values.set(key, String(value)),
        snapshot: () => Object.fromEntries(values)
    };
}

test('resolveRuntimeConfig prefers validated URL values and persists userId', () => {
    const storage = memoryStorage({ 'agent.userId': 'stored-user' });
    const config = resolveRuntimeConfig({
        search: '?userId=demo-user_01&apiBase=http%3A%2F%2Flocalhost%3A8090%2F',
        storage,
        defaultUserId: 'default-user',
        origin: 'http://localhost'
    });

    assert.deepEqual(config, {
        apiBase: 'http://localhost:8090',
        userId: 'demo-user_01'
    });
    assert.equal(storage.snapshot()['agent.userId'], 'demo-user_01');
});

test('resolveRuntimeConfig falls back from invalid URL userId to storage', () => {
    const storage = memoryStorage({ 'agent.userId': 'stored-user' });
    const config = resolveRuntimeConfig({
        search: '?userId=%3Cscript%3E',
        storage,
        defaultUserId: 'default-user',
        origin: 'http://localhost'
    });

    assert.equal(config.userId, 'stored-user');
    assert.equal(config.apiBase, '');
});

test('resolveRuntimeConfig falls back when storage throws', () => {
    const storage = {
        getItem: () => { throw new Error('blocked'); },
        setItem: () => { throw new Error('blocked'); }
    };
    const config = resolveRuntimeConfig({
        search: '', storage, defaultUserId: 'default-user', origin: 'http://localhost'
    });

    assert.deepEqual(config, { apiBase: '', userId: 'default-user' });
});

test('buildApiUrl joins same-origin and configured base URLs', () => {
    assert.equal(buildApiUrl('', '/api/v1/session/list'), '/api/v1/session/list');
    assert.equal(
        buildApiUrl('http://localhost:8090', '/api/v1/agent/auto_agent'),
        'http://localhost:8090/api/v1/agent/auto_agent'
    );
});
```

- [ ] **步骤 2：运行测试并确认新导出尚不存在**

运行：

```powershell
node --test docs/dev-ops/nginx/html/test/agent-ui-core.test.js
```

预期：测试失败，因为尚未导出 `resolveRuntimeConfig` 和 `buildApiUrl`。

### 任务 2：实现带校验的运行配置

**文件：**
- 修改：`docs/dev-ops/nginx/html/js/agent-ui-core.js`
- 测试：`docs/dev-ops/nginx/html/test/agent-ui-core.test.js`

- [ ] **步骤 1：在模块返回语句前增加配置工具**

```javascript
const USER_ID_PATTERN = /^[A-Za-z0-9._:-]{1,64}$/;

function safeStorageGet(storage, key) {
    try {
        return storage && storage.getItem ? storage.getItem(key) : null;
    } catch (_) {
        return null;
    }
}

function safeStorageSet(storage, key, value) {
    try {
        if (storage && storage.setItem) storage.setItem(key, value);
    } catch (_) {
        // 存储能力是可选的；内存中的配置仍然可用。
    }
}

function normalizeApiBase(value, origin) {
    if (!value) return '';
    try {
        const url = new URL(value, origin);
        if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password) return '';
        return `${url.origin}${url.pathname.replace(/\/$/, '') === '/' ? '' : url.pathname.replace(/\/$/, '')}`;
    } catch (_) {
        return '';
    }
}

function resolveRuntimeConfig({ search, storage, defaultUserId, origin }) {
    const params = new URLSearchParams(search || '');
    const queryUserId = params.get('userId');
    const storedUserId = safeStorageGet(storage, 'agent.userId');
    const userId = [queryUserId, storedUserId, defaultUserId]
        .find((value) => USER_ID_PATTERN.test(value || ''));
    if (queryUserId && queryUserId === userId) {
        safeStorageSet(storage, 'agent.userId', userId);
    }

    const queryApiBase = normalizeApiBase(params.get('apiBase'), origin);
    const storedApiBase = normalizeApiBase(safeStorageGet(storage, 'agent.apiBase'), origin);
    if (queryApiBase) safeStorageSet(storage, 'agent.apiBase', queryApiBase);

    return {
        apiBase: queryApiBase || storedApiBase || '',
        userId: userId || defaultUserId
    };
}

function buildApiUrl(apiBase, path) {
    const normalizedPath = path.startsWith('/') ? path : `/${path}`;
    return `${apiBase || ''}${normalizedPath}`;
}
```

更新导出对象：

```javascript
return {
    createSseParser,
    escapeHtml,
    sanitizeMarkdown,
    classifyAgentEvent,
    resolveRuntimeConfig,
    buildApiUrl
};
```

- [ ] **步骤 2：运行全部核心测试**

运行：

```powershell
node --test docs/dev-ops/nginx/html/test/agent-ui-core.test.js
```

预期：第一批与第二批的全部配置测试通过。

- [ ] **步骤 3：提交运行配置工具**

```powershell
git add docs/dev-ops/nginx/html/js/agent-ui-core.js docs/dev-ops/nginx/html/test/agent-ui-core.test.js
git commit -m "feat: resolve frontend runtime configuration"
```

### 任务 3：将运行配置应用到全部 API 调用

**文件：**
- 修改：`docs/dev-ops/nginx/html/index.html:936-1070,1160-1225,1390-1535,1930-2080`

- [ ] **步骤 1：只解析一次配置并保留当前默认用户**

扩展模块解构并替换硬编码用户常量：

```javascript
const {
    createSseParser,
    escapeHtml,
    sanitizeMarkdown,
    classifyAgentEvent,
    resolveRuntimeConfig,
    buildApiUrl: joinApiUrl
} = window.AgentUiCore;

const runtimeConfig = resolveRuntimeConfig({
    search: window.location.search,
    storage: window.localStorage,
    defaultUserId: 'test-user-f52b2ed1',
    origin: window.location.origin
});
const currentUserId = runtimeConfig.userId;

function buildApiUrl(path) {
    return joinApiUrl(runtimeConfig.apiBase, path);
}
```

这里有意保留用户当前的 `test-user-f52b2ed1` 作为兜底值。

- [ ] **步骤 2：替换全部 Fetch 绝对地址**

严格使用以下形式：

```javascript
buildApiUrl(`/api/v1/session/list?userId=${encodeURIComponent(currentUserId)}`)
buildApiUrl(`/api/v1/session/${encodeURIComponent(targetSessionId)}/messages`)
buildApiUrl('/api/v1/agent/auto_agent')
buildApiUrl('/api/v1/trading/analysis')
buildApiUrl(`/api/v1/session/${encodeURIComponent(sessionId)}/sync-memory?userId=${encodeURIComponent(currentUserId)}`)
```

- [ ] **步骤 3：在不增加业务模式的前提下展示当前用户**

在现有会话 ID 状态旁增加：

```html
<span class="text-[10px] text-gray-500">用户:</span>
<span id="currentUserId" class="font-mono text-[10px] text-gray-600 bg-white px-1.5 py-0.5 rounded"></span>
```

解析配置后初始化该元素：

```javascript
document.getElementById('currentUserId').textContent = currentUserId;
```

- [ ] **步骤 4：确认不再存在固定 API 主机或第二份用户常量**

运行：

```powershell
rg -n "http://localhost:8090|const currentUserId" docs/dev-ops/nginx/html/index.html
```

预期：不存在固定 API URL；只保留一个 `const currentUserId = runtimeConfig.userId`。

- [ ] **步骤 5：提交页面配置集成**

```powershell
git add docs/dev-ops/nginx/html/index.html
git commit -m "fix: support same-origin and configurable frontend runtime"
```

### 任务 4：定义并实现幂等请求生命周期

**文件：**
- 修改：`docs/dev-ops/nginx/html/test/agent-ui-core.test.js`
- 修改：`docs/dev-ops/nginx/html/js/agent-ui-core.js`
- 测试：`docs/dev-ops/nginx/html/test/agent-ui-core.test.js`

- [ ] **步骤 1：追加生命周期测试**

```javascript
const { createRequestLifecycle } = require('../js/agent-ui-core.js');

test('request lifecycle rejects duplicate starts and finishes once', () => {
    const states = [];
    const controller = { signal: {}, abortCalled: 0, abort() { this.abortCalled += 1; } };
    const lifecycle = createRequestLifecycle({
        onChange: (state) => states.push(state.status),
        controllerFactory: () => controller
    });

    assert.equal(lifecycle.start('general'), true);
    assert.equal(lifecycle.start('trading'), false);
    assert.equal(lifecycle.finish('completed'), true);
    assert.equal(lifecycle.finish('completed'), false);
    assert.deepEqual(states, ['running', 'completed']);
});

test('request lifecycle aborts an active request as cancelled', () => {
    const states = [];
    const controller = { signal: { id: 'signal' }, abortCalled: 0, abort() { this.abortCalled += 1; } };
    const lifecycle = createRequestLifecycle({
        onChange: (state) => states.push(state),
        controllerFactory: () => controller
    });

    lifecycle.start('trading');
    assert.deepEqual(lifecycle.signal(), { id: 'signal' });
    assert.equal(lifecycle.cancel(), true);
    assert.equal(controller.abortCalled, 1);
    assert.equal(states.at(-1).status, 'cancelled');
    assert.equal(lifecycle.isRunning(), false);
});
```

- [ ] **步骤 2：运行测试并确认生命周期导出尚不存在**

运行：

```powershell
node --test docs/dev-ops/nginx/html/test/agent-ui-core.test.js
```

预期：测试失败，因为尚未导出 `createRequestLifecycle`。

- [ ] **步骤 3：实现生命周期工具**

```javascript
function createRequestLifecycle({ onChange, controllerFactory }) {
    let state = { status: 'idle', mode: null };
    let controller = null;
    const makeController = controllerFactory || (() => new AbortController());

    function transition(status, mode) {
        state = { status, mode: mode == null ? state.mode : mode };
        onChange({ ...state });
    }

    return {
        start(mode) {
            if (state.status === 'running') return false;
            controller = makeController();
            transition('running', mode);
            return true;
        },
        finish(status) {
            if (state.status !== 'running') return false;
            transition(status, state.mode);
            controller = null;
            return true;
        },
        cancel() {
            if (state.status !== 'running' || !controller) return false;
            controller.abort();
            transition('cancelled', state.mode);
            controller = null;
            return true;
        },
        signal() {
            return controller ? controller.signal : undefined;
        },
        isRunning() {
            return state.status === 'running';
        },
        snapshot() {
            return { ...state };
        }
    };
}
```

将 `createRequestLifecycle` 加入导出对象。

- [ ] **步骤 4：运行全部测试并提交**

运行：

```powershell
node --test docs/dev-ops/nginx/html/test/agent-ui-core.test.js
```

预期：全部测试通过。

```powershell
git add docs/dev-ops/nginx/html/js/agent-ui-core.js docs/dev-ops/nginx/html/test/agent-ui-core.test.js
git commit -m "feat: add cancellable frontend request lifecycle"
```

### 任务 5：使用共享生命周期驱动两类流式 UI

**文件：**
- 修改：`docs/dev-ops/nginx/html/index.html:768-775,1390-1660,1800-2040`

- [ ] **步骤 1：在现有加载条中增加取消控件**

Inside `#loading`, after the loading text, add:

```html
<button id="cancelRequestBtn" type="button"
        class="ml-2 px-2.5 py-1 text-[10px] font-semibold text-red-600 bg-white border border-red-200 rounded-full hover:bg-red-50 focus:outline-none focus:ring-2 focus:ring-red-300">
    取消任务
</button>
```

- [ ] **步骤 2：创建统一生命周期对象和 UI 状态渲染器**

```javascript
const requestLifecycle = window.AgentUiCore.createRequestLifecycle({
    onChange: ({ status, mode }) => {
        const running = status === 'running';
        isConnected = running;
        document.getElementById('loading').classList.toggle('hidden', !running);
        document.getElementById('sendBtn').disabled = running;
        document.getElementById('sendTradingBtn').disabled = running;
        document.getElementById('cancelRequestBtn').classList.toggle('hidden', !running);
        setProcessingState(running, mode || currentMode);
        if (!running) {
            clearStreamingEffects();
            restoreSendButtonContent();
        }
    }
});

function restoreSendButtonContent() {
    document.getElementById('sendBtn').innerHTML = getGeneralSendButtonContent();
    document.getElementById('sendTradingBtn').innerHTML = getTradingSendButtonContent();
}
```

将现有交易按钮 HTML 抽取到 `getTradingSendButtonContent()`。在所有调用方都改用 `requestLifecycle` 后，将 `showLoadingState`/`closeLoadingState` 改成薄包装或删除。

- [ ] **步骤 3：接入取消操作**

```javascript
document.getElementById('cancelRequestBtn').addEventListener('click', () => {
    if (requestLifecycle.cancel()) {
        showToast('任务已取消', 'info');
        addStageMessage('error', 'cancelled', '用户已取消本次任务', null, 'thinking');
    }
});
```

- [ ] **步骤 4：保证通用请求只启动和结束一次**

At the top of `sendMessage`, replace the `isConnected` branch with:

```javascript
if (!requestLifecycle.start('general')) {
    showToast('已有任务正在处理中', 'info');
    return;
}
```

Pass the signal to Fetch:

```javascript
signal: requestLifecycle.signal()
```

使用以下终止处理逻辑：

```javascript
.catch((error) => {
    if (error.name !== 'AbortError') {
        addStageMessage('error', null, getFriendlyErrorMessage(error), null, 'thinking');
        requestLifecycle.finish('failed');
    }
})
.finally(() => {
    if (requestLifecycle.isRunning()) {
        requestLifecycle.finish(terminalEventReceived ? 'completed' : 'failed');
    }
});
```

- [ ] **步骤 5：将相同生命周期应用到交易请求**

使用 `requestLifecycle.start('trading')`、相同的 `signal`、AbortError 处理和幂等 `.finally(...)`。删除独立的交易按钮禁用与恢复代码。

- [ ] **步骤 6：在破坏性 UI 切换前取消当前请求**

At the beginning of `createNewChat`, session selection, and mode switching:

```javascript
if (requestLifecycle.isRunning()) {
    requestLifecycle.cancel();
}
```

这些由明确导航操作触发的取消不显示错误 Toast。

- [ ] **步骤 7：替换阻塞式校验提示**

Replace the five non-confirmation `alert(...)` calls with `showToast(message, 'info')`. Keep the destructive `confirm(...)` for clearing all local chat UI because it requires explicit user confirmation.

- [ ] **步骤 8：运行静态检查和自动化测试**

运行：

```powershell
node --test docs/dev-ops/nginx/html/test/agent-ui-core.test.js
rg -n "alert\(|http://localhost:8090|new AbortController" docs/dev-ops/nginx/html/index.html
git diff --check
```

预期：全部测试通过；不存在 `alert(...)` 或固定 API 主机；AbortController 只在核心生命周期默认实现中构造；差异检查无异常。

- [ ] **步骤 9：提交生命周期集成**

```powershell
git add docs/dev-ops/nginx/html/index.html
git commit -m "fix: unify and cancel frontend agent requests"
```

### 任务 6：第二批集成检查点

**文件：**
- 验证：`docs/dev-ops/nginx/html/index.html`
- 验证：`docs/dev-ops/nginx/html/js/agent-ui-core.js`
- 验证：`docs/dev-ops/nginx/html/test/agent-ui-core.test.js`

- [ ] **步骤 1：运行完整前端工具测试套件**

```powershell
node --test docs/dev-ops/nginx/html/test/agent-ui-core.test.js
```

预期：全部测试通过。

- [ ] **步骤 2：执行浏览器验证**

严格验证以下场景：

1. 快速连续点击两次发送只创建一个网络请求。
2. 取消能够停止通用流、恢复控件，并允许无需刷新直接发起新请求。
3. 取消交易流时具有相同行为。
4. 默认同源部署在没有 `apiBase` 时正常工作。
5. `?apiBase=http://localhost:8090&userId=demo-user` uses the configured host and user.
6. An invalid `userId=<script>` is rejected and falls back to the stored/default user.
7. 会话列表、会话消息和记忆同步使用同一份已解析配置。

- [ ] **步骤 3：记录检查点提交**

```powershell
git status --short
git log -5 --oneline
```

预期：只剩与本轮无关的既有用户文件处于修改或未跟踪状态；第二批提交清晰可见。在此停止，将构建结果交给用户完成真实端到端验证后再开始第三批。
