#!/usr/bin/env bash
# Set every NodeMedic condition to False (kept, not deleted: "cleared" differs from "never reported").
# usage: clear-faults.sh <node>... | --all
set -euo pipefail

DIR="$(cd "$(dirname "$0")" && pwd)"
CONDITIONS=(GpuXidError NvlinkDegraded)

[[ $# -ge 1 ]] || { echo "usage: $0 <node>... | --all" >&2; exit 1; }

if [[ $1 == --all ]]; then
  mapfile -t NODES < <(kubectl get nodes -l nodemedic.io/pool=gpu -o name | sed 's|^node/||')
else
  NODES=("$@")
fi

for node in "${NODES[@]}"; do
  for c in "${CONDITIONS[@]}"; do
    "$DIR/inject-fault.sh" "$node" "$c" False
  done
done
