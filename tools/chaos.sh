#!/usr/bin/env bash
# Randomly inject GPU faults into active nodes until Ctrl+C, then clear them all.
set -euo pipefail

DIR="$(cd "$(dirname "$0")" && pwd)"

usage() {
  cat <<EOF
usage: $0 [--rate N] [--flap] [--correlated N]
  --rate N         fault rounds per minute (default 2)
  --flap           toggle the fault True/False a few times instead of a single True
  --correlated N   hit N active nodes at the same moment each round (default 1)
EOF
}

RATE=2 FLAP=false CORRELATED=1
while [[ $# -gt 0 ]]; do
  case "$1" in
    --rate)       RATE=${2:?--rate needs a value}; shift 2 ;;
    --correlated) CORRELATED=${2:?--correlated needs a value}; shift 2 ;;
    --flap)       FLAP=true; shift ;;
    -h|--help)    usage; exit 0 ;;
    *) echo "unknown flag: $1" >&2; usage >&2; exit 1 ;;
  esac
done

[[ $RATE =~ ^[1-9][0-9]*$ ]]       || { echo "--rate must be a positive integer" >&2; exit 1; }
[[ $CORRELATED =~ ^[1-9][0-9]*$ ]] || { echo "--correlated must be a positive integer" >&2; exit 1; }

SLEEP=$((60 / RATE))
(( SLEEP > 0 )) || SLEEP=1

cleanup() {
  echo "stopping, clearing faults..."
  "$DIR/clear-faults.sh" --all
  exit 0
}
trap cleanup INT TERM

echo "chaos: rate=$RATE/min flap=$FLAP correlated=$CORRELATED  (Ctrl+C to stop and clean up)"

while true; do
  # only active nodes: spares are idle and quarantined nodes are already out of service
  mapfile -t ACTIVE < <(kubectl get nodes -l nodemedic.io/role=active -o name | sed 's|^node/||')
  if (( ${#ACTIVE[@]} == 0 )); then
    echo "$(date +%T)  no active nodes"
    sleep "$SLEEP"
    continue
  fi

  n=$CORRELATED
  if (( n > ${#ACTIVE[@]} )); then
    echo "warning: only ${#ACTIVE[@]} active nodes, hitting all of them"
    n=${#ACTIVE[@]}
  fi

  mapfile -t TARGETS < <(printf '%s\n' "${ACTIVE[@]}" | shuf -n "$n")
  TYPE=$(shuf -n 1 -e GpuXidError NvlinkDegraded)

  if [[ $FLAP == true ]]; then
    for _ in 1 2 3; do
      for node in "${TARGETS[@]}"; do "$DIR/inject-fault.sh" "$node" "$TYPE" True Flapping; done
      sleep 2
      for node in "${TARGETS[@]}"; do "$DIR/inject-fault.sh" "$node" "$TYPE" False Flapping; done
      sleep 2
    done
  else
    for node in "${TARGETS[@]}"; do "$DIR/inject-fault.sh" "$node" "$TYPE" True; done
  fi

  sleep "$SLEEP"
done
