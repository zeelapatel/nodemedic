# Phase 2: Fabric8 Warm-up (temp guide)

Everything here runs **locally**. A Java app on your machine (WSL) talks to your local kind cluster through `~/.kube/config`.
`playground/` is just the name of the local folder for this throwaway code. It has nothing to do with online playgrounds like KillerCoda.

A throwaway Java 21 + Fabric8 console app. You won't use an operator framework yet.
The goal is to feel the raw API (list, watch, informer, patch, evict, conflict) before JOSDK hides it from you.

---

## 0. Before you start

**Cluster in a known state**
```bash
docker start nodemedic-worker nodemedic-worker2 2>/dev/null   # if you stopped them earlier
kubectl get nodes                                               # all Ready?
# or start fresh:
kind delete cluster --name nodemedic && bash infra/up.sh
kubectl apply -f nginx.yaml
```

**Where to put the code:** the roadmap says to keep code in the WSL filesystem (`~/code/nodemedic`), not `/mnt/f/...`.
Maven builds on `/mnt/*` are 5–10x slower. That's fine for a playground, but move the repo before Phase 4.

**Auth:** Fabric8 reads `~/.kube/config`, the same as kubectl, and uses kind's admin context. So there's no RBAC to set up here.
Check that `kubectl config current-context` shows `kind-nodemedic`.

---

## 1. Project setup

A local Maven project in the repo:
```
nodemadic/playground/
  pom.xml
  src/main/java/playground/
    ListNodes.java        # ex 1
    WatchNodes.java       # ex 2
    InformNodes.java      # ex 3
    CordonNode.java       # ex 4
    EvictPod.java         # ex 5
    Conflict409.java      # ex 6
```
Use one class with its own `main` per exercise. Small files are easier to compare later.

**pom.xml essentials**
- `<maven.compiler.release>21</maven.compiler.release>`
- `io.fabric8:kubernetes-client`: the latest 7.x from Maven Central. Pin it and note the version in your journal.
- `org.slf4j:slf4j-simple`: without it, Fabric8 logs nothing and connection errors are invisible.
- Run from the IDE, or add `exec-maven-plugin` and run `mvn -q compile exec:java -Dexec.mainClass=playground.ListNodes`.

**Client creation (all exercises)**
```java
try (KubernetesClient client = new KubernetesClientBuilder().build()) {
    ...
}
```
The client is `AutoCloseable` and holds threads and HTTP connections. Watches and informers keep the JVM alive, so block `main` on purpose (`Thread.currentThread().join()` or a `CountDownLatch`).

---

## Ex 1: List nodes

**Do:** print each node's name, labels, `spec.unschedulable`, and every condition (type, status, reason).

**API pointers**
- `client.nodes().list().getItems()`
- `node.getMetadata().getName()`, `.getLabels()`
- `node.getSpec().getUnschedulable()`: a `Boolean`. It's **null** when it was never set, so handle that.
- `node.getStatus().getConditions()`: `type`, `status` (a *string*: "True"/"False"/"Unknown"), `reason`, `lastTransitionTime`

**Check:** the output matches `kubectl get nodes -L nodemedic.io/role` and `kubectl describe node`.
Stop a node (`docker stop nodemedic-worker3`), wait about 40s, and run it again. `Ready` should now show `Unknown`.

**Notice:** the built-in conditions are `Ready`, `MemoryPressure`, `DiskPressure`, `PIDPressure`, and `NetworkUnavailable`. NodeMedic's custom conditions will sit next to them.

---

## Ex 2: Raw watch, print condition changes

**Do:** watch nodes and print a line **only when a condition's status changes**, for example:
`nodemedic-worker  GpuXidError  False -> True  (Xid79)`

**API pointers**
- `client.nodes().watch(new Watcher<Node>() { eventReceived(Action action, Node node); onClose(WatcherException e); })`
- `Action` is `ADDED`, `MODIFIED`, `DELETED`, `ERROR`, or `BOOKMARK`.

**Trigger it** (the Phase 1 ex 9 command, from another terminal):
```bash
kubectl patch node nodemedic-worker --subresource=status --type=strategic -p \
'{"status":{"conditions":[{"type":"GpuXidError","status":"True","reason":"Xid79","message":"simulated"}]}}'
# flip it back with "status":"False"
```

**Things you'll run into (this is the lesson)**
1. **The watch only gives you the new object.** To detect "False -> True", you have to keep the previous state yourself, for example in `Map<nodeName, Map<condType, status>>`.
2. **MODIFIED fires constantly.** The kubelet updates heartbeats and timestamps, so printing every event floods the output. Diff on `type + status` and ignore `lastHeartbeatTime`.
3. **On startup you get an ADDED event for every node.** That's the initial state, not real changes, so treat it as seeding your map.
4. **Kill the connection and see what happens.** Restart the control-plane (`docker restart nodemedic-control-plane`) and watch `onClose`. Fabric8 reconnects automatically, but anything that changed during the gap can be missed. Write down what you saw.

---

## Ex 3: SharedIndexInformer

**Do:** the same output as ex 2, but using an informer.

**API pointers**
- `SharedIndexInformer<Node> inf = client.nodes().inform(handler, resyncMillis);`
  (or `client.nodes().runnableInformer(resync)`, then `addEventHandler(...)`, then `.run()`)
- `ResourceEventHandler<Node>`: `onAdd(Node)`, `onUpdate(Node oldObj, Node newObj)`, `onDelete(Node, boolean deletedFinalStateUnknown)`
- The cache: `inf.getStore().list()` and `inf.getStore().getByKey("nodemedic-worker")` (cluster-scoped key = name)
- `inf.hasSynced()`: wait for it before trusting the cache.

