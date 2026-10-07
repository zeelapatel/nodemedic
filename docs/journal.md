# Journal

One line per day: what broke, how I fixed it.

- 2026-09-28: kubeadm init failed on WSL. Cause: cgroup v1 on old WSL kernel. Fix: wsl --update.
- 2026-09-28: Stopped a worker; pods took 5 min to move (default 300s unreachable toleration). Lowered to 30s.
- Cordon only stops new pods, Drain evicts pods while respecting PDBs and a NoExecute taint Kills pods without checking PDBs.
- The pod is still Running but gets no traffic. Readiness controls who receives traffic, not whether the pod is alive. The zero-downtime demo depends on this.
- 2026-10-06: Phase 4 build failed with "cannot find symbol" and "duplicate class: Signal". Cause: the CRD and reconciler files had no `package` line and no imports. Fix: added them.
- 2026-10-06: `./tools/inject-fault.sh` gave Permission denied. Cause: the scripts were committed without the executable bit (repo started on /mnt/f). Fix: chmod +x and `git update-index --chmod=+x`.
- 2026-10-06: `kubectl auth can-i create pods/eviction --as=...` said no although the role has the rule. Cause: that spelling isn't treated as a subresource. Fix: `can-i create pods --subresource=eviction`.
- 2026-10-06: `kubectl apply -f samples/policy.yaml` said the path doesn't exist. Cause: I was in the old clone on /mnt/f, not ~/nodemedic. Fix: work only in ~/nodemedic.
- 2026-10-06: `git push` got 403 although gh was logged in. Cause: the token was a fine-grained PAT without Contents: write. Fix: gave the token write access, then the push worked.
