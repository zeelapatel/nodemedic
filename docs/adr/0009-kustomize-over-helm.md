# 0009: Kustomize over Helm

Status: accepted (Phase 4)

## Context
The operator ships as a handful of manifests: a namespace, two generated CRDs, RBAC and a Deployment. Phase 6 CI needs to validate them, and later phases (Argo CD) need to deploy them.

## Options
1. **Helm chart.** Templating with values, packaging, and a large ecosystem.
2. **Kustomize.** Built into `kubectl`, plain YAML with overlays and patches, no templating.
3. **Raw `kubectl apply -f` on each file.**

## Decision
Kustomize. `deploy/operator/kustomization.yaml` lists the resources and sets the namespace; `kubectl apply -k deploy/operator` installs everything.

## Consequences
- No extra tool to install. `kubectl kustomize deploy/operator` renders the bundle for CI to check.
- Every file stays valid YAML that can be applied on its own and reviewed in a diff.
- The generated CRDs are included untouched. They're regenerated from the Java classes, so they must never be edited by hand, and CI will diff them for drift in Phase 6.
- Overlays and the `images:` field cover what's needed later (per-environment changes, swapping the placeholder image tag in Phase 9).
- There's no templating or chart packaging. If the project ever needs configurable installs for other people, a Helm chart can be added on top.
