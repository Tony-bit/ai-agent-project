# Elasticsearch MCP Container Design

## Context

The application starts Elasticsearch MCP as a stdio child process. The production Java container currently contains only a JRE and the application JAR, so it cannot execute `node` or load the MCP server package.

## Decision

Install the distribution-provided `nodejs` and `npm` packages in the application image. During the image build, install the pinned `@awesome-ai/elasticsearch-mcp@1.0.7` package under `/opt/elasticsearch-mcp`.

The runtime MCP configuration must use `/usr/bin/node` and `/opt/elasticsearch-mcp/node_modules/@awesome-ai/elasticsearch-mcp/dist/index.js`. Host paths and Windows paths are not valid inside the Linux container.

## Verification

Build the image, then verify that `node --version` succeeds and the MCP entry point exists inside the resulting container. Application logs must no longer contain `Cannot run program "node"`.
