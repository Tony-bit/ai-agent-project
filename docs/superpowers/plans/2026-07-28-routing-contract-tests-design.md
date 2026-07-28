# Routing Contract Tests Design

## Goal

Add fast, deterministic unit-test coverage for small routing transformations,
node-to-node transitions, and parameter propagation. Prevent regressions where
a field, context value, task order, or routing choice is changed or dropped
between adjacent nodes.

## Scope

Extend the existing domain-layer unit tests for:

- `AnalysisDepthFollowUpResolver`
- `IntentRoutingNode`
- `QueryDecompositionNode`
- `TaskRoutingSlotNode`
- `RoutingResultHandler`

The tests cover the current `完整的投资分析` alias fix and the surrounding
routing contracts. Production behavior is not changed by this work.

## Test Strategy

Use layered contract tests in the existing test classes:

1. Test pure transformations directly, including accepted aliases,
   normalization, derived intent and executor values, and preservation of
   existing slots and metrics.
2. Capture arguments at service and node boundaries and assert every relevant
   field instead of only verifying that a dependency was called.
3. Verify branch transitions for clarification, single-task, multi-task, and
   graph-validation fallback paths.
4. Verify identity where adjacent nodes must receive the same request or
   dynamic-context instance.

## Coverage

### Analysis depth follow-up

- Recognize `完整的投资分析` as a full-analysis choice.
- Preserve punctuation and whitespace normalization behavior.
- Restore the nearest eligible financial request and derive the expected
  effective query, intent, and executor node.
- Preserve model-provided slots and metrics during enforcement.
- Leave unresolved input and model output unchanged.

### Unified routing node

- Pass message, prepared history, client configuration, session ID, and trace
  context to the routing service without substitution.
- When a follow-up is resolved, copy all `ExecuteCommandEntity` fields into the
  effective downstream request while changing only the message.
- When no follow-up is resolved, pass the original request unchanged.
- Keep clarification responses out of business execution nodes.

### Split routing nodes

- Pass the original message, prepared history, client configuration, session
  ID, and trace context into query decomposition.
- Store the decomposition result, stage metrics, and start time under the
  expected dynamic-context keys.
- Pass the same request and context into `TaskRoutingSlotNode`.
- Sort decomposed tasks by `taskIndex`, then pass each task's content, task ID,
  derived sequence number, history, configuration, session ID, and trace
  context into slot routing.
- Pass converted subtasks and accumulated metrics to `RoutingResultHandler`.
- Verify fallback arguments and results when graph validation fails.

### Routing result handler

- Verify clarification, single-task, and multi-task state conversions.
- Verify stored context keys, original message, slots, and selected executor.
- Pass the same request and context instances into the selected downstream
  node.
- Do not invoke an execution node for clarification results.

## Isolation

All third-party components and external boundaries are mocked, including model
routing services, conversation context providers, Spring application-context
lookups, repositories, and downstream execution nodes. The tests do not start
Spring, access a database, call a network service, or invoke a real model.

## Verification

Run the five focused test classes through the Maven domain module, then run the
complete `ai-agent-study-domain` unit-test suite. Any unrelated pre-existing
failure is reported separately and is not hidden by changes to production
code.
