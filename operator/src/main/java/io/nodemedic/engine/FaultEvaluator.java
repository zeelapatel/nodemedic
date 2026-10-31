package io.nodemedic.engine;

import io.fabric8.kubernetes.api.model.Node;
import io.nodemedic.crd.FaultRecord;
import io.nodemedic.crd.NodeRemediation;
import io.nodemedic.crd.Phase;
import io.nodemedic.crd.RemediationPolicy;
import io.nodemedic.crd.RemediationPolicySpec;
import io.nodemedic.crd.Severity;
import io.nodemedic.crd.Signal;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The pure decision core (ADR 0012). No Kubernetes client, no clock: the caller passes
 * {@code now}. Handles detection, the threshold/window, the budget and release after cooldown.
 * Progress of Cordoned/Draining nodes belongs to the shell and the Drainer, not here.
 */
public final class FaultEvaluator {

    /** History kept per node; the oldest entries are dropped beyond this. */
    public static final int MAX_FAULTS = 20;

    /** Phases that hold a node out of service, and so count against the budget (D4). */
    private static final Set<Phase> BUDGET_PHASES =
            EnumSet.of(Phase.Cordoned, Phase.SpareActivated, Phase.Draining, Phase.DrainStalled);

    private FaultEvaluator() {
    }

    /** True if a remediation in this phase holds a budget slot. */
    public static boolean consumesBudget(Phase phase) {
        return BUDGET_PHASES.contains(phase);
    }

    /** One remembered or observed fault occurrence, identified by (type, at). */
    private record Occurrence(String type, Instant at) {
    }

    /**
     * Decides for all pool nodes at once, so the budget is applied consistently. Nodes are
     * processed oldest first fault, then by name (D5), and each ADMIT uses up one budget slot.
     * The result is in processing order.
     */
    public static Map<String, Decision> decideAll(List<Observation> observations,
                                                  RemediationPolicy policy, Instant now) {
        int budgetUsed = (int) observations.stream()
                .filter(o -> o.remediation() != null && BUDGET_PHASES.contains(phaseOf(o.remediation())))
                .count();

        List<Signal> signals = signals(policy);
        List<Observation> ordered = new ArrayList<>(observations);
        ordered.sort(Comparator
                .comparing((Observation o) -> firstFaultAt(o, signals))
                .thenComparing(Observation::nodeName));

        Map<String, Decision> result = new LinkedHashMap<>();
        for (Observation o : ordered) {
            Decision d = decide(o.node(), o.remediation(), policy, now, budgetUsed);
            if (d.action() == Decision.Action.ADMIT) {
                budgetUsed++;
            }
            result.put(o.nodeName(), d);
        }
        return result;
    }

    /**
     * Decision for one node. {@code budgetUsed} is how many other nodes currently hold a budget
     * slot; {@code remediation} is null when the node has no NodeRemediation.
     */
    public static Decision decide(Node node, NodeRemediation remediation, RemediationPolicy policy,
                                  Instant now, int budgetUsed) {
        RemediationPolicySpec spec = policy.getSpec();
        List<Signal> signals = signals(policy);
        List<ActiveFault> active = Faults.activeFaults(node, signals);
        List<Occurrence> existing = recorded(remediation);
        List<Occurrence> merged = merge(existing, active);
        boolean faultsChanged = !new LinkedHashSet<>(existing).equals(new LinkedHashSet<>(merged));
        List<FaultRecord> records = toRecords(merged);

        if (remediation == null) {
            return active.isEmpty() ? Decision.none() : Decision.createSuspect(records);
        }

        // Past Suspect the phase is driven by the shell; only keep the fault history current.
        if (phaseOf(remediation) != Phase.Suspect) {
            return faultsChanged ? Decision.update(records, null) : Decision.none();
        }

        String currentMessage = remediation.getStatus() == null || remediation.getStatus().getMessage() == null
                ? "" : remediation.getStatus().getMessage();

        if (qualifies(active, merged, signals, spec, now)) {
            if (budgetUsed < spec.getMaxConcurrentRemediations()) {
                return Decision.admit(records);
            }
            return faultsChanged || !Decision.WAITING_FOR_BUDGET.equals(currentMessage)
                    ? Decision.update(records, Decision.WAITING_FOR_BUDGET)
                    : Decision.none();
        }

        if (active.isEmpty() && quietLongEnough(remediation, merged, spec, now)) {
            return Decision.release();
        }
        return faultsChanged || !currentMessage.isEmpty()
                ? Decision.update(records, "")
                : Decision.none();
    }

