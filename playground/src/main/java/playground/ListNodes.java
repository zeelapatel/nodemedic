package playground;

import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.NodeCondition;
import io.fabric8.kubernetes.client.KubernetesClient;

import java.util.List;
import java.util.Map;

/** Ex 1: list nodes with labels, unschedulable flag, and every condition. */
public class ListNodes {

    public static void main(String[] args) {
        try (KubernetesClient client = Kube.client()) {
            List<Node> nodes = client.nodes().list().getItems();
            System.out.printf("%d nodes%n%n", nodes.size());

            for (Node node : nodes) {
                String name = node.getMetadata().getName();
                // null when never cordoned, so compare against TRUE instead of unboxing
                boolean unschedulable = Boolean.TRUE.equals(node.getSpec().getUnschedulable());

                System.out.printf("== %s  (unschedulable=%s)%n", name, unschedulable);

                System.out.println("  labels:");
                Map<String, String> labels = node.getMetadata().getLabels();
                if (labels != null) {
                    labels.forEach((k, v) -> System.out.printf("    %s=%s%n", k, v));
                }

                System.out.println("  conditions:");
                List<NodeCondition> conditions = node.getStatus().getConditions();
                if (conditions != null) {
                    for (NodeCondition c : conditions) {
                        System.out.printf("    %-20s %-8s %-30s since %s%n",
                                c.getType(), c.getStatus(), c.getReason(), c.getLastTransitionTime());
                    }
                }
                System.out.println();
            }
        }
    }
}
