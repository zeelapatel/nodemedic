package io.nodemedic.crd;

import io.fabric8.crd.generator.annotation.PrinterColumn;

import java.util.List;

public class NodeRemediationStatus {

    @PrinterColumn(name = "Phase")
    private Phase phase;
    private List<FaultRecord> faults;               // capped at 20 in Phase 7
    @PrinterColumn(name = "Spare")
    private String spareNode;                       // spare activated to replace this node
    private List<Transition> transitions;           // phase history
    private String message;                         // human-readable detail, e.g. why a drain stalled

    public NodeRemediationStatus() {
    }

    public Phase getPhase() {
        return phase;
    }

    public void setPhase(Phase phase) {
        this.phase = phase;
    }

    public List<FaultRecord> getFaults() {
        return faults;
    }

    public void setFaults(List<FaultRecord> faults) {
        this.faults = faults;
    }

    public String getSpareNode() {
        return spareNode;
    }

    public void setSpareNode(String spareNode) {
        this.spareNode = spareNode;
    }

    public List<Transition> getTransitions() {
        return transitions;
    }

    public void setTransitions(List<Transition> transitions) {
        this.transitions = transitions;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
