#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

kind create cluster --config infra/kind-config.yaml
kubectl wait --for=condition=Ready nodes --all --timeout=180s
kubectl taint nodes -l nodemedic.io/role=spare nodemedic.io/spare=true:NoSchedule --overwrite
kubectl get nodes -L nodemedic.io/role
