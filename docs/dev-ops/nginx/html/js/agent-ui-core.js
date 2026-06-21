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
        let pendingCr = false;

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
            let index = buffer.indexOf('\n\n');
            while (index !== -1) {
                if (index > maxBufferLength) {
                    const raw = buffer.slice(0, index);
                    buffer = '';
                    const error = new Error(`SSE buffer exceeds ${maxBufferLength} characters`);
                    report(error, raw);
                    throw error;
                }
                const block = buffer.slice(0, index);
                buffer = buffer.slice(index + 2);
                dispatch(block);
                index = buffer.indexOf('\n\n');
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
                let text = `${pendingCr ? '\r' : ''}${String(chunk == null ? '' : chunk)}`;
                pendingCr = text.endsWith('\r');
                if (pendingCr) {
                    text = text.slice(0, -1);
                }
                buffer += text.replaceAll('\r\n', '\n').replaceAll('\r', '\n');
                drain();
            },
            finish() {
                if (pendingCr) {
                    buffer += '\n';
                    pendingCr = false;
                    drain();
                }
                if (buffer.trim()) dispatch(buffer);
                buffer = '';
            },
            reset() {
                buffer = '';
                pendingCr = false;
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
