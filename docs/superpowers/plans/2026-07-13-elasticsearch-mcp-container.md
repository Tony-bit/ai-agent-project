# Elasticsearch MCP Container Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the Java application image able to launch the Elasticsearch MCP stdio server.

**Architecture:** Add Node.js and npm to the existing JRE image, then install a pinned Elasticsearch MCP package into a stable container path. Keep runtime selection in the existing database-backed MCP configuration.

**Tech Stack:** Docker, Eclipse Temurin 17 JRE, Node.js, npm, Elasticsearch MCP

---

### Task 1: Add Elasticsearch MCP runtime dependencies

| Task | status |
|------|------|
| Task 1: Add Elasticsearch MCP runtime dependencies | append |

**Files:**
- Modify: `ai-agent-study-app/Dockerfile`

- [ ] **Step 1: Add Node.js and npm installation**

Add an `apt-get` layer that installs `nodejs` and `npm` without recommended packages and removes the apt package lists.

- [ ] **Step 2: Install the pinned MCP package**

Create `/opt/elasticsearch-mcp` and install `@awesome-ai/elasticsearch-mcp@1.0.7` there with production dependencies only.

- [ ] **Step 3: Validate the Dockerfile**

Run:

```powershell
docker build -t system/ai-agent-study-app:1.0 ./ai-agent-study-app
```

Expected: the image builds successfully.

- [ ] **Step 4: Validate the image contents**

Run:

```powershell
docker run --rm system/ai-agent-study-app:1.0 node --version
docker run --rm --entrypoint sh system/ai-agent-study-app:1.0 -c "test -f /opt/elasticsearch-mcp/node_modules/@awesome-ai/elasticsearch-mcp/dist/index.js"
```

Expected: Node prints its version and the file check exits with status 0.

- [ ] **Step 5: Commit**

```powershell
git add ai-agent-study-app/Dockerfile docs/superpowers/plans/2026-07-13-elasticsearch-mcp-container-design.md docs/superpowers/plans/2026-07-13-elasticsearch-mcp-container.md
git commit -m "build: add elasticsearch mcp runtime to app image"
```
