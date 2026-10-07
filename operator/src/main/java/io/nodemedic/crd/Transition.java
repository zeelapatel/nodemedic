package io.nodemedic.crd;

public class Transition {

    private Phase phase;                            // phase entered
    private String at;                              // RFC 3339 timestamp

    public Transition() {
    }

    public Phase getPhase() {
        return phase;
    }

    public void setPhase(Phase phase) {
        this.phase = phase;
    }

    public String getAt() {
        return at;
    }

    public void setAt(String at) {
        this.at = at;
    }
}
