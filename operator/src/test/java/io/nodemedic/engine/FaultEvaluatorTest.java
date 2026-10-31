package io.nodemedic.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.nodemedic.crd.FaultRecord;
import io.nodemedic.crd.NodeRemediation;
import io.nodemedic.crd.NodeRemediationStatus;
import io.nodemedic.crd.Phase;
import io.nodemedic.crd.RemediationPolicy;
import io.nodemedic.crd.RemediationPolicySpec;
import io.nodemedic.crd.Severity;
import io.nodemedic.crd.Signal;
import io.nodemedic.engine.Decision.Action;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class FaultEvaluatorTest {

    private static final Instant T0 = Instant.parse("2026-10-20T12:00:00Z");
    private static final String XID = "GpuXidError";
    private static final String NVLINK = "NvlinkDegraded";

    // ---- builders ----

    private static Signal signal(String type, Severity severity) {
        var s = new Signal();
        s.setConditionType(type);
        s.setSeverity(severity);
        return s;
    }

    /** faults=3, window=10m, cooldown=15m, budget 1 unless changed by the caller. */
    private static RemediationPolicy policy() {
        var threshold = new RemediationPolicySpec.Threshold();
        threshold.setFaults(3);
        threshold.setWindow("10m");
        var spec = new RemediationPolicySpec();
        spec.setSignals(List.of(signal(XID, Severity.Critical), signal(NVLINK, Severity.Degraded)));
        spec.setThreshold(threshold);
        spec.setCooldown("15m");
        spec.setMaxConcurrentRemediations(1);
        var policy = new RemediationPolicy();
        policy.setSpec(spec);
        return policy;
    }

    private static String ts(Instant t) {
        return t.toString();
    }

    private static Instant minutes(long m) {
        return T0.plus(Duration.ofMinutes(m));
    }

    /** Node with the given condition (type, status) transitioned at the given time. */
    private static Node node(String name, String type, String status, Instant at) {
        return TestNodes.named(name, type, status, ts(at));
    }

    private static FaultRecord fault(String type, Instant at) {
        var f = new FaultRecord();
        f.setType(type);
        f.setAt(ts(at));
        return f;
    }

    private static NodeRemediation remediation(String name, Phase phase, String message, FaultRecord... faults) {
        var nr = new NodeRemediation();
        nr.setMetadata(new ObjectMetaBuilder().withName(name).withCreationTimestamp(ts(T0)).build());
        var status = new NodeRemediationStatus();
        status.setPhase(phase);
        status.setMessage(message);
        status.setFaults(new ArrayList<>(List.of(faults)));
        nr.setStatus(status);
        return nr;
    }

    private static NodeRemediation suspect(String name, FaultRecord... faults) {
        return remediation(name, Phase.Suspect, "", faults);
    }

    private static Decision decide(Node node, NodeRemediation nr, Instant now) {
        return FaultEvaluator.decide(node, nr, policy(), now, 0);
    }

    // ---- creating the CR ----

    @Test
    void healthyNodeWithoutCrDoesNothing() {
        var node = node("n1", XID, "False", T0);

        assertThat(decide(node, null, T0).action()).isEqualTo(Action.NONE);
    }

    @Test
    void firstFaultCreatesSuspectWithThatFault() {
        var node = node("n1", NVLINK, "True", T0);

        var d = decide(node, null, T0);

        assertThat(d.action()).isEqualTo(Action.CREATE_SUSPECT);
        assertThat(d.faults()).hasSize(1);
        assertThat(d.faults().get(0).getType()).isEqualTo(NVLINK);
        assertThat(d.faults().get(0).getAt()).isEqualTo(ts(T0));
    }

    // ---- admission ----

    @Test
    void criticalFaultIsAdmittedOnTheFirstOccurrence() {
        var node = node("n1", XID, "True", T0);
        var nr = suspect("n1", fault(XID, T0));

        assertThat(decide(node, nr, T0).action()).isEqualTo(Action.ADMIT);
    }

    @Test
    void clearedCriticalFaultDoesNotAdmit() {
        var node = node("n1", XID, "False", T0);
        var nr = suspect("n1", fault(XID, T0));

        assertThat(decide(node, nr, minutes(1)).action()).isEqualTo(Action.NONE);
    }

    @Test
    void degradedAtThresholdInsideTheWindowIsAdmitted() {
        var node = node("n1", NVLINK, "True", minutes(2));
        var nr = suspect("n1", fault(NVLINK, T0), fault(NVLINK, minutes(1)));
        // two recorded + the new one on the node = 3 faults within 10m
        var d = decide(node, nr, minutes(2));

        assertThat(d.action()).isEqualTo(Action.ADMIT);
    }

    @Test
    void twoDegradedFaultsAreNotEnough() {
        var node = node("n1", NVLINK, "True", minutes(1));
        var nr = suspect("n1", fault(NVLINK, T0));

        var d = decide(node, nr, minutes(1));

        assertThat(d.action()).isEqualTo(Action.UPDATE);
        assertThat(d.faults()).hasSize(2);
    }

    @Test
    void degradedFaultsOutsideTheWindowDoNotCount() {
        // first fault is 11 minutes old at "now", so only 2 of the 3 are inside the 10m window
        var node = node("n1", NVLINK, "True", minutes(11));
        var nr = suspect("n1", fault(NVLINK, T0), fault(NVLINK, minutes(5)));

        var d = decide(node, nr, minutes(11));

        assertThat(d.action()).isEqualTo(Action.UPDATE);
        assertThat(d.faults()).hasSize(3);
    }

    @Test
    void faultExactlyAtTheWindowEdgeStillCounts() {
        var node = node("n1", NVLINK, "True", minutes(10));
        var nr = suspect("n1", fault(NVLINK, T0), fault(NVLINK, minutes(5)));

        assertThat(decide(node, nr, minutes(10)).action()).isEqualTo(Action.ADMIT);
    }

    @Test
    void invalidThresholdWindowNeverAdmitsDegraded() {
        var p = policy();
        p.getSpec().getThreshold().setWindow("soon");
        var node = node("n1", NVLINK, "True", minutes(2));
        var nr = suspect("n1", fault(NVLINK, T0), fault(NVLINK, minutes(1)));

        assertThat(FaultEvaluator.decide(node, nr, p, minutes(2), 0).action()).isNotEqualTo(Action.ADMIT);
    }

    // ---- dedupe ----

    @Test
    void sameFaultSeenAgainIsNotRecordedTwice() {
        var node = node("n1", NVLINK, "True", T0);
        var nr = suspect("n1", fault(NVLINK, T0));

        // heartbeats re-trigger reconciles; nothing new to write
        assertThat(decide(node, nr, minutes(1)).action()).isEqualTo(Action.NONE);
        assertThat(decide(node, nr, minutes(2)).action()).isEqualTo(Action.NONE);
    }

    @Test
    void newTransitionTimeIsANewOccurrence() {
        var node = node("n1", NVLINK, "True", minutes(1));
        var nr = suspect("n1", fault(NVLINK, T0));

        var d = decide(node, nr, minutes(1));

        assertThat(d.action()).isEqualTo(Action.UPDATE);
        assertThat(d.faults()).extracting(FaultRecord::getAt).containsExactly(ts(T0), ts(minutes(1)));
    }

    @Test
    void historyIsCappedAtTwentyKeepingTheNewest() {
        var old = new ArrayList<FaultRecord>();
        for (int i = 0; i < 20; i++) {
            old.add(fault(NVLINK, minutes(i)));
        }
        var node = node("n1", NVLINK, "True", minutes(30));
        var nr = suspect("n1", old.toArray(new FaultRecord[0]));

        var d = decide(node, nr, minutes(30));

        assertThat(d.faults()).hasSize(FaultEvaluator.MAX_FAULTS);
        assertThat(d.faults().get(0).getAt()).isEqualTo(ts(minutes(1)));         // oldest dropped
        assertThat(d.faults().get(19).getAt()).isEqualTo(ts(minutes(30)));
    }

    // ---- cooldown / release ----

    @Test
    void releasedAfterTheCooldownWithNoFault() {
        var node = node("n1", NVLINK, "False", T0);
        var nr = suspect("n1", fault(NVLINK, T0));

        assertThat(decide(node, nr, T0.plus(Duration.ofMinutes(15))).action()).isEqualTo(Action.RELEASE);
    }

    @Test
    void notReleasedOneMinuteBeforeTheCooldownEnds() {
        var node = node("n1", NVLINK, "False", T0);
        var nr = suspect("n1", fault(NVLINK, T0));

        assertThat(decide(node, nr, T0.plus(Duration.ofMinutes(14))).action()).isEqualTo(Action.NONE);
    }

    @Test
    void anActiveFaultPreventsReleaseHoweverOldItIs() {
        var node = node("n1", NVLINK, "True", T0);
        var nr = suspect("n1", fault(NVLINK, T0));

        assertThat(decide(node, nr, minutes(120)).action()).isNotEqualTo(Action.RELEASE);
    }

    @Test
    void flappingRestartsTheCooldownFromTheLatestFault() {
        // fault at T0, cleared, fault again at +10m, cleared again
        var node = node("n1", NVLINK, "False", minutes(12));
        var nr = suspect("n1", fault(NVLINK, T0), fault(NVLINK, minutes(10)));

        // 15m after the FIRST fault would be +15m, but the clock restarted at +10m
        assertThat(decide(node, nr, minutes(15)).action()).isEqualTo(Action.NONE);
        assertThat(decide(node, nr, minutes(24)).action()).isEqualTo(Action.NONE);
        assertThat(decide(node, nr, minutes(25)).action()).isEqualTo(Action.RELEASE);
    }

    // ---- budget ----

    @Test
    void qualifyingNodeWaitsWhenTheBudgetIsFull() {
        var node = node("n1", XID, "True", T0);
        var nr = suspect("n1", fault(XID, T0));

        var d = FaultEvaluator.decide(node, nr, policy(), T0, 1);

        assertThat(d.action()).isEqualTo(Action.UPDATE);
        assertThat(d.message()).isEqualTo(Decision.WAITING_FOR_BUDGET);
    }

    @Test
    void waitingMessageIsNotRewrittenOnEveryReconcile() {
        var node = node("n1", XID, "True", T0);
        var nr = remediation("n1", Phase.Suspect, Decision.WAITING_FOR_BUDGET, fault(XID, T0));

        assertThat(FaultEvaluator.decide(node, nr, policy(), T0, 1).action()).isEqualTo(Action.NONE);
    }

    @Test
    void nodeIsAdmittedOnceABudgetSlotFrees() {
        var node = node("n1", XID, "True", T0);
        var nr = remediation("n1", Phase.Suspect, Decision.WAITING_FOR_BUDGET, fault(XID, T0));

        var d = FaultEvaluator.decide(node, nr, policy(), minutes(5), 0);

        assertThat(d.action()).isEqualTo(Action.ADMIT);
        assertThat(d.message()).isEmpty();
    }

    @Test
    void budgetOfZeroPausesAdmissions() {
        var p = policy();
        p.getSpec().setMaxConcurrentRemediations(0);
        var node = node("n1", XID, "True", T0);
        var nr = suspect("n1", fault(XID, T0));

        assertThat(FaultEvaluator.decide(node, nr, p, T0, 0).action()).isEqualTo(Action.UPDATE);
    }

    @Test
    void whenTwoNodesQualifyTheOneWithTheOlderFaultGoesFirst() {
        var newer = new Observation(node("a-node", XID, "True", minutes(5)), suspect("a-node", fault(XID, minutes(5))));
        var older = new Observation(node("z-node", XID, "True", T0), suspect("z-node", fault(XID, T0)));

        var decisions = FaultEvaluator.decideAll(List.of(newer, older), policy(), minutes(6));

        assertThat(decisions.get("z-node").action()).isEqualTo(Action.ADMIT);
        assertThat(decisions.get("a-node").action()).isEqualTo(Action.UPDATE);
        assertThat(decisions.get("a-node").message()).isEqualTo(Decision.WAITING_FOR_BUDGET);
    }

    @Test
    void faultsAtTheSameTimeAreOrderedByNodeName() {
        var b = new Observation(node("b-node", XID, "True", T0), suspect("b-node", fault(XID, T0)));
        var a = new Observation(node("a-node", XID, "True", T0), suspect("a-node", fault(XID, T0)));

        var decisions = FaultEvaluator.decideAll(List.of(b, a), policy(), T0);

        assertThat(decisions.get("a-node").action()).isEqualTo(Action.ADMIT);
        assertThat(decisions.get("b-node").action()).isEqualTo(Action.UPDATE);
    }

    @Test
    void nodesAlreadyBeingRemediatedUseUpTheBudget() {
        for (Phase busy : List.of(Phase.Cordoned, Phase.SpareActivated, Phase.Draining, Phase.DrainStalled)) {
            var working = new Observation(node("w", XID, "True", T0), remediation("w", busy, "", fault(XID, T0)));
            var waiting = new Observation(node("n", XID, "True", minutes(1)), suspect("n", fault(XID, minutes(1))));

            var decisions = FaultEvaluator.decideAll(List.of(working, waiting), policy(), minutes(2));

            assertThat(decisions.get("n").action()).as("while another node is %s", busy).isEqualTo(Action.UPDATE);
        }
    }

    @Test
    void aQuarantinedOrSuspectNodeDoesNotUseTheBudget() {
        var suspectOnly = new Observation(node("s", NVLINK, "True", T0), suspect("s", fault(NVLINK, T0)));
        var critical = new Observation(node("n", XID, "True", minutes(1)), suspect("n", fault(XID, minutes(1))));

        var decisions = FaultEvaluator.decideAll(List.of(suspectOnly, critical), policy(), minutes(2));

        assertThat(decisions.get("n").action()).isEqualTo(Action.ADMIT);
    }

    // ---- nodes already past Suspect ----

    @Test
    void phaseIsNeverChangedForANodeAlreadyBeingRemediated() {
        var node = node("n1", XID, "True", T0);
        var nr = remediation("n1", Phase.Draining, "", fault(XID, T0));

        assertThat(decide(node, nr, minutes(60)).action()).isEqualTo(Action.NONE);
    }

    @Test
    void aNewFaultOnACordonedNodeIsStillRecorded() {
        var node = node("n1", NVLINK, "True", minutes(3));
        var nr = remediation("n1", Phase.Cordoned, "", fault(XID, T0));

        var d = decide(node, nr, minutes(3));

        assertThat(d.action()).isEqualTo(Action.UPDATE);
        assertThat(d.faults()).hasSize(2);
        assertThat(d.message()).isNull();                  // leave the message alone
    }
}
