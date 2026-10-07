# 0008: Single reconciler, single writer

Status: accepted (Phase 4)

## Context
The operator enforces a budget: at most `maxConcurrentRemediations` nodes may be remediated at once. A budget is only safe if nothing can decide "there's room" for two nodes at the same moment. Node events arrive in bursts, and a Node change triggers a reconcile of the policy.

## Options
1. **Multiple replicas with leader election.** Highly available, but more moving parts.
2. **One replica, relying on JOSDK's per-resource serialization.** JOSDK never runs two reconciles of the same primary resource at the same time, and merges events that arrive while one is running.
3. **A reconciler per node.** Simple per node, but the budget becomes a race between reconcilers.

## Decision
Option 2: one operator replica, and one `PolicyReconciler` that owns the whole pool. All decisions for a policy (admit, count the budget, pick a spare) happen inside a single reconcile of that policy. Because JOSDK serializes those, the budget check and the admit are never interleaved with another writer.

## Consequences
- The budget is race-free without locks or leader election.
- The operator is a single point of failure. It's acceptable here: if it's down, no new remediations start, but existing state lives in the `NodeRemediation` objects and is picked up again on restart.
- The Deployment sets `replicas: 1`, and it's pinned to the control-plane so it never runs on a node it might drain.
- Reconciles are level-based and triggered by any Node change (heartbeats and status updates included), so one fault can cause several reconciles. The logic has to be idempotent.
- Moving to multiple replicas later needs leader election, and this ADR would be superseded.
