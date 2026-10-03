package playground;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;

/**
 * Ex 5: evict (respects PDB) vs delete (ignores PDB).
 * args: <pod> <evict|delete>   e.g. nginx-687fd7f58-2c6lw evict
 */
public class EvictPod {

    public static void main(String[] args) {
        String pod = args[0];
        String mode = args.length > 1 ? args[1] : "evict";

        try (KubernetesClient client = Kube.client()) {
            var res = client.pods().inNamespace("default").withName(pod);

            if (mode.equals("evict")) {
                boolean ok = res.evict();
                System.out.println("evict returned: " + ok);
            } else {
                System.out.println("delete returned: " + res.delete());
            }
        } catch (KubernetesClientException e) {
            System.out.println("threw: code=" + e.getCode() + "  " + e.getMessage());
        }
    }
}
