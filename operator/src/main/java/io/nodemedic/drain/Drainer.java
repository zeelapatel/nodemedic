package io.nodemedic.drain;

import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.policy.v1.EvictionBuilder;
import io.fabric8.kubernetes.api.model.policy.v1.PodDisruptionBudget;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.RequestConfig;
import io.fabric8.kubernetes.client.RequestConfigBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * One bounded round of draining a node: evict what can be evicted, report the rest. It never
 * waits and never loops; the reconciler calls it again after a delay (ADR 0014). It evicts
 * only, never deletes, so a PodDisruptionBudget can always say no.
 */
public class Drainer {

    /** Status message once the node holds nothing that still needs to move. */
    public static final String DRAINED = "drained";

    private static final Logger log = LoggerFactory.getLogger(Drainer.class);

    /**
     * By default the client retries a 429 (and 5xx) with backoff, which would hold the reconcile
     * thread for many seconds per blocked pod. A refused eviction has to come back at once so the
     * reconciler can record it and requeue; JOSDK's own retry handles real errors.
     */
    private static final RequestConfig NO_RETRY = new RequestConfigBuilder().withRequestRetryBackoffLimit(0).build();

    private final KubernetesClient client;
    /** A light copy of the client (same connection, different request config) for evictions. */
    private final KubernetesClient evictionClient;

    public Drainer(KubernetesClient client) {
        this.client = client;
        this.evictionClient = client.newClient(NO_RETRY).adapt(KubernetesClient.class);
    }

    /** A pod whose eviction was refused, with the PDBs that probably caused it. */
    public record Blocked(String pod, List<String> pdbs) {
    }

    /** What one round did and what is left. */
    public record Round(List<String> evicted, List<String> terminating, List<String> bare, List<Blocked> blocked) {

        /** Nothing is left that still has to leave the node. */
        public boolean done() {
            return evicted.isEmpty() && terminating.isEmpty() && bare.isEmpty() && blocked.isEmpty();
        }

        /** Human-readable status for NodeRemediation.status.message. */
        public String message() {
            if (done()) {
                return DRAINED;
            }
            List<String> parts = new ArrayList<>();
            if (!evicted.isEmpty()) {
                parts.add("evicted " + evicted.size());
            }
            if (!terminating.isEmpty()) {
                parts.add("waiting for " + terminating.size() + " terminating");
            }
            if (!blocked.isEmpty()) {
                String pdbs = blocked.stream().flatMap(b -> b.pdbs().stream()).distinct().sorted()
                        .collect(Collectors.joining(", "));
                parts.add(blocked.size() + " blocked by PDB " + (pdbs.isEmpty() ? "(unknown)" : pdbs));
            }
            if (!bare.isEmpty()) {
                parts.add(bare.size() + " without a controller, not evicted: " + preview(bare));
            }
            return "draining: " + String.join("; ", parts);
        }

        private static String preview(List<String> names) {
            return names.size() <= 3 ? String.join(", ", names)
                    : String.join(", ", names.subList(0, 3)) + ", ...";
        }
    }

    public Round run(String nodeName) {
        List<Pod> pods = client.pods().inAnyNamespace().withField("spec.nodeName", nodeName).list().getItems()
                .stream()
                .sorted(Comparator.comparing((Pod p) -> p.getMetadata().getNamespace())
                        .thenComparing(p -> p.getMetadata().getName()))
                .toList();

        List<String> evicted = new ArrayList<>();
        List<String> terminating = new ArrayList<>();
        List<String> bare = new ArrayList<>();
        List<Blocked> blocked = new ArrayList<>();

        for (Pod pod : pods) {
            String id = pod.getMetadata().getNamespace() + "/" + pod.getMetadata().getName();
            switch (PodFilter.classify(pod)) {
                case IGNORED -> { }
                case TERMINATING -> terminating.add(id);
                case BARE -> bare.add(id);
                case EVICT -> {
                    switch (evict(pod)) {
                        case ACCEPTED -> {
                            log.info("node {}: evicted pod {}", nodeName, id);
                            evicted.add(id);
                        }
                        case REFUSED -> blocked.add(new Blocked(id, pdbsFor(pod)));
                        case GONE -> { }             // 404: already deleted, which is what we wanted
                    }
                }
            }
        }
        return new Round(evicted, terminating, bare, blocked);
    }

    private enum Outcome { ACCEPTED, REFUSED, GONE }

    private Outcome evict(Pod pod) {
        var eviction = new EvictionBuilder()
                .withMetadata(new ObjectMetaBuilder()
                        .withName(pod.getMetadata().getName())
                        .withNamespace(pod.getMetadata().getNamespace())
                        .build())
                .build();
        try {
            // true = accepted; false = 429, a PDB (or rate limit) says "not now"; anything else throws
            boolean accepted = evictionClient.pods().inNamespace(pod.getMetadata().getNamespace())
                    .withName(pod.getMetadata().getName()).evict(eviction);
            return accepted ? Outcome.ACCEPTED : Outcome.REFUSED;
        } catch (KubernetesClientException e) {
            if (e.getCode() == 404) {
                return Outcome.GONE;
            }
            throw e;
        }
    }

    private List<String> pdbsFor(Pod pod) {
        try {
            List<PodDisruptionBudget> pdbs = client.policy().v1().podDisruptionBudget()
                    .inNamespace(pod.getMetadata().getNamespace()).list().getItems();
            return PdbMatcher.blocking(pod, pdbs);
        } catch (RuntimeException e) {
            log.warn("could not read PodDisruptionBudgets to explain a refused eviction: {}", e.getMessage());
            return List.of();
        }
    }
}
