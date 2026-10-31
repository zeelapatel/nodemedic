package io.nodemedic.engine;

import io.fabric8.kubernetes.api.model.Node;

import java.util.Map;

public final class NodePool {

    private NodePool() {
    }

    /**
     * True if every selector entry is present on the node's labels with the same value.
     * An empty or missing selector matches nothing: unlike kubectl, an operator that drains
     * nodes must not treat "no selector" as "the whole cluster".
     */
    public static boolean matches(Node node, Map<String, String> selector) {
        if (node == null || selector == null || selector.isEmpty()) {
            return false;
        }
        Map<String, String> labels = node.getMetadata() == null ? null : node.getMetadata().getLabels();
        if (labels == null) {
            return false;
        }
        return selector.entrySet().stream()
                .allMatch(e -> e.getValue() != null && e.getValue().equals(labels.get(e.getKey())));
    }
}