**Compare with ex 2 and write down why the informer is better:**
| | Raw watch | Informer |
|---|---|---|
| Old object on update | you keep it yourself | `onUpdate(old, new)` |
| Reconnect / missed events | partly handled, but the gap can lose events | relists and resumes from `resourceVersion` |
| Local cache | none | `getStore()`, no API calls |
| Periodic resync | none | yes, `onUpdate` fires again with old == new |

**Experiments**
- Set resync to 30s. When `onUpdate` fires with the **same `resourceVersion`** for old and new, that's a resync, not a change. Print it so you can see it.
- Run `getStore().list()` in a loop every 5s and confirm it makes no API calls. You can check that with `kubectl get --raw /metrics | grep apiserver_request_total`, or just reason about it.
- Repeat the control-plane restart from ex 2 and compare the behavior.

**Key idea:** a resync exists so a reconciler can repair state even when no event arrives. That's the "level-triggered" part.

---

## Ex 4: Cordon from code

**Do:** set `spec.unschedulable = true` on a node, confirm it, then uncordon it. Try it **two ways**.

**A. `edit()`: read-modify-write**
```java
client.nodes().withName(n).edit(node ->
    new NodeBuilder(node).editSpec().withUnschedulable(true).endSpec().build());
```

**B. Patch: send only the diff**
```java
client.nodes().withName(n).patch(
    PatchContext.of(PatchType.JSON_MERGE), "{\"spec\":{\"unschedulable\":true}}");
```

**Check:** `kubectl get nodes` shows `Ready,SchedulingDisabled`. Scale nginx up (`kubectl scale deploy nginx --replicas=9`) and confirm no new pods land on that node.

**Write down:** how A and B differ. A sends the whole object, so it can conflict (ex 6). B sends only the fields that changed. Look at `kubectl get node <n> -o yaml` and note that cordon is **not** a taint, even though the scheduler treats it like one (`node.kubernetes.io/unschedulable`).

**Bonus:** add the taint `nodemedic.io/remediating=true:NoSchedule` from code. Watch out: a JSON merge patch on `spec.taints` **replaces the whole list**, so you'd delete the spare taint. Find out how to append safely (a JSON Patch `add` op, or `edit()`).

---

## Ex 5: Evict a pod from code

**Set up a PDB that blocks:**
```yaml
apiVersion: policy/v1
kind: PodDisruptionBudget
metadata: { name: nginx-pdb }
spec:
  minAvailable: 6        # with replicas: 6, no voluntary disruption is allowed
  selector: { matchLabels: { app: nginx } }
```

**Do**
1. Pick an nginx pod and evict it with `client.pods().inNamespace("default").withName(p).evict()`.
2. Note the **return value** and whether anything is **thrown**, in three cases:
   - PDB allows it (change `minAvailable` to 5)
   - PDB blocks it (`minAvailable: 6`): the server returns HTTP **429**
   - The pod doesn't exist (404)
3. Do the same with `kubectl proxy` and curl (Phase 1 ex 7) and compare the raw HTTP response with what Fabric8 gives you.

**Write down:** what Fabric8 turns a 429 into. A boolean? An exception? With what code? Your Phase 6 `Drainer` depends on this exact behavior.

**Also compare:** `client.pods()...delete()` on the same pod with the blocking PDB. It succeeds, because delete **bypasses PDBs**. That's ADR-0017 in one experiment.

---

## Ex 6: Trigger a 409 Conflict

**Do**
1. Read a node in Java and keep the object: `Node stale = client.nodes().withName(n).get();`
2. Pause (a `Scanner` read on stdin, or a breakpoint).
3. Change it from the terminal: `kubectl label node <n> touched=yes --overwrite`
4. Back in Java, modify `stale` (add a label) and send it **with its old `resourceVersion`**:
   `client.resource(stale).update();`
5. Catch `KubernetesClientException` and print `e.getCode()`. You should see **409**.

**Then try the same thing with:**
- `edit()`: it re-reads first, so no conflict (unless something changes in the tiny window between read and write).
- a patch: no `resourceVersion` is sent by default, so no conflict. That's last-writer-wins.
- setting `stale.getMetadata().setResourceVersion(null)` before `update()`: what happens?

**Write down:** when you *want* a 409 (the Phase 7 spare claim: two remediations must not grab the same spare) and when you don't (simple idempotent status writes).
The pattern is: on a 409, re-read, re-check, and retry.

---

## Done when
You can draw, without notes:
`API server ⇄ watch ⇄ informer (cache + resync) ⇄ work queue ⇄ reconcile`
and you can answer: *"The operator was down while 10 events fired. What happens?"*
(The informer relists on start, the reconcile sees the current state, and nothing is lost, because the reconciler acts on state, not on events.)

## Journal / ADR hooks
- `docs/journal.md`: the reconnect behavior from ex 2, resync events from ex 3, the 429 mapping from ex 5, and the 409 code from ex 6.
- ADR-0005 (JOSDK + Fabric8 vs Go) and ADR-0006 (Spring vs Quarkus) are due at the end of this phase.

## Pitfalls
- Forgetting `--subresource=status` in the kubectl patch: the condition never appears, and ex 2 looks broken when it isn't.
- `main` returning right away: watches and informers run on background threads, so block `main`.
- Missing slf4j binding: errors are swallowed silently.
- Merge-patching `spec.taints` or `status.conditions`: lists get replaced, not merged.
- Comparing `Boolean unschedulable` with `==`, or forgetting it can be `null`.
