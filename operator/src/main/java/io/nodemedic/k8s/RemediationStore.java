package io.nodemedic.k8s;

import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.nodemedic.crd.FaultRecord;
import io.nodemedic.crd.NodeRemediation;
import io.nodemedic.crd.NodeRemediationSpec;
import io.nodemedic.crd.NodeRemediationStatus;
import io.nodemedic.crd.Phase;
import io.nodemedic.crd.Transition;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads and writes NodeRemediation objects. Every write is check-then-act, so repeating it
 * (after a retry or a restart) is harmless. Status goes through the status endpoint.
 */
public class RemediationStore {

    private final KubernetesClient client;

    public RemediationStore(KubernetesClient client) {
        this.client = client;
    }

    /**
     * This policy's remediations, read live from the API server. The informer cache can lag
     * behind our own writes, and the budget must not be counted from stale data.
     */
    public List<NodeRemediation> list(String policyName) {
        return client.resources(NodeRemediation.class).list().getItems().stream()
                .filter(nr -> nr.getSpec() != null && policyName.equals(nr.getSpec().getPolicyRef()))
                .toList();
    }

    /** Creates the CR for a node (name = node name, owned by the Node) in Suspect. */
    public void createSuspect(Node node, String policyName, List<FaultRecord> faults, Instant now) {
        String name = node.getMetadata().getName();

        var spec = new NodeRemediationSpec();
        spec.setNodeName(name);
        spec.setPolicyRef(policyName);

        var nr = new NodeRemediation();
        nr.setMetadata(new ObjectMetaBuilder()
                .withName(name)
                .withOwnerReferences(new OwnerReferenceBuilder()
                        .withApiVersion("v1")
                        .withKind("Node")
                        .withName(name)
                        .withUid(node.getMetadata().getUid())
                        .build())
                .build());
        nr.setSpec(spec);

        try {
            client.resources(NodeRemediation.class).resource(nr).create();
        } catch (KubernetesClientException e) {
            if (e.getCode() != 409) {          // 409 = already exists = this step is already done
                throw e;
            }
        }

        // status can't be set on create (status subresource), so write it separately;
        // an existing CR that already has a phase is left alone
        editStatus(name, st -> {
            if (st.getPhase() != null) {
                return;
            }
            st.setPhase(Phase.Suspect);
            st.setFaults(new ArrayList<>(faults));
            st.setMessage("");
            addTransition(st, Phase.Suspect, now);
        });
    }

    /** Replaces the fault history and, if not null, the message. The phase is untouched. */
    public void recordStatus(String name, List<FaultRecord> faults, String message) {
        editStatus(name, st -> {
            st.setFaults(new ArrayList<>(faults));
            if (message != null) {
                st.setMessage(message);
            }
        });
    }

    /** Suspect to Cordoned. Does nothing to a CR that has already moved on. */
    public void admit(String name, List<FaultRecord> faults, Instant now) {
        editStatus(name, st -> {
            st.setFaults(new ArrayList<>(faults));
            st.setMessage("");
            if (st.getPhase() == null || st.getPhase() == Phase.Suspect) {
                st.setPhase(Phase.Cordoned);
                addTransition(st, Phase.Cordoned, now);
            }
        });
    }

    /** The remediation as it is on the API server right now, or null. */
    public NodeRemediation get(String name) {
        return client.resources(NodeRemediation.class).withName(name).get();
    }

    /** Cordoned to Draining. Does nothing to a CR in any other phase. */
    public void startDraining(String name, Instant now) {
        editStatus(name, st -> {
            if (st.getPhase() == Phase.Cordoned) {
                st.setPhase(Phase.Draining);
                addTransition(st, Phase.Draining, now);
            }
        });
    }

    public void setMessage(String name, String message) {
        editStatus(name, st -> st.setMessage(message));
    }

    /** Draining to DrainStalled with an explanation. Does nothing to a CR in any other phase. */
    public void stall(String name, String message, Instant now) {
        editStatus(name, st -> {
            if (st.getPhase() == Phase.Draining) {
                st.setPhase(Phase.DrainStalled);
                st.setMessage(message);
                addTransition(st, Phase.DrainStalled, now);
            }
        });
    }

    public void delete(String name) {
        client.resources(NodeRemediation.class).withName(name).delete();
    }

    private void editStatus(String name, java.util.function.Consumer<NodeRemediationStatus> change) {
        client.resources(NodeRemediation.class).withName(name).editStatus(nr -> {
            var st = nr.getStatus() == null ? new NodeRemediationStatus() : nr.getStatus();
            change.accept(st);
            nr.setStatus(st);
            return nr;
        });
    }

    private static void addTransition(NodeRemediationStatus st, Phase phase, Instant now) {
        if (st.getTransitions() == null) {
            st.setTransitions(new ArrayList<>());
        }
        var t = new Transition();
        t.setPhase(phase);
        t.setAt(now.toString());
        st.getTransitions().add(t);
    }
}
