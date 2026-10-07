package io.nodemedic.crd;

import io.fabric8.generator.annotation.Required;

public class Signal {
    
    @Required private String conditionType;   // "GpuXidError"
    @Required private Severity severity;      // Critical | Degraded

    public Signal() {
    }

    public String getConditionType() {
        return conditionType;
    }

    public void setConditionType(String conditionType) {
        this.conditionType = conditionType;
    }

    public Severity getSeverity() {
        return severity;
    }

    public void setSeverity(Severity severity) {
        this.severity = severity;
    }
}
