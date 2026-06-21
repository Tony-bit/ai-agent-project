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
