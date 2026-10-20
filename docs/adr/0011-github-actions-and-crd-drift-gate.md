# 0011: GitHub Actions, with a CRD drift check as a CI gate

Status: accepted (Phase 6)

## Context
The repo is hosted on GitHub and has three things that can silently break:
- the Java build and its tests,
- the generated CRD YAML in `deploy/operator/crds/`, which is produced from the Java classes but committed, because the cluster and Kustomize need the file (ADR 0009),
- the Kustomize manifests for the operator and the demo app.

Nothing stopped someone from changing a CRD class and forgetting to commit the regenerated YAML. The code and the schema the API server enforces would then disagree without any error.

## Options
1. **CI service outside GitHub** (Jenkins, CircleCI, and similar). More to run and configure, with no benefit for a single repo.
2. **GitHub Actions**, built in and free for public repositories.
3. **Build only**, with no drift check. Simple, but it doesn't catch stale CRD YAML.
4. **Generate the CRDs at deploy time** instead of committing them. This removes drift, but then nothing in git shows a schema change in a pull request, and Kustomize and GitOps tools need the file in the repo.

## Decision
GitHub Actions, with `.github/workflows/ci.yml` running on pull requests and pushes to `main`. Three parallel jobs:
- **build-test:** `mvn -B verify` on Temurin 21, which compiles both modules and runs the tests.
- **crd-drift:** regenerate the CRDs with `mvn -pl operator process-classes`, then fail if `git status --porcelain` shows any change under `deploy/operator/crds/`. `git status` is used instead of `git diff` so that a brand-new, untracked CRD file is also caught.
- **manifest-validate:** `kubectl kustomize` for `deploy/operator` and `deploy/demo-app` must render.

The workflow has `permissions: contents: read` and needs no secrets.

## Consequences
- A change to a CRD class without the regenerated YAML turns the PR red, so the Java classes stay the single source of truth for the schema and the committed YAML cannot go stale.
- Schema changes show up in pull-request diffs.
- Each check was shown to fail for its own reason by breaking it on purpose (a failing test turns only `build-test` red).
- The checks do not compare the committed YAML with what is applied in a cluster, so drift between git and a live cluster is not detected. That belongs to GitOps in Phase 10.
- `kubectl kustomize` checks that the manifests render, not that every field is valid, so an unknown field in a manifest passes. `kubeconform` could add schema validation later.
- CI starts from a clean machine on every run, so a green check means the build works without anything left over on a developer's computer.
