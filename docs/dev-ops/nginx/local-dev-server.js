'use strict';

const fs = require('node:fs');
const http = require('node:http');
const path = require('node:path');

const DEFAULT_HOST = '127.0.0.1';
const DEFAULT_PORT = 8080;
const DEFAULT_BACKEND = 'http://127.0.0.1:8090';
const DEFAULT_STATIC_ROOT = path.join(__dirname, 'html');

const HOP_BY_HOP_HEADERS = new Set([
    'connection',
    'keep-alive',
    'proxy-authenticate',
    'proxy-authorization',
    'te',
    'trailer',
    'transfer-encoding',
    'upgrade'
]);

const CONTENT_TYPES = new Map([
    ['.css', 'text/css; charset=utf-8'],
    ['.html', 'text/html; charset=utf-8'],
    ['.ico', 'image/x-icon'],
    ['.js', 'text/javascript; charset=utf-8'],
    ['.json', 'application/json; charset=utf-8'],
    ['.map', 'application/json; charset=utf-8'],
    ['.png', 'image/png'],
    ['.svg', 'image/svg+xml'],
    ['.webp', 'image/webp']
]);

function filterHeaders(headers) {
    return Object.fromEntries(Object.entries(headers)
        .filter(([name]) => !HOP_BY_HOP_HEADERS.has(name.toLowerCase())));
}

function writeText(response, statusCode, contentType, body) {
    if (response.headersSent) {
        response.destroy();
        return;
    }
    response.writeHead(statusCode, {
        'Content-Type': contentType,
        'Content-Length': Buffer.byteLength(body),
        'Cache-Control': 'no-store'
    });
    response.end(body);
}

function proxyApiRequest(request, response, backend, logger) {
    const headers = filterHeaders(request.headers);
    headers.host = backend.host;
    let downstreamClosed = false;

    const upstream = http.request({
        protocol: backend.protocol,
        hostname: backend.hostname,
        port: backend.port,
        method: request.method,
        path: request.url,
        headers
    }, (upstreamResponse) => {
        response.writeHead(upstreamResponse.statusCode || 502,
            filterHeaders(upstreamResponse.headers));
        upstreamResponse.on('error', (error) => {
            if (downstreamClosed) {
                return;
            }
            logger.error(`API upstream stream failed: ${request.method} ${request.url}: ${error.message}`);
            response.destroy(error);
        });
        upstreamResponse.pipe(response);
    });

    upstream.on('error', (error) => {
        logger.error(`API proxy failed: ${request.method} ${request.url}: ${error.message}`);
        writeText(response, 502, 'application/json; charset=utf-8',
            JSON.stringify({ error: 'Local backend is unavailable' }));
    });

    response.on('close', () => {
        if (!response.writableEnded) {
            downstreamClosed = true;
            upstream.destroy();
        }
    });

    request.pipe(upstream);
}

function resolveStaticFile(staticRoot, requestUrl) {
    let pathname;
    try {
        pathname = decodeURIComponent(new URL(requestUrl, 'http://localhost').pathname);
    } catch (_) {
        return { statusCode: 400 };
    }

    const relativePath = pathname === '/' ? 'index.html' : pathname.replace(/^\/+/, '');
    const root = path.resolve(staticRoot);
    const filePath = path.resolve(root, relativePath);
    if (filePath !== root && !filePath.startsWith(`${root}${path.sep}`)) {
        return { statusCode: 403 };
    }
    return { filePath };
}

function serveStaticFile(request, response, staticRoot) {
    if (!['GET', 'HEAD'].includes(request.method)) {
        writeText(response, 405, 'text/plain; charset=utf-8', 'Method Not Allowed');
        return;
    }

    const resolved = resolveStaticFile(staticRoot, request.url);
    if (resolved.statusCode) {
        writeText(response, resolved.statusCode, 'text/plain; charset=utf-8', 'Invalid path');
        return;
    }

    fs.stat(resolved.filePath, (error, stats) => {
        if (error || !stats.isFile()) {
            writeText(response, 404, 'text/plain; charset=utf-8', 'Not Found');
            return;
        }

        response.writeHead(200, {
            'Content-Type': CONTENT_TYPES.get(path.extname(resolved.filePath).toLowerCase())
                || 'application/octet-stream',
            'Content-Length': stats.size,
            'Cache-Control': 'no-cache'
        });
        if (request.method === 'HEAD') {
            response.end();
            return;
        }
        const stream = fs.createReadStream(resolved.filePath);
        stream.on('error', () => response.destroy());
        stream.pipe(response);
    });
}

function createDevServer(options = {}) {
    const backend = new URL(options.backend || DEFAULT_BACKEND);
    if (backend.protocol !== 'http:') {
        throw new Error('The local backend URL must use http:');
    }
    const staticRoot = path.resolve(options.staticRoot || DEFAULT_STATIC_ROOT);
    const logger = options.logger || console;

    return http.createServer((request, response) => {
        if (request.url === '/api' || request.url.startsWith('/api/')) {
            proxyApiRequest(request, response, backend, logger);
            return;
        }
        serveStaticFile(request, response, staticRoot);
    });
}

function parseArgs(argv) {
    const options = {
        host: DEFAULT_HOST,
        port: DEFAULT_PORT,
        backend: DEFAULT_BACKEND,
        staticRoot: DEFAULT_STATIC_ROOT
    };
    for (let index = 0; index < argv.length; index += 1) {
        const name = argv[index];
        const value = argv[index + 1];
        if (name === '--host' && value) options.host = value;
        else if (name === '--port' && value) options.port = Number(value);
        else if (name === '--backend' && value) options.backend = value;
        else if (name === '--static-root' && value) options.staticRoot = value;
        else throw new Error(`Unknown or incomplete argument: ${name}`);
        index += 1;
    }
    if (!Number.isInteger(options.port) || options.port < 0 || options.port > 65535) {
        throw new Error(`Invalid port: ${options.port}`);
    }
    return options;
}

function startFromCommandLine() {
    const options = parseArgs(process.argv.slice(2));
    const server = createDevServer(options);
    server.on('error', (error) => {
        console.error(`Local UI server failed: ${error.message}`);
        process.exitCode = 1;
    });
    server.listen(options.port, options.host, () => {
        const address = server.address();
        const port = typeof address === 'object' && address ? address.port : options.port;
        console.log(`Local UI: http://localhost:${port}`);
        console.log(`API proxy: ${options.backend}`);
        console.log('Press Ctrl+C to stop.');
    });
}

if (require.main === module) {
    startFromCommandLine();
}

module.exports = {
    createDevServer,
    filterHeaders,
    parseArgs,
    resolveStaticFile
};
