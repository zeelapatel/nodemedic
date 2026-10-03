package playground;

import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.NodeCondition;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.fabric8.kubernetes.client.informers.SharedIndexInformer;

import java.util.HashMap;
import java.util.Map;

/** Ex 3: same as WatchNodes, but with an informer (gives old + new, cache, resync). */
public class InformerNodes {

    public static void main(String[] args) throws InterruptedException {
        try (KubernetesClient client = Kube.client()) {
            SharedIndexInformer<Node> informer = client.nodes().inform(new ResourceEventHandler<Node>() {
                @Override
                public void onAdd(Node node) {
                    System.out.println("add    " + node.getMetadata().getName());
                }

                @Override
                public void onUpdate(Node oldNode, Node newNode) {
                    String name = newNode.getMetadata().getName();

                    // same resourceVersion = resync, not a real change
                    if (oldNode.getMetadata().getResourceVersion().equals(newNode.getMetadata().getResourceVersion())) {
                        System.out.println("resync " + name);
                        return;
                    }

                    // no map of our own needed: the informer hands us the old object
                    Map<String, String> before = statuses(oldNode);
                    for (NodeCondition c : newNode.getStatus().getConditions()) {
                        String old = before.get(c.getType());
                        if (old != null && !old.equals(c.getStatus())) {
                            System.out.printf("%s  %s  %s -> %s  (%s)%n",
                                    name, c.getType(), old, c.getStatus(), c.getReason());
                        }
                    }
                }

                @Override
                public void onDelete(Node node, boolean deletedFinalStateUnknown) {
                    System.out.println("delete " + node.getMetadata().getName());
                }
            }, 30_000); // resync every 30s

            System.out.println("synced: " + informer.hasSynced()
                    + ", nodes in cache: " + informer.getStore().list().size());
            System.out.println("watching nodes... Ctrl+C to stop");
            Thread.currentThread().join();
        }
    }

    static Map<String, String> statuses(Node node) {
        Map<String, String> m = new HashMap<>();
        for (NodeCondition c : node.getStatus().getConditions()) {
            m.put(c.getType(), c.getStatus());
        }
        return m;
    }
}
