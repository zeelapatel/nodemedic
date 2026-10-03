package playground;

import io.fabric8.kubernetes.api.model.NodeBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.PatchContext;
import io.fabric8.kubernetes.client.dsl.base.PatchType;

/**
 * Ex 4: cordon / uncordon a node two ways.
 * args: <node> <edit|patch> <true|false>   e.g. nodemedic-worker edit true
 */
public class CordonNode {

    public static void main(String[] args) {
        String node = args.length > 0 ? args[0] : "nodemedic-worker";
        String mode = args.length > 1 ? args[1] : "edit";
        boolean cordon = args.length > 2 ? Boolean.parseBoolean(args[2]) : true;

        try (KubernetesClient client = Kube.client()) {
            if (mode.equals("edit")) {
                // A: read-modify-write, sends the whole object back
                client.nodes().withName(node).edit(n ->
                        new NodeBuilder(n).editSpec().withUnschedulable(cordon).endSpec().build());
            } else {
                // B: send only the changed field
                client.nodes().withName(node).patch(
                        PatchContext.of(PatchType.JSON_MERGE),
                        "{\"spec\":{\"unschedulable\":" + cordon + "}}");
            }

            Boolean now = client.nodes().withName(node).get().getSpec().getUnschedulable();
            System.out.printf("%s via %s -> unschedulable=%s%n", node, mode, now);
        }
    }
}
