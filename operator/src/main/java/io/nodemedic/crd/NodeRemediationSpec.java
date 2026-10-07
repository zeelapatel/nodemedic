package io.nodemedic.crd;

import io.fabric8.crd.generator.annotation.PrinterColumn;

public class NodeRemediationSpec {

    @PrinterColumn(name = "Node")
    private String nodeName;                        // the faulty node; also the CR's name
    private String policyRef;                       // name of the RemediationPolicy that admitted it

    public NodeRemediationSpec() {
    }

    public String getNodeName() {
        return nodeName;
    }

    public void setNodeName(String nodeName) {
        this.nodeName = nodeName;
    }

    public String getPolicyRef() {
        return policyRef;
    }

    public void setPolicyRef(String policyRef) {
        this.policyRef = policyRef;
    }
}
