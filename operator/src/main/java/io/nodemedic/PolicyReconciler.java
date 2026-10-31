package io.nodemedic;

import io.fabric8.kubernetes.api.model.Node;
import io.javaoperatorsdk.operator.api.config.informer.InformerEventSourceConfiguration;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.ControllerConfiguration;
import io.javaoperatorsdk.operator.api.reconciler.EventSourceContext;
import io.javaoperatorsdk.operator.api.reconciler.Reconciler;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import io.javaoperatorsdk.operator.processing.event.ResourceID;
import io.javaoperatorsdk.operator.processing.event.source.EventSource;
import io.javaoperatorsdk.operator.processing.event.source.informer.InformerEventSource;
import io.nodemedic.crd.NodeRemediation;
import io.nodemedic.crd.Phase;
import io.nodemedic.crd.RemediationPolicy;
import io.nodemedic.crd.RemediationPolicyStatus;
import io.nodemedic.drain.Drainer;
import io.nodemedic.engine.Decision;
import io.nodemedic.engine.DrainTimeout;
import io.nodemedic.engine.Durations;
import io.nodemedic.engine.FaultEvaluator;
import io.nodemedic.engine.NodePool;
import io.nodemedic.engine.Observation;
import io.nodemedic.k8s.Events;
import io.nodemedic.k8s.NodeCordoner;
import io.nodemedic.k8s.RemediationStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Thin on purpose: gather what exists, ask {@link FaultEvaluator} what to do, apply it,
 * write the policy status. The decisions themselves live in the engine package.
 */
@Component
@ControllerConfiguration
public class PolicyReconciler implements Reconciler<RemediationPolicy> {

    private static final Logger log = LoggerFactory.getLogger(PolicyReconciler.class);

    /** How soon to look again while any node is still being worked on. */
    static final Duration REQUEUE = Duration.ofSeconds(15);

    private final Clock clock;

    public PolicyReconciler(Clock clock) {
        this.clock = clock;
    }

    @Override
    public UpdateControl<RemediationPolicy> reconcile(RemediationPolicy policy, Context<RemediationPolicy> ctx) {
        String policyName = policy.getMetadata().getName();
        Instant now = clock.instant();
        log.debug("reconciling policy {} (generation {})", policyName, policy.getMetadata().getGeneration());
        warnIfInvalid(policy);

        var store = new RemediationStore(ctx.getClient());
        Map<String, NodeRemediation> existing = store.list(policyName).stream()
                .collect(Collectors.toMap(nr -> nr.getMetadata().getName(), Function.identity()));

        Map<String, Node> poolNodes = ctx.getSecondaryResources(Node.class).stream()
                .filter(n -> NodePool.matches(n, policy.getSpec().getNodeSelector()))
                .collect(Collectors.toMap(n -> n.getMetadata().getName(), Function.identity()));

        List<Observation> observations = poolNodes.values().stream()
                .map(n -> new Observation(n, existing.get(n.getMetadata().getName())))
                .toList();

        Map<String, Decision> decisions = FaultEvaluator.decideAll(observations, policy, now);

        // the phase each node's remediation will have once the decisions are applied
        Map<String, Phase> resulting = new HashMap<>();
        existing.forEach((name, nr) -> resulting.put(name, phaseOf(nr)));

        decisions.forEach((name, d) -> {
            apply(store, poolNodes.get(name), policyName, d, now);
            switch (d.action()) {
                case CREATE_SUSPECT -> resulting.put(name, Phase.Suspect);
                case ADMIT -> resulting.put(name, Phase.Cordoned);
                case RELEASE -> resulting.remove(name);
                default -> { }
            }
        });

        Set<String> drained = cordonAndDrain(ctx, policy, resulting, now);

        return finish(policy, resulting, drained);
    }

    /**
     * Level-based: every remediation in Cordoned gets a cordoned node, then moves to Draining;
     * every one in Draining gets one bounded drain round. Safe to repeat after a restart.
     *
     * @return the nodes that are completely drained
     */
    private Set<String> cordonAndDrain(Context<RemediationPolicy> ctx, RemediationPolicy policy,
                                       Map<String, Phase> resulting, Instant now) {
        var client = ctx.getClient();
        var store = new RemediationStore(client);
        var cordoner = new NodeCordoner(client);
        var drainer = new Drainer(client);
        Optional<Duration> timeout = Durations.parse(policy.getSpec().getDrainTimeout());
        Set<String> drained = new HashSet<>();

        for (String name : List.copyOf(resulting.keySet())) {
            Phase phase = resulting.get(name);
            if (phase == Phase.Cordoned) {
                if (cordoner.ensureCordoned(name, store.get(name), now)) {
                    log.info("node {}: cordoned", name);
                }
                store.startDraining(name, now);
                resulting.put(name, Phase.Draining);
                phase = Phase.Draining;
            }
            if (phase != Phase.Draining) {
                continue;
            }

            NodeRemediation current = store.get(name);
            if (current == null) {
                continue;                                   // deleted meanwhile; nothing to drain for
            }
            Drainer.Round round = drainer.run(name);
            String message = round.message();
            String before = current.getStatus() == null || current.getStatus().getMessage() == null
                    ? "" : current.getStatus().getMessage();

            if (round.done()) {
                drained.add(name);
                if (!Drainer.DRAINED.equals(before)) {
                    log.info("node {}: drained", name);
                    store.setMessage(name, Drainer.DRAINED);
                    emitBoth(client, name, current, Events.NORMAL, "Drained", "Node " + name + " is drained", now);
                }
            } else if (DrainTimeout.exceeded(current, timeout, now)) {
                String why = "drain stalled after " + policy.getSpec().getDrainTimeout() + ": " + message;
                log.warn("node {}: {}", name, why);
                store.stall(name, why, now);
                resulting.put(name, Phase.DrainStalled);
                emitBoth(client, name, current, Events.WARNING, "DrainStalled", why, now);
            } else if (!message.equals(before)) {
                store.setMessage(name, message);
            }
        }
        return drained;
    }

