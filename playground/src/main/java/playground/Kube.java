package playground;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;

public final class Kube {
    private Kube() {}
    public static KubernetesClient client() {
        return new KubernetesClientBuilder().build();
    }
}
