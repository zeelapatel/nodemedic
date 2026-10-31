package io.nodemedic.drain;

import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.api.model.Pod;

/** Decides what a drain does with each pod on the node, the way {@code kubectl drain} does. */
public final class PodFilter {

    public enum Kind {
        /** Has a controller that will recreate it elsewhere; evict it. */
        EVICT,
        /** Already shutting down; wait for it, don't evict it again. */
        TERMINATING,
        /** No controller owns it; evicting would lose it for good, so a human decides (D6). */
        BARE,
        /** DaemonSet, mirror or finished pod; the drain doesn't care about these. */
        IGNORED
    }

    private static final String MIRROR_ANNOTATION = "kubernetes.io/config.mirror";

    private PodFilter() {
    }

    public static Kind classify(Pod pod) {
        if (finished(pod) || isMirror(pod) || isDaemonSetPod(pod)) {
            return Kind.IGNORED;
        }
        if (pod.getMetadata().getDeletionTimestamp() != null) {
            return Kind.TERMINATING;
        }
        if (!hasController(pod)) {
            return Kind.BARE;
        }
        return Kind.EVICT;
    }

    private static boolean finished(Pod pod) {
        if (pod.getStatus() == null) {
            return false;
        }
        String phase = pod.getStatus().getPhase();
        return "Succeeded".equals(phase) || "Failed".equals(phase);
    }

    /** Static pods are managed by the kubelet from a file; the API only holds a read-only mirror. */
    private static boolean isMirror(Pod pod) {
        var annotations = pod.getMetadata().getAnnotations();
        return annotations != null && annotations.containsKey(MIRROR_ANNOTATION);
    }

    /** A DaemonSet would put the pod straight back on the same node, so evicting is pointless. */
    private static boolean isDaemonSetPod(Pod pod) {
        return owners(pod).stream().anyMatch(o -> "DaemonSet".equals(o.getKind()));
    }

    private static boolean hasController(Pod pod) {
        return owners(pod).stream().anyMatch(o -> Boolean.TRUE.equals(o.getController()));
    }

    private static java.util.List<OwnerReference> owners(Pod pod) {
        var owners = pod.getMetadata().getOwnerReferences();
        return owners == null ? java.util.List.of() : owners;
    }
}
