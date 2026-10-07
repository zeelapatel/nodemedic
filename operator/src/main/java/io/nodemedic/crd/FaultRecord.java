package io.nodemedic.crd;

public class FaultRecord {

    private String type;                            // node condition type, e.g. "GpuXidError"
    private String at;                              // RFC 3339 timestamp, e.g. "2026-10-05T14:03:00Z"

    public FaultRecord() {
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getAt() {
        return at;
    }

    public void setAt(String at) {
        this.at = at;
    }
}
