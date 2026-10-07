package io.nodemedic.crd;

import java.util.List;
import java.util.Map;

public class RemediationPolicySpec {

    private Map<String, String> nodeSelector;      // {nodemedic.io/pool: gpu}
    private List<Signal> signals;
    private Threshold threshold;
    private String cooldown;                        // Go-style duration, e.g. "15m"
    private int maxConcurrentRemediations;
    private String drainTimeout;                    // Go-style duration, e.g. "10m"
    private Map<String, String> spareSelector;      // {nodemedic.io/role: spare}

    public RemediationPolicySpec() {
    }

    public Map<String, String> getNodeSelector() {
        return nodeSelector;
    }

    public void setNodeSelector(Map<String, String> nodeSelector) {
        this.nodeSelector = nodeSelector;
    }

    public List<Signal> getSignals() {
        return signals;
    }

    public void setSignals(List<Signal> signals) {
        this.signals = signals;
    }

    public Threshold getThreshold() {
        return threshold;
    }

    public void setThreshold(Threshold threshold) {
        this.threshold = threshold;
    }

    public String getCooldown() {
        return cooldown;
    }

    public void setCooldown(String cooldown) {
        this.cooldown = cooldown;
    }

    public int getMaxConcurrentRemediations() {
        return maxConcurrentRemediations;
    }

    public void setMaxConcurrentRemediations(int maxConcurrentRemediations) {
        this.maxConcurrentRemediations = maxConcurrentRemediations;
    }

    public String getDrainTimeout() {
        return drainTimeout;
    }

    public void setDrainTimeout(String drainTimeout) {
        this.drainTimeout = drainTimeout;
    }

    public Map<String, String> getSpareSelector() {
        return spareSelector;
    }

    public void setSpareSelector(Map<String, String> spareSelector) {
        this.spareSelector = spareSelector;
    }

    /** Degraded signals admit a node after {@code faults} faults within {@code window}. */
    public static class Threshold {

        private int faults;                         // e.g. 3
        private String window;                      // Go-style duration, e.g. "10m"

        public int getFaults() {
            return faults;
        }

        public void setFaults(int faults) {
            this.faults = faults;
        }

        public String getWindow() {
            return window;
        }

        public void setWindow(String window) {
            this.window = window;
        }
    }
}
