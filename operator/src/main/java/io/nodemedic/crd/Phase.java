package io.nodemedic.crd;

// Healthy = no NodeRemediation exists, so it isn't a phase.
public enum Phase {
    Suspect,
    Cordoned,
    SpareActivated,
    Draining,
    Quarantined,
    DrainStalled
}
