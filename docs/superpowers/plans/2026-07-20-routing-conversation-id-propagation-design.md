# Routing Conversation ID Propagation Design

## Goal

Ensure every model call made during one routing request uses the exact `sessionId`
from `ExecuteCommandEntity` as its Spring AI conversation ID. Routing calls must
never fall back to the shared `default` conversation.

## Design

- `IntentRoutingNode`, `QueryDecompositionNode`, and `TaskRoutingSlotNode` pass
  `request.getSessionId()` explicitly into `IntentRoutingService`.
- `IntentRoutingService` adds the same value to the advisor context under
  `ChatMemory.CONVERSATION_ID` for unified routing, query decomposition, and
  every task-slot routing call.
- Each call also carries its explicit conversation scene (`routing`,
  `decomposition`, or `slot`) so `ConversationContextAdvisor` never treats a
  routing model call as a chat-memory call.
- Existing service overloads remain available for offline evaluation and older
  callers. They still carry an explicit non-chat scene, so an absent session ID
  cannot create or update the `default` chat-memory entry.
- Routing prompts already contain the prepared history. An advisor-context flag
  identifies this condition and prevents the advisor from injecting the same
  history a second time.

## Invariants

- One incoming `sessionId` maps to one unchanged conversation ID throughout the
  complete routing pipeline.
- No task ID, stage name, suffix, generated value, or default value may replace
  the incoming conversation ID.
- Non-chat routing calls do not read or write through `MessageChatMemoryAdvisor`.

## Verification

- Node tests verify that the exact request session ID is passed to the service.
- Service/advisor tests verify the conversation ID, scene, and preloaded-history
  behavior.
- Existing routing and chat-memory test suites continue to pass.
