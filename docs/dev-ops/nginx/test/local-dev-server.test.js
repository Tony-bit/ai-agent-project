'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const http = require('node:http');
const os = require('node:os');
const path = require('node:path');
const { once } = require('node:events');
const { createDevServer, filterHeaders, parseArgs, resolveStaticFile } = require('../local-dev-server.js');

async function listen(server) {
    server.listen(0, '127.0.0.1');
    await once(server, 'listening');
    return server.address().port;
}

async function close(server) {
    if (server.listening) {
        const closed = once(server, 'close');
        server.close();
        if (typeof server.closeAllConnections === 'function') {
            server.closeAllConnections();
        }
        await closed;
    }
}

test('serves the frontend root and static content types', async (context) => {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'local-ui-static-'));
    fs.writeFileSync(path.join(root, 'index.html'), '<h1>Local UI</h1>');
    fs.writeFileSync(path.join(root, 'app.js'), 'console.log("ok");');
    const server = createDevServer({ staticRoot: root, logger: { error() {} } });
    context.after(() => close(server));
    const port = await listen(server);

    const rootResponse = await fetch(`http://127.0.0.1:${port}/`);
    assert.equal(rootResponse.status, 200);
    assert.equal(rootResponse.headers.get('content-type'), 'text/html; charset=utf-8');
    assert.equal(await rootResponse.text(), '<h1>Local UI</h1>');

    const scriptResponse = await fetch(`http://127.0.0.1:${port}/app.js`);
    assert.equal(scriptResponse.headers.get('content-type'), 'text/javascript; charset=utf-8');
});

test('rejects static paths outside the configured root', () => {
    const root = path.resolve('safe-static-root');
    const resolved = resolveStaticFile(root, '/..%2fsecret.txt');
    assert.equal(resolved.statusCode, 403);
});

test('proxies API method path query body and authorization header', async (context) => {
    let received;
    const backend = http.createServer(async (request, response) => {
        const chunks = [];
        for await (const chunk of request) chunks.push(chunk);
        received = {
            method: request.method,
            url: request.url,
            authorization: request.headers.authorization,
            body: Buffer.concat(chunks).toString('utf8')
        };
        response.writeHead(201, { 'Content-Type': 'application/json', 'X-Upstream': 'yes' });
        response.end('{"ok":true}');
    });
    context.after(() => close(backend));
    const backendPort = await listen(backend);

    const proxy = createDevServer({ backend: `http://127.0.0.1:${backendPort}` });
    context.after(() => close(proxy));
    const proxyPort = await listen(proxy);
    const response = await fetch(`http://127.0.0.1:${proxyPort}/api/test?q=1`, {
        method: 'POST',
        headers: { Authorization: 'Bearer test-token', 'Content-Type': 'application/json' },
        body: '{"value":1}'
    });

    assert.equal(response.status, 201);
    assert.equal(response.headers.get('x-upstream'), 'yes');
    assert.equal(await response.text(), '{"ok":true}');
    assert.deepEqual(received, {
        method: 'POST',
        url: '/api/test?q=1',
        authorization: 'Bearer test-token',
        body: '{"value":1}'
    });
});

test('streams the first SSE chunk before the upstream response ends', async (context) => {
    let upstreamEnded = false;
    const backend = http.createServer((request, response) => {
        response.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache' });
        response.write('data: first\n\n');
        setTimeout(() => {
            upstreamEnded = true;
            response.end('data: second\n\n');
        }, 150);
    });
    context.after(() => close(backend));
    const backendPort = await listen(backend);

    const proxy = createDevServer({ backend: `http://127.0.0.1:${backendPort}` });
    context.after(() => close(proxy));
    const proxyPort = await listen(proxy);
    const response = await fetch(`http://127.0.0.1:${proxyPort}/api/stream`);
    const reader = response.body.getReader();
    const first = await reader.read();

    assert.equal(upstreamEnded, false);
    assert.match(new TextDecoder().decode(first.value), /data: first/);
    await reader.cancel();
});

test('survives an upstream failure after SSE headers were sent', async (context) => {
    const backend = http.createServer((request, response) => {
        response.writeHead(200, { 'Content-Type': 'text/event-stream' });
        response.write('data: first\n\n');
        setTimeout(() => response.socket.destroy(), 25);
    });
    context.after(() => close(backend));
    const backendPort = await listen(backend);

    const errors = [];
    const proxy = createDevServer({
        backend: `http://127.0.0.1:${backendPort}`,
        logger: { error(message) { errors.push(message); } }
    });
    context.after(() => close(proxy));
    const proxyPort = await listen(proxy);

    const interrupted = await fetch(`http://127.0.0.1:${proxyPort}/api/stream`);
    await assert.rejects(interrupted.text());
    const healthResponse = await fetch(`http://127.0.0.1:${proxyPort}/missing`);
    assert.equal(healthResponse.status, 404);
    assert.equal(errors.length, 1);
});

test('returns JSON 502 when the backend is unavailable', async (context) => {
    const unavailable = http.createServer();
    const unavailablePort = await listen(unavailable);
    await close(unavailable);

    const errors = [];
    const proxy = createDevServer({
        backend: `http://127.0.0.1:${unavailablePort}`,
        logger: { error(message) { errors.push(message); } }
    });
    context.after(() => close(proxy));
    const proxyPort = await listen(proxy);
    const response = await fetch(`http://127.0.0.1:${proxyPort}/api/test`);

    assert.equal(response.status, 502);
    assert.equal(response.headers.get('content-type'), 'application/json; charset=utf-8');
    assert.deepEqual(await response.json(), { error: 'Local backend is unavailable' });
    assert.equal(errors.length, 1);
});

test('filters hop-by-hop headers and validates command line options', () => {
    assert.deepEqual(filterHeaders({ connection: 'keep-alive', authorization: 'Bearer x' }), {
        authorization: 'Bearer x'
    });
    assert.deepEqual(parseArgs(['--port', '8088', '--backend', 'http://127.0.0.1:8091']), {
        host: '127.0.0.1',
        port: 8088,
        backend: 'http://127.0.0.1:8091',
        staticRoot: path.resolve(__dirname, '..', 'html')
    });
    assert.throws(() => parseArgs(['--port', 'invalid']), /Invalid port/);
});
