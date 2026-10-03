package playground;

import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.NodeCondition;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.WatcherException;

import java.util.HashMap;
import java.util.Map;

/** Ex 2: raw watch, print only when a condition's status changes. */
public class WatchNodes {

    // node name -> (condition type -> status). The watch only gives the new object, so we remember the old.
    static final Map<String, Map<String, String>> last = new HashMap<>();

    public static void main(String[] args) throws InterruptedException {
        try (KubernetesClient client = Kube.client()) {
            client.nodes().watch(new Watcher<Node>() {
                @Override
                public void eventReceived(Action action, Node node) {
                    String name = node.getMetadata().getName();
                    Map<String, String> prev = last.computeIfAbsent(name, k -> new HashMap<>());

                    for (NodeCondition c : node.getStatus().getConditions()) {
                        String old = prev.put(c.getType(), c.getStatus());
                        if (old != null && !old.equals(c.getStatus())) {
                            System.out.printf("%s  %s  %s -> %s  (%s)%n",
                                    name, c.getType(), old, c.getStatus(), c.getReason());
                        }
                    }
                }

                @Override
                public void onClose(WatcherException e) {
                    System.out.println("watch closed: " + e.getMessage());
                }
            });

            System.out.println("watching nodes... Ctrl+C to stop");
            Thread.currentThread().join();
        }
    }
}
