package io.nodemedic.drain;

import static io.nodemedic.drain.PodFilter.Kind.BARE;
import static io.nodemedic.drain.PodFilter.Kind.EVICT;
import static io.nodemedic.drain.PodFilter.Kind.IGNORED;
import static io.nodemedic.drain.PodFilter.Kind.TERMINATING;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PodFilterTest {

    @Test
    void podWithAControllerIsEvicted() {
        assertThat(PodFilter.classify(TestPods.ordinary("p"))).isEqualTo(EVICT);
    }

    @Test
    void daemonSetPodsAreIgnored() {
        assertThat(PodFilter.classify(TestPods.daemonSet("p"))).isEqualTo(IGNORED);
    }

    @Test
    void mirrorPodsAreIgnored() {
        assertThat(PodFilter.classify(TestPods.mirror("p"))).isEqualTo(IGNORED);
    }

    @Test
    void finishedPodsAreIgnored() {
        assertThat(PodFilter.classify(TestPods.finished("Succeeded", "p"))).isEqualTo(IGNORED);
        assertThat(PodFilter.classify(TestPods.finished("Failed", "p"))).isEqualTo(IGNORED);
    }

    @Test
    void podsAlreadyShuttingDownAreWaitedFor() {
        assertThat(PodFilter.classify(TestPods.terminating("p"))).isEqualTo(TERMINATING);
    }

    @Test
    void podWithoutAControllerIsNotEvicted() {
        assertThat(PodFilter.classify(TestPods.bare("p"))).isEqualTo(BARE);
    }

    @Test
    void aTerminatingDaemonSetPodIsStillIgnored() {
        var pod = TestPods.running("p").editMetadata()
                .withOwnerReferences(new io.fabric8.kubernetes.api.model.OwnerReferenceBuilder()
                        .withKind("DaemonSet").withName("ds").withController(true).build())
                .withDeletionTimestamp("2026-10-20T12:00:00Z").endMetadata().build();
        assertThat(PodFilter.classify(pod)).isEqualTo(IGNORED);
    }

    @Test
    void anOwnerThatIsNotTheControllerDoesNotCount() {
        var pod = TestPods.running("p").editMetadata()
                .withOwnerReferences(new io.fabric8.kubernetes.api.model.OwnerReferenceBuilder()
                        .withKind("ConfigMap").withName("cm").withController(false).build())
                .endMetadata().build();
        assertThat(PodFilter.classify(pod)).isEqualTo(BARE);
    }
}
