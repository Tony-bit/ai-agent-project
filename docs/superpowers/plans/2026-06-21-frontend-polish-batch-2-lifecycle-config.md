# Frontend Polish Batch 2: Request Lifecycle and Runtime Configuration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give general chat, trading analysis, session history, and memory sync one consistent runtime configuration and a cancellable, idempotent request lifecycle.

**Architecture:** Extend the tested UMD core from Batch 1 with pure configuration and lifecycle utilities. The page resolves configuration once at startup, builds every API URL through one helper, and drives both streaming request UIs from one lifecycle object backed by `AbortController`.

**Tech Stack:** Vanilla JavaScript, Fetch, AbortController, URLSearchParams, localStorage, Node.js `node:test`, static HTML/Tailwind CSS.

---

## Prerequisite

Batch 1 must be committed and its checkpoint accepted. The files `js/agent-ui-core.js` and `test/agent-ui-core.test.js` must exist and the Batch 1 tests must pass before this plan starts.

## File map

- Modify `docs/dev-ops/nginx/html/js/agent-ui-core.js`: add validated runtime configuration and request lifecycle utilities.
- Modify `docs/dev-ops/nginx/html/test/agent-ui-core.test.js`: add deterministic config and lifecycle tests.
- Modify `docs/dev-ops/nginx/html/index.html:768-775,936-1070,1160-1225,1390-1660,1800-2080`: add cancellation UI, resolve runtime config, update all Fetch calls, and unify request state transitions.

### Task 1: Specify runtime configuration behavior

**Files:**
- Modify: `docs/dev-ops/nginx/html/test/agent-ui-core.test.js`
- Test: `docs/dev-ops/nginx/html/test/agent-ui-core.test.js`

- [ ] **Step 1: Append tests for API Base URL and user ID precedence**

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

- [ ] **Step 2: Run the tests and verify the new exports are missing**

Run:

```powershell
node --test docs/dev-ops/nginx/html/test/agent-ui-core.test.js
```

Expected: FAIL because `resolveRuntimeConfig` and `buildApiUrl` are not exported.

### Task 2: Implement validated runtime configuration

**Files:**
- Modify: `docs/dev-ops/nginx/html/js/agent-ui-core.js`
- Test: `docs/dev-ops/nginx/html/test/agent-ui-core.test.js`

- [ ] **Step 1: Add configuration helpers before the module return statement**

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
        // Storage is optional; in-memory configuration remains usable.
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

Update the export object:

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

- [ ] **Step 2: Run all core tests**

Run:

```powershell
node --test docs/dev-ops/nginx/html/test/agent-ui-core.test.js
```

Expected: all Batch 1 and Batch 2 configuration tests PASS.

- [ ] **Step 3: Commit runtime configuration utilities**

```powershell
git add docs/dev-ops/nginx/html/js/agent-ui-core.js docs/dev-ops/nginx/html/test/agent-ui-core.test.js
git commit -m "feat: resolve frontend runtime configuration"
```

### Task 3: Apply runtime configuration to every API call

**Files:**
- Modify: `docs/dev-ops/nginx/html/index.html:936-1070,1160-1225,1390-1535,1930-2080`

- [ ] **Step 1: Resolve configuration once and preserve the current user default**

Extend the module destructuring and replace the hard-coded user constant:

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

This intentionally keeps the user's current `test-user-f52b2ed1` value as the fallback.

- [ ] **Step 2: Replace all absolute Fetch URLs**

Use these exact forms:

```javascript
buildApiUrl(`/api/v1/session/list?userId=${encodeURIComponent(currentUserId)}`)
buildApiUrl(`/api/v1/session/${encodeURIComponent(targetSessionId)}/messages`)
buildApiUrl('/api/v1/agent/auto_agent')
buildApiUrl('/api/v1/trading/analysis')
buildApiUrl(`/api/v1/session/${encodeURIComponent(sessionId)}/sync-memory?userId=${encodeURIComponent(currentUserId)}`)
```

- [ ] **Step 3: Show the active user without adding a new business mode**

Next to the existing session ID status, add:

```html
<span class="text-[10px] text-gray-500">用户:</span>
<span id="currentUserId" class="font-mono text-[10px] text-gray-600 bg-white px-1.5 py-0.5 rounded"></span>
```

Initialize it after resolving configuration:

```javascript
document.getElementById('currentUserId').textContent = currentUserId;
```

- [ ] **Step 4: Verify no API host or second user constant remains**

Run:

```powershell
rg -n "http://localhost:8090|const currentUserId" docs/dev-ops/nginx/html/index.html
```

Expected: no fixed API URL; exactly one `const currentUserId = runtimeConfig.userId`.

- [ ] **Step 5: Commit page configuration integration**

```powershell
git add docs/dev-ops/nginx/html/index.html
git commit -m "fix: support same-origin and configurable frontend runtime"
```

### Task 4: Specify and implement an idempotent request lifecycle

