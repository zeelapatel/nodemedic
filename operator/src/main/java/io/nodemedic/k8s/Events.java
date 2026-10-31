package io.nodemedic.k8s;

import io.fabric8.kubernetes.api.model.EventBuilder;
import io.fabric8.kubernetes.api.model.EventSourceBuilder;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.ObjectReferenceBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;

/**
 * Kubernetes Events (what {@code kubectl describe} shows). Emit them on transitions only, never
 * on every reconcile, or the cluster fills up with noise. They are best-effort: a failure to
 * write an Event is logged and never fails the reconcile.
 */
public final class Events {

    public static final String NORMAL = "Normal";
    public static final String WARNING = "Warning";

    private static final Logger log = LoggerFactory.getLogger(Events.class);

    /** Events about cluster-scoped objects have to live in some namespace. */
    private static final String NAMESPACE = "default";

    private Events() {
    }

    public static void emit(KubernetesClient client, HasMetadata about, String type, String reason,
                            String message, Instant now) {
        try {
            String name = about.getMetadata().getName();
            // kubelet emits Node events with the node's name as the UID, and kubectl describe
            // searches for that, so do the same or the Event won't show up there
            String uid = "Node".equals(about.getKind()) ? name : about.getMetadata().getUid();

            var event = new EventBuilder()
                    .withNewMetadata().withGenerateName(name + ".").withNamespace(NAMESPACE).endMetadata()
                    .withInvolvedObject(new ObjectReferenceBuilder()
                            .withApiVersion(about.getApiVersion())
                            .withKind(about.getKind())
                            .withName(name)
                            .withUid(uid)
                            .build())
                    .withType(type)
                    .withReason(reason)
                    .withMessage(message)
                    .withCount(1)
                    .withFirstTimestamp(now.toString())
                    .withLastTimestamp(now.toString())
                    .withSource(new EventSourceBuilder().withComponent("nodemedic").build())
                    .withReportingComponent("nodemedic")
                    .build();
            client.v1().events().inNamespace(NAMESPACE).resource(event).create();
        } catch (RuntimeException e) {
            log.warn("could not write Event {} for {}/{}: {}", reason, about.getKind(),
                    about.getMetadata().getName(), e.getMessage());
        }
    }
}
