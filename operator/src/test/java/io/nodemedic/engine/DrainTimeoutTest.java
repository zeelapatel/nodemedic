package io.nodemedic.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.nodemedic.crd.NodeRemediation;
import io.nodemedic.crd.NodeRemediationStatus;
import io.nodemedic.crd.Phase;
import io.nodemedic.crd.Transition;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class DrainTimeoutTest {

    private static final Instant T0 = Instant.parse("2026-10-20T12:00:00Z");
    private static final Optional<Duration> TEN_MIN = Optional.of(Duration.ofMinutes(10));

    private static Transition transition(Phase phase, Instant at) {
        var t = new Transition();
        t.setPhase(phase);
        t.setAt(at.toString());
        return t;
    }

    private static NodeRemediation withTransitions(Transition... transitions) {
        var status = new NodeRemediationStatus();
        status.setTransitions(new ArrayList<>(java.util.List.of(transitions)));
        var nr = new NodeRemediation();
        nr.setStatus(status);
        return nr;
    }

    @Test
    void startIsWhenItEnteredDraining() {
        var nr = withTransitions(transition(Phase.Suspect, T0), transition(Phase.Draining, T0.plusSeconds(60)));

        assertThat(DrainTimeout.enteredDraining(nr)).contains(T0.plusSeconds(60));
    }

    @Test
    void notExceededBeforeTheTimeout() {
        var nr = withTransitions(transition(Phase.Draining, T0));

        assertThat(DrainTimeout.exceeded(nr, TEN_MIN, T0.plus(Duration.ofMinutes(9)))).isFalse();
    }

    @Test
    void exceededExactlyAtTheTimeout() {
        var nr = withTransitions(transition(Phase.Draining, T0));

        assertThat(DrainTimeout.exceeded(nr, TEN_MIN, T0.plus(Duration.ofMinutes(10)))).isTrue();
    }

    @Test
    void neverExceededWithoutATimeout() {
        var nr = withTransitions(transition(Phase.Draining, T0));

        assertThat(DrainTimeout.exceeded(nr, Optional.empty(), T0.plus(Duration.ofDays(30)))).isFalse();
    }

    @Test
    void neverExceededWhenItNeverEnteredDraining() {
        var nr = withTransitions(transition(Phase.Cordoned, T0));

        assertThat(DrainTimeout.exceeded(nr, TEN_MIN, T0.plus(Duration.ofDays(1)))).isFalse();
    }

    @Test
    void aCrWithoutAnyStatusIsNeverExceeded() {
        assertThat(DrainTimeout.exceeded(new NodeRemediation(), TEN_MIN, T0.plus(Duration.ofDays(1)))).isFalse();
    }
}