**Files:**
- Modify: `docs/dev-ops/nginx/html/test/agent-ui-core.test.js`
- Modify: `docs/dev-ops/nginx/html/js/agent-ui-core.js`
- Test: `docs/dev-ops/nginx/html/test/agent-ui-core.test.js`

- [ ] **Step 1: Append lifecycle tests**

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

- [ ] **Step 2: Run tests and verify the lifecycle export is missing**

Run:

```powershell
node --test docs/dev-ops/nginx/html/test/agent-ui-core.test.js
```

Expected: FAIL because `createRequestLifecycle` is not exported.

- [ ] **Step 3: Implement the lifecycle utility**

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

Add `createRequestLifecycle` to the exported object.

- [ ] **Step 4: Run all tests and commit**

Run:

```powershell
node --test docs/dev-ops/nginx/html/test/agent-ui-core.test.js
```

Expected: all tests PASS.

```powershell
git add docs/dev-ops/nginx/html/js/agent-ui-core.js docs/dev-ops/nginx/html/test/agent-ui-core.test.js
git commit -m "feat: add cancellable frontend request lifecycle"
```

### Task 5: Drive both streaming UIs from the shared lifecycle

**Files:**
- Modify: `docs/dev-ops/nginx/html/index.html:768-775,1390-1660,1800-2040`

- [ ] **Step 1: Add a cancel control to the existing loading strip**

Inside `#loading`, after the loading text, add:

```html
<button id="cancelRequestBtn" type="button"
        class="ml-2 px-2.5 py-1 text-[10px] font-semibold text-red-600 bg-white border border-red-200 rounded-full hover:bg-red-50 focus:outline-none focus:ring-2 focus:ring-red-300">
    取消任务
</button>
```

- [ ] **Step 2: Create one lifecycle and one UI state renderer**

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

Extract the existing trading button HTML into `getTradingSendButtonContent()` and make `showLoadingState`/`closeLoadingState` thin wrappers or remove them after all callers use `requestLifecycle`.

- [ ] **Step 3: Wire cancellation**

```javascript
document.getElementById('cancelRequestBtn').addEventListener('click', () => {
    if (requestLifecycle.cancel()) {
        showToast('任务已取消', 'info');
        addStageMessage('error', 'cancelled', '用户已取消本次任务', null, 'thinking');
    }
});
```

- [ ] **Step 4: Start and finish general requests exactly once**

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

Use this terminal handling:

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

- [ ] **Step 5: Apply the same lifecycle to trading requests**

Use `requestLifecycle.start('trading')`, the same `signal`, AbortError handling, and idempotent `.finally(...)`. Remove the separate manual trading-button disable/restore code.

- [ ] **Step 6: Cancel before destructive UI transitions**

At the beginning of `createNewChat`, session selection, and mode switching:

```javascript
if (requestLifecycle.isRunning()) {
    requestLifecycle.cancel();
}
```

Do not display an error Toast for these explicit navigation cancellations.

- [ ] **Step 7: Replace blocking validation alerts**

Replace the five non-confirmation `alert(...)` calls with `showToast(message, 'info')`. Keep the destructive `confirm(...)` for clearing all local chat UI because it requires explicit user confirmation.

- [ ] **Step 8: Run static and automated checks**

Run:

```powershell
node --test docs/dev-ops/nginx/html/test/agent-ui-core.test.js
rg -n "alert\(|http://localhost:8090|new AbortController" docs/dev-ops/nginx/html/index.html
git diff --check
```

Expected: all tests PASS; no `alert(...)` or fixed API host remains; AbortController construction exists only in the core lifecycle default; diff check is clean.

- [ ] **Step 9: Commit lifecycle integration**

```powershell
git add docs/dev-ops/nginx/html/index.html
git commit -m "fix: unify and cancel frontend agent requests"
```

### Task 6: Batch 2 integration checkpoint

**Files:**
- Verify: `docs/dev-ops/nginx/html/index.html`
- Verify: `docs/dev-ops/nginx/html/js/agent-ui-core.js`
- Verify: `docs/dev-ops/nginx/html/test/agent-ui-core.test.js`

- [ ] **Step 1: Run the full frontend utility test suite**

```powershell
node --test docs/dev-ops/nginx/html/test/agent-ui-core.test.js
```

Expected: all tests PASS.

- [ ] **Step 2: Perform browser verification**

Verify these exact scenarios:

1. Two rapid send clicks create one network request.
2. Cancel stops a general stream, restores controls, and allows a new request without refresh.
3. Cancel stops a trading stream with the same behavior.
4. Default same-origin deployment works without `apiBase`.
5. `?apiBase=http://localhost:8090&userId=demo-user` uses the configured host and user.
6. An invalid `userId=<script>` is rejected and falls back to the stored/default user.
7. Session list, session messages, and memory sync use the same resolved configuration.

- [ ] **Step 3: Record the checkpoint commit**

```powershell
git status --short
git log -5 --oneline
```

Expected: only unrelated pre-existing user files remain modified/untracked; Batch 2 commits are visible. Stop here and hand the build to the user for real end-to-end verification before starting Batch 3.

