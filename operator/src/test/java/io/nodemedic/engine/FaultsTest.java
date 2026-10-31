package io.nodemedic.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.fabric8.kubernetes.api.model.NodeBuilder;
import io.nodemedic.crd.Severity;
import io.nodemedic.crd.Signal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class FaultsTest {

    private static final String T1 = "2026-10-20T22:00:00Z";
    private static final String T2 = "2026-10-20T22:05:00Z";

    private static Signal signal(String type, Severity severity) {
        var s = new Signal();
        s.setConditionType(type);
        s.setSeverity(severity);
        return s;
    }

    private static final List<Signal> SIGNALS = List.of(
            signal("GpuXidError", Severity.Critical),
            signal("NvlinkDegraded", Severity.Degraded));

    @Test
    void trueConditionOfAConfiguredSignalIsAFault() {
        var node = TestNodes.withConditions("GpuXidError", "True", T1);

        assertThat(Faults.activeFaults(node, SIGNALS)).containsExactly(
                new ActiveFault("GpuXidError", Severity.Critical, Instant.parse(T1)));
    }

    @Test
    void severityComesFromTheSignal() {
        var node = TestNodes.withConditions("NvlinkDegraded", "True", T1);

        assertThat(Faults.activeFaults(node, SIGNALS)).extracting(ActiveFault::severity)
                .containsExactly(Severity.Degraded);
    }

    @Test
    void falseAndUnknownStatusAreNotFaults() {
        var node = TestNodes.withConditions("GpuXidError", "False", T1, "NvlinkDegraded", "Unknown", T1);

        assertThat(Faults.activeFaults(node, SIGNALS)).isEmpty();
    }

    @Test
    void conditionsThatAreNotConfiguredSignalsAreIgnored() {
        var node = TestNodes.withConditions("Ready", "True", T1, "MemoryPressure", "True", T1);

        assertThat(Faults.activeFaults(node, SIGNALS)).isEmpty();
    }

    @Test
    void conditionWithoutAUsableTimeIsSkipped() {
        var node = TestNodes.withConditions("GpuXidError", "True", "not-a-time");

        assertThat(Faults.activeFaults(node, SIGNALS)).isEmpty();
    }

    @Test
    void timeWithAnOffsetIsAccepted() {
        var node = TestNodes.withConditions("GpuXidError", "True", "2026-10-20T15:00:00-07:00");

        assertThat(Faults.activeFaults(node, SIGNALS)).extracting(ActiveFault::at)
                .containsExactly(Instant.parse(T1));
    }

    @Test
    void resultIsSortedByType() {
        var node = TestNodes.withConditions("NvlinkDegraded", "True", T2, "GpuXidError", "True", T1);

        assertThat(Faults.activeFaults(node, SIGNALS)).extracting(ActiveFault::type)
                .containsExactly("GpuXidError", "NvlinkDegraded");
    }

    @Test
    void nodeWithoutStatusOrNullInputsGivesNoFaults() {
        var bare = new NodeBuilder().withNewMetadata().withName("bare").endMetadata().build();

        assertThat(Faults.activeFaults(bare, SIGNALS)).isEmpty();
        assertThat(Faults.activeFaults(null, SIGNALS)).isEmpty();
        assertThat(Faults.activeFaults(TestNodes.withConditions("GpuXidError", "True", T1), null)).isEmpty();
    }
}
