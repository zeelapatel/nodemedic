package io.nodemedic.engine;

import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.NodeCondition;
import io.nodemedic.crd.Signal;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class Faults {

    private Faults() {
    }

    /**
     * The policy's signals that are currently active on the node: a condition whose type is a
     * configured signal and whose status is "True". Conditions without a usable
     * lastTransitionTime are skipped, because a fault can't be deduplicated without it.
     * Sorted by type, then time, so results are deterministic.
     */
    public static List<ActiveFault> activeFaults(Node node, List<Signal> signals) {
        if (node == null || node.getStatus() == null || node.getStatus().getConditions() == null
                || signals == null) {
            return List.of();
        }
        Map<String, Signal> byType = new HashMap<>();
        for (Signal s : signals) {
            byType.putIfAbsent(s.getConditionType(), s);   // first definition wins
        }

        List<ActiveFault> result = new ArrayList<>();
        for (NodeCondition c : node.getStatus().getConditions()) {
            Signal signal = byType.get(c.getType());
            if (signal == null || !"True".equals(c.getStatus())) {
                continue;
            }
            Instant at = parse(c.getLastTransitionTime());
            if (at != null) {
                result.add(new ActiveFault(c.getType(), signal.getSeverity(), at));
            }
        }
        result.sort(Comparator.comparing(ActiveFault::type).thenComparing(ActiveFault::at));
        return result;
    }

    private static Instant parse(String rfc3339) {
        if (rfc3339 == null || rfc3339.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(rfc3339).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
