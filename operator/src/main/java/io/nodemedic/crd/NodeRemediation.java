package io.nodemedic.crd;

import io.fabric8.crd.generator.annotation.AdditionalPrinterColumn;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.model.annotation.Group;
import io.fabric8.kubernetes.model.annotation.ShortNames;
import io.fabric8.kubernetes.model.annotation.Version;

@Group("nodemedic.io")
@Version("v1alpha1")
@ShortNames("nr")
@AdditionalPrinterColumn(name = "Age", jsonPath = ".metadata.creationTimestamp",
                         type = AdditionalPrinterColumn.Type.DATE)
public class NodeRemediation extends CustomResource<NodeRemediationSpec, NodeRemediationStatus> {}
