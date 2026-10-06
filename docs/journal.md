# Journal

One line per day: what broke, how I fixed it.

- 2026-09-28: kubeadm init failed on WSL. Cause: cgroup v1 on old WSL kernel. Fix: wsl --update.
- 2026-09-28: Stopped a worker; pods took 5 min to move (default 300s unreachable toleration). Lowered to 30s.
- Cordon only stops new pods, Drain evicts pods while respecting PDBs and a NoExecute taint Kills pods without checking PDBs.
- The pod is still Running but gets no traffic. Readiness controls who receives traffic, not whether the pod is alive. The zero-downtime demo depends on this.