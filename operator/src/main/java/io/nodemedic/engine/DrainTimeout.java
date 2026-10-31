package io.nodemedic.engine;

import io.nodemedic.crd.NodeRemediation;
import io.nodemedic.crd.Phase;
import io.nodemedic.crd.Transition;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/** Pure clock logic for "has this drain run too long". The start time comes from the status history. */
public final class DrainTimeout {

    private DrainTimeout() {
    }

    /** When the remediation last entered Draining, from {@code status.transitions}. */
    public static Optional<Instant> enteredDraining(NodeRemediation remediation) {
        if (remediation.getStatus() == null || remediation.getStatus().getTransitions() == null) {
            return Optional.empty();
        }
        Instant latest = null;
        for (Transition t : remediation.getStatus().getTransitions()) {
            if (t.getPhase() != Phase.Draining || t.getAt() == null) {
                continue;
            }
            try {
                Instant at = OffsetDateTime.parse(t.getAt()).toInstant();
                if (latest == null || at.isAfter(latest)) {
                    latest = at;
                }
            } catch (DateTimeParseException ignored) {
                // an unreadable entry is skipped
            }
        }
        return Optional.ofNullable(latest);
    }

    /** True once the drain has run for at least {@code timeout}. No timeout or no start means never. */
    public static boolean exceeded(NodeRemediation remediation, Optional<Duration> timeout, Instant now) {
        if (timeout.isEmpty()) {
            return false;
        }
        return enteredDraining(remediation)
                .map(start -> !now.isBefore(start.plus(timeout.get())))
                .orElse(false);
    }
}
