'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const {
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
} = require('../js/agent-ui-core.js');

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

test('request lifecycle rejects duplicate starts and finishes the matching token once', () => {
    const states = [];
    const controller = { signal: {}, abortCalled: 0, abort() { this.abortCalled += 1; } };
    const lifecycle = createRequestLifecycle({
        onChange: (state) => states.push(state.status),
        controllerFactory: () => controller
    });

    const request = lifecycle.start('general');
    assert.deepEqual(request, { id: 1, mode: 'general' });
    assert.equal(lifecycle.start('trading'), null);
    assert.equal(lifecycle.finish(request, 'completed'), true);
    assert.equal(lifecycle.finish(request, 'completed'), false);
    assert.deepEqual(states, ['running', 'completed']);
});

test('request lifecycle aborts an active request as cancelled', () => {
    const states = [];
    const controller = { signal: { id: 'signal' }, abortCalled: 0, abort() { this.abortCalled += 1; } };
    const lifecycle = createRequestLifecycle({
        onChange: (state) => states.push(state),
        controllerFactory: () => controller
    });

    const request = lifecycle.start('trading');
    assert.deepEqual(lifecycle.signal(request), { id: 'signal' });
    assert.equal(lifecycle.cancel(request), true);
    assert.equal(controller.abortCalled, 1);
    assert.equal(states.at(-1).status, 'cancelled');
    assert.equal(lifecycle.isRunning(), false);
});

test('a cancelled request cannot finish a newer request', () => {
    const lifecycle = createRequestLifecycle({
        onChange: () => {},
        controllerFactory: () => ({ signal: {}, abort() {} })
    });
    const oldRequest = lifecycle.start('general');
    assert.equal(lifecycle.cancel(oldRequest), true);
    const newRequest = lifecycle.start('trading');

    assert.equal(lifecycle.finish(oldRequest, 'failed'), false);
    assert.equal(lifecycle.isActive(newRequest), true);
    assert.equal(lifecycle.finish(newRequest, 'completed'), true);
});

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

test('createSseParser preserves a CRLF boundary split across chunks', () => {
    const events = [];
    const parser = createSseParser({ onEvent: events.push.bind(events), onError: assert.fail });

    parser.push('data: {"type":"analysis"}\r');
    parser.push('\n\r');
    parser.push('\ndata: {"type":"complete"}\n\n');

    assert.deepEqual(events, [{ type: 'analysis' }, { type: 'complete' }]);
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
    assert.throws(() => normalizeAgentEvent(new Date()), /plain object/);
    assert.throws(() => normalizeAgentEvent({ type: 'content', content: { unsafe: true } }), /content must be a string/);
    assert.throws(() => normalizeAgentEvent({ type: '<img>', content: 'x' }), /type is invalid/);
});

test('classifyAgentEvent covers the complete protocol matrix', () => {
    const cases = [
        [{ type: 'summary' }, 'result', false, null],
        [{ type: 'final', subType: 'final_completed' }, 'result', false, null],
        [{ type: 'complete' }, 'result', true, 'completed'],
        [{ type: 'trading', subType: 'final_decision' }, 'result', false, null],
        [{ type: 'analysis', subType: 'error' }, 'thinking', true, 'failed'],
        [{ type: 'future_event' }, 'thinking', false, null]
    ];

    for (const [event, target, requestTerminal, outcome] of cases) {
        const classification = classifyAgentEvent(event);
        assert.equal(classification.target, target);
        assert.equal(classification.requestTerminal, requestTerminal);
        assert.equal(classification.outcome, outcome);
    }
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
