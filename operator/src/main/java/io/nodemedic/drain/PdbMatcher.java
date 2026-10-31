package io.nodemedic.drain;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.policy.v1.PodDisruptionBudget;

import java.util.List;
import java.util.Map;

/**
 * Names the PodDisruptionBudgets that are probably behind a refused eviction. Fabric8 turns an
 * eviction 429 into a plain {@code false}, so the API's own explanation is not available.
 * Only {@code matchLabels} is evaluated; a PDB that selects with {@code matchExpressions} alone
 * is not reported.
 */
public final class PdbMatcher {

    private PdbMatcher() {
    }

    /** PDBs in the pod's namespace that select it, those with no disruptions left listed first. */
    public static List<String> blocking(Pod pod, List<PodDisruptionBudget> pdbs) {
        Map<String, String> labels = pod.getMetadata().getLabels() == null ? Map.of() : pod.getMetadata().getLabels();

        List<PodDisruptionBudget> matching = pdbs.stream()
                .filter(p -> selects(p, labels))
                .toList();

        List<PodDisruptionBudget> exhausted = matching.stream()
                .filter(p -> p.getStatus() != null && p.getStatus().getDisruptionsAllowed() != null
                        && p.getStatus().getDisruptionsAllowed() <= 0)
                .toList();

        return (exhausted.isEmpty() ? matching : exhausted).stream()
                .map(p -> p.getMetadata().getNamespace() + "/" + p.getMetadata().getName())
                .sorted()
                .toList();
    }

    private static boolean selects(PodDisruptionBudget pdb, Map<String, String> podLabels) {
        if (pdb.getSpec() == null || pdb.getSpec().getSelector() == null) {
            return false;
        }
        var selector = pdb.getSpec().getSelector();
        Map<String, String> wanted = selector.getMatchLabels() == null ? Map.of() : selector.getMatchLabels();
        boolean noExpressions = selector.getMatchExpressions() == null || selector.getMatchExpressions().isEmpty();
        if (wanted.isEmpty() && noExpressions) {
            return true;                                  // an empty selector matches every pod in the namespace
        }
        return !wanted.isEmpty() && wanted.entrySet().stream()
                .allMatch(e -> e.getValue().equals(podLabels.get(e.getKey())));
    }
}
