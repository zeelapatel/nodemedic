# NodeMedic

[![CI](https://github.com/zeelapatel/nodemedic/actions/workflows/ci.yml/badge.svg)](https://github.com/zeelapatel/nodemedic/actions/workflows/ci.yml)

Kubernetes operator that detects faulty GPU nodes and replaces them from a warm spare pool
(cordon, PDB-aware drain, blast-radius budget). Java 21 · Spring Boot · JOSDK · Fabric8.

🚧 Work in progress.

## Quickstart (local)
```bash
./infra/up.sh            # 1 control-plane, 3 active + 2 spare workers (kind)
