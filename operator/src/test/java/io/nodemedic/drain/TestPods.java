package io.nodemedic.drain;

import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;

import java.util.Map;

/** Builders for test pods. */
final class TestPods {

    private TestPods() {
    }

    /** A running pod owned by a ReplicaSet controller: the ordinary evictable case. */
    static PodBuilder running(String name) {
        return new PodBuilder()
                .withNewMetadata()
                    .withNamespace("default").withName(name).withLabels(Map.of("app", "web"))
                    .withOwnerReferences(new OwnerReferenceBuilder()
                            .withKind("ReplicaSet").withName("web-rs").withController(true).build())
                .endMetadata()
                .withNewSpec().withNodeName("n1").endSpec()
                .withNewStatus().withPhase("Running").endStatus();
    }

    static Pod ordinary(String name) {
        return running(name).build();
    }

    static Pod daemonSet(String name) {
        return running(name).editMetadata().withOwnerReferences(new OwnerReferenceBuilder()
                .withKind("DaemonSet").withName("ds").withController(true).build()).endMetadata().build();
    }

    static Pod mirror(String name) {
        return running(name).editMetadata().withOwnerReferences()
                .withAnnotations(Map.of("kubernetes.io/config.mirror", "abc")).endMetadata().build();
    }

    static Pod finished(String phase, String name) {
        return running(name).editStatus().withPhase(phase).endStatus().build();
    }

    static Pod terminating(String name) {
        return running(name).editMetadata().withDeletionTimestamp("2026-10-20T12:00:00Z").endMetadata().build();
    }

    static Pod bare(String name) {
        return running(name).editMetadata().withOwnerReferences().endMetadata().build();
    }
}
