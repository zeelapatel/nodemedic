package io.nodemedic.k8s;

import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.PatchContext;
import io.fabric8.kubernetes.client.dsl.base.PatchType;
import io.nodemedic.crd.NodeRemediation;

import java.time.Instant;

/**
 * Makes a node unschedulable. A merge patch of just the fields we own is used instead of
 * read-modify-write, because the kubelet updates the same Node object constantly and an
 * {@code edit()} could overwrite its changes.
 */
public class NodeCordoner {

    public static final String CORDONED_BY = "nodemedic.io/cordoned-by";
    public static final String CORDONED_AT = "nodemedic.io/cordoned-at";
    public static final String OWNER = "nodemedic";

    private final KubernetesClient client;

    public NodeCordoner(KubernetesClient client) {
        this.client = client;
    }

    /**
     * Cordons the node if it isn't already. Reads the Node live (not from the informer cache,
     * which may not show our last write yet), so a repeated call does nothing and emits nothing.
     *
     * @return true if this call cordoned the node
     */
    public boolean ensureCordoned(String nodeName, NodeRemediation remediation, Instant now) {
        Node node = client.nodes().withName(nodeName).get();
        if (node == null) {
            return false;                                   // node is gone; nothing to cordon
        }
        if (node.getSpec() != null && Boolean.TRUE.equals(node.getSpec().getUnschedulable())) {
            return false;
        }

        String patch = "{\"metadata\":{\"annotations\":{\"" + CORDONED_BY + "\":\"" + OWNER + "\",\""
                + CORDONED_AT + "\":\"" + now + "\"}},\"spec\":{\"unschedulable\":true}}";
        Node updated = client.nodes().withName(nodeName).patch(PatchContext.of(PatchType.JSON_MERGE), patch);

        Events.emit(client, updated, Events.NORMAL, "Cordoned",
                "NodeMedic cordoned the node after a fault", now);
        if (remediation != null) {
            Events.emit(client, remediation, Events.NORMAL, "Cordoned",
                    "Node " + nodeName + " cordoned", now);
        }
        return true;
    }
}
