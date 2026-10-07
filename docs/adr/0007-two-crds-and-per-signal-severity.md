# 0007: Two CRDs, and per-signal severity

Status: accepted (Phase 4)

## Context
NodeMedic needs to store two different kinds of information:
- **Configuration**: which nodes a pool covers, which node conditions count as faults, how many remediations may run at once, cooldowns and timeouts. It changes rarely and is written by a human.
- **Per-incident state**: which node is in which phase, which faults were seen, which spare replaced it. It changes constantly and is written only by the operator.

Faults also aren't equally urgent. A GPU that has fallen off the bus (`GpuXidError`, XID 79) is unusable immediately. A flaky NVLink (`NvlinkDegraded`) still works and may recover.

## Options
1. **One CRD** holding both config and incident state in `spec` and `status`.
2. **Two CRDs**: `RemediationPolicy` (config, one per pool) and `NodeRemediation` (one per incident, named after the node).
3. **Two CRDs, plus one global threshold** ("N faults in a window") applied to every signal.
4. **Two CRDs, plus a severity on each signal.**

## Decision
Options 2 and 4: two cluster-scoped CRDs, and each signal carries `severity: Critical | Degraded`.
- **Critical** signals admit the node on the first fault and skip the threshold. They still go through the concurrency budget.
- **Degraded** signals admit the node only after `threshold.faults` faults within `threshold.window`.

Both CRDs are cluster-scoped. Nodes are cluster-scoped, and a cluster-scoped object can't be owned by a namespaced one, so a namespaced CRD would break the ownerReference planned for Phase 7.

## Consequences
- Incident state has its own lifecycle. `kubectl get nr` is the live view of what the operator is doing, with Node, Phase, Spare and Age columns. A `NodeRemediation` existing means "this node is in an incident", and nothing exists for healthy nodes.
- The status subresource separates the two writers: users edit `spec`, the operator writes `status`. RBAC can enforce that split.
- A single threshold would either delay fixing a dead GPU (threshold too high) or evict nodes on a blip (threshold too low). Per-signal severity avoids both, at the cost of a slightly bigger policy schema.
- `severity` is an enum, so the API server rejects typos such as `critical` at `kubectl apply` time.
- Durations (`cooldown`, `drainTimeout`, `threshold.window`) are strings like `"10m"`. They aren't ISO-8601, so they're parsed with Fabric8's Go-style `Duration` in Phase 7.
