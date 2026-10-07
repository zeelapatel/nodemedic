package io.nodemedic.crd;

import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.model.annotation.Group;
import io.fabric8.kubernetes.model.annotation.ShortNames;
import io.fabric8.kubernetes.model.annotation.Version;

@Group("nodemedic.io")
@Version("v1alpha1")
@ShortNames("rp")
public class RemediationPolicy extends CustomResource<RemediationPolicySpec, RemediationPolicyStatus> {

}
