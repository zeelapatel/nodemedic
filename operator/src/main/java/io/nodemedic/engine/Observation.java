package io.nodemedic.engine;

import io.fabric8.kubernetes.api.model.Node;
import io.nodemedic.crd.NodeRemediation;

/** One pool node as seen right now, with its NodeRemediation if one exists (else null). */
public record Observation(Node node, NodeRemediation remediation) {

    public String nodeName() {
        return node.getMetadata().getName();
    }
}
