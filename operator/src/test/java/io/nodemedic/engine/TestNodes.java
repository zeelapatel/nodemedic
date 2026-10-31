package io.nodemedic.engine;

import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.NodeBuilder;

import java.util.Map;

/** Builders for test nodes. */
final class TestNodes {

    private TestNodes() {
    }

    static Node withLabels(Map<String, String> labels) {
        return new NodeBuilder().withNewMetadata().withName("n1").withLabels(labels).endMetadata().build();
    }

    /** A node whose status holds one condition per (type, status, lastTransitionTime) triple. */
    static Node withConditions(String... typeStatusTime) {
        var builder = new NodeBuilder().withNewMetadata().withName("n1").endMetadata().withNewStatus();
        for (int i = 0; i < typeStatusTime.length; i += 3) {
            builder.addNewCondition()
                    .withType(typeStatusTime[i])
                    .withStatus(typeStatusTime[i + 1])
                    .withLastTransitionTime(typeStatusTime[i + 2])
                    .endCondition();
        }
        return builder.endStatus().build();
    }

    /** Like {@link #withConditions(String...)} but with a chosen node name. */
    static Node named(String name, String... typeStatusTime) {
        var builder = new NodeBuilder().withNewMetadata().withName(name).endMetadata().withNewStatus();
        for (int i = 0; i < typeStatusTime.length; i += 3) {
            builder.addNewCondition()
                    .withType(typeStatusTime[i])
                    .withStatus(typeStatusTime[i + 1])
                    .withLastTransitionTime(typeStatusTime[i + 2])
                    .endCondition();
        }
        return builder.endStatus().build();
    }
}