    // ---- admission -------------------------------------------------------------------------

    /** Critical: an active Critical fault. Degraded: enough occurrences inside the window. */
    private static boolean qualifies(List<ActiveFault> active, List<Occurrence> merged,
                                     List<Signal> signals, RemediationPolicySpec spec, Instant now) {
        if (active.stream().anyMatch(f -> f.severity() == Severity.Critical)) {
            return true;
        }
        var threshold = spec.getThreshold();
        if (threshold == null || threshold.getFaults() <= 0) {
            return false;
        }
        Optional<Duration> window = Durations.parse(threshold.getWindow());
        if (window.isEmpty()) {
            return false;
        }
        Instant since = now.minus(window.get());
        Map<String, Severity> severities = severitiesByType(signals);
        long inWindow = merged.stream()
                .filter(o -> severities.get(o.type()) == Severity.Degraded)
                .filter(o -> !o.at().isBefore(since))
                .count();
        return inWindow >= threshold.getFaults();
    }

    // ---- release ---------------------------------------------------------------------------

    /** Quiet = cooldown has passed since the newest fault (or the CR's creation if it has none). */
    private static boolean quietLongEnough(NodeRemediation remediation, List<Occurrence> merged,
                                           RemediationPolicySpec spec, Instant now) {
        Optional<Duration> cooldown = Durations.parse(spec.getCooldown());
        if (cooldown.isEmpty()) {
            return false;
        }
        Instant reference = merged.stream().map(Occurrence::at).max(Comparator.naturalOrder())
                .orElseGet(() -> parseTime(remediation.getMetadata() == null
                        ? null : remediation.getMetadata().getCreationTimestamp()));
        if (reference == null) {
            return false;
        }
        return !now.isBefore(reference.plus(cooldown.get()));
    }

    // ---- fault history ---------------------------------------------------------------------

    private static List<Occurrence> recorded(NodeRemediation remediation) {
        if (remediation == null || remediation.getStatus() == null || remediation.getStatus().getFaults() == null) {
            return List.of();
        }
        List<Occurrence> result = new ArrayList<>();
        for (FaultRecord r : remediation.getStatus().getFaults()) {
            Instant at = parseTime(r.getAt());
            if (at != null) {
                result.add(new Occurrence(r.getType(), at));
            }
        }
        return result;
    }

    /** Existing history plus new occurrences, deduplicated by (type, at), oldest first, capped. */
    private static List<Occurrence> merge(List<Occurrence> existing, List<ActiveFault> active) {
        Set<Occurrence> all = new LinkedHashSet<>(existing);
        for (ActiveFault f : active) {
            all.add(new Occurrence(f.type(), f.at()));
        }
        List<Occurrence> sorted = new ArrayList<>(all);
        sorted.sort(Comparator.comparing(Occurrence::at).thenComparing(Occurrence::type));
        int excess = sorted.size() - MAX_FAULTS;
        return excess > 0 ? new ArrayList<>(sorted.subList(excess, sorted.size())) : sorted;
    }

    private static List<FaultRecord> toRecords(List<Occurrence> occurrences) {
        List<FaultRecord> records = new ArrayList<>();
        for (Occurrence o : occurrences) {
            FaultRecord r = new FaultRecord();
            r.setType(o.type());
            r.setAt(o.at().toString());
            records.add(r);
        }
        return records;
    }

    private static Instant firstFaultAt(Observation o, List<Signal> signals) {
        List<ActiveFault> active = Faults.activeFaults(o.node(), signals);
        return merge(recorded(o.remediation()), active).stream()
                .map(Occurrence::at).min(Comparator.naturalOrder()).orElse(Instant.MAX);
    }

    // ---- small helpers ---------------------------------------------------------------------

    private static Phase phaseOf(NodeRemediation r) {
        return r.getStatus() == null || r.getStatus().getPhase() == null ? Phase.Suspect : r.getStatus().getPhase();
    }

    private static List<Signal> signals(RemediationPolicy policy) {
        return policy.getSpec() == null || policy.getSpec().getSignals() == null
                ? List.of() : policy.getSpec().getSignals();
    }

    private static Map<String, Severity> severitiesByType(List<Signal> signals) {
        Map<String, Severity> map = new HashMap<>();
        for (Signal s : signals) {
            map.putIfAbsent(s.getConditionType(), s.getSeverity());
        }
        return map;
    }

    private static Instant parseTime(String rfc3339) {
        if (rfc3339 == null || rfc3339.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(rfc3339).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
