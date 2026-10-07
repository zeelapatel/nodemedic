package io.nodemedic.crd;

public class RemediationPolicyStatus {

    private int activeRemediations;
    private int sparesAvailable;
    private Long observedGeneration;

    public RemediationPolicyStatus() {
    }

    public int getActiveRemediations() {
        return activeRemediations;
    }

    public void setActiveRemediations(int activeRemediations) {
        this.activeRemediations = activeRemediations;
    }

    public int getSparesAvailable() {
        return sparesAvailable;
    }

    public void setSparesAvailable(int sparesAvailable) {
        this.sparesAvailable = sparesAvailable;
    }

    public Long getObservedGeneration() {
        return observedGeneration;
    }

    public void setObservedGeneration(Long observedGeneration) {
        this.observedGeneration = observedGeneration;
    }
}
