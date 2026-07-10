# Interview Demo Runbook

This runbook keeps the interview demo short, repeatable, and easy to recover when an external provider is slow.

## Prerequisites

1. Run the default test suite:

```powershell
mvn clean test
```

2. Start the local dependencies needed by your profile:

```powershell
docker compose -f docs/dev-ops/docker-compose-redis-pgvector.yml up -d
```

3. Export runtime credentials in the current shell. Do not write real values into tracked files:

```powershell
$env:ZHIPU_API_KEY="..."
$env:DASHSCOPE_API_KEY="..."
$env:TUSHARE_TOKEN="..."
$env:MYSQL_URL="jdbc:mysql://localhost:3306/ai-agent-station?useSSL=false&serverTimezone=Asia/Shanghai"
$env:MYSQL_USER="..."
$env:MYSQL_PASSWORD="..."
```

4. Optional trace export:

```powershell
$env:LANGFUSE_ENABLED="true"
$env:LANGFUSE_HOST="..."
$env:LANGFUSE_PUBLIC_KEY="..."
$env:LANGFUSE_SECRET_KEY="..."
```

5. Start the app:

```powershell
mvn -pl ai-agent-study-app -am spring-boot:run
```

The app listens on `http://localhost:8090`.

## Demo Flow

### 1. Single Agent Chat

```powershell
curl.exe -N -H "Content-Type: application/json" --data-binary "@docs/demo/single-chat.json" http://localhost:8090/api/v1/agent/auto_agent
```

Talk track:
- The request goes through the unified AutoAgent entrypoint.
- The response is streamed through SSE.
- The system records `sessionId`, user input, model output, and trace metadata when observability is enabled.

### 2. Multi Task Decomposition

```powershell
curl.exe -N -H "Content-Type: application/json" --data-binary "@docs/demo/multi-task.json" http://localhost:8090/api/v1/agent/auto_agent
```

Talk track:
- A complex request is decomposed into subtasks.
- Each subtask runs with isolated context.
- The final answer is synthesized from subtask results.

### 3. Multi Agent Stock Analysis

Prefer the direct trading endpoint for the most stable interview demo:

```powershell
curl.exe -N -H "Content-Type: application/json" --data-binary "@docs/demo/trading-analysis.json" http://localhost:8090/api/v1/trading/analysis
```

Use the unified agent route only when you want to demonstrate intent routing:

```powershell
curl.exe -N -H "Content-Type: application/json" --data-binary "@docs/demo/stock-analysis.json" http://localhost:8090/api/v1/agent/auto_agent
```

Talk track:
- The trading graph selects fundamental, technical, sentiment, and news analysts.
- Skills expose tool contracts, while infrastructure adapters call market data and news providers.
- Risk rounds and debate rounds are bounded to keep the demo predictable.

### 4. Retry and Degradation

Use unit tests as the deterministic demo:

```powershell
mvn -pl ai-agent-study-domain -Dtest="RetryChatModelTest,RetryChatModelCornerTest,RetryChatModelStreamTest" test
```

Talk track:
- Retry happens only for retryable provider errors.
- The strategy avoids sleeping after the final attempt.
- Stream mode degrades safely when token budget or stream failures make direct streaming unsafe.

### 5. Trace View

When `LANGFUSE_ENABLED=true`, copy the emitted `traceId` from logs or response metadata and open it in Langfuse.

Show:
- Trace lifecycle
- `chat_client_call` span
- Generation input/output
- Latency and model metadata
- Error metadata for failed or degraded calls

## Recovery Plan

- If provider latency is high, switch from the unified stock prompt to `docs/demo/trading-analysis.json`.
- If Langfuse is unavailable, leave `LANGFUSE_ENABLED=false`; the app still generates trace IDs and the demo can continue.
- If online model calls are unavailable, use the retry unit-test command to prove the failure handling behavior.