    /** The same Event on the Node and on its NodeRemediation. */
    private static void emitBoth(io.fabric8.kubernetes.client.KubernetesClient client, String nodeName,
                                 NodeRemediation remediation, String type, String reason, String message,
                                 Instant now) {
        var node = client.nodes().withName(nodeName).get();
        if (node != null) {
            Events.emit(client, node, type, reason, message, now);
        }
        Events.emit(client, remediation, type, reason, message, now);
    }

    private void apply(RemediationStore store, Node node, String policyName, Decision d, Instant now) {
        String name = node.getMetadata().getName();
        switch (d.action()) {
            case NONE -> { }
            case CREATE_SUSPECT -> {
                log.info("node {}: fault detected, creating NodeRemediation (Suspect)", name);
                store.createSuspect(node, policyName, d.faults(), now);
            }
            case UPDATE -> {
                log.info("node {}: updating status{}", name, d.message() == null ? "" : " (" + d.message() + ")");
                store.recordStatus(name, d.faults(), d.message());
            }
            case ADMIT -> {
                log.info("node {}: admitted, Suspect -> Cordoned", name);
                store.admit(name, d.faults(), now);
            }
            case RELEASE -> {
                log.info("node {}: quiet for the cooldown, removing NodeRemediation", name);
                store.delete(name);
            }
        }
    }

    /** Writes the policy status if it changed, and asks to be called again while work remains. */
    private UpdateControl<RemediationPolicy> finish(RemediationPolicy policy, Map<String, Phase> resulting,
                                                    Set<String> drained) {
        int active = (int) resulting.values().stream().filter(FaultEvaluator::consumesBudget).count();
        boolean workRemains = resulting.entrySet().stream().anyMatch(e ->
                e.getValue() == Phase.Suspect || e.getValue() == Phase.Cordoned
                        || (e.getValue() == Phase.Draining && !drained.contains(e.getKey())));

        RemediationPolicyStatus status = policy.getStatus() == null ? new RemediationPolicyStatus() : policy.getStatus();
        boolean changed = status.getActiveRemediations() != active
                || !Objects.equals(status.getObservedGeneration(), policy.getMetadata().getGeneration());
        status.setActiveRemediations(active);
        status.setObservedGeneration(policy.getMetadata().getGeneration());
        policy.setStatus(status);

        UpdateControl<RemediationPolicy> control =
                changed ? UpdateControl.patchStatus(policy) : UpdateControl.noUpdate();
        return workRemains ? control.rescheduleAfter(REQUEUE) : control;
    }

    private static Phase phaseOf(NodeRemediation nr) {
        return nr.getStatus() == null || nr.getStatus().getPhase() == null ? Phase.Suspect : nr.getStatus().getPhase();
    }

    /** A broken duration makes the evaluator skip that rule; say so instead of failing silently. */
    private static void warnIfInvalid(RemediationPolicy policy) {
        var spec = policy.getSpec();
        String name = policy.getMetadata().getName();
        if (Durations.parse(spec.getCooldown()).isEmpty()) {
            log.warn("policy {}: cooldown '{}' is not a valid duration; nodes will not be released", name, spec.getCooldown());
        }
        if (Durations.parse(spec.getDrainTimeout()).isEmpty()) {
            log.warn("policy {}: drainTimeout '{}' is not a valid duration; a stuck drain will never be marked DrainStalled", name, spec.getDrainTimeout());
        }
        if (spec.getThreshold() == null || Durations.parse(spec.getThreshold().getWindow()).isEmpty()) {
            log.warn("policy {}: threshold.window is missing or invalid; Degraded signals will never be admitted", name);
        }
    }

    @Override
    public List<EventSource<?, RemediationPolicy>> prepareEventSources(EventSourceContext<RemediationPolicy> ctx) {
        // any node change -> reconcile every policy; the evaluator ignores nodes outside the pool
        var nodes = InformerEventSourceConfiguration.from(Node.class, RemediationPolicy.class)
                .withSecondaryToPrimaryMapper(node -> ctx.getPrimaryCache().list()
                        .map(ResourceID::fromResource)
                        .collect(Collectors.toSet()))
                .build();

        // a NodeRemediation change -> reconcile the policy it names
        var remediations = InformerEventSourceConfiguration.from(NodeRemediation.class, RemediationPolicy.class)
                .withSecondaryToPrimaryMapper(nr -> nr.getSpec() == null || nr.getSpec().getPolicyRef() == null
                        ? Set.<ResourceID>of()
                        : Set.of(new ResourceID(nr.getSpec().getPolicyRef())))
                .build();

        return List.of(new InformerEventSource<>(nodes, ctx), new InformerEventSource<>(remediations, ctx));
    }
}
