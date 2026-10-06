#!/usr/bin/env bash
# Simulate a GPU fault by writing a NodeMedic condition onto a node's status.
# usage: inject-fault.sh <node> <GpuXidError|NvlinkDegraded> <True|False> [reason]
#
# Simulated failure types (stand-ins for what NVIDIA DCGM / node-problem-detector report):
#
#   GpuXidError     A hard GPU failure. XID errors are fault codes the NVIDIA driver writes
#                   to the kernel log. We simulate XID 79, "GPU has fallen off the bus":
#                   the GPU is no longer visible on PCIe, so any job using it crashes and
#                   only a reset or hardware repair fixes it. Other real examples:
#                   XID 48 (double-bit ECC memory error), XID 63/64 (row-remapping failure).
#                   Severity: critical, remediate on the first occurrence.
#
#   NvlinkDegraded  A GPU interconnect problem. NVLink is the high-speed link between GPUs
#                   in one node; when a link drops or flaps, GPUs still work but multi-GPU
#                   training slows down or hangs on collective ops (all-reduce).
#                   Severity: degraded, remediate only if it repeats (threshold in a window).
#
# The kubelet ignores condition types it doesn't own, so these stay until changed.
set -euo pipefail

usage() {
  echo "usage: $0 <node> <GpuXidError|NvlinkDegraded> <True|False> [reason]" >&2
  exit 1
}
[[ $# -ge 3 ]] || usage

NODE=$1 TYPE=$2 STATUS=$3 REASON=${4:-}

case "$TYPE" in
  GpuXidError)    MSG="Xid79: GPU has fallen off the bus (simulated)" ;;
  NvlinkDegraded) MSG="NVLink link down (simulated)" ;;
  *) echo "unknown condition: $TYPE" >&2; usage ;;
esac

case "$STATUS" in
  True)  REASON=${REASON:-Simulated} ;;
  False) REASON=${REASON:-Cleared}; MSG="cleared" ;;
  *) echo "status must be True or False" >&2; usage ;;
esac

kubectl get node "$NODE" >/dev/null

# lastTransitionTime is set on every call, so repeated True injects count as separate faults
NOW=$(date -u +%Y-%m-%dT%H:%M:%SZ)
PATCH=$(printf '{"status":{"conditions":[{"type":"%s","status":"%s","reason":"%s","message":"%s","lastTransitionTime":"%s","lastHeartbeatTime":"%s"}]}}' \
  "$TYPE" "$STATUS" "$REASON" "$MSG" "$NOW" "$NOW")

# strategic merge patches conditions by "type", so Ready and the kubelet's other conditions are untouched
kubectl patch node "$NODE" --subresource=status --type=strategic -p "$PATCH" >/dev/null
echo "$(date +%T)  $NODE  $TYPE=$STATUS  ($REASON)"
